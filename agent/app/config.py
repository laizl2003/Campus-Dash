"""LLM 配置：从环境变量读取，不把密钥写进代码。

接入真实模型时只需要设置环境变量，不用改代码：

    LLM_ENABLED=true
    LLM_BASE_URL=https://api.llm.ustc.edu.cn/v1
    LLM_API_KEY=你的密钥
    LLM_MODEL=qwen3.8-reasoner
    LLM_TIMEOUT=60
    LLM_TEMPERATURE=0
    LLM_JSON_MODE=true

docker-compose 里对应写到 agent 服务的 environment 段。
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class LLMSettings:
    enabled: bool = False
    base_url: str = ""
    api_key: str = ""
    model: str = ""
    timeout: float = 20.0
    temperature: float = 0.0
    max_tokens: int = 512
    # 是否请求 JSON 输出模式；不支持的厂商会自动去掉这个字段重试
    json_mode: bool = True

    @classmethod
    def from_env(cls) -> "LLMSettings":
        return cls(
            enabled=_env_bool("LLM_ENABLED", False),
            base_url=os.getenv("LLM_BASE_URL", "").rstrip("/"),
            api_key=os.getenv("LLM_API_KEY", ""),
            model=os.getenv("LLM_MODEL", ""),
            timeout=float(os.getenv("LLM_TIMEOUT", "20")),
            temperature=float(os.getenv("LLM_TEMPERATURE", "0")),
            max_tokens=int(os.getenv("LLM_MAX_TOKENS", "512")),
            json_mode=_env_bool("LLM_JSON_MODE", True),
        )

    def usable(self) -> bool:
        """三个必填项齐了才算可用，避免半个配置进入调用分支。"""
        return self.enabled and bool(self.base_url) and bool(self.api_key) and bool(self.model)

    def missing_fields(self) -> list[str]:
        missing: list[str] = []
        if not self.base_url:
            missing.append("LLM_BASE_URL")
        if not self.api_key:
            missing.append("LLM_API_KEY")
        if not self.model:
            missing.append("LLM_MODEL")
        return missing
