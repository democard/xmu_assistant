package com.xmu.assistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 外观主题模式常量：跟随系统。 */
const val THEME_MODE_SYSTEM = "system"
/** 外观主题模式常量：强制浅色。 */
const val THEME_MODE_LIGHT = "light"
/** 外观主题模式常量：强制深色。 */
const val THEME_MODE_DARK = "dark"

/**
 * 加密设置存储。内部对 EncryptedSharedPreferences 做进程级单例缓存：
 * MasterKey 构建与加密 prefs 初始化较慢（每次实例化都重建），
 * 缓存后各模块（Activity/监控服务/Widget）首次访问才初始化一次，
 * 冷启动与后台唤醒更快。
 */
class AssistantSettings private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: android.content.SharedPreferences = encryptedPrefs(appContext)
    private val autoLoginPolicyStore = AutoLoginPolicyStore(
        readStored = { prefs.getString("auto_login_policy", null) },
        readCookie = { cookieHeader },
        writeStored = { value -> prefs.edit().putString("auto_login_policy", value).apply() },
    )

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    var cookieHeader: String
        get() = prefs.getString("cookie_header", "") ?: ""
        set(value) = prefs.edit().putString("cookie_header", value).apply()

    var autoLoginPolicy: AutoLoginPolicy
        get() = autoLoginPolicyStore.policy
        set(value) { autoLoginPolicyStore.policy = value }

    var scoreCookieHeader: String
        get() = prefs.getString("score_cookie_header", "") ?: ""
        set(value) = prefs.edit().putString("score_cookie_header", value).apply()

    var scoreRecordsJson: String
        get() = prefs.getString("score_records_json", "") ?: ""
        set(value) = prefs.edit().putString("score_records_json", value).apply()

    var scoreUpdatedAtMillis: Long
        get() = prefs.getLong("score_updated_at_millis", 0L)
        set(value) = prefs.edit().putLong("score_updated_at_millis", value).apply()

    var academicCacheJson: String
        get() = prefs.getString("academic_cache_json", "") ?: ""
        set(value) = prefs.edit().putString("academic_cache_json", value).apply()

    var scheduleCacheJson: String
        get() = prefs.getString("schedule_cache_json", "") ?: ""
        set(value) = prefs.edit().putString("schedule_cache_json", value).apply()

    /**
     * 外观主题模式："system" 跟随系统 / "light" 强制浅色 / "dark" 强制深色。
     * 默认跟随系统，保持旧行为。
     */
    var themeMode: String
        get() = prefs.getString("theme_mode", THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    /** 考试提醒总开关（默认关）。 */
    var examReminderEnabled: Boolean
        get() = prefs.getBoolean("exam_reminder_enabled", false)
        set(value) = prefs.edit().putBoolean("exam_reminder_enabled", value).apply()

    /** 考试提前提醒分钟数（0-60，默认 30）。 */
    var examReminderAdvanceMinutes: Int
        get() = prefs.getInt("exam_reminder_advance_minutes", 30)
        set(value) = prefs.edit().putInt("exam_reminder_advance_minutes", value.coerceIn(0, 60)).apply()

    /** 是否使用全屏通知（闹钟式锁屏提醒），需额外授权。 */
    var examReminderFullScreen: Boolean
        get() = prefs.getBoolean("exam_reminder_full_screen", false)
        set(value) = prefs.edit().putBoolean("exam_reminder_full_screen", value).apply()

    var monitorDesired: Boolean
        get() = prefs.getBoolean("monitor_desired", false)
        set(value) = prefs.edit().putBoolean("monitor_desired", value).apply()

    var monitorLastCheckMillis: Long
        get() = prefs.getLong("monitor_last_check_millis", 0L)
        set(value) = prefs.edit().putLong("monitor_last_check_millis", value).apply()

    var monitorConsecutiveFailures: Int
        get() = prefs.getInt("monitor_consecutive_failures", 0)
        set(value) = prefs.edit().putInt("monitor_consecutive_failures", value).apply()

    var monitorLastError: String
        get() = prefs.getString("monitor_last_error", "") ?: ""
        set(value) = prefs.edit().putString("monitor_last_error", value).apply()

    fun recordMonitorSuccess() {
        prefs.edit()
            .putLong("monitor_last_check_millis", System.currentTimeMillis())
            .putInt("monitor_consecutive_failures", 0)
            .putString("monitor_last_error", "")
            .apply()
    }

    fun recordMonitorFailure(error: String) {
        prefs.edit()
            .putInt("monitor_consecutive_failures", monitorConsecutiveFailures + 1)
            .putString("monitor_last_error", error)
            .apply()
    }

    fun markLoginSucceeded() {
        autoLoginPolicy = AutoLoginPolicy.ENABLED
        // 登录态镜像置 true：Widget 周期 worker 的 CAS 续登复核依据（见属性注释）
        widgetWorkerLoggedIn = true
    }

    fun markAutoLoginFailed() { autoLoginPolicy = AutoLoginPolicy.BLOCKED }

    fun markUserLoggedOut() { autoLoginPolicy = AutoLoginPolicy.USER_LOGGED_OUT }

    fun rollcall(): RollcallSettings = RollcallSettings(
        pollIntervalSeconds = prefs.getInt("poll_interval_seconds", 30).coerceIn(1, 300),
        autoAnswerNumber = prefs.getBoolean("auto_answer_number", false),
        autoAnswerRadar = prefs.getBoolean("auto_answer_radar", false),
    )

    fun saveRollcall(settings: RollcallSettings) {
        prefs.edit()
            .putInt("poll_interval_seconds", settings.pollIntervalSeconds.coerceIn(1, 300))
            .putBoolean("auto_answer_number", settings.autoAnswerNumber)
            .putBoolean("auto_answer_radar", settings.autoAnswerRadar)
            .apply()
    }

    /**
     * 桌面小卡片开关。布尔开关非敏感，额外双写一份到明文镜像 prefs：
     * Widget 渲染路径（onUpdate 主线程）读明文镜像，避免首触点在主线程
     * 初始化 Keystore/加密 prefs（体检记录：ScheduleWidgetProvider:70）。
     */
    var widgetEnabled: Boolean
        get() = prefs.getBoolean("widget_enabled", true)
        set(value) {
            prefs.edit().putBoolean("widget_enabled", value).apply()
            appContext.getSharedPreferences(WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WIDGET_ENABLED_MIRROR_KEY, value)
                .apply()
        }

    /**
     * 登录态镜像（布尔，非敏感）。写点：[markLoginSucceeded] → true、
     * [clearSession] / clearLoggedOutUi → false。周期 Widget 刷新 worker 运行在
     * 后台进程、读不到组合内存登录态，而凭据（学号/密码）按设计登出残留——
     * worker 不复核登录态会以残留凭据发起教务 CAS 登录（登出后幽灵登录 =
     * 风控暴露，同款守卫见 ScoreSectionState.refresh）。与 widgetEnabled 同款
     * 双写明文镜像：worker 侧只读镜像，不触碰加密 prefs（Keystore）。
     */
    var widgetWorkerLoggedIn: Boolean
        get() = prefs.getBoolean("widget_worker_logged_in", true)
        set(value) {
            prefs.edit().putBoolean("widget_worker_logged_in", value).apply()
            appContext.getSharedPreferences(WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(WIDGET_LOGGED_IN_MIRROR_KEY, value)
                .apply()
        }

    /**
     * 手动指定的教学周（0=纯自动，1..18=用户手动校准基准），**按学期分别存储**。
     * 手动指定只是「校准基准」：系统据此反推开学日，之后仍按日期自动推进周次
     * （明天同周、下周自动 +1），直到再次手动校准或清除校准。
     * 换学期后各学期互不干扰：**不回退旧版单值**（旧学期的手动基准不得错误作用到新学期）。
     */
    fun manualAcademicWeek(termCode: String): Int {
        if (termCode.isBlank()) return prefs.getInt("manual_academic_week", 0).coerceIn(0, 25)
        return manualWeekMap()[termCode] ?: 0
    }

    fun setManualAcademicWeek(termCode: String, week: Int) {
        if (termCode.isBlank()) {
            prefs.edit().putInt("manual_academic_week", week.coerceIn(0, 25)).apply()
            return
        }
        val map = manualWeekMap().toMutableMap()
        if (week <= 0) map.remove(termCode) else map[termCode] = week.coerceIn(0, 25)
        @Suppress("UNCHECKED_CAST")
        val json = org.json.JSONObject(map as Map<Any?, Any?>)
        prefs.edit().putString("manual_academic_week_by_term", json.toString()).apply()
    }

    private fun manualWeekMap(): Map<String, Int> = runCatching {
        val raw = prefs.getString("manual_academic_week_by_term", "") ?: ""
        if (raw.isBlank()) return@runCatching emptyMap()
        val obj = org.json.JSONObject(raw)
        obj.keys().asSequence().mapNotNull { key ->
            obj.optInt(key, 0).takeIf { it in 1..25 }?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 清空所有学期的手动周次（退出登录时调用）。 */
    fun clearManualAcademicWeeks() {
        prefs.edit()
            .remove("manual_academic_week_by_term")
            .remove("manual_academic_week")
            .apply()
    }

    /**
     * 清理旧版加密 prefs 里的课表缓存残留。
     * 课表缓存已迁移到明文文件（schedule_cache.json），加密残留只在
     * 迁移成功且读回校验通过后删除一次，避免重复迁移和加密数据长期残留。
     */
    fun clearScheduleCacheLegacyPref() {
        prefs.edit().remove("schedule_cache_json").apply()
    }

    fun notifications(): NotificationSettings = NotificationSettings(
        systemEnabled = prefs.getBoolean("notify_system", true),
        pushPlusEnabled = prefs.getBoolean("notify_pushplus", false),
        pushPlusToken = prefs.getString("pushplus_token", "") ?: "",
        qqMailEnabled = prefs.getBoolean("notify_qq_mail", false),
        qqMailSender = prefs.getString("qq_sender", "") ?: "",
        qqMailPassword = prefs.getString("qq_password", "") ?: "",
        qqMailRecipient = prefs.getString("qq_recipient", "") ?: "",
        qqMailPorts = prefs.getString("qq_ports", "465,587") ?: "465,587",
    )

    fun saveNotifications(settings: NotificationSettings) {
        prefs.edit()
            .putBoolean("notify_system", settings.systemEnabled)
            .putBoolean("notify_pushplus", settings.pushPlusEnabled)
            .putString("pushplus_token", settings.pushPlusToken)
            .putBoolean("notify_qq_mail", settings.qqMailEnabled)
            .putString("qq_sender", settings.qqMailSender)
            .putString("qq_password", settings.qqMailPassword)
            .putString("qq_recipient", settings.qqMailRecipient)
            .putString("qq_ports", settings.qqMailPorts.ifBlank { "465,587" })
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove("cookie_header")
            .remove("score_cookie_header")
            .remove("score_records_json")
            .remove("score_updated_at_millis")
            .remove("academic_cache_json")
            .remove("schedule_cache_json")
            .remove("monitor_desired")
            .remove("monitor_last_check_millis")
            .remove("monitor_consecutive_failures")
            .remove("monitor_last_error")
            .apply()
        // 会话终止（登出/恢复失败清理）：镜像置 false，Widget 周期 worker
        // 绝不以残留凭据发起 CAS（登出后幽灵登录，见 widgetWorkerLoggedIn 注释）
        widgetWorkerLoggedIn = false
    }

    companion object {
        /** widgetEnabled 明文镜像的 prefs 文件名与键名（布尔开关非敏感）。 */
        const val WIDGET_MIRROR_PREFS = "widget_mirror"
        const val WIDGET_ENABLED_MIRROR_KEY = "widget_enabled"
        const val WIDGET_LOGGED_IN_MIRROR_KEY = "widget_logged_in"

        /** widget 渲染路径专用读取：明文镜像命中即返回，不触碰加密 prefs。
         *  镜像缺失（老版本升级后首次渲染）回读一次加密值并回填，此后不再走加密路径。 */
        fun readWidgetEnabledMirror(context: Context): Boolean {
            val plain = context.applicationContext
                .getSharedPreferences(WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE)
            if (plain.contains(WIDGET_ENABLED_MIRROR_KEY)) {
                return plain.getBoolean(WIDGET_ENABLED_MIRROR_KEY, true)
            }
        val migrated = runCatching { AssistantSettings(context).widgetEnabled }.getOrDefault(true)
        plain.edit().putBoolean(WIDGET_ENABLED_MIRROR_KEY, migrated).apply()
        return migrated
    }

        /**
         * Widget 周期 worker 专用登录态读取（三态）：null = 键缺失（升级前旧装、
         * 登录态未知，调用方按「允许」处理维持现状），false = 已登出（禁止 CAS）。
         * 只读明文镜像，不触碰加密 prefs（Keystore）。
         */
        fun readWidgetLoggedInMirror(context: Context): Boolean? {
            val plain = context.applicationContext
                .getSharedPreferences(WIDGET_MIRROR_PREFS, Context.MODE_PRIVATE)
            if (plain.contains(WIDGET_LOGGED_IN_MIRROR_KEY)) {
                return plain.getBoolean(WIDGET_LOGGED_IN_MIRROR_KEY, true)
            }
            return null
        }

        @Volatile
        private var instance: AssistantSettings? = null

        /** 进程级单例：加密 prefs 只初始化一次，后续复用，加快冷启动与后台唤醒。 */
        operator fun invoke(context: Context): AssistantSettings {
            val current = instance
            if (current != null) return current
            return synchronized(this) {
                instance ?: AssistantSettings(context).also { instance = it }
            }
        }

        /** 加密 prefs 是否已构建完成（组合期门禁快路径：已就绪则无需等待占位帧）。 */
        fun isReady(): Boolean = cachedPrefs != null

        /** 加密 prefs 进程级缓存：MasterKey/EncryptedSharedPreferences 只建一次。 */
        @Volatile
        private var cachedPrefs: android.content.SharedPreferences? = null

        private fun encryptedPrefs(context: Context): android.content.SharedPreferences {
            val current = cachedPrefs
            if (current != null) return current
            return synchronized(this) {
                cachedPrefs ?: run {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        "xmu_assistant",
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                    ).also { cachedPrefs = it }
                }
            }
        }
    }
}
