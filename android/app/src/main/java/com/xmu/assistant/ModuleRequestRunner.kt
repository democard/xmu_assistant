package com.xmu.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/**
 * 模块网络请求的统一会话守卫骨架（B1 单点化）：各 SectionState/MainActivity
 * 复刻的 scope.launch(IO) → runCatching → withContext(Main) 内世代判定 →
 * 主线程收尾与任务完成后的门释放收敛于此。
 *
 * 行为约定：
 * - ioWork 在 IO 线程执行并包进 runCatching（异常与返回值统一走 Result）；
 * - 结果回填与 loading 释放都在 Main 线程，且各自独立做一次世代判定
 *   （登出/换号后晚到的结果既不回填也不释放）；
 * - 门释放绑定最终完成回调：启动前取消也能释放；阻塞 IO 尚未返回时仍持门。
 *
 * 未并入的差异形态（保持原样，详见各文件）：
 * - exam.checkChanges / rollcall.refreshHistory：getOrNull/多阶段 SWR 形态。
 * （score.refresh/schedule.refresh 已并入：client 工厂建在 scope 外经闭包传入、
 *  账号复核折叠进 acceptsResult；schedule 恢复路径的无条件 transition 复位经 onFinally
 *  表达，执行序与原内层 finally 一致——复位段先于守卫释放，且不受世代判定影响。）
 */
internal fun <T> CoroutineScope.runModuleRequest(
    requestGate: RequestGate,
    gateKey: String,
    acceptsResult: () -> Boolean,
    ioWork: suspend () -> T,
    onResult: (Result<T>) -> Unit,
    releaseLoading: () -> Unit,
    /** 内层 finally 的无条件收尾段（默认空）：在守卫释放 releaseLoading 之前执行。
     *  供不受世代判定约束、必须无条件执行的复位使用（schedule 恢复路径的
     *  transition 复位——登出竞态下也必须恢复首页按钮可用）。 */
    onFinally: () -> Unit = {},
): Job = launchGatedRequest(requestGate, gateKey) {
    val result = runCatching { ioWork() }
    withContext(Dispatchers.Main) {
        try {
            if (acceptsResult()) onResult(result)
        } finally {
            onFinally()
            if (acceptsResult()) releaseLoading()
        }
    }
}
