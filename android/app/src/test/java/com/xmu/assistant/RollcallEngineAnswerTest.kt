package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冻结分区（RollcallEngine）核心算法的行为测试。
 *
 * 遵守冻结约束：只读测试 RollcallEngine.kt 的公开纯函数，不修改冻结文件。
 * solveRadarCandidates 是雷达签到的坐标解算核心（双锚点距离交会），
 * 此前完全无行为覆盖（审查 P1-D）。
 */
class RollcallEngineAnswerTest {

    @Test
    fun `radar candidates solve returns two points for valid geometry`() {
        // 两个锚点相距 2km，各 1km 半径：交会圆相切于中点 → 两点重合（d == r1 + r2 边界）
        val lat1 = 24.4
        val lon1 = 118.0
        val lat2 = 24.4 + 0.01 // 约 1.11km 北移
        val lon2 = 118.0
        // 放宽：使用明显相交的几何（两圆相交）
        val candidates = solveRadarCandidates(
            lat1 = lat1, lon1 = lon1,
            lat2 = lat2, lon2 = lon2,
            distance1 = 1500.0,
            distance2 = 1500.0,
        )
        assertNotNull("two intersecting circles must yield candidates", candidates)
        assertEquals("must return exactly two candidate points", 2, candidates!!.size)
        // 两个解关于两锚点连线对称
        val (a, b) = candidates
        assertTrue("solutions must be distinct", a != b)
        // 解应在两锚点中点附近（等距时对称分布）
        val midLat = (lat1 + lat2) / 2
        val midLon = (lon1 + lon2) / 2
        assertTrue("solution A near midpoint", Math.abs(a.first - midLat) < 0.02 && Math.abs(a.second - midLon) < 0.02)
        assertTrue("solution B near midpoint", Math.abs(b.first - midLat) < 0.02 && Math.abs(b.second - midLon) < 0.02)
    }

    @Test
    fun `radar candidates returns null when circles do not intersect`() {
        // 两锚点相距 100km，半径各 1km → 圆不相交，无解
        val lat1 = 24.4
        val lon1 = 118.0
        val lat2 = 24.4 + 0.9 // 约 100km
        val lon2 = 118.0
        val candidates = solveRadarCandidates(
            lat1 = lat1, lon1 = lon1,
            lat2 = lat2, lon2 = lon2,
            distance1 = 1000.0,
            distance2 = 1000.0,
        )
        assertNull("disjoint circles must yield no candidates", candidates)
    }

    @Test
    fun `radar candidates returns null for concentric anchors`() {
        // 两锚点完全重合：无方向参考，无解
        val candidates = solveRadarCandidates(
            lat1 = 24.4, lon1 = 118.0,
            lat2 = 24.4, lon2 = 118.0,
            distance1 = 1000.0,
            distance2 = 1000.0,
        )
        assertNull("identical anchors must yield no candidates", candidates)
    }

    @Test
    fun `radar candidates handles one circle inside another`() {
        // 内切/内含：distance 差 > 圆心距 → 无解
        val lat1 = 24.4
        val lon1 = 118.0
        val lat2 = 24.4 + 0.002 // 约 220m
        val lon2 = 118.0
        val candidates = solveRadarCandidates(
            lat1 = lat1, lon1 = lon1,
            lat2 = lat2, lon2 = lon2,
            distance1 = 2000.0,
            distance2 = 200.0,
        )
        assertNull("contained circle must yield no candidates", candidates)
    }
}
