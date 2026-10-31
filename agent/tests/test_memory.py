"""短期记忆：把最近几轮拼进 prompt，冲突以靠后为准。"""

from __future__ import annotations

from datetime import datetime

from app.agent.extractor import Extractor
from app.agent.prompts import MAX_HISTORY_MESSAGES, SYSTEM_PROMPT, build_user_prompt
from app.models import Slot, TaskDraft, required_slots
from app.service import ChatService, Session


def _history() -> list[dict]:
    return [
        {"role": "user", "content": "帮我取快递"},
        {"role": "assistant", "content": "从哪里取货或开始？"},
        {"role": "user", "content": "菜鸟驿站"},
    ]


def test_prompt_contains_recent_turns():
    prompt = build_user_prompt(
        "改成西区东门", TaskDraft(type="DELIVERY"), [Slot.LOCATION_FROM],
        now=datetime(2026, 11, 1, 10, 0, 0), history=_history(),
    )
    assert "最近对话" in prompt
    assert "用户：菜鸟驿站" in prompt
    assert "助手：从哪里取货或开始？" in prompt


def test_prompt_without_history_has_no_block():
    prompt = build_user_prompt("帮我取快递", TaskDraft(), [Slot.TYPE], now=datetime(2026, 11, 1, 10, 0, 0))
    assert "最近对话" not in prompt


def test_history_is_truncated():
    long_history = [
        {"role": "user", "content": "x" * 300},
    ] * 20
    prompt = build_user_prompt(
        "还有呢", TaskDraft(), [Slot.TYPE], now=datetime(2026, 11, 1, 10, 0, 0), history=long_history,
    )
    # 只保留最近 8 条，每条截断到 120 字
    assert prompt.count("用户：") == MAX_HISTORY_MESSAGES
    assert "x" * 121 not in prompt


def test_system_prompt_says_later_wins_on_conflict():
    assert "靠后的说法" in SYSTEM_PROMPT
    assert "冲突" in SYSTEM_PROMPT


class RecordingExtractor(Extractor):
    """记录每次抽取收到的 history，验证 service 真的传下来了。"""

    def __init__(self) -> None:
        self.seen: list[list[dict] | None] = []

    def extract(self, message, draft, history=None):
        self.seen.append(history)
        patch: dict[str, object] = {}
        if draft.type is None:
            patch["type"] = "DELIVERY"
        return patch


class MemoryStore:
    """带 messages 的内存 store，模拟从库里取历史。"""

    def __init__(self) -> None:
        self.sessions: list[Session] = []
        self.rows: dict[str, list[dict]] = {}

    def create(self, user_id=None, panel_id=None) -> Session:
        session = Session(id=f"s{len(self.sessions) + 1}", panel_id=panel_id or "p1")
        self.sessions.append(session)
        return session

    def get(self, session_id):
        return next((s for s in self.sessions if s.id == session_id), None)

    def get_active_session(self, panel_id):
        return next((s for s in self.sessions if s.panel_id == panel_id), None)

    def ensure_session(self, panel_id, user_id=None):
        return self.get_active_session(panel_id) or self.create(user_id, panel_id)

    def save(self, session, status=None, last_message=None) -> None:
        return None

    def messages(self, session_id: str):
        return self.rows.get(session_id, [])

    def append_message(self, session_id, role, content, meta=None):
        self.rows.setdefault(session_id, []).append({"role": role, "content": content})


def test_service_passes_history_to_extractor():
    extractor = RecordingExtractor()
    store = MemoryStore()
    service = ChatService(extractor=extractor, store=store)
    session = store.create(panel_id="p1")

    service.chat(session.id, "帮我取快递")
    service.chat(session.id, "菜鸟驿站")

    # 第一轮没有历史，第二轮能拿到第一轮的消息
    assert extractor.seen[0] == []
    assert extractor.seen[1], "第二轮应该带上短期记忆"
    assert extractor.seen[1][0]["content"] == "帮我取快递"
