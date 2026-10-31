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

    def extract(
        self, message: str, draft: TaskDraft, history: list[dict] | None = None
    ) -> dict[str, object]:
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


def test_ambiguous_input_is_unclear():
    """看不出想干什么的输入不猜，返回 UNCLEAR。"""
    for text in ("外卖", "第三个", "行随便吧", "都行", "你定", "那个呢"):
        assert guess_intent_by_rules(text) is Intent.UNCLEAR, text


def test_clear_appends_still_classified():
    """正常的补充描述不能被误判成 UNCLEAR。"""
    for text in ("东西挺重的", "快递比较大需要小推车", "麻烦下午五点前送到", "取件码9933"):
        assert guess_intent_by_rules(text) is Intent.APPEND, text


def test_unclear_keeps_draft_and_replies_with_guide():
    service, sid = _ready_service(Intent.UNCLEAR)
    reply = service.chat(sid, "外卖")
    # 草稿完全不动，尤其 description 不能被写入
    assert reply["draft"]["description"] is None
    assert reply["draft"]["title"] == "把书还到图书馆"
    assert reply["unclear"] is True
    assert "不太确定" in reply["message"]
    assert "确认发布" not in reply["message"] or "发布" in reply["message"]


def test_unclear_reply_lists_options():
    from app.agent.intent import UNCLEAR_REPLY
    assert "补充任务内容" in UNCLEAR_REPLY
    assert "修改信息" in UNCLEAR_REPLY


def test_show_draft_intent_rules():
    """要求再看一次卡片。"""
    for text in ("再给我看下任务", "卡片呢", "刚才那个单子再发一遍", "重新展示一下",
                 "看看现在长什么样", "再看看"):
        assert guess_intent_by_rules(text) is Intent.SHOW_DRAFT, text


def test_specific_field_question_is_query_not_show_draft():
    """"多少钱""是问字段，不是要求展示卡片。"""
    for text in ("多少钱", "几点开始", "送到哪", "取件码是什么"):
        assert guess_intent_by_rules(text) is Intent.QUERY, text


def test_show_draft_keeps_draft_and_flags_frontend():
    voice = _ready_service(Intent.SHOW_DRAFT)
    service, sid = voice
    reply = service.chat(sid, "再给我看下任务")
    assert reply["draft"]["title"] == "把书还到图书馆"
    assert reply["draft"]["description"] is None
    assert reply["show_draft"] is True
    assert reply["cleared"] is False
    assert "任务卡片" in reply["message"]


def test_colloquial_confirmation_is_finish():
    """口语确认词（ok了/好的/行）表示认可，不能写进任务内容。"""
    for text in ("ok了", "OK了", "ok", "好的", "行", "可以", "没问题", "嗯", "收到", "好嘞", "行行"):
        assert guess_intent_by_rules(text) is Intent.FINISH, text


def test_confirmation_word_inside_sentence_is_not_finish():
    """带任务信息的句子不能因为含"好的""可以"就被判成结束。"""
    for text in ("好的我要五点送到", "可以带个小推车", "快递比较大需要小推车", "麻烦五点前送到"):
        assert guess_intent_by_rules(text) is Intent.APPEND, text


def test_polite_only_is_chitchat_but_mixed_is_append():
    for text in ("谢谢你", "辛苦了", "你好"):
        assert guess_intent_by_rules(text) is Intent.CHITCHAT, text
    for text in ("尽快还，谢谢", "快递比较大，谢谢", "你好我要取快递"):
        assert guess_intent_by_rules(text) is Intent.APPEND, text


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
