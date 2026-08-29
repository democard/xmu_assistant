package com.xmu.assistant

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryTest {
    @Test
    fun `missing policy enables auto login only when a cookie exists`() {
        assertEquals(AutoLoginPolicy.ENABLED, migratedAutoLoginPolicy(null, "cookie"))
        assertEquals(AutoLoginPolicy.USER_LOGGED_OUT, migratedAutoLoginPolicy(null, ""))
        assertEquals(AutoLoginPolicy.USER_LOGGED_OUT, migratedAutoLoginPolicy(null, "   "))
    }

    @Test
    fun `stored valid policy wins and invalid policy migrates conservatively`() {
        assertEquals(AutoLoginPolicy.BLOCKED, migratedAutoLoginPolicy("BLOCKED", "cookie"))
        assertEquals(AutoLoginPolicy.USER_LOGGED_OUT, migratedAutoLoginPolicy("USER_LOGGED_OUT", "cookie"))
        assertEquals(AutoLoginPolicy.ENABLED, migratedAutoLoginPolicy("unknown", "cookie"))
        assertEquals(AutoLoginPolicy.USER_LOGGED_OUT, migratedAutoLoginPolicy("unknown", ""))
    }

    @Test
    fun `policy store reads missing and invalid values without writing migration back`() {
        var stored: String? = null
        val writes = mutableListOf<String>()
        val store = AutoLoginPolicyStore(
            readStored = { stored },
            readCookie = { "cookie" },
            writeStored = { writes += it; stored = it },
        )

        assertEquals(AutoLoginPolicy.ENABLED, store.policy)
        stored = "invalid"
        assertEquals(AutoLoginPolicy.ENABLED, store.policy)
        assertEquals("invalid", stored)
        stored = null
        assertEquals(AutoLoginPolicy.ENABLED, store.policy)
        assertTrue("reading must not materialize migration", writes.isEmpty())
        assertNull(stored)
    }

    @Test
    fun `policy store helpers persist every explicit three-state transition`() {
        var stored: String? = null
        val store = AutoLoginPolicyStore(
            readStored = { stored },
            readCookie = { "cookie" },
            writeStored = { stored = it },
        )

        store.markLoginSucceeded()
        assertEquals("ENABLED", stored)
        assertEquals(AutoLoginPolicy.ENABLED, store.policy)
        store.markAutoLoginFailed()
        assertEquals("BLOCKED", stored)
        assertEquals(AutoLoginPolicy.BLOCKED, store.policy)
        store.markUserLoggedOut()
        assertEquals("USER_LOGGED_OUT", stored)
        assertEquals(AutoLoginPolicy.USER_LOGGED_OUT, store.policy)
    }

    @Test
    fun `stale migration reads cannot overwrite explicit blocked or logged out policies`() {
        listOf(AutoLoginPolicy.BLOCKED, AutoLoginPolicy.USER_LOGGED_OUT).forEach { explicitPolicy ->
            var stored: String? = null
            val readCaptured = CountDownLatch(1)
            val resumeRead = CountDownLatch(1)
            val store = AutoLoginPolicyStore(
                readStored = {
                    val snapshot = stored
                    readCaptured.countDown()
                    check(resumeRead.await(5, TimeUnit.SECONDS)) { "stale reader was not resumed" }
                    snapshot
                },
                readCookie = { "cookie" },
                writeStored = { stored = it },
            )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val staleRead = executor.submit<AutoLoginPolicy> { store.policy }
                assertTrue("reader did not capture old value", readCaptured.await(5, TimeUnit.SECONDS))
                store.policy = explicitPolicy
                resumeRead.countDown()

                assertEquals(AutoLoginPolicy.ENABLED, staleRead.get(5, TimeUnit.SECONDS))
                assertEquals(explicitPolicy.name, stored)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `concurrent probe admission has exactly one winner`() {
        val coordinator = SessionRecoveryCoordinator()
        val results = concurrently(16) { coordinator.tryStartProbe(100L) }

        assertEquals(1, results.filterNotNull().size)
    }

    @Test
    fun `completed probe is suppressed until the full interval elapses`() {
        val coordinator = SessionRecoveryCoordinator()
        val token = requireNotNull(coordinator.tryStartProbe(1_000L))

        coordinator.finishProbe(token, 1_000L)

        assertNull(coordinator.tryStartProbe(60_999L))
        assertNotNull(coordinator.tryStartProbe(61_000L))
    }

    @Test
    fun `cancelled stale probe releases single flight without consuming throttle`() {
        val coordinator = SessionRecoveryCoordinator()
        val token = requireNotNull(coordinator.tryStartProbe(1_000L))

        coordinator.cancelProbe(token)

        assertNotNull(coordinator.tryStartProbe(1_000L))
    }

    @Test
    fun `clock rollback conservatively admits a new probe while exact ttl remains enforced`() {
        val coordinator = SessionRecoveryCoordinator()
        val first = requireNotNull(coordinator.tryStartProbe(100_000L))
        coordinator.finishProbe(first, 100_000L)

        assertNotNull(coordinator.tryStartProbe(90_000L))

        val exact = SessionRecoveryCoordinator()
        val exactFirst = requireNotNull(exact.tryStartProbe(100_000L))
        exact.finishProbe(exactFirst, 100_000L)
        assertNull(exact.tryStartProbe(159_999L))
        assertNotNull(exact.tryStartProbe(160_000L))
    }

    @Test
    fun `invalidated probe token cannot finish or be accepted`() {
        val coordinator = SessionRecoveryCoordinator()
        val token = requireNotNull(coordinator.tryStartProbe(1_000L))

        coordinator.invalidate()
        coordinator.finishProbe(token, 1_000L)

        assertFalse(coordinator.accepts(token))
        assertNotNull(coordinator.tryStartProbe(1_000L))
    }

    @Test
    fun `concurrent auto login admission has exactly one winner`() {
        val coordinator = SessionRecoveryCoordinator()
        val results = concurrently(16) { coordinator.tryStartAutoLogin() }

        assertEquals(1, results.filterNotNull().size)
    }

    @Test
    fun `finished and invalidated auto login work is rejected`() {
        val coordinator = SessionRecoveryCoordinator()
        val completed = requireNotNull(coordinator.tryStartAutoLogin())

        assertTrue(coordinator.accepts(completed))
        coordinator.finishAutoLogin(completed)
        assertFalse(coordinator.accepts(completed))

        val stale = requireNotNull(coordinator.tryStartAutoLogin())
        coordinator.invalidate()
        assertFalse(coordinator.accepts(stale))
        assertNotNull(coordinator.tryStartAutoLogin())
    }

    @Test
    fun `probe and auto login are independent but both invalidated together`() {
        val coordinator = SessionRecoveryCoordinator()
        val probe = requireNotNull(coordinator.tryStartProbe(100L))
        val autoLogin = requireNotNull(coordinator.tryStartAutoLogin())

        assertTrue(coordinator.accepts(probe))
        assertTrue(coordinator.accepts(autoLogin))
        coordinator.invalidate()
        assertFalse(coordinator.accepts(probe))
        assertFalse(coordinator.accepts(autoLogin))
    }

    private fun concurrently(count: Int, task: () -> SessionWorkToken?): List<SessionWorkToken?> {
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        try {
            val futures = (1..count).map {
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "workers did not start" }
                    task()
                })
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "workers did not become ready" }
            start.countDown()
            return futures.map { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
