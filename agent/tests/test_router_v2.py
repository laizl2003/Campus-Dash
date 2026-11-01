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
