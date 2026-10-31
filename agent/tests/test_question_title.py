"""追问合并 + 标题净化测试。"""

from __future__ import annotations

from app.agent.graph import clean_title
from app.models import Slot, next_question
from app.service import ChatService


def test_single_slot_question_is_one_line():
    q = next_question([Slot.LOCATION_TO])
    assert q == "送到哪里？例如：3 号楼 502。"


def test_multiple_slots_merge_into_one_question():
    q = next_question([Slot.LOCATION_FROM, Slot.LOCATION_TO, Slot.START_TIME])
    assert "还需要这些信息" in q
    assert q.count("·") == 3
    assert "取货" in q and "送到哪里" in q and "什么时候开始" in q


def test_more_than_three_slots_reports_remaining():
    q = next_question([Slot.LOCATION_FROM, Slot.LOCATION_TO, Slot.START_TIME, Slot.REWARD])
    assert q.count("·") == 3
    assert "还有 1 项" in q


def test_no_missing_returns_none():
    assert next_question([]) is None


def test_clean_title_removes_money():
    assert clean_title("帮我取快递，8块") == "帮我取快递"
    assert clean_title("帮我取快递，悬赏8块") == "帮我取快递"
    assert clean_title("帮忙买一杯奶茶，15.5元") == "帮忙买一杯奶茶"


def test_clean_title_removes_leading_time():
    assert clean_title("明天下午三点帮我去菜鸟驿站取快递，8块") == "帮我去菜鸟驿站取快递"
    assert clean_title("后天上午10点去东门取快递") == "去东门取快递"
    assert clean_title("下午三点取快递") == "取快递"


def test_clean_title_removes_polite_tail():
    assert clean_title("帮我把书还到图书馆，5块，谢谢") == "帮我把书还到图书馆"


def test_clean_title_keeps_meaningful_content():
    assert clean_title("取快递到3号楼502") == "取快递到3号楼502"
    assert clean_title("帮我取快递到3号楼") == "帮我取快递到3号楼"


def test_clean_title_does_not_empty_out():
    """全是时间金额时，不能剥成空串。"""
    assert clean_title("8块") == "8块"
    assert clean_title("明天下午三点") == "明天下午三点"


def test_first_turn_asks_all_missing_at_once():
    """开场时缺多个字段，应该一次问全而不是挤牙膏。"""
    from app.agent.extractor import Extractor

    class FullType(Extractor):
        def extract(self, message, draft):
            if draft.type is None:
                return {"type": "DELIVERY"}
            return {}

    service = ChatService(FullType())
    session = service.create_session()
    reply = service.chat(session.id, "帮我取快递")
    assert reply["status"] == "ASKING"
    # 缺 title/location_from/location_to/start_time/reward，一次最多问 3 个
    assert "还需要这些信息" in reply["message"]
    assert reply["message"].count("·") == 3
