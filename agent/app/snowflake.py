"""雪花 ID：64 位、按时间递增、单机生成不需要数据库协调。

结构（共 63 位，最高位留 0 保证是正数）：
  41 位毫秒时间戳 | 10 位机器号 | 12 位序列号

为什么用它：消息 id 要在插入前就确定（重试幂等、批量插入、将来分库），
自增只能插入后回读。
"""

from __future__ import annotations

import os
import threading
import time

# 2024-01-01T00:00:00Z，比它早的时间戳会算成负数，换纪元要同步改这里
EPOCH_MS = 1_704_067_200_000

WORKER_BITS = 10
SEQ_BITS = 12
MAX_WORKER_ID = (1 << WORKER_BITS) - 1
SEQ_MASK = (1 << SEQ_BITS) - 1
WORKER_SHIFT = SEQ_BITS
TIMESTAMP_SHIFT = SEQ_BITS + WORKER_BITS


class Snowflake:
    """线程安全的雪花生成器。

    同一个实例保证递增；多个实例靠 worker_id 区分，不允许重复。
    """

    def __init__(self, worker_id: int | None = None) -> None:
        if worker_id is None:
            worker_id = int(os.getenv("AGENT_WORKER_ID", "0"))
        if not 0 <= worker_id <= MAX_WORKER_ID:
            raise ValueError(f"worker_id 必须在 0..{MAX_WORKER_ID} 之间")
        self.worker_id = worker_id
        self._lock = threading.Lock()
        self._last_ms = -1
        self._seq = 0

    def next_id(self) -> int:
        with self._lock:
            now = int(time.time() * 1000)

            # 时钟回拨：不能生成比已发出的更小的 id，
            # 沿用上一次的时间戳、继续用序列号兜住。
            if now < self._last_ms:
                now = self._last_ms

            if now == self._last_ms:
                self._seq = (self._seq + 1) & SEQ_MASK
                if self._seq == 0:
                    # 这一毫秒的 4096 个号用完了，等到下一毫秒
                    while now <= self._last_ms:
                        time.sleep(0.0002)
                        now = int(time.time() * 1000)
            else:
                self._seq = 0

            self._last_ms = now
            return ((now - EPOCH_MS) << TIMESTAMP_SHIFT) | (self.worker_id << WORKER_SHIFT) | self._seq


_default = Snowflake()


def next_id() -> int:
    """全局默认生成器，进程内共用。"""
    return _default.next_id()


def worker_id_of(value: int) -> int:
    """从 id 里解出机器号，排障用。"""
    return (value >> WORKER_SHIFT) & MAX_WORKER_ID


def timestamp_of(value: int) -> int:
    """从 id 里解出毫秒时间戳。"""
    return (value >> TIMESTAMP_SHIFT) + EPOCH_MS
