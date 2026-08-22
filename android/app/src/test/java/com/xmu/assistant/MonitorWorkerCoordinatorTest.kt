package com.xmu.assistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorWorkerCoordinatorTest {
    @Test
    fun `active current token ignores repeated start`() {
        val coordinator = MonitorWorkerCoordinator()
        val token = requireNotNull(coordinator.start(monitorDesired = true))

        assertNull(coordinator.start(monitorDesired = true))
        assertTrue(coordinator.isCurrent(token, monitorDesired = true))
    }

    @Test
    fun `invalidated old token starts a replacement while old worker still exists`() {
        val coordinator = MonitorWorkerCoordinator()
        val oldToken = requireNotNull(coordinator.start(monitorDesired = true))

        coordinator.requestInvalidateCurrent()
        val newToken = requireNotNull(coordinator.start(monitorDesired = true))

        assertNotEquals(oldToken, newToken)
        assertFalse(coordinator.isCurrent(oldToken, monitorDesired = true))
        assertTrue(coordinator.isCurrent(newToken, monitorDesired = true))
    }

    @Test
    fun `old worker completion cannot clear a newer worker`() {
        val coordinator = MonitorWorkerCoordinator()
        val oldToken = requireNotNull(coordinator.start(monitorDesired = true))
        coordinator.requestInvalidateCurrent()
        val newToken = requireNotNull(coordinator.start(monitorDesired = true))

        coordinator.complete(oldToken)

        assertTrue(coordinator.isCurrent(newToken, monitorDesired = true))
        assertNull(coordinator.start(monitorDesired = true))
    }

    @Test
    fun `current worker completion clears state for a future start`() {
        val coordinator = MonitorWorkerCoordinator()
        val token = requireNotNull(coordinator.start(monitorDesired = true))

        coordinator.complete(token)

        assertFalse(coordinator.isCurrent(token, monitorDesired = true))
        assertNotEquals(token, requireNotNull(coordinator.start(monitorDesired = true)))
    }

    @Test
    fun `conditional destroy invalidates only its current worker and restart works`() {
        val coordinator = MonitorWorkerCoordinator()
        val oldToken = requireNotNull(coordinator.start(monitorDesired = true))

        coordinator.requestInvalidate(oldToken)
        val newToken = requireNotNull(coordinator.start(monitorDesired = true))
        coordinator.requestInvalidate(oldToken)

        assertTrue(coordinator.isCurrent(newToken, monitorDesired = true))
        coordinator.requestInvalidate(newToken)
        assertFalse(coordinator.isCurrent(newToken, monitorDesired = true))
        assertNotEquals(newToken, requireNotNull(coordinator.start(monitorDesired = true)))
    }

    @Test
    fun `blocked side effect permits replacement ownership but not overlapping execution`() {
        val coordinator = MonitorWorkerCoordinator()
        val oldToken = requireNotNull(coordinator.start(monitorDesired = true))
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val actionFinished = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)

        Thread {
            coordinator.runIfCurrent(oldToken, monitorDesired = true) {
                actionStarted.countDown()
                assertTrue(releaseAction.await(5, TimeUnit.SECONDS))
            }
            actionFinished.countDown()
        }.start()
        assertTrue(actionStarted.await(5, TimeUnit.SECONDS))

        coordinator.requestInvalidateCurrent()
        val replacement = requireNotNull(coordinator.start(monitorDesired = true))
        val replacementThread = Thread {
            coordinator.runIfCurrent(replacement, monitorDesired = true) {
                replacementEntered.countDown()
            }
        }
        replacementThread.start()

        assertNotEquals(oldToken, replacement)
        assertTrue(coordinator.isCurrent(replacement, monitorDesired = true))
        assertFalse(actionFinished.await(150, TimeUnit.MILLISECONDS))
        assertFalse(replacementEntered.await(150, TimeUnit.MILLISECONDS))
        releaseAction.countDown()
        assertTrue(actionFinished.await(5, TimeUnit.SECONDS))
        assertTrue(replacementEntered.await(5, TimeUnit.SECONDS))
        replacementThread.join(5_000)
        coordinator.awaitQuiescence()
    }
}
