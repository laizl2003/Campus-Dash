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


def test_parse_confirmation():
    from app.service import parse_confirmation

    assert parse_confirmation("是") is True
    assert parse_confirmation("对，是新的一单") is True
    assert parse_confirmation("不是") is False
    assert parse_confirmation("不用，就是改一下") is False
    assert parse_confirmation("帮我取快递") is None


def _service_with_ready_draft():
    from app.agent.extractor import Extractor
    from app.models import TaskDraft
    from app.service import ChatService

    class Noop(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    service = ChatService(extractor=Noop())
    session = service.store.create(user_id="u-1", panel_id="p-1")
    session.draft = TaskDraft(
        type="DELIVERY", title="女生宿舍311送袜子到男生宿舍311",
        location_from="女生宿舍311", location_to="男生宿舍311", reward_cents=1000,
    )
    service.store.save(session)
    return service, session


def test_new_task_asks_before_merging():
    """READY 时描述另一件事：不合并，先问一句。"""
    service, session = _service_with_ready_draft()
    service.graph.invoke = lambda state: {
        **state, "draft": state["draft"], "missing": [], "status": "READY",
        "intent": __import__("app.agent.intent", fromlist=["Intent"]).Intent.NEW_TASK,
        "new_task_hint": "帮我取个快递，8块",
    }

    reply = service._chat(session, "帮我取个快递，8块", "tok")

    assert reply["pending_new_task"] is True
    assert "新的一单" in reply["message"]
    # 草稿一点没动
    assert reply["draft"]["location_from"] == "女生宿舍311"
    assert reply["draft"]["reward_cents"] == 1000
    assert service.store.get(session.id).pending["message"] == "帮我取个快递，8块"


def test_confirmation_yes_starts_fresh_draft():
    """回"是"：清空草稿，用当时那句描述重来。"""
    service, session = _service_with_ready_draft()
    session.pending = {"message": "帮我取个快递，8块"}
    service.store.save(session)

    seen = {}

    def fake_invoke(state):
        seen["draft"] = state["draft"]
        return {**state, "draft": state["draft"], "missing": [], "status": "ASKING",
                "intent": None, "question": "从哪里取货？"}

    service.graph.invoke = fake_invoke
    service._chat(session, "是", "tok")

    # 传给图的是空草稿
    assert seen["draft"].location_from is None
    assert seen["draft"].reward_cents is None
    assert service.store.get(session.id).pending is None


def test_confirmation_no_keeps_draft():
    """回"不"：保留原草稿，把它当成修改。"""
    service, session = _service_with_ready_draft()
    session.pending = {"message": "帮我取个快递，8块"}
    service.store.save(session)

    seen = {}

    def fake_invoke(state):
        seen["draft"] = state["draft"]
        return {**state, "draft": state["draft"], "missing": [], "status": "READY",
                "intent": None}

    service.graph.invoke = fake_invoke
    service._chat(session, "不是", "tok")

    assert seen["draft"].location_from == "女生宿舍311"
    assert seen["draft"].reward_cents == 1000


def test_heuristic_new_task_when_both_locations_change():
    """模型没判新单时的兜底：取货+送达同时换成另一组，就当新单。"""
    from app.agent.graph import _looks_like_new_task
    from app.models import TaskDraft

    draft = TaskDraft(type="DELIVERY", location_from="女生宿舍311", location_to="男生宿舍311")
    assert _looks_like_new_task(draft, {"location_from": "一食堂", "location_to": "5号楼201"})
    # 只改一个地点不算换单
    assert not _looks_like_new_task(draft, {"location_to": "5号楼201"})


def test_confirmation_understands_natural_phrasing():
    """不再只认"是/否"：自然说法交给模型判断。"""
    from app.agent.extractor import Extractor
    from app.service import parse_confirmation

    class Labeler(Extractor):
        def __init__(self, label):
            self.label = label

        def extract(self, message, draft, history=None):
            return {}

        def classify_text(self, system_prompt, user_text):
            return self.label

    new = Labeler("NEW")
    same = Labeler("SAME")
    other = Labeler("OTHER")

    assert parse_confirmation("这是新的一单", extractor=new) is True
    assert parse_confirmation("还是原来那单", extractor=same) is False
    assert parse_confirmation("先看看广场有什么", extractor=other) is None

    # 规则能直接判的，不走模型（extractor 传 None 也行）
    assert parse_confirmation("是") is True
    assert parse_confirmation("不用，就改这个") is False


def test_confirmation_falls_back_when_model_fails():
    from app.agent.extractor import Extractor
    from app.service import parse_confirmation

    class Broken(Extractor):
        def extract(self, message, draft, history=None):
            return {}

        def classify_text(self, system_prompt, user_text):
            raise RuntimeError("模型挂了")

    # 规则判不出来的说法，模型又挂了 -> 不硬猜，交回正常流程
    assert parse_confirmation("说的就是这一单的金额", extractor=Broken()) is None


def test_confirmation_too_long_is_not_confirmation():
    from app.agent.extractor import Extractor
    from app.service import parse_confirmation

    class Labeler(Extractor):
        def extract(self, message, draft, history=None):
            return {}

        def classify_text(self, system_prompt, user_text):
            return "NEW"

    long_text = "我觉得这个任务还是先别发了等我把地址确认一下再说吧顺便问下余额"
    assert parse_confirmation(long_text, extractor=Labeler()) is None


def test_confirmed_modify_does_not_ask_again():
    """回「还是原来那单」后，重放原话不能再被判定成新单（否则反复追问）。"""
    from app.agent.extractor import Extractor
    from app.agent.intent import Intent
    from app.models import TaskDraft
    from app.service import ChatService

    class SameLabeler(Extractor):
        def extract(self, message, draft, history=None):
            return {}

        def classify_text(self, system_prompt, user_text):
            return "SAME"

        def classify_intent(self, message, draft):
            # 模型仍然觉得这是新的一单
            return Intent.NEW_TASK

    service = ChatService(extractor=SameLabeler())
    session = service.store.create(user_id="u-1", panel_id="p-1")
    session.draft = TaskDraft(type="DELIVERY", title="送袜子", location_from="A", location_to="B",
                              reward_cents=1000)
    session.pending = {"message": "帮我取个快递，8块"}
    service.store.save(session)

    seen = {}

    def fake_invoke(state):
        seen["force_merge"] = state.get("force_merge")
        return {**state, "draft": state["draft"], "missing": [], "status": "READY", "intent": None}

    service.graph.invoke = fake_invoke
    service._chat(session, "还是原来那单，我是想改金额", "tok")

    assert seen["force_merge"] is True
    assert service.store.get(session.id).pending is None


def test_unrelated_answer_clears_pending():
    """回一句不相关的话：待确认要清掉，不能一直挂着。"""
    from app.agent.extractor import Extractor
    from app.models import TaskDraft
    from app.service import ChatService

    class Other(Extractor):
        def extract(self, message, draft, history=None):
            return {}

        def classify_text(self, system_prompt, user_text):
            return "OTHER"

    service = ChatService(extractor=Other())
    session = service.store.create(user_id="u-1", panel_id="p-1")
    session.draft = TaskDraft(type="DELIVERY", title="送袜子", location_from="A", location_to="B",
                              reward_cents=1000)
    session.pending = {"message": "帮我取个快递，8块"}
    service.store.save(session)

    service.graph.invoke = lambda state: {**state, "draft": state["draft"], "missing": [],
                                          "status": "READY", "intent": None}
    service._chat(session, "先帮我看看广场有什么单", "tok")

    assert service.store.get(session.id).pending is None


def test_platform_query_works_mid_draft():
    """草稿就绪时也要能问广场/优惠券，只有"多少钱"这种才压回发单。"""
    from app.agent.router import Route, route_message
    from app.models import TaskDraft

    ready = TaskDraft(type="DELIVERY", title="送袜子", location_from="A", location_to="B",
                      reward_cents=1000)

    assert route_message("看看现在广场有什么单", draft=ready) is Route.QUERY
    assert route_message("看看我的优惠券", draft=ready) is Route.QUERY
    assert route_message("有什么单可以接", draft=ready) is Route.QUERY
    # 指代当前草稿的问句仍然走发单
    assert route_message("现在多少钱", draft=ready) is Route.TASK


def test_answer_mid_draft_reminds_draft_alive():
    """查询回答后提醒"草稿还在"，别让用户以为这一单丢了。"""
    from app.agent.extractor import Extractor
    from app.models import TaskDraft
    from app.service import ChatService

    class Noop(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    service = ChatService(extractor=Noop())
    session = service.store.create(user_id="u-1", panel_id="p-1")
    session.draft = TaskDraft(type="DELIVERY", title="送袜子", location_from="A", location_to="B",
                              reward_cents=1000)
    service.store.save(session)

    service.graph.invoke = lambda state: {"status": "ANSWER", "answer": "广场有 3 单",
                                          "draft": state["draft"], "missing": [],
                                          "tool_calls": []}
    reply = service._chat(session, "看看现在广场有什么单", "tok")

    assert "广场有 3 单" in reply["message"]
    assert "草稿还在" in reply["message"]
