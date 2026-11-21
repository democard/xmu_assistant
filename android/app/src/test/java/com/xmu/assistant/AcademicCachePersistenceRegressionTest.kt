package com.xmu.assistant

import android.app.Activity
import android.content.Context
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AcademicCachePersistenceRegressionTest {
    @Test
    fun `account cache clear waits for an entered courseware writer before clearing persisted JSON`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val prefs = activity.getSharedPreferences("maintenance-academic-persist", Context.MODE_PRIVATE)
        val settings = AssistantSettings.forTest(activity, prefs)
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val course = CourseSummary("course-a", "课程 A")
        var cache = AcademicCacheSnapshot()
        val transport = QueryHttpTransport { request ->
            val body = when {
                "/courseware-activities" in request.url ->
                    """{"activities":[{"id":"file-a","type":"document","title":"讲义 A"}]}"""
                "/api/activities/" in request.url -> """{"title":"讲义 A","type":"file"}"""
                else -> error("unexpected URL: ${request.url}")
            }
            QueryHttpResponse(request.url, 200, null, body, emptyMap())
        }
        val state = CoursewareSectionState(
            activity = activity,
            requestGate = RequestGate(),
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = scope,
            show = {},
            requireLogin = { true },
            loggedIn = { true },
            cookieHeader = { "session=fixture" },
            busy = { "" },
            setBusy = {},
            selectedCourseId = { null },
            setSelectedCourseId = {},
            academicCache = { cache },
            setAcademicCache = {
                cache = it
                AcademicCacheSnapshot.updateProcessCache(it)
            },
            setAcademicCacheJson = { json ->
                writerEntered.countDown()
                check(releaseWriter.await(5, TimeUnit.SECONDS))
                settings.academicCacheJson = json
                writerFinished.countDown()
            },
            isSelectedCourse = { false },
            setPendingSessionRetry = {},
            createCoursewareClient = { cookie ->
                CoursewareClient(cookie, transport, { size -> Executors.newFixedThreadPool(size) })
            },
        )
        val clearExecutor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(state.load(course, forceRefresh = true))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (writerEntered.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                writerEntered.await(10, TimeUnit.MILLISECONDS)
            }
            assertEquals("courseware refresh must reach its real persistence callback", 0L, writerEntered.count)

            val clearStarted = CountDownLatch(1)
            val clearFinished = CountDownLatch(1)
            val clear = clearExecutor.submit {
                clearStarted.countDown()
                try {
                    clearAcademicCachePersistence(
                        invalidateCache = {
                            cache = AcademicCacheSnapshot()
                            AcademicCacheSnapshot.updateProcessCache(null)
                        },
                        clearCacheJson = { settings.academicCacheJson = "" },
                    )
                } finally {
                    clearFinished.countDown()
                }
            }
            assertTrue("account clear must start", clearStarted.await(5, TimeUnit.SECONDS))
            // On the old path the clear returns before this writer releases the gate;
            // with the barrier it remains blocked until the current write is complete.
            val clearReturnedBeforeWriter = clearFinished.await(150, TimeUnit.MILLISECONDS)
            releaseWriter.countDown()
            assertTrue("entered courseware persistence must finish", writerFinished.await(5, TimeUnit.SECONDS))
            clear.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                "logout/account replacement must leave the academic cache empty after an entered writer completes",
                "",
                settings.academicCacheJson,
            )
            assertFalse("account clear must wait for the already-entered persistence writer", clearReturnedBeforeWriter)
            assertTrue(cache.courses.isEmpty())
            assertTrue(cache.coursewareByCourse.isEmpty())
        } finally {
            releaseWriter.countDown()
            clearExecutor.shutdownNow()
            assertTrue(clearExecutor.awaitTermination(5, TimeUnit.SECONDS))
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            epoch.detachOwner(owner)
            AcademicCacheSnapshot.updateProcessCache(null)
            prefs.edit().clear().commit()
            controller.pause().stop().destroy()
        }
    }
}
