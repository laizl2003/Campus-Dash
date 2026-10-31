"""意图识别测试：READY 之后不同说法要分到不同动作。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.agent.intent import Intent, guess_intent_by_rules, normalize_intent
from app.models import TaskDraft
from app.service import ChatService


class ReadyExtractor(Extractor):
    """第一轮直接给出完整草稿。"""

    def __init__(self, intent: Intent | None = None) -> None:
        self.calls = 0
        self.fixed_intent = intent

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        self.calls += 1
        if self.calls > 1:
            # EDIT 意图下，抽取器要能把"金额改成12"解析成字段
            if "12" in message:
                return {"reward_cents": 1200}
            return {}
        return {"type": "OTHER", "title": "帮忙还书", "reward_cents": 500}

    def classify_intent(self, message: str, draft: TaskDraft) -> Intent | None:
        return self.fixed_intent


def _ready_service(intent: Intent | None = None) -> tuple[ChatService, str]:
    service = ChatService(ReadyExtractor(intent))
    session = service.create_session()
    service.chat(session.id, "帮我把书还到图书馆，5块")
    return service, session.id


def test_rules_cover_common_intents():
    cases = {
        "快递比较大需要小推车": Intent.APPEND,
        "没有了": Intent.FINISH,
        "发布吧": Intent.PUBLISH,
        "把金额改成12块": Intent.EDIT,
        "多少钱": Intent.QUERY,
        "算了不发了": Intent.CANCEL,
        "你是谁": Intent.CHITCHAT,
    }
    for text, expected in cases.items():
        assert guess_intent_by_rules(text) is expected, text


def test_normalize_intent_handles_model_noise():
    assert normalize_intent("APPEND") is Intent.APPEND
    assert normalize_intent(" publish ") is Intent.PUBLISH
    assert normalize_intent("APPEND: 用户在补充细节") is Intent.APPEND
    assert normalize_intent('{"intent":"FINISH"}') is Intent.FINISH
    assert normalize_intent("看不懂的标签") is None


def test_append_intent_goes_to_description():
    service, sid = _ready_service(Intent.APPEND)
    reply = service.chat(sid, "书比较重，麻烦带个小推车")
    assert reply["draft"]["description"] == "书比较重，麻烦带个小推车"
    assert reply["intent"] == "APPEND"


def test_publish_intent_does_not_touch_description():
    """说"发布吧"不应该变成任务内容。"""
    service, sid = _ready_service(Intent.PUBLISH)
    reply = service.chat(sid, "发布吧")
    assert reply["draft"]["description"] is None
    assert reply["intent"] == "PUBLISH"
    assert "确认发布" in reply["message"]


def test_edit_intent_updates_field_not_description():
    service, sid = _ready_service(Intent.EDIT)
    reply = service.chat(sid, "把金额改成12块")
    assert reply["draft"]["reward_cents"] == 1200
    assert reply["draft"]["description"] is None
    assert reply["intent"] == "EDIT"


def test_query_intent_keeps_draft():
    service, sid = _ready_service(Intent.QUERY)
    reply = service.chat(sid, "现在多少钱")
    assert reply["draft"]["reward_cents"] == 500
    assert reply["draft"]["description"] is None
    assert reply["intent"] == "QUERY"


def test_cancel_and_chitchat_leave_draft_untouched():
    for intent in (Intent.CANCEL, Intent.CHITCHAT):
        service, sid = _ready_service(intent)
        reply = service.chat(sid, "嗯")
        assert reply["draft"]["description"] is None
        assert reply["intent"] == intent.value


def test_fallback_to_rules_when_model_silent():
    """没有模型时用关键词兜底，"发布吧"不会写进任务内容。"""
    service, sid = _ready_service(None)
    reply = service.chat(sid, "发布吧")
    assert reply["draft"]["description"] is None
    assert reply["intent"] == "PUBLISH"


def test_asking_state_ignores_intent():
    """还没填完时不走意图分支，仍然逐项追问。"""
    from app.agent.extractor import EmptyExtractor

    service = ChatService(EmptyExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "发布吧")
    assert reply["status"] == "ASKING"
    assert reply["intent"] is None
