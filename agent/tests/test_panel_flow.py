"""面板语义：一单结束后，同一面板下一条消息要换新会话。"""

from __future__ import annotations

from app.agent.extractor import Extractor
from app.models import TaskDraft
from app.service import ChatService, Session


class StubExtractor(Extractor):
    """只认类型，其余交给规则，验证会话切换不依赖模型。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if draft.type is None and ("快递" in message or "奶茶" in message):
            patch["type"] = "DELIVERY"
        return patch


class PanelStore:
    """内存版面板存储，带 status，能验证 CLOSED 之后的切换。"""

    def __init__(self) -> None:
        self.sessions: list[Session] = []
        self.status: dict[str, str] = {}
        self.messages: dict[str, list[dict[str, object]]] = {}

    def create(self, user_id=None, panel_id=None) -> Session:
        session = Session(id=f"s{len(self.sessions) + 1}", panel_id=panel_id or "p1")
        self.sessions.append(session)
        return session

    def get(self, session_id: str):
        return next((s for s in self.sessions if s.id == session_id), None)

    def get_active_session(self, panel_id: str):
        for session in reversed(self.sessions):
            if session.panel_id == panel_id and self.status.get(session.id) != "CLOSED":
                return session
        return None

    def ensure_session(self, panel_id: str, user_id=None) -> Session:
        return self.get_active_session(panel_id) or self.create(user_id, panel_id)

    def save(self, session: Session, status: str | None = None, last_message=None) -> None:
        if status:
            self.status[session.id] = status

    def append_message(self, session_id: str, role: str, content: str) -> None:
        self.messages.setdefault(session_id, []).append({"role": role, "content": content})

    def messages(self, session_id: str):
        return self.messages.get(session_id, [])


def _service() -> tuple[ChatService, PanelStore]:
    store = PanelStore()
    return ChatService(extractor=StubExtractor(), store=store), store


def test_same_panel_reuses_active_session():
    service, store = _service()
    first = service.chat_panel("p1", "帮我取快递")
    second = service.chat_panel("p1", "还要送到 3 号楼")

    assert first["session_id"] == second["session_id"]
    assert len(store.sessions) == 1


def test_publish_closes_session_and_next_message_opens_new_one():
    service, store = _service()
    first = service.chat_panel("p1", "帮我取快递")
    service.mark_published(first["session_id"])

    second = service.chat_panel("p1", "再帮我买杯奶茶")

    assert second["session_id"] != first["session_id"]
    assert len(store.sessions) == 2
    assert store.status[first["session_id"]] == "CLOSED"
    # 新会话是全新草稿，不会带着上一单的内容
    assert second["draft"]["title"] != first["draft"]["title"]


def test_mark_published_panel_closes_active_session():
    service, store = _service()
    reply = service.chat_panel("p1", "帮我取快递")
    service.mark_published_panel("p1")

    assert store.status[reply["session_id"]] == "CLOSED"


def test_panels_are_isolated():
    service, store = _service()
    a = service.chat_panel("p1", "帮我取快递")
    b = service.chat_panel("p2", "帮我取快递")

    assert a["session_id"] != b["session_id"]
    assert len(store.sessions) == 2
