"""取消任务：会话置 CANCELED，和已发布(CLOSED)区分，面板继续可用。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.models import TaskDraft
from app.service import ChatService, Session


class StubExtractor(Extractor):
    def extract(
        self, message: str, draft: TaskDraft, history: list[dict] | None = None
    ) -> dict[str, object]:
        patch: dict[str, object] = {}
        if draft.type is None:
            patch["type"] = "DELIVERY"
        if "8块" in message:
            patch["reward_cents"] = 800
        return patch


class PanelStore:
    def __init__(self) -> None:
        self.sessions: list[Session] = []
        self.status: dict[str, str] = {}
        self.cancelled: set[str] = set()
        self.messages: list[dict[str, object]] = []

    def create(self, user_id=None, panel_id=None) -> Session:
        session = Session(id=f"s{len(self.sessions) + 1}", panel_id=panel_id or "p1")
        self.sessions.append(session)
        return session

    def get(self, session_id: str):
        return next((s for s in self.sessions if s.id == session_id), None)

    def get_active_session(self, panel_id: str):
        for session in reversed(self.sessions):
            if session.panel_id == panel_id and self.status.get(session.id) not in ("CLOSED", "CANCELED"):
                return session
        return None

    def ensure_session(self, panel_id: str, user_id=None) -> Session:
        return self.get_active_session(panel_id) or self.create(user_id, panel_id)

    def save(self, session: Session, status: str | None = None, last_message=None) -> None:
        if status:
            self.status[session.id] = status

    def append_message(self, session_id, role, content, meta=None) -> None:
        self.messages.append({"session": session_id, "role": role, "content": content})

    def mark_card_cancelled(self, session_id: str) -> int:
        self.cancelled.add(session_id)
        return 1

    def mark_card_published(self, session_id: str, errand_id: str) -> int:
        return 1


def _service() -> tuple[ChatService, PanelStore]:
    store = PanelStore()
    return ChatService(extractor=StubExtractor(), store=store), store


def test_cancel_closes_session_with_canceled_status():
    service, store = _service()
    first = service.chat_panel("p1", "帮我取快递，8块")

    service.record_cancelled(panel_id="p1")

    assert store.status[first["session_id"]] == "CANCELED"
    assert first["session_id"] in store.cancelled


def test_cancel_keeps_panel_usable_and_opens_new_session():
    """取消后继续对话：同一面板，新会话。"""
    service, store = _service()
    first = service.chat_panel("p1", "帮我取快递，8块")
    service.record_cancelled(panel_id="p1")

    second = service.chat_panel("p1", "帮我买杯奶茶，8块")

    assert second["session_id"] != first["session_id"]
    assert second["panel_id"] == "p1"
    assert store.status[first["session_id"]] == "CANCELED"


def test_cancel_announces_two_messages():
    service, store = _service()
    service.chat_panel("p1", "帮我取快递，8块")
    before = len(store.messages)

    service.record_cancelled(panel_id="p1")

    added = store.messages[before:]
    assert len(added) == 2
    assert "已取消" in added[0]["content"]


def test_chat_cancel_intent_marks_cancelled_without_extra_broadcast():
    service, store = _service()
    service.chat("s0", "x") if False else None
    session = store.create(panel_id="p9")
    for text in ("帮我取快递，8块", "菜鸟驿站东门", "3号楼502"):
        service.chat(session.id, text)
    before = len(store.messages)

    reply = service.chat(session.id, "取消")

    assert reply["intent"] == "CANCEL"
    assert store.status[session.id] == "CANCELED"
    # 对话里取消只回一句话，不再多播两条
    assert len(store.messages) - before == 2
