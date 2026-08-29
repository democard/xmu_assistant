"""UI 快照（SWR 缓存先行）：常量、序列化助手与保存/恢复方法。

自 app.py 机械搬出（tray.py / notifications_page.py 同款手法），方法体逐字保留；
app.py 通过 import 与 Mixin 引用保持行为不变。tests 侧经
`xmu_rollcall.desktop_qt.app` 命名空间再导出访问，不感知拆分。
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from ..config import CONFIG_DIR
from ..courseware import CourseSummary
from ..diag_log import log as _diag_log
from .core import CourseRollcallRecord

# ---- UI 快照（SWR 缓存先行）-------------------------------------------------
# 最近一次成功拉取的签到记录与课件课程列表落盘；下次启动先渲染快照再后台
# 刷新，页面"秒开"旧数据而非空白等待（平台无缓存头，应用层快照是唯一路径）。
# 损坏/缺失静默跳过；跨账号通过 account_id 校验防串号。
UI_SNAPSHOT_VERSION = 1


def _ui_snapshot_path() -> Path:
    return CONFIG_DIR / "ui_snapshot.json"


def ui_snapshot_to_json(account_id, records, courses) -> str:
    from dataclasses import asdict

    return json.dumps(
        {
            "version": UI_SNAPSHOT_VERSION,
            "account_id": str(account_id or ""),
            "course_records": [asdict(r) for r in records],
            "courseware_courses": [asdict(c) for c in courses],
        },
        ensure_ascii=False,
    )


def ui_snapshot_from_json(text: str):
    """解析快照文本 → (account_id, records, courses)；任何损坏返回 None。"""
    try:
        data = json.loads(text)
        if not isinstance(data, dict) or data.get("version") != UI_SNAPSHOT_VERSION:
            return None
        records = [CourseRollcallRecord(**item) for item in data.get("course_records", [])]
        courses = [CourseSummary(**item) for item in data.get("courseware_courses", [])]
        return str(data.get("account_id", "")), records, courses
    except Exception:
        return None


class UiSnapshotMixin:
    REQUIRED_HOST_ATTRS: tuple[str, ...] = (
        "_refresh_course_table",
        "_set_courseware_courses",
        "account",
        "courseware_courses",
        "log",
    )

    def _save_ui_snapshot(self):
        """把当前签到记录/课件课程列表落盘（SWR 缓存先行，原子写）。失败静默。"""
        try:
            text = ui_snapshot_to_json(
                (self.account or {}).get("id"),
                self.course_records,
                self.courseware_courses,
            )
            path = _ui_snapshot_path()
            tmp = f"{path}.tmp"
            with open(tmp, "w", encoding="utf-8") as file:
                file.write(text)
            os.replace(tmp, path)
        except Exception as exc:
            # 静默吞掉但留一线索：快照写盘失败会让下次启动退化为空白首屏
            # （SWR 缓存先行失效），打包 exe 内无 stdout，只能经 diag.log 留痕。
            _diag_log(f"保存 UI 快照失败：{exc}")

    def _restore_ui_snapshot(self):
        """启动时恢复上次快照：页面先显示旧数据，后台刷新完成后覆盖。"""
        try:
            parsed = ui_snapshot_from_json(_ui_snapshot_path().read_text(encoding="utf-8"))
        except Exception:
            return
        if not parsed:
            return
        account_id, records, courses = parsed
        self._snapshot_account_id = account_id
        if records:
            self.course_records = records
            self._refresh_course_table()
            self.log(f"已从本地快照恢复 {len(records)} 条签到记录（缓存先行）。")
        if courses:
            self._set_courseware_courses(courses, "本地快照")
