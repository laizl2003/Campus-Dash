"""追问阶段抽取截止时间与任务细节的测试。"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.agent.extractor import Extractor
from app.models import Slot, TaskDraft, next_question, required_slots
from app.service import ChatService


class SparseExtractor(Extractor):
    """模拟模型：能从一句话里同时抽出截止时间、金额和描述。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if "明天" in message or "点" in message:
            patch["expired_at"] = (datetime.now() + timedelta(days=1)).replace(
                hour=15, minute=0, second=0, microsecond=0)
        if "8块" in message:
            patch["reward_cents"] = 800
        if "小推车" in message or "不要撒" in message:
            patch["description"] = message
        return patch


def _asking_session():
    """只差悬赏：这样对话会停在 ASKING，能观察追问清单。"""
    service = ChatService(SparseExtractor())
    session = service.create_session()
    session.draft = TaskDraft(
        type="DELIVERY", title="取快递",
        location_from="菜鸟驿站", location_to="3号楼502",
    )
    return service, session.id


def test_description_and_deadline_extracted_in_one_turn():
    service, sid = _asking_session()
    reply = service.chat(sid, "明天下午三点，8块，快递有点大需要小推车")
    assert reply["status"] == "READY"
    assert reply["draft"]["expired_at"] is not None
    assert reply["draft"]["description"] == "明天下午三点，8块，快递有点大需要小推车"


def test_description_does_not_pollute_other_fields():
    service, sid = _asking_session()
    reply = service.chat(sid, "明天下午三点，8块，快递有点大需要小推车")
    assert reply["draft"]["location_from"] == "菜鸟驿站"
    assert reply["draft"]["location_to"] == "3号楼502"
    assert reply["draft"]["title"] == "取快递"


def test_question_keeps_detail_hint_until_complete():
    service, sid = _asking_session()
    reply = service.chat(sid, "还没有")
    assert reply["status"] == "ASKING"
    hint_lines = [line for line in reply["message"].splitlines() if line.strip().startswith("·")]
    assert hint_lines, reply["message"]
    assert "任务细节的描述？" in hint_lines[-1]


def test_deadline_hint_is_second_to_last():
    """截止时间是选填，固定排在倒数第二行，最后一行留给任务细节。"""
    service, sid = _asking_session()
    reply = service.chat(sid, "还没有")
    lines = [line for line in reply["message"].splitlines() if line.strip().startswith("·")]
    assert "截止时间是什么时候？" in lines[-2]
    assert "任务细节的描述？" in lines[-1]


def test_deadline_hint_disappears_once_answered():
    service, sid = _asking_session()
    reply = service.chat(sid, "明天下午三点")
    assert reply["draft"]["expired_at"] is not None
    assert "截止时间是什么时候？" not in reply["message"]


def test_expired_at_is_not_required():
    draft = TaskDraft(type="DELIVERY", title="取快递", location_from="A",
                      location_to="B", reward_cents=800)
    assert required_slots(draft) == []
    assert Slot.EXPIRED_AT not in required_slots(draft)


def test_hint_line_format_matches_example():
    q = next_question([Slot.REWARD])
    assert "任务细节的描述？（可选，如：外卖有汤，不要撒了）" in q
    assert "截止时间是什么时候？（可选，如：明天下午 3 点）" in q
