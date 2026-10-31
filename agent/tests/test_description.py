"""信息完整后追加任务内容的测试。"""

from __future__ import annotations

from app.agent.extractor import EmptyExtractor, Extractor
from app.models import TaskDraft, append_description, is_no_more
from app.service import ChatService


class OneShotExtractor(Extractor):
    """第一轮把必填全部给出，之后不再抽取。"""

    def __init__(self) -> None:
        self.calls = 0

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        self.calls += 1
        if self.calls > 1:
            return {}
        return {
            "type": "OTHER",
            "title": "帮忙还书",
            "reward_cents": 500,
        }


def test_is_no_more_matches_common_phrases():
    for text in ["没有", "没有了", "不用", "就这些", "够了", "好了。"]:
        assert is_no_more(text), text
    for text in ["有点重", "送到3号楼", "没有电梯请帮忙"]:
        assert not is_no_more(text), text


def test_append_description_concatenates_and_limits():
    draft = TaskDraft(title="x", description="原来的说明")
    merged = append_description(draft, "补充：书比较重")
    assert merged.description == "原来的说明；补充：书比较重"

    long_draft = TaskDraft(title="x", description="a" * 290)
    capped = append_description(long_draft, "b" * 50)
    assert len(capped.description) == 300


def test_ready_state_still_accepts_description():
    """信息齐全后，用户补充的话应进入任务内容，而不是被当字段抽取。"""
    service = ChatService(OneShotExtractor())
    session = service.create_session()

    reply = service.chat(session.id, "帮我把书还到图书馆，5块")
    assert reply["status"] == "READY"
    assert "还需要补充任务内容吗" in reply["message"]

    reply = service.chat(session.id, "书比较重，麻烦带个小推车")
    assert reply["status"] == "READY"
    assert reply["draft"]["description"] == "书比较重，麻烦带个小推车"

    reply = service.chat(session.id, "尽快还，谢谢")
    assert reply["draft"]["description"] == "书比较重，麻烦带个小推车；尽快还，谢谢"


def test_no_more_keeps_draft_unchanged():
    service = ChatService(OneShotExtractor())
    session = service.create_session()
    service.chat(session.id, "帮我把书还到图书馆，5块")

    reply = service.chat(session.id, "没有")
    assert reply["status"] == "READY"
    assert reply["draft"]["description"] is None


def test_asking_state_not_affected_by_description_flow():
    """还没填完时不进入追加描述分支，仍然逐项追问。"""
    service = ChatService(EmptyExtractor())
    session = service.create_session()
    reply = service.chat(session.id, "帮我取个快递")
    assert reply["status"] == "ASKING"
    assert reply["draft"]["description"] is None
