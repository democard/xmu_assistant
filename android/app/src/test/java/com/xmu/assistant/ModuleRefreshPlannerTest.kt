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
        assertEquals(120_000L, moduleFreshnessMillis(RefreshModule.SCORES))
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
        assertTrue(tracker.shouldRefresh(RefreshModule.SCORES, now - 120_001L, now))
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
