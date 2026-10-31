"""LLM 抽取链路测试。

不调用真实模型：用子类覆盖 _call_llm 返回预设文本，
验证解析、单位换算、字段校验、失败降级这几段逻辑。
"""

from __future__ import annotations

from datetime import datetime

from app.agent.extractor import LLMCallResult, LLMExtractor, RuleExtractor
from app.config import LLMSettings
from app.models import TaskDraft


class ScriptedLLM(LLMExtractor):
    """按脚本返回模型输出文本，跳过真实 HTTP 调用。"""

    def __init__(self, content, settings=None) -> None:
        super().__init__(
            settings or LLMSettings(enabled=True, base_url="http://x", api_key="k", model="m"),
            max_retries=1,
        )
        self.content = content

    def _call_llm(self, system_prompt: str, user_prompt: str) -> LLMCallResult:
        if isinstance(self.content, Exception):
            raise self.content
        return LLMCallResult(content=self.content)


def test_plain_json_is_parsed():
    llm = ScriptedLLM('{"type":"DELIVERY","title":"取快递"}')
    patch = llm.extract("取快递", TaskDraft())
    assert patch["type"] == "DELIVERY"
    assert patch["title"] == "取快递"


def test_markdown_fence_is_stripped():
    llm = ScriptedLLM('```json\n{"reward_cents": 800}\n```')
    assert llm.extract("8块", TaskDraft())["reward_cents"] == 800


def test_extra_prose_is_tolerated():
    llm = ScriptedLLM('好的，结果如下：{"location_to":"3号楼502"} 请确认。')
    assert llm.extract("3号楼502", TaskDraft())["location_to"] == "3号楼502"


def test_reward_string_with_yuan_is_converted():
    llm = ScriptedLLM('{"reward_cents":"8元"}')
    assert llm.extract("8块", TaskDraft())["reward_cents"] == 800


def test_type_alias_is_normalized():
    llm = ScriptedLLM('{"type":"跑腿配送"}')
    assert llm.extract("跑腿", TaskDraft())["type"] == "DELIVERY"


def test_natural_language_time_falls_back_to_rule():
    llm = ScriptedLLM('{"start_time":"明天下午三点"}')
    patch = llm.extract("明天下午三点", TaskDraft())
    assert isinstance(patch["start_time"], datetime)
    assert patch["start_time"].hour == 15


def test_invalid_type_is_dropped():
    llm = ScriptedLLM('{"type":"MAIL","title":"x"}')
    patch = llm.extract("寄信", TaskDraft())
    assert "type" not in patch
    assert patch["title"] == "x"


def test_unknown_fields_are_dropped():
    llm = ScriptedLLM('{"title":"x","hacker":"y","campus_id":9}')
    patch = llm.extract("x", TaskDraft())
    assert set(patch) == {"title"}


def test_garbage_output_falls_back_to_rules():
    llm = ScriptedLLM("抱歉我不知道")
    patch = llm.extract("跑腿配送，8块", TaskDraft())
    assert patch["type"] == "DELIVERY"
    assert patch["reward_cents"] == 800
    assert llm.last_error is not None


def test_exception_falls_back_to_rules():
    llm = ScriptedLLM(RuntimeError("timeout"))
    patch = llm.extract("帮买奶茶，5块", TaskDraft())
    assert patch["type"] == "BUY"
    assert patch["reward_cents"] == 500


def test_unreachable_endpoint_degrades_to_rules():
    """真实调用打不通时，仍然降级给规则兜底，对话不中断。"""
    settings = LLMSettings(
        enabled=True, base_url="http://127.0.0.1:1/v1", api_key="k",
        model="m", timeout=1.0,
    )
    llm = LLMExtractor(settings, fallback=RuleExtractor(), max_retries=1)
    patch = llm.extract("跑腿配送，8块", TaskDraft())
    assert patch["type"] == "DELIVERY"
    assert patch["reward_cents"] == 800
    assert llm.last_error is not None


def test_settings_usable_requires_all_fields():
    assert not LLMSettings(enabled=True, base_url="", api_key="", model="").usable()
    assert LLMSettings(enabled=True, base_url="u", api_key="k", model="m").usable()
    assert not LLMSettings(enabled=False, base_url="u", api_key="k", model="m").usable()
