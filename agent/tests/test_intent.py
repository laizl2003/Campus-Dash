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


def test_finish_sentences_with_prefix_suffix():
    """"别的没什么要补充的了"这类带前后缀的说法必须识别成结束。"""
    cases = [
        "别的没什么要补充的了",
        "没别的了",
        "没什么要补充的了",
        "就这样吧",
        "不用了谢谢",
        "别的就不用了",
        "暂时没有别的了",
        "就这些吧谢谢",
    ]
    for text in cases:
        assert guess_intent_by_rules(text) is Intent.FINISH, text


def test_publish_exclusion_is_not_publish():
    """"不用发""先别发"这类收回发布意图的话，绝不能判成 PUBLISH。

    FINISH 和 CANCEL 都算合理："不用发"是结束，"不发了吧"更接近取消，
    这里只断言"不是发布"，避免把语义判断写死。
    """
    for text in ("不用发", "先别发", "不发了吧"):
        assert guess_intent_by_rules(text) is not Intent.PUBLISH, text


def test_normalize_intent_survives_garbage():
    """模型吐出大量 \r 之类垃圾时返回 None，交给规则兜底。"""
    assert normalize_intent("\r" * 300) is None
    assert normalize_intent("") is None
    assert normalize_intent("完全无关的文字") is None


def test_normalize_intent_finds_label_inside_text():
    assert normalize_intent("我认为是 PUBLISH 这一类") is Intent.PUBLISH
    assert normalize_intent("结果：FINISH") is Intent.FINISH


def test_intent_call_does_not_use_json_mode():
    """意图分类必须关闭 JSON 模式，否则推理模型会输出错乱内容。"""
    from app.agent.extractor import LLMCallResult, LLMExtractor
    from app.config import LLMSettings

    class Recorder(LLMExtractor):
        def __init__(self):
            super().__init__(LLMSettings(enabled=True, base_url="http://x", api_key="k", model="m"))
            self.json_mode_seen = "unset"

        def _call_llm(self, system_prompt, user_prompt, json_mode=None):
            self.json_mode_seen = json_mode
            return LLMCallResult(content="FINISH")

    ex = Recorder()
    result = ex.classify_intent("没有了", TaskDraft(type="OTHER", title="x", reward_cents=500))
    assert result is Intent.FINISH
    assert ex.json_mode_seen is False


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
