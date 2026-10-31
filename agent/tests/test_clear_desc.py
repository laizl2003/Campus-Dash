"""清空任务内容 + 内容去口语化测试。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.agent.intent import Intent, guess_intent_by_rules
from app.models import TaskDraft
from app.service import ChatService


class EditableExtractor(Extractor):
    """第一轮给完整草稿，之后按固定意图和整理结果回应。"""

    def __init__(self, intent: Intent, summary: str | None = None) -> None:
        self.calls = 0
        self.fixed_intent = intent
        self.summary = summary

    def extract(
        self, message: str, draft: TaskDraft, history: list[dict] | None = None
    ) -> dict[str, object]:
        self.calls += 1
        if self.calls > 1:
            return {}
        return {"type": "OTHER", "title": "帮忙还书", "reward_cents": 500}

    def classify_intent(self, message: str, draft: TaskDraft) -> Intent | None:
        return self.fixed_intent

    def summarize_description(self, existing: str | None, message: str) -> str | None:
        return self.summary


def _ready(intent: Intent, summary: str | None = None):
    service = ChatService(EditableExtractor(intent, summary))
    session = service.create_session()
    service.chat(session.id, "帮我把书还到图书馆，5块")
    session.draft = TaskDraft(type="OTHER", title="帮忙还书", reward_cents=500, description="书比较重，需要小推车")
    return service, session.id


def test_circuit_breaker_forwards_new_methods():
    """包装器必须透传 classify_intent / summarize_description。

    之前漏了透传，调用静默落到基类默认实现，表现为
    "意图和整理功能写了但完全不生效"，排查了很久。
    """
    from app.agent.extractor import CircuitBreakerExtractor, LLMCallResult, LLMExtractor
    from app.config import LLMSettings

    class Stub(LLMExtractor):
        def _call_llm(self, system_prompt, user_prompt, json_mode=None):
            # 内容整理返回整理结果；意图分类返回 FINISH
            if "任务说明" in system_prompt or "整理" in system_prompt:
                return LLMCallResult(content="快递较大，取件码9933")
            return LLMCallResult(content="FINISH")

    settings = LLMSettings(enabled=True, base_url="http://x", api_key="k", model="m")
    cb = CircuitBreakerExtractor(primary=Stub(settings))
    assert cb.classify_intent("没有了", TaskDraft()) is Intent.FINISH
    assert cb.summarize_description(None, "嗯那个快递挺大的") == "快递较大，取件码9933"


def test_clear_intent_rules():
    for text in ("把任务内容删掉", "清空备注", "不要任务内容了", "删除描述", "去掉刚才那些说明"):
        assert guess_intent_by_rules(text) is Intent.CLEAR_DESCRIPTION, text


def test_clear_does_not_swallow_edit_or_append():
    assert guess_intent_by_rules("把金额改成12块") is Intent.EDIT
    assert guess_intent_by_rules("删掉取件码改成9933") is Intent.APPEND


def test_clear_description_only_resets_description():
    service, sid = _ready(Intent.CLEAR_DESCRIPTION)
    reply = service.chat(sid, "把任务内容删掉")
    assert reply["draft"]["description"] is None
    # 其他字段必须保留
    assert reply["draft"]["title"] == "帮忙还书"
    assert reply["draft"]["reward_cents"] == 500
    assert reply["cleared"] is True
    assert "已清空任务内容" in reply["message"]


def test_append_uses_summarized_text():
    """模型给出整理结果时，任务内容用整理后的书面表述。"""
    service, sid = _ready(Intent.APPEND, summary="快递体积较大，需携带小推车；取件码9933")
    reply = service.chat(sid, "嗯那个快递挺大的 我天 取件码是9933 麻烦你带个推车吧谢谢")
    assert reply["draft"]["description"] == "快递体积较大，需携带小推车；取件码9933"
    assert reply["cleared"] is False


def test_append_falls_back_to_raw_when_summary_missing():
    """模型整理失败时退回原文拼接，信息不能丢。"""
    service, sid = _ready(Intent.APPEND, summary=None)
    reply = service.chat(sid, "取件码9933")
    assert "取件码9933" in reply["draft"]["description"]
    assert "书比较重" in reply["draft"]["description"]


def test_summary_is_truncated_to_field_limit():
    service, sid = _ready(Intent.APPEND, summary="长" * 500)
    reply = service.chat(sid, "补充")
    assert len(reply["draft"]["description"]) <= 300
