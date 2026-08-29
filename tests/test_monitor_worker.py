"""MonitorWorker 轮询主循环行为测试（片C 补测：此前零行为覆盖）。

直接在测试线程同步调用 run()（不起真线程）；engine 以替身注入
（mock.patch 构造期 RollcallEngine，不改产线代码）；stop_event 用可控
假事件驱动固定轮询次数，wait 记录等待秒数作为自适应轮询观察点。

锁定：状态事件序、payload 去重、有界去重集合（FIFO 驱逐）、会话过期
终态停机、RetryCancelled 静默停机、密集轮询等待选择、间隔钳位。
"""

from __future__ import annotations

import threading
import unittest
from unittest import mock

import requests

from xmu_rollcall.desktop_qt.core import (
    ACTIVE_POLL_INTERVAL_SECONDS,
    MAX_POLL_INTERVAL_SECONDS,
    MIN_POLL_INTERVAL_SECONDS,
    MonitorWorker,
)
from xmu_rollcall.rollcall_models import RollcallEvent
from xmu_rollcall.utils import RetryCancelled, SessionExpiredError


def _event(rid: str, remaining=None) -> RollcallEvent:
    return RollcallEvent(
        rollcall_id=rid,
        course_title="高等数学",
        teacher="师",
        rollcall_type="数字签到",
        status="进行中",
        raw={"id": rid},
        remaining_seconds=remaining,
    )


class _GateStopEvent(threading.Event):
    """允许固定轮询次数的假停止事件；wait 记录等待秒数（自适应轮询观察点）。"""

    def __init__(self, loops: int):
        super().__init__()
        self.remaining = loops
        self.wait_args = []

    def is_set(self) -> bool:
        return self.remaining <= 0

    def wait(self, timeout=None):
        self.wait_args.append(timeout)
        self.remaining -= 1
        return self.remaining <= 0


class _FakeEngine:
    def __init__(self, payloads, events_map=None, poll_error=None):
        self._payloads = list(payloads)
        self._events_map = events_map or {}
        self._poll_error = poll_error
        self.poll_calls = 0

    def poll_payload(self):
        self.poll_calls += 1
        if self._poll_error is not None:
            raise self._poll_error
        return self._payloads[min(self.poll_calls, len(self._payloads)) - 1]

    def build_events(self, payload):
        return self._events_map.get(self.poll_calls, [])


class MonitorWorkerLoopTest(unittest.TestCase):
    def _run(self, engine, loops, interval=60):
        events = []
        stop = _GateStopEvent(loops)
        with mock.patch(
            "xmu_rollcall.desktop_qt.core.RollcallEngine", return_value=engine
        ):
            worker = MonitorWorker(requests.Session(), events.append, stop, interval)
        worker.run()
        return worker, events, stop

    def test_status_events_and_single_poll_cycle(self):
        engine = _FakeEngine(
            payloads=[{"rollcalls": [{"id": "r1"}]}],
            events_map={1: [_event("r1")]},
        )
        worker, events, _ = self._run(engine, loops=1)
        self.assertEqual(events[0], ("monitor_status", "运行中"))
        self.assertEqual(events[-1], ("monitor_status", "已停止"))
        poll_events = [e for e in events if e[0] == "poll"]
        self.assertEqual(len(poll_events), 1)
        self.assertEqual(poll_events[0][1], 1)
        self.assertEqual(poll_events[0][3], 1)
        self.assertEqual(worker.query_count, 1)
        rollcalls = [e for e in events if e[0] == "rollcall"]
        self.assertEqual(len(rollcalls), 1)
        self.assertEqual(rollcalls[0][1].rollcall_id, "r1")

    def test_duplicate_payload_does_not_reemit_known_ids(self):
        same = {"rollcalls": [{"id": "r1"}]}
        engine = _FakeEngine(
            payloads=[same, same],
            events_map={1: [_event("r1")], 2: [_event("r1")]},
        )
        _, events, _ = self._run(engine, loops=2)
        rollcalls = [e for e in events if e[0] == "rollcall"]
        self.assertEqual(
            len(rollcalls), 1, "同一 payload 重复轮询不得重复 emit（去重集合语义）"
        )

    def test_changed_payload_with_known_ids_skips_via_seen_set(self):
        # payload 内容变化（服务端新增字段）但事件 id 相同：payload 相等守卫放行，
        # 由 seen_rollcall_ids 去重集合跳过——与 payload 相等守卫是两条不同防线。
        first = {"rollcalls": [{"id": "r1"}]}
        second = {"rollcalls": [{"id": "r1"}], "extra": 1}
        engine = _FakeEngine(
            payloads=[first, second],
            events_map={1: [_event("r1")], 2: [_event("r1")]},
        )
        worker, events, _ = self._run(engine, loops=2)
        self.assertEqual(worker.query_count, 2, "两轮轮询都必须真实执行")
        rollcalls = [e for e in events if e[0] == "rollcall"]
        self.assertEqual(
            len(rollcalls), 1,
            "已通知过的签到 id 在 payload 变化后也不得重复 emit（seen 集合防线）",
        )

    def test_seen_set_is_bounded_and_evicts_oldest(self):
        ids = [f"e{i}" for i in range(MonitorWorker.MAX_SEEN_ROLLCALL_IDS + 1)]
        engine = _FakeEngine(
            # payload 不得与 last_payload 初始值 {"rollcalls": []} 相同：
            # 「payload 有变化才 emit」守卫会拦下全部事件
            payloads=[{"rollcalls": [], "cycle": 1}],
            events_map={1: [_event(rid) for rid in ids]},
        )
        worker, events, _ = self._run(engine, loops=1)
        rollcalls = [e for e in events if e[0] == "rollcall"]
        self.assertEqual(len(rollcalls), MonitorWorker.MAX_SEEN_ROLLCALL_IDS + 1)
        self.assertEqual(
            len(worker.seen_rollcall_ids),
            MonitorWorker.MAX_SEEN_ROLLCALL_IDS,
            "常驻监控内存占用必须钉在常量级",
        )
        self.assertNotIn("e0", worker.seen_rollcall_ids, "最早插入者被淘汰")
        self.assertIn(ids[-1], worker.seen_rollcall_ids)

    def test_session_expired_stops_polling_with_error_event(self):
        engine = _FakeEngine(
            payloads=[{"rollcalls": []}],
            poll_error=SessionExpiredError("登录已过期，请重新登录"),
        )
        worker, events, stop = self._run(engine, loops=5)
        self.assertEqual(engine.poll_calls, 1, "过期是终态：不得继续按 interval 轮询")
        self.assertEqual(stop.wait_args, [], "停机路径不得进入间隔等待")
        self.assertIn(("error", "轮询失败：登录已过期，请重新登录"), events)
        self.assertEqual(events[-1], ("monitor_status", "已停止"))

    def test_retry_cancelled_stops_silently(self):
        engine = _FakeEngine(payloads=[{"rollcalls": []}], poll_error=RetryCancelled())
        _, events, _ = self._run(engine, loops=5)
        self.assertFalse(
            any(e[0] == "error" for e in events),
            "暂停/停止打断的重试取消应静默收尾，不算轮询错误",
        )
        self.assertEqual(events[-1], ("monitor_status", "已停止"))

    def test_active_rollcall_switches_to_dense_polling_interval(self):
        engine = _FakeEngine(
            payloads=[{"rollcalls": [{"id": "r1"}]}],
            events_map={1: [_event("r1", remaining=120)]},
        )
        _, _, stop = self._run(engine, loops=1, interval=60)
        self.assertEqual(stop.wait_args, [ACTIVE_POLL_INTERVAL_SECONDS])

    def test_idle_rollcall_keeps_user_interval(self):
        engine = _FakeEngine(
            payloads=[{"rollcalls": [{"id": "r1"}]}],
            events_map={1: [_event("r1", remaining=None)]},
        )
        _, _, stop = self._run(engine, loops=1, interval=60)
        self.assertEqual(stop.wait_args, [60])

    def test_interval_is_clamped_to_configured_bounds(self):
        events = []
        stop = _GateStopEvent(0)
        with mock.patch(
            "xmu_rollcall.desktop_qt.core.RollcallEngine",
            return_value=_FakeEngine(payloads=[]),
        ):
            # interval=0 是 falsy：走 DEFAULT 而非下界钳位（构造函数 `or` 语义）
            default = MonitorWorker(requests.Session(), events.append, stop, 0)
            low = MonitorWorker(requests.Session(), events.append, stop, 1)
            high = MonitorWorker(
                requests.Session(), events.append, stop, 10 ** 9
            )
        self.assertEqual(default.interval, 30)
        self.assertEqual(low.interval, MIN_POLL_INTERVAL_SECONDS)
        self.assertEqual(high.interval, MAX_POLL_INTERVAL_SECONDS)


if __name__ == "__main__":
    unittest.main()
