"""空会话定时清理：任务能跑起来、能被取消、失败不炸。"""

from __future__ import annotations

import asyncio

import pytest

from app import main


class FakeStore:
    def __init__(self, raises: bool = False) -> None:
        self.calls = 0
        self.raises = raises

    def purge_empty(self) -> int:
        self.calls += 1
        if self.raises:
            raise RuntimeError("db down")
        return 3


def _run_loop_briefly() -> None:
    """跑一小会儿再取消，验证循环体行为。"""

    async def scenario() -> None:
        task = asyncio.create_task(main._purge_loop())
        await asyncio.sleep(0.05)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())


def test_purge_loop_calls_purge(monkeypatch):
    store = FakeStore()
    monkeypatch.setattr(main.service, "store", store)
    monkeypatch.setattr(main, "PURGE_INTERVAL_SECONDS", 0)

    _run_loop_briefly()

    assert store.calls >= 1


def test_purge_loop_survives_failure(monkeypatch):
    """数据库抖动不能让清理任务退出。"""
    store = FakeStore(raises=True)
    monkeypatch.setattr(main.service, "store", store)
    monkeypatch.setattr(main, "PURGE_INTERVAL_SECONDS", 0)

    _run_loop_briefly()

    assert store.calls >= 1


def test_purge_loop_skips_store_without_purge(monkeypatch):
    """内存版 store 没有 purge_empty，循环应该安静跳过。"""

    class Bare:
        pass

    monkeypatch.setattr(main.service, "store", Bare())
    monkeypatch.setattr(main, "PURGE_INTERVAL_SECONDS", 0)

    _run_loop_briefly()  # 不抛异常即通过
