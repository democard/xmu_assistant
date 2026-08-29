package com.xmu.assistant

fun refreshFailureMessage(error: Throwable): String {
    when (error) {
        is ScheduleSessionExpiredException -> return "教务登录已过期，请重新登录"
        is ScheduleNetworkException -> return "无法连接厦大教务系统，请检查网络后重试"
        is ScheduleResponseException -> return "教务课表接口返回异常，请稍后重试"
        is ScheduleTermUnavailableException -> return "没有找到可用学期，请稍后重试"
        is ScoreJsonFormatException -> return "成绩接口返回格式异常，请稍后重试"
        is ScoreSessionExpiredException -> return "教务登录已过期，请重新登录后重试"
        is ExistingScoreSessionUnavailable -> return "教务会话暂不可用，请稍后重试"
        is ExamSessionExpiredException -> return "教务登录已过期，请重新登录后查看考试安排"
        is ExamNetworkException -> return "无法连接厦大教务系统，请检查网络后重试"
        is ExamResponseException -> return "考试安排接口返回异常，请稍后重试"
        is ExamLoginInProgressException -> return "登录处理中，请稍候重试"
        is MainSessionExpiredException -> return "教务登录已过期，请重新登录后重试"
        is AcademicLoginBlockedException -> return "教务登录请求过于频繁，请稍后再试（网络环境可能被临时限制）"
    }
    val message = error.message.orEmpty()
    // 注意：不再有 "Empty key" 特判（历史误导文案"成绩接口查询格式异常，请安装最新版本"）。
    // 该异常实际来自空盐 AES 加密（loginAndGetToken 提取不到 pwdEncryptSalt），
    // 现已在源头类型化为 AcademicLoginBlockedException；残留的原始异常落回通用文案。
    return when (shortCoursewareError(message)) {
        "登录过期" -> "登录已过期，请重新登录"
        "网络失败" -> "网络连接失败，请稍后重试"
        "平台未提供地址" -> "平台没有提供可用地址"
        else -> "数据刷新失败，请稍后重试"
    }
}

fun refreshStateText(errorMessage: String, hasData: Boolean): String =
    if (hasData) {
        "刷新失败，正在显示上次数据：$errorMessage"
    } else {
        "刷新失败：$errorMessage。请重试。"
    }
