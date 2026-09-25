package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MailReleaseKeepRulesContractTest {
    @Test
    fun `release keeps JavaMail classes named by provider and mailcap resources`() {
        val rules = sequenceOf(
            File("proguard-rules.pro"),
            File("app/proguard-rules.pro"),
            File("android/app/proguard-rules.pro"),
        ).firstOrNull(File::isFile)?.readText()
        requireNotNull(rules) { "release ProGuard rules were not found from ${File(".").absolutePath}" }

        // Transport.send selects the smtp provider even when port 465 enables SSL.
        assertTrue(
            "the provider's reflective class name must survive R8",
            Regex("(?m)^-keep class com\\.sun\\.mail\\.smtp\\.SMTPTransport \\{ \\*; \\}$")
                .containsMatchIn(rules),
        )
        assertTrue(
            "mailcap's reflective content handlers must survive R8",
            Regex("(?m)^-keep class com\\.sun\\.mail\\.handlers\\.\\*\\* \\{ \\*; \\}$")
                .containsMatchIn(rules),
        )
    }
}
