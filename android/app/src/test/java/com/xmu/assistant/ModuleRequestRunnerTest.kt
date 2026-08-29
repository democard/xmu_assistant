package com.xmu.assistant

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * runModuleRequest（B1 统一会话守卫骨架）行为锁定。五 SectionState +
 * MainActivity 的模块刷新全部经此单点，回归会同时击穿多个模块：
 * - 结果回填与 loading 释放各自独立做世代判定（acceptsResult=false 时都不执行）；
 * - onFinally 是内层 finally 的无条件段（守卫拒绝路径也必须执行）；
 * - 门释放在外层 NonCancellable finally：协程取消/内层跳过都不会让 gateKey 永久占用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModuleRequestRunnerTest {

    /** 轮询驱动 Robolectric 主 looper（withContext(Main) 段落地）直至条件成立或超时。 */
    private fun awaitCondition(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun `delivers result and releases loading and gate when accepted`() {
        val gate = RequestGate()
        assertTrue("前置：测试自己负责 tryStart（骨架只负责释放）", gate.tryStart("m"))
        val scope = newScope()
        val results = Collections.synchronizedList(mutableListOf<Result<Int>>())
        var loadingReleased = 0
        var finallyRan = 0
        var mainThreadSeen = false
        val job = scope.runModuleRequest(
            requestGate = gate,
            gateKey = "m",
            acceptsResult = { true },
            ioWork = { 42 },
            onResult = { result ->
                results += result
                mainThreadSeen = Looper.myLooper() == Looper.getMainLooper()
            },
            releaseLoading = { loadingReleased++ },
            onFinally = { finallyRan++ },
        )
        assertTrue("骨架应完整收尾", awaitCondition { job.isCompleted })
        assertEquals(1, results.size)
        assertEquals(42, results[0].getOrNull())
        assertTrue("onResult 必须在主线程回填", mainThreadSeen)
        assertEquals(1, loadingReleased)
        assertEquals(1, finallyRan)
        assertTrue("门已释放", gate.tryStart("m").also { if (it) gate.finish("m") })
        scope.cancel()
    }

    @Test
    fun `skips result and loading but still runs onFinally and releases gate when not accepted`() {
        val gate = RequestGate()
        assertTrue(gate.tryStart("m"))
        val scope = newScope()
        var onResultRan = false
        var loadingReleased = 0
        var finallyRan = 0
        val job = scope.runModuleRequest(
            requestGate = gate,
            gateKey = "m",
            acceptsResult = { false },
            ioWork = { 42 },
            onResult = { onResultRan = true },
            releaseLoading = { loadingReleased++ },
            onFinally = { finallyRan++ },
        )
        assertTrue(awaitCondition { job.isCompleted })
        assertFalse("世代不符不得回填结果（登出/换号晚到结果丢弃）", onResultRan)
        assertEquals("世代不符不得释放 loading（由 clearLoadingState 兜底）", 0, loadingReleased)
        assertEquals("onFinally 是无条件段，守卫拒绝也要执行", 1, finallyRan)
        assertTrue(gate.tryStart("m").also { if (it) gate.finish("m") })
        scope.cancel()
    }

    @Test
    fun `delivers failure result when ioWork throws`() {
        val gate = RequestGate()
        assertTrue(gate.tryStart("m"))
        val scope = newScope()
        val results = Collections.synchronizedList(mutableListOf<Result<Int>>())
        var loadingReleased = 0
        val failure = IllegalStateException("接口超时")
        val job = scope.runModuleRequest(
            requestGate = gate,
            gateKey = "m",
            acceptsResult = { true },
            ioWork = { throw failure },
            onResult = { results += it },
            releaseLoading = { loadingReleased++ },
        )
        assertTrue(awaitCondition { job.isCompleted })
        assertEquals(1, results.size)
        assertSame(failure, results[0].exceptionOrNull())
        assertEquals(1, loadingReleased)
        assertTrue(gate.tryStart("m").also { if (it) gate.finish("m") })
        scope.cancel()
    }

    @Test
    fun `releases gate even when the coroutine is cancelled mid-ioWork`() {
        val gate = RequestGate()
        assertTrue(gate.tryStart("m"))
        val scope = newScope()
        val ioStarted = CompletableDeferred<Unit>()
        val releaseIo = CompletableDeferred<Unit>()
        var onResultRan = false
        val job = scope.runModuleRequest(
            requestGate = gate,
            gateKey = "m",
            acceptsResult = { true },
            ioWork = { ioStarted.complete(Unit); releaseIo.await(); 42 },
            onResult = { onResultRan = true },
            releaseLoading = {},
        )
        // 先等协程真正进入 ioWork 再取消：建模生产里「请求在途时登出/换号」。
        // 若在启动前取消，launch 的 body（含 finally）根本不会执行。
        assertTrue("ioWork 未进入", awaitCondition { ioStarted.isCompleted })
        // ioWork 挂起期间取消整个 scope：内层 withContext 整段跳过（无回填/无释放），
        // 但外层 NonCancellable finally 必须释放门（否则 gateKey 永久占用）。
        scope.cancel()
        releaseIo.complete(Unit)
        // isCompleted（终态）才代表外层 finally（含 NonCancellable 门释放）已执行完；
        // isCancelled 在取消发起时即为真，不能作为收尾判据。
        assertTrue(awaitCondition { job.isCompleted })
        assertTrue(job.isCancelled)
        assertFalse("取消后不得回填结果", onResultRan)
        assertTrue("取消后门仍必须释放", gate.tryStart("m").also { if (it) gate.finish("m") })
    }
}
