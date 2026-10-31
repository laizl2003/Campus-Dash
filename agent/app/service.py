"""会话服务：把状态机包装成一次对话调用。

前端只需要持有 conversation_id 和上一轮 draft，服务端负责：
1. 合并本轮抽取结果；
2. 计算缺失槽位；
3. 返回追问或完整草稿。

本 Demo 的会话保存在进程内存里；接 Redis/MySQL 时替换 store 即可。
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


class ChatService:
    def __init__(self, extractor: Extractor | None = None, store: SessionStore | None = None) -> None:
        self.extractor = extractor or default_extractor()
        self.store = store or SessionStore()
        self.graph = build_graph(self.extractor)

    def create_session(self) -> Session:
        return self.store.create()

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

        session.draft = draft
        session.turns += 1
        session.history.append({"role": "user", "content": message})
        session.history.append({"role": "assistant", "content": state.get("message") or question or ""})
        self.store.save(session)

        return draft_to_reply(status, draft, missing, question)
