"""换号直登互斥旗标复位契约（2026-08-30 体检 P2-3）。

缺陷背景：_ev_login_success 跨账号分支此前的「清理面对齐登出路径」修复
只对齐了数据结构（事件表/课件表/下载状态），漏掉 5 个 *_in_progress 互斥
旗标——旧账号刷新/下载在途时直接登录新账号，残留 True 会让新账号的
自动刷新被旗标检查静默跳过、新下载被拦截直至旧批次事件到达。

源码契约（与 test_page_shortcuts_contract 同法）：跨账号清理段（自
`_snapshot_account_id and` 判定起、至 else 前）必须包含与 logout 完全
一致的 5 个旗标复位。
"""

from __future__ import annotations

import inspect
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "xmu-rollcall-cli"))

from xmu_rollcall.desktop_qt.app import DashboardWindow  # noqa: E402

FLAGS = (
    "course_refresh_in_progress",
    "course_verify_in_progress",
    "courseware_courses_refresh_in_progress",
    "courseware_refresh_in_progress",
    "courseware_download_in_progress",
)

# 指标行复位面（与 logout 一致）：最近结果/当前签到/最近检查/运行时长
METRIC_RESETS = (
    'self.metric_last_result.setText("-")',
    'self.metric_rollcall_count.setText("0")',
    'self.metric_last_check.setText("-")',
    'self.metric_runtime.setText("0s")',
    "self.started_at = None",
)


class SwitchAccountFlagResetTest(unittest.TestCase):
    def _cross_account_branch(self) -> str:
        source = inspect.getsource(DashboardWindow._ev_login_success)
        marker = "self._snapshot_account_id and"
        start = source.index(marker)
        # 跨账号段到恢复 _snapshot_account_id 的 else 分支为止
        end = source.index("\n        else:", start)
        return source[start:end]

    def test_cross_account_branch_resets_all_mutex_flags(self):
        branch = self._cross_account_branch()
        for flag in FLAGS:
            self.assertIn(f"self.{flag} = False", branch, f"跨账号分支缺少 {flag} 复位")

    def test_cross_account_branch_resets_metric_rows(self):
        branch = self._cross_account_branch()
        for reset in METRIC_RESETS:
            self.assertIn(reset, branch, f"跨账号分支缺少指标复位 {reset}")

    def test_logout_resets_same_flags_for_parity(self):
        # 登出路径是对齐基准：两侧旗标集合必须一致，任何一侧增删都应同步
        source = inspect.getsource(DashboardWindow.logout)
        for flag in FLAGS:
            self.assertIn(f"self.{flag} = False", source)


if __name__ == "__main__":
    unittest.main()
