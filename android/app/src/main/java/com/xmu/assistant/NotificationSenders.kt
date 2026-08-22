package com.xmu.assistant

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

fun rollcallNotificationBody(event: RollcallEvent, actionUrl: String): String {
    val remaining = if (event.deadline.isBlank()) "未知" else event.deadline
    return listOf(
        "课程：${event.courseTitle}",
        "类型：${event.type}",
        "剩余：$remaining",
        "状态：${event.result}",
        "打开：$actionUrl",
    ).joinToString("\n")
}

class PushPlusSender(private val token: String) {
    fun send(title: String, body: String) {
        require(token.isNotBlank()) { "PushPlus token is empty" }
        val payload = """{"token":"${escapeJson(token)}","title":"${escapeJson(title)}","content":"${escapeJson(body)}","template":"txt"}"""
        val conn = URL("https://www.pushplus.plus/send").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            if (conn.responseCode !in 200..299) error("PushPlus failed: ${conn.responseCode}")
            // 流读取后关闭、连接断开：避免 keep-alive 连接无法归还池（句柄累积）
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // 用 JSON 解析判断业务码：子串匹配对 "code": 200（带空格）等格式变体会误判失败
            val code = runCatching { org.json.JSONObject(response).optInt("code", Int.MIN_VALUE) }
                .getOrDefault(Int.MIN_VALUE)
            if (code != Int.MIN_VALUE && code != 200) error("PushPlus 发送失败（code=$code）")
        } finally {
            conn.disconnect()
        }
    }
}

class QQMailSender(
    private val sender: String,
    private val password: String,
    private val recipient: String,
    private val ports: String = "465,587",
) {
    fun send(title: String, body: String) {
        require(sender.isNotBlank() && password.isNotBlank() && recipient.isNotBlank()) {
            "QQ mail settings are incomplete"
        }
        val errors = mutableListOf<String>()
        for (port in parseSmtpPorts(ports)) {
            runCatching { sendWithPort(port, title, body) }
                .onSuccess { return }
                .onFailure { errors += "$port: ${it.message}" }
        }
        error(errors.joinToString("；"))
    }

    private fun sendWithPort(port: Int, title: String, body: String) {
        val props = smtpProperties(port)
        val session = Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(sender, password)
            }
        })
        val message = MimeMessage(session).apply {
            // 显式 UTF-8：Session 级 mail.mime.charset 统一约束 From/Subject/正文的
            // 缺省 charset（部分 ROM/JVM 实现缺省非 UTF-8 时中文会乱码）
            setFrom(sender)
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient))
            setSubject(title, "UTF-8")
            setText(body, "UTF-8")
        }
        Transport.send(message)
    }
}

fun smtpProperties(port: Int): Properties = Properties().apply {
    put("mail.smtp.auth", "true")
    put("mail.mime.charset", "UTF-8")
    put("mail.smtp.host", "smtp.qq.com")
    put("mail.smtp.port", port.toString())
    put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MILLIS.toString())
    put("mail.smtp.timeout", SMTP_TIMEOUT_MILLIS.toString())
    put("mail.smtp.writetimeout", SMTP_TIMEOUT_MILLIS.toString())
    if (port == 465) {
        put("mail.smtp.ssl.enable", "true")
    } else {
        put("mail.smtp.starttls.enable", "true")
    }
}

private const val SMTP_TIMEOUT_MILLIS = 15_000

fun parseSmtpPorts(value: String): List<Int> {
    val ports = value.split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..65535 }
        .distinct()
    return ports.ifEmpty { listOf(465, 587) }
}

fun escapeJson(value: String): String = buildString {
    value.forEach { ch ->
        when {
            ch == '\\' -> append("\\\\")
            ch == '"' -> append("\\\"")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch == '\b' -> append("\\b")
            ch == '\u000C' -> append("\\f")
            // 其余 <0x20 控制字符必须 \u00XX 转义：课程名含此类字符时 payload 是非法 JSON，
            // PushPlus 会直接拒绝导致发送失败
            ch < '\u0020' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
}
