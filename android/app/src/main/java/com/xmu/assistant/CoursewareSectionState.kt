package com.xmu.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 课程/课件模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *   后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态；
 *   会话过期时经 setPendingSessionRetry 挂起一次性安全续登（recoverExpiredModule 处理）。
 * - 课程列表（courses/academicCache/selectedCourseId）留在组合侧（课程刷新 refreshCourses
 *   与 CoursewarePage 编排共用），本分块只承接课件部分的状态与动作，通过函数式注入读写。
 * - 下载写回 busy（组合侧全局忙碌指示）用读写器注入，与拆分前行为一致。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *   不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class CoursewareSectionState(
    private val activity: Activity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val requireLogin: () -> Boolean,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val busy: () -> String,
    private val setBusy: (String) -> Unit,
    private val selectedCourseId: () -> String?,
    private val setSelectedCourseId: (String?) -> Unit,
    private val academicCache: () -> AcademicCacheSnapshot,
    private val setAcademicCache: (AcademicCacheSnapshot) -> Unit,
    private val setAcademicCacheJson: (String) -> Unit,
    private val isSelectedCourse: (String) -> Boolean,
    private val setPendingSessionRetry: (ModuleReadRequest, CourseSummary?) -> Unit,
) {
    var coursewareItems by mutableStateOf(emptyList<CoursewareUiItem>())
        private set

    var selectedCoursewareIds by mutableStateOf(setOf<String>())
        private set

    var coursewareDownloadProgress by mutableStateOf("")
        private set

    var loadingIds by mutableStateOf(emptySet<String>())
        private set

    var refreshErrors by mutableStateOf(emptyMap<String, String>())
        private set

    var downloadLoading by mutableStateOf(false)
        private set

    /** 加载某课程的课件列表。缓存优先：命中且未过期直接返回；强制刷新（forceRefresh）跳过缓存。 */
    fun load(
        course: CourseSummary,
        forceRefresh: Boolean = false,
        alreadyRetriedAfterRecovery: Boolean = false,
    ): Boolean {
        if (!requireLogin()) return false
        setSelectedCourseId(course.id)
        coursewareItems = academicCache().coursewareByCourse[course.id].orEmpty()
        selectedCoursewareIds = emptySet()
        coursewareDownloadProgress = ""
        if (!forceRefresh &&
            academicCache().coursewareByCourse.containsKey(course.id) &&
            isAcademicCacheFresh(academicCache().coursewareUpdatedAtMillis[course.id] ?: 0L)
        ) return false
        val gateKey = "courseware:${course.id}"
        if (!requestGate.tryStart(gateKey)) return false
        refreshErrors = refreshErrors - course.id
        loadingIds = loadingIds + course.id
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { CoursewareClient(activity, session.cookieHeader).fetchCourseware(course.id) }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            result.onSuccess { list ->
                                refreshErrors = refreshErrors - course.id
                                val updatedAt = System.currentTimeMillis()
                                val updatedCache = academicCache().withCourseware(course.id, list, updatedAt)
                                setAcademicCache(updatedCache)
                                setAcademicCacheJson(academicCacheToJson(updatedCache))
                                if (isSelectedCourse(course.id)) {
                                    selectedCoursewareIds = retainAvailableCoursewareSelection(selectedCoursewareIds, list)
                                    coursewareItems = list
                                }
                                show("已读取 ${list.size} 条课件")
                            }
                            result.onFailure { error ->
                                if (sessionExpiryAction(error, alreadyRetriedAfterRecovery) == SessionExpiryAction.RECOVER_ONCE) {
                                    refreshErrors = refreshErrors +
                                        (course.id to "登录已过期，正在安全续登")
                                    setPendingSessionRetry(ModuleReadRequest.COURSEWARE, course)
                                } else {
                                    val errorMessage = refreshFailureMessage(error)
                                    refreshErrors = refreshErrors + (course.id to errorMessage)
                                    show("课件读取失败：$errorMessage")
                                }
                            }
                        }
                    } finally {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            loadingIds = loadingIds - course.id
                        }
                    }
                }
            } finally {
                // 互斥门释放提到协程最外层（NonCancellable）：scope 被取消时
                // withContext(Main) 抛 CancellationException 会跳过内层 finally，
                // finish 放内层会导致 gateKey 永久占用、之后该课课件加载被 tryStart 永远拒绝
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish(gateKey)
                }
            }
        }
        return true
    }

    /** 勾选状态切换（组合侧 CoursewarePage onToggle 委托）。 */
    fun toggleSelected(id: String) {
        selectedCoursewareIds = if (id in selectedCoursewareIds) {
            selectedCoursewareIds - id
        } else {
            selectedCoursewareIds + id
        }
    }

    /** 全选/取消全选（组合侧 CoursewarePage onSelectAll 委托）。 */
    fun toggleSelectAll() {
        selectedCoursewareIds = if (selectedCoursewareIds.size == coursewareItems.size) {
            emptySet()
        } else {
            coursewareItems.map { it.id }.toSet()
        }
    }

    /** 下载勾选的课件：后台并行下载，进度/结果逐条写回 UI。 */
    fun downloadSelected() {
        if (!requireLogin()) return
        val targets = coursewareItems.filter { it.id in selectedCoursewareIds }
        if (targets.isEmpty()) {
            show("请先勾选课件")
            return
        }
        if (!requestGate.tryStart("downloads")) return
        downloadLoading = true
        val sessionCookie = cookieHeader()
        // 快照发起时的会话纪元：下载是后台线程写回，登出/重登/切换账号必须
        // 让旧回调失效（裸 cookie 比较拦不住「登出后同账号重登」的陈旧写回）。
        val session = sessionEpoch.snapshot(sessionOwner, sessionCookie)
        coursewareItems = coursewareItems.map { item ->
            if (item.id in selectedCoursewareIds) {
                item.copy(downloadStatus = COURSEWARE_STATUS_DOWNLOADING, failureReason = "")
            } else {
                item
            }
        }
        coursewareDownloadProgress = "下载进度：下载中 ${targets.size} / 成功 0 / 失败 0"
        setBusy("正在下载课件")
        scope.launch(Dispatchers.IO) {
            try {
                val files = AtomicInteger(0)
                val entries = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val done = AtomicInteger(0)
                val client = CoursewareClient(activity, sessionCookie)
                runCatching {
                    downloadCoursewareInParallel(targets) { item ->
                        val outcome = runCatching { client.download(item) }
                        val status: String
                        val reason: String
                        outcome.onSuccess { result ->
                            if (result == COURSEWARE_STATUS_ENTRY_SAVED) entries.incrementAndGet() else files.incrementAndGet()
                        }.onFailure {
                            failed.incrementAndGet()
                        }
                        status = outcome.getOrElse { error -> coursewareFailureStatus(error.message.orEmpty()) }
                        reason = outcome.exceptionOrNull()?.message.orEmpty().let(::shortCoursewareError)
                        val completed = done.incrementAndGet()
                        // boundedParallelMap 的 transform 是同步回调（非 suspend 上下文），
                        // 用 runOnUiThread 切主线程写 Compose 状态（与拆分前行为一致）。
                        activity.runOnUiThread {
                            if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                                coursewareItems = coursewareItems.map { current ->
                                    if (current.id == item.id) {
                                        current.copy(
                                            downloadStatus = status,
                                            failureReason = if (outcome.isSuccess) "" else reason,
                                        )
                                    } else {
                                        current
                                    }
                                }
                                coursewareDownloadProgress =
                                    "下载进度：下载中 ${targets.size - completed} / 成功 ${files.get() + entries.get()} / 失败 ${failed.get()}"
                            }
                        }
                    }
                }.onFailure { error ->
                    // 协程取消不是「下载失败」：重抛，不弹失败 toast（转屏/离开页面属正常取消）
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    withContext(Dispatchers.Main) {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            show("下载失败：${friendlyMessage(error)}")
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (sessionEpoch.accepts(session, cookieHeader(), loggedIn()) && done.get() == targets.size) {
                        show("下载完成：文件 ${files.get()}，入口 ${entries.get()}，失败 ${failed.get()}")
                    }
                }
            } finally {
                // 收尾（复位 busy/loading、释放互斥门）必须随取消也能执行：
                // 放 withContext(Main) 内层时，协程取消会整段跳过，"downloads" 门永久占用
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (busy() == "正在下载课件") setBusy("")
                    downloadLoading = false
                    requestGate.finish("downloads")
                }
            }
        }
    }

    /** 打开选中的课件（平台内链优先，缺省回退到课程学习页）。 */
    fun openSelected() {
        val item = coursewareItems.firstOrNull { it.id in selectedCoursewareIds } ?: coursewareItems.firstOrNull()
        if (item == null) {
            show("请先选择课件")
            return
        }
        val url = item.sourceUrl.ifBlank { "https://lnt.xmu.edu.cn/course/${item.courseId}/learning-activity#/${item.activityId}" }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            show("无法打开平台页面")
        }
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。 */
    fun clearLoadingState() {
        loadingIds = emptySet()
        downloadLoading = false
    }

    /** 登出/换号清理：清课件内存状态（课程列表/缓存由组合侧一并清理，与拆分前一致）。 */
    fun clearAll() {
        coursewareItems = emptyList()
        loadingIds = emptySet()
        refreshErrors = emptyMap()
        selectedCoursewareIds = emptySet()
        coursewareDownloadProgress = ""
        downloadLoading = false
    }
}
