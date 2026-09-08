package com.xmu.assistant

import okhttp3.Request
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

internal data class RankResponse(val code: Int, val headers: Map<String, List<String>>, val bytes: ByteArray)
internal fun interface RankTransport { fun execute(path: String, form: Map<String, String>?, cookie: String): RankResponse }

/** No transparent retries or redirects for the non-idempotent application POST. */
internal class RankOkHttpTransport : RankTransport {
    private val http = XmuHttpClients.query.newBuilder().retryOnConnectionFailure(false).build()
    override fun execute(path: String, form: Map<String, String>?, cookie: String): RankResponse {
        require(path.startsWith("/jwapp/sys/jdfpm/") && !path.contains(".."))
        val builder = Request.Builder().url("https://jw.xmu.edu.cn$path")
            .header("Cookie", cookie).header("Referer", "https://jw.xmu.edu.cn/jwapp/sys/jdfpm/*default/index.do")
            .tag(NetworkOperation::class.java, NetworkOperation.SCORES)
        if (form != null) builder.post(FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build())
        return http.newCall(builder.build()).execute().use { response ->
            val body = response.body ?: throw IOException("排名接口没有返回内容")
            // A GPA certificate is about 338KB. Bound both chunked and declared response bodies.
            require(body.contentLength() <= 5 * 1024 * 1024) { "证明文件过大，已停止读取" }
            val bytes = body.byteStream().use { it.readBytesLimited(5 * 1024 * 1024) }
            RankResponse(response.code, response.headers.toMultimap(), bytes)
        }
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        check(out.size() + n <= limit) { "证明文件过大，已停止读取" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal class RankAuthExpired : IllegalStateException("教务登录已过期，请重新获取")
internal class RankSubmitUncertain : IllegalStateException("申请可能已提交。请继续查询结果，勿重复申请。")
internal class RankSubmitRejected : IllegalStateException("教务未受理本次申请，请稍后重试")

internal class XmuRankClient(
    cookie: String,
    private val renewSession: (String) -> String,
    private val active: () -> Boolean,
    private val transport: RankTransport = RankOkHttpTransport(),
) {
    private val jar = XmuScoreCookieJar().apply { seed(cookie) }
    private var renewed = false
    fun cookieHeader(): String = jar.header()

    private fun request(path: String, form: Map<String, String>? = emptyMap(), submit: Boolean = false): RankResponse {
        check(active()) { "账号会话已改变，操作已停止" }
        val response = transport.execute(path, form, jar.header())
        // Shared academic cookie jar handles Set-Cookie in the same way as existing modules.
        jar.read(response.headers)
        val text = if (response.bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) "" else response.bytes.toString(Charsets.UTF_8)
        val query = QueryHttpResponse("https://jw.xmu.edu.cn$path", response.code,
            response.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull(), text, response.headers)
        if (JwSessionMarkers.isAuthFailure(response.code, query)) {
            if (!submit && !renewed && active()) {
                renewed = true
                jar.seed(renewSession(jar.header()))
                return request(path, form)
            }
            throw RankAuthExpired()
        }
        check(response.code in 200..299) { "教务接口暂不可用（HTTP ${response.code}）" }
        return response
    }

    fun records(): List<RankRecord> {
        val root = json(request("/jwapp/sys/jdfpm/api/jdjs/getJdjssq.do"))
        val data = root.optJSONObject("datas")?.opt("getJdjssq")
            ?: error("申请记录格式改变，已停止操作")
        val result = mutableListOf<RankRecord>()
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
                is JSONObject -> {
                    val id = value.optString("WID").takeUnless { it == "null" }.orEmpty()
                    if (id.isNotBlank()) result += RankRecord(id, value.optInt("CYJSZYRS", 0), value.optString("CJFWWID").takeUnless { it == "null" }.orEmpty(),
                        value.optString("JSSJ").ifBlank { value.optString("SQSJ") })
                    else value.keys().forEach { visit(value.opt(it)) }
                }
            }
        }
        visit(data)
        return result.distinctBy { it.id }
    }

    fun ranges(): List<RankRange> {
        val root = json(request("/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do"))
        val rows = root.optJSONObject("datas")?.optJSONObject("cxxskxcjfw")?.optJSONArray("rows")
            ?: root.optJSONArray("rows") ?: error("无法识别可选成绩范围，未提交申请")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull {
            val id = it.optString("WID"); val name = it.optString("XSMC")
            if (id.isBlank() || name.isBlank() || id == "null" || name == "null") null else RankRange(id, name)
        }.distinctBy { it.id }.also { check(it.isNotEmpty()) { "当前没有可申请的成绩范围" } }
    }

    fun submit(rangeId: String) {
        try {
            val root = json(request("/jwapp/sys/jdfpm/api/jdjs/addJdjssq.do", mapOf("CJFWWID" to rangeId), submit = true))
            if (root.has("success") && root.opt("success")?.toString() == "false") throw RankSubmitRejected()
            // Even an unfamiliar success response is reconciled by new WID, never blindly resubmitted.
        } catch (rejected: RankSubmitRejected) { throw rejected }
        catch (error: Exception) { throw RankSubmitUncertain() }
    }

    fun certificate(id: String): ByteArray {
        val encoded = URLEncoder.encode(id, "UTF-8")
        val bytes = request("/jwapp/sys/jdfpm/api/jdjs/printZm.do?wid=$encoded&type=0&DYNR=0&SFXYPM=1", null).bytes
        check(bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
            "教务尚未返回有效 PDF。可继续查询本次结果。"
        }
        return bytes
    }

    private fun json(response: RankResponse): JSONObject = try {
        JSONObject(response.bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
    } catch (e: Exception) { throw IllegalStateException("排名接口返回格式异常", e) }
}
