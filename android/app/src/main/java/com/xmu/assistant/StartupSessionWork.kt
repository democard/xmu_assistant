package com.xmu.assistant

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 启动探测/自动登录共用的工作生命周期；回填与会话接纳判定由调用方保留。 */
internal fun CoroutineScope.launchStartupSessionWork(
    onFinished: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    work: suspend CoroutineScope.() -> Unit,
): Job = launch(dispatcher, block = work).also { job ->
    // 完成回调覆盖“调度前即取消”，该路径不会进入协程 body 的 finally。
    // 使用最终完成回调而非取消通知：阻塞登录尚未返回时仍持门，避免新旧登录重叠。
    job.invokeOnCompletion { onFinished() }
}
