package com.xmu.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * 桌面「今日课程」小卡片。
 *
 * 只读取 ScheduleWidgetData 的明文摘要（课程名/时间/地点），不触碰加密
 * 主缓存；课表刷新成功后由 MainActivity 调用 [refreshAll] 同步更新。
 */
class ScheduleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        onUpdateInternal(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ScheduleWidgetProvider::class.java),
            )
            if (ids.isNotEmpty()) {
                onUpdateInternal(context, manager, ids)
            }
        }

        private fun onUpdateInternal(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
        ) {
            // 只构建一次 RemoteViews 再应用到全部 widgetId：
            // 避免每个 id 重复读 prefs/解密/建视图（多 widget 时主线程重复 IO）。
            val views = try {
                buildRemoteViews(context)
            } catch (error: Throwable) {
                // 渲染异常兜底：某些 ROM/Launcher 上个别视图操作可能抛异常，
                // 退化为提示文本，避免桌面出现完全空白的小卡片。
                RemoteViews(
                    context.packageName,
                    R.layout.schedule_widget,
                ).apply {
                    setViewVisibility(R.id.widget_courses, android.view.View.GONE)
                    setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                    setTextViewText(R.id.widget_empty, "打开 App 刷新后查看今日课程")
                }
            }
            ids.forEach { widgetId ->
                manager.updateAppWidget(widgetId, views)
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(
                context.packageName,
                R.layout.schedule_widget,
            )
            val snapshot = ScheduleWidgetData.load(context)
            val enabled = AssistantSettings(context).widgetEnabled

            // 点击 Widget 打开 App 课表页
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("open_page", "课表")
            }
            val pending = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, pending)
            views.setOnClickPendingIntent(R.id.widget_courses, pending)
            views.setOnClickPendingIntent(R.id.widget_empty, pending)

            if (!enabled) {
                views.setViewVisibility(R.id.widget_courses, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                views.setTextViewText(R.id.widget_empty, "桌面小卡片已关闭，可在 App 策略页重新开启")
                return views
            }

            if (snapshot == null || snapshot.courses.isEmpty()) {
                views.setViewVisibility(R.id.widget_courses, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_empty,
                    context.getString(
                        if (snapshot == null) {
                            R.string.schedule_widget_no_data
                        } else {
                            R.string.schedule_widget_empty
                        },
                    ),
                )
                return views
            }

            views.setViewVisibility(R.id.widget_courses, android.view.View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, android.view.View.GONE)
            // 快照是生成当天的「今日课程」；跨日后（savedEpochDay != 今天或未知 0）不冒充今日：
            // 只渲染空态提示打开 App 刷新，**不再渲染昨日课程列表**（避免小卡片显示昨天的课误导用户）。
            val today = java.time.LocalDate.now().toEpochDay()
            val isTodaySnapshot = snapshot.savedEpochDay == today
            if (!isTodaySnapshot) {
                views.setViewVisibility(R.id.widget_courses, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_empty, android.view.View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_empty,
                    if (snapshot.week in 1..30) "第${snapshot.week}周 · 打开 App 刷新今日课程" else "打开 App 刷新今日课程",
                )
                return views
            }
            views.setTextViewText(
                R.id.widget_week,
                if (snapshot.week in 1..30) "第${snapshot.week}周 · 今日课程" else context.getString(R.string.schedule_widget_default_title),
            )

            // 先清空容器再 addView：标准实现 updateAppWidget 会重新 inflate，但部分 launcher
            // （一加 ColorOS 实测）会复用 view tree 导致 addView 累加、课程重复显示。
            views.removeAllViews(R.id.widget_courses)

            // 每门课一行：时间 课程名 地点（复用 views，保留点击跳转和标题配置）
            snapshot.courses
                .sortedWith(compareBy { it.startSection })
                .forEach { course ->
                    val row = RemoteViews(
                        context.packageName,
                        R.layout.schedule_widget_row,
                    )
                    row.setTextViewText(
                        R.id.row_time,
                        "${formatXmuTime(course.startTime)}—${formatXmuTime(course.endTime)}",
                    )
                    row.setTextViewText(R.id.row_course, course.courseName)
                    row.setTextViewText(R.id.row_location, course.location)
                    views.addView(R.id.widget_courses, row)
                }
            return views
        }
    }
}
