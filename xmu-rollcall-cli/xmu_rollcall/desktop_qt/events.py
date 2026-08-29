"""GUI 事件总线契约（体检报告 Opt-1）。

DashboardWindow 的事件总线是「worker 线程发射元组 → GUI 线程 _handle_event 消费」。
体检 P0-1（login_success 按 3 元组解包 4 元组必崩）证明：手工维护的事件元组协议
缺 schema 兜底，发射端加字段、消费端整包解包即崩。本模块集中登记每个事件种类
的形状约定，配套 tests/test_event_contract.py 做静态三向校验：

1) app.py / core.py / notifications_page.py / courseware_page.py / courses_page.py 中
   所有 ``_emit(("kind", ...)`` 字面量的 kind 必须已登记；
2) ``_handle_event`` 消费的每个 kind（``kind == "..."`` 分支）必须已登记，
   且登记表不允许出现从未被发射/消费的死条目；
3) 每个发射点字面量实参个数 ≥ 登记的最小实参（含 kind 本身）。

配套编码约定：**消费端一律下标取值**（``event[1]``、``event[:3]``、可选尾元用
``len(event) > n`` 判断），禁止对整个 event 做定长解包——这样发射端未来追加
字段不会炸掉 GUI 槽（P0-1 的根因即违反此约定）。

本模块不得导入 Qt/requests 等重依赖：契约测试要在无 GUI 环境直接 import。
"""

from __future__ import annotations

# kind -> (含 kind 在内的最小实参个数, 说明)
EVENT_CONTRACTS: dict[str, tuple[int, str]] = {
    # ---- core.MonitorWorker 发射 ----
    "monitor_status": (2, "监控启停状态文本"),
    "poll": (4, "轮询计数/时间戳/可见签到数"),
    "rollcall": (2, "新发现的签到事件(RollcallEvent)"),
    "error": (2, "后台错误文案"),
    # ---- 登录/恢复/应答 worker 发射 ----
    "login_success": (4, "登录或恢复成功(session, account, login_epoch)"),
    "login_failed": (2, "登录失败原因"),
    "restore_failed": (3, "自动恢复失败(原因, silent)"),
    "answer_result": (4, "签到应答结果(event_id, ok, detail)"),
    "number_code": (3, "数字签到码(event_id, code[, detail])；detail 可省略"),
    "merge_session_cookies": (3, "worker 克隆会话回写(session[, worker_account_id])"),
    "notification_result": (3, "通知发送结果(ok, detail)"),
    # ---- 签到情况 / 课件刷新 worker 发射 ----
    "course_rollcalls": (4, "签到情况结果(records, source, worker_account_id)"),
    "course_rollcalls_error": (4, "签到情况失败(message, silent, worker_account_id)"),
    "course_records_verified": (4, "本人状态核实结果(records, worker_account_id, origin=auto/manual)"),
    "course_records_verify_error": (3, "手动核实所选失败(message, worker_account_id)"),
    "courseware_courses": (4, "课件课程列表(courses, source, worker_account_id)"),
    "courseware_courses_error": (4, "课件课程列表失败(message, silent, worker_account_id)"),
    "courseware": (4, "单课程课件列表(course, items, worker_account_id)"),
    "courseware_error": (4, "单课程课件失败(message, silent, worker_account_id)"),
    "courseware_download_progress": (5, "下载进度(index, total, filename, key)"),
    "courseware_download_item_done": (3, "单个文件完成(key, status)"),
    "courseware_download_done": (6, "批量下载收尾(downloaded, entries, errors, destination[, raw_errors])"),
}
