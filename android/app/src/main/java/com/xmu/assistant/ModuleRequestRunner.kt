package com.xmu.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模块网络请求的统一会话守卫骨架（B1 单点化）：各 SectionState/MainActivity
 * 复刻的 scope.launch(IO) → runCatching → withContext(Main) 内世代判定 →
 * 双层 finally → NonCancellable 门释放样板收敛于此。
 *
 * 逐字保持的原语义：
 * - ioWork 在 IO 线程执行并包进 runCatching（异常与返回值统一走 Result）；
 * - 结果回填与 loading 释放都在 Main 线程，且各自独立做一次世代判定
 *   （登出/换号后晚到的结果既不回填也不释放）；
 * - 门释放提到协程最外层（NonCancellable）：协程取消时内层 withContext
 *   整段跳过，finish 放内层会让 gateKey 永久占用。
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
): Job = launch(Dispatchers.IO) {
    try {
        val result = runCatching { ioWork() }
        withContext(Dispatchers.Main) {
            try {
                if (acceptsResult()) onResult(result)
            } finally {
                onFinally()
                if (acceptsResult()) releaseLoading()
            }
        }
    } finally {
        withContext(kotlinx.coroutines.NonCancellable) {
            requestGate.finish(gateKey)
        }
    }
}
