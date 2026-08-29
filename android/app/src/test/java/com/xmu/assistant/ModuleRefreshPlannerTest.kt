package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleRefreshPlannerTest {
    private val now = 1_000_000L

    @Test
    fun `maps each business page to its refresh module`() {
        assertEquals(RefreshModule.ROLLCALL, refreshModuleForPage("签到情况"))
        assertEquals(RefreshModule.SCORES, refreshModuleForPage("成绩"))
        assertEquals(RefreshModule.COURSES, refreshModuleForPage("课程课件"))
        assertEquals(RefreshModule.COURSES, refreshModuleForPage("缓存课件"))
    }

    @Test
    fun `maps non business blank and unknown pages to none`() {
        // 课表不自动刷新：点击标签直接展示缓存，由用户手动点刷新
        listOf("课表", "首页", "通知", "教程", "策略", "", "未知页面").forEach { page ->
            assertEquals(RefreshModule.NONE, refreshModuleForPage(page))
        }
    }

    @Test
    fun `exposes the exact cache freshness windows`() {
        assertEquals(15_000L, moduleFreshnessMillis(RefreshModule.ROLLCALL))
        // 成绩：用户拍板由 120s 放宽到 5 分钟（2026-08-24 真机联调）
        assertEquals(300_000L, moduleFreshnessMillis(RefreshModule.SCORES))
        assertEquals(300_000L, moduleFreshnessMillis(RefreshModule.COURSES))
        assertEquals(0L, moduleFreshnessMillis(RefreshModule.NONE))
        assertTrue(isAcademicCacheFresh(now - 299_999L, now))
        assertFalse(isAcademicCacheFresh(now - 300_000L, now))
        assertFalse(isAcademicCacheFresh(now + 1L, now))
    }

    @Test
    fun `stale cache refreshes even on first entry`() {
        val tracker = ForegroundModuleRefreshTracker()

        tracker.beginForeground()

        // 缓存过期（now - 1 距今超过 15 秒）→ 刷新
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 15_001L, now))
    }

    @Test
    fun `fresh cache does not refresh even on first entry`() {
        val tracker = ForegroundModuleRefreshTracker()

        tracker.beginForeground()

        // 缓存新鲜（1 秒前刷新）→ 不刷新，避免点标签就发请求
        assertFalse(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 1L, now))
    }

    @Test
    fun `repeated entry is suppressed while cache remains fresh and refreshes once stale`() {
        val tracker = ForegroundModuleRefreshTracker()

        tracker.beginForeground()
        assertFalse(tracker.shouldRefresh(RefreshModule.SCORES, now - 1L, now))
        assertFalse(tracker.shouldRefresh(RefreshModule.SCORES, now - 1L, now))
        assertTrue(tracker.shouldRefresh(RefreshModule.SCORES, now - 300_001L, now))
    }

    @Test
    fun `ttl equality future and nonpositive timestamps are stale`() {
        val tracker = ForegroundModuleRefreshTracker()

        tracker.beginForeground()
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 15_000L, now))
        assertFalse(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 1L, now))
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, now + 1L, now))
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, 0L, now))
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, -1L, now))
    }

    @Test
    fun `force bypasses freshness only for a refreshable foreground module`() {
        val tracker = ForegroundModuleRefreshTracker()

        tracker.beginForeground()
        assertTrue(tracker.shouldRefresh(RefreshModule.COURSES, now - 1L, now, force = true))
        assertFalse(tracker.shouldRefresh(RefreshModule.NONE, now - 1L, now, force = true))
    }

    @Test
    fun `background rejects entries and foreground re-enables freshness checks`() {
        val tracker = ForegroundModuleRefreshTracker()

        assertFalse(tracker.shouldRefresh(RefreshModule.ROLLCALL, 0L, now, force = true))
        tracker.beginForeground()
        assertFalse(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 1L, now))
        tracker.endForeground()
        assertFalse(tracker.shouldRefresh(RefreshModule.SCORES, 0L, now, force = true))
        tracker.beginForeground()
        assertFalse(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 1L, now))
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, 0L, now))
    }

    @Test
    fun `none never refreshes`() {
        val tracker = ForegroundModuleRefreshTracker()

        tracker.beginForeground()
        assertFalse(tracker.shouldRefresh(RefreshModule.NONE, 0L, now))
        assertFalse(tracker.shouldRefresh(RefreshModule.NONE, now - 1L, now))
    }

    @Test
    fun `pending module keeps only the latest business entry until startup settles`() {
        val pending = PendingModuleRefresh()

        assertNull(pending.enter(RefreshModule.ROLLCALL, sessionReady = false))
        assertNull(pending.enter(RefreshModule.SCORES, sessionReady = false))
        assertEquals(RefreshModule.SCORES, pending.release())
        assertNull(pending.release())
        assertEquals(RefreshModule.COURSES, pending.enter(RefreshModule.COURSES, sessionReady = true))
    }

    @Test
    fun `non business navigation and background clear pending module work`() {
        val pending = PendingModuleRefresh()

        pending.enter(RefreshModule.ROLLCALL, sessionReady = false)
        assertNull(pending.enter(RefreshModule.NONE, sessionReady = false))
        assertNull(pending.release())

        pending.enter(RefreshModule.COURSES, sessionReady = false)
        pending.clear()
        assertNull(pending.release())
    }

    // ---- E3 补位：世代/覆盖语义与「只有两类异常触发续登」的边界 ----

    @Test
    fun `entering with a ready session discards any pending module`() {
        val pending = PendingModuleRefresh()

        pending.enter(RefreshModule.ROLLCALL, sessionReady = false)
        // 会话已就绪：本次直接放行，且**丢弃**此前挂起的旧模块
        // （旧模块属于上一段会话，放行它会在新会话下重放一次过期请求）
        assertEquals(RefreshModule.COURSES, pending.enter(RefreshModule.COURSES, sessionReady = true))
        assertNull("就绪路径必须丢弃挂起项", pending.release())
    }

    @Test
    fun `entering none clears pending even when session is ready`() {
        val pending = PendingModuleRefresh()

        pending.enter(RefreshModule.SCORES, sessionReady = false)
        // NONE 判定先于 sessionReady 分支：非业务页一律清空并拒绝
        assertNull(pending.enter(RefreshModule.NONE, sessionReady = true))
        assertNull(pending.release())
    }

    @Test
    fun `pending keeps only the latest of a longer navigation chain`() {
        val pending = PendingModuleRefresh()

        assertNull(pending.enter(RefreshModule.ROLLCALL, sessionReady = false))
        assertNull(pending.enter(RefreshModule.SCORES, sessionReady = false))
        assertNull(pending.enter(RefreshModule.COURSES, sessionReady = false))
        assertNull(pending.enter(RefreshModule.ROLLCALL, sessionReady = false))
        assertEquals(RefreshModule.ROLLCALL, pending.release())
        assertNull("release 必须是一次性的", pending.release())
    }

    @Test
    fun `only main and schedule expiry types request recovery`() {
        // 只有 Main/Schedule 两类会话失效会挂起一次性续登；其余异常一律走错误展示，
        // 否则任意失败都可能触发 CAS 重登（风控红线）。
        val nonRecoverable = listOf(
            ScoreSessionExpiredException(),
            ExamSessionExpiredException(),
            ScheduleNetworkException(java.io.IOException("timeout")),
            ScheduleTermUnavailableException(),
            ScheduleResponseException(),
            IllegalStateException("解析失败"),
        )
        nonRecoverable.forEach { error ->
            assertEquals(
                "非续登类型不得触发续登：${error::class.simpleName}",
                SessionExpiryAction.SHOW_ERROR,
                sessionExpiryAction(error, alreadyRetried = false),
            )
        }
        // 两类可续登类型在 alreadyRetried 时也必须降级为展示错误（一次性语义）
        listOf(MainSessionExpiredException(), ScheduleSessionExpiredException()).forEach { error ->
            assertEquals(
                SessionExpiryAction.RECOVER_ONCE,
                sessionExpiryAction(error, alreadyRetried = false),
            )
            assertEquals(
                "alreadyRetried 必须降级：${error::class.simpleName}",
                SessionExpiryAction.SHOW_ERROR,
                sessionExpiryAction(error, alreadyRetried = true),
            )
        }
    }

    @Test
    fun `tracker applies the module specific freshness window`() {
        val tracker = ForegroundModuleRefreshTracker()
        tracker.beginForeground()

        // 课件/课程窗口 300s：299s 新鲜、300s 恰好过期（与 isAcademicCacheFresh 同口径）
        assertFalse(tracker.shouldRefresh(RefreshModule.COURSES, now - 299_999L, now))
        assertTrue(tracker.shouldRefresh(RefreshModule.COURSES, now - 300_000L, now))
        // 签到窗口短得多（15s）：同样的 60s 间隔在签到已过期、在成绩仍新鲜
        assertTrue(tracker.shouldRefresh(RefreshModule.ROLLCALL, now - 60_000L, now))
        assertFalse(tracker.shouldRefresh(RefreshModule.SCORES, now - 60_000L, now))
    }

    @Test
    fun `academic cache freshness rejects zero and future timestamps`() {
        // updatedAtMillis=0 表示从未取到数据：必须算不新鲜（否则首开被误判为有缓存）
        assertFalse(isAcademicCacheFresh(0L, now))
        assertFalse(isAcademicCacheFresh(now + 1L, now))
        assertTrue(isAcademicCacheFresh(now, now))
    }

    @Test
    fun `only first typed session expiry requests recovery`() {
        assertEquals(
            SessionExpiryAction.RECOVER_ONCE,
            sessionExpiryAction(MainSessionExpiredException(), alreadyRetried = false),
        )
        assertEquals(
            SessionExpiryAction.SHOW_ERROR,
            sessionExpiryAction(MainSessionExpiredException(), alreadyRetried = true),
        )
        assertEquals(
            SessionExpiryAction.SHOW_ERROR,
            sessionExpiryAction(IllegalStateException("网络失败"), alreadyRetried = false),
        )
        assertEquals(
            SessionExpiryAction.RECOVER_ONCE,
            sessionExpiryAction(ScheduleSessionExpiredException(), alreadyRetried = false),
        )
        assertEquals(
            SessionExpiryAction.SHOW_ERROR,
            sessionExpiryAction(ScheduleSessionExpiredException(), alreadyRetried = true),
        )
    }
}
