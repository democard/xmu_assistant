package com.xmu.assistant

/**
 * 全局忙碌指示（busy）的状态常量。
 *
 * busy 直接以文案形式渲染在底部 ToastBar（值即显示文本），因此用常量收敛
 * 引用点、字符串值保持不变；空串 [IDLE] 表示空闲。写入与比较分散在
 * MainActivity / MainScreen / CoursewareSectionState / ScheduleSectionState
 * 四个文件，两侧文案不一致会让 busy 永不复位——新增忙碌态必须在此登记，
 * 由 BusyStatesSourceContractTest 守护防魔法字符串逃逸。
 */
object BusyStates {
    /** 空闲（无任何在途全局动作）。 */
    const val IDLE = ""

    /** 登录流程进行中。 */
    const val LOGGING_IN = "正在登录"

    /** 退出登录进行中。 */
    const val LOGGING_OUT = "正在退出登录"

    /** 暂停后台监控进行中。 */
    const val PAUSING_MONITOR = "正在暂停监控"

    /** 课件下载进行中（CoursewareSectionState 下载互斥期内持有）。 */
    const val DOWNLOADING_COURSEWARE = "正在下载课件"

    /** 通知页发送测试通知进行中。 */
    const val SENDING_TEST_NOTIFICATION = "正在发送测试通知"

    /** 启动/恢复时后台核实登录态进行中。 */
    const val CHECKING_LOGIN_STATE = "正在检查登录状态"

    /** 会话过期后的安全重登进行中。 */
    const val SESSION_EXPIRED_RELOGIN = "会话已过期，正在安全重登"
}
