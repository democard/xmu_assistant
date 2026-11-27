package com.xmu.assistant

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TronclassWebViewPageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `target url opens specific course when courseId exists and falls back to home`() {
        assertEquals("https://lnt.xmu.edu.cn/course/12345/content#/", tronclassRollcallTargetUrl("12345"))
        assertEquals("https://lnt.xmu.edu.cn/course/12345/content#/", tronclassRollcallTargetUrl(" 12345 "))
        assertEquals("https://lnt.xmu.edu.cn/user/index#/", tronclassRollcallTargetUrl(""))
        assertEquals("https://lnt.xmu.edu.cn/user/index#/", tronclassRollcallTargetUrl("   "))
        assertEquals("https://lnt.xmu.edu.cn/user/index", tronclassHttpProbeUrl("https://lnt.xmu.edu.cn/user/index#/"))
    }

    @Test
    fun `cookie parser preserves valid name value pairs and rejects malformed parts`() {
        assertEquals(
            listOf("session=abc123", "route=xyz"),
            extractTronclassCookiePairs("session=abc123; ; =bad; route=xyz"),
        )
        assertEquals(emptyList<String>(), extractTronclassCookiePairs(""))
    }

    @Test
    fun `cookie jar exports sso cookies grouped by origin for webview injection`() {
        val jar = SimpleCookieJar()
        jar.read(mapOf("Set-Cookie" to listOf("KEYCLOAK_IDENTITY=id1; Path=/auth/realms/xmu/; Secure; HttpOnly")), "https://c-identity.xmu.edu.cn/auth/realms/xmu/")
        jar.read(mapOf("Set-Cookie" to listOf("session=lnt1; Path=/; Secure; HttpOnly")), "https://lnt.xmu.edu.cn/api/login")
        val byOrigin = jar.allCookiesByOrigin()
        assertEquals(listOf("KEYCLOAK_IDENTITY=id1; Path=/auth/realms/xmu/; Secure; HttpOnly"), byOrigin["https://c-identity.xmu.edu.cn"])
        assertEquals(listOf("session=lnt1; Path=/; Secure; HttpOnly"), byOrigin["https://lnt.xmu.edu.cn"])
    }

    @Test
    fun `web session cache isolates cookies per user and clears cleanly`() {
        TronclassWebSessionCache.clear()
        TronclassWebSessionCache.update("userA", mapOf("https://lnt.xmu.edu.cn" to listOf("session=a")))
        assertEquals(mapOf("https://lnt.xmu.edu.cn" to listOf("session=a")), TronclassWebSessionCache.get("userA"))
        assertTrue(TronclassWebSessionCache.get("userB").isEmpty())
        TronclassWebSessionCache.clear()
        assertTrue(TronclassWebSessionCache.get("userA").isEmpty())
    }

    @Test
    fun `allowed host check restricts webview navigation and sanitizes webview user agent`() {
        assertTrue(isAllowedTronclassHost("lnt.xmu.edu.cn"))
        assertTrue(isAllowedTronclassHost("c-mobile.xmu.edu.cn"))
        assertTrue(isAllowedTronclassHost("c-identity.xmu.edu.cn"))
        assertFalse(isAllowedTronclassHost("lnt.xmu.edu.cn.evil.com"))
        assertFalse(isAllowedTronclassHost("example.com"))
        assertFalse(isAllowedTronclassHost(null))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) Chrome/120.0.0.0 Mobile Safari/537.36",
            sanitizeWebViewUserAgent("Mozilla/5.0 (Linux; Android 14; wv) Chrome/120.0.0.0 Mobile Safari/537.36"),
        )
    }

    @Test
    fun `logged out webview page renders login prompt without initializing webview`() {
        composeRule.setContent {
            XmuMobileTheme {
                TronclassRollcallPage(
                    loggedIn = false,
                    cookieHeader = "",
                    targetEvent = null,
                    onBack = {},
                    onOpenStatus = {},
                )
            }
        }
        composeRule.onNodeWithText("请先登录").assertExists()
    }
}
