package com.xmu.assistant

enum class AutoLoginPolicy {
    ENABLED,
    BLOCKED,
    USER_LOGGED_OUT,
}

enum class StartupSessionAction {
    KEEP_SESSION,
    AUTO_LOGIN,
    SHOW_UNKNOWN,
    STAY_LOGGED_OUT,
}

fun decideStartupSessionAction(
    health: SessionHealth,
    policy: AutoLoginPolicy,
    credentialsPresent: Boolean,
): StartupSessionAction = when (health) {
    SessionHealth.VALID -> StartupSessionAction.KEEP_SESSION
    SessionHealth.UNKNOWN -> StartupSessionAction.SHOW_UNKNOWN
    SessionHealth.EXPIRED -> if (policy == AutoLoginPolicy.ENABLED && credentialsPresent) {
        StartupSessionAction.AUTO_LOGIN
    } else {
        StartupSessionAction.STAY_LOGGED_OUT
    }
}

fun decideBlankCookieStartupAction(
    policy: AutoLoginPolicy,
    credentialsPresent: Boolean,
): StartupSessionAction = if (policy == AutoLoginPolicy.ENABLED && credentialsPresent) {
    StartupSessionAction.AUTO_LOGIN
} else {
    StartupSessionAction.STAY_LOGGED_OUT
}

/** Deterministic seam for offline startup-flow tests; Android owns UI persistence. */
class StartupRecoveryOrchestrator(
    private val probe: (String) -> SessionHealth,
    private val login: () -> Boolean,
) {
    var autoLoginFailed: Boolean = false
        private set

    fun run(cookie: String, policy: AutoLoginPolicy, username: String, password: String): StartupSessionAction {
        if (cookie.isBlank()) return decideBlankCookieStartupAction(policy, username.isNotBlank() && password.isNotBlank())
        val action = decideStartupSessionAction(probe(cookie), policy, username.isNotBlank() && password.isNotBlank())
        if (action == StartupSessionAction.AUTO_LOGIN && !login()) autoLoginFailed = true
        return action
    }

    fun acceptCompletion(tokenAccepted: Boolean, ownerActive: Boolean, credentialsMatch: Boolean): Boolean =
        tokenAccepted && ownerActive && credentialsMatch
}

fun migratedAutoLoginPolicy(stored: String?, cookieHeader: String): AutoLoginPolicy =
    AutoLoginPolicy.entries.firstOrNull { it.name == stored }
        ?: if (cookieHeader.isNotBlank()) AutoLoginPolicy.ENABLED else AutoLoginPolicy.USER_LOGGED_OUT

class AutoLoginPolicyStore(
    private val readStored: () -> String?,
    private val readCookie: () -> String,
    private val writeStored: (String) -> Unit,
) {
    var policy: AutoLoginPolicy
        get() = migratedAutoLoginPolicy(readStored(), readCookie())
        set(value) = writeStored(value.name)

    fun markLoginSucceeded() { policy = AutoLoginPolicy.ENABLED }

    fun markAutoLoginFailed() { policy = AutoLoginPolicy.BLOCKED }

    fun markUserLoggedOut() { policy = AutoLoginPolicy.USER_LOGGED_OUT }
}

data class SessionWorkToken(val generation: Long)

class SessionRecoveryCoordinator(private val minProbeIntervalMillis: Long = 60_000L) {
    private val lock = Any()
    private var nextGeneration = 0L
    private var probeToken: SessionWorkToken? = null
    private var autoLoginToken: SessionWorkToken? = null
    private var lastCompletedProbeAtMillis: Long? = null
    // academic 域（ids/jw CAS）登录的跨模块单飞门：成绩/课表/恢复模块共用，
    // 杜绝两个模块同时打身份域登录（并发 CAS 登录 = 风控红线）。
    private var academicCasLoginInFlight = false

    fun tryStartProbe(nowMillis: Long): SessionWorkToken? = synchronized(lock) {
        if (probeToken != null || !probeIntervalElapsed(nowMillis)) return null
        newToken().also { probeToken = it }
    }

    fun finishProbe(token: SessionWorkToken, nowMillis: Long) = synchronized(lock) {
        if (probeToken != token) return
        probeToken = null
        lastCompletedProbeAtMillis = nowMillis
    }

    fun cancelProbe(token: SessionWorkToken) = synchronized(lock) {
        if (probeToken == token) probeToken = null
    }

    /** 尝试获取 academic CAS 登录门：已在途（含 TronClass 身份域登录在途）则拒绝（风控红线：不并发打两个身份域）。 */
    fun tryStartAcademicCasLogin(): Boolean = synchronized(lock) {
        if (academicCasLoginInFlight || autoLoginToken != null) return false
        academicCasLoginInFlight = true
        true
    }

    /** 释放 academic CAS 登录门（登录成功或失败都必须调用）。 */
    fun finishAcademicCasLogin() {
        synchronized(lock) { academicCasLoginInFlight = false }
    }

    fun tryStartAutoLogin(): SessionWorkToken? = synchronized(lock) {
        // 与 academic CAS 登录互斥（风控红线：不并发打两个身份域）
        if (autoLoginToken != null || academicCasLoginInFlight) return null
        newToken().also { autoLoginToken = it }
    }

    fun finishAutoLogin(token: SessionWorkToken) = synchronized(lock) {
        if (autoLoginToken == token) autoLoginToken = null
    }

    fun invalidate() = synchronized(lock) {
        nextGeneration += 1
        probeToken = null
        autoLoginToken = null
    }

    fun accepts(token: SessionWorkToken): Boolean = synchronized(lock) {
        probeToken == token || autoLoginToken == token
    }

    private fun probeIntervalElapsed(nowMillis: Long): Boolean =
        lastCompletedProbeAtMillis?.let { completedAtMillis ->
            nowMillis < completedAtMillis || nowMillis - completedAtMillis >= minProbeIntervalMillis
        } ?: true

    private fun newToken(): SessionWorkToken = SessionWorkToken(++nextGeneration)
}

internal object ProcessSessionRecovery {
    val coordinator = SessionRecoveryCoordinator()
}
