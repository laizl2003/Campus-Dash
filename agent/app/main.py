"""FastAPI 入口：创建会话、多轮对话。

路由前缀 /agent 由 nginx 反代，主前端通过同源地址调用。
本 Demo 不调用主项目发布接口，只返回可确认的草稿（READY 状态）。
"""

from __future__ import annotations

import httpx
import os

from fastapi import FastAPI, Header, HTTPException
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


def _current_user(authorization: str | None) -> str | None:
    """从主项目会话里取当前用户 id。

    这里不重复实现鉴权：调用主项目 /api/auth/me 由它校验 token，
    能拿到 userId 就说明登录态有效。取不到也允许匿名使用，只是历史无法按人隔离。
    """
    if not authorization:
        return None
    backend = os.getenv("DASH_BACKEND_URL", "http://app:8080")
    try:
        resp = httpx.get(f"{backend}/api/auth/me", headers={"Authorization": authorization}, timeout=5.0)
        if resp.status_code >= 400:
            return None
        body = resp.json().get("data") or {}
        return str(body.get("userId") or "") or None  # 与主项目 CurrentUser 一致：字符串 id
    except Exception:  # noqa: BLE001
        return None


@app.post("/sessions")
def create_session(authorization: str | None = Header(default=None)) -> dict[str, object]:
    user_id = _current_user(authorization)
    session = service.create_session(user_id)
    return {"session_id": session.id, "user_id": user_id}


@app.get("/sessions")
def list_sessions(authorization: str | None = Header(default=None)) -> dict[str, object]:
    """当前用户的会话列表，最多 20 条。"""
    user_id = _current_user(authorization)
    if not user_id:
        return {"sessions": []}
    return {"sessions": service.list_sessions(user_id)}


@app.delete("/sessions/{session_id}")
def delete_session(session_id: str) -> dict[str, bool]:
    """删除会话，MySQL 版会连消息一起删掉。"""
    return {"deleted": service.delete_session(session_id)}


@app.get("/sessions/{session_id}/messages")
def session_messages(session_id: str) -> dict[str, object]:
    return {"messages": service.messages(session_id)}


class PublishRequest(BaseModel):
    """与主项目 POST /api/errands 的请求体保持一致。"""

    campusId: int = 1
    type: str
    title: str
    rewardCents: int
    slotTotal: int = 1
    locationFrom: str | None = None
    locationTo: str | None = None
    expiredAt: str | None = None
    description: str | None = None


@app.post("/publish")
def publish(req: PublishRequest, authorization: str | None = Header(default=None)) -> dict[str, object]:
    """把确认后的草稿转发给主项目发单接口。

    身份直接透传浏览器带来的 Authorization，
    Agent 服务不保存也不校验 token，鉴权仍由主项目负责。
    """
    if not authorization:
        raise HTTPException(status_code=401, detail="缺少登录态")

    backend = os.getenv("DASH_BACKEND_URL", "http://app:8080")
    try:
        resp = httpx.post(
            f"{backend}/api/errands",
            headers={"Authorization": authorization, "Content-Type": "application/json"},
            json=req.model_dump(),
            timeout=15.0,
        )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"主服务不可用: {exc}") from exc

    try:
        data = resp.json()
    except ValueError:
        raise HTTPException(status_code=502, detail="主服务返回异常") from None

    if resp.status_code >= 400 or data.get("code") != "OK":
        # 把主项目的业务错误原样带回，前端能直接提示"余额不足"等原因
        raise HTTPException(status_code=400, detail=data.get("message") or "发布失败")

    body = data.get("data") or {}
    return {"errandId": str(body.get("errandId", "")), "status": body.get("status")}


class PingRequest(BaseModel):
    session_id: str


@app.post("/chat/ping")
def ping(req: PingRequest) -> dict[str, bool]:
    """检查会话是否还在。

    会话存在进程内存里，服务重启后旧 id 就失效了。
    前端发送前先探一下，失效就自动新建，用户不会看到 404 报错。
    """
    return {"alive": service.store.get(req.session_id) is not None}


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
