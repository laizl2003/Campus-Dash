"""截止时间：选填语义 + 追问位置 + 补充阶段回填。"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.agent.extractor import Extractor
from app.agent.intent import Intent
from app.models import Slot, TaskDraft, next_question, required_slots
from app.service import ChatService


def _deadline(days: int = 1, hour: int = 15) -> datetime:
    return (datetime.now() + timedelta(days=days)).replace(
        hour=hour, minute=0, second=0, microsecond=0)


class DeadlineExtractor(Extractor):
    """模拟模型：识别"截止"类说法，并支持识别为 APPEND 意图。"""

    def __init__(self, intent: Intent | None = None) -> None:
        self.fixed_intent = intent

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        if "明天" in message and ("点" in message or "截止" in message):
            return {"expired_at": _deadline()}
        return {}

    def classify_intent(self, message: str, draft: TaskDraft) -> Intent | None:
        return self.fixed_intent


def _ready_session(intent: Intent | None = None):
    service = ChatService(DeadlineExtractor(intent))
    session = service.create_session()
    session.draft = TaskDraft(
        type="DELIVERY", title="取快递",
        location_from="菜鸟驿站", location_to="3号楼502",
        reward_cents=800,
    )
    return service, session.id


def test_deadline_never_required():
    """缺截止时间也算信息完整——不填代表永久有效。"""
    draft = TaskDraft(type="DELIVERY", title="取快递", location_from="A",
                      location_to="B", reward_cents=800)
    assert required_slots(draft) == []


def test_deadline_line_is_second_to_last():
    """截止时间排在必填清单之后、任务细节之前。"""
    text = next_question([Slot.LOCATION_FROM, Slot.REWARD])
    lines = text.splitlines()
    assert "截止时间是什么时候？" in lines[-2]
    assert "任务细节的描述？" in lines[-1]


def test_deadline_question_marked_optional():
    text = next_question([Slot.REWARD])
    assert "截止时间是什么时候？（可选，如：明天下午 3 点）" in text


def test_deadline_line_hidden_once_filled():
    draft = TaskDraft(type="DELIVERY", title="取快递", reward_cents=800,
                      expired_at=_deadline())
    text = next_question([Slot.LOCATION_TO], draft=draft)
    assert "截止时间是什么时候？" not in text


def test_deadline_filled_during_follow_up():
    """追问阶段顺口说时间，要进 expired_at 而不是别处。"""
    service = ChatService(DeadlineExtractor())
    session = service.create_session()
    session.draft = TaskDraft(type="DELIVERY", title="取快递")
    reply = service.chat(session.id, "明天下午三点截止")
    assert reply["draft"]["expired_at"] is not None
    assert reply["draft"]["location_from"] is None


def test_deadline_filled_after_info_complete():
    """信息已完整后补充阶段说截止时间，应改字段而不是写进任务内容。"""
    service, sid = _ready_session(Intent.APPEND)
    reply = service.chat(sid, "明天下午三点截止")
    assert reply["status"] == "READY"
    assert reply["draft"]["expired_at"] is not None
    assert reply["draft"]["description"] is None, "不能当成任务内容追加"
    assert reply["deadline_updated"] is True
    assert "截止时间已更新" in reply["message"]


def test_normal_append_still_goes_to_description():
    """没有时间的普通补充，仍然进任务内容。"""
    service, sid = _ready_session(Intent.APPEND)
    reply = service.chat(sid, "外卖有汤，不要撒了")
    assert reply["deadline_updated"] is False
    assert reply["draft"]["expired_at"] is None
