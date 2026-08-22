package com.xmu.assistant

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class XmuScheduleModelsTest {
    @Test
    fun `client requests current term then schedule with academic cookie`() {
        val requests = mutableListOf<QueryHttpRequest>()
        val transport = QueryHttpTransport { request ->
            requests += request
            val body = when {
                request.url.endsWith("kfdxnxqcx.do") ->
                    """{"datas":{"kfdxnxqcx":{"rows":[{"XNXQDM":"20261"}]}}}"""
                else ->
                    """{"success":true,"pkjgList":[]}"""
            }
            QueryHttpResponse(request.url, 200, null, body, emptyMap())
        }

        val result = XmuScheduleClient("JSESSIONID=fixture", transport)
            .fetchCurrentSchedule("123456")

        assertEquals("20261", result.termCode)
        // 20261 是已知学期（官方校历表内），不应额外请求 getZcxx（刷新更快）
        assertEquals(2, requests.size)
        assertEquals(null, result.currentWeek)
        assertTrue(requests.all { it.headers["Cookie"] == "JSESSIONID=fixture" })
        assertEquals(NetworkOperation.SCHEDULE, requests.first().operation)
        assertTrue(requests.none { it.url.endsWith("getZcxx.do") })
        assertTrue(requests.any { it.body.contains("XNXQDM=20261") && it.body.contains("XH=123456") })
    }

    @Test
    fun `unknown term requests current week for inference`() {
        val requests = java.util.Collections.synchronizedList(mutableListOf<QueryHttpRequest>())
        val transport = QueryHttpTransport { request ->
            requests += request
            val body = when {
                request.url.endsWith("kfdxnxqcx.do") ->
                    """{"datas":{"kfdxnxqcx":{"rows":[{"XNXQDM":"99991"}]}}}"""
                request.url.endsWith("getZcxx.do") ->
                    """{"currentZc":"3","zcList":[{"ZC":1},{"ZC":2},{"ZC":3}],"success":true}"""
                else ->
                    """{"success":true,"pkjgList":[]}"""
            }
            QueryHttpResponse(request.url, 200, null, body, emptyMap())
        }

        val result = XmuScheduleClient("JSESSIONID=fixture", transport)
            .fetchCurrentSchedule("123456")

        // 未知学期（99991 不在官方表内）会额外请求 getZcxx
        assertEquals(3, requests.size)
        assertEquals(3, result.currentWeek)
        assertTrue(requests.any { it.url.endsWith("getZcxx.do") })
    }

    @Test
    fun `parses numeric and string fields and preserves all source rows`() {
        val body = """
            {
              "success": true,
              "pkjgList": [
                {
                  "XQ": "4",
                  "KSJCDM": 1,
                  "JSJCDM": "2",
                  "KSSJ": "08:00",
                  "JSSJ": "09:40",
                  "KCMC": "课程G",
                  "JASMC": "教学楼B-205",
                  "JSXM": "老师S",
                  "ZCMC": "1-15周，单周"
                },
                {
                  "XQ": 4,
                  "KSJCDM": 1,
                  "JSJCDM": 2,
                  "KSSJ": 800,
                  "JSSJ": 940,
                  "KCMC": "课程G",
                  "JASMC": "教学楼B-205",
                  "JSXM": "老师S",
                  "ZCMC": "1-15周，单周"
                }
              ]
            }
        """.trimIndent()

        val entries = parseXmuScheduleEntries(body, "20261")

        assertEquals(2, entries.size)
        assertEquals(4, entries.first().weekday)
        assertEquals(1, entries.first().startSection)
        assertEquals(800, entries.first().startTime)
        assertEquals(940, entries.first().endTime)
        assertEquals("20261", entries.first().termCode)
        assertEquals("老师S", entries.first().teacher)
    }

    @Test
    fun `selects the newest valid academic term regardless of server row order`() {
        assertEquals(
            "20261",
            selectXmuCurrentTermCode(listOf("20252", "20261", "20251")),
        )
    }

    @Test
    fun `selects the summer short term as newest when present`() {
        // 夏季短学期代码 ...3 必须参与选择：暑假时 20263 应优先于 20262
        assertEquals(
            "20263",
            selectXmuCurrentTermCode(listOf("20261", "20262", "20263")),
        )
        assertEquals(
            "20263",
            selectXmuCurrentTermCode(listOf("20263", "20262", "20261")),
        )
    }

    @Test
    fun `ignores malformed term codes when choosing`() {
        // 非 4 位学年+1 位学期格式的代码不参与选择，也不会炸
        assertEquals("20261", selectXmuCurrentTermCode(listOf("garbage", "20261", "")))
        assertEquals(null, selectXmuCurrentTermCode(emptyList()))
    }

    @Test
    fun `week three keeps data structures on Monday and Tuesday`() {
        val body = """
            {
              "success": true,
              "pkjgList": [
                {
                  "XQ": 1,
                  "KSJCDM": 1,
                  "JSJCDM": 2,
                  "KSSJ": "08:00",
                  "JSSJ": "09:40",
                  "KCMC": "课程C",
                  "JASMC": "教学楼101",
                  "JSXM": "教师甲",
                  "ZCMC": "1-16周"
                },
                {
                  "XQ": 2,
                  "KSJCDM": 1,
                  "JSJCDM": 2,
                  "KSSJ": "08:00",
                  "JSSJ": "09:40",
                  "KCMC": "课程C",
                  "JASMC": "教学楼102",
                  "JSXM": "教师乙",
                  "ZCMC": "1-16周"
                }
              ]
            }
        """.trimIndent()

        val entries = parseXmuScheduleEntries(body, "20261").forWeek(3)

        assertEquals(setOf(1, 2), entries.map { it.weekday }.toSet())
        assertEquals(2, entries.count { it.courseName == "课程C" })
    }

    @Test
    fun `groups parallel teacher or room records into one display course`() {
        val entries = listOf(
            XmuScheduleEntry(2, 3, 4, 1000, 1140, "课程C", "嘉庚二号楼101", "教师甲", "1-16周", "20261"),
            XmuScheduleEntry(2, 3, 4, 1000, 1140, "课程C", "嘉庚二号楼102", "教师乙", "1-16周", "20261"),
        )

        val groups = entries.groupForDisplay()

        assertEquals(1, groups.size)
        assertEquals(listOf("嘉庚二号楼101", "嘉庚二号楼102"), groups.single().rooms)
        assertEquals(listOf("教师甲", "教师乙"), groups.single().teachers)
        assertEquals(
            listOf(
                XmuScheduleVariant("嘉庚二号楼101", "教师甲", "1-16周"),
                XmuScheduleVariant("嘉庚二号楼102", "教师乙", "1-16周"),
            ),
            groups.single().variants,
        )
        assertTrue(groups.single().courseName.contains("课程C"))
    }

    @Test
    fun `complementary weeks keep the week to room mapping per variant`() {
        // 同一课程同一时间，周次互补但教室不同（1-8 周在 A 教室、9-16 周在 B 教室）：
        // 合并为一个显示组，但每个 variant 必须保留自己的周次，避免"哪段周次在哪教室"丢失。
        val entries = listOf(
            XmuScheduleEntry(1, 1, 2, 800, 940, "大学英语", "嘉庚一号楼201", "教师甲", "1-8周", "20261"),
            XmuScheduleEntry(1, 1, 2, 800, 940, "大学英语", "嘉庚一号楼301", "教师乙", "9-16周", "20261"),
        )

        val groups = entries.groupForDisplay()

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("1-8周；9-16周", group.weeks)
        assertEquals(
            setOf(
                XmuScheduleVariant("嘉庚一号楼201", "教师甲", "1-8周"),
                XmuScheduleVariant("嘉庚一号楼301", "教师乙", "9-16周"),
            ),
            group.variants.toSet(),
        )
        // 周次与教室必须一一对应，而不是各自去重后无法对齐
        assertEquals(
            mapOf("1-8周" to "嘉庚一号楼201", "9-16周" to "嘉庚一号楼301"),
            group.variants.associate { it.weeks to it.room },
        )
    }

    @Test
    fun `parses current term code list from academic response`() {
        val body = """
            {
              "datas": {
                "kfdxnxqcx": {
                  "rows": [
                    {"XNXQDM": "20261"},
                    {"XNXQDM": "20252"}
                  ]
                }
              }
            }
        """.trimIndent()

        assertEquals(listOf("20261", "20252"), parseXmuTermCodes(body))
    }

    @Test
    fun `formats hhmm values`() {
        assertEquals("08:00", formatXmuTime(800))
        assertEquals("09:40", formatXmuTime(940))
    }

    @Test
    fun `client classifies login html and network failures without exposing response content`() {
        val loginTransport = QueryHttpTransport { request ->
            QueryHttpResponse(
                request.url,
                200,
                null,
                """<html><form action="/authserver/login"><input id="pwdEncryptSalt"></form></html>""",
                emptyMap(),
            )
        }
        assertThrows(ScheduleSessionExpiredException::class.java) {
            XmuScheduleClient("expired", loginTransport).fetchTermCodes()
        }

        val networkTransport = QueryHttpTransport { throw IOException("private network detail") }
        assertThrows(ScheduleNetworkException::class.java) {
            XmuScheduleClient("cookie", networkTransport).fetchTermCodes()
        }
    }

    @Test
    fun `client never classifies server errors as session expired`() {
        // 风控红线：5xx 与无登录特征的服务端 HTML 页绝不能判会话过期（避免误触发 CAS 续登）
        val serverErrorTransport = QueryHttpTransport { request ->
            QueryHttpResponse(request.url, 500, null, "<html><body>数据库繁忙，请稍后再试</body></html>", emptyMap())
        }
        assertThrows(ScheduleResponseException::class.java) {
            XmuScheduleClient("cookie", serverErrorTransport).fetchTermCodes()
        }

        val genericHtmlTransport = QueryHttpTransport { request ->
            QueryHttpResponse(request.url, 200, null, "<html><body>教务系统维护中</body></html>", emptyMap())
        }
        assertThrows(ScheduleResponseException::class.java) {
            XmuScheduleClient("cookie", genericHtmlTransport).fetchTermCodes()
        }
    }
}
