package com.xmu.assistant

enum class RefreshModule {
    ROLLCALL,
    SCORES,
    COURSES,
    NONE,
}

enum class SessionExpiryAction {
    RECOVER_ONCE,
    SHOW_ERROR,
}

fun sessionExpiryAction(error: Throwable, alreadyRetried: Boolean): SessionExpiryAction =
    if ((error is MainSessionExpiredException || error is ScheduleSessionExpiredException) && !alreadyRetried) {
        SessionExpiryAction.RECOVER_ONCE
    } else {
        SessionExpiryAction.SHOW_ERROR
    }

class PendingModuleRefresh {
    private var pending: RefreshModule? = null

    fun enter(module: RefreshModule, sessionReady: Boolean): RefreshModule? {
        if (module == RefreshModule.NONE) {
            pending = null
            return null
        }
        if (sessionReady) {
            pending = null
            return module
        }
        pending = module
        return null
    }

    fun release(): RefreshModule? = pending.also { pending = null }

    fun clear() {
        pending = null
    }
}

fun refreshModuleForPage(page: String): RefreshModule = when (page) {
    "签到情况" -> RefreshModule.ROLLCALL
    "成绩" -> RefreshModule.SCORES
    "课程课件", "缓存课件" -> RefreshModule.COURSES
    // 课表不自动刷新：课表按学期基本不变，点击标签一律直接展示缓存，
    // 需要最新数据时由用户点课表页的「刷新」按钮手动拉取。
    else -> RefreshModule.NONE
}

fun moduleFreshnessMillis(module: RefreshModule): Long = when (module) {
    RefreshModule.ROLLCALL -> 15_000L
    // 成绩：用户拍板由 120s 放宽到 5 分钟（2026-08-24 真机联调），与历史签到窗对齐
    RefreshModule.SCORES -> 300_000L
    RefreshModule.COURSES -> 300_000L
    RefreshModule.NONE -> 0L
}

fun isAcademicCacheFresh(
    updatedAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean = updatedAtMillis in 1..nowMillis &&
    nowMillis - updatedAtMillis < moduleFreshnessMillis(RefreshModule.COURSES)

class ForegroundModuleRefreshTracker {
    private val lock = Any()
    private var foreground = false

    fun beginForeground() {
        synchronized(lock) {
            foreground = true
        }
    }

    fun endForeground() {
        synchronized(lock) {
            foreground = false
        }
    }

    /**
     * 是否需要刷新。仅在模块缓存过期（超过 freshness 窗口）时才返回 true；
     * 切换标签不会无条件触发网络请求，避免浪费流量和等待。
     * 手动刷新按钮直接调用 refreshXxx()，不走这里，天然是强制的。
     */
    fun shouldRefresh(
        module: RefreshModule,
        updatedAtMillis: Long,
        nowMillis: Long,
        force: Boolean = false,
    ): Boolean = synchronized(lock) {
        if (!foreground || module == RefreshModule.NONE) return@synchronized false
        force || !isFresh(module, updatedAtMillis, nowMillis)
    }

    private fun isFresh(module: RefreshModule, updatedAtMillis: Long, nowMillis: Long): Boolean {
        if (updatedAtMillis <= 0L || updatedAtMillis > nowMillis) return false
        return nowMillis - updatedAtMillis < moduleFreshnessMillis(module)
    }
}
