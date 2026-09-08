package com.xmu.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RankTest {
    @Test fun `rank parsing requires consistent positive rank and total`() {
        assertEquals(RankNumbers(12, 120), rankNumbersFromText("The total number of students in the major is 120,\nwith a GPA rank of 12."))
        assertNull(rankNumbersFromText("The total number of students in the major is 120, with a GPA rank of 0."))
        assertNull(rankNumbersFromText("The total number of students in the major is 120, with a GPA rank of 121."))
        assertNull(rankNumbersFromText("GPA 3.9; major rank *"))
        assertNull(rankNumbersFromText("total number of students in the major is 120, with a GPA rank of 12. total number of students in the major is 120, with a GPA rank of 13."))
    }

    @Test fun `fingerprint detects mark and credit corrections without counting change`() {
        val a = XmuScoreRecord("a", "A", "term", "20261", 2.0, score = 80.0)
        val b = a.copy(courseCode = "b")
        assertEquals(rankScoreFingerprint(listOf(a, b)), rankScoreFingerprint(listOf(b, a)))
        assertNotEquals(rankScoreFingerprint(listOf(a)), rankScoreFingerprint(listOf(a.copy(score = 90.0))))
        assertNotEquals(rankScoreFingerprint(listOf(a)), rankScoreFingerprint(listOf(a.copy(credit = 3.0))))
    }

    @Test fun `new application cannot be confused with expired historical record`() {
        val p = RankPending(setOf("old"), RankRange("r", "全部"), 1, "fp")
        assertNull(identifyRankRecord(listOf(RankRecord("old", 120)), p))
        assertEquals("new", identifyRankRecord(listOf(RankRecord("old", 120), RankRecord("new", 120)), p)?.id)
        assertThrows(IllegalStateException::class.java) { identifyRankRecord(listOf(RankRecord("new", 120), RankRecord("other", 120)), p) }
        assertNull(identifyRankRecord(listOf(RankRecord("other", 120)), p.copy(recordId = "new")))
    }

    @Test fun `owner isolation and pending submission survive cache roundtrip`() {
        val cache = RankCache("owner", RankResult("r", 12, 120, "全部", 1, 2, "", "fp", "pdf"),
            RankPending(setOf("r"), RankRange("scope", "全部"), 3, "fp"))
        assertEquals(cache, rankCacheFromJson(rankCacheToJson(cache), "owner"))
        assertEquals(RankCache("other"), rankCacheFromJson(rankCacheToJson(cache), "other"))
        val cleared = cache.copy(result = cache.result!!.copy(pdfBase64 = ""))
        assertEquals(12, rankCacheFromJson(rankCacheToJson(cleared), "owner").result?.position)
        assertEquals("", rankCacheFromJson(rankCacheToJson(cleared), "owner").result?.pdfBase64)
    }

    private fun reply(body: String, code: Int = 200) = RankResponse(code, emptyMap(), body.toByteArray())

    @Test fun `read auth failure renews once while network and server errors never renew`() {
        var calls = 0; var renewals = 0
        val client = XmuRankClient("s=old", { renewals++; "s=new" }, { true }, RankTransport { _, _, cookie ->
            calls++
            if (calls == 1) reply("", 401) else {
                assertTrue(cookie.contains("s=new")); reply("""{"datas":{"getJdjssq":[]}}""")
            }
        })
        assertTrue(client.records().isEmpty()); assertEquals(1, renewals)
        for (code in listOf(500, 429)) {
            val failing = XmuRankClient("s=old", { fail("must not relogin"); "" }, { true }, RankTransport { _, _, _ -> reply("bad", code) })
            assertThrows(IllegalStateException::class.java) { failing.records() }
        }
    }

    @Test fun `lost submit response is never replayed or relogged`() {
        var posts = 0
        val client = XmuRankClient("s=old", { fail("submit must not renew"); "" }, { true }, RankTransport { path, form, _ ->
            assertTrue(path.endsWith("addJdjssq.do")); assertEquals(mapOf("CJFWWID" to "scope"), form)
            posts++; throw IOException("lost response")
        })
        assertThrows(RankSubmitUncertain::class.java) { client.submit("scope") }
        assertEquals(1, posts)
    }

    @Test fun `records ranges and PDF are parsed conservatively`() {
        val client = XmuRankClient("s=old", { "" }, { true }, RankTransport { path, _, _ ->
            when {
                path.endsWith("getJdjssq.do") -> reply("""{"datas":{"getJdjssq":[{"ZX":{"WID":"new","CYJSZYRS":120}}]}}""")
                path.endsWith("cxxskxcjfw.do") -> reply("""{"datas":{"cxxskxcjfw":{"rows":[{"WID":"range","XSMC":"全部成绩"}]}}}""")
                else -> reply("<html>not a certificate</html>")
            }
        })
        assertEquals("new", client.records().single().id)
        assertEquals(RankRange("range", "全部成绩"), client.ranges().single())
        assertThrows(IllegalStateException::class.java) { client.certificate("new") }
    }

    @Test fun `account invalidation prevents outbound request`() {
        val client = XmuRankClient("", { fail("no renewal"); "" }, { false }, RankTransport { _, _, _ -> fail("no network"); reply("") })
        assertThrows(IllegalStateException::class.java) { client.records() }
    }
}
