"""标题锁定与会话删除的测试。"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

import pytest

from app.agent.extractor import Extractor
from app.agent.intent import Intent
from app.models import TaskDraft, merge_draft
from app.service import ChatService


def test_title_lock_blocks_overwrite():
    draft = TaskDraft(type="DELIVERY", title="取快递", reward_cents=800)
    merged = merge_draft(draft, {"title": "取外卖送到3号楼502", "description": "外卖有汤"})
    assert merged.title == "取快递"
    assert merged.description == "外卖有汤"


def test_allow_locked_lets_edit_change_title():
    draft = TaskDraft(type="DELIVERY", title="取快递", reward_cents=800)
    merged = merge_draft(draft, {"title": "取外卖"}, allow_locked=True)
    assert merged.title == "取外卖"


def test_empty_title_can_still_be_filled():
    draft = TaskDraft(type="DELIVERY", reward_cents=800)
    merged = merge_draft(draft, {"title": "取快递"})
    assert merged.title == "取快递"


class MentioningExtractor(Extractor):
    def __init__(self, intent: Intent | None = None) -> None:
        self.fixed_intent = intent

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if "外卖" in message:
            patch["title"] = "取外卖送到3号楼502"
        if "不要撒" in message:
            patch["description"] = "外卖有汤，不要撒了"
        return patch

    def classify_intent(self, message: str, draft: TaskDraft) -> Intent | None:
        return self.fixed_intent


def _full_draft() -> TaskDraft:
    return TaskDraft(
        type="DELIVERY", title="取快递",
        location_from="菜鸟驿站", location_to="3号楼502",
        start_time=datetime.now() + timedelta(days=1),
        reward_cents=800,
    )


def test_title_not_rewritten_by_casual_mention():
    service = ChatService(MentioningExtractor())
    session = service.create_session()
    session.draft = _full_draft()
    reply = service.chat(session.id, "外卖有汤，不要撒了")
    assert reply["draft"]["title"] == "取快递"


def test_edit_intent_can_rewrite_title():
    service = ChatService(MentioningExtractor(Intent.EDIT))
    session = service.create_session()
    session.draft = _full_draft()
    reply = service.chat(session.id, "改成外卖那个说法")
    assert reply["status"] == "READY"
    assert reply["intent"] == "EDIT"
    assert reply["draft"]["title"] == "取外卖送到3号楼502"


@pytest.mark.skipif(not os.getenv("AGENT_MYSQL_DSN"), reason="needs MySQL")
def test_delete_removes_messages_and_session():
    from app.store_mysql import MySQLSessionStore

    store = MySQLSessionStore()
    session = store.create("test-user-delete")
    store.append_message(session.id, "user", "hello")
    store.append_message(session.id, "assistant", "hi")
    assert store.message_count(session.id) == 2

    assert store.delete(session.id) is True
    assert store.get(session.id) is None
    assert store.message_count(session.id) == 0
