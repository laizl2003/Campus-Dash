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

    def append_message(self, session_id, role, content, meta=None, user_id=None) -> None:
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


def test_cancel_clears_draft():
    """取消 = 这一单作废：草稿被清空，会话置 CANCELED。"""
    from app.agent.extractor import Extractor
    from app.service import ChatService
    from app.models import TaskDraft

    class EmptyExtractor(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    service = ChatService(extractor=EmptyExtractor())
    session = service.store.create()
    session.draft = TaskDraft(
        type="DELIVERY", title="取快递", location_from="A", location_to="B", reward_cents=500
    )
    service.store.save(session)

    service.record_cancelled(session_id=session.id)

    reloaded = service.store.get(session.id)
    assert reloaded.draft.reward_cents is None
    assert reloaded.draft.location_from is None


class RecordingStore:
    """够用的内存 store：和 MySQL 一样会存消息与卡片 meta。"""

    def __init__(self):
        self.sessions = {}
        self.rows = {}
        self.status = {}

    def create(self, user_id=None, panel_id=None):
        import uuid
        from app.service import Session

        session = Session(id=str(uuid.uuid4()), panel_id=panel_id or str(uuid.uuid4()))
        self.sessions[session.id] = session
        return session

    def get(self, session_id):
        return self.sessions.get(session_id)

    def save(self, session, status=None, last_message=None):
        self.sessions[session.id] = session
        if status:
            self.status[session.id] = status

    def get_active_session(self, panel_id):
        for session in self.sessions.values():
            if session.panel_id == panel_id and self.status.get(session.id) not in ("CLOSED", "CANCELED"):
                return session
        return None

    def append_message(self, session_id, role, content, meta=None, user_id=None):
        self.rows.setdefault(session_id, []).append({"role": role, "content": content, "meta": meta or {}})

    def messages(self, session_id):
        return self.rows.get(session_id, [])

    def panel_messages(self, panel_id, limit = 200, before_id = None, user_id=None):
        rows = []
        for session in self.sessions.values():
            if session.panel_id == panel_id:
                rows.extend(self.rows.get(session.id, []))
        return rows[-limit:]

    def mark_card_published(self, session_id, errand_id):
        for row in reversed(self.rows.get(session_id, [])):
            if (row.get("meta") or {}).get("draft"):
                row["meta"]["publishedId"] = errand_id
                return 1
        return 0

    def mark_card_cancelled(self, session_id):
        for row in reversed(self.rows.get(session_id, [])):
            if (row.get("meta") or {}).get("draft"):
                row["meta"]["cancelled"] = True
                return 1
        return 0


def test_tool_publish_marks_reply_and_skips_card(monkeypatch):
    """工具发单成功后：回复带 published_id，且不再新增草稿卡（否则能再点一次发布）。"""
    from app.agent.extractor import Extractor
    from app.models import TaskDraft
    from app.service import ChatService
    from app.tools import actions

    class ReadyExtractor(Extractor):
        def extract(self, message, draft, history=None):
            return {}

    monkeypatch.setattr(
        actions,
        "publish_draft",
        lambda token, draft: {"ok": True, "errand_id": "999", "paid_yuan": 8.0},
    )

    service = ChatService(extractor=ReadyExtractor(), store=RecordingStore())
    monkeypatch.setattr(service, "_voucher_for", lambda *args, **kwargs: None)

    session = service.store.create()
    session.draft = TaskDraft(
        type="DELIVERY", title="取快递", location_from="A楼", location_to="B楼", reward_cents=800
    )
    service.store.save(session)

    # 先产生一张草稿卡
    service._chat(session, "再看看", "tok")
    cards_before = [r for r in service.store.messages(session.id) if (r.get("meta") or {}).get("draft")]
    assert len(cards_before) == 1

    # 这一轮被判成"发布"
    import app.agent.graph as graph_module
    from app.agent.intent import Intent

    monkeypatch.setattr(
        graph_module, "_classify", lambda extractor, message, draft: Intent.PUBLISH
    )
    reply = service._chat(session, "发布吧", "tok")

    assert reply["status"] == "READY"
    assert reply["published_id"] == "999"

    cards_after = [r for r in service.store.messages(session.id) if (r.get("meta") or {}).get("draft")]
    assert len(cards_after) == 1, "工具发布后不应再新增草稿卡"
    assert cards_after[0]["meta"]["publishedId"] == "999"
