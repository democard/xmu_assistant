package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体检报告 P1-4：监控 seen 持久化只存 Cookie 的 SHA-256 指纹，
 * 不得把原始会话凭据写进普通 SharedPreferences。
 */
class CookieFingerprintTest {
    @Test
    fun `fingerprint is stable for the same header`() {
        val header = "sessionid=abc; csrftoken=xyz"
        assertEquals(cookieFingerprint(header), cookieFingerprint(header))
    }

    @Test
    fun `different headers produce different fingerprints`() {
        assertNotEquals(cookieFingerprint("sessionid=aaa"), cookieFingerprint("sessionid=bbb"))
    }

    @Test
    fun `blank header maps to blank fingerprint`() {
        assertEquals("", cookieFingerprint(""))
        assertEquals("", cookieFingerprint("   "))
    }

    @Test
    fun `fingerprint is lowercase hex of sha-256 length`() {
        val fingerprint = cookieFingerprint("sessionid=abc")
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        // 与已知 SHA-256 参考值对齐，防止算法/编码被误改
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("sessionid=abc".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        assertEquals(expected, fingerprint)
    }
}
