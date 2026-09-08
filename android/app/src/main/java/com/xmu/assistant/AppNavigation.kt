package com.xmu.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class AppDestination(val page: String, val label: String, val icon: Int, val description: String = "")

internal val primaryDestinations = listOf(
    AppDestination("首页", "首页", R.drawable.ic_nav_home),
    AppDestination("课表", "课表", R.drawable.ic_nav_calendar),
    AppDestination("成绩", "成绩", R.drawable.ic_nav_scores),
    AppDestination("签到情况", "签到", R.drawable.ic_nav_check),
    AppDestination("更多", "更多", R.drawable.ic_nav_grid),
)

internal val moreDestinations = listOf(
    AppDestination("课程课件", "课程课件", R.drawable.ic_nav_folder, "查找课程 · 批量下载课件"),
    AppDestination("考试安排", "考试安排", R.drawable.ic_nav_calendar, "考试时间、考场与历史安排"),
    AppDestination("通知", "通知", R.drawable.ic_nav_bell, "系统、微信与邮件提醒"),
    AppDestination("策略", "策略与外观", R.drawable.ic_nav_settings, "监控策略 · 主题 · 桌面小卡片"),
    AppDestination("教程", "使用教程", R.drawable.ic_nav_help, "功能说明与常见问题"),
)

internal fun primaryPage(page: String): String =
    if (page == "专业排名") "成绩" else if (primaryDestinations.any { it.page == page }) page else "更多"

internal fun parentPage(page: String): String =
    if (page == "专业排名") "成绩" else if (moreDestinations.any { it.page == page }) "更多" else "首页"

/** Keep each page's small UI state while its composition is absent; discard it on account changes. */
@Composable
internal fun PageStateHost(page: String, owner: String, content: @Composable () -> Unit) {
    key(owner) {
        val holder = rememberSaveableStateHolder()
        holder.SaveableStateProvider(page, content)
    }
}

@Composable
internal fun AppBackNavigation(page: String, onSelected: (String) -> Unit) {
    BackHandler(enabled = page != "首页") { onSelected(parentPage(page)) }
}

@Composable
internal fun AppNavigationBar(
    selected: String,
    notificationSettings: NotificationSettings,
    downloadingCount: Int,
    onSelected: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            primaryDestinations.forEach { destination ->
                NavigationBarItem(
                    modifier = Modifier.semantics {
                        if (destination.page == "更多") {
                            stateDescription = when {
                                downloadingCount > 0 -> "$downloadingCount 个文件下载中"
                                notificationSettingsMissing(notificationSettings) -> "有通知渠道待配置"
                                else -> "校园工具与设置"
                            }
                        }
                    },
                    selected = primaryPage(selected) == destination.page,
                    onClick = { if (selected != destination.page) onSelected(destination.page) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = themePrimarySoft(),
                        selectedIconColor = themePrimary(),
                        selectedTextColor = themePrimary(),
                    ),
                    icon = {
                        BadgedBox(badge = {
                            if (destination.page == "更多") {
                                when {
                                    downloadingCount > 0 -> Badge { Text(if (downloadingCount > 99) "99+" else "$downloadingCount") }
                                    notificationSettingsMissing(notificationSettings) -> Badge { Text("!") }
                                }
                            }
                        }) {
                            Icon(painterResource(destination.icon), contentDescription = null, modifier = Modifier.size(23.dp))
                        }
                    },
                    label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
internal fun MorePage(
    notificationSettings: NotificationSettings,
    downloadingCount: Int,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("校园工具", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("学习工具与偏好设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
        moreDestinations.forEach { destination ->
            Surface(
                onClick = { onSelected(destination.page) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = themePrimarySoft()) {
                        Icon(painterResource(destination.icon), null, tint = themePrimary(), modifier = Modifier.padding(12.dp).size(24.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(destination.label, fontWeight = FontWeight.Bold)
                        Text(destination.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when {
                            destination.page == "通知" && notificationSettingsMissing(notificationSettings) ->
                                Text("有通知渠道待配置", style = MaterialTheme.typography.labelMedium, color = themeWarning())
                            destination.page == "课程课件" && downloadingCount > 0 ->
                                Text("$downloadingCount 个文件下载中", style = MaterialTheme.typography.labelMedium, color = themePrimary())
                        }
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
