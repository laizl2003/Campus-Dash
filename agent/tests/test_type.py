"""类型判定：帮拿/取/带 一律归跑腿配送。"""

from __future__ import annotations

import pytest

from app.agent.extractor import guess_type


@pytest.mark.parametrize("message", [
    "帮忙拿外卖",
    "帮我拿个快递",
    "顺手把餐带上来",
    "帮我取外卖",
    "帮我把书还到图书馆",
    "取快递到3号楼502",
])
def test_pickup_and_deliver_is_delivery(message):
    assert guess_type(message) == "DELIVERY"


@pytest.mark.parametrize("message", [
    "帮买一杯奶茶",
    "超市代购",
])
def test_buy_stays_buy(message):
    assert guess_type(message) == "BUY"


@pytest.mark.parametrize("message", [
    "帮忙带句话给室友",
    "帮忙看管一下包",
])
def test_non_transport_help_is_other(message):
    assert guess_type(message) == "OTHER"


def test_queue_stays_queue():
    assert guess_type("帮我代排队") == "QUEUE"
