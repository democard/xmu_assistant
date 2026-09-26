package com.xmu.assistant

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupSessionLifecycleTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }

        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }

    private class RecoveryGate(val automaticLogin: Boolean) {
        val coordinator = SessionRecoveryCoordinator(minProbeIntervalMillis = 0)
        fun start(): SessionWorkToken? = if (automaticLogin) {
            coordinator.tryStartAutoLogin()
        } else {
            coordinator.tryStartProbe(100L)
        }
        fun finish(token: SessionWorkToken) {
            if (automaticLogin) coordinator.finishAutoLogin(token) else coordinator.cancelProbe(token)
        }
    }

    @Test
    fun `destroy before IO dispatch releases probe and automatic login admission`() {
        listOf(false, true).forEach { automaticLogin ->
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val gate = RecoveryGate(automaticLogin)
            val token = requireNotNull(gate.start())
            val io = QueuedDispatcher()
            var requests = 0
            val job = controller.get().lifecycleScope.launchStartupSessionWork(
                onFinished = { gate.finish(token) }, dispatcher = io,
            ) { requests++ }

            controller.pause().stop().destroy()
            io.drain()

            assertTrue(job.isCompleted)
            assertTrue(job.isCancelled)
            assertEquals(0, requests)
            assertNotNull("destroyed owner left admission occupied: login=$automaticLogin", gate.start())
        }
    }

    @Test
    fun `destroy while IO is suspended drops completion and releases both recovery gates`() {
        listOf(false, true).forEach { automaticLogin ->
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val gate = RecoveryGate(automaticLogin)
            val token = requireNotNull(gate.start())
            val io = QueuedDispatcher()
            val main = QueuedDispatcher()
            val response = CompletableDeferred<Unit>()
            var started = false
            var callbacks = 0
            val job = controller.get().lifecycleScope.launchStartupSessionWork(
                onFinished = { gate.finish(token) }, dispatcher = io,
            ) {
                started = true
                response.await()
                withContext(main) { callbacks++ }
            }
            io.drain()
            assertTrue(started)

            controller.pause().stop().destroy()
            response.complete(Unit)
            io.drain()
            main.drain()
            io.drain()

            assertTrue(job.isCompleted)
            assertEquals(0, callbacks)
            assertNotNull("cancelled request left admission occupied: login=$automaticLogin", gate.start())
        }
    }

    @Test
    fun `cancelled blocking login keeps admission until the underlying operation actually returns`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val gate = RecoveryGate(automaticLogin = true)
        val token = requireNotNull(gate.start())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val job = controller.get().lifecycleScope.launchStartupSessionWork(
                onFinished = { gate.finish(token) }, dispatcher = io,
            ) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                withContext(QueuedDispatcher()) { callbacks.incrementAndGet() }
            }
            job.invokeOnCompletion { completed.countDown() }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                controller.pause().stop().destroy()
                assertFalse("blocking request has not returned yet", job.isCompleted)
                assertNull("another login must not overlap the still-running operation", gate.start())
            } finally {
                release.countDown()
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(0, callbacks.get())
            assertNotNull("finished cancelled login must no longer hold admission", gate.start())
        }
    }

    @Test
    fun `late result from a replaced owner cannot mutate state or release newer admission`() {
        val gate = RecoveryGate(automaticLogin = true)
        val epoch = SessionEpoch()
        val oldOwner = epoch.attachOwner()
        val oldToken = requireNotNull(gate.start())
        val io = QueuedDispatcher()
        val main = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + io)
        var callbacks = 0
        try {
            val job = scope.launchStartupSessionWork(onFinished = { gate.finish(oldToken) }, dispatcher = io) {
                withContext(main) {
                    if (gate.coordinator.accepts(oldToken) && epoch.isOwnerActive(oldOwner)) callbacks++
                }
            }
            io.drain() // Old IO is complete, while its main-thread result is still queued.
            epoch.attachOwner()
            gate.coordinator.invalidate()
            val newToken = requireNotNull(gate.start())

            main.drain()
            io.drain()

            assertTrue(job.isCompleted)
            assertEquals(0, callbacks)
            assertTrue(gate.coordinator.accepts(newToken))
            assertNull(gate.start())
        } finally {
            scope.cancel()
        }
    }
}
