package com.xmu.assistant

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1800dp")
class AndroidUiOptimizationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `maskHomeUsername handles short and standard student ids safely`() {
        assertEquals("123", maskHomeUsername("123"))
        assertEquals("12**6", maskHomeUsername("123456"))
        assertEquals("202****5678", maskHomeUsername("202612345678"))
    }

    @Test
    fun `schedule override store applies edits to entries for ICS export`() {
        val app = RuntimeEnvironment.getApplication()
        val store = ScheduleOverrideStore(app)
        store.clear()
        val base = XmuScheduleGroup(1, 1, 2, 800, 935, "数据结构", listOf("A101"), listOf("王老师"), "1-16周")
        store.save("20261", base, ScheduleCourseOverride(3, 5, 6, "坤銮楼 B207", "王老师", "2-15周"))
        val updated = store.applyToEntries(
            "20261",
            listOf(XmuScheduleEntry(1, 1, 2, 800, 935, "数据结构", "A101", "王老师", "1-16周", "20261")),
        ).single()
        assertEquals(3, updated.weekday)
        assertEquals(5, updated.startSection)
        assertEquals(6, updated.endSection)
        assertEquals("坤銮楼 B207", updated.room)
        assertEquals("2-15周", updated.weeks)
        store.clear()
    }

    @Test
    fun `home page exposes today overview and monitor card without one tap sign in`() {
        var navigated = ""
        composeRule.setContent {
            XmuMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HomePage(
                        username = "202612345678", password = "", loggedIn = true, monitorRunning = false,
                        accountTransitionInProgress = false, monitorTransitionInProgress = false,
                        monitorStatus = "未启动", monitorLastCheck = "10:00:00",
                        monitorFailureCount = 0, monitorLastError = "", rollcallSettings = RollcallSettings(),
                        onUsername = {}, onPassword = {}, onLogin = {}, onLogout = {},
                        onStartMonitor = {}, onStopMonitor = {},
                        onOpenBackgroundSettings = {}, onNavigate = { navigated = it },
                    )
                }
            }
        }
        composeRule.onNodeWithText("一键签到").assertDoesNotExist()
        composeRule.onNodeWithText("自动签到快捷开关").assertDoesNotExist()
        composeRule.onNodeWithText("今日课程").assertExists()
        composeRule.onNodeWithText("监控设置").assertExists()
        composeRule.onNodeWithText("通知").performClick()
        composeRule.runOnIdle { assertEquals("通知", navigated) }
    }

    @Test
    fun `rollcall status page renders 3 distinct branches and absent history badge`() {
        val events = listOf(
            RollcallEvent("d1", "软件工程", "李老师", "数字签到", "进行中", numberCode = "3819", ownStatus = "已签"),
            RollcallEvent("r1", "高等数学", "陈老师", "雷达签到", "进行中", courseId = "c-radar"),
            RollcallEvent("q1", "大学英语", "张老师", "二维码签到", "进行中"),
        )
        val history = listOf(
            RollcallHistoryItem(
                "h1", "c1", "数据结构", "雷达签到", "11-18 10:00",
                progress = StudentRollcallProgress(64, 58, 6, 0, 5800.0 / 64.0, true),
                sortKeyMillis = null, ownStatus = STATUS_SIGNED,
            ),
            RollcallHistoryItem("h2", "c2", "操作系统", "数字签到", "11-16 08:00", null, "缺勤", numberCode = "4521"),
        )

        composeRule.setContent {
            XmuMobileTheme {
                Box(Modifier.width(390.dp).height(1800.dp)) {
                    RollcallStatusPage(
                        events = events,
                        openedEventId = "",
                        loading = false,
                        refreshError = "",
                        updatedAtMillis = 1L,
                        historyItems = history,
                        historyLoading = false,
                        historyError = "",
                        historyUpdatedAtMillis = 1L,
                        loggedIn = true,
                        monitorRunning = true,
                        onRefresh = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("监控中").assertExists()
        composeRule.onNodeWithText("签到码 3819").assertDoesNotExist()
        composeRule.onNodeWithText("数字签到").assertExists()
        composeRule.onNodeWithText("雷达签到").assertExists()
        composeRule.onNodeWithText("二维码签到").assertExists()
        composeRule.onNodeWithText("需扫码").assertDoesNotExist()
        composeRule.onNodeWithText("复制").assertDoesNotExist()
        composeRule.onNodeWithText("雷达坐标").assertDoesNotExist()
        composeRule.onNodeWithText("最近 2 次").assertExists()
        composeRule.onNodeWithText("缺勤").assertExists()
        composeRule.onNodeWithText("!").assertExists()
        composeRule.onNodeWithText("已签").assertExists()
        composeRule.onNodeWithText("58 / 64 人").assertExists()
    }

    @Test
    fun `schedule page opens course picker modal sheet and enters edit sheet`() {
        val today = java.time.LocalDate.now()
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        val calendar = XmuAcademicCalendar("20261", "2026-2027", "第一学期", monday, monday.plusWeeks(18))
        val course = XmuScheduleEntry(today.dayOfWeek.value, 1, 2, 800, 935, "数据结构与算法", "学武楼 A305", "王老师", "1-16周", "20261")
        composeRule.setContent {
            XmuMobileTheme {
                SchedulePage(
                    entries = listOf(course),
                    termCode = "20261",
                    updatedAtMillis = 0L,
                    loading = false,
                    refreshError = "",
                    loggedIn = true,
                    onRefresh = {},
                    inferredCalendar = calendar,
                    pinnedHeader = true,
                )
            }
        }
        composeRule.onNodeWithText("修改安排").performClick()
        composeRule.onNodeWithText("修改课程安排").assertExists()
        composeRule.onNodeWithText("编辑 ›").performClick()
        composeRule.onNodeWithText("保存修改").assertExists()
    }

    @Test
    fun `courseware page opens modal course sheet and selects course`() {
        val courses = listOf(
            CourseSummary("c-1", "高等数学", "2026-2027 第一学期", "2026-1"),
            CourseSummary("c-2", "线性代数", "2026-2027 第一学期", "2026-1"),
        )
        var selectedTitle = ""
        composeRule.setContent {
            XmuMobileTheme {
                CoursewarePreviewPage(
                    courses = courses,
                    selectedCourse = courses.first(),
                    coursewareItems = emptyList(),
                    selectedIds = emptySet(),
                    onRefreshCourses = {},
                    onCourseSelected = { selectedTitle = it.title },
                    onToggle = {},
                    onSelectAll = {},
                    onDownload = {},
                    onRefreshCourseware = {},
                    onOpenPlatform = {},
                    downloadProgress = "",
                    downloadLoading = false,
                    coursesLoading = false,
                    coursesRefreshError = "",
                    coursewareLoading = false,
                    coursewareRefreshError = "",
                    loggedIn = true,
                )
            }
        }
        composeRule.onNodeWithText("换课 ▾").performClick()
        composeRule.onNodeWithText("选择课程").assertExists()
        composeRule.onNodeWithText("当前").assertExists()
        composeRule.onNodeWithText("线性代数").performClick()
        composeRule.runOnIdle { assertEquals("线性代数", selectedTitle) }
        composeRule.onNodeWithText("选择课程").assertDoesNotExist()
    }
}
