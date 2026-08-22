package com.xmu.assistant

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal fun <T, R> boundedParallelMap(
    items: List<T>,
    maxParallel: Int,
    executorFactory: (Int) -> ExecutorService = { size ->
        // 默认池：daemon + 命名线程，避免高频调用残留非守护线程（应用常驻，线程不阻塞退出）
        Executors.newFixedThreadPool(size) { runnable ->
            Thread(runnable, "xmu-bounded").apply { isDaemon = true }
        }
    },
    transform: (T) -> R,
): List<R> {
    require(maxParallel > 0) { "maxParallel must be positive" }
    if (items.isEmpty()) return emptyList()
    val executor = executorFactory(minOf(maxParallel, items.size))
    return try {
        val tasks = items.map { item -> java.util.concurrent.Callable<R> { transform(item) } }
        // invokeAll 并行等待所有任务，避免逐个 get() 串行阻塞；
        // 任务异常被 Future 包成 ExecutionException，统一 unwrap 出原始异常，
        // 保证上层按原始异常类型分类（会话过期/网络/格式）不会失配。
        executor.invokeAll(tasks).map { future ->
            try {
                future.get()
            } catch (error: java.util.concurrent.ExecutionException) {
                throw error.cause ?: error
            }
        }
    } catch (error: InterruptedException) {
        // 调用协程被取消时 invokeAll 抛 InterruptedException，但默认 shutdown 不中断在跑任务，
        // 下载会继续跑满读超时且取消路径不经任务内 catch（.part 残留、线程悬挂）；
        // shutdownNow 中断在跑任务，任务内 catch/finally 得以清理临时文件。
        executor.shutdownNow()
        throw error
    } finally {
        executor.shutdown()
    }
}
