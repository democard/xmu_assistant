package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * seen 环溢出逐出行为测试（本轮补测）：防重复通知的去重结构必须有界，
 * 逐出最老端并保持队列/集合双结构同步（PC 端孪生已有
 * test_seen_set_is_bounded_and_evicts_oldest，Android 侧此前仅常量文本锚）。
 */
class SeenRingEvictionTest {
    @Test
    fun `ring evicts oldest beyond cap and keeps set in sync`() {
        val seen = ArrayDeque<String>()
        val seenSet = mutableSetOf<String>()
        for (i in 1..305) {
            val id = "r$i"
            seenSet.add(id)
            seen.addLast(id)
            evictSeenOverflow(seen, seenSet, 300)
        }
        assertEquals(300, seen.size)
        assertEquals(seen.toSet(), seenSet)
        // 最老 5 个被逐出，界上最老 r6 仍在
        assertFalse("r1" in seenSet)
        assertFalse("r5" in seenSet)
        assertTrue("r6" in seenSet)
        assertTrue("r305" in seenSet)
        assertEquals("r6", seen.first())
    }

    @Test
    fun `under cap nothing is evicted`() {
        val seen = ArrayDeque(listOf("a", "b", "c"))
        val seenSet = mutableSetOf("a", "b", "c")
        evictSeenOverflow(seen, seenSet, 300)
        assertEquals(listOf("a", "b", "c"), seen.toList())
        assertEquals(setOf("a", "b", "c"), seenSet)
    }

    @Test
    fun `evicted id is no longer deduplicated`() {
        // 环逐出的功能语义：超出界的旧 id 再次出现会被当作新事件
        // （monitorLoop 的 filter { it.id !in seenSet } 放行）——这是去重
        // 结构有界的取舍，锁定行为防误改
        val seen = ArrayDeque<String>()
        val seenSet = mutableSetOf<String>()
        seenSet.add("old")
        seen.addLast("old")
        seenSet.add("new")
        seen.addLast("new")
        evictSeenOverflow(seen, seenSet, 1)
        assertEquals(listOf("new"), seen.toList())
        assertFalse("old" in seenSet)
        assertTrue("new" in seenSet)
    }
}
