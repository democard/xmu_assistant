package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSettingsSourceContractTest {
    @Test
    fun `session clear preserves credentials and removes only session cache keys`() {
        val source = settingsSource()
        val clearSessionBlock = source
            .substringAfter("fun clearSession()", missingDelimiterValue = "")
            .substringBefore("fun clearCredentialsAndSession()")

        assertTrue("clearSession was not found", clearSessionBlock.isNotBlank())
        listOf(
            "cookie_header",
            "score_cookie_header",
            "score_records_json",
            "score_updated_at_millis",
            "academic_cache_json",
            "schedule_cache_json",
            "monitor_desired",
            "monitor_last_check_millis",
            "monitor_consecutive_failures",
            "monitor_last_error",
        ).forEach { key ->
            assertTrue("clearSession must remove $key", "remove(\"$key\")" in clearSessionBlock)
        }
        assertTrue("clearSession must preserve username", "remove(\"username\")" !in clearSessionBlock)
        assertTrue("clearSession must preserve password", "remove(\"password\")" !in clearSessionBlock)
        assertTrue("clearSession must preserve the auto-login policy", "remove(\"auto_login_policy\")" !in clearSessionBlock)
        assertTrue("clearSession must not write the auto-login policy", "putString(\"auto_login_policy\"" !in clearSessionBlock)
    }

    @Test
    fun `settings persist and transition the auto-login policy without credential deletion`() {
        val source = settingsSource()

        assertTrue("policy must use the dedicated preference key", "\"auto_login_policy\"" in source)
        assertTrue("policy read must delegate to the side-effect-free policy store", "get() = autoLoginPolicyStore.policy" in source)
        assertTrue("successful login must enable automatic login", "fun markLoginSucceeded() { autoLoginPolicy = AutoLoginPolicy.ENABLED }" in source)
        assertTrue("failed automatic login must block retries", "fun markAutoLoginFailed() { autoLoginPolicy = AutoLoginPolicy.BLOCKED }" in source)
        assertTrue("explicit logout must persist user logout", "fun markUserLoggedOut() { autoLoginPolicy = AutoLoginPolicy.USER_LOGGED_OUT }" in source)
    }

    @Test
    fun `settings exposes no automatic credential deletion path`() {
        val source = settingsSource()
        assertTrue("session cleanup must never remove username", "remove(\"username\")" !in source)
        assertTrue("session cleanup must never remove password", "remove(\"password\")" !in source)
    }

    private fun settingsSource(): String {
        val relativePath = "src/main/java/com/xmu/assistant/AssistantSettings.kt"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) { "AssistantSettings.kt was not found from ${File(".").absolutePath}" }
            .readText()
    }
}
