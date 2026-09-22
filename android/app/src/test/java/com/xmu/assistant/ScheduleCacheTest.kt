package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleCacheTest {
    @Test
    fun `schedule cache round trip preserves source rows term and timestamp`() {
        val snapshot = XmuScheduleSnapshot(
            entries = listOf(
                XmuScheduleEntry(
                    weekday = 4,
                    startSection = 1,
                    endSection = 2,
                    startTime = 800,
                    endTime = 940,
                    courseName = "课程G",
                    room = "教学楼B-205",
                    teacher = "老师A",
                    weeks = "1-15周，单周",
                    termCode = "20261",
                ),
            ),
            termCode = "20261",
            updatedAtMillis = 123456L,
        )

        assertEquals(snapshot, xmuScheduleSnapshotFromJson(xmuScheduleSnapshotToJson(snapshot)))
    }

    @Test
    fun `malformed or cross term schedule cache becomes empty`() {
        assertEquals(XmuScheduleSnapshot(), xmuScheduleSnapshotFromJson(""))
        assertEquals(XmuScheduleSnapshot(), xmuScheduleSnapshotFromJson("{not-json"))
        assertEquals(
            XmuScheduleSnapshot(),
            xmuScheduleSnapshotFromJson(
                """
                {
                  "termCode": "20261",
                  "updatedAtMillis": 123,
                  "entries": [{
                    "weekday": 1,
                    "startSection": 1,
                    "endSection": 2,
                    "startTime": 800,
                    "endTime": 940,
                    "courseName": "fixture",
                    "termCode": "20252"
                  }]
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `one bad entry is skipped without dropping the whole cache`() {
        // 单条坏记录（缺字段）只跳过该条，其余条目与元数据必须保留（不全表作废）
        val json = """
            {
              "schemaVersion": 3,
              "termCode": "20261",
              "updatedAtMillis": 456,
              "entries": [
                {
                  "weekday": 1,
                  "startSection": 1,
                  "endSection": 2,
                  "startTime": 800,
                  "endTime": 940,
                  "courseName": "good-entry",
                  "termCode": "20261"
                },
                {
                  "weekday": 2,
                  "startSection": 3,
                  "courseName": "broken-entry",
                  "termCode": "20261"
                }
              ]
            }
        """.trimIndent()

        val snapshot = xmuScheduleSnapshotFromJson(json)
        assertEquals("good entry must survive the broken sibling", 1, snapshot.entries.size)
        assertEquals("good-entry", snapshot.entries.first().courseName)
        assertEquals("20261", snapshot.termCode)
        assertEquals(456L, snapshot.updatedAtMillis)
    }

    @Test
    fun `legacy cache without schema version becomes empty to force refresh`() {
        // 旧版缓存（ZC=1 时代）没有 schemaVersion 字段，可能缺周一/周二等课程，
        // 必须视为无效强制重新拉取全学期数据。
        val legacy = """
            {
              "termCode": "20261",
              "updatedAtMillis": 123,
              "entries": [{
                "weekday": 1,
                "startSection": 1,
                "endSection": 2,
                "startTime": 800,
                "endTime": 940,
                "courseName": "课程C",
                "room": "C204",
                "teacher": "老师J",
                "weeks": "1-2,7,9-12周",
                "termCode": "20261"
              }]
            }
        """.trimIndent()

        val snapshot = xmuScheduleSnapshotFromJson(legacy)

        assertEquals(XmuScheduleSnapshot(), snapshot)
    }

    @Test
    fun `current cache round trip preserves schema version`() {
        val snapshot = XmuScheduleSnapshot(
            entries = listOf(
                XmuScheduleEntry(
                    weekday = 1,
                    startSection = 1,
                    endSection = 2,
                    startTime = 800,
                    endTime = 940,
                    courseName = "课程C",
                    room = "C204",
                    teacher = "老师J",
                    weeks = "1-2,7,9-12周",
                    termCode = "20261",
                ),
            ),
            termCode = "20261",
            updatedAtMillis = 456L,
        )

        val restored = xmuScheduleSnapshotFromJson(xmuScheduleSnapshotToJson(snapshot))

        assertEquals(snapshot, restored)
        assertEquals(SCHEDULE_CACHE_VERSION, restored.schemaVersion)
    }

    @Test
    fun `file round trip preserves snapshot and missing file returns empty`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "schedule-cache-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "cache.json")
            val snapshot = XmuScheduleSnapshot(
                entries = listOf(
                    XmuScheduleEntry(1, 1, 2, 800, 940, "课程C", "C204", "老师J", "1-16周", "20261"),
                    XmuScheduleEntry(2, 3, 4, 1010, 1150, "课程A", "C206", "老师A", "1-16周", "20261"),
                ),
                termCode = "20261",
                updatedAtMillis = 789L,
            )

            saveScheduleSnapshotToFile(file, snapshot)
            assertEquals(snapshot, loadScheduleSnapshotFromFile(file))

            // 不存在的文件返回空快照
            assertEquals(XmuScheduleSnapshot(), loadScheduleSnapshotFromFile(File(dir, "missing.json")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `failed atomic replace preserves the previous cache`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "schedule-cache-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "cache.json")
            val old = XmuScheduleSnapshot(termCode = "20261", updatedAtMillis = 111L)
            saveScheduleSnapshotToFile(file, old)

            val newer = xmuScheduleSnapshotToJson(old.copy(updatedAtMillis = 222L))
            assertEquals(
                "failed replacement should report the write failure",
                true,
                runCatching { writeScheduleSnapshotAtomically(file, newer) { _, _ -> false } }.isFailure,
            )

            assertEquals("atomic replace failure must keep the old cache", old, loadScheduleSnapshotFromFile(file))
            assertEquals(false, File(dir, "cache.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `successful retry stops invoking the replacer`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "schedule-cache-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "cache.json")
            var calls = 0
            val json = xmuScheduleSnapshotToJson(XmuScheduleSnapshot(termCode = "20261"))

            writeScheduleSnapshotAtomically(file, json) { _, _ ->
                calls++
                calls == 2
            }

            assertEquals("one initial attempt plus one successful retry", 2, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `normal saves replace an existing cache`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "schedule-cache-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "cache.json")
            saveScheduleSnapshotToFile(file, XmuScheduleSnapshot(termCode = "20261", updatedAtMillis = 111L))
            saveScheduleSnapshotToFile(file, XmuScheduleSnapshot(termCode = "20261", updatedAtMillis = 222L))

            assertEquals(222L, loadScheduleSnapshotFromFile(file).updatedAtMillis)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `corrupted file returns empty snapshot`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "schedule-cache-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "broken.json")
            file.writeText("{not-valid-json")
            assertEquals(XmuScheduleSnapshot(), loadScheduleSnapshotFromFile(file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `inferred calendars round trip per term`() {
        val snapshot = XmuScheduleSnapshot(
            entries = listOf(
                XmuScheduleEntry(1, 1, 2, 800, 940, "课程C", "C204", "老师J", "1-16周", "20261"),
            ),
            termCode = "20261",
            updatedAtMillis = 456L,
            inferredCalendars = mapOf(
                "20261" to XmuAcademicCalendar(
                    termCode = "20261",
                    academicYearLabel = "2026—2027学年",
                    semesterLabel = "第一学期",
                    startDate = java.time.LocalDate.of(2026, 9, 7),
                    endDate = java.time.LocalDate.of(2027, 1, 9),
                    totalWeeks = 18,
                ),
            ),
        )

        val restored = xmuScheduleSnapshotFromJson(xmuScheduleSnapshotToJson(snapshot))

        assertEquals(snapshot, restored)
        assertEquals(
            java.time.LocalDate.of(2026, 9, 7),
            restored.inferredCalendars["20261"]?.startDate,
        )
    }

    @Test
    fun `version two cache without calendars still loads entries`() {
        // 版本 2 的缓存没有 inferredCalendars 字段，课程数据仍应正常读取
        val v2 = """
            {
              "schemaVersion": 2,
              "termCode": "20261",
              "updatedAtMillis": 123,
              "entries": [{
                "weekday": 1,
                "startSection": 1,
                "endSection": 2,
                "startTime": 800,
                "endTime": 940,
                "courseName": "课程C",
                "room": "C204",
                "teacher": "老师J",
                "weeks": "1-2,7,9-12周",
                "termCode": "20261"
              }]
            }
        """.trimIndent()

        val snapshot = xmuScheduleSnapshotFromJson(v2)

        assertEquals(1, snapshot.entries.size)
        assertEquals("课程C", snapshot.entries.first().courseName)
        assertEquals(emptyMap<String, XmuAcademicCalendar>(), snapshot.inferredCalendars)
    }
}
