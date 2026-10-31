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
from .agent.intent import Intent
from .models import TYPE_LABELS, TaskDraft, next_question, required_slots


@dataclass
class Session:
    id: str
    # 一次聊天（面板）下可以有多个会话：一单发布后换个会话继续
    panel_id: str | None = None
    draft: TaskDraft = field(default_factory=TaskDraft)
    turns: int = 0
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    history: list[dict[str, object]] = field(default_factory=list)


class SessionStore:
    """Demo 用内存会话表；替换成 Redis 时保持同样接口即可。"""

    def __init__(self) -> None:
        self._sessions: dict[str, Session] = {}

    def create(self, user_id: str | None = None, panel_id: str | None = None) -> Session:
        session = Session(id=str(uuid.uuid4()), panel_id=panel_id or str(uuid.uuid4()))
        self._sessions[session.id] = session
        return session

    def get_active_session(self, panel_id: str) -> Session | None:
        for session in self._sessions.values():
            if session.panel_id == panel_id:
                return session
        return None

    def ensure_session(self, panel_id: str, user_id: str | None = None) -> Session:
        return self.get_active_session(panel_id) or self.create(user_id, panel_id)

    def append_message(
        self, session_id: str, role: str, content: str, meta: dict | None = None
    ) -> None:
        # 内存版不存消息表，历史只在进程内有意义
        return None

    def messages(self, session_id: str) -> list[dict[str, object]]:
        return []

    def panel_messages(self, panel_id: str, limit: int = 200, before_id: int | None = None) -> list[dict[str, object]]:
        return []

    def list_panels(self, user_id: str, limit: int = 20) -> list[dict[str, object]]:
        return []

    def delete_panel(self, panel_id: str) -> bool:
        ids = [s.id for s in self._sessions.values() if s.panel_id == panel_id]
        for sid in ids:
            self._sessions.pop(sid, None)
        return bool(ids)

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

    def mark_published(self, session_id: str | None) -> None:
        """发布成功后把会话标记成 CLOSED。

        不标记的话，用户从历史里点开这一单继续说，会被当成"给已发布的草稿补内容"，
        新任务就带着上一单的字段开始了。
        """
        if not session_id:
            return
        session = self.store.get(session_id)
        if session is None:
            return
        try:
            self.store.save(session, status="CLOSED")
        except TypeError:
            # 内存版 save 只收 session，没有状态列可写
            self.store.save(session)

    def record_cancelled(self, panel_id: str | None = None,
                         session_id: str | None = None,
                         announce: bool = True) -> None:
        """取消这一单：会话置 CANCELED、卡片标记已取消、按需补提示消息。

        和发布走同一套收尾，只是一单"没发出去"。会话结束后面板仍可用，
        下一句话会自动开新会话，用户不用刷新页面。
        """
        target = session_id
        if target is None and panel_id:
            session = self.store.get_active_session(panel_id)
            target = session.id if session is not None else None
        if target is None:
            return

        session = self.store.get(target)
        if session is not None:
            try:
                self.store.save(session, status="CANCELED")
            except TypeError:
                self.store.save(session)
        mark_card = getattr(self.store, "mark_card_cancelled", None)
        if mark_card is not None:
            mark_card(target)
        if announce and hasattr(self.store, "append_message"):
            self.store.append_message(
                target, "assistant",
                "任务已取消，这一单不会发布。",
            )
            self.store.append_message(
                target, "assistant",
                "接下来想发什么任务？直接告诉我就行。",
            )

    def record_published(self, errand_id: str, panel_id: str | None = None,
                         session_id: str | None = None) -> None:
        """发布成功后的收尾：结束会话、把卡片标记成已发布、补两条提示消息。

        这三件事都要落库，否则刷新页面后：会话还开着（下一单写进旧草稿）、
        卡片变回可编辑、也看不到"已发布"的提示。
        """
        target = session_id
        if target is None and panel_id:
            session = self.store.get_active_session(panel_id)
            target = session.id if session is not None else None
        if target is None:
            return

        self.mark_published(target)
        mark_card = getattr(self.store, "mark_card_published", None)
        if mark_card is not None:
            mark_card(target, errand_id)
        if hasattr(self.store, "append_message"):
            self.store.append_message(
                target, "assistant",
                f"任务已发布，编号 {errand_id}。可以在「我的任务」里查看进度。",
            )
            self.store.append_message(
                target, "assistant",
                "这一单已经结束，接下来想发什么任务？直接告诉我就行。",
            )

    def mark_published_panel(self, panel_id: str | None) -> None:
        # 前端只记 panel：把该面板下进行中的会话标记为已结束。
        if not panel_id:
            return
        session = self.store.get_active_session(panel_id)
        if session is not None:
            self.mark_published(session.id)

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
        return self._chat(session, message)

    def chat_panel(self, panel_id: str, message: str, user_id: str | None = None) -> AgentReply:
        # 面板入口：自动挑进行中的会话，没有就开一个。
        # 前端只记 panel_id，就不用再操心"该用哪个 session"，
        # 也不会出现发布后还往旧草稿里写的问题。
        session = self.store.ensure_session(panel_id, user_id)
        return self._chat(session, message)

    def create_panel(self, user_id: str | None = None) -> dict[str, str]:
        # 新建一段对话：此刻只发一个 panel_id，真正开 session 等第一条消息。
        import uuid as _uuid

        panel_id = str(_uuid.uuid4())
        self.store.ensure_session(panel_id, user_id)
        return {"panel_id": panel_id}

    def panel_messages(self, panel_id: str, limit: int = 200, before_id: int | None = None):
        return self.store.panel_messages(panel_id, limit=limit, before_id=before_id)

    def list_panels(self, user_id: str, limit: int = 20):
        return self.store.list_panels(user_id, limit)

    def delete_panel(self, panel_id: str) -> bool:
        return bool(self.store.delete_panel(panel_id))

    def _chat(self, session, message: str) -> AgentReply:

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
        # 图里可能给追问加过说明（例如"这句不太像地点"），优先用它
        question = (state.get("question") or next_question(missing, draft=draft)) if status != "READY" else None
        # 只有本轮真的追加了内容才传 True；首次完整和"没有了"都要区分开
        appended = state.get("appended") if status == "READY" else None
        intent = state.get("intent") if status == "READY" else None

        session.draft = draft
        session.turns += 1

        # 先把回复算出来，再落库。
        # 之前这里用的是 state["message"]——那是**用户输入**，
        # 结果 assistant 消息存成了用户原话的复读，刷新后整段历史都是废的。
        reply = draft_to_reply(status, draft, missing, question, appended, intent,
                               list(state.get("updated_fields") or []),
                               bool(state.get("rejected_location")))
        reply_text = reply.get("message") or question or ""

        session.history.append({"role": "user", "content": message})
        session.history.append({"role": "assistant", "content": reply_text})

        try:
            self.store.save(session, status=status, last_message=message)
        except TypeError:
            self.store.save(session)

        # 卡片状态跟着消息一起存：刷新页面才能把草稿卡片画回来
        card_meta = None
        if status == "READY":
            card_meta = {
                "draft": draft.model_dump(mode="json"),
                "type_label": TYPE_LABELS.get(str(draft.type), None) if draft.type else None,
            }
        if hasattr(self.store, "append_message"):
            self.store.append_message(session.id, "user", message)
            self.store.append_message(session.id, "assistant", reply_text, card_meta)

        # 用户在对话里说"取消"：和点卡片取消一样收尾，但不再重复播报
        if status == "READY" and intent == Intent.CANCEL:
            self.record_cancelled(session_id=session.id, announce=False)
        # 让前端知道这轮落在哪个会话上（同一个面板下，发布后会换成新会话）
        reply["session_id"] = session.id
        reply["panel_id"] = session.panel_id
        return reply
