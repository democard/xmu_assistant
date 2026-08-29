package com.xmu.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicCacheTest {
    @Test
    fun `academic cache round trip preserves every course and courseware field`() {
        val snapshot = AcademicCacheSnapshot(
            courses = listOf(
                CourseSummary(id = "course-1", title = "fixture-one", term = "2025-1"),
                CourseSummary(id = "course-2", title = "fixture-two", term = "2025-2"),
            ),
            coursesUpdatedAtMillis = 101L,
            coursewareByCourse = mapOf(
                "course-1" to listOf(
                    CoursewareUiItem(
                        id = "item-1",
                        courseId = "course-1",
                        activityId = "activity-1",
                        title = "fixture-item",
                        filename = "fixture.pdf",
                        type = "file",
                        moduleName = "module-one",
                        referenceId = "reference-one",
                        sourceUrl = "https://fixture.invalid/file.pdf",
                        downloadStatus = COURSEWARE_STATUS_SUCCESS,
                        failureReason = "fixture-reason",
                    ),
                ),
                "course-2" to emptyList(),
            ),
            coursewareUpdatedAtMillis = mapOf("course-1" to 202L, "course-2" to 303L),
        )

        val decoded = academicCacheFromJson(academicCacheToJson(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `malformed academic cache becomes an empty snapshot`() {
        assertEquals(AcademicCacheSnapshot(), academicCacheFromJson("{not-json"))
        assertEquals(AcademicCacheSnapshot(), academicCacheFromJson(""))
    }

    @Test
    fun `courseware from another course invalidates the course cache and timestamp`() {
        val decoded = academicCacheFromJson(
            """
            {
              "coursewareByCourse": {
                "course-1": [
                  {
                    "id": "wrong-course-item",
                    "courseId": "course-2",
                    "activityId": "activity-1",
                    "title": "fixture-item",
                    "filename": "fixture.pdf",
                    "type": "file"
                  }
                ],
                "course-2": []
              },
              "coursewareUpdatedAtMillis": {
                "course-1": 101,
                "course-2": 202
              }
            }
            """.trimIndent(),
        )

        assertFalse(decoded.coursewareByCourse.containsKey("course-1"))
        assertFalse(decoded.coursewareUpdatedAtMillis.containsKey("course-1"))
        assertTrue(decoded.coursewareByCourse.containsKey("course-2"))
        assertEquals(emptyList<CoursewareUiItem>(), decoded.coursewareByCourse.getValue("course-2"))
        assertEquals(202L, decoded.coursewareUpdatedAtMillis.getValue("course-2"))
    }

    @Test
    fun `courseware without a course id invalidates the course cache and timestamp`() {
        val decoded = academicCacheFromJson(
            """
            {
              "coursewareByCourse": {
                "course-1": [
                  {
                    "id": "unattributed-item",
                    "courseId": "",
                    "activityId": "activity-1",
                    "title": "fixture-item",
                    "filename": "fixture.pdf",
                    "type": "file"
                  }
                ]
              },
              "coursewareUpdatedAtMillis": {
                "course-1": 101
              }
            }
            """.trimIndent(),
        )

        assertFalse(decoded.coursewareByCourse.containsKey("course-1"))
        assertFalse(decoded.coursewareUpdatedAtMillis.containsKey("course-1"))
    }

    @Test
    fun `illegal entry invalidates its entire course cache and timestamp`() {
        val decoded = academicCacheFromJson(
            """
            {
              "coursewareByCourse": {
                "course-1": [
                  {
                    "id": "valid-item",
                    "courseId": "course-1",
                    "activityId": "activity-1",
                    "title": "fixture-item",
                    "filename": "fixture.pdf",
                    "type": "file"
                  },
                  "not-an-object"
                ],
                "course-2": []
              },
              "coursewareUpdatedAtMillis": {
                "course-1": 101,
                "course-2": 202
              }
            }
            """.trimIndent(),
        )

        assertFalse(decoded.coursewareByCourse.containsKey("course-1"))
        assertFalse(decoded.coursewareUpdatedAtMillis.containsKey("course-1"))
        assertTrue(decoded.coursewareByCourse.containsKey("course-2"))
        assertEquals(202L, decoded.coursewareUpdatedAtMillis.getValue("course-2"))
    }

    @Test
    fun `cache updates copy state and leave old snapshot unchanged`() {
        val original = AcademicCacheSnapshot(
            courses = listOf(CourseSummary("old", "old-title")),
            coursesUpdatedAtMillis = 1L,
        )
        val newCourses = listOf(CourseSummary("new", "new-title", "2026-1"))
        val item = CoursewareUiItem(
            id = "item",
            courseId = "new",
            activityId = "activity",
            title = "title",
            filename = "file.pdf",
            type = "file",
        )

        val withCourses = original.withCourses(newCourses, 2L)
        val withCourseware = withCourses.withCourseware("new", listOf(item), 3L)

        assertNotSame(original, withCourses)
        assertEquals(listOf("old"), original.courses.map { it.id })
        assertTrue(original.coursewareByCourse.isEmpty())
        assertEquals(newCourses, withCourseware.courses)
        assertEquals(listOf(item), withCourseware.coursewareByCourse.getValue("new"))
        assertEquals(3L, withCourseware.coursewareUpdatedAtMillis.getValue("new"))
    }
}
