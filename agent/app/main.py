"""FastAPI 入口：创建会话、多轮对话。

路由前缀 /agent 由 nginx 反代，主前端通过同源地址调用。
本 Demo 不调用主项目发布接口，只返回可确认的草稿（READY 状态）。
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .agent.extractor import CircuitBreakerExtractor, LLMExtractor, RuleExtractor
from .config import LLMSettings
from .models import TYPE_LABELS
from .service import ChatService

app = FastAPI(title="CampusDash Agent", version="0.1.0")
service = ChatService()


class ChatRequest(BaseModel):
    session_id: str
    message: str = Field(min_length=1, max_length=500)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/status")
def status() -> dict[str, object]:
    """排查用：模型是否真的接上了、缺哪些配置。

    网关（nginx）会把 /agent 前缀去掉再转发，
    所以这里注册的是 /status，对外访问是 /agent/status。
    """
    settings = LLMSettings.from_env()
    extractor = service.extractor
    if isinstance(extractor, CircuitBreakerExtractor):
        extractor_name = type(extractor.primary).__name__
    else:
        extractor_name = type(extractor).__name__
    return {
        "extractor": extractor_name,
        "llm_enabled": settings.enabled,
        "llm_usable": settings.usable(),
        "model": settings.model or None,
        "missing_config": settings.missing_fields(),
    }


@app.post("/sessions")
def create_session() -> dict[str, str]:
    session = service.create_session()
    return {"session_id": session.id}


@app.post("/chat")
def chat(req: ChatRequest) -> dict[str, object]:
    try:
        reply = service.chat(req.session_id, req.message)
    except KeyError:
        raise HTTPException(status_code=404, detail="session not found") from None
    draft = reply["draft"]
    draft_type = draft.get("type")
    return {
        **reply,
        "type_label": TYPE_LABELS.get(str(draft_type), None) if draft_type else None,
    }
