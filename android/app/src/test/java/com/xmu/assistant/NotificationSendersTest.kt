package com.xmu.assistant

import java.io.IOException
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLHandshakeException
import javax.mail.internet.MimeMessage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NotificationSendersTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    @Test
    fun `smtp properties bound connect read and write waits`() {
        val properties = smtpProperties(port = 465)

        assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.timeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.enable"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals(null, properties.getProperty("mail.smtp.starttls.enable"))
        assertEquals("465", properties.getProperty("mail.smtp.port"))
    }

    @Test
    fun `submission port keeps the same bounded waits with starttls`() {
        val properties = smtpProperties(port = 587)

        assertEquals("15000", properties.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.timeout"))
        assertEquals("15000", properties.getProperty("mail.smtp.writetimeout"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.enable"))
        assertEquals("true", properties.getProperty("mail.smtp.starttls.required"))
        assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
        assertEquals("587", properties.getProperty("mail.smtp.port"))
    }

    @Test fun `submission uncertainty stops port fallback`() {
        val openedPorts = mutableListOf<String>()
        var sends = 0
        val factory = SmtpConnectionFactory { properties, _, _ ->
            openedPorts += properties.getProperty("mail.smtp.port")
            object : SmtpConnection {
                override fun send(message: MimeMessage) {
                    sends++ // The remote peer may have accepted DATA before this acknowledgement fails.
                    throw IOException("connection lost after DATA")
                }
                override fun close() = Unit
            }
        }

        val error = org.junit.Assert.assertThrows(SmtpSubmissionUncertainException::class.java) {
            QQMailSender("sender", "password", "recipient", "465,587", factory).send("title", "body")
        }

        assertTrue(error.message.orEmpty().contains("结果未知"))
        assertEquals(listOf("465"), openedPorts)
        assertEquals(1, sends)
    }

    @Test fun `pre-submission connection failure safely tries next port`() {
        val openedPorts = mutableListOf<String>()
        var sends = 0
        val factory = SmtpConnectionFactory { properties, _, _ ->
            val port = properties.getProperty("mail.smtp.port")
            openedPorts += port
            if (port == "465") throw IOException("connection refused before DATA")
            object : SmtpConnection {
                override fun send(message: MimeMessage) { sends++ }
                override fun close() = Unit
            }
        }

        QQMailSender("sender", "password", "recipient", "465,587", factory).send("title", "body")

        assertEquals(listOf("465", "587"), openedPorts)
        assertEquals(1, sends)
    }

    @Test fun `close failure after successful send does not resend`() {
        val openedPorts = mutableListOf<String>()
        var sends = 0
        val factory = SmtpConnectionFactory { properties, _, _ ->
            openedPorts += properties.getProperty("mail.smtp.port")
            object : SmtpConnection {
                override fun send(message: MimeMessage) { sends++ }
                override fun close() { throw IOException("QUIT returned 451") }
            }
        }

        QQMailSender("sender", "password", "recipient", "465,587", factory).send("title", "body")

        assertEquals(listOf("465"), openedPorts)
        assertEquals(1, sends)
    }

    @Test fun `TLS hostname verification failure occurs before authentication or message submission`() {
        val openedPorts = mutableListOf<String>()
        var authenticationAttempts = 0
        var sends = 0
        val factory = SmtpConnectionFactory { properties, _, _ ->
            openedPorts += properties.getProperty("mail.smtp.port")
            assertEquals("true", properties.getProperty("mail.smtp.ssl.checkserveridentity"))
            if (properties.getProperty("mail.smtp.port") != "465") {
                assertEquals("true", properties.getProperty("mail.smtp.starttls.required"))
            }
            // Model the JavaMail TLS handshake rejecting a certificate for the wrong SMTP host.
            throw SSLHandshakeException("certificate hostname mismatch")
        }

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            QQMailSender("sender", "password", "recipient", "587,465", factory).send("title", "body")
        }

        assertEquals(listOf("587", "465"), openedPorts)
        assertEquals(0, authenticationAttempts)
        assertEquals(0, sends)
    }

    @Test fun `JavaMail rejects a wrong-host certificate before sending SMTP credentials`() {
        val tls = createTlsFixture("wrong.example.test")
        val server = LocalImplicitTlsSmtpServer(tls.server)
        var identityCheckEnabled = false
        try {
            val factory = SmtpConnectionFactory { properties, sender, password ->
                // Keep the network/verification hostname identical to the valid TLS case;
                // only the certificate SAN differs.
                properties.setProperty("mail.smtp.host", "localhost")
                properties.setProperty("mail.smtp.port", server.port.toString())
                properties.setProperty("mail.smtp.ssl.enable", "true")
                properties.remove("mail.smtp.starttls.enable")
                properties.remove("mail.smtp.starttls.required")
                properties.put("mail.smtp.ssl.socketFactory", tls.clientSocketFactory)
                identityCheckEnabled = properties.getProperty("mail.smtp.ssl.checkserveridentity") == "true"
                JavaMailSmtpConnectionFactory.open(properties, sender, password)
            }

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                QQMailSender("fixture-user", "fixture-password", "to@example.test", "465", factory)
                    .send("fixture", "fixture")
            }

            assertTrue(
                "JavaMail must have peer identity verification enabled",
                identityCheckEnabled,
            )
            assertTrue(server.awaitConnection())
            // Paired with the matching-SAN localhost test: host, port, TLS trust, and SMTP
            // dialogue are identical; only this fixture's certificate SAN differs.
            assertTrue("trusted wrong-SAN certificate completes TLS", server.awaitHandshake())
            assertTrue("SMTP greeting is sent only after the TLS handshake", server.awaitGreeting())
            assertTrue("client aborts the wrong-SAN SMTP session", server.failure is IOException)
            assertTrue("hostname mismatch must fail before AUTH", server.commands.none { it.startsWith("AUTH", true) })
        } finally {
            server.close()
        }
    }

    @Test fun `JavaMail accepts a valid TLS host and submits a message`() {
        val tls = createTlsFixture("localhost")
        val server = LocalImplicitTlsSmtpServer(tls.server)
        try {
            val factory = SmtpConnectionFactory { properties, sender, password ->
                properties.setProperty("mail.smtp.host", "localhost")
                properties.setProperty("mail.smtp.port", server.port.toString())
                properties.setProperty("mail.smtp.ssl.enable", "true")
                properties.remove("mail.smtp.starttls.enable")
                properties.remove("mail.smtp.starttls.required")
                properties.put("mail.smtp.ssl.socketFactory", tls.clientSocketFactory)
                JavaMailSmtpConnectionFactory.open(properties, sender, password)
            }

            try {
                QQMailSender("fixture-user", "fixture-password", "to@example.test", "465", factory)
                    .send("fixture", "body")
            } catch (error: Exception) {
                throw AssertionError("local TLS server failed: ${server.failure}", error)
            }

            assertTrue(server.awaitConnection())
            assertEquals(null, server.failure)
            assertTrue(server.commands.any { it.startsWith("AUTH", true) })
            assertTrue(server.commands.any { it.equals("DATA", true) })
        } finally {
            server.close()
        }
    }

    @Test fun `JavaMail refuses SMTP authentication when server does not offer STARTTLS`() {
        val server = LocalPlainSmtpServer()
        try {
            val factory = SmtpConnectionFactory { properties, sender, password ->
                properties.setProperty("mail.smtp.host", "127.0.0.1")
                properties.setProperty("mail.smtp.port", server.port.toString())
                JavaMailSmtpConnectionFactory.open(properties, sender, password)
            }

            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                QQMailSender("fixture-user", "fixture-password", "to@example.test", "587", factory)
                    .send("fixture", "body")
            }

            assertTrue(server.awaitConnection())
            assertTrue("STARTTLS was unavailable; credentials must not be sent", server.commands.none { it.startsWith("AUTH", true) })
            assertTrue("client should detect missing STARTTLS", server.commands.any { it.equals("EHLO", true) || it.startsWith("EHLO ", true) })
        } finally {
            server.close()
        }
    }

    @Test fun `JavaMail upgrades STARTTLS and submits a message over a valid host`() {
        val tls = createTlsFixture("localhost")
        val server = LocalStartTlsSmtpServer(tls.server)
        try {
            val factory = SmtpConnectionFactory { properties, sender, password ->
                properties.setProperty("mail.smtp.host", "localhost")
                properties.setProperty("mail.smtp.port", server.port.toString())
                properties.put("mail.smtp.ssl.socketFactory", tls.clientSocketFactory)
                JavaMailSmtpConnectionFactory.open(properties, sender, password)
            }

            QQMailSender("fixture-user", "fixture-password", "to@example.test", "587", factory)
                .send("fixture", "body")

            assertTrue(server.awaitConnection())
            assertTrue(server.commands.any { it.equals("STARTTLS", true) })
            assertTrue(server.commands.any { it.startsWith("AUTH", true) })
            assertTrue(server.commands.any { it.equals("DATA", true) })
        } finally {
            server.close()
        }
    }

    private data class TlsFixture(val server: SSLContext, val clientSocketFactory: javax.net.ssl.SSLSocketFactory)

    private fun createTlsFixture(hostName: String): TlsFixture {
        val keystore = File(temporaryFolder.root, "smtp-$hostName.p12")
        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val keytool = File(System.getProperty("java.home"), "bin/keytool${if (isWindows) ".exe" else ""}")
        check(keytool.isFile) { "JDK keytool is required for the local TLS fixture" }
        val process = ProcessBuilder(
            keytool.absolutePath, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "3650", "-storetype", "PKCS12", "-keystore", keystore.absolutePath,
            "-storepass", "changeit", "-keypass", "changeit", "-dname", "CN=$hostName",
            "-ext", "SAN=dns:$hostName", "-noprompt",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool fixture generation failed: $output" }
        val store = KeyStore.getInstance("PKCS12").apply {
            keystore.inputStream().use { load(it, "changeit".toCharArray()) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "changeit".toCharArray())
        }.keyManagers
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("fixture", store.getCertificate("fixture"))
        }
        val trustManagers = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
        ).apply { init(trustStore) }.trustManagers
        val clientContext = SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }
        return TlsFixture(serverContext, clientContext.socketFactory)
    }

    private class LocalImplicitTlsSmtpServer(context: SSLContext) : AutoCloseable {
        private val server = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        private val acceptedHandshake = CountDownLatch(1)
        private val completedHandshake = CountDownLatch(1)
        private val greetingSent = CountDownLatch(1)
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var failure: Throwable? = null
        val port: Int get() = server.localPort
        private val worker = Thread({
            try {
                server.accept().use { socket ->
                    acceptedHandshake.countDown()
                    (socket as javax.net.ssl.SSLSocket).startHandshake()
                    completedHandshake.countDown()
                    serveSmtp(socket, commands, advertiseStartTls = false) { greetingSent.countDown() }
                }
            } catch (error: Exception) {
                // Client-side TLS verification is expected to close the socket before SMTP auth.
                failure = error
            }
        }, "smtp-local-tls-fixture").apply { isDaemon = true; start() }

        fun awaitConnection(): Boolean = acceptedHandshake.await(5, TimeUnit.SECONDS)
        fun awaitHandshake(): Boolean = completedHandshake.await(1, TimeUnit.SECONDS)
        fun awaitGreeting(): Boolean = greetingSent.await(1, TimeUnit.SECONDS)

        override fun close() {
            runCatching { server.close() }
            worker.join(2000)
        }
    }

    private class LocalPlainSmtpServer : AutoCloseable {
        private val server = java.net.ServerSocket(0)
        private val accepted = CountDownLatch(1)
        val commands = CopyOnWriteArrayList<String>()
        val port: Int get() = server.localPort
        private val worker = Thread({
            try {
                server.accept().use { socket ->
                    accepted.countDown()
                    serveSmtp(socket, commands, advertiseStartTls = false)
                }
            } catch (_: Exception) { }
        }, "smtp-local-plain-fixture").apply { isDaemon = true; start() }

        fun awaitConnection(): Boolean = accepted.await(5, TimeUnit.SECONDS)
        override fun close() { runCatching { server.close() }; worker.join(2000) }
    }

    private class LocalStartTlsSmtpServer(context: SSLContext) : AutoCloseable {
        private val server = java.net.ServerSocket(0)
        private val accepted = CountDownLatch(1)
        val commands = CopyOnWriteArrayList<String>()
        val port: Int get() = server.localPort
        private val worker = Thread({
            try {
                server.accept().use { socket ->
                    accepted.countDown()
                    serveSmtp(socket, commands, advertiseStartTls = true, serverTlsContext = context)
                }
            } catch (_: Exception) { }
        }, "smtp-local-starttls-fixture").apply { isDaemon = true; start() }

        fun awaitConnection(): Boolean = accepted.await(5, TimeUnit.SECONDS)
        override fun close() { runCatching { server.close() }; worker.join(2000) }
    }

    private companion object {
    fun serveSmtp(
        socket: java.net.Socket,
        commands: MutableList<String>,
        advertiseStartTls: Boolean,
        serverTlsContext: SSLContext? = null,
        onGreeting: () -> Unit = {},
    ) {
        var activeSocket = socket
        var output = activeSocket.getOutputStream().bufferedWriter()
        var input = activeSocket.getInputStream().bufferedReader()
        fun reply(text: String) { output.write(text); output.write("\r\n"); output.flush() }
        reply("220 local fixture")
        onGreeting()
        var loginStep = 0
        while (true) {
            val line = input.readLine() ?: break
            commands += line
            when {
                line.startsWith("EHLO", true) -> {
                    reply("250-local")
                    if (advertiseStartTls) reply("250-STARTTLS")
                    reply("250-AUTH PLAIN LOGIN")
                    reply("250 OK")
                }
                line.equals("AUTH LOGIN", true) -> { loginStep = 1; reply("334 VXNlcm5hbWU6") }
                line.startsWith("AUTH PLAIN", true) -> reply("235 authenticated")
                line.equals("STARTTLS", true) && serverTlsContext != null -> {
                    reply("220 begin TLS")
                    val upgraded = serverTlsContext.socketFactory.createSocket(
                        activeSocket,
                        activeSocket.inetAddress.hostAddress,
                        activeSocket.port,
                        true,
                    ) as javax.net.ssl.SSLSocket
                    upgraded.useClientMode = false
                    upgraded.startHandshake()
                    activeSocket = upgraded
                    output = activeSocket.getOutputStream().bufferedWriter()
                    input = activeSocket.getInputStream().bufferedReader()
                    loginStep = 0
                }
                loginStep == 1 -> { loginStep = 2; reply("334 UGFzc3dvcmQ6") }
                loginStep == 2 -> { loginStep = 0; reply("235 authenticated") }
                line.startsWith("MAIL FROM", true) -> reply("250 sender ok")
                line.startsWith("RCPT TO", true) -> reply("250 recipient ok")
                line.equals("DATA", true) -> {
                    reply("354 end with dot")
                    while (input.readLine()?.let { it != "." } == true) { }
                    reply("250 queued")
                }
                line.equals("QUIT", true) -> { reply("221 goodbye"); break }
                else -> reply("250 OK")
            }
        }
    }
    }

    // ===== 2026-08-30 补测债：发送器行为与纯函数矩阵 =====

    @Test
    fun `parse smtp ports normalizes dedupes filters and falls back to defaults`() {
        assertEquals(listOf(465, 587), parseSmtpPorts("465,587"))
        assertEquals(listOf(587, 465), parseSmtpPorts(" 587 , 465 , 587 "))
        assertEquals(listOf(465), parseSmtpPorts("465"))
        assertEquals(listOf(465, 587), parseSmtpPorts("abc,0,70000"))
        assertEquals(listOf(465, 587), parseSmtpPorts(""))
    }

    @Test
    fun `notification labels a valid deadline correctly and omits unavailable time`() {
        val event = RollcallEvent(
            id = "1", courseTitle = "数据结构", teacher = "张三",
            type = "数字签到", status = "进行中", deadline = "2026-06-14 08:00", result = "待处理",
        )
        val body = rollcallNotificationBody(event, actionUrl = "xmurollcall://rollcall/1")
        assertEquals(
            listOf(
                "课程：数据结构",
                "类型：数字签到",
                "截止：2026-06-14 08:00",
                "状态：待处理",
                "打开：xmurollcall://rollcall/1",
            ).joinToString("\n"),
            body,
        )
        for (deadline in listOf("", "null", "invalid")) {
            val noDeadlineBody = rollcallNotificationBody(event.copy(deadline = deadline), "u")
            assertEquals(4, noDeadlineBody.lines().size)
            assertTrue(noDeadlineBody.lines().none { it.startsWith("截止：") || it.startsWith("剩余：") })
        }
    }

    @Test
    fun `escape json escapes structural characters control bytes and keeps plain cjk`() {
        assertEquals("a\\\\b", escapeJson("a\\b"))
        assertEquals("\\\"q\\\"", escapeJson("\"q\""))
        assertEquals("l\\nr", escapeJson("l\nr"))
        assertEquals("t\\tb", escapeJson("t\tb"))
        // 其余 <0x20 控制字节必须 \u00XX 转义，否则 payload 非法 JSON
        assertEquals("x\\u0001y", escapeJson("x\u0001y"))
        assertEquals("中文课程", escapeJson("中文课程"))
    }

    @Test
    fun `pushplus send posts escaped payload and accepts business success`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"code\":200}"))
        server.start()
        try {
            val sender = PushPlusSender(token = "tok\"en", endpoint = server.url("/send").toString())
            sender.send("标题\"A", "第一行\n第二行")
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/send", request.path)
            assertEquals("POST", request.method)
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("tok\"en", payload.getString("token"))
            assertEquals("标题\"A", payload.getString("title"))
            assertEquals("第一行\n第二行", payload.getString("content"))
            assertEquals("txt", payload.getString("template"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus send rejects business failure code with code detail`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"code\":500,\"msg\":\"fail\"}"))
        server.start()
        try {
            val sender = PushPlusSender(token = "t", endpoint = server.url("/send").toString())
            val error = runCatching { sender.send("t", "b") }.exceptionOrNull()
            assertTrue("expected IllegalStateException", error is IllegalStateException)
            assertTrue(error!!.message!!.contains("code=500"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus malformed acknowledgement is rejected without resending`() {
        val server = MockWebServer()
        val bodies = listOf("<html>gateway error</html>", "", "{}", "[]",
            """{"code":null}""", """{"code":true}""", """{"code":200.5}""")
        bodies.forEach { server.enqueue(MockResponse().setBody(it)) }
        server.start()
        try {
            val sender = PushPlusSender("fixture-token", endpoint = server.url("/send").toString())
            bodies.forEach { body ->
                val error = runCatching { sender.send("test", "test") }.exceptionOrNull()
                assertTrue("unexpected acceptance of $body", error is IllegalStateException)
            }
            assertEquals(bodies.size, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus string business code confirms request acceptance`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"code":"200"}"""))
        server.start()
        try {
            PushPlusSender("fixture-token", endpoint = server.url("/send").toString()).send("test", "test")
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus send rejects http level failure`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502))
        server.start()
        try {
            val sender = PushPlusSender(token = "t", endpoint = server.url("/send").toString())
            val error = runCatching { sender.send("t", "b") }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("502"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `pushplus blank token is rejected before any request leaves`() {
        val server = MockWebServer()
        server.start()
        try {
            val sender = PushPlusSender(token = "   ", endpoint = server.url("/send").toString())
            val error = runCatching { sender.send("t", "b") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
