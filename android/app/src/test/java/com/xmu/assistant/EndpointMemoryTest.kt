package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** 端点记忆回归测试（与桌面端 utils.ordered_endpoints 语义对齐，双端一致性 A3）。 */
class EndpointMemoryTest {

    @Test
    fun `no memory keeps original order`() {
        EndpointMemory.reset()
        val ordered = EndpointMemory.ordered("courses", listOf("/a", "/b", "/c"))
        assertEquals(listOf("/a", "/b", "/c"), ordered)
    }

    @Test
    fun `remembered endpoint moves to front`() {
        EndpointMemory.reset()
        EndpointMemory.remember("courses", "/b")
        val ordered = EndpointMemory.ordered("courses", listOf("/a", "/b", "/c"))
        assertEquals(listOf("/b", "/a", "/c"), ordered)
    }

    @Test
    fun `unknown purpose is ignored`() {
        EndpointMemory.reset()
        EndpointMemory.remember("other", "/x")
        val ordered = EndpointMemory.ordered("courses", listOf("/a", "/b"))
        assertEquals(listOf("/a", "/b"), ordered)
    }

    @Test
    fun `remembered endpoint missing from candidates is ignored`() {
        EndpointMemory.reset()
        EndpointMemory.remember("courses", "/gone")
        val ordered = EndpointMemory.ordered("courses", listOf("/a", "/b"))
        assertEquals(listOf("/a", "/b"), ordered)
    }
}
