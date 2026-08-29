package com.xmu.assistant

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 快捷设置磁贴：不打开 App 一键开/关签到监控。
 *
 * 与 App 内开关走同一启停链路：
 * - 开：monitorDesired = true + startForegroundService(RollcallMonitorService)
 * - 关：requestInvalidateActiveRun() + monitorDesired = false + stopService
 * - 未登录（cookie 为空）：磁贴置灰提示，点击带去首页登录（磁贴环境无输入界面）
 *
 * 状态判定复用 shouldStartMonitorWorker（monitorDesired && cookie 非空），
 * 与 RollcallMonitorService.onStartCommand 的启动门同一契约，不会出现
 * 「磁贴显示运行中而服务实际已停」的口径分裂。App 内启动/暂停后经
 * [requestResync] 请求磁贴重渲染（磁贴可见时系统回调 onStartListening）。
 */
class MonitorControlTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        renderTile()
    }

    override fun onClick() {
        super.onClick()
        val settings = AssistantSettings(applicationContext)
        if (settings.cookieHeader.isBlank()) {
            // 未登录无法监控：磁贴无输入界面，带去首页登录后再开
            openMainActivity()
            renderTile()
            return
        }
        if (shouldStartMonitorWorker(settings.monitorDesired, settings.cookieHeader)) {
            // 关闭：与 App 内 onStopMonitor 同链路（App 内另有 UI 记账/静止等待，磁贴侧不需要）
            RollcallMonitorService.requestInvalidateActiveRun()
            settings.monitorDesired = false
            stopService(Intent(this, RollcallMonitorService::class.java))
        } else {
            // 开启：与 App 内 onStartMonitor 同链路
            settings.monitorDesired = true
            startForegroundService(Intent(this, RollcallMonitorService::class.java))
        }
        renderTile()
    }

    // API<34 无 PendingIntent 变体（34+ 新增），旧重载是唯一途径：抑制弃用 lint
    // 而非改行为（方法内新 API 分支已走 startActivityAndCollapse(PendingIntent)）。
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("open_page", "首页")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderTile() {
        val tile = qsTile ?: return
        val settings = AssistantSettings(applicationContext)
        tile.state = when {
            settings.cookieHeader.isBlank() -> Tile.STATE_UNAVAILABLE
            shouldStartMonitorWorker(settings.monitorDesired, settings.cookieHeader) -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    companion object {
        /** App 内启动/暂停监控后调用，请求系统在磁贴可见时重渲染（幂等，失败静默）。 */
        fun requestResync(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, MonitorControlTileService::class.java),
                )
            }
        }
    }
}
