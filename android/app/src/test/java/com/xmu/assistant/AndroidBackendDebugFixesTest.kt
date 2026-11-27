package com.xmu.assistant

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBackendDebugFixesTest {

    @Test
    fun `exam selectTerm without cache prompts login instead of login in progress when logged out`() {
        val toasts = mutableListOf<String>()
        val epoch = SessionEpoch()
        val state = ExamSectionState(
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get(),
            requestGate = RequestGate(),
            sessionEpoch = epoch,
            sessionOwner = epoch.attachOwner(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = { toasts += it },
            loggedIn = { false },
            cookieHeader = { "" },
            username = { "" },
            password = { "" },
            accountTransitionInProgress = { false },
            scoreCookieHeader = { "" },
            setScoreCookieHeader = {},
            reminderSettings = { ExamReminderSettings() },
        )

        state.selectTerm("2025-2026-2")
        assertNull(state.summary)
        assertEquals(listOf("请先登录"), toasts)
    }

    @Test
    fun `schedule override store caches parsed snapshot and supports clear`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ScheduleOverrideStore(context)
        store.clear()

        val original = XmuScheduleGroup(
            weekday = 1,
            startSection = 1,
            endSection = 2,
            startTime = 800,
            endTime = 935,
            courseName = "数据结构",
            rooms = listOf("旧教室"),
            teachers = listOf("王老师"),
            weeks = "1-16周",
        )
        store.save("2025-2026-2", original, ScheduleCourseOverride(3, 5, 6, "坤銮楼 B207", "王老师", "1-16周"))
        assertTrue(store.has("2025-2026-2", original))
        val applied = store.apply("2025-2026-2", original)
        assertEquals(3, applied.weekday)
        assertEquals(5, applied.startSection)
        assertEquals(listOf("坤銮楼 B207"), applied.rooms)

        // Another instance sees the persisted override and clearing resets both.
        val other = ScheduleOverrideStore(context)
        assertEquals(listOf("坤銮楼 B207"), other.apply("2025-2026-2", original).rooms)
        other.clear()
        assertFalse(store.has("2025-2026-2", original))
        assertEquals(listOf("旧教室"), store.apply("2025-2026-2", original).rooms)
    }

    @Test
    fun `rank clearPdf resets stage after saving completes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        var storedJson = rankCacheToJson(
            RankCache(
                owner = rankDigest("20230001"),
                result = RankResult(
                    recordId = "rec-1",
                    position = 5,
                    participants = 100,
                    rangeName = "计算机类",
                    requestedAt = 1000L,
                    obtainedAt = 2000L,
                    calculatedAt = "2026-11-18",
                    fingerprint = "fp",
                    pdfBase64 = "JVBERi0=",
                ),
            ),
        )
        val state = RankSectionState(
            context = context,
            gate = RequestGate(),
            epoch = epoch,
            owner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            loggedIn = { true },
            cookie = { "cookie=1" },
            username = { "20230001" },
            password = { "secret" },
            transitioning = { false },
            academicCookie = { "jw=1" },
            saveCookie = {},
            readCache = { storedJson },
            writeCache = { json, _ -> storedJson = json; true },
            show = {},
            initialScores = emptyList(),
        )

        state.clearPdf()
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && state.loading) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(state.loading)
        assertEquals("", state.stage)
        assertEquals("", state.cache.result?.pdfBase64)
    }
}
