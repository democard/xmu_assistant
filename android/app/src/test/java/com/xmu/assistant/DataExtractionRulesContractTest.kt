package com.xmu.assistant

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云备份/设备迁移排除清单守护。
 *
 * rules 文件存在时，API 31+ 上 allowBackup 会被忽略、按规则默认全包含——清单缺一项，
 * 对应数据就会进用户云备份/换机迁移。此前已漏过 exam_cache / rollcall_seen /
 * 加密 prefs / 外部课件四项（与既有三项同类：明文学业记录、凭据、大体积文件），
 * 这里逐项锁死，且 cloud-backup 与 device-transfer 两节都必须有。
 */
class DataExtractionRulesContractTest {

    @Test
    fun `sensitive stores are excluded from cloud backup and device transfer`() {
        val source = rulesSource()
        val cloud = excludesIn(source, "cloud-backup")
        val transfer = excludesIn(source, "device-transfer")

        val required = listOf(
            "file" to "schedule_cache.json", // 明文课表（教师/教室）
            "file" to "rollcall_history_cache.json", // 明文签到历史
            "sharedpref" to "schedule_widget.xml", // widget 摘要
            "sharedpref" to "exam_cache.xml", // 明文考试安排（课程/日期/教室）
            "sharedpref" to "rollcall_seen.xml", // 签到通知去重集
            "sharedpref" to "xmu_assistant.xml", // 加密凭据：MasterKey 不随迁移，恢复即自锁
            "external" to "Download/xmu助手课件", // 课件下载目录（体积可达数百 MB）
        )
        required.forEach { (domain, path) ->
            assertTrue("cloud-backup 必须排除 $domain/$path", domain to path in cloud)
            assertTrue("device-transfer 必须排除 $domain/$path", domain to path in transfer)
        }
    }

    private fun excludesIn(source: String, section: String): Set<Pair<String, String>> {
        val body = source
            .substringAfter("<$section>", missingDelimiterValue = "")
            .substringBefore("</$section>", missingDelimiterValue = "")
        assertTrue("$section section was not found", body.isNotBlank())
        return Regex("""<exclude\s+domain="([^"]+)"\s+path="([^"]+)"(\s*/>)?""")
            .findAll(body)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toSet()
    }

    private fun rulesSource(): String {
        val relativePath = "src/main/res/xml/data_extraction_rules.xml"
        val sourceFile = sequenceOf(
            File(relativePath),
            File("app", relativePath),
            File("android/app", relativePath),
        ).firstOrNull(File::isFile)

        return requireNotNull(sourceFile) {
            "data_extraction_rules.xml was not found from ${File(".").absolutePath}"
        }.readText()
    }
}
