"""LLM 抽取器接口、规则兜底、真实模型实现（HTTP 调用留空给使用者填）。

设计说明
--------
1. 抽取器只负责"从自然语言里读出字段"，不负责追问、不负责发布。
2. 追问由状态机（graph.py）根据缺失槽位决定，不交给模型自由发挥，
   这样"缺字段必问、问几次、问哪个"是确定且可测试的。
3. extractor 返回的必须是 JSON 字典；模型返回非法内容时抛 ExtractionError，
   由上层降级为"本轮没抽取到新信息"，不会写坏草稿。

四种实现
--------
- RuleExtractor   确定性关键词兜底，没有模型也能跑通（默认兜底）
- EmptyExtractor  什么都不抽，用于测试最差情况
- LLMExtractor    真实模型：提示词/解析/校验/重试/降级都已实现，
                  只有 _call_llm 留空，填入你的 API 调用即可
"""

from __future__ import annotations

import json
import re
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime, timedelta

from ..config import LLMSettings
from ..models import STRUCTURED_TYPES, TaskDraft, required_slots
from .prompts import SYSTEM_PROMPT, build_user_prompt


class ExtractionError(RuntimeError):
    """模型输出无法解析成字段字典。"""


class Extractor(ABC):
    """把一句自然语言转成任务字段 patch。"""

    @abstractmethod
    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        raise NotImplementedError


TYPE_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("DELIVERY", ("跑腿", "配送", "快递", "取件", "外卖", "送餐", "拿")),
    ("BUY", ("帮买", "买", "采购", "超市", "奶茶", "咖啡", "带一份", "带饭")),
    ("QUEUE", ("排队", "代排", "占座", "取号", "打饭")),
    ("OTHER", ("其他", "别的", "随便", "还书", "帮忙", "借")),
]

REWARD_PATTERN = re.compile(r"(\d+(?:\.\d{1,2})?)\s*(?:元|块|rmb|人民币)", re.IGNORECASE)


def guess_type(message: str) -> str | None:
    """按关键词猜类型；命中多个时按优先级取第一个。"""
    for errand_type, keywords in TYPE_KEYWORDS:
        if any(keyword in message for keyword in keywords):
            return errand_type
    return None


def guess_reward_cents(message: str) -> int | None:
    match = REWARD_PATTERN.search(message)
    if not match:
        return None
    cents = round(float(match.group(1)) * 100)
    return cents if cents > 0 else None


CN_HOURS: dict[str, int] = {
    "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5,
    "六": 6, "七": 7, "八": 8, "九": 9, "十": 10,
    "十一": 11, "十二": 12, "十三": 13, "十四": 14,
}


def _cn_hour(token: str) -> int | None:
    """把"三""十一"这类中文小时转成数字。"""
    return CN_HOURS.get(token)


def guess_start_time(message: str, now: datetime | None = None) -> datetime | None:
    """只处理 Demo 里最常见的几种说法，真实解析交给 LLM。"""
    base = now or datetime.now()
    day_offset = 0
    if "后天" in message:
        day_offset = 2
    elif "明天" in message:
        day_offset = 1
    elif "今天" in message:
        day_offset = 0

    hour: int | None = None
    minute = 0
    match = re.search(r"(\d{1,2})\s*[:：]\s*(\d{2})", message)
    if match:
        hour, minute = int(match.group(1)), int(match.group(2))
    else:
        match = re.search(r"(下午|晚上|中午|上午)?\s*(\d{1,2}|[一二两三四五六七八九十]{1,3})\s*点(?:\s*(\d{1,2})\s*分)?", message)
        if match:
            period, raw_hour = match.group(1), match.group(2)
            hour = int(raw_hour) if raw_hour.isdigit() else _cn_hour(raw_hour)
            minute = int(match.group(3)) if match.group(3) else 0
            if hour is None:
                return None
            if period in ("下午", "晚上") and hour < 12:
                hour += 12
            if period == "中午" and hour < 11:
                hour += 12

    has_day = day_offset > 0 or "今天" in message
    if hour is None and not has_day:
        return None
    if not 0 <= (hour or 0) <= 23:
        return None

    target = (base + timedelta(days=day_offset)).replace(
        hour=hour if hour is not None else 9, minute=minute, second=0, microsecond=0
    )
    return target


def looks_like_title(message: str) -> bool:
    text = message.strip()
    return 2 <= len(text) <= 64 and not REWARD_PATTERN.fullmatch(text)


class RuleExtractor(Extractor):
    """确定性兜底抽取：类型、金额、时间用关键词和正则。

    它不是要替代模型，而是保证"模型未接入时 Demo 仍然可跑"，
    同时给模型抽取提供一个可对比的基线。
    """

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if draft.type is None:
            guessed = guess_type(message)
            if guessed:
                patch["type"] = guessed
        reward = guess_reward_cents(message)
        if reward is not None:
            patch["reward_cents"] = reward
        start = guess_start_time(message)
        if start is not None:
            patch["start_time"] = start
        return patch


class EmptyExtractor(Extractor):
    """空实现：永远返回空 patch，用于测试最差情况。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        return {}

# ---------------------------------------------------------------- 真实模型

@dataclass
class LLMCallResult:
    """一次模型调用的原始返回。_call_llm 返回它，便于替换不同厂商的响应结构。"""

    content: str
    model: str | None = None
    usage: dict[str, object] | None = None


class LLMExtractor(Extractor):
    """真实模型抽取器。

    整条链路已实现，只有 _call_llm 需要你填：

        extract()
          ├─ 组装 prompt（prompts.py）
          ├─ _call_llm()        <- 你要填的地方：发 HTTP 请求，返回 LLMCallResult
          ├─ _parse_json()      容错解析（代码块/前后废话/JSON 片段）
          ├─ _normalize()       单位换算、时间解析、类型别名
          ├─ _validate()        只保留合法字段，非法字段丢弃
          └─ 失败重试 -> 仍失败则降级给 fallback

    降级策略很重要：模型超时或返回垃圾时，不能让整轮对话失败，
    否则用户会看到"助手不可用"。这里回落到 RuleExtractor，
    保证至少还能继续追问。
    """

    def __init__(
        self,
        settings: LLMSettings,
        fallback: Extractor | None = None,
        max_retries: int = 2,
    ) -> None:
        self.settings = settings
        self.fallback = fallback or RuleExtractor()
        self.max_retries = max_retries
        self.last_error: str | None = None

    # ------------------------------------------------------------ 对外入口

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        text = message.strip()
        if not text:
            return {}

        user_prompt = build_user_prompt(text, draft, required_slots(draft))

        for attempt in range(self.max_retries + 1):
            try:
                result = self._call_llm(SYSTEM_PROMPT, user_prompt)
                payload = self._parse_json(result.content)
                patch = self._normalize(payload, text)
                patch = self._validate(patch, draft)
                self.last_error = None
                return patch
            except NotImplementedError:
                # 使用者还没填 _call_llm：直接降级，避免无意义重试
                self.last_error = "LLMExtractor._call_llm 未实现"
                return self.fallback.extract(text, draft)
            except ExtractionError as exc:
                self.last_error = str(exc)
                if attempt < self.max_retries:
                    time.sleep(0.3 * (attempt + 1))
            except Exception as exc:  # 网络异常、超时等
                self.last_error = f"{type(exc).__name__}: {exc}"
                if attempt < self.max_retries:
                    time.sleep(0.5 * (attempt + 1))

        # 全部失败：降级，保证对话不中断
        return self.fallback.extract(text, draft)

    # ------------------------------------------------------------ 待填实现

    def _call_llm(self, system_prompt: str, user_prompt: str) -> LLMCallResult:
        """===== 在这里填入你的模型调用 =====

        绝大多数厂商都兼容 OpenAI Chat Completions 协议，参考实现：

            import httpx
            resp = httpx.post(
                f"{self.settings.base_url}/chat/completions",
                headers={"Authorization": f"Bearer {self.settings.api_key}"},
                json={
                    "model": self.settings.model,
                    "temperature": self.settings.temperature,
                    "max_tokens": self.settings.max_tokens,
                    "response_format": {"type": "json_object"},
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                },
                timeout=self.settings.timeout,
            )
            resp.raise_for_status()
            data = resp.json()
            return LLMCallResult(
                content=data["choices"][0]["message"]["content"],
                model=data.get("model"),
                usage=data.get("usage"),
            )

        非 OpenAI 协议的厂商，只要最终返回 LLMCallResult(content=模型输出文本) 即可，
        后面的解析、校验、重试逻辑都不用改。
        """
        raise NotImplementedError("请在 LLMExtractor._call_llm 中填入模型调用")

    # ------------------------------------------------------------ 解析与清洗

    @staticmethod
    def _parse_json(content: str) -> dict[str, object]:
        """容错解析：模型常带代码块、前后解释、甚至多个 JSON。

        顺序：去代码块 -> 直接解析 -> 截取第一个 {...}。
        """
        if not content or not content.strip():
            raise ExtractionError("模型返回空内容")

        text = content.strip()

        fence = re.search(r"```(?:json)?\s*(.+?)```", text, re.DOTALL)
        if fence:
            text = fence.group(1).strip()

        try:
            data = json.loads(text)
            if isinstance(data, dict):
                return data
            raise ExtractionError("模型返回的不是 JSON 对象")
        except json.JSONDecodeError:
            pass

        start = text.find("{")
        end = text.rfind("}")
        if start != -1 and end > start:
            try:
                data = json.loads(text[start:end + 1])
                if isinstance(data, dict):
                    return data
            except json.JSONDecodeError as exc:
                raise ExtractionError(f"JSON 解析失败: {exc}") from exc

        raise ExtractionError("模型输出中找不到 JSON")

    @staticmethod
    def _normalize(payload: dict[str, object], message: str) -> dict[str, object]:
        """把模型输出整理成 TaskDraft 能接受的形状。

        处理三件事：
        1. 金额可能是"8"、"8元"、"8.0"，统一换算成分；
        2. 时间可能是自然语言，兜底解析；
        3. 类型可能是中文或大小写混杂，统一成枚举值。
        """
        type_alias = {
            "跑腿配送": "DELIVERY", "跑腿": "DELIVERY", "配送": "DELIVERY",
            "帮买": "BUY", "买东西": "BUY", "代购": "BUY",
            "代排队": "QUEUE", "排队": "QUEUE",
            "其他帮忙": "OTHER", "其他": "OTHER",
        }

        patch: dict[str, object] = {}

        raw_type = payload.get("type")
        if isinstance(raw_type, str) and raw_type.strip():
            key = raw_type.strip()
            patch["type"] = type_alias.get(key, key.upper())

        for field in ("title", "location_from", "location_to", "description"):
            value = payload.get(field)
            if isinstance(value, str) and value.strip():
                patch[field] = value.strip()

        reward = payload.get("reward_cents")
        if reward is not None:
            cents = LLMExtractor._to_cents(reward)
            if cents is not None:
                patch["reward_cents"] = cents

        start = payload.get("start_time")
        if start is not None:
            parsed = LLMExtractor._to_datetime(start, message)
            if parsed is not None:
                patch["start_time"] = parsed

        return patch

    @staticmethod
    def _to_cents(value: object) -> int | None:
        if isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value if value > 0 else None
        if isinstance(value, float):
            return int(round(value)) if value > 0 else None
        if isinstance(value, str):
            match = re.search(r"(\d+(?:\.\d{1,2})?)", value)
            if not match:
                return None
            number = float(match.group(1))
            # 带"元/块"字样的按元换算，纯数字按分处理
            if re.search(r"元|块|rmb|人民币", value, re.IGNORECASE):
                return int(round(number * 100)) or None
            return int(round(number)) or None
        return None

    @staticmethod
    def _to_datetime(value: object, message: str) -> datetime | None:
        if isinstance(value, datetime):
            return value
        if isinstance(value, str):
            text = value.strip()
            for parser in (
                lambda s: datetime.fromisoformat(s),
                lambda s: datetime.strptime(s, "%Y-%m-%d %H:%M"),
                lambda s: datetime.strptime(s, "%Y-%m-%d"),
            ):
                try:
                    return parser(text)
                except ValueError:
                    continue
            return guess_start_time(text)
        # 模型没解析出来，退回原始句子再试一次
        return guess_start_time(message)

    @staticmethod
    def _validate(patch: dict[str, object], draft: TaskDraft) -> dict[str, object]:
        """只保留合法字段，并用 TaskDraft 做一次整体校验。

        非法字段直接丢弃而不是报错：宁可少一个字段继续追问，
        也不要因为模型多答一个词就让整轮失败。
        """
        allowed = set(TaskDraft.model_fields.keys())
        clean = {k: v for k, v in patch.items() if k in allowed}

        if clean.get("type") not in (None, "DELIVERY", "BUY", "QUEUE", "OTHER"):
            clean.pop("type", None)

        title = clean.get("title")
        if isinstance(title, str) and len(title) > 64:
            clean["title"] = title[:64]

        desc = clean.get("description")
        if isinstance(desc, str) and len(desc) > 300:
            clean["description"] = desc[:300]

        try:
            merged = {**draft.model_dump(), **clean}
            TaskDraft.model_validate(merged)
        except Exception:
            return {}
        return clean


class CircuitBreakerExtractor(Extractor):
    """可选的熔断包装：模型连续失败时，一段时间内直接走兜底。

    模型服务抖动时，避免每条消息都干等一次超时。
    """

    def __init__(
        self,
        primary: Extractor,
        fallback: Extractor | None = None,
        failure_threshold: int = 5,
        cooldown_seconds: float = 30.0,
    ) -> None:
        self.primary = primary
        self.fallback = fallback or RuleExtractor()
        self.failure_threshold = failure_threshold
        self.cooldown_seconds = cooldown_seconds
        self._failures = 0
        self._open_until = 0.0

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        if time.time() < self._open_until:
            return self.fallback.extract(message, draft)
        try:
            patch = self.primary.extract(message, draft)
            self._failures = 0
            return patch
        except Exception:
            self._failures += 1
            if self._failures >= self.failure_threshold:
                self._open_until = time.time() + self.cooldown_seconds
                self._failures = 0
            return self.fallback.extract(message, draft)


def default_extractor() -> Extractor:
    """按环境变量决定用真实模型还是规则兜底。

    设置 LLM_ENABLED=true 并填好地址/密钥/模型名后自动走 LLMExtractor；
    未配置时走 RuleExtractor，保证 Demo 随时可跑。
    """
    settings = LLMSettings.from_env()
    if settings.usable():
        return CircuitBreakerExtractor(primary=LLMExtractor(settings), fallback=RuleExtractor())
    return RuleExtractor()
