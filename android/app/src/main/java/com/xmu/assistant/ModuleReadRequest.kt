package com.xmu.assistant

/** 模块读取请求类型：会话过期续登（recoverExpiredModule）按类型恢复对应模块。 */
internal enum class ModuleReadRequest { ROLLCALL, COURSES, COURSEWARE, SCHEDULE }

/** 一次性安全续登请求：携带模块类型；课件恢复需要带课程对象。 */
internal data class ModuleReadRetry(
    val request: ModuleReadRequest,
    val course: CourseSummary? = null,
)
