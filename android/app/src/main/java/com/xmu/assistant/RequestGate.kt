package com.xmu.assistant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class RequestGate {
    private val active = mutableSetOf<String>()

    @Synchronized
    fun tryStart(key: String): Boolean = active.add(key)

    @Synchronized
    fun finish(key: String) {
        active.remove(key)
    }
}

/** 释放已取得的请求门，包括尚未调度即被取消的任务；阻塞操作返回前仍持门。 */
internal fun CoroutineScope.launchGatedRequest(
    requestGate: RequestGate,
    gateKey: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    work: suspend CoroutineScope.() -> Unit,
): Job = launch(dispatcher, block = work).also { job ->
    job.invokeOnCompletion { requestGate.finish(gateKey) }
}
