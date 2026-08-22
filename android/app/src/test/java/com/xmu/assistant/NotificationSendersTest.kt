package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationSendersTest {
    @Test
    fun `smtp properties bound connect read and write waits`() {
        val properties = smtpProperties(port = 465)

        assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.timeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.enable"))
        assertEquals("465", properties.getProperty("mail.smtp.port"))
    }

    @Test
    fun `submission port keeps the same bounded waits with starttls`() {
        val properties = smtpProperties(port = 587)

        assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.timeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"))
        assertEquals("587", properties.getProperty("mail.smtp.port"))
    }
}
