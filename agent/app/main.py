"""FastAPI 入口：创建会话、多轮对话。

路由前缀 /agent 由 nginx 反代，主前端通过同源地址调用。
本 Demo 不调用主项目发布接口，只返回可确认的草稿（READY 状态）。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import os

import httpx

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from .agent.extractor import CircuitBreakerExtractor, LLMExtractor, RuleExtractor
from .config import LLMSettings
from .models import TYPE_LABELS
from .service import ChatService

logger = logging.getLogger(__name__)

# 空会话（只建了会话没说话）默认每天清一次
PURGE_INTERVAL_SECONDS = int(os.getenv("AGENT_PURGE_INTERVAL_SECONDS", "86400"))


async def _purge_loop() -> None:
    # 定时清理空会话，避免 agent_session 被建会话的请求撑大。
    # 只删 turns == 0 且超过一天没动过的行，说过话的会话不受影响。
    while True:
        try:
            await asyncio.sleep(PURGE_INTERVAL_SECONDS)
            purge = getattr(service.store, "purge_empty", None)
            if purge is None:
                continue
            removed = await asyncio.to_thread(purge)
            if removed:
                logger.info("已清理 %s 个空会话", removed)
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            logger.exception("清理空会话失败，下一轮重试")


@contextlib.asynccontextmanager
async def lifespan(_: FastAPI):
    task = asyncio.create_task(_purge_loop())
    try:
        yield
    finally:
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="CampusDash Agent", version="0.1.0", lifespan=lifespan)
service = ChatService()


class ChatRequest(BaseModel):
    # 二选一：panel_id（推荐，自动挑进行中的会话）或 session_id（兼容旧前端）
    panel_id: str | None = None
    session_id: str | None = None
    message: str = Field(min_length=1, max_length=500)

    def resolved_panel(self) -> str | None:
        return self.panel_id


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


@app.post("/panels")
def create_panel(authorization: str | None = Header(default=None)) -> dict[str, object]:
    """新建一段对话。一段对话里可以发多单，一单结束就换会话。"""
    user_id = _current_user(authorization)
    panel = service.create_panel(user_id)
    return {**panel, "user_id": user_id}


@app.get("/panels")
def list_panels(limit: int = 20, authorization: str | None = Header(default=None)) -> dict[str, object]:
    """当前用户的对话列表（按面板聚，不是一个会话一条）。"""
    user_id = _current_user(authorization)
    if not user_id:
        return {"panels": []}
    return {"panels": service.list_panels(user_id, limit)}


@app.get("/panels/{panel_id}/messages")
def panel_messages(panel_id: str, limit: int = 200, before_id: int | None = None) -> dict[str, object]:
    """整段对话的消息，按雪花 id 排序即真实顺序。"""
    return {"messages": service.panel_messages(panel_id, limit=limit, before_id=before_id)}


@app.delete("/panels/{panel_id}")
def delete_panel(panel_id: str) -> dict[str, bool]:
    return {"deleted": service.delete_panel(panel_id)}


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
    # 发布成功后要把这条会话标记为 CLOSED；不属于主项目接口字段，转发时剔除
    sessionId: str | None = Field(default=None, exclude=True)
    # 前端只记 panel 时走这个：把该面板下进行中的会话置为已结束
    panelId: str | None = Field(default=None, exclude=True)


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
    # 这一单已经落地：会话到此为止，避免后续消息继续往旧草稿里追加
    if req.sessionId:
        service.mark_published(req.sessionId)
    else:
        service.mark_published_panel(req.panelId)
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
def chat(req: ChatRequest, authorization: str | None = Header(default=None)) -> dict[str, object]:
    try:
        if req.panel_id:
            # 前端只记 panel：服务端自动挑"进行中"的会话，发布后自然换新会话
            reply = service.chat_panel(req.panel_id, req.message, _current_user(authorization))
        elif req.session_id:
            reply = service.chat(req.session_id, req.message)
        else:
            raise HTTPException(status_code=400, detail="缺少 panel_id") from None
    except KeyError:
        raise HTTPException(status_code=404, detail="session not found") from None
    draft = reply["draft"]
    draft_type = draft.get("type")
    return {
        **reply,
        "type_label": TYPE_LABELS.get(str(draft_type), None) if draft_type else None,
    }
