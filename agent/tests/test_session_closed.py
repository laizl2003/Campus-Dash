"""发布后把会话标记为 CLOSED：历史里的单子不能被当成新草稿继续写。"""

from __future__ import annotations

from app.models import TaskDraft
from app.service import ChatService, Session


class RecordingStore:
    """最小内存 store，记录每次 save 的 status。"""

    def __init__(self) -> None:
        self.sessions: dict[str, Session] = {}
        self.statuses: list[str | None] = []

    def create(self) -> Session:
        session = Session(id="s1")
        self.sessions[session.id] = session
        return session

    def get(self, session_id: str) -> Session | None:
        return self.sessions.get(session_id)

    def save(self, session: Session, status: str | None = None) -> None:
        self.sessions[session.id] = session
        self.statuses.append(status)


def test_mark_published_sets_closed():
    store = RecordingStore()
    service = ChatService(store=store)
    session = store.create()
    session.draft = TaskDraft(type="DELIVERY", title="取快递", reward_cents=800)

    service.mark_published(session.id)

    assert store.statuses[-1] == "CLOSED"


def test_mark_published_ignores_unknown_session():
    """历史 id 过期或没传 sessionId 时不能报错。"""
    store = RecordingStore()
    service = ChatService(store=store)

    service.mark_published("不存在的会话")
    service.mark_published(None)

    assert store.statuses == []


def test_mark_published_does_not_touch_draft():
    """只改状态，草稿要原样保留，历史回看才有内容。"""
    store = RecordingStore()
    service = ChatService(store=store)
    session = store.create()
    session.draft = TaskDraft(type="DELIVERY", title="取快递", reward_cents=800)

    service.mark_published(session.id)

    assert store.sessions[session.id].draft.title == "取快递"
