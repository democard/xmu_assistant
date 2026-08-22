package com.xmu.assistant

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 考试安排模块的独立分块：状态 + 动作（从 XmuAssistantApp 拆分，不引入 ViewModel）。
 *
 * 设计约定（与拆分前行为逐字对齐）：
 * - 状态字段全部 Compose 可观察（mutableStateOf），仅 UI 线程写入；
 *   后台线程只读发起时的快照值（避免跨线程读写 MutableState）。
 * - 转屏/进程重建恢复：selectedTerm 与 manuallySelected 由组合侧
 *   rememberSaveable + Saver 保存/恢复（见 MainActivity 中的声明）；
 *   summary/validTerms 等其余状态初始从磁盘缓存重载。
 * - 会话保护：网络回调先经 sessionEpoch.accepts(...) 校验世代再写状态，
 *   与其它模块共用同一进程级 epoch；续登写回教务 cookie（setScoreCookieHeader）
 *   前同样校验 isCurrent（防登出/换号后旧账号 cookie 写回）。
 * - 存储依赖收窄为函数式注入（scoreCookieHeader/setScoreCookieHeader/reminderSettings）：
 *   组合侧绑定 AssistantSettings（加密 prefs），测试注入 fake，互不依赖。
 * - 风控红线：考试内部可能触发教务 CAS 续登（XmuExamClient.fetchTermExams 统一续登，
 *   进程级单飞门），与 TronClass 手动登录并发会打两个身份域——refresh/checkChanges 等
 *   入口检查 accountTransitionInProgress；请求统一走 "exam" 互斥门，
 *   提醒重排走 "exam-reminder" 门。
 * - 网络/IO 用注入的 scope（组合侧 rememberCoroutineScope，随 Activity 销毁取消），
 *   不再使用裸 thread{}（防"页面已关还弹 Toast / 写已销毁状态"）。
 */
internal class ExamSectionState(
    private val activity: ComponentActivity,
    private val requestGate: RequestGate,
    private val sessionEpoch: SessionEpoch,
    private val sessionOwner: SessionOwner,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val loggedIn: () -> Boolean,
    private val cookieHeader: () -> String,
    private val username: () -> String,
    private val password: () -> String,
    private val accountTransitionInProgress: () -> Boolean,
    private val scoreCookieHeader: () -> String,
    private val setScoreCookieHeader: (String) -> Unit,
    private val     reminderSettings: () -> ExamReminderSettings,
    // null = 未指定（首次创建）：取缓存学期列表的第一个；显式空串（Saver 恢复无选择）保持空
    selectedTermInitial: String? = null,
    manuallySelectedInitial: Boolean = false,
) {
    // 学期列表只读一次：此前 selectedTermInitial 默认值与 validTerms 各调一次 loadTerms，
    // 组合期间重复两次 prefs IO + JSON 解析
    private val initialTerms: List<String> = ExamCache.loadTerms(activity)

    /** 当前选中的学期（默认最近一个有效学期；进程重建由 Saver 恢复）。 */
    var selectedTerm by mutableStateOf(selectedTermInitial ?: initialTerms.firstOrNull().orEmpty())
        private set

    /** 用户是否手动切换过学期：未手动选过时默认始终校正到最近学期。 */
    var manuallySelected by mutableStateOf(manuallySelectedInitial)
        private set

    /** 当前学期展示数据（缓存优先，悄悄检查发现变化才更新）。 */
    var summary by mutableStateOf(ExamCache.loadTerm(activity, selectedTermInitial ?: initialTerms.firstOrNull().orEmpty()))
        private set

    /** 有效学期列表（新→旧，最近在前）。 */
    var validTerms by mutableStateOf(initialTerms)
        private set

    var loading by mutableStateOf(false)
        private set

    var refreshError by mutableStateOf("")
        private set

    /** 悄悄检查/切学期检测到变化并自动更新（页面显示「检测到变化，自动更新」横幅）。 */
    var autoUpdated by mutableStateOf(false)
        private set

    /** 学期条点击：立即复用缓存结果，后台悄悄检查变化；无缓存且已登录才拉取。 */
    fun selectTerm(term: String) {
        selectedTerm = term
        manuallySelected = true
        autoUpdated = false
        val cached = ExamCache.loadTerm(activity, term)
        if (cached != null) {
            // 有缓存：立即复用上次结果，后台悄悄检查变化
            summary = cached
            refreshError = ""
            checkChanges(term)
        } else if (!loggedIn() || cookieHeader().isBlank() || accountTransitionInProgress()) {
            // 登录/恢复在途且无该学期缓存：若保留旧学期 summary，ExamPage 不按
            // selectedTerm 过滤会把「旧学期数据」显示到新切换的标签下（错位）。
            // 此处清空摘要防串显，并提示用户稍后重试（页面以提示代替错位数据）。
            summary = null
            show("登录处理中，请稍候")
        } else {
            // 无缓存：拉取该学期（首次查看，允许加载指示）
            summary = null
            refresh()
        }
    }

    /** 获取教务域会话 cookie（与课表/成绩同域，直接复用 settings 存储）。
     *
     * 注意：**不再调用 ensureScheduleSession**。实测（2026-08-17 体检）：
     * ensureScheduleSession 会打开课表应用（appShow wdkbapp），而 jwapp 是
     * 单应用会话模型——课表应用会话占用后，考试应用（studentWdksapApp）的
     * 业务请求一律被 Tomcat 拒绝（403），每次操作都逼出一次完整 CAS 续登
     * （风控暴露翻倍 + 请求量翻倍），且续登后的有效 cookie 写回后仍被下一轮
     * ensureScheduleSession 顶掉，形成固定循环。
     * 考试链路改为：直接用存储 cookie → XmuExamClient 内部 appOpen（考试应用
     * 入口）→ 业务请求；会话过期（401/403/登录页）时由 fetchTermExams 统一
     * 续登一次并重试，成功后把有效 cookie 写回 settings（下次操作直接复用，
     * 不再循环）。
     */
    fun ensureAcademicSession(): String {
        // 后台执行时复查登录在途：考试续登与 TronClass 登录并发会打两个身份域（风控红线）
        if (accountTransitionInProgress()) throw ExamLoginInProgressException()
        return scoreCookieHeader()
    }

    /** 按当前设置重排考试提醒闹钟（幂等：先取消旧计划再排新计划）。
     *  聚合所有已缓存学期的考试（跨学期提醒不丢）；执行挪到后台线程，不阻塞主线程。 */
    fun rescheduleReminders() {
        if (!requestGate.tryStart("exam-reminder")) return
        val reminderSettings = reminderSettings()
        val context = activity
        scope.launch(Dispatchers.IO) {
            try {
                // 登出竞态保护（与 clearAll 的 gate 等待双保险）：执行前复查教务会话已清，
                // 避免登出后仍用旧缓存重建闹钟（reschedule 线程可能晚于登出启动）。
                if (scoreCookieHeader().isBlank()) return@launch
                ExamReminder.ensureChannel(context)
                // 聚合所有已缓存学期的考试（含未完成/已完成，schedule 内部跳过已过提醒时间的）
                ExamReminder.schedule(context, reminderSettings, ExamCache.loadAllExams(context))
            } finally {
                // NonCancellable：scope 取消时普通 withContext 会抛 CancellationException 跳过 finish，
                // 导致门永久占用（与其它分块同一修复）
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    requestGate.finish("exam-reminder")
                }
            }
        }
    }

    /** 强制刷新当前学期考试数据（手动刷新按钮）。写回前校验会话纪元。 */
    fun fetchData(term: String, session: SessionRequest, gateKey: String) {
        val accountUsername = username()
        val accountPassword = password()
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching {
                    // 考试接口在教务域（jw.xmu.edu.cn）：复用课表/成绩的教务会话，
                    // 会话过期时客户端内部走与课表/成绩同构的安全续登（进程级单飞门，不并发打登录）。
                    val jwCookie = ensureAcademicSession()
                    val client = XmuExamClient(
                        jwCookie,
                        accountUsername,
                        accountPassword,
                        mayRelogin = { sessionEpoch.isCurrent(session) },
                    )
                    val fetched = client.fetchTermExams(term)
                    if (sessionEpoch.isCurrent(session)) setScoreCookieHeader(client.currentCookie())
                    fetched
                }
                withContext(Dispatchers.Main) {
                    try {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            result.onSuccess { fetched ->
                                refreshError = ""
                                if (fetched != null) {
                                    // 无条件落缓存（任何学期数据都可供切学期/提醒聚合复用）
                                    ExamCache.saveTerm(activity, fetched)
                                    // 仅当仍为当前选中学期才更新显示（防止在途请求覆盖用户新切的学期）
                                    if (term == selectedTerm) {
                                        summary = fetched
                                        autoUpdated = false
                                    }
                                    // 数据更新后重排提醒（聚合所有缓存学期）
                                    rescheduleReminders()
                                } else if (term == selectedTerm) {
                                    // 该学期两接口都空（无考试也无已选课未安排）：显示空状态，
                                    // 不设错误文案（"暂无数据"不是刷新失败）。
                                    // 空摘要也落缓存 + 重排提醒：清掉该学期已删除考试的旧闹钟（避免残留误触发）。
                                    val empty = XmuTermExamSummary(term, term, emptyList(), emptyList())
                                    summary = empty
                                    ExamCache.saveTerm(activity, empty)
                                    rescheduleReminders()
                                }
                            }
                            result.onFailure { error ->
                                refreshError = refreshFailureMessage(error)
                                show("考试安排读取失败：$refreshError")
                            }
                        }
                    } finally {
                        if (sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            loading = false
                        }
                    }
                }
            } finally {
                // 门释放提到协程最外层（NonCancellable）：协程取消时内层 withContext 整段跳过，
                // finish 放内层会让 gateKey 永久占用
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish(gateKey)
                }
            }
        }
    }

    fun refresh() {
        // 登录/恢复在途时禁止并发刷新：考试内部可能触发 academic CAS 续登（XmuExamClient 统一续登），
        // 与 TronClass 手动登录并发会打两个身份域（风控红线，与课表刷新同一保护）。
        if (accountTransitionInProgress()) {
            show("登录处理中，请稍候")
            return
        }
        if (cookieHeader().isBlank()) {
            show("请先登录")
            return
        }
        if (username().isBlank() || password().isBlank()) {
            show("请先在首页填写学号和密码")
            return
        }
        val gateKey = "exam"
        if (!requestGate.tryStart(gateKey)) {
            show("正在刷新，请稍候")
            return
        }
        loading = true
        refreshError = ""
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val term = selectedTerm
        val accountUsername = username()
        val accountPassword = password()
        val knownTerms = validTerms
        if (term.isBlank()) {
            // 还没有选中学期：先探测有效学期，再取最近一个
            scope.launch(Dispatchers.IO) {
                // 门释放走外层 finally（NonCancellable）：协程取消时 withContext(Main) 整段
                // 跳过会导致 "exam" 门永久占用；fetchData 路径由 fetchData 自己的 finally 释放，
                // 用 ownsGate 标记归属避免双重释放
                var ownsGate = true
                try {
                    val probe = runCatching {
                        val jwCookie = ensureAcademicSession()
                        val client = XmuExamClient(
                            jwCookie,
                            accountUsername,
                            accountPassword,
                            mayRelogin = { sessionEpoch.isCurrent(session) },
                        )
                        val terms = client.probeValidTerms(knownTerms = knownTerms)
                        if (sessionEpoch.isCurrent(session)) setScoreCookieHeader(client.currentCookie())
                        terms
                    }
                    val result = probe.getOrDefault(ExamProbeResult(emptyList(), null))
                    val terms = result.terms
                    val probeError = probe.exceptionOrNull()
                    val target = terms.firstOrNull()
                    // 仅探测成功才记录重探时刻（失败不屏蔽 6h 重探，可自愈）
                    if (probeError == null) ExamCache.markProbed(activity)
                    // 窗口内全部有效学期数据落缓存（切学期秒显 + 跨学期提醒聚合）
                    result.summaries.forEach { (_, s) -> ExamCache.saveTerm(activity, s) }
                    withContext(Dispatchers.Main) {
                        if (!sessionEpoch.accepts(session, cookieHeader(), loggedIn())) {
                            loading = false
                            return@withContext
                        }
                        validTerms = terms
                        ExamCache.saveTerms(activity, terms)
                        if (target != null) {
                            // 默认选中最近一个学期；用户已手动选择且仍有效则保留
                            val desired = selectedTerm.takeIf { it in terms } ?: target
                            selectedTerm = desired
                            // 门移交给 fetchData（其 finally 统一释放）
                            ownsGate = false
                            fetchData(desired, session, gateKey)
                        } else {
                            loading = false
                            // 探测失败要明确提示（会话过期/网络异常），不能静默成"没有数据"
                            refreshError = probeError?.let { refreshFailureMessage(it) } ?: "没有找到考试数据"
                        }
                    }
                } finally {
                    if (ownsGate) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            requestGate.finish(gateKey)
                        }
                    }
                }
            }
            return
        }
        fetchData(term, session, gateKey)
    }

    /** 切换学期/进入页面后的悄悄检查：有变化（列表增删/未完成→已完成）才更新。 */
    fun checkChanges(term: String) {
        if (!loggedIn() || cookieHeader().isBlank()) return
        // 登录/恢复在途时跳过：考试检查可能触发 academic CAS 续登（XmuExamClient 统一续登），
        // 与 TronClass 手动登录并发会打两个身份域（风控红线，与课表刷新同一保护）。
        if (accountTransitionInProgress()) return
        val cached = summary?.takeIf { it.termCode == term } ?: return
        // 与手动刷新/静默刷新共用同一互斥 key：任一考试请求在途时静默跳过（避免并发请求与乱序写回）
        val gateKey = "exam"
        if (!requestGate.tryStart(gateKey)) return
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountUsername = username()
        val accountPassword = password()
        scope.launch(Dispatchers.IO) {
            try {
                val latest = runCatching {
                    val jwCookie = ensureAcademicSession()
                    val client = XmuExamClient(
                        jwCookie,
                        accountUsername,
                        accountPassword,
                        mayRelogin = { sessionEpoch.isCurrent(session) },
                    )
                    val summary = client.fetchTermExams(term)
                    if (sessionEpoch.isCurrent(session)) setScoreCookieHeader(client.currentCookie())
                    summary
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (!sessionEpoch.accepts(session, cookieHeader(), loggedIn())) return@withContext
                    // 用户已切走：结果仅落缓存，不改显示
                    if (term != selectedTerm) {
                        latest?.let { ExamCache.saveTerm(activity, it) }
                        return@withContext
                    }
                    if (latest == null) {
                        // 非空→变空（教务清空该学期）：与 silentRefresh 同构——显示空态、落空缓存、
                        // 重排提醒（清掉已删考试的旧闹钟），避免切学期路径下空壳学期长期显示过期数据。
                        if (cached.exams.isNotEmpty() || cached.unarranged.isNotEmpty()) {
                            val empty = XmuTermExamSummary(term, term, emptyList(), emptyList())
                            summary = empty
                            ExamCache.saveTerm(activity, empty)
                            autoUpdated = true
                            rescheduleReminders()
                        }
                        return@withContext
                    }
                    val changed = examsChanged(cached, latest)
                    if (changed) {
                        summary = latest
                        ExamCache.saveTerm(activity, latest)
                        autoUpdated = true
                        // 数据变化后重新调度提醒（新增考试/时间变动）
                        rescheduleReminders()
                    }
                }
            } finally {
                // 门释放提到协程最外层（NonCancellable），协程取消也能执行
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish(gateKey)
                }
            }
        }
    }

    /**
     * 进入考试页的默认路径（悄悄检查，与用户要求一致）：
     * - 立即复用缓存结果（多学期缓存 + 学期列表缓存），不显示"正在刷新"表达；
     *   仅无任何缓存可复用时（首次）显示加载指示
     * - 后台静默拉取：当前学期数据每次进入都检查；学期列表按节流间隔（6 小时）重探
     * - 检测到变化（学期列表/考试数据）才更新，并提示「检测到变化，自动更新」
     * - 默认选中最近一个学期；用户已手动选择且仍有效则保留
     */
    fun silentRefresh() {
        // 与手动刷新/切学期检查共用同一互斥 key：任一考试请求在途时静默跳过
        val gateKey = "exam"
        if (!requestGate.tryStart(gateKey)) return
        if (summary == null) loading = true
        // 主线程一次性快照 Compose 状态，后台线程只读快照（避免跨线程读 MutableState）
        val snapshotSelectedTerm = selectedTerm
        val snapshotManuallySelected = manuallySelected
        val snapshotValidTerms = validTerms
        val session = sessionEpoch.snapshot(sessionOwner, cookieHeader())
        val accountUsername = username()
        val accountPassword = password()
        scope.launch(Dispatchers.IO) {
            try {
                val result = runCatching {
                    val jwCookie = ensureAcademicSession()
                    val client = XmuExamClient(
                        jwCookie,
                        accountUsername,
                        accountPassword,
                        mayRelogin = { sessionEpoch.isCurrent(session) },
                    )
                    val reProbe = ExamCache.shouldReProbe(activity) || snapshotValidTerms.isEmpty()
                    var terms: List<String>
                    var summaries: Map<String, XmuTermExamSummary> = emptyMap()
                    if (reProbe) {
                        // probe 返回新→旧（最近在前）+ 全部有效学期数据（probe 已拉取，复用省请求）；
                        // 传入已缓存学期（knownTerms）：窗口收敛只探测新增/边界学期，省请求
                        val probe = client.probeValidTerms(knownTerms = snapshotValidTerms)
                        terms = probe.terms
                        summaries = probe.summaries
                        ExamCache.markProbed(activity)
                        // 窗口内全部有效学期落缓存（切学期秒显 + 跨学期提醒聚合）
                        summaries.forEach { (_, s) -> ExamCache.saveTerm(activity, s) }
                    } else {
                        terms = snapshotValidTerms
                    }
                    // 目标学期与 UI 校正用同一规则（未手动选择时始终取最近学期 = 列表第一个）
                    val target = if (snapshotManuallySelected) {
                        snapshotSelectedTerm.takeIf { it in terms } ?: terms.firstOrNull()
                    } else {
                        terms.firstOrNull()
                    }
                    // 目标为最近学期且 probe 已拉取时直接复用，不再重复请求（提速）
                    val fetched = summaries[target] ?: target?.let { client.fetchTermExams(it) }
                    if (sessionEpoch.isCurrent(session)) setScoreCookieHeader(client.currentCookie())
                    SilentRefreshResult(terms, target, fetched, summaries)
                }
                withContext(Dispatchers.Main) {
                    try {
                        if (!sessionEpoch.accepts(session, cookieHeader(), loggedIn())) return@withContext
                        result.onSuccess { (terms, target, latest, _) ->
                            refreshError = ""
                            validTerms = terms
                            ExamCache.saveTerms(activity, terms)
                            // 默认最近一个学期（未手动选择时始终校正到最近 = 列表第一个）
                            val desired = if (manuallySelected) {
                                selectedTerm.takeIf { it in terms } ?: terms.firstOrNull()
                            } else {
                                terms.firstOrNull()
                            }
                            if (desired != null && desired != selectedTerm) {
                                selectedTerm = desired
                                summary = ExamCache.loadTerm(activity, desired)
                                autoUpdated = false
                            }
                            // 用户已切走：结果仅落缓存（后台已存），不改显示与横幅
                            if (target == null || target != selectedTerm) {
                                return@onSuccess
                            }
                            val shown = summary
                            if (shown == null || shown.termCode != target) {
                                // 该学期没有可复用的上次结果：直接显示最新数据（首次查看）
                                summary = latest
                                latest?.let { ExamCache.saveTerm(activity, it) }
                                autoUpdated = false
                            } else if (latest == null) {
                                // 学期数据非空→变空（考试/未安排被教务清空）：视为变化，显示空态；
                                // 落空缓存 + 重排提醒（清掉已删考试的旧闹钟，避免残留误触发）
                                if (shown.exams.isNotEmpty() || shown.unarranged.isNotEmpty()) {
                                    val empty = XmuTermExamSummary(target, target, emptyList(), emptyList())
                                    summary = empty
                                    ExamCache.saveTerm(activity, empty)
                                    autoUpdated = true
                                    rescheduleReminders()
                                } else {
                                    autoUpdated = false
                                }
                            } else if (examsChanged(shown, latest)) {
                                // 悄悄检查发现变化：更新数据 + 提示 + 重排提醒
                                summary = latest
                                ExamCache.saveTerm(activity, latest)
                                autoUpdated = true
                                rescheduleReminders()
                            } else {
                                // 无变化：清除上次的更新横幅（避免残留误导）
                                autoUpdated = false
                            }
                        }
                        result.onFailure { error ->
                            // 有缓存时静默失败：保留上次结果，下次进入/手动刷新再试；
                            // 无缓存可复用（首次）才明确提示，避免空页面无解释
                            if (summary == null) {
                                refreshError = refreshFailureMessage(error)
                            }
                        }
                    } finally {
                        loading = false
                    }
                }
            } finally {
                // 门释放提到协程最外层（NonCancellable），协程取消也能执行
                withContext(kotlinx.coroutines.NonCancellable) {
                    requestGate.finish(gateKey)
                }
            }
        }
    }

    /** 会话失效时仅复位加载中标志（保留已展示数据，供 invalidateMainSessionUi 使用）。
     *  缺失会导致：刷新在途时会话失效，fetchData 回调被 sessionEpoch 守卫丢弃，
     *  finally 的条件复位同样被跳过，loading 永真、刷新按钮永久转圈。 */
    fun clearLoadingState() {
        loading = false
    }

    /** 登出/换号清理：清状态 + 磁盘缓存 + 提醒闹钟（带 gate 竞态保护，与 clearLoggedOutUi 语义对齐）。
     *  考试数据不含个人信息，但跟随账号会话，换号时也必须清（防串号串提醒）。 */
    fun clearAll() {
        summary = null
        validTerms = emptyList()
        selectedTerm = ""
        manuallySelected = false
        loading = false
        refreshError = ""
        autoUpdated = false
        ExamCache.clear(activity)
        // 登出-提醒竞态保护：尝试取得 exam-reminder 门（无在途重排）再 cancelAll。
        // 在途时不忙等（阻塞 UI 且 reschedule 线程执行前复查 scoreCookieHeader 已兜底），
        // 仅在真正取得门时才释放（不释放他人持有的门）。
        val acquired = requestGate.tryStart("exam-reminder")
        ExamReminder.cancelAll(activity)
        if (acquired) requestGate.finish("exam-reminder")
    }

    /** rememberSaveable Saver 使用的可保存快照（顺序固定：selectedTerm, manuallySelected）。 */
    fun savedState(): List<Any> = listOf(selectedTerm, manuallySelected)
}

/** 悄悄检查的结果：有效学期列表 + 目标学期 + 最新考试数据 + 窗口内全部学期数据。 */
internal data class SilentRefreshResult(
    val terms: List<String>,
    val target: String?,
    val latest: XmuTermExamSummary?,
    val summaries: Map<String, XmuTermExamSummary> = emptyMap(),
)
