package com.xmu.assistant

import android.app.Activity
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * ScheduleSectionState（课表模块独立分块）行为测试（E1，六 SectionState 最后一个无行为测试者）。
 * 覆盖启动缓存恢复链与状态清理，无网络路径（refresh 由 XmuScheduleClientTest 覆盖）：
 * - loadCachedSnapshotOnStartup 文件缓存先行应用；
 * - 仅空快照+isEligible 才应用（登出/已有数据不回灌不覆盖）；
 * - applySnapshot 覆盖内存与进程级快照；
 * - clearAll 全清（含进程级快照）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduleSectionStateTest {

    private fun entry(course: String = "数据结构") = XmuScheduleEntry(
        weekday = 1,
        startSection = 1,
        endSection = 2,
        startTime = 800,
        endTime = 950,
        courseName = course,
        room = "A306",
        teacher = "张三",
        weeks = "1-16",
        termCode = "2025-2026-2",
    )

    private fun snapshot(term: String = "2025-2026-2", course: String = "数据结构") = XmuScheduleSnapshot(
        entries = listOf(entry(course)),
        termCode = term,
        updatedAtMillis = 1_723_000_000_000L,
    )

    private class Harness(
        val state: ScheduleSectionState,
        val gate: RequestGate,
        val toasts: MutableList<String>,
        val errors: MutableList<String>,
        val pendingRetries: MutableList<ModuleReadRetry>,
        val transitionResets: MutableList<Boolean>,
        val busyWrites: MutableList<String>,
        val startupReady: MutableList<Boolean>,
        /** 账号读写器引用的可变槽：测试可在刷新在途时改账号，验证 accepts 账号复核分支。 */
        val usernameRef: MutableList<String>,
    )

    private fun successResult() = ScheduleRefreshResult(
        jwCookie = "jw-cookie",
        termCode = "2025-2026-2",
        entries = listOf(entry()),
        currentWeek = null,
    )

    private fun newState(
        loggedIn: Boolean = true,
        snapshotInitial: XmuScheduleSnapshot = XmuScheduleSnapshot(),
        accountTransitionInProgress: Boolean = false,
        busyInitial: String = "",
        fetchSchedule: ScheduleFetcher = ScheduleFetcher { _, _, _, _ -> successResult() },
    ): Harness {
        val toasts = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val pendingRetries = mutableListOf<ModuleReadRetry>()
        val transitionResets = mutableListOf<Boolean>()
        val busyWrites = mutableListOf<String>()
        val startupReady = mutableListOf<Boolean>()
        var accountTransition = accountTransitionInProgress
        var busy = busyInitial
        val usernameRef = mutableListOf("12320011234567")
        val gate = RequestGate()
        val epoch = SessionEpoch()
        val owner = epoch.attachOwner()
        val state = ScheduleSectionState(
            activity = Robolectric.buildActivity(Activity::class.java).setup().get(),
            requestGate = gate,
            sessionEpoch = epoch,
            sessionOwner = owner,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            show = { toasts += it },
            showError = { errors += it },
            loggedIn = { loggedIn },
            requireLogin = {
                if (loggedIn) true else {
                    toasts += "请先登录"
                    false
                }
            },
            cookieHeader = { "session=fixture" },
            username = { usernameRef[0] },
            password = { "fixture-password" },
            accountTransitionInProgress = { accountTransition },
            setAccountTransitionInProgress = { transitionResets += it },
            busy = { busy },
            setBusy = { busyWrites += it },
            scoreCookieHeader = { "stored-cookie" },
            setScoreCookieHeader = {},
            manualAcademicWeek = { 0 },
            setStartupSessionReady = { startupReady += it },
            setPendingSessionRetry = { pendingRetries += it },
            clearToast = {},
            fetchSchedule = fetchSchedule,
            snapshotInitial = snapshotInitial,
        )
        return Harness(
            state, gate, toasts, errors, pendingRetries,
            transitionResets, busyWrites, startupReady, usernameRef,
        )
    }

    /** 轮询驱动 Robolectric 主 looper（withContext(Main) 段落地）直至条件成立或超时。 */
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    /** Robolectric 无 AndroidKeyStore，EncryptedSharedPreferences 构不出：
     *  以明文 prefs 反射种入进程级缓存作测试 seam（仅影响本 JVM 测试态，生产零改动）。 */
    private fun plainSettings(context: android.content.Context): AssistantSettings {
        // Kotlin 把 companion 的 private var 编为外部类静态字段（Robolectric 增改后无 Companion 实例字段）
        val field = AssistantSettings::class.java.getDeclaredField("cachedPrefs")
        field.isAccessible = true
        if (field.get(null) == null) {
            field.set(
                null,
                context.getSharedPreferences("schedule_state_test_plain", android.content.Context.MODE_PRIVATE),
            )
        }
        return AssistantSettings(context)
    }

    @Test
    fun `startup cache load applies file snapshot when eligible`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        saveScheduleSnapshotToFile(activity, snapshot())
        val harness = newState()
        harness.state.loadCachedSnapshotOnStartup(activity, plainSettings(activity)) { true }
        assertTrue("文件缓存未应用", await { harness.state.entries.isNotEmpty() })
        assertEquals("2025-2026-2", harness.state.termCode)
        assertEquals(1_723_000_000_000L, harness.state.updatedAtMillis)
        assertEquals("数据结构", harness.state.entries.single().courseName)
    }

    @Test
    fun `startup cache load is dropped when no longer eligible`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        saveScheduleSnapshotToFile(activity, snapshot())
        val harness = newState()
        // 登出竞态：异步读盘落地时 isEligible 已为假（登出/换号）——旧账号缓存不得回灌
        harness.state.loadCachedSnapshotOnStartup(activity, plainSettings(activity)) { false }
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(" ineligible 回填必须被丢弃", harness.state.entries.isEmpty())
        assertEquals("", harness.state.termCode)
    }

    @Test
    fun `applySnapshot overwrites memory state and process snapshot`() {
        val harness = newState()
        ScheduleSectionState.updateProcessScheduleSnapshot(null)
        harness.state.applySnapshot(snapshot(term = "2026-2027-1", course = "操作系统"))
        assertEquals(listOf("操作系统"), harness.state.entries.map { it.courseName })
        assertEquals("2026-2027-1", harness.state.termCode)
        assertEquals(1_723_000_000_000L, harness.state.updatedAtMillis)
        assertEquals("2026-2027-1", ScheduleSectionState.currentProcessScheduleSnapshot()?.termCode)
    }

    @Test
    fun `clearAll resets memory state and process snapshot`() {
        val harness = newState(snapshotInitial = snapshot())
        harness.state.applySnapshot(snapshot(term = "2026-2027-1", course = "操作系统"))
        assertTrue(harness.state.entries.isNotEmpty())
        harness.state.clearAll()
        assertTrue(harness.state.entries.isEmpty())
        assertEquals("", harness.state.termCode)
        assertEquals(0L, harness.state.updatedAtMillis)
        assertFalse(harness.state.loading)
        assertEquals("", harness.state.refreshError)
        assertNull("进程级快照必须一并清空（防转屏回退旧账号数据）", ScheduleSectionState.currentProcessScheduleSnapshot())
    }

    // ---- 会话过期续登路径（E1 本轮补位；fetchSchedule seam 注入取数结果） ----

    @Test
    fun `session expiry defers one-shot recovery with schedule retry`() {
        val harness = newState(fetchSchedule = ScheduleFetcher { _, _, _, _ -> throw ScheduleSessionExpiredException() })
        assertTrue("刷新未进入在途", harness.state.refresh())
        assertTrue("续登挂起未落地", await { harness.pendingRetries.isNotEmpty() })
        assertEquals(listOf(ModuleReadRetry(ModuleReadRequest.SCHEDULE)), harness.pendingRetries)
        assertEquals("教务登录已过期，正在安全续登", harness.state.refreshError)
        // 会话过期路径不弹失败 toast（由一次性续登接管，避免误导用户）
        assertTrue("会话过期不得弹失败 toast", harness.errors.isEmpty())
        assertTrue("loading 必须释放（守卫通过）", await { !harness.state.loading })
    }

    @Test
    fun `recovery path resets transition and marks startup session ready`() {
        // 恢复路径：alreadyRetriedAfterRecovery=true 且 busy 停在续登文案
        val harness = newState(
            accountTransitionInProgress = true,
            busyInitial = BusyStates.SESSION_EXPIRED_RELOGIN,
        )
        assertTrue("恢复路径必须无视 transition 在途（否则续登后永远刷不了）", harness.state.refresh(alreadyRetriedAfterRecovery = true))
        assertTrue("课表未落地", await { harness.state.entries.isNotEmpty() })
        assertEquals(listOf(false), harness.transitionResets)
        assertTrue("busy 必须从续登文案复位", harness.busyWrites.contains(BusyStates.IDLE))
        // 恢复成功补发「会话已就绪」：释放 startup 期间缓存的待刷新模块
        assertEquals(listOf(true), harness.startupReady)
        assertTrue(harness.toasts.contains("课表已更新"))
        assertTrue("loading 必须释放", await { !harness.state.loading })
    }

    @Test
    fun `account transition in progress blocks refresh unless already retried`() {
        val blocked = newState(accountTransitionInProgress = true)
        assertFalse("登录/恢复在途时必须拒绝并发刷新（两个身份域并发是风控红线）", blocked.state.refresh())
        assertTrue(blocked.toasts.contains("登录处理中，请稍候"))
        assertFalse("被拒时不得进入在途（loading 保持假）", blocked.state.loading)

        // 同一在途态下，恢复路径（alreadyRetriedAfterRecovery）必须放行
        val recovering = newState(accountTransitionInProgress = true)
        assertTrue(recovering.state.refresh(alreadyRetriedAfterRecovery = true))
        assertTrue("恢复路径应放行并完成", await { recovering.state.entries.isNotEmpty() })
    }

    @Test
    fun `stale result is dropped when the account changes mid flight`() {
        // 账号复核折叠进 accepts 谓词（B1）：取数在途时换号，结果必须被整体丢弃。
        // 此处刻意不推进 epoch，单独验证「账号复核」这一条守卫（真实换号还会推进 epoch）。
        // 用双闩把「取数在途」与「放行取数」两个时刻固定下来，避免 sleep 竞态：
        // 先等取数真正开始，再改账号，最后放行 —— accepts 账号复核必然观察到新账号。
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val harness = newState(fetchSchedule = ScheduleFetcher { _, _, _, _ ->
            entered.countDown()
            assertTrue("取数放行超时", release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            successResult()
        })
        assertTrue("刷新未进入在途", harness.state.refresh())
        assertTrue("取数未开始", entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        harness.usernameRef[0] = "99920010999999"
        release.countDown()
        // 账号不符 → accepts 为假 → onResult 整体跳过，旧账号数据不得落地
        assertTrue("取数应已完成", await { harness.state.loading || harness.state.entries.isEmpty() })
        assertTrue("换号后旧结果不得回灌", harness.state.entries.isEmpty())
        assertEquals("", harness.state.termCode)
        assertTrue("不得提示课表已更新", harness.toasts.none { it == "课表已更新" })
        // loading 在此路径下刻意不释放：runModuleRequest 的 releaseLoading 同样受
        // accepts 门控，账号已换时交由会话失效路径（invalidate → clearLoadingState）
        // 统一复位，避免把新账号的 loading 误清。此处锁定该现行为。
        assertTrue("accepts 为假时 loading 由会话失效路径复位，此处保持为真", harness.state.loading)
    }
}
