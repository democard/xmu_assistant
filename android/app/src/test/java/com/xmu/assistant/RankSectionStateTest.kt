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
