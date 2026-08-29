package com.xmu.assistant

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 候选端点记忆（与桌面端 utils.remember_good_endpoint/ordered_endpoints 对齐）：
 * 上次成功的端点下次首选，省掉 1~5 个必败探测往返。进程级缓存即可。
 */
internal object EndpointMemory {
    private val lastGood = mutableMapOf<String, String>()

    fun ordered(purpose: String, candidates: List<String>): List<String> =
        synchronized(lastGood) {
            val good = lastGood[purpose]
            when {
                good == null || !candidates.contains(good) -> candidates
                else -> listOf(good) + candidates.filterNot { it == good }
            }
        }

    fun remember(purpose: String, endpoint: String) =
        synchronized(lastGood) { lastGood[purpose] = endpoint }

    fun reset() = synchronized(lastGood) { lastGood.clear() }
}

/**
 * 直链课件扩展名全集（与桌面端 courseware.DIRECT_URL_EXTENSIONS 16 种对齐）：
 * 下载分流（isDirectDownloadUrl）与页内「可直下」计数（isDirectCoursewareUrl）
 * 共用同一来源，防清单漂移——此前 .7z/.mov/.m4v/.mp3/.m4a 只在下载侧生效、
 * 计数侧算「入口」，汇总行与实际下载行为不一致。
 */
val DIRECT_COURSEWARE_EXTENSIONS: List<String> = listOf(
    ".pdf", ".ppt", ".pptx", ".doc", ".docx", ".xls", ".xlsx", ".zip",
    ".rar", ".7z", ".mp4", ".mov", ".m4v", ".mp3", ".m4a", ".m3u8",
)

class CoursewareClient private constructor(
    private val context: Context?,
    private val cookieHeader: String,
    private val queryTransport: QueryHttpTransport,
    private val executorFactory: (Int) -> ExecutorService,
    private val fileDownloadTransport: FileDownloadTransport,
    private val downloadDirectory: File?,
) {
    private val baseUrl = "https://lnt.xmu.edu.cn"

    constructor(context: Context, cookieHeader: String) : this(
        // applicationContext：client 会贯穿整个下载期（后台线程持引用），
        // 持 Activity context 时转屏/关闭页面即泄漏整个 Activity 视图树
        context = context.applicationContext,
        cookieHeader = cookieHeader,
        queryTransport = OkHttpQueryTransport(),
        executorFactory = { size -> Executors.newFixedThreadPool(size) },
        fileDownloadTransport = OkHttpFileDownloadTransport(),
        downloadDirectory = null,
    )

    internal constructor(
        cookieHeader: String,
        queryTransport: QueryHttpTransport,
        executorFactory: (Int) -> ExecutorService,
        fileDownloadTransport: FileDownloadTransport = OkHttpFileDownloadTransport(),
        downloadDirectory: File? = null,
    ) : this(
        context = null,
        cookieHeader = cookieHeader,
        queryTransport = queryTransport,
        executorFactory = executorFactory,
        fileDownloadTransport = fileDownloadTransport,
        downloadDirectory = downloadDirectory,
    )

    fun fetchCourses(): List<CourseSummary> {
        val endpoints = listOf(
            "/api/my-courses?per_page=1000",
            "/api/courses?role=student&per_page=1000",
            "/api/courses?course_role=student&per_page=1000",
            "/api/my/courses?per_page=1000",
            "/api/courses?per_page=1000",
            "/api/courses",
        )
        val errors = mutableListOf<String>()
        // 端点记忆：上次成功的端点本次优先尝试（与桌面端对齐）
        for (endpoint in EndpointMemory.ordered(ENDPOINT_PURPOSE_COURSES, endpoints)) {
            try {
                // 课程端点 403 可能是资源级无权限，不立即判会话过期；
                // 只有全部端点都失败且其中出现过真会话失效（401/登录页重定向）才续登，
                // 避免单个端点权限问题误触发 CAS 登录（风控红线）。
                val payload = getJson(
                    "$baseUrl$endpoint",
                    NetworkOperation.COURSES,
                    forbiddenMeansSessionExpired = false,
                )
                val rawCourses = unwrapList(payload, "courses", "data", "items", "list", "results")
                val courses = rawCourses.mapNotNull { raw ->
                    val obj = raw as? JSONObject ?: return@mapNotNull null
                    val id = firstString(obj, "id", "course_id", "courseId", "cid", "uuid")
                    val title = firstString(obj, "name", "title", "course_title", "course_name", "display_name")
                    if (id.isBlank() || title.isBlank()) return@mapNotNull null
                    val term = firstTerm(obj)
                    CourseSummary(
                        id = id,
                        title = title,
                        term = term,
                        semesterCode = firstSemesterCode(obj, term),
                    )
                }
                if (courses.isNotEmpty()) {
                    EndpointMemory.remember(ENDPOINT_PURPOSE_COURSES, endpoint)
                    return courses
                }
            } catch (error: MainSessionExpiredException) {
                // 真会话失效（401/登录页重定向）已被 getJson 确认：立即上抛触发续登，
                // 不再串行打满其余端点白等（与 fetchCourseware 行为一致）；
                // 减少未认证状态下的多余请求，同样符合风控红线。
                throw error
            } catch (error: Throwable) {
                errors.add("$endpoint: ${error.message}")
            }
        }
        // 附上各端点失败原因：六个端点全挂时保留诊断信息（404 还是超时），便于排查
        val detail = errors.takeIf { it.isNotEmpty() }?.joinToString("；")?.let { "（$it）" }.orEmpty()
        error("当前无法读取课程，请稍后重试或重新登录$detail")
    }

    fun fetchCourseware(courseId: String): List<CoursewareUiItem> {
        val endpoints = listOf(
            "/api/course/$courseId/courseware-activities",
            "/api/courses/$courseId/courseware-activities",
            "/api/course/$courseId/activities",
            "/api/courses/$courseId/activities",
        )
        val activitiesPayload = endpoints.firstNotNullOfOrNull { endpoint ->
            try {
                // 与 fetchCourses 同语义：端点级 403（资源无权限/防盗链）不判会话过期，
                // 只有 401/登录页重定向才上抛触发续登，避免单课程权限问题带动 CAS 登录。
                getJson("$baseUrl$endpoint", NetworkOperation.COURSEWARE, forbiddenMeansSessionExpired = false)
            } catch (error: MainSessionExpiredException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        } ?: error("当前无法读取课程课件，请稍后重试或重新登录")
        val activities = unwrapList(activitiesPayload, "activities", "courseware_activities", "data", "items", "list")
        val queryableActivities = activities.mapIndexedNotNull { index, raw ->
            val activity = raw as? JSONObject ?: return@mapIndexedNotNull null
            val activityId = firstString(activity, "id")
            val activityType = firstString(activity, "type")
            if (activityId.isBlank() || activityType in setOf("exam", "homework")) return@mapIndexedNotNull null
            index to activity
        }
        if (queryableActivities.isEmpty()) return emptyList()
        val executor = executorFactory(minOf(MAX_PARALLEL_DETAILS, queryableActivities.size))
        return try {
            queryableActivities.map { (index, activity) ->
                executor.submit<List<CoursewareUiItem>> {
                    val activityId = firstString(activity, "id")
                    try {
                        val detail = getJson(
                            "$baseUrl/api/activities/$activityId",
                            NetworkOperation.COURSEWARE,
                            forbiddenMeansSessionExpired = false,
                        )
                        itemsFromDetail(courseId, activity, detail)
                    } catch (error: MainSessionExpiredException) {
                        throw error
                    } catch (error: Throwable) {
                        listOf(entryItem(courseId, activity, index, error.message.orEmpty()))
                    }
                }
            }.flatMap { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    // 与 boundedParallelMap 同一约定：统一 unwrap 出原始异常，
                    // 否则上层按异常类型分类（会话/网络/格式）会失配
                    throw error.cause ?: error
                }
            }
        } catch (error: Throwable) {
            // 体检报告 §6-OPT2：首个 future 失败即整批上抛，但此前已 submit 的其余任务
            // 仍会逐个对（可能已失效的）会话发请求——会话过期瞬间就是几十个必败请求的
            // 风暴 + 非守护线程滞留。shutdownNow 清空队列并中断在途任务。
            executor.shutdownNow()
            throw error
        } finally {
            executor.shutdown()
        }
    }

    fun download(item: CoursewareUiItem): String {
        val directory = downloadDirectory
            ?: File(
                // getExternalFilesDir 可返回 null（外部存储不可用）：File(null, child)
                // 得相对路径，后续 mkdirs/写入失败抛难定位异常——回落内部私有目录
                checkNotNull(context).getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: checkNotNull(context).filesDir,
                "xmu助手课件",
            )
        directory.mkdirs()
        return if (item.referenceId.isNotBlank()) {
            val signed = getJson("$baseUrl/api/uploads/reference/${item.referenceId}/url", NetworkOperation.COURSEWARE) as? JSONObject
            val url = signed?.optString("url").orEmpty()
            if (!url.startsWith("http")) error("平台未提供地址")
            downloadUrl(url, directory, item.filename.ifBlank { item.title })
            COURSEWARE_STATUS_SUCCESS
        } else if (isDirectDownloadUrl(item.sourceUrl)) {
            downloadUrl(item.sourceUrl, directory, item.filename.ifBlank { item.title })
            COURSEWARE_STATUS_SUCCESS
        } else {
            val url = item.sourceUrl.ifBlank { "$baseUrl/course/${item.courseId}/learning-activity#/${item.activityId}" }
            synchronized(FILE_ALLOCATION_LOCK) {
                val target = availableFile(directory, "${item.title}.url")
                target.writeText("[InternetShortcut]\nURL=$url\n", Charsets.UTF_8)
            }
            COURSEWARE_STATUS_ENTRY_SAVED
        }
    }

    private fun itemsFromDetail(courseId: String, activity: JSONObject, detail: Any): List<CoursewareUiItem> {
        val obj = detail as? JSONObject ?: return listOf(entryItem(courseId, activity, 0, "平台未提供地址"))
        val uploads = obj.optJSONArray("uploads") ?: JSONArray()
        val activityId = firstString(activity, "id")
        val title = firstString(obj, "title", "name").ifBlank { firstString(activity, "title", "name").ifBlank { "未命名课件" } }
        val type = readableType(firstString(obj, "type").ifBlank { firstString(activity, "type") })
        val sourceUrl = findUrl(obj).ifBlank { "$baseUrl/course/$courseId/learning-activity#/$activityId" }
        val moduleName = firstString(activity, "module_name", "syllabus_name", "chapter", "section")
        if (uploads.length() == 0) {
            return listOf(
                CoursewareUiItem(
                    id = activityId,
                    courseId = courseId,
                    activityId = activityId,
                    title = title,
                    filename = "",
                    type = type,
                    moduleName = moduleName,
                    sourceUrl = sourceUrl,
                    downloadStatus = COURSEWARE_STATUS_AVAILABLE,
                )
            )
        }
        return (0 until uploads.length()).mapNotNull { index ->
            val upload = uploads.optJSONObject(index) ?: return@mapNotNull null
            // optRealString：显式 null 会被 optString 读成 "null" 字面量，拼进签名
            // URL 必败（与 rollcall_id/number_code 同款陷阱）
            val referenceId = upload.optRealString("reference_id")
            CoursewareUiItem(
                id = "$activityId-$index-${referenceId.ifBlank { upload.optString("id") }}",
                courseId = courseId,
                activityId = activityId,
                title = title,
                filename = firstString(upload, "name", "filename", "file_name").ifBlank { title },
                type = readableType(upload.optString("type", type)),
                moduleName = moduleName,
                referenceId = referenceId,
                sourceUrl = sourceUrl,
                downloadStatus = COURSEWARE_STATUS_AVAILABLE,
            )
        }
    }

    private fun entryItem(courseId: String, activity: JSONObject, index: Int, reason: String): CoursewareUiItem {
        val activityId = firstString(activity, "id").ifBlank { "activity-$index" }
        return CoursewareUiItem(
            id = activityId,
            courseId = courseId,
            activityId = activityId,
            title = firstString(activity, "title", "name").ifBlank { "未命名课件" },
            filename = "",
            type = readableType(firstString(activity, "type").ifBlank { "入口" }),
            moduleName = firstString(activity, "module_name", "syllabus_name", "chapter", "section"),
            sourceUrl = findUrl(activity).ifBlank { "$baseUrl/course/$courseId/learning-activity#/$activityId" },
            downloadStatus = COURSEWARE_STATUS_AVAILABLE,
            failureReason = reason.takeIf { it.isNotBlank() }?.let { shortCoursewareError(it) }.orEmpty(),
        )
    }

    private fun downloadUrl(url: String, directory: File, filename: String): String {
        val (target, partial) = reserveDownloadFiles(directory, filename)
        return try {
            val headers = linkedMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
                "Accept-Language" to "zh-CN,zh;q=0.9",
            )
            // 只对平台同源域名带会话 Cookie；签名/CDN 直链（第三方主机）不携带，
            // 避免把登录 cookie 外泄给无关服务器（安全红线）。
            // 同源判定按 URL host 解析比对，不用字符串前缀：lnt.xmu.edu.cn.evil.com
            // 这类前缀伪装域也会 startsWith 命中，Cookie 会外泄给攻击者主机。
            if (cookieHeader.isNotBlank() && isSameHostAsBaseUrl(url)) headers["Cookie"] = cookieHeader
            val result = fileDownloadTransport.download(
                FileDownloadRequest(url = url, headers = headers, operation = NetworkOperation.DOWNLOAD),
                partial,
            )
            // 401 = 会话确定失效（触发续登）；403 = 平台拒绝（版权保护/防盗链/无权限），
            // 与桌面端 courseware._download_url 分类对齐：CDN 防盗链 403 不应引发 CAS 续登风暴
            if (result.code == 401) throw MainSessionExpiredException()
            if (result.code == 403) error("课件下载被平台拒绝（无下载权限）")
            if (result.code !in 200..299) error("网络失败")
            if ("text/html" in result.contentType.lowercase()) error("网络失败")
            check(partial.renameTo(target)) { "课件文件保存失败" }
            target.absolutePath
        } catch (error: Throwable) {
            // 保留 .part：已下载字节是断点续传的依据（传输层失败分支不写脏数据）；
            // 原实现失败即删，续传形同虚设。
            throw error
        } finally {
            // 成功（已 rename）/失败（保留 .part 待续传）都释放进程内占位，
            // 让后续重试可复用同名断点
            releaseReservation(partial)
        }
    }

    /** URL 是否指向 baseUrl 同一主机（按 host 解析比对，防前缀伪装域外泄 Cookie）。 */
    private fun isSameHostAsBaseUrl(url: String): Boolean = runCatching {
        java.net.URL(url).host.equals(java.net.URL(baseUrl).host, ignoreCase = true)
    }.getOrDefault(false)

    private fun getJson(
        url: String,
        operation: NetworkOperation,
        forbiddenMeansSessionExpired: Boolean = true,
    ): Any {
        val headers = linkedMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) Mobile Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Accept" to "application/json, text/plain, */*",
        )
        if (cookieHeader.isNotBlank()) headers["Cookie"] = cookieHeader
        val response = queryTransport.execute(
            QueryHttpRequest(
                url = url,
                method = "GET",
                headers = headers,
                operation = operation,
            ),
        )
        if (response.code == 401 || (response.code == 403 && forbiddenMeansSessionExpired)) {
            throw MainSessionExpiredException()
        }
        // 会话过期时平台返回 302 跳身份域（query 客户端 followRedirects=false），
        // 与探活同一判定；否则误分类为「网络失败」，续登机制永不触发
        if (response.code in 300..399 && isIdentityRedirect(response.url, response.location)) {
            throw MainSessionExpiredException()
        }
        if (response.code !in 200..299) error("网络失败")
        // 2xx 但非 JSON（空 body/HTML 错误页）：解析异常统一归为「网络失败」，
        // 否则 JSONException 会从 download() 等无兜底调用点逃逸
        return runCatching {
            if (response.body.trimStart().startsWith("[")) JSONArray(response.body) else JSONObject(response.body)
        }.getOrElse { error("网络失败") }
    }

    private fun unwrapList(value: Any, vararg keys: String): List<Any> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) }
            is JSONObject -> {
                for (key in keys) {
                    val nested = value.opt(key)
                    val list = if (nested != null) unwrapList(nested, *keys) else emptyList()
                    if (list.isNotEmpty()) return list
                }
                value.keys().asSequence()
                    .mapNotNull { key -> value.opt(key) }
                    .firstNotNullOfOrNull { nested -> unwrapList(nested, *keys).takeIf { it.isNotEmpty() } }
                    ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } } ?: ""

    private fun firstTerm(obj: JSONObject): String {
        val year = obj.opt("academic_year")
        if (year is JSONObject) return firstString(year, "name", "code", "id")
        return obj.optString("academic_year").ifBlank {
            obj.optString("term_name").ifBlank {
                obj.optString("semester_name").ifBlank { obj.optString("term") }
            }
        }
    }

    private fun firstSemesterCode(obj: JSONObject, term: String): String {
        val semester = obj.opt("semester")
        if (semester is JSONObject) return firstString(semester, "code", "id", "name")
        val raw = obj.optString("semester").ifBlank { obj.optString("semester_code") }
        return raw.ifBlank { term }
    }

    private fun findUrl(value: Any?): String {
        return when (value) {
            is String -> value.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
            is JSONArray -> (0 until value.length()).asSequence().map { findUrl(value.opt(it)) }.firstOrNull { it.isNotBlank() }.orEmpty()
            is JSONObject -> {
                val keys = listOf("url", "source_url", "link", "href", "video_url", "play_url", "resource_url", "download_url", "external_url", "preview_url")
                keys.asSequence().map { findUrl(value.opt(it)) }.firstOrNull { it.isNotBlank() }
                    ?: value.keys().asSequence().map { findUrl(value.opt(it)) }.firstOrNull { it.isNotBlank() }.orEmpty()
            }
            else -> ""
        }
    }

    private fun readableType(raw: String): String {
        val lowered = raw.lowercase()
        return when {
            "video" in lowered -> "视频"
            "link" in lowered || "page" in lowered || "html" in lowered || "h5" in lowered || "scorm" in lowered -> "入口"
            else -> "资料"
        }
    }

    private fun isDirectDownloadUrl(url: String): Boolean {
        val lowered = url.lowercase().substringBefore("?")
        return DIRECT_COURSEWARE_EXTENSIONS.any { lowered.endsWith(it) }
    }

    private fun availableFile(directory: File, rawName: String): File {
        val target0 = File(directory, sanitizedFileName(rawName))
        var target = target0
        val dot = target.name.lastIndexOf('.')
        val stem = if (dot > 0) target.name.substring(0, dot) else target.name
        val suffix = if (dot > 0) target.name.substring(dot) else ""
        var index = 2
        while (target.exists() || File(target.parentFile, target.name + ".part").exists()) {
            target = File(directory, "$stem ($index)$suffix")
            index += 1
        }
        return target
    }

    private fun sanitizedFileName(rawName: String): String =
        rawName.replace(Regex("""[<>:"/\\|?*\u0000-\u001f]"""), "_")
            .trim()
            .trimEnd('.')
            .ifBlank { "courseware" }
            .take(120)

    private fun reserveDownloadFiles(directory: File, rawName: String): Pair<File, File> {
        // 有限重试：目录不可写（磁盘满/权限/文件系统错误）时 createNewFile 会一直失败，
        // 若 while(true) 会永久忙等并持有锁；限次后抛错让下载走失败分支。
        var attempts = 0
        while (attempts < 50) {
            attempts += 1
            // 探测与原子占位必须在同一临界区：若 createNewFile 在锁外，
            // 并发批量下载同名课件时两个线程会拿到同一个 .part 路径互相覆写（TOCTOU 静默损坏）。
            val allocated = synchronized(FILE_ALLOCATION_LOCK) {
                // 断点续传优先（仅首轮）：正式文件缺失而同名 .part 残留且未被在途任务持有
                // 时复用原名接着下载——否则每次重试都因 .part 存在而换名，续传形同虚设。
                if (attempts == 1) {
                    val primary = File(directory, sanitizedFileName(rawName))
                    val primaryPartial = File(primary.parentFile, primary.name + ".part")
                    if (!primary.exists() && primaryPartial.exists() && primaryPartial.path !in claimedParts) {
                        claimedParts += primaryPartial.path
                        return@synchronized primary to primaryPartial
                    }
                }
                val target = availableFile(directory, rawName)
                val partial = File(target.parentFile, target.name + ".part")
                if (partial.createNewFile()) {
                    claimedParts += partial.path
                    target to partial
                } else {
                    null
                }
            }
            if (allocated != null) return allocated
        }
        error("课件文件目录不可写，请检查存储空间")
    }

    private fun releaseReservation(partial: File) {
        synchronized(FILE_ALLOCATION_LOCK) { claimedParts.remove(partial.path) }
    }

    private companion object {
        const val MAX_PARALLEL_DETAILS = 4
        const val ENDPOINT_PURPOSE_COURSES = "courses"
        val FILE_ALLOCATION_LOCK = Any()

        /** 进程内在途下载占用的 .part 路径（配合 FILE_ALLOCATION_LOCK 守护）。 */
        val claimedParts = mutableSetOf<String>()
    }
}
