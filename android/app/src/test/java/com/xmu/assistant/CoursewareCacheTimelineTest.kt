package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
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
class CoursewareCacheTimelineTest {
    private val courseA = CourseSummary("course-a", "课程 A")
    private val courseB = CourseSummary("course-b", "课程 B")

    private fun item(course: CourseSummary) = CoursewareUiItem(
        "file-${course.id}", course.id, "activity-${course.id}", "讲义 ${course.title}", "${course.id}.pdf", "文档",
        sourceUrl = "https://fixture.invalid/${course.id}.pdf",
    )

    private inner class Harness(
        query: QueryHttpTransport = QueryHttpTransport { error("unexpected network request") },
    ) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + Dispatchers.IO)
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val downloadEntered = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        private val downloads = File(controller.get().filesDir, "maintenance3-downloads").apply { mkdirs() }
        var selected: String? = null
        var busy = ""
        var account = "account-a"
        var cache = AcademicCacheSnapshot(
            coursewareByCourse = mapOf(courseA.id to listOf(item(courseA)), courseB.id to listOf(item(courseB))),
            coursewareUpdatedAtMillis = mapOf(courseA.id to System.currentTimeMillis(), courseB.id to System.currentTimeMillis()),
        )
        val writes = CopyOnWriteArrayList<String>()
        val state = CoursewareSectionState(
            controller.get(), RequestGate(), epoch, owner, scope, {}, requireLogin = { true }, loggedIn = { true },
            cookieHeader = { "session=$account" }, busy = { busy }, setBusy = { busy = it },
            selectedCourseId = { selected }, setSelectedCourseId = { selected = it },
            academicCache = { cache }, setAcademicCache = { cache = it; AcademicCacheSnapshot.updateProcessCache(it) },
            setAcademicCacheJson = { writes += it }, isSelectedCourse = { selected == it }, setPendingSessionRetry = {},
            createCoursewareClient = { cookie -> CoursewareClient(
                cookie, query, { size -> Executors.newFixedThreadPool(size) },
                FileDownloadTransport { _, target ->
                    downloadEntered.countDown()
                    check(releaseDownload.await(5, TimeUnit.SECONDS))
                    target.writeText("%PDF-fixture")
                    FileDownloadResult(200, "application/pdf")
                }, downloads,
            ) },
        )

        fun startDownload(): Job {
            assertFalse(state.load(courseA))
            state.toggleSelectAll()
            val previous = parent.children.toSet()
            state.downloadSelected()
            val request = parent.children.single { it !in previous }
            assertTrue(downloadEntered.await(5, TimeUnit.SECONDS))
            return request
        }

        fun load(course: CourseSummary): Job {
            val previous = parent.children.toSet()
            assertTrue(state.load(course, forceRefresh = true))
            return parent.children.single { it !in previous }
        }

        fun finish(request: Job) {
            val done = CountDownLatch(1)
            request.invokeOnCompletion { done.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (done.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                done.await(1, TimeUnit.MILLISECONDS)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("courseware operation must finish", 0L, done.count)
        }

        fun savedFiles(): List<String> = downloads.listFiles().orEmpty().filter { it.extension == "pdf" }.map { it.readText() }

        override fun close() {
            releaseDownload.countDown()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            downloads.listFiles().orEmpty().forEach { it.delete() }
            downloads.delete()
            AcademicCacheSnapshot.updateProcessCache(null)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `finishing a download after selecting another course keeps that course's progress empty`() {
        Harness().use { h ->
            val request = h.startDownload()
            assertFalse(h.state.load(courseB))
            assertEquals("", h.state.coursewareDownloadProgress)
            h.releaseDownload.countDown()
            h.finish(request)

            assertEquals(courseB.id, h.selected)
            assertEquals(listOf(item(courseB)), h.state.coursewareItems)
            assertEquals(listOf(item(courseB)), h.cache.coursewareByCourse[courseB.id])
            assertEquals("old course download must not repopulate new course progress", "", h.state.coursewareDownloadProgress)
            assertEquals("switching courses must not discard the completed download", listOf("%PDF-fixture"), h.savedFiles())
            assertFalse(h.state.downloadLoading)
        }
    }

    @Test
    fun `download completion after account replacement cannot refill cleared courseware`() {
        Harness().use { h ->
            val request = h.startDownload()
            h.epoch.invalidate(h.owner)
            h.account = "account-b"
            h.cache = AcademicCacheSnapshot()
            h.selected = null
            h.state.clearAll()
            h.releaseDownload.countDown()
            h.finish(request)

            assertTrue(h.state.coursewareItems.isEmpty())
            assertTrue(h.state.selectedCoursewareIds.isEmpty())
            assertEquals("", h.state.coursewareDownloadProgress)
            assertFalse(h.state.downloadLoading)
        }
    }

    @Test
    fun `late course A list joins cache without replacing the selected course B list`() {
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val query = QueryHttpTransport { request ->
            val course = if ("course-a" in request.url) courseA else courseB
            val body = when {
                "courseware-activities" in request.url -> {
                    if (course == courseA) { aEntered.countDown(); check(releaseA.await(5, TimeUnit.SECONDS)) }
                    """{"activities":[{"id":"${course.id}-activity","type":"document","title":"${course.title}"}]}"""
                }
                "/api/activities/" in request.url -> """{"title":"最新 ${course.title}","type":"file"}"""
                else -> error("unexpected URL: ${request.url}")
            }
            QueryHttpResponse(request.url, 200, null, body, emptyMap())
        }
        try {
            Harness(query).use { h ->
                val a = h.load(courseA)
                assertTrue(aEntered.await(5, TimeUnit.SECONDS))
                h.finish(h.load(courseB))
                val selectedItems = h.state.coursewareItems
                releaseA.countDown()
                h.finish(a)

                assertEquals(courseB.id, h.selected)
                assertEquals(selectedItems, h.state.coursewareItems)
                assertEquals("最新 课程 A", h.cache.coursewareByCourse[courseA.id]?.single()?.title)
                assertEquals("最新 课程 B", h.cache.coursewareByCourse[courseB.id]?.single()?.title)
                assertTrue(h.state.loadingIds.isEmpty())
            }
        } finally { releaseA.countDown() }
    }
}
