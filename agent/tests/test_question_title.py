"""追问合并 + 标题净化测试。"""

from __future__ import annotations

from app.agent.graph import clean_title
from app.models import Slot, next_question
from app.service import ChatService


def test_single_slot_question_is_one_list_with_hint():
    """单字段时也带末尾的细节提示（必填未填完前始终存在）。"""
    q = next_question([Slot.LOCATION_TO])
    assert "送到哪里？" in q
    assert "任务细节的描述？" in q
    assert q.index("送到哪里") < q.index("任务细节的描述")


def test_every_question_line_ends_with_question_mark():
    """每一行追问都必须是问句。"""
    q = next_question([Slot.LOCATION_FROM, Slot.LOCATION_TO, Slot.REWARD])
    for line in q.splitlines():
        stripped = line.strip()
        if stripped.startswith("·"):
            assert "？" in stripped, stripped


def test_multiple_slots_merge_into_one_question():
    q = next_question([Slot.LOCATION_FROM, Slot.LOCATION_TO, Slot.REWARD])
    assert "还需要这些信息" in q
    # 3 个必填 + 截止时间 + 细节提示
    assert q.count("·") == 5
    assert "取货" in q and "送到哪里" in q and "悬赏金额" in q


def test_more_than_three_slots_reports_remaining():
    q = next_question([Slot.LOCATION_FROM, Slot.LOCATION_TO, Slot.REWARD, Slot.TITLE])
    # 3 个必填 + 截止时间 + 细节提示
    assert q.count("·") == 5
    assert "还有 1 项" in q
    # "还有 N 项"紧跟必填清单，不打断两个选填提示
    lines = q.splitlines()
    assert "还有 1 项" in lines[-3]
    assert "截止时间是什么时候？" in lines[-2]
    assert "任务细节的描述？" in lines[-1]


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
    # 缺 4 个必填，一次最多问 3 个，外加截止时间与细节两行选填提示
    assert "还需要这些信息" in reply["message"]
    assert reply["message"].count("·") == 5
    assert "任务细节的描述？" in reply["message"]
