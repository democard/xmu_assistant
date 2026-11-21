package com.xmu.assistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ScoreMediaStoreRegressionTest.ExactResolverShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScoreShareLifecycleRegressionTest {
    class MissingChooserActivity : Activity() {
        override fun startActivity(intent: Intent) {
            throw ActivityNotFoundException("fixture chooser unavailable")
        }
    }

    private class MediaProvider : ContentProvider() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var block = false
        var inserts = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserts += 1
            entered.countDown()
            if (block) check(release.await(10, TimeUnit.SECONDS)) { "fixture release timed out" }
            return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "87")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
                            selectionArgs: Array<out String>?) = 1
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 1
    }

    private class Fixture(val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()) {
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val parent = SupervisorJob()
        val errors = CopyOnWriteArrayList<Throwable>()
        val messages = CopyOnWriteArrayList<String>()
        val provider = MediaProvider()
        var loggedIn = true
        val state: ScoreSectionState
        init {
            provider.attachInfo(activity, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
            val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "87")
            shadowOf(activity.contentResolver).registerOutputStreamSupplier(uri) { ByteArrayOutputStream() }
            state = ScoreSectionState(
                activity = activity, requestGate = gate, sessionEpoch = epoch, sessionOwner = owner,
                scope = CoroutineScope(parent + Dispatchers.Main + CoroutineExceptionHandler { _, error -> errors += error }),
                show = { messages += it }, loggedIn = { loggedIn }, cookieHeader = { "session=fixture-a" },
                username = { "fixture-a" }, password = { "fixture-pass" }, accountTransitionInProgress = { false },
                scoreCookieHeader = { "" }, setScoreCookieHeader = {}, setScoreRecordsJson = {},
                setScoreUpdatedAtMillisPref = {},
                scoreRecordsInitial = listOf(XmuScoreRecord(courseCode = "c1", courseName = "虚构课程",
                    term = "2025-2026-1", termCode = "20251", credit = 3.0, score = 90.0,
                    gradePoint = 4.0, countsForStatistics = true)),
            )
        }
        fun awaitCompletion() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (parent.children.none { !it.isCompleted } && gate.tryStart("score_share")) {
                    gate.finish("score_share")
                    return
                }
                Thread.sleep(10)
            }
            error("share did not finish")
        }
        fun close() {
            provider.release.countDown()
            parent.cancel()
            activity.finish()
        }
    }

    @Test
    fun `logout during storage suppresses the old account share panel and messages`() {
        val fixture = Fixture()
        try {
            fixture.provider.block = true
            fixture.state.shareLongImage()
            assertTrue(fixture.provider.entered.await(10, TimeUnit.SECONDS))
            fixture.epoch.invalidate(fixture.owner)
            fixture.loggedIn = false
            fixture.state.clearAll()
            fixture.provider.release.countDown()
            fixture.awaitCompletion()
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
            assertTrue(fixture.messages.isEmpty())
            assertTrue(fixture.errors.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun `missing chooser reports a recoverable error instead of escaping the coroutine`() {
        val fixture = Fixture(Robolectric.buildActivity(MissingChooserActivity::class.java).setup().get())
        try {
            fixture.state.shareLongImage()
            fixture.awaitCompletion()
            assertTrue("uncaught errors: ${fixture.errors}", fixture.errors.isEmpty())
            assertTrue(fixture.messages.any { it.contains("分享") })
            assertTrue(fixture.gate.tryStart("score_share"))
            fixture.gate.finish("score_share")
        } finally { fixture.close() }
    }

    @Test
    fun `rapid share clicks during storage generate only one image and chooser`() {
        val fixture = Fixture()
        try {
            fixture.provider.block = true
            fixture.state.shareLongImage()
            assertTrue(fixture.provider.entered.await(10, TimeUnit.SECONDS))
            fixture.state.shareLongImage()
            fixture.provider.release.countDown()
            fixture.awaitCompletion()
            assertEquals(1, fixture.provider.inserts)
            assertNotNull(shadowOf(fixture.activity).nextStartedActivity)
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
        } finally { fixture.close() }
    }

    @Test
    fun `current offline cache remains exportable without starting a login`() {
        val fixture = Fixture()
        try {
            fixture.loggedIn = false
            fixture.state.shareLongImage()
            fixture.awaitCompletion()
            assertEquals(1, fixture.provider.inserts)
            assertNotNull(shadowOf(fixture.activity).nextStartedActivity)
            assertTrue(fixture.errors.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun `finishing activity does not receive a late share panel`() {
        val fixture = Fixture()
        try {
            fixture.provider.block = true
            fixture.state.shareLongImage()
            assertTrue(fixture.provider.entered.await(10, TimeUnit.SECONDS))
            fixture.activity.finish()
            fixture.provider.release.countDown()
            fixture.awaitCompletion()
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
            assertTrue(fixture.errors.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun `successful sharing starts one chooser and releases the gate`() {
        val fixture = Fixture()
        try {
            fixture.state.shareLongImage()
            fixture.awaitCompletion()
            assertEquals(1, fixture.provider.inserts)
            assertNotNull(shadowOf(fixture.activity).nextStartedActivity)
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.gate.tryStart("score_share"))
            fixture.gate.finish("score_share")
        } finally { fixture.close() }
    }

    @Test
    fun `cancelled scope does not save and releases a newly acquired share gate`() {
        val fixture = Fixture()
        try {
            fixture.parent.cancel()
            fixture.state.shareLongImage()
            fixture.awaitCompletion()
            assertEquals(0, fixture.provider.inserts)
            assertTrue(fixture.gate.tryStart("score_share"))
            fixture.gate.finish("score_share")
        } finally { fixture.close() }
    }
}
