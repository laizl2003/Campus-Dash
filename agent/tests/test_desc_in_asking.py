"""追问阶段抽取任务细节（description）的测试。"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.agent.extractor import Extractor
from app.models import Slot, TaskDraft, next_question
from app.service import ChatService


class SparseExtractor(Extractor):
    """模拟模型：能从一句话里同时抽出时间和描述。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if "明天" in message or "点" in message:
            patch["start_time"] = (datetime.now() + timedelta(days=1)).replace(
                hour=15, minute=0, second=0, microsecond=0)
        if "小推车" in message or "不要撒" in message:
            patch["description"] = message
        return patch


def _asking_session():
    service = ChatService(SparseExtractor())
    session = service.create_session()
    session.draft = TaskDraft(
        type="DELIVERY", title="取快递",
        location_from="菜鸟驿站", location_to="3号楼502",
        reward_cents=800,
    )
    return service, session.id


def test_description_extracted_during_asking():
    service, sid = _asking_session()
    reply = service.chat(sid, "明天下午三点，快递有点大需要小推车")
    assert reply["status"] == "READY"
    assert reply["draft"]["start_time"] is not None
    assert reply["draft"]["description"] == "明天下午三点，快递有点大需要小推车"


def test_description_does_not_pollute_other_fields():
    service, sid = _asking_session()
    reply = service.chat(sid, "明天下午三点，快递有点大需要小推车")
    assert reply["draft"]["location_from"] == "菜鸟驿站"
    assert reply["draft"]["location_to"] == "3号楼502"
    assert reply["draft"]["title"] == "取快递"


def test_question_keeps_detail_hint_until_complete():
    service, sid = _asking_session()
    reply = service.chat(sid, "没有说到时间")
    assert reply["status"] == "ASKING"
    hint_lines = [line for line in reply["message"].splitlines() if line.strip().startswith("·")]
    assert hint_lines, reply["message"]
    assert "任务细节的描述？" in hint_lines[-1]


def test_detail_hint_disappears_when_complete():
    service, sid = _asking_session()
    reply = service.chat(sid, "明天下午三点")
    assert reply["status"] == "READY"
    assert "任务细节的描述？" not in reply["message"]


def test_hint_line_format_matches_example():
    q = next_question([Slot.REWARD])
    assert "任务细节的描述？（可选，如：外卖有汤，不要撒了）" in q
