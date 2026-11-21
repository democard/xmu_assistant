package com.xmu.assistant

import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AcademicCacheStartupRegressionTest {
    private class Queue : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private fun snapshot(title: String = "课程 A") = AcademicCacheSnapshot(
        courses = listOf(CourseSummary("course-a", title)),
        coursesUpdatedAtMillis = 100L,
        coursewareByCourse = mapOf("course-a" to listOf(CoursewareUiItem(
            "file-a", "course-a", "activity-a", "讲义 A", "lecture.pdf", "文档",
        ))),
    )

    private class Harness(stored: AcademicCacheSnapshot) : AutoCloseable {
        private val queue = Queue()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private var storedJson = academicCacheToJson(stored)
        var cache = AcademicCacheSnapshot()
            private set
        var courses = emptyList<CourseSummary>()
            private set
        var reads = 0
            private set
        private var request: Job? = null

        init { AcademicCacheSnapshot.updateProcessCache(null) }

        fun readButHoldMain() {
            request = scope.launch {
                restoreAcademicCacheOnStartup(
                    currentCache = { cache },
                    readCacheJson = { reads++; storedJson },
                    applyCache = ::replace,
                    cacheDispatcher = queue,
                )
            }
            shadowOf(Looper.getMainLooper()).idle()
            queue.drain()
            assertEquals("the actual startup reader must have captured the old persisted snapshot", 1, reads)
        }

        fun replace(next: AcademicCacheSnapshot) {
            AcademicCacheSnapshot.updateProcessCache(next)
            cache = next
            courses = next.courses
        }

        fun clearAccount() {
            AcademicCacheSnapshot.updateProcessCache(null)
            cache = AcademicCacheSnapshot()
            courses = emptyList()
            storedJson = ""
        }

        fun finish() {
            shadowOf(Looper.getMainLooper()).idle()
            queue.drain()
            assertTrue("startup continuation must finish", request?.isCompleted == true)
        }

        override fun close() {
            scope.cancel()
            queue.drain()
            shadowOf(Looper.getMainLooper()).idle()
            AcademicCacheSnapshot.updateProcessCache(null)
        }
    }

    @Test
    fun `logout after startup read cannot restore the former account in memory or process cache`() {
        Harness(snapshot()).use { h ->
            h.readButHoldMain()
            h.clearAccount()
            h.finish()

            assertTrue("logout must keep the visible course list empty", h.courses.isEmpty())
            assertEquals(AcademicCacheSnapshot(), h.cache)
            assertNull("late startup read must not recreate the former process snapshot", AcademicCacheSnapshot.currentProcessCache())
        }
    }

    @Test
    fun `account replacement with an empty cache invalidates an already read former snapshot`() {
        Harness(snapshot()).use { h ->
            h.readButHoldMain()
            val accountB = AcademicCacheSnapshot()
            h.replace(accountB)
            h.finish()

            assertTrue("new account must not inherit the former course list", h.courses.isEmpty())
            assertEquals(accountB, h.cache)
            assertEquals(accountB, AcademicCacheSnapshot.currentProcessCache())
        }
    }

    @Test
    fun `newer successful empty course refresh wins over a startup snapshot already waiting on Main`() {
        Harness(snapshot()).use { h ->
            h.readButHoldMain()
            val refreshed = AcademicCacheSnapshot(coursesUpdatedAtMillis = 200L)
            h.replace(refreshed)
            h.finish()

            assertEquals(refreshed, h.cache)
            assertEquals(refreshed, AcademicCacheSnapshot.currentProcessCache())
            assertTrue(h.courses.isEmpty())
        }
    }

    @Test
    fun `unchanged startup applies persisted courses and courseware through the real loader`() {
        val stored = snapshot()
        Harness(stored).use { h ->
            h.readButHoldMain()
            h.finish()

            assertEquals(stored, h.cache)
            assertEquals(stored.courses, h.courses)
            assertEquals(stored, AcademicCacheSnapshot.currentProcessCache())
        }
    }

    @Test
    fun `nonempty refresh remains visible when the old startup snapshot resumes`() {
        Harness(snapshot()).use { h ->
            h.readButHoldMain()
            val refreshed = snapshot("课程 B")
            h.replace(refreshed)
            h.finish()

            assertEquals(refreshed, h.cache)
            assertEquals(refreshed.courses, h.courses)
        }
    }
}
