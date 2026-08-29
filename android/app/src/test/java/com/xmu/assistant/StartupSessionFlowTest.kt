package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSessionFlowTest {
    @Test
    fun `valid session is kept regardless of auto login policy`() {
        AutoLoginPolicy.entries.forEach { policy ->
            assertEquals(
                StartupSessionAction.KEEP_SESSION,
                decideStartupSessionAction(SessionHealth.VALID, policy, credentialsPresent = false),
            )
        }
    }

    @Test
    fun `unknown session is retained and never auto logged in`() {
        AutoLoginPolicy.entries.forEach { policy ->
            assertEquals(
                StartupSessionAction.SHOW_UNKNOWN,
                decideStartupSessionAction(SessionHealth.UNKNOWN, policy, credentialsPresent = true),
            )
        }
    }

    @Test
    fun `expired session auto logs in only with enabled policy and complete credentials`() {
        assertEquals(
            StartupSessionAction.AUTO_LOGIN,
            decideStartupSessionAction(SessionHealth.EXPIRED, AutoLoginPolicy.ENABLED, credentialsPresent = true),
        )
        listOf(
            AutoLoginPolicy.ENABLED to false,
            AutoLoginPolicy.BLOCKED to true,
            AutoLoginPolicy.USER_LOGGED_OUT to true,
        ).forEach { (policy, credentialsPresent) ->
            assertEquals(
                StartupSessionAction.STAY_LOGGED_OUT,
                decideStartupSessionAction(SessionHealth.EXPIRED, policy, credentialsPresent),
            )
        }
    }

    @Test
    fun `blank cookie startup evaluates enabled policy without probing`() {
        assertEquals(
            StartupSessionAction.AUTO_LOGIN,
            decideBlankCookieStartupAction(AutoLoginPolicy.ENABLED, credentialsPresent = true),
        )
        listOf(AutoLoginPolicy.ENABLED to false, AutoLoginPolicy.BLOCKED to true, AutoLoginPolicy.USER_LOGGED_OUT to true)
            .forEach { (policy, credentialsPresent) ->
                assertEquals(StartupSessionAction.STAY_LOGGED_OUT, decideBlankCookieStartupAction(policy, credentialsPresent))
            }
    }

    @Test
    fun `orchestrator skips probe for blank cookie and preserves unknown state`() {
        var probes = 0
        var logins = 0
        val blank = StartupRecoveryOrchestrator({ probes += 1; SessionHealth.VALID }, { logins += 1; true })
        assertEquals(StartupSessionAction.AUTO_LOGIN, blank.run("", AutoLoginPolicy.ENABLED, "u", "p"))
        assertEquals(0, probes)
        assertEquals(0, logins)

        val unknown = StartupRecoveryOrchestrator({ probes += 1; SessionHealth.UNKNOWN }, { logins += 1; true })
        assertEquals(StartupSessionAction.SHOW_UNKNOWN, unknown.run("cookie", AutoLoginPolicy.ENABLED, "u", "p"))
        assertEquals(1, probes)
        assertEquals(0, logins)
    }

    @Test
    fun `orchestrator allows exactly one auto login then blocks failures`() {
        var logins = 0
        val flow = StartupRecoveryOrchestrator({ SessionHealth.EXPIRED }, { logins += 1; false })
        assertEquals(StartupSessionAction.AUTO_LOGIN, flow.run("cookie", AutoLoginPolicy.ENABLED, "u", "p"))
        assertTrue(flow.autoLoginFailed)
        assertEquals(1, logins)
        assertEquals(StartupSessionAction.STAY_LOGGED_OUT, flow.run("cookie", AutoLoginPolicy.BLOCKED, "u", "p"))
        assertEquals(1, logins)
    }

    @Test
    fun `stale completions never persist`() {
        val flow = StartupRecoveryOrchestrator({ SessionHealth.EXPIRED }, { true })
        assertFalse(flow.acceptCompletion(tokenAccepted = false, ownerActive = true, credentialsMatch = true))
        assertFalse(flow.acceptCompletion(tokenAccepted = true, ownerActive = false, credentialsMatch = true))
        assertFalse(flow.acceptCompletion(tokenAccepted = true, ownerActive = true, credentialsMatch = false))
        assertTrue(flow.acceptCompletion(tokenAccepted = true, ownerActive = true, credentialsMatch = true))
    }
}
