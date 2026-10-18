package com.xmu.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadarCoordinateStoreTest {
    private lateinit var context: Context
    private lateinit var store: RadarCoordinateStore

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        store = RadarCoordinateStore(context)
        store.clear()
    }

    @Test fun `each course may save multiple places with one preferred`() {
        val first = store.add("course-1", "数学建模", "坤銮楼 B207", 24.605607, 118.313093, "preset")
        val second = store.add("course-1", "数学建模", "学武楼 A305", 24.605515, 118.314094, "onsite")
        store.add("course-2", "高等数学", "文宣楼 C201", 24.605273, 118.309745, "custom")

        val modeling = store.placesFor("course-1", "数学建模")
        assertEquals(2, modeling.size)
        assertEquals(second.id, store.preferredFor("course-1", "数学建模")?.id)
        assertEquals(1, modeling.count { it.preferred })

        store.setPreferred(first.id)
        assertEquals(first.id, store.preferredFor("course-1", "数学建模")?.id)
        assertEquals("文宣楼 C201", store.preferredFor("course-2", "高等数学")?.name)
    }

    @Test fun `deleting preferred promotes a remaining place`() {
        val first = store.add("course-1", "数学建模", "一号", 24.6, 118.3, "custom")
        val second = store.add("course-1", "数学建模", "二号", 24.61, 118.31, "custom")
        assertTrue(store.delete(second.id))
        assertEquals(first.id, store.preferredFor("course-1", "数学建模")?.id)
        assertTrue(store.preferredFor("course-1", "数学建模")!!.preferred)
    }

    @Test fun `same title with different platform ids stays isolated`() {
        store.add("course-old", "数学建模", "旧学期教室", 24.6, 118.3, "custom")
        store.add("course-new", "数学建模", "新学期教室", 24.61, 118.31, "custom")
        assertEquals("旧学期教室", store.preferredFor("course-old", "数学建模")?.name)
        assertEquals("新学期教室", store.preferredFor("course-new", "数学建模")?.name)
    }

    @Test fun `preset asset contains the approved six zones and playgrounds`() {
        val presets = loadXiangAnRadarPresets(context)
        assertEquals(44, presets.size)
        assertEquals(
            setOf("主楼群", "西部片区", "医学与生命", "东部科研", "公共教学", "北部科研"),
            presets.map { it.zone }.toSet(),
        )
        assertTrue(presets.any { it.name == "一期操场" })
        assertTrue(presets.any { it.name == "二期操场" })
        assertFalse(presets.any { "八闽苑" in it.name || "公寓" in it.name })
    }

    @Test fun `onsite WGS84 conversion shifts XMU coordinate but leaves overseas unchanged`() {
        val xmu = wgs84ToGcj02(24.605, 118.313)
        assertNotEquals(24.605, xmu.first, 0.000001)
        assertNotEquals(118.313, xmu.second, 0.000001)
        assertTrue(kotlin.math.abs(xmu.first - 24.605) < 0.02)
        assertTrue(kotlin.math.abs(xmu.second - 118.313) < 0.02)
        assertEquals(51.5074 to -0.1278, wgs84ToGcj02(51.5074, -0.1278))
    }
}
