package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
class RankLifecycleRegressionTest {
    private class Harness(
        private val factoryFailure: Boolean = false,
        private val persist: (String) -> Unit = {},
    ) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val parent = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(parent + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures += error })
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        var requests = 0
        var stored = rankCacheToJson(RankCache(rankDigest("account-a"), pending = RankPending(
            emptySet(), RankRange("range", "全部"), 100L, rankScoreFingerprint(emptyList()),
        )))
        val state = RankSectionState(
            controller.get(), gate, epoch, owner, scope,
            { true }, { "session=account-a" }, { "account-a" }, { "synthetic" }, { false },
            { "JWSESSION=original" }, persist,
            { stored }, { value, current -> if (current()) { stored = value; true } else false },
            {}, emptyList(),
            clientFactory = { cookie, renew, active ->
                if (factoryFailure) throw IOException("client initialization failed")
                XmuRankClient(cookie, renew, active, RankTransport { _, _, _ ->
                    requests++
                    RankResponse(200, mapOf("Set-Cookie" to listOf("JWSESSION=renewed; Path=/")),
                        """{"datas":{"getJdjssq":[]}}""".toByteArray())
                })
            },
            pollDelays = emptyList(), fallbackPollDelays = emptyList(),
        )

        fun start(): Job? {
            state.fetch()
            return parent.children.singleOrNull()
        }

        fun awaitDone(job: Job?) {
            if (job != null) assertTrue("ranking operation must complete", awaitCondition { job.isCompleted })
        }

        fun assertGateReleased() {
            assertTrue("completed ranking operation must release its admission", gate.tryStart("rankings"))
            gate.finish("rankings")
        }

        override fun close() {
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            controller.pause().stop().destroy()
        }
    }

    companion object {
        private fun awaitCondition(condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return true
                Thread.sleep(5)
            }
            return condition()
        }
    }

    @Test
    fun `cancel before fetch dispatch releases ranking admission without network work`() {
        Harness().use { h ->
            h.scope.cancel()
            h.awaitDone(h.start())
            shadowOf(Looper.getMainLooper()).idle()
            h.assertGateReleased()
            assertEquals(0, h.requests)
            assertTrue(h.failures.isEmpty())
        }
    }

    @Test
    fun `cancel before local cache mutations releases ranking admission`() {
        for (abandon in listOf(false, true)) {
            Harness().use { h ->
                h.scope.cancel()
                if (abandon) h.state.abandonPending() else h.state.clearPdf()
                shadowOf(Looper.getMainLooper()).idle()
                h.assertGateReleased()
                assertEquals(0, h.requests)
                assertTrue(h.failures.isEmpty())
            }
        }
    }

    @Test
    fun `client construction failure is contained and does not strand the request`() {
        Harness(factoryFailure = true).use { h ->
            val thrown = runCatching { h.start() }.exceptionOrNull()
            assertEquals("client construction must use the normal failure path", null, thrown)
            assertTrue(awaitCondition { !h.state.loading })
            h.assertGateReleased()
            assertTrue(h.state.error.contains("client initialization failed"))
            assertEquals(0, h.requests)
            assertTrue(h.failures.isEmpty())
        }
    }

    @Test
    fun `renewed cookie persistence shares the main thread with session transitions`() {
        val commits = CopyOnWriteArrayList<Pair<String, Boolean>>()
        Harness(persist = { commits += it to (Looper.myLooper() == Looper.getMainLooper()) }).use { h ->
            h.awaitDone(h.start())
            assertEquals(1, commits.size)
            assertTrue(commits.single().first.contains("JWSESSION=renewed"))
            assertTrue("cookie acceptance and logout must be ordered on Main", commits.single().second)
            h.assertGateReleased()
            assertFalse(h.state.loading)
        }
    }

    @Test
    fun `logout cannot be overtaken by an old off-main cookie setter`() {
        val persisted = AtomicReference("JWSESSION=original")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Harness(persist = { cookie ->
            entered.countDown()
            if (Looper.myLooper() != Looper.getMainLooper()) {
                check(release.await(10, TimeUnit.SECONDS))
            }
            persisted.set(cookie)
        }).use { h ->
            try {
                val job = h.start()
                assertTrue(awaitCondition { entered.count == 0L })
                h.epoch.invalidate(h.owner)
                h.state.clearAll()
                persisted.set("JWSESSION=new-account")
                release.countDown()
                h.awaitDone(job)
                assertEquals("JWSESSION=new-account", persisted.get())
                h.assertGateReleased()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `cookie persistence failure still clears loading and releases admission`() {
        Harness(persist = { throw IOException("credential storage failed") }).use { h ->
            h.awaitDone(h.start())
            h.assertGateReleased()
            assertFalse(h.state.loading)
            assertTrue("storage failure must be handled by the module", h.failures.isEmpty())
            assertTrue(h.state.error.isNotBlank())
        }
    }
}
