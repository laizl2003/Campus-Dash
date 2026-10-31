"""READY 阶段：一句话里同时改字段和补说明。"""

from __future__ import annotations

from datetime import datetime, timedelta

from app.agent.extractor import Extractor
from app.agent.intent import Intent
from app.models import TaskDraft
from app.service import ChatService


class MixedExtractor(Extractor):
    """模拟模型：一句话里既给字段新值，又给出剩余说明。"""

    def __init__(self, intent: Intent = Intent.APPEND) -> None:
        self.fixed_intent = intent

    def extract(
        self, message: str, draft: TaskDraft, history: list[dict] | None = None
    ) -> dict[str, object]:
        patch: dict[str, object] = {}
        if "标题改成" in message:
            patch["title"] = "代取快递"
        if "送到" in message:
            patch["location_to"] = "4号楼101"
        if "12块" in message:
            patch["reward_cents"] = 1200
        if "明天下午三点" in message:
            patch["expired_at"] = (datetime.now() + timedelta(days=1)).replace(
                hour=15, minute=0, second=0, microsecond=0)
        if "不要撒" in message:
            patch["description"] = "外卖有汤，不要撒了"
        return patch

    def classify_intent(self, message: str, draft: TaskDraft) -> Intent | None:
        return self.fixed_intent


def _ready(intent: Intent = Intent.APPEND):
    service = ChatService(MixedExtractor(intent))
    session = service.create_session()
    session.draft = TaskDraft(
        type="DELIVERY", title="取快递",
        location_from="菜鸟驿站", location_to="3号楼502", reward_cents=800,
    )
    return service, session.id


def test_deadline_and_content_in_one_message():
    """一句话里既有时间又有补充说明，两者都要生效。"""
    service, sid = _ready()
    reply = service.chat(sid, "明天下午三点前送到，外卖有汤不要撒")
    assert reply["draft"]["expired_at"] is not None
    assert reply["draft"]["description"] is not None
    assert "不要撒" in reply["draft"]["description"]


def test_required_fields_editable_after_complete():
    """信息齐全后，用户仍然可以改必填字段。"""
    service, sid = _ready()
    reply = service.chat(sid, "送到4号楼101，12块")
    assert reply["draft"]["location_to"] == "4号楼101"
    assert reply["draft"]["reward_cents"] == 1200
    assert "location_to" in reply["updated_fields"]
    assert "reward_cents" in reply["updated_fields"]


def test_field_names_listed_in_reply():
    service, sid = _ready()
    reply = service.chat(sid, "送到4号楼101，12块")
    assert "送达地点" in reply["message"]
    assert "悬赏" in reply["message"]


def test_title_change_needs_edit_intent():
    """APPEND 不覆盖标题；明确 EDIT 才改。"""
    service, sid = _ready(Intent.APPEND)
    reply = service.chat(sid, "标题改成代取快递")
    assert reply["draft"]["title"] == "取快递"

    service2, sid2 = _ready(Intent.EDIT)
    reply2 = service2.chat(sid2, "标题改成代取快递")
    # 标题会自动带上已知地点："菜鸟驿站" + "代取快递"
    assert reply2["draft"]["title"] == "菜鸟驿站代取快递"


def test_field_value_not_leaked_into_description():
    """改字段时不该把这句也写进任务描述。"""
    service, sid = _ready()
    reply = service.chat(sid, "送到4号楼101")
    assert reply["draft"]["description"] is None
