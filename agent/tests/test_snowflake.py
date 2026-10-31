"""雪花 ID：递增、唯一、可从 id 反解时间。"""

from __future__ import annotations

import threading
import time

import pytest

from app.snowflake import (
    EPOCH_MS, MAX_WORKER_ID, Snowflake, next_id, timestamp_of, worker_id_of,
)


def test_increases_monotonically():
    gen = Snowflake(worker_id=1)
    ids = [gen.next_id() for _ in range(5000)]
    assert ids == sorted(ids)
    assert len(set(ids)) == len(ids)


def test_encodes_worker_and_time():
    gen = Snowflake(worker_id=7)
    before = int(time.time() * 1000)
    value = gen.next_id()
    after = int(time.time() * 1000)

    assert worker_id_of(value) == 7
    assert before - 5 <= timestamp_of(value) <= after + 5
    assert timestamp_of(value) >= EPOCH_MS


def test_thread_safe_unique():
    gen = Snowflake(worker_id=3)
    results: list[int] = []
    lock = threading.Lock()

    def worker() -> None:
        for _ in range(500):
            value = gen.next_id()
            with lock:
                results.append(value)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(results) == 4000
    assert len(set(results)) == 4000


def test_sequence_overflow_spills_into_next_millisecond():
    """一毫秒 4096 个号用完要自动等下一毫秒，不能回绕。"""
    gen = Snowflake(worker_id=0)
    ids = [gen.next_id() for _ in range(5000)]
    assert len(set(ids)) == 5000
    assert ids == sorted(ids)


def test_clock_rollback_does_not_go_backwards(monkeypatch):
    gen = Snowflake(worker_id=0)
    base = 1_800_000_000_000
    monkeypatch.setattr(time, "time", lambda: base / 1000)
    first = gen.next_id()

    # 时钟倒退 1 秒
    monkeypatch.setattr(time, "time", lambda: (base - 1000) / 1000)
    second = gen.next_id()

    assert second > first


def test_worker_id_bounds():
    Snowflake(worker_id=0)
    Snowflake(worker_id=MAX_WORKER_ID)
    with pytest.raises(ValueError):
        Snowflake(worker_id=MAX_WORKER_ID + 1)
    with pytest.raises(ValueError):
        Snowflake(worker_id=-1)


def test_worker_from_env(monkeypatch):
    monkeypatch.setenv("AGENT_WORKER_ID", "12")
    gen = Snowflake()
    assert gen.worker_id == 12


def test_module_level_next_id_works():
    a = next_id()
    b = next_id()
    assert b > a
