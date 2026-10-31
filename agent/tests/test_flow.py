"""状态机与追问逻辑测试。

用 FakeExtractor 模拟"模型只抽到部分字段"，验证：
1. 缺字段一定追问；
2. 用户只回答一个字段时，之前的信息不丢；
3. 其他帮忙类型不追问地点和时间；
4. 信息齐全后进入 READY。
"""

from __future__ import annotations

from app.agent.extractor import EmptyExtractor, Extractor
from app.models import TaskDraft
from app.service import ChatService


class FakeExtractor(Extractor):
    """按预设脚本返回 patch，模拟不同程度的模型抽取能力。"""

    def __init__(self, script: list[dict[str, object]]) -> None:
        self.script = script
        self.calls = 0

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        if not self.script:
            return {}
        index = min(self.calls, len(self.script) - 1)
        self.calls += 1
        return self.script[index]


def test_empty_extractor_asks_for_type_first():
    service = ChatService(EmptyExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "帮我取个快递")
    assert reply["status"] == "ASKING"
    assert reply["missing"] == ["type"]
    assert "哪一类任务" in reply["message"]


def test_missing_fields_are_asked_one_by_one():
    script = [
        {"type": "DELIVERY", "title": "取快递"},
        {"location_from": "菜鸟驿站"},
        {"location_to": "3 号楼 502"},
        {"expired_at": "2026-11-01T15:00:00", "reward_cents": 800},
    ]
    service = ChatService(FakeExtractor(script))
    session = service.create_session()

    reply = service.chat(session.id, "帮我取快递")
    assert reply["missing"][0] == "location_from"

    reply = service.chat(session.id, "菜鸟驿站")
    assert reply["missing"][0] == "location_to"
    assert "送到哪里" in reply["message"]

    reply = service.chat(session.id, "3 号楼 502")
    # 截止时间是选填，不再出现在缺失清单里
    assert reply["missing"] == ["reward_cents"]

    reply = service.chat(session.id, "明天下午三点，8 块")
    assert reply["status"] == "READY"
    assert reply["question"] is None
    assert reply["draft"]["location_from"] == "菜鸟驿站"
    assert reply["draft"]["reward_cents"] == 800


def test_other_type_skips_location_and_time():
    script = [{"type": "OTHER", "title": "帮忙还书", "reward_cents": 500}]
    service = ChatService(FakeExtractor(script))
    session = service.create_session()
    reply = service.chat(session.id, "帮我把书还到图书馆，给 5 块")
    assert reply["status"] == "READY"
    assert reply["missing"] == []


def test_bare_answer_does_not_break_flow():
    service = ChatService(EmptyExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "DELIVERY")
    assert reply["missing"][0] == "type"

    reply = service.chat(session.id, "3 号楼 502")
    assert reply["status"] == "ASKING"


def test_rule_extractor_collects_fields_across_turns():
    """默认规则抽取器：多轮只补字段，不会把已有信息冲掉。"""
    service = ChatService()
    session = service.create_session()

    reply = service.chat(session.id, "跑腿配送")
    assert reply["draft"]["type"] == "DELIVERY"

    reply = service.chat(session.id, "取快递到3号楼")
    assert reply["draft"]["title"] == "取快递到3号楼"

    reply = service.chat(session.id, "菜鸟驿站东门")
    assert reply["draft"]["location_from"] == "菜鸟驿站东门"

    reply = service.chat(session.id, "3号楼502")
    assert reply["draft"]["location_to"] == "3号楼502"

    reply = service.chat(session.id, "明天下午三点，8块")
    assert reply["status"] == "READY"
    assert reply["draft"]["reward_cents"] == 800
    assert reply["draft"]["expired_at"].startswith("2026-11-01T15:00")
