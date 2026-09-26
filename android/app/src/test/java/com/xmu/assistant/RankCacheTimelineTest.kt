package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import java.util.concurrent.CountDownLatch
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
class RankCacheTimelineTest {
    private class Harness : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        private val parent = SupervisorJob()
        private val scope = CoroutineScope(parent + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        var account = "account-a"
        var stored = rankCacheToJson(RankCache(rankDigest(account), pending = RankPending(
            emptySet(), RankRange("scope", "全部"), 100L, rankScoreFingerprint(emptyList()),
        )))
        val requests = mutableListOf<String>()
        val state = RankSectionState(
            controller.get(), RequestGate(), epoch, owner, scope, { true }, { "session=$account" },
            { account }, { "synthetic" }, { false }, { "academic=synthetic" }, {},
            { stored }, { value, active -> if (active()) { stored = value; true } else false }, {}, emptyList(),
            clientFactory = { cookie, renew, active ->
                XmuRankClient(cookie, renew, active, RankTransport { path, _, _ ->
                    requests += path
                    check("getJdjssq.do" in path) { "Only the existing pending request may be queried" }
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    RankResponse(200, emptyMap(), """{"datas":{"getJdjssq":[]}}""".toByteArray())
                })
            },
            pollDelays = emptyList(), fallbackPollDelays = emptyList(),
        )

        fun startFinalPoll(): Job {
            state.fetch()
            val request = parent.children.single()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            return request
        }

        fun finish(request: Job) {
            release.countDown()
            val done = CountDownLatch(1)
            request.invokeOnCompletion { done.countDown() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (done.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                done.await(1, TimeUnit.MILLISECONDS)
            }
            assertEquals("rank query must finish", 0L, done.count)
        }

        override fun close() {
            release.countDown()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `old final rank poll cannot restore a warning after switching accounts and clearing`() {
        Harness().use { h ->
            val request = h.startFinalPoll()
            h.epoch.invalidate(h.owner)
            h.account = "account-b"
            h.state.clearAll()
            h.finish(request)

            assertEquals("old pending query must not change new account's UI", "", h.state.error)
            assertEquals("", h.state.stage)
            assertFalse(h.state.loading)
            assertNull(h.state.cache.pending)
            assertNull(h.state.cache.result)
            assertEquals(rankDigest("account-b"), h.state.cache.owner)
            assertEquals("", h.stored)
            assertEquals(1, h.requests.size)
        }
    }

    @Test
    fun `live pending rank query retains its pending marker without resubmitting`() {
        Harness().use { h ->
            h.finish(h.startFinalPoll())

            assertTrue(h.state.error.contains("本次计算尚未完成"))
            assertNotNull(h.state.cache.pending)
            assertNotNull(rankCacheFromJson(h.stored, rankDigest(h.account)).pending)
            assertEquals(1, h.requests.size)
            assertFalse(h.state.loading)
        }
    }
}
