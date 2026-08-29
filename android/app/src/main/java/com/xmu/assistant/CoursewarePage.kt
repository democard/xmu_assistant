package com.xmu.assistant

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoursewarePage(
    courses: List<CourseSummary>,
    selectedCourse: CourseSummary?,
    coursewareItems: List<CoursewareUiItem>,
    selectedIds: Set<String>,
    onRefreshCourses: () -> Unit,
    onCourseSelected: (CourseSummary) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onRefreshCourseware: () -> Unit,
    onOpenPlatform: () -> Unit,
    downloadProgress: String,
    downloadLoading: Boolean,
    coursesLoading: Boolean,
    coursesRefreshError: String,
    coursesUpdatedAtMillis: Long,
    coursewareLoading: Boolean,
    coursewareRefreshError: String,
    coursewareUpdatedAtMillis: Long,
    loggedIn: Boolean,
) {
    // 键用课程 id 集合而非列表实例：内容不变的刷新会产生新 list 实例，
    // 以实例为键会把用户筛选静默重置为「全部」
    val courseIds = remember(courses) { courses.map { it.id } }
    var selectedYear by remember(courseIds) { mutableStateOf("全部") }
    var selectedSemester by remember(courseIds) { mutableStateOf("全部") }
    val years = remember(courses) { courseYears(courses) }
    val semesters = listOf("全部", "第一学期", "第二学期", "第三学期")
    val filteredCourses = remember(courses, selectedYear, selectedSemester) {
        filterCourses(courses, selectedYear, selectedSemester)
    }
    val counts = remember(coursewareItems) { coursewareCounts(coursewareItems) }
    val downloadEnabled = remember(selectedIds, downloadLoading) {
        coursewareDownloadEnabled(selectedIds, downloadLoading)
    }
    // LazyColumn 懒加载：视口外的 item 不会被组合，BringIntoViewRequester 对未组合节点
    // 静默失效，必须用 LazyListState 按 index 滚动。
    // 布局约定：index 0 = 课程选择区 SectionCard；其后每门课一个独立 item（懒加载）；
    // “当前课程”标题紧跟课程列表，index = 1 + filteredCourses.size。
    // rememberSaveable：转屏后保留滚动位置（reveal 定位也复用同一 state）。
    val coursewareListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val coursewareHeaderItemIndex = 1 + filteredCourses.size
    var coursewareRevealRequestId by remember { mutableIntStateOf(0) }

    fun selectCourseAndReveal(course: CourseSummary) {
        onCourseSelected(course)
        coursewareRevealRequestId += 1
    }

    LaunchedEffect(coursewareRevealRequestId) {
        if (coursewareRevealRequestId > 0 && selectedCourse != null) {
            // 等待列表至少接受标题 item：仅在「未选课程 → 选中课程」时列表从 1 项
            // 变为 ≥2+课程数 项，此时若立即滚动会越界；切课场景 totalItemsCount 沿用旧值
            // 立即通过（标题 index 恒为 1+课程数，无需真正等待新标题组合）。
            snapshotFlow { coursewareListState.layoutInfo.totalItemsCount }
                .first { it > coursewareHeaderItemIndex }
            coursewareListState.animateScrollToItem(coursewareHeaderItemIndex)
        }
    }

    LazyColumn(
        state = coursewareListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard(stringResource(R.string.courseware_page_title)) {
                Button(onClick = onRefreshCourses, enabled = !coursesLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (coursesLoading) {
                            stringResource(R.string.courseware_page_courses_refreshing)
                        } else {
                            stringResource(R.string.courseware_page_courses_refresh)
                        },
                    )
                }
                RefreshStateBanner(loading = coursesLoading, errorMessage = coursesRefreshError, hasData = courses.isNotEmpty())
                if (coursesUpdatedAtMillis > 0L) {
                    Text(
                        stringResource(R.string.courseware_page_courses_updated, formatMonitorTime(coursesUpdatedAtMillis)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (courses.isEmpty()) {
                    // 空态三档之「未登录」：给出登录引导（未登录时点刷新课程只会得到
                    // 弹出的请先登录提示，页面自身无解释）
                    if (!loggedIn) {
                        EmptyState(
                            stringResource(R.string.courseware_page_empty_not_logged_in_title),
                            stringResource(R.string.courseware_page_empty_not_logged_in_body),
                        )
                    } else {
                        EmptyState(
                            stringResource(R.string.courseware_page_empty_no_selection_title),
                            stringResource(R.string.courseware_page_empty_no_selection_body),
                        )
                    }
                } else {
                    OptionRow(stringResource(R.string.courseware_page_filter_year), years, selectedYear) { selectedYear = it }
                    OptionRow(stringResource(R.string.courseware_page_filter_semester), semesters, selectedSemester) { selectedSemester = it }
                    Text(stringResource(R.string.courseware_page_course_list_label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (filteredCourses.isEmpty()) {
                        EmptyState(
                            stringResource(R.string.courseware_page_no_match_title),
                            stringResource(R.string.courseware_page_no_match_body),
                        )
                    }
                }
            }
        }
        // 课程列表拆成独立 item 实现真懒加载：上百门课时不再整块一次性组合。
        // 注意：不用显式 key。课程 id 跨学期可能重复（同一门课不同学期），
        // 若用内容 key 会重复导致 LazyColumn 崩溃；默认 index key 绝对安全。
        if (courses.isNotEmpty() && filteredCourses.isNotEmpty()) {
            items(count = filteredCourses.size) { index ->
                val course = filteredCourses[index]
                val selected = selectedCourse?.id == course.id
                if (selected) {
                    Button(onClick = { selectCourseAndReveal(course) }, modifier = Modifier.fillMaxWidth()) { Text(course.displayName) }
                } else {
                    OutlinedButton(onClick = { selectCourseAndReveal(course) }, modifier = Modifier.fillMaxWidth()) { Text(course.displayName) }
                }
            }
        }
        if (selectedCourse != null) {
            item {
                Text(
                    stringResource(R.string.courseware_page_current_course, selectedCourse.displayName),
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(
                    stringResource(
                        R.string.courseware_page_summary,
                        coursewareItems.size,
                        counts.direct,
                        counts.entry,
                        counts.limited,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloadProgress.isNotBlank()) {
                item {
                    Text(downloadProgress, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Button(onClick = onRefreshCourseware, enabled = !coursewareLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (coursewareLoading) {
                            stringResource(R.string.courseware_page_refreshing)
                        } else {
                            stringResource(R.string.courseware_page_refresh)
                        },
                    )
                }
            }
            item {
                RefreshStateBanner(
                    loading = coursewareLoading,
                    errorMessage = coursewareRefreshError,
                    hasData = coursewareItems.isNotEmpty(),
                )
            }
            if (coursewareUpdatedAtMillis > 0L) {
                item {
                    Text(
                        stringResource(R.string.courseware_page_updated, formatMonitorTime(coursewareUpdatedAtMillis)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onDownload, enabled = downloadEnabled, modifier = Modifier.weight(1f)) {
                        Text(
                            if (downloadLoading) {
                                stringResource(R.string.courseware_page_downloading)
                            } else {
                                stringResource(R.string.courseware_page_download, selectedIds.size)
                            },
                        )
                    }
                    OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.courseware_page_select_all))
                    }
                }
            }
            item {
                OutlinedButton(onClick = onOpenPlatform, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.courseware_page_open_platform))
                }
            }
            if (coursewareItems.isEmpty()) {
                item {
                    EmptyState(
                        stringResource(R.string.courseware_page_empty_title),
                        stringResource(R.string.courseware_page_empty_body),
                    )
                }
            } else {
                // 注意：不使用显式 key。课件 id 可能因平台返回重复/空而碰撞，
                // 若用内容 key 会重复导致 LazyColumn 崩溃；默认 index key 绝对安全。
                items(count = coursewareItems.size) { index ->
                    val item = coursewareItems[index]
                    CoursewareItemCard(item, checked = item.id in selectedIds, onToggle = { onToggle(item.id) })
                }
            }
        }
    }
}
