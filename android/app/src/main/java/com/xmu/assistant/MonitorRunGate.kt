package com.xmu.assistant

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

class MonitorRunGate {
    private val generation = AtomicLong(0L)
    private val sideEffectMutex = ReentrantLock(true)

    fun begin(): Long = generation.incrementAndGet()

    fun requestInvalidate() {
        generation.incrementAndGet()
    }

    fun awaitQuiescence() {
        sideEffectMutex.lock()
        try {
            // Acquiring the mutex is the barrier; no state mutation is needed here.
        } finally {
            sideEffectMutex.unlock()
        }
    }

    fun isActive(token: Long, running: Boolean, monitorDesired: Boolean): Boolean =
        running && monitorDesired && token == generation.get()

    fun runIfActive(token: Long, monitorDesired: Boolean, action: () -> Unit): Boolean {
        sideEffectMutex.lock()
        return try {
            if (!monitorDesired || token != generation.get()) return false
            action()
            true
        } finally {
            sideEffectMutex.unlock()
        }
    }
}

class MonitorWorkerCoordinator {
    private val lock = Any()
    private val gate = MonitorRunGate()
    private var currentToken: Long? = null

    fun start(monitorDesired: Boolean): Long? = synchronized(lock) {
        if (!monitorDesired) return@synchronized null
        val activeToken = currentToken
        if (activeToken != null && gate.isActive(activeToken, running = true, monitorDesired = true)) {
            return@synchronized null
        }
        gate.begin().also { currentToken = it }
    }

    fun requestInvalidateCurrent() {
        synchronized(lock) {
            gate.requestInvalidate()
            currentToken = null
        }
    }

    fun requestInvalidate(token: Long) {
        synchronized(lock) {
            if (currentToken == token) {
                gate.requestInvalidate()
                currentToken = null
            }
        }
    }

    fun awaitQuiescence() {
        gate.awaitQuiescence()
    }

    fun complete(token: Long) {
        synchronized(lock) {
            if (currentToken == token) currentToken = null
        }
    }

    fun isCurrent(token: Long, monitorDesired: Boolean): Boolean = synchronized(lock) {
        currentToken == token && gate.isActive(token, running = true, monitorDesired)
    }

    /** 是否仍有活跃 run（worker 退出线程据此决定 Service 是否自停）。 */
    fun hasCurrent(): Boolean = synchronized(lock) { currentToken != null }

    fun runIfCurrent(token: Long, monitorDesired: Boolean, action: () -> Unit): Boolean {
        if (synchronized(lock) { currentToken != token }) return false
        return gate.runIfActive(token, monitorDesired, action)
    }
}

fun <T> processActiveMonitorPoll(
    events: Iterable<T>,
    runIfActive: (action: () -> Unit) -> Boolean,
    onNotify: (T) -> Unit,
    shouldAnswer: (T) -> Boolean,
    onAnswer: (T) -> Unit,
    onSuccess: () -> Unit,
    // 每个事件处理完成（通知 + 可选应答）后回调：调用方据此逐个标记去重，
    // 中途停止/异常时已处理的事件不会丢，未处理的下一轮仍会通知
    onProcessed: (T) -> Unit = {},
) {
    for (event in events) {
        if (!runIfActive { onNotify(event) }) return
        if (shouldAnswer(event)) {
            // 互斥区内仅做活性校验（空 action，毫秒级）；应答本体在互斥区外执行：
            // 网络应答可能耗时数十秒（多次 15s 超时的 PUT），持锁会阻塞登出/暂停路径的
            // awaitQuiescence，表现为「正在退出登录」长时间转圈卡死（与第三方推送同理）。
            if (!runIfActive { }) {
                // 应答被中断（停止监控/登出）：通知已发出，仍按已处理标记，避免下轮重复通知
                onProcessed(event)
                return
            }
            try {
                onAnswer(event)
                // 应答正常返回（成功或平台明确拒绝）：标记已处理
                onProcessed(event)
            } catch (error: MainSessionExpiredException) {
                // 永久/会话失效：标记已处理，避免对同一签到反复通知/应答轰炸（P3 本意），
                // 异常继续上抛由外层收尾（记录失败/引导重登）。
                onProcessed(event)
                throw error
            }
            // 其它异常（瞬时网络/5xx/超时等）：不标记已处理 → 下轮轮询会重试该签到，
            // 不能让瞬时失败把自动签到永久丢掉（复查 H1 修订）。
        } else {
            onProcessed(event)
        }
    }
    runIfActive(onSuccess)
}
