package com.xmu.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorStartPolicyTest {
    @Test
    fun `worker starts only for an explicitly desired live session`() {
        assertTrue(shouldStartMonitorWorker(monitorDesired = true, cookieHeader = "session-cookie"))
        assertFalse(shouldStartMonitorWorker(monitorDesired = false, cookieHeader = "session-cookie"))
        assertFalse(shouldStartMonitorWorker(monitorDesired = true, cookieHeader = ""))
        assertFalse(shouldStartMonitorWorker(monitorDesired = true, cookieHeader = "   "))
    }
}
