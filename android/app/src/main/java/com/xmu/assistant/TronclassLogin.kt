package com.xmu.assistant

import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class TronclassLogin(
    private val transport: LoginHttpTransport = OkHttpLoginTransport(),
    private val base64Encoder: (ByteArray) -> String = {
        Base64.encodeToString(it, Base64.NO_WRAP)
    },
) {
    private val jar = SimpleCookieJar()
    private val random = SecureRandom()

    fun login(username: String, password: String): LoginResult {
        require(username.isNotBlank() && password.isNotBlank()) { "请输入学号和密码" }

        val authUrl = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/auth"
        val tokenUrl = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/token"
        val loginUrl = "https://lnt.xmu.edu.cn/api/login?login=access_token"
        val redirectUri = "https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true"
        val authParams = form(
            "scope" to "openid",
            "response_type" to "code",
            "client_id" to "TronClassH5",
            "redirect_uri" to redirectUri,
        )

        val first = request("$authUrl?$authParams").requireNotServerError("登录跳转")
        val second = request(resolve(first.location ?: error("登录跳转失败"), authUrl)).requireNotServerError("登录跳转")
        val loginPage = request(resolve(second.location ?: error("登录页面跳转失败"), authUrl)).requireNotServerError("登录页面")
        val salt = Regex("""id="pwdEncryptSalt"\s+value="([^"]+)"""").find(loginPage.body)?.groupValues?.get(1)
            ?: error("无法读取登录加密参数")
        val execution = Regex("""name="execution"\s+value="([^"]+)"""").find(loginPage.body)?.groupValues?.get(1)
            ?: error("无法读取登录流水号")

        val encrypted = encryptPassword(password, salt)
        val loginForm = form(
            "username" to username,
            "password" to encrypted,
            "captcha" to "",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to "",
            "execution" to execution,
        )
        // 错误分类：服务端 5xx/坏页 ≠ 密码错误——避免把服务繁忙误报成"账号或密码可能不正确"，
        // 误导用户反复重试登录（额外风控暴露）。
        val afterPassword = request(loginPage.url, method = "POST", body = loginForm).requireNotServerError("密码提交")
        val afterIdentity = request(resolve(afterPassword.location ?: error("账号或密码可能不正确"), loginPage.url))
            .requireNotServerError("身份确认")
        val callback = resolve(afterIdentity.location ?: error("登录回调失败"), loginPage.url)
        val code = Regex("""[?&]code=([^&]+)""").find(callback)?.groupValues?.get(1)
            ?: error("登录回调没有返回 code")

        val tokenBody = form(
            "client_id" to "TronClassH5",
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "scope" to "openid",
        )
        val tokenResponse = request(tokenUrl, method = "POST", body = tokenBody).requireNotServerError("令牌获取")
        val token = JSONObject(tokenResponse.body)
            .optString("access_token")
            .takeIf { it.isNotBlank() }
            ?: error("登录令牌获取失败")

        val finalLogin = request(
            loginUrl,
            method = "POST",
            contentType = "application/json; charset=utf-8",
            body = JSONObject().put("access_token", token).put("org_id", 1).toString(),
        ).requireNotServerError("TronClass 登录")
        if (finalLogin.code !in 200..299) error("TronClass 登录失败：${finalLogin.code}")
        return LoginResult(cookieHeader = jar.header())
    }

    /** 服务端 5xx 显式归为"服务繁忙"（与密码错误/网络失败区分，避免误导重试）。 */
    private fun LoginHttpResponse.requireNotServerError(step: String): LoginHttpResponse {
        if (code in 500..599) error("教务服务繁忙（$step），请稍后重试")
        return this
    }

    private fun encryptPassword(password: String, salt: String): String {
        val plaintext = randomString(64) + password
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(randomString(16).toByteArray(Charsets.UTF_8)),
        )
        return base64Encoder(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        return (0 until length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun request(
        url: String,
        method: String = "GET",
        contentType: String = "application/x-www-form-urlencoded; charset=utf-8",
        body: String = "",
    ): LoginHttpResponse {
        val response = transport.execute(
            LoginHttpRequest(
                url = url,
                method = method,
                contentType = contentType,
                body = body,
                cookieHeader = jar.header(),
            ),
        )
        jar.read(response.headers)
        return response
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun resolve(location: String, base: String): String = URL(URL(base), location).toString()

}

class SimpleCookieJar {
    private val cookies = linkedMapOf<String, String>()

    // @Synchronized：登录流程可能被并发调用（重登/恢复竞争），linkedMapOf 非线程安全，
    // read（写）与 header（读）并发会抛 ConcurrentModificationException
    // （对照 XmuScoreCookieJar 的 @Synchronized 同款防护）。
    @Synchronized
    fun read(headers: Map<String, List<String>>) {
        headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { raw ->
                val pair = raw.substringBefore(";")
                val name = pair.substringBefore("=", "")
                if (name.isNotBlank()) cookies[name] = pair
            }
    }

    @Synchronized
    fun header(): String = cookies.values.joinToString("; ")
}
