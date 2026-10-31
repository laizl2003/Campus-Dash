"""会话服务：把状态机包装成一次对话调用。

前端只需要持有 conversation_id 和上一轮 draft，服务端负责：
1. 合并本轮抽取结果；
2. 计算缺失槽位；
3. 返回追问或完整草稿。

会话存储两种实现：
- SessionStore     进程内存，零依赖，适合本地随手跑
- MySQLSessionStore 落库，容器重启后历史对话还在（生产用这个）

设置 AGENT_MYSQL_DSN 后自动切换成 MySQL。
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from .agent.extractor import Extractor, default_extractor
from .agent.graph import AgentReply, build_graph, draft_to_reply
from .models import TaskDraft, next_question, required_slots


@dataclass
class Session:
    id: str
    draft: TaskDraft = field(default_factory=TaskDraft)
    turns: int = 0
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    history: list[dict[str, object]] = field(default_factory=list)


class SessionStore:
    """Demo 用内存会话表；替换成 Redis 时保持同样接口即可。"""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def create(self) -> Session:
        session = Session(id=str(uuid.uuid4()))
        self._sessions[session.id] = session
        return session

    def get(self, session_id: str) -> Session | None:
        return self._sessions.get(session_id)

    def save(self, session: Session) -> None:
        self._sessions[session.id] = session


def default_store() -> "SessionStore | MySQLSessionStore":
    """配了 DSN 就用 MySQL，否则退回内存。

    MySQL 连不上时不让整个服务起不来：打日志并降级到内存，
    用户仍能对话，只是重启后会话不保留。
    """
    import logging
    import os

    if not os.getenv("AGENT_MYSQL_DSN"):
        return SessionStore()
    try:
        from .store_mysql import MySQLSessionStore

        return MySQLSessionStore()
    except Exception as exc:  # noqa: BLE001
        logging.getLogger(__name__).warning("MySQL 会话存储不可用，降级为内存: %s", exc)
        return SessionStore()


class ChatService:
    def __init__(self, extractor: Extractor | None = None, store=None) -> None:
        self.extractor = extractor or default_extractor()
        self.store = store if store is not None else default_store()
        self.graph = build_graph(self.extractor)

    def create_session(self, user_id: str | None = None) -> Session:
        try:
            return self.store.create(user_id)
        except TypeError:
            # 内存版 create 不接收 user_id
            return self.store.create()

    def delete_session(self, session_id: str) -> bool:
        """删除会话。内存版直接丢字典，MySQL 版连消息一起删。"""
        if hasattr(self.store, "delete"):
            return bool(self.store.delete(session_id))
        existed = self.store.get(session_id) is not None
        if existed:
            self.store._sessions.pop(session_id, None)  # noqa: SLF001
        return existed

    def list_sessions(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        if hasattr(self.store, "list_sessions"):
            return self.store.list_sessions(user_id, limit)
        return []

    def messages(self, session_id: str) -> list[dict[str, object]]:
        if hasattr(self.store, "messages"):
            return self.store.messages(session_id)
        return []

    def chat(self, session_id: str, message: str) -> AgentReply:
        session = self.store.get(session_id)
        if session is None:
            raise KeyError(session_id)

        missing = required_slots(session.draft)
        state = self.graph.invoke(
            {
                "message": message,
                "draft": session.draft,
                "missing": missing,
            }
        )
        draft: TaskDraft = state["draft"]
        missing = state["missing"]
        status = state.get("status", "ASKING")
        question = next_question(missing) if status != "READY" else None
        # 只有本轮真的追加了内容才传 True；首次完整和"没有了"都要区分开
        appended = state.get("appended") if status == "READY" else None

        session.draft = draft
        session.turns += 1
        session.history.append({"role": "user", "content": message})
        reply_text = state.get("message") or question or ""
        session.history.append({"role": "assistant", "content": reply_text})

        try:
            self.store.save(session, status=status, last_message=message)
        except TypeError:
            self.store.save(session)
        if hasattr(self.store, "append_message"):
            self.store.append_message(session.id, "user", message)
            self.store.append_message(session.id, "assistant", reply_text)

        return draft_to_reply(status, draft, missing, question, appended)
