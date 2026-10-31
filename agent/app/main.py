"""FastAPI 入口：创建会话、多轮对话。

路由前缀 /agent 由 nginx 反代，主前端通过同源地址调用。
本 Demo 不调用主项目发布接口，只返回可确认的草稿（READY 状态）。
"""

from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

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
