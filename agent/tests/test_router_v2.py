"""v2 入口路由与工具链路。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.agent.graph import build_graph
from app.agent.router import Route, guess_route, route_message
from app.models import TaskDraft


def test_rules_route_common_phrases():
    assert guess_route("我还有多少钱") is Route.QUERY
    assert guess_route("余额和信用分") is Route.QUERY
    assert guess_route("帮我接两单") is Route.GRAB
    assert guess_route("把能领的券都领了") is Route.COUPON
    # 发单说法不该被当成查询
    assert guess_route("帮我取个快递") is None
    assert guess_route("外卖有汤别撒了") is None


def test_ready_draft_keeps_ambiguous_question_in_task():
    """草稿已就绪时，"现在多少钱"是在问这一单，不是查余额。"""
    ready = TaskDraft(type="DELIVERY", title="取快递", location_from="A", location_to="B", reward_cents=500)
    assert route_message("现在多少钱", draft=ready) is Route.TASK
    # 明确指向账户才走查询
    assert route_message("我还有多少钱", draft=ready) is Route.QUERY


def test_route_without_draft_still_queries():
    assert route_message("我还有多少钱") is Route.QUERY


class EchoExtractor(Extractor):
    def extract(self, message, draft, history=None):
        return {}


class FakeToolLoop:
    """假工具循环：记录调用参数，返回固定回答。"""

    def __init__(self) -> None:
        self.seen: list[tuple[str, str]] = []
        self.routes: list[str] = []

    def run(self, message, token, history=None, route="query"):
        self.seen.append((message, token))
        self.routes.append(route)
        return {"ok": True, "reply": f"余额 995 元（{message}）", "calls": [{"name": "get_wallet"}]}


def test_query_goes_through_tool_node():
    loop = FakeToolLoop()
    graph = build_graph(EchoExtractor(), tool_loop=loop)
    state = graph.invoke({
        "message": "我还有多少钱",
        "draft": TaskDraft(),
        "missing": [],
        "history": [],
        "user_token": "tok-1",
    })

    assert state["status"] == "ANSWER"
    assert "995" in state["answer"]
    assert state["tool_calls"][0]["name"] == "get_wallet"
    assert loop.seen == [("我还有多少钱", "tok-1")]


def test_task_message_does_not_enter_tool_node():
    loop = FakeToolLoop()
    graph = build_graph(EchoExtractor(), tool_loop=loop)
    state = graph.invoke({
        "message": "帮我取个快递",
        "draft": TaskDraft(),
        "missing": [],
        "history": [],
        "user_token": "tok-1",
    })

    assert state["status"] != "ANSWER"
    assert loop.seen == []


def test_parse_grab_count():
    """接单数量：中文/数字都认，未给默认 1，上限 5。"""
    from app.tools.tool_loop import parse_grab_count

    assert parse_grab_count("帮我接两单") == 2
    assert parse_grab_count("接 3 单") == 3
    assert parse_grab_count("接五单") == 5
    assert parse_grab_count("帮我接单") == 1
    assert parse_grab_count("接 10 单") == 5


def test_fallback_plan_by_route():
    """模型不可用时的确定性计划：领券/接单必须能落地。"""
    from app.tools.tool_loop import fallback_plan

    assert [name for name, _ in fallback_plan("coupon", "把能领的券都领了")] == ["claim_all_vouchers"]
    plan = fallback_plan("grab", "帮我接两单")
    assert plan[0][0] == "grab_best" and plan[0][1]["count"] == 2
    names = [name for name, _ in fallback_plan("query", "我余额和信用分多少")]
    assert "get_wallet" in names and "get_credit" in names


def test_route_tools_are_filtered():
    """按路由只挂相关工具，避免 schema 太大拖慢模型。"""
    from app.tools.tool_loop import ROUTE_TOOLS

    assert "get_wallet" in ROUTE_TOOLS["query"]
    assert ROUTE_TOOLS["coupon"] == ("list_claimable_vouchers", "claim_all_vouchers")
    assert "publish_errand" not in ROUTE_TOOLS["query"]


def test_claim_all_counts_already_claimed_as_skipped(monkeypatch):
    """已经领过的券不算失败，避免回答里出现"失败 15 张"。"""
    from mcp_server import tools

    monkeypatch.setattr(tools, "list_claimable_vouchers", lambda token: {
        "ok": True,
        "count": 2,
        "items": [
            {"type": 1, "description": "满10减2"},
            {"type": 2, "description": "满20减5"},
        ],
    })

    def fake_claim(token, voucher_type):
        if voucher_type == 1:
            return {"ok": True, "data": {}}
        return {"ok": False, "error": "该券已领取"}

    monkeypatch.setattr(tools, "claim_voucher", fake_claim)
    result = tools.claim_all_vouchers("tok")

    assert result["claimed_count"] == 1
    assert result["skipped_count"] == 1
    assert result["failed"] == []


def test_list_square_skips_own_errands(monkeypatch):
    """广场列表要过滤掉自己发布的单，否则 agent 会接自己的单。"""
    from mcp_server import tools

    monkeypatch.setattr(tools, "get_me", lambda token: {"ok": True, "user_id": "me-1"})
    monkeypatch.setattr(
        tools,
        "_get",
        lambda path, token, params=None: {
            "ok": True,
            "data": [
                {"id": "1", "title": "别人的单", "rewardCents": "2000", "status": "PUBLISHED", "publisherId": "other"},
                {"id": "2", "title": "我的单", "rewardCents": "5000", "status": "PUBLISHED", "publisherId": "me-1"},
                {"id": "3", "title": "已接走", "rewardCents": "9000", "status": "TAKEN", "publisherId": "other"},
            ],
        },
    )

    result = tools.list_square_errands("tok")

    assert result["count"] == 1
    assert result["items"][0]["title"] == "别人的单"
    assert result["skipped_own"] == 1


def test_grab_refuses_own_errand(monkeypatch):
    """即使模型直接点单，也不能接自己的单。"""
    from mcp_server import tools

    monkeypatch.setattr(tools, "get_me", lambda token: {"ok": True, "user_id": "me-1"})
    monkeypatch.setattr(
        tools,
        "_get",
        lambda path, token, params=None: {"ok": True, "data": {"publisherId": "me-1"}},
    )
    called = []
    monkeypatch.setattr(tools, "_post", lambda *a, **k: called.append(a) or {"ok": True})

    result = tools.grab_errand("tok", "2")

    assert result["ok"] is False
    assert "自己" in result["error"]
    assert called == [], "不应该真的调用接单接口"


def test_grab_best_only_picks_others(monkeypatch):
    """批量接单按实付降序，但只接别人的。"""
    from mcp_server import tools

    monkeypatch.setattr(tools, "get_me", lambda token: {"ok": True, "user_id": "me-1"})
    monkeypatch.setattr(
        tools,
        "_get",
        lambda path, token, params=None: {
            "ok": True,
            "data": [
                {"id": "a", "title": "我的大单", "rewardCents": "9000", "status": "PUBLISHED", "publisherId": "me-1"},
                {"id": "b", "title": "别人的小单", "rewardCents": "1000", "status": "PUBLISHED", "publisherId": "other"},
            ],
        },
    )
    grabbed = []

    def fake_grab(token, errand_id):
        detail = {"ok": True, "data": {"publisherId": "other"}}
        return {"ok": True, "data": {}}

    monkeypatch.setattr(tools, "grab_errand", fake_grab)
    result = tools.grab_best("tok", 2)

    assert result["grabbed_count"] == 1
    assert result["grabbed"][0]["title"] == "别人的小单"
    assert result["skipped_own"] == 1


def test_panel_messages_requires_owner(monkeypatch):
    """别的用户拿着 panel_id 也不能读，一律 404。"""
    from fastapi import HTTPException

    import app.main as main

    monkeypatch.setattr(main, "_current_user", lambda authorization: "u-1")
    monkeypatch.setattr(main.service, "panel_owner", lambda panel_id: "u-2")

    try:
        main._require_panel("p-other", "Bearer x")
    except HTTPException as exc:
        assert exc.status_code == 404
    else:
        raise AssertionError("应当拒绝非本人面板")


def test_panel_messages_passes_user_to_store():
    """查询时把 user_id 传下去，SQL 层按用户过滤。"""
    from app.service import ChatService

    seen = {}

    class Store:
        def panel_messages(self, panel_id, limit=200, before_id=None, user_id=None):
            seen["user_id"] = user_id
            seen["panel_id"] = panel_id
            return []

    service = ChatService(store=Store())
    service.panel_messages("p-1", user_id="u-1")
    assert seen == {"user_id": "u-1", "panel_id": "p-1"}


def test_history_query_is_scoped_to_panel_and_user():
    """历史只按当前用户 + 当前面板取，不会串到别人的对话。"""
    from app.service import ChatService

    class Store:
        def __init__(self):
            self.rows = []

        def panel_messages(self, panel_id, limit=200, before_id=None, user_id=None):
            return [row for row in self.rows if row["user"] == user_id and row["panel"] == panel_id]

    store = Store()
    store.rows = [
        {"user": "u-1", "panel": "p-1", "content": "我的"},
        {"user": "u-2", "panel": "p-1", "content": "别人的"},
    ]
    service = ChatService(store=store)

    mine = service.panel_messages("p-1", user_id="u-1")
    assert [row["content"] for row in mine] == ["我的"]


def test_router_distinguishes_cancel_order_from_draft():
    """只有"取消某个已发布的单"才走取消链路；草稿阶段的"取消"仍归发单。"""
    from app.agent.router import Route, route_message
    from app.models import TaskDraft

    empty = TaskDraft()
    assert route_message("取消西区东门菜鸟驿站那一单", draft=empty) is Route.CANCEL
    assert route_message("把我昨天发的订单取消掉", draft=empty) is Route.CANCEL

    # 草稿里有内容时，"取消"是丢草稿，不是删已发布的单
    drafting = TaskDraft(type="DELIVERY", reward_cents=800, location_from="东门")
    assert route_message("取消", draft=drafting) is Route.TASK


def test_cancel_errand_refuses_wrong_status(monkeypatch):
    """硬约束：只有 PUBLISHED 能取消，被接走/已完成一律拒绝。"""
    from mcp_server import tools

    monkeypatch.setattr(tools, "get_me", lambda token: {"ok": True, "user_id": "me-1"})
    monkeypatch.setattr(
        tools,
        "_get",
        lambda path, token, params=None: {
            "ok": True,
            "data": {"publisherId": "me-1", "status": "LOCKED", "title": "取快递"},
        },
    )
    called = []
    monkeypatch.setattr(tools, "_post", lambda *a, **k: called.append(a) or {"ok": True})

    result = tools.cancel_errand("tok", "123")

    assert result["ok"] is False
    assert "PUBLISHED" in result["error"]
    assert called == [], "不该调用取消接口"


def test_cancel_errand_refuses_others_order(monkeypatch):
    """别人的单不能取消。"""
    from mcp_server import tools

    monkeypatch.setattr(tools, "get_me", lambda token: {"ok": True, "user_id": "me-1"})
    monkeypatch.setattr(
        tools,
        "_get",
        lambda path, token, params=None: {
            "ok": True,
            "data": {"publisherId": "other", "status": "PUBLISHED", "title": "取快递"},
        },
    )
    called = []
    monkeypatch.setattr(tools, "_post", lambda *a, **k: called.append(a) or {"ok": True})

    result = tools.cancel_errand("tok", "123")

    assert result["ok"] is False
    assert "不是你发布" in result["error"]
    assert called == []


def test_cancel_errand_success(monkeypatch):
    from mcp_server import tools

    monkeypatch.setattr(tools, "get_me", lambda token: {"ok": True, "user_id": "me-1"})
    monkeypatch.setattr(
        tools,
        "_get",
        lambda path, token, params=None: {
            "ok": True,
            "data": {"publisherId": "me-1", "status": "PUBLISHED", "title": "取快递", "rewardCents": "800"},
        },
    )
    monkeypatch.setattr(tools, "_post", lambda *a, **k: {"ok": True, "data": {"result": "REFUNDED"}})

    result = tools.cancel_errand("tok", "123")

    assert result["ok"] is True
    assert result["refund"] == "REFUNDED"
    assert result["reward_yuan"] == 8.0


def test_list_my_errands_maps_phase_and_time(monkeypatch):
    """phase/时间条件要变成后端认识的 status 与 ISO 参数。"""
    from mcp_server import tools

    seen = {}

    def fake_get(path, token, params=None):
        seen.update(params or {})
        return {"ok": True, "data": []}

    monkeypatch.setattr(tools, "_get", fake_get)
    tools.list_my_errands("tok", role="grabbed", phase="active", expired_before="2026-11-02T00:00:00Z")

    assert seen["role"] == "GRABBED_BY_ME"
    assert "PUBLISHED" in seen["status"] and "DELIVERED" in seen["status"]
    assert "SETTLED" not in seen["status"]
    assert seen["expiredTo"] == "2026-11-02T00:00:00Z"


def test_phase_sets_cover_before_and_after_settle():
    from mcp_server.tools import PHASE_STATUSES

    active = set(PHASE_STATUSES["active"].split(","))
    done = set(PHASE_STATUSES["done"].split(","))
    assert "SETTLED" not in active and "SETTLED" in done
    assert {"PUBLISHED", "LOCKED", "ACCEPTED", "PICKED_UP"} <= active


def test_parse_dsml_tool_calls():
    """模型没走 tool_calls、把调用写进正文时，要能自己捞出来执行。"""
    from app.tools.tool_loop import _parse_dsml, _strip_dsml

    B = "\uff5c\uff5cDSML\uff5c\uff5c"
    sample = (
        f"<{B} calls>\n"
        f"<{B} invoke name=\"cancel_errand\">\n"
        f"<{B} parameter name=\"errand_id\" string=\"true\">242618605273878528</{B} parameter>\n"
        f"</{B} invoke>\n"
        f"</{B} calls>"
    )

    calls = _parse_dsml(sample)
    assert len(calls) == 1
    assert calls[0]["function"]["name"] == "cancel_errand"
    assert "242618605273878528" in calls[0]["function"]["arguments"]
    # 正文不能把内部标记和参数值漏给用户
    assert _strip_dsml(sample) == ""


def test_multi_step_tool_rounds_keep_tools_available():
    """ReAct 两步（先查再取消）：除最后一轮外都要继续带 tools。"""
    import inspect

    from app.tools import tool_loop

    source = inspect.getsource(tool_loop.ToolLoop.run)
    assert "round_index == MAX_ROUNDS - 1" in source


def test_other_users_panel_cannot_be_written():
    """拿到别人的 panel_id 也写不进去：面板 id 不是凭证。"""
    import pytest

    from app.agent.extractor import Extractor
    from app.service import ChatService

    class Noop(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    service = ChatService(extractor=Noop())
    session = service.store.create(user_id="user-a", panel_id="p-a")
    service.store.save(session)

    with pytest.raises(PermissionError):
        service.chat_panel("p-a", "帮我取快递", user_id="user-b", user_token="tok-b")


def test_other_users_session_cannot_be_written():
    import pytest

    from app.agent.extractor import Extractor
    from app.service import ChatService

    class Noop(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    service = ChatService(extractor=Noop())
    session = service.store.create(user_id="user-a", panel_id="p-a")
    service.store.save(session)

    with pytest.raises(PermissionError):
        service.chat(session.id, "帮我取快递", "tok-b", user_id="user-b")


def test_owner_can_keep_talking():
    from app.agent.extractor import Extractor
    from app.service import ChatService

    class Noop(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    service = ChatService(extractor=Noop())
    session = service.store.create(user_id="user-a", panel_id="p-a")
    service.store.save(session)

    reply = service.chat_panel("p-a", "帮我取快递", user_id="user-a", user_token="")
    assert reply["status"] in ("ASKING", "READY")


def test_model_cannot_override_user_token():
    """模型即使"照抄"或伪造 user_token，也会被真实 token 覆盖。"""
    from app.tools.mcp_client import MCPClient

    payload = MCPClient.build_payload(
        {"user_token": "attacker-token", "other_user_id": "u-2", "title": "x"},
        "real-token",
    )
    assert payload["user_token"] == "real-token"
    assert payload["title"] == "x"

    # 工具 schema 里也不会暴露 user_token，模型根本不知道该填这个参数
    schema = MCPClient._to_schema(
        type("T", (), {"name": "publish_errand", "description": "",
                       "inputSchema": {"type": "object",
                                       "properties": {"user_token": {"type": "string"},
                                                     "title": {"type": "string"}},
                                       "required": ["user_token", "title"]}})()
    )
    assert "user_token" not in schema["function"]["parameters"]["properties"]
    assert schema["function"]["parameters"]["required"] == ["title"]


def test_router_detects_settle_intent():
    from app.agent.router import Route, route_message

    assert route_message("帮我结算订单") is Route.SETTLE
    assert route_message("把送达的单都确认收货吧") is Route.SETTLE
    # 发单说法不能误判
    assert route_message("帮我取个快递") is not Route.SETTLE


def test_settle_only_touches_delivered(monkeypatch):
    """只结算自己发布的、状态为 DELIVERED 的单；待接单/进行中的单不能碰。"""
    from mcp_server import tools

    monkeypatch.setattr(
        tools,
        "list_my_errands",
        lambda token, **kw: {
            "ok": True,
            "items": [
                {"errand_id": "a", "title": "已送达", "status": "DELIVERED", "reward_yuan": 8.0},
                {"errand_id": "b", "title": "待接单", "status": "PUBLISHED", "reward_yuan": 5.0},
                {"errand_id": "c", "title": "进行中", "status": "PICKED_UP", "reward_yuan": 6.0},
            ],
        },
    )
    settled = []

    def fake_post(path, token, body=None):
        settled.append(path)
        return {"ok": True, "data": {"result": "SETTLED"}}

    monkeypatch.setattr(tools, "_post", fake_post)
    result = tools.settle_my_errands("tok")

    assert result["settled_count"] == 1
    assert result["settled"][0]["errand_id"] == "a"
    assert settled == ["/api/errands/a/settle"]


def test_settle_reports_nothing_to_do(monkeypatch):
    from mcp_server import tools

    monkeypatch.setattr(
        tools,
        "list_my_errands",
        lambda token, **kw: {"ok": True, "items": [
            {"errand_id": "b", "title": "待接单", "status": "PUBLISHED", "reward_yuan": 5.0},
        ]},
    )
    called = []
    monkeypatch.setattr(tools, "_post", lambda *a, **k: called.append(a) or {"ok": True})

    result = tools.settle_my_errands("tok")

    assert result["settled_count"] == 0
    assert "没有待确认" in result["note"]
    assert called == []


def test_settle_reports_partial_failure(monkeypatch):
    from mcp_server import tools

    monkeypatch.setattr(
        tools,
        "list_my_errands",
        lambda token, **kw: {"ok": True, "items": [
            {"errand_id": "a", "title": "好单", "status": "DELIVERED", "reward_yuan": 8.0},
            {"errand_id": "b", "title": "坏单", "status": "DELIVERED", "reward_yuan": 9.0},
        ]},
    )

    def fake_post(path, token, body=None):
        if "/a/" in path:
            return {"ok": True, "data": {}}
        return {"ok": False, "error": "资金不足"}

    monkeypatch.setattr(tools, "_post", fake_post)
    result = tools.settle_my_errands("tok")

    assert result["settled_count"] == 1
    assert len(result["failed"]) == 1
    assert result["failed"][0]["error"] == "资金不足"


def test_normalize_expired_at_rolls_past_time():
    """过去的时刻顺延一天；太早的直接丢弃；未来的原样保留。"""
    from datetime import datetime

    from app.models import normalize_expired_at

    now = datetime(2026, 11, 1, 20, 46)

    rolled, status = normalize_expired_at(datetime(2026, 11, 1, 1, 0), now)
    assert status == "rolled"
    assert rolled == datetime(2026, 11, 2, 1, 0)

    future, status = normalize_expired_at(datetime(2026, 11, 2, 12, 0), now)
    assert status is None and future == datetime(2026, 11, 2, 12, 0)

    dropped, status = normalize_expired_at(datetime(2026, 10, 1, 1, 0), now)
    assert status == "dropped" and dropped is None


def test_merge_draft_never_stores_past_deadline():
    """任何来源的过期时间都进不了草稿。"""
    from datetime import datetime, timedelta

    from app.models import TaskDraft, merge_draft

    draft = TaskDraft(type="DELIVERY", title="取快递")
    merged = merge_draft(draft, {"expired_at": datetime.now() - timedelta(hours=3)})

    assert merged.expired_at is not None
    assert merged.expired_at > datetime.now()

    very_old = merge_draft(draft, {"expired_at": datetime.now() - timedelta(days=10)})
    assert very_old.expired_at is None


def test_guard_deadline_tells_user_about_roll():
    """纠正后要给用户一句话，不能说改就改。"""
    from datetime import datetime, timedelta

    from app.agent.graph import _guard_deadline

    patch = {"expired_at": datetime.now() - timedelta(hours=2)}
    note = _guard_deadline(patch)

    assert note and "已经过去" in note
    assert patch["expired_at"] > datetime.now()
