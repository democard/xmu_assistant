package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RankSectionStateTest {
    @Test fun `certificate text layer yields GPA ranking`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val bytes = java.io.ByteArrayOutputStream()
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            document.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use {
                it.beginText()
                it.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f)
                it.newLineAtOffset(30f, 700f)
                it.showText("The total number of students in the major is 90, with a GPA rank of 8.")
                it.endText()
            }
            document.save(bytes)
        }
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes.toByteArray()).use {
            assertEquals(RankNumbers(8, 90), rankNumbersFromText(com.tom_roush.pdfbox.text.PDFTextStripper().getText(it)))
        }
    }
    private fun awaitDone(state: RankSectionState) {
        val end = System.currentTimeMillis() + 15000
        while (state.loading && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20)
        }
        assertFalse("operation must finish", state.loading)
    }

    private fun certificateFixture(total: Int, rank: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { document ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            document.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page).use {
                it.beginText()
                it.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f)
                it.newLineAtOffset(30f, 700f)
                it.showText("The total number of students in the major is $total, with a GPA rank of $rank.")
                it.endText()
            }
            document.save(bytes)
        }
        return bytes.toByteArray()
    }

    /** 已提交申请、但服务端始终不生成新记录（已有有效记录被合并）：必须采用本范围最近记录而不是无限等待。 */
    @Test fun `stalled new record adopts latest existing record instead of waiting forever`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var stored = ""
        var submissions = 0
        val epoch = SessionEpoch(); val owner = epoch.attachOwner()
        fun res(body: String) = RankResponse(200, emptyMap(), body.toByteArray())
        val existing = """{"datas":{"getJdjssq":[{"ZX":{"WID":"old","CYJSZYRS":"152","CJFWWID":"scope","JSSJ":"2026-09-18"}}]}}"""
        val state = RankSectionState(context, RequestGate(), epoch, owner, scope,
            { true }, { "session=test" }, { "u1" }, { "p" }, { false }, { "session=test" }, {},
            { stored }, { json, active -> if (active()) { stored = json; true } else false }, {}, emptyList(),
            clientFactory = { cookie, renew, active -> XmuRankClient(cookie, renew, active, RankTransport { path, _, _ ->
                when {
                    "cxxskxcjfw" in path -> res("""{"datas":{"cxxskxcjfw":{"rows":[{"WID":"scope","XSMC":"全部"}]}}}""")
                    "addJdjssq" in path -> { submissions++; res("""{"code":"0"}""") }
                    "printZm" in path -> RankResponse(200, emptyMap(), certificateFixture(152, 84))
                    else -> res(existing)
                }
            }) },
            pollDelays = emptyList(), fallbackPollDelays = emptyList(),
        )
        try {
            state.fetch(); awaitDone(state)
            assertEquals(1, submissions)
            assertEquals("old", state.cache.result?.recordId)
            assertEquals(84, state.cache.result?.position)
            assertEquals(152, state.cache.result?.participants)
            assertNull(state.cache.pending)
            assertTrue(state.error.contains("未生成新记录"))
        } finally { scope.cancel() }
    }

    /** 服务端正常生成新记录时走原路径：结果归因新记录，且不出现兜底提示。 */
    @Test fun `completed new record is used without adoption note`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var stored = ""
        var submissions = 0
        val epoch = SessionEpoch(); val owner = epoch.attachOwner()
        fun res(body: String) = RankResponse(200, emptyMap(), body.toByteArray())
        val old = """{"ZX":{"WID":"old","CYJSZYRS":"152","CJFWWID":"scope","JSSJ":"2026-09-18"}}"""
        val fresh = """{"ZX":{"WID":"new","CYJSZYRS":"90","CJFWWID":"scope","JSSJ":"2026-09-19"}}"""
        val state = RankSectionState(context, RequestGate(), epoch, owner, scope,
            { true }, { "session=test" }, { "u1" }, { "p" }, { false }, { "session=test" }, {},
            { stored }, { json, active -> if (active()) { stored = json; true } else false }, {}, emptyList(),
            clientFactory = { cookie, renew, active -> XmuRankClient(cookie, renew, active, RankTransport { path, _, _ ->
                when {
                    "cxxskxcjfw" in path -> res("""{"datas":{"cxxskxcjfw":{"rows":[{"WID":"scope","XSMC":"全部"}]}}}""")
                    "addJdjssq" in path -> { submissions++; res("""{"code":"0"}""") }
                    "printZm" in path -> RankResponse(200, emptyMap(), certificateFixture(90, 8))
                    else -> res(if (submissions == 0) """{"datas":{"getJdjssq":[$old]}}""" else """{"datas":{"getJdjssq":[$old,$fresh]}}""")
                }
            }) },
        )
        try {
            state.fetch(); awaitDone(state)
            assertEquals(1, submissions)
            assertEquals("new", state.cache.result?.recordId)
            assertEquals(8, state.cache.result?.position)
            assertNull(state.cache.pending)
            assertEquals("", state.error)
        } finally { scope.cancel() }
    }

    @Test fun `uncertain submission persists before POST and restart resumes without submitting`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var stored = ""
        var submissions = 0
        var reads = 0
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        fun create() = RankSectionState(context, RequestGate(), epoch, owner, scope,
            { true }, { "session=test" }, { "u1" }, { "p" }, { false }, { "session=test" }, {},
            { stored }, { json, active -> if (active()) { stored = json; true } else false }, {}, emptyList(),
            clientFactory = { cookie, renew, active -> XmuRankClient(cookie, renew, active, RankTransport { path, _, _ ->
                val body = when {
                    "cxxskxcjfw" in path -> """{"datas":{"cxxskxcjfw":{"rows":[{"WID":"scope","XSMC":"全部"}]}}}"""
                    "addJdjssq" in path -> {
                        submissions++
                        assertNotNull(rankCacheFromJson(stored, rankDigest("u1")).pending)
                        throw java.io.IOException("lost response")
                    }
                    else -> { reads++; if (submissions > 0) throw java.io.IOException("offline")
                        """{"datas":{"getJdjssq":[]}}""" }
                }
                RankResponse(200, emptyMap(), body.toByteArray())
            }) })
        try {
            val first = create()
            assertEquals(0, reads)
            first.fetch(); first.fetch(); awaitDone(first)
            assertEquals(1, submissions)
            assertNotNull(first.cache.pending)
            val resumed = create()
            resumed.fetch(); awaitDone(resumed)
            assertEquals(1, submissions)
            assertNotNull(resumed.cache.pending)
            assertTrue(reads >= 2)
        } finally { scope.cancel() }
    }

    @Test fun `PDF cleanup keeps ranking dates and survives recreation`() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val epoch = SessionEpoch(); val owner = epoch.attachOwner()
        val result = RankResult("r1", 8, 90, "全部", 100, 200, "date", rankScoreFingerprint(emptyList()), "cGRm")
        var stored = rankCacheToJson(RankCache(rankDigest("u1"), result))
        val state = RankSectionState(context, RequestGate(), epoch, owner, scope,
            { true }, { "session=test" }, { "u1" }, { "p" }, { false }, { "" }, {},
            { stored }, { json, active -> if (active()) { stored = json; true } else false }, {}, emptyList())
        try {
            state.clearPdf(); awaitDone(state)
            assertEquals(result.copy(pdfBase64 = ""), state.cache.result)
            assertEquals(state.cache, rankCacheFromJson(stored, rankDigest("u1")))
            assertFalse(state.stale)
        } finally { scope.cancel() }
    }
}
