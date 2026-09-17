"""雪花算法 ID 生成器（线程安全）：41 位时间戳 + 5 位数据中心 + 5 位工作节点 + 12 位序列"""
import threading
import time

# 起始纪元：2024-01-01 00:00:00 UTC
EPOCH = 1704067200000

DATACENTER_BITS = 5
WORKER_BITS = 5
SEQUENCE_BITS = 12

MAX_DATACENTER_ID = -1 ^ (-1 << DATACENTER_BITS)
MAX_WORKER_ID = -1 ^ (-1 << WORKER_BITS)
MAX_SEQUENCE = -1 ^ (-1 << SEQUENCE_BITS)

WORKER_SHIFT = SEQUENCE_BITS
DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS
TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS


class Snowflake:
    def __init__(self, worker_id: int = 1, datacenter_id: int = 1):
        if worker_id > MAX_WORKER_ID or worker_id < 0:
            raise ValueError(f"worker_id 超出范围 0-{MAX_WORKER_ID}")
        if datacenter_id > MAX_DATACENTER_ID or datacenter_id < 0:
            raise ValueError(f"datacenter_id 超出范围 0-{MAX_DATACENTER_ID}")
        self.worker_id = worker_id
        self.datacenter_id = datacenter_id
        self._sequence = 0
        self._last_timestamp = -1
        self._lock = threading.Lock()

    def _current_millis(self) -> int:
        return int(time.time() * 1000)

    def _wait_next_millis(self, last_ts: int) -> int:
        ts = self._current_millis()
        while ts <= last_ts:
            ts = self._current_millis()
        return ts

    def next_id(self) -> int:
        with self._lock:
            ts = self._current_millis()
            if ts < self._last_timestamp:
                raise RuntimeError("时钟回拨，拒绝生成 ID")
            if ts == self._last_timestamp:
                self._sequence = (self._sequence + 1) & MAX_SEQUENCE
                if self._sequence == 0:
                    ts = self._wait_next_millis(self._last_timestamp)
            else:
                self._sequence = 0
            self._last_timestamp = ts
            return ((ts - EPOCH) << TIMESTAMP_SHIFT) | (self.datacenter_id << DATACENTER_SHIFT) | \
                   (self.worker_id << WORKER_SHIFT) | self._sequence


snowflake = Snowflake()
