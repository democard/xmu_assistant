package com.xmu.assistant

/**
 * SharedPreferences 存储登记表（B4：仅清单与注释，不改任何读写语义）。
 *
 * 背景：四处独立 SharedPreferences 互不知晓（B0 #3b），登出清理链
 * （MainActivity.clearLoggedOutUi）依赖各存储的删除语义逐处清理。
 * 本对象只做文件名/键空间的单一登记，供后续收敛/审计用；各存储的
 * getSharedPreferences 调用保持原样。
 *
 * ── 文件级清单 ──────────────────────────────────────────────────────
 * 1. xmu_assistant        【加密】AssistantSettings：主设置+会话凭据。
 *    EncryptedSharedPreferences（AES256_SIV/GCM），进程级单例缓存。
 *    键：username / password / cookie_header / score_cookie_header /
 *        score_records_json / score_updated_at_millis / academic_cache_json /
 *        schedule_cache_json / theme_mode / poll_interval_seconds /
 *        monitor_desired / monitor_last_check_millis / monitor_last_error /
 *        monitor_consecutive_failures / auto_answer_number / auto_answer_radar /
 *        auto_login_policy / exam_reminder_enabled / exam_reminder_advance_minutes /
 *        exam_reminder_full_screen / notify_system / notify_pushplus /
 *        pushplus_token / notify_qq_mail / qq_sender / qq_password /
 *        qq_recipient / qq_ports / widget_enabled / manual_academic_week /
 *        manual_academic_week_by_term / widget_worker_logged_in（登录态，
 *        markLoginSucceeded 置 true / clearSession 置 false）
 * 2. exam_cache           【明文】ExamCache：考试安排多学期缓存（JSON 数组）。
 *    键：terms（学期列表）/ term_<code>（各学期摘要）/ last_probe_epoch_millis
 *    （学期窗口探测节流）。
 * 3. schedule_widget      【明文】ScheduleWidgetData：桌面小卡片今日摘要镜像。
 *    键：today_summary（单键 JSON）。
 * 4. widget_mirror        【明文】AssistantSettings 内的 widget 明文镜像：
 *    桌面小卡片服务进程读开关，避免为读一个布尔加载加密库。
 *    键：widget_enabled（与加密侧同名键）/ widget_logged_in（登录态镜像，
 *    与加密侧 widget_worker_logged_in 同步双写，供周期 worker 复核是否允许
 *    教务 CAS 续登——登出后凭据残留，不复核会幽灵登录）。
 * 5. rollcall_seen         【明文】RollcallMonitorService：已通知签到去重持久化。
 *    键：cookie（归属账号标识，换号失效判据）/ seen_ids（去重集合）/
 *    seen_ids_ordered（有界 FIFO，上限 MAX_SEEN_ROLLCALLS=300）。
 *
 * ── 登出清理链对照（clearLoggedOutUi，MainActivity；2026-08-29 与实现逐行核对）─
 * 登出时实际清理：会话键（cookie_header/score_cookie_header/score_records_json/
 * score_updated_at_millis）、academic_cache_json、exam_cache（exam.clearAll →
 * ExamCache.clear，考试数据跟随账号会话）、schedule_widget（ScheduleWidgetData.
 * clear）、手动周次（clearManualAcademicWeeks），以及课表快照文件与签到历史
 * 缓存文件（deleteScheduleSnapshotFile/deleteRollcallHistoryCacheFile）。
 * 凭据（username/password）按设计残留（便捷换号/重登；Widget 周期 worker 以
 * 登录态镜像拦截幽灵 CAS，见 4）。rollcall_seen 不清：去重集绑定归属账号
 * cookie 的 SHA-256 指纹（restoreSeenRollcalls 比对不符即整体失效），换号后
 * 新账号首启自愈重建，防误吞依赖键指纹而非登出清理。
 * widget_worker_logged_in / widget_logged_in 由写点直接置 false
 * （markLoginSucceeded/clearSession/clearLoggedOutUi），不参与「清理」语义。
 * 本表仅固化对照关系防误删/漏删；清理实现改动须同步本段。
 */
internal object PrefStores {
    /** 登记锚：登记表命中的文件名必须与实际 getSharedPreferences 一致（测试守护）。 */
    val fileNames: Set<String> = setOf(
        "xmu_assistant",
        "exam_cache",
        "schedule_widget",
        "widget_mirror",
        "rollcall_seen",
    )
}
