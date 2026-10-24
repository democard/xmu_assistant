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
    @Test fun `multiple store instances serialize concurrent read modify write`() {
        val workers = (1..8).map { index -> Thread {
            val instance = RadarCoordinateStore(context)
            repeat(10) { instance.add("c1", "数学", "$index-$it", 24.6, 118.3, "custom") }
        } }
        workers.forEach(Thread::start)
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        val places = store.placesFor("c1", "数学")
        assertEquals(80, places.size)
        assertEquals(1, places.count { it.preferred })
    }

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

    @Test fun `title only mutations do not corrupt id bound courses with same title`() {
        val c1First = store.add("course-1", "数学建模", "c1-A", 24.60, 118.30, "custom", makePreferred = true)
        val c1Second = store.add("course-1", "数学建模", "c1-B", 24.61, 118.31, "custom", makePreferred = false)
        val c2Only = store.add("course-2", "数学建模", "c2-A", 24.62, 118.32, "custom", makePreferred = true)

        val legacy1 = store.add("", "数学建模", "legacy-1", 24.63, 118.33, "custom", makePreferred = true)
        val legacy2 = store.add("", "数学建模", "legacy-2", 24.64, 118.34, "custom", makePreferred = true)

        assertEquals(c1First.id, store.preferredFor("course-1", "数学建模")?.id)
        assertTrue(store.preferredFor("course-1", "数学建模")!!.preferred)
        assertEquals(c2Only.id, store.preferredFor("course-2", "数学建模")?.id)
        assertTrue(store.preferredFor("course-2", "数学建模")!!.preferred)

        assertTrue(store.setPreferred(legacy1.id))
        assertEquals(c1First.id, store.preferredFor("course-1", "数学建模")?.id)
        assertTrue(store.preferredFor("course-1", "数学建模")!!.preferred)
        assertEquals(c2Only.id, store.preferredFor("course-2", "数学建模")?.id)
        assertTrue(store.preferredFor("course-2", "数学建模")!!.preferred)

        assertTrue(store.delete(legacy1.id))
        assertEquals(legacy2.id, store.preferredFor("", "数学建模")?.id)
        assertTrue(store.preferredFor("", "数学建模")!!.preferred)

        assertTrue(store.delete(c1First.id))
        assertEquals(c1Second.id, store.preferredFor("course-1", "数学建模")?.id)
        assertTrue(store.preferredFor("course-1", "数学建模")!!.preferred)
    }

    @Test fun `stored json skips out of range coordinates and sanitizes null or negative fields`() {
        context.applicationContext
            .getSharedPreferences("xmu_radar_coordinates", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "course_locations_v1",
                """[
                    {"id":"bad-lat","courseId":"c1","courseTitle":"数学","name":"越界纬度","latitude":95.0,"longitude":118.3,"preferred":true},
                    {"id":"bad-lon","courseId":"c1","courseTitle":"数学","name":"越界经度","latitude":24.6,"longitude":-185.0,"preferred":true},
                    {"id":"valid","courseId":null,"courseTitle":"数学","name":null,"latitude":24.6,"longitude":118.3,"source":null,"preferred":true,"accuracyMeters":-3.0}
                ]""",
            )
            .commit()

        val places = store.placesFor("", "数学")
        assertEquals(listOf("valid"), places.map { it.id })
        val item = places.single()
        assertEquals("", item.courseId)
        assertEquals("未命名地点", item.name)
        assertEquals("custom", item.source)
        org.junit.Assert.assertNull(item.accuracyMeters)
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
