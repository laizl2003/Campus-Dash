"""地点校验：不像地点就不填、继续追问（和金额同一套策略）。"""

from __future__ import annotations

import pytest

from app.agent.extractor import Extractor
from app.models import TaskDraft, looks_like_place, sanitize_places
from app.service import ChatService


class StubExtractor(Extractor):
    """只认类型，其余交给裸回答回填，方便验证地点校验。"""

    def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
        patch: dict[str, object] = {}
        if draft.type is None:
            patch["type"] = "DELIVERY"
        if "8块" in message:
            patch["reward_cents"] = 800
        # 模拟模型把"改送到X"解析成地点，X 可能并不像地点
        if message.startswith("改送到"):
            patch["location_to"] = message[len("改送到"):]
        return patch


@pytest.mark.parametrize("text", [
    "菜鸟驿站", "3号楼502", "一食堂二楼窗口", "西区东门", "图书馆", "A栋",
    "宿舍楼下", "体育馆对面", "快递柜",
])
def test_accepts_place_like(text):
    assert looks_like_place(text) is True


@pytest.mark.parametrize("text", [
    "8块", "50", "明天下午三点", "哈哈哈", "就那个", "随便", "没有了",
    "多少钱？", "不要了", "asdf", "。。。",
])
def test_rejects_non_place(text):
    assert looks_like_place(text) is False


def test_sanitize_drops_bad_locations_only():
    patch = {"location_from": "哈哈哈", "location_to": "3号楼502", "reward_cents": 800}
    clean = sanitize_places(patch)

    assert "location_from" not in clean
    assert clean["location_to"] == "3号楼502"
    assert clean["reward_cents"] == 800


def test_bad_location_keeps_asking():
    """用户答"哈哈哈"，地点不能被填上，追问里要说明原因。"""
    service = ChatService(StubExtractor())
    session = service.create_session()
    service.chat(session.id, "帮我取快递，8块")

    reply = service.chat(session.id, "哈哈哈")

    assert reply["status"] == "ASKING"
    assert reply["draft"]["location_from"] is None
    assert "location_from" in reply["missing"]
    assert "不太像地点" in reply["message"]


def test_good_location_is_accepted():
    service = ChatService(StubExtractor())
    session = service.create_session()
    service.chat(session.id, "帮我取快递，8块")

    reply = service.chat(session.id, "菜鸟驿站东门")

    assert reply["draft"]["location_from"] == "菜鸟驿站东门"
    assert "location_from" not in reply["missing"]


def test_model_returned_garbage_location_is_filtered():
    """模型硬要返回"就那个"，也不能写进草稿。"""

    class BadModel(Extractor):
        def extract(self, message: str, draft: TaskDraft) -> dict[str, object]:
            patch: dict[str, object] = {}
            if draft.type is None:
                patch["type"] = "DELIVERY"
            patch["location_from"] = message
            return patch

    service = ChatService(BadModel())
    session = service.create_session()
    service.chat(session.id, "帮我取快递")

    reply = service.chat(session.id, "就那个")

    assert reply["draft"]["location_from"] is None
    assert "location_from" in reply["missing"]


def test_ready_edit_with_bad_location_keeps_draft_and_explains():
    """已完成后再改地点，说了句不像地点的话，草稿不能变。"""
    service = ChatService(StubExtractor())
    session = service.create_session()
    for text in ("帮我取快递，8块", "菜鸟驿站东门", "3号楼502"):
        reply = service.chat(session.id, text)
    assert reply["status"] == "READY"

    reply = service.chat(session.id, "改送到哈哈哈")

    assert reply["draft"]["location_to"] == "3号楼502"
    assert "没太看明白" in reply["message"]
