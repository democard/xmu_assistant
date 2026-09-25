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

/**
 * 带门槛的签到轮次。通知和提交分别去重；等待门槛、缺少数字码时保持待处理。
 * 写请求异常最多重试 [maxAnswerAttempts] 次，避免回执不明时无限重复提交。
 */
internal fun processRollcallMonitorPoll(
    events: Iterable<RollcallEvent>,
    settings: RollcallSettings,
    notifiedIds: MutableSet<String>,
    completedIds: MutableSet<String>,
    answerAttempts: MutableMap<String, Int>,
    runIfActive: (action: () -> Unit) -> Boolean,
    onNotify: (RollcallEvent) -> Unit,
    onAnswer: (RollcallEvent) -> Boolean,
    onSuccess: () -> Unit,
    settingsStillCurrent: () -> Boolean = { true },
    maxAnswerAttempts: Int = 3,
) {
    var hasUnresolvedAnswerFailure = false
    val pendingEvents = mutableListOf<RollcallEvent>()

    // 同轮先发出所有新事件的本地通知，再执行可能长时间阻塞的网络应答。
    for (event in events) {
        if (event.id in completedIds) continue
        if (event.id !in notifiedIds) {
            if (!runIfActive {
                    onNotify(event)
                    notifiedIds += event.id
                }
            ) return
        }
        pendingEvents += event
    }

    for (event in pendingEvents) {
        if (event.id in completedIds) continue
        val autoEnabled = when (event.type) {
            "数字签到" -> settings.autoAnswerNumber
            "雷达签到" -> settings.autoAnswerRadar
            else -> false
        }
        val previousAttempt = answerAttempts[event.id] ?: 0
        val expired = event.isExpired || event.remainingSeconds == 0L
        if (!expired && event.ownStatus == STATUS_UNKNOWN) {
            if (previousAttempt != 0) hasUnresolvedAnswerFailure = true
            continue
        }
        val resolvedStatus = event.ownStatus ?: event.status
        val definitelyFinished = expired || isTerminalRollcallStatus(resolvedStatus)
        if (definitelyFinished || event.type !in setOf("数字签到", "雷达签到")) {
            if (!runIfActive {
                    completedIds += event.id
                    answerAttempts.remove(event.id)
                }
            ) return
            continue
        }
        if (!autoEnabled) continue
        if (answerAttemptCount(previousAttempt) >= maxAnswerAttempts) {
            // 有界停止写入，但保留失败态；不能写入 completed 后让下一轮 onSuccess
            // 把真实的签到失败从监控健康状态中清掉。
            hasUnresolvedAnswerFailure = true
            continue
        }
        if (!settings.thresholdReached(event.progress)) {
            if (previousAttempt != 0) hasUnresolvedAnswerFailure = true
            continue
        }
        if (event.type == "数字签到" && event.numberCode.isBlank()) {
            if (previousAttempt != 0) hasUnresolvedAnswerFailure = true
            continue
        }
        // 回执不明必须等下一轮明细明确仍未签才允许有限重试；平台明确拒绝表示
        // 写入未生效，可在下一轮取到新码后安全重试。
        if (wasAnswerResultUncertain(previousAttempt) && event.ownStatus !in setOf("未签", "缺勤")) {
            hasUnresolvedAnswerFailure = true
            continue
        }
        if (!settingsStillCurrent()) continue
        if (!runIfActive { }) return

        try {
            val accepted = onAnswer(event)
            if (!accepted) {
                if (!runIfActive {
                        recordAnswerAttempt(answerAttempts, event.id, AnswerAttemptOutcome.REJECTED)
                    }
                ) return
                hasUnresolvedAnswerFailure = true
                continue
            }
            if (!runIfActive {
                    completedIds += event.id
                    answerAttempts.remove(event.id)
                }
            ) return
        } catch (error: MainSessionExpiredException) {
            throw error
        } catch (error: RollcallAnswerRejectedException) {
            if (!runIfActive {
                    recordAnswerAttempt(answerAttempts, event.id, AnswerAttemptOutcome.REJECTED)
                }
            ) return
            throw error
        } catch (error: Throwable) {
            if (!runIfActive {
                    recordAnswerAttempt(answerAttempts, event.id, AnswerAttemptOutcome.UNCERTAIN)
                }
            ) return
            throw error
        }
    }
    if (!hasUnresolvedAnswerFailure) runIfActive(onSuccess)
}

private enum class AnswerAttemptOutcome { REJECTED, UNCERTAIN }

/**
 * 持久化兼容编码：旧版正整数本来就表示回执不明，继续保留；负整数表示平台明确拒绝。
 * 绝对值始终是该签到累计写入次数。
 */
private fun recordAnswerAttempt(
    attempts: MutableMap<String, Int>,
    eventId: String,
    outcome: AnswerAttemptOutcome,
): Int {
    val count = answerAttemptCount(attempts[eventId] ?: 0) + 1
    attempts[eventId] = if (outcome == AnswerAttemptOutcome.UNCERTAIN) count else -count
    return count
}

private fun answerAttemptCount(encoded: Int): Int = kotlin.math.abs(encoded)

private fun wasAnswerResultUncertain(encoded: Int): Boolean = encoded > 0
