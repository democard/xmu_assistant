"""A3 签到延迟+取消+截止保护的逻辑仿真（不依赖真签到/Qt/网络）。

用假引擎记录是否真的提交，复刻 app._answer_worker + _auto_answer_delay +
_cancel_pending_answer 的编排逻辑，验证：
1. 配置延迟生效，未被取消时延迟后提交；
2. 延迟等待期间收到取消信号立即中止、不提交；
3. 截止保护：剩余时间不足时跳过延迟立即提交；
4. 手动应答会取消在途的自动延迟应答（不重复提交）。
"""
from __future__ import annotations

import sys
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.utils import compute_auto_answer_delay  # noqa: E402


class _FakeEngine:
    """记录 answer 调用，代替真实 RollcallEngine（不打网络）。"""

    def __init__(self):
        self.calls: list[tuple[str, str]] = []

    def answer(self, rollcall_type: str, rollcall_id: str) -> bool:
        self.calls.append((rollcall_type, rollcall_id))
        return True


class _FakeEvent:
    """最小事件载体，带 remaining_seconds（复刻 RollcallEvent 的字段）。"""

    def __init__(self, rollcall_type: str, rollcall_id: str, remaining_seconds):
        self.rollcall_type = rollcall_type
        self.rollcall_id = rollcall_id
        self.remaining_seconds = remaining_seconds


def _simulate_answer_worker(event, settings, engine, cancellations, event_id, delay=None):
    """复刻 app._answer_worker 的控制流（Event.wait 可取消 + 引擎调用）。

    返回 "answered" / "cancelled"。delay=None 时用 compute_auto_answer_delay 决定。
    """
    d = delay if delay is not None else compute_auto_answer_delay(
        event.rollcall_type, settings, event.remaining_seconds
    )
    if d > 0:
        cancel = threading.Event()
        cancellations[event_id] = cancel
        try:
            if cancel.wait(d):
                return "cancelled"
        finally:
            # 与 app._answer_worker 一致：归属校验后再清理
            finish_cancel_slot(cancellations, event_id, cancel)
    engine.answer(event.rollcall_type, event.rollcall_id)
    return "answered"


def finish_cancel_slot(cancellations, event_id, cancel):
    """复刻 app._answer_worker 的 finally：归属校验后清理取消信号。

    无条件按 event_id pop 会误删同事件新 worker 刚放入的信号 → 该签到此后
    无法再被取消/跳过，到点照常提交 → 重复签到（旧 worker 慢退场竞态）。
    仅当槽内仍是「自己的」那个信号时才移除。
    """
    if cancellations.get(event_id) is cancel:
        cancellations.pop(event_id, None)


def _simulate_cancel(cancellations, event_id):
    """复刻 app._cancel_pending_answer。"""
    cancel = cancellations.pop(event_id, None)
    if cancel is not None:
        cancel.set()


class AutoAnswerSimulationTests(unittest.TestCase):
    def setUp(self):
        self.cancellations: dict[str, threading.Event] = {}

    def _settings(self, nmin=10, nmax=30):
        return {"number_delay_min": nmin, "number_delay_max": nmax,
                "radar_delay_min": 0, "radar_delay_max": 0}

    def test_delay_then_submit_when_not_cancelled(self):
        # 配置 0.3-0.5s 延迟（缩短以加快测试），剩余充足，不取消 → 延迟后提交
        engine = _FakeEngine()
        event = _FakeEvent("数字签到", "rc-1", remaining_seconds=600)
        t0 = time.monotonic()
        result = _simulate_answer_worker(
            event, self._settings(nmin=0.3, nmax=0.5), engine, self.cancellations, "e1"
        )
        elapsed = time.monotonic() - t0
        self.assertEqual(result, "answered")
        self.assertEqual(len(engine.calls), 1)
        self.assertGreaterEqual(elapsed, 0.3)
        self.assertLess(elapsed, 1.5)
        # 取消表已清理
        self.assertNotIn("e1", self.cancellations)

    def test_cancel_during_delay_aborts_without_submit(self):
        engine = _FakeEngine()
        event = _FakeEvent("数字签到", "rc-2", remaining_seconds=600)
        cancel = threading.Event()
        self.cancellations["e2"] = cancel
        # 0.2s 后触发取消，延迟设 5s（远大于取消时机）
        threading.Timer(0.2, lambda: _simulate_cancel(self.cancellations, "e2")).start()
        t0 = time.monotonic()
        result = _simulate_answer_worker(
            event, self._settings(nmin=5, nmax=5), engine, self.cancellations, "e2"
        )
        elapsed = time.monotonic() - t0
        self.assertEqual(result, "cancelled")
        self.assertEqual(engine.calls, [])  # 未提交
        self.assertLess(elapsed, 1.0)  # 远早于 5s 延迟返回

    def test_deadline_protection_skips_delay_and_submits_immediately(self):
        # 报告 A3 要求：剩余 5s + 配置 10-30s → 立即提交
        engine = _FakeEngine()
        event = _FakeEvent("数字签到", "rc-3", remaining_seconds=5)
        t0 = time.monotonic()
        result = _simulate_answer_worker(
            event, self._settings(nmin=10, nmax=30), engine, self.cancellations, "e3"
        )
        elapsed = time.monotonic() - t0
        self.assertEqual(result, "answered")
        self.assertEqual(len(engine.calls), 1)
        self.assertLess(elapsed, 0.1)  # 截止保护跳过延迟

    def test_manual_answer_cancels_pending_auto(self):
        # 复刻 app 流程：自动应答已派发（带延迟），随后用户手动应答 → 取消在途自动
        engine = _FakeEngine()
        event = _FakeEvent("数字签到", "rc-4", remaining_seconds=600)
        outcomes = []
        # 自动应答线程（延迟 3s）
        def auto_task():
            outcomes.append(_simulate_answer_worker(
                event, self._settings(nmin=3, nmax=3), engine, self.cancellations, "e4"
            ))
        t = threading.Thread(target=auto_task)
        t.start()
        time.sleep(0.2)  # 让自动 worker 进入延迟等待
        # 用户手动应答：先取消在途自动（与 app._answer_event(auto=False) 同序）
        _simulate_cancel(self.cancellations, "e4")
        t.join(timeout=2.0)
        self.assertFalse(t.is_alive())
        # 自动应答被取消、未提交；手动应答由调用方另行发起（此处只验自动侧被取消）
        self.assertEqual(outcomes, ["cancelled"])
        self.assertEqual(engine.calls, [])

    def test_disabled_delay_submits_immediately(self):
        # 配置 max=0 视为关闭延迟 → 立即提交
        engine = _FakeEngine()
        event = _FakeEvent("数字签到", "rc-5", remaining_seconds=600)
        t0 = time.monotonic()
        result = _simulate_answer_worker(
            event, self._settings(nmin=0, nmax=0), engine, self.cancellations, "e5"
        )
        self.assertEqual(result, "answered")
        self.assertLess(time.monotonic() - t0, 0.1)

    def test_stale_worker_finally_does_not_drop_new_cancel_signal(self):
        # 竞态回归（批 D 遗留）：同一事件在旧 worker 慢退场期间被新 worker 接管，
        # 旧 worker 的 finally 若无条件 pop 会误删新信号 → 新 worker 无法被取消
        # → 到点照常提交 → 重复签到。时序被显式固定，不依赖真实调度。
        engine = _FakeEngine()
        # ① 旧 worker A：已放入自己的取消信号（对应 app._answer_worker 进入 wait 前）
        cancel_a = threading.Event()
        self.cancellations["evt-stale"] = cancel_a
        # ② 模拟用户手动应答取消 A：pop + set（此后槽为空，A 即将 return，finally 未跑）
        _simulate_cancel(self.cancellations, "evt-stale")
        # ③ 竞态窗口：监控再次派发同一事件，新 worker B 先接管槽位（A 的 finally 才到）
        cancel_b = threading.Event()
        self.cancellations["evt-stale"] = cancel_b
        # ④ A 的 finally（归属校验）—— 不得误删 B 的信号
        finish_cancel_slot(self.cancellations, "evt-stale", cancel_a)
        self.assertIn("evt-stale", self.cancellations, "旧 worker 的 finally 误删了新取消信号")
        self.assertIs(self.cancellations["evt-stale"], cancel_b)
        # ⑤ B 仍可被后续取消/跳过（否则会到点提交）
        _simulate_cancel(self.cancellations, "evt-stale")
        self.assertTrue(cancel_b.is_set())
        self.assertNotIn("evt-stale", self.cancellations)
        # 全程无提交发生（本次回归中没有任何 worker 实际到达引擎调用点）
        self.assertEqual(engine.calls, [])


if __name__ == "__main__":
    unittest.main()
