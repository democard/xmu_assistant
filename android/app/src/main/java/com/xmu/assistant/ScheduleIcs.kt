package com.xmu.assistant

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 课表 → iCalendar (.ics) 导出（纯函数，无 Android 依赖）。
 *
 * RFC 5545 关键取舍：
 * - 时间用浮动本地时间（DTSTART 不带 Z、不写 VTIMEZONE），导入端按本地时区解析；
 * - RFC 5545 没有「周次奇偶」概念，单双周完全靠 DTSTART 锚定正确奇偶周的首上课日
 *   + RRULE INTERVAL=2 展开（与周次校准日历 startDate 联动）；
 * - 断档周次（如 "1-8,10-16"）无法用单个 RRULE 表达：按极大等差段（步长 1/2）
 *   拆成多个 VEVENT，SUMMARY 相同、日期互补，避免把缺上的第 9 周错误包含进来；
 * - BYDAY 单天时 WKST 边界不构成影响（RFC 默认 WKST=MO），省略以减小体积。
 */

/** 一段可展开为 RRULE 的等差周次：firstWeek..lastWeek 步长 step（step=0 表示仅单周）。 */
internal data class IcsWeekRun(val firstWeek: Int, val lastWeek: Int, val step: Int)

/**
 * 把排序后的周次集合切分为极大等差段（步长 ∈ {1,2}，步长中途变化即断段），
 * 例如 [1..8,10..16] → 1-8(step1) + 10-16(step1)；[7,9,10,11,12] → 7-9(step2) + 10-12(step1)。
 */
internal fun splitIcsWeekRuns(weeks: Collection<Int>): List<IcsWeekRun> {
    val sorted = weeks.toSortedSet().toList()
    if (sorted.isEmpty()) return emptyList()
    val runs = ArrayList<IcsWeekRun>()
    var first = sorted[0]
    var last = first
    var step = 0
    for (index in 1 until sorted.size) {
        val week = sorted[index]
        val delta = week - last
        when {
            (delta == 1 || delta == 2) && step == 0 -> step = delta
            (delta == 1 || delta == 2) && delta != step -> {
                runs += IcsWeekRun(first, last, step)
                first = week
                step = 0
            }
            delta != 1 && delta != 2 -> {
                runs += IcsWeekRun(first, last, step)
                first = week
                step = 0
            }
        }
        last = week
    }
    runs += IcsWeekRun(first, last, step)
    return runs
}

/** RFC 5545 TEXT 转义：反斜杠 → 分号 → 逗号 → 换行（CRLF/LF 均归一为 \\n 字面量）。 */
internal fun icsEscape(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

private val ICS_BYDAY = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/**
 * 生成整份 .ics 文本；calendar 为 null 时返回 null（开学日未校准，调用方应提示后重试）。
 * 周次不可解析的排课沿用展示语义兜底为全学期每周上课；weekday/时间非法的条目跳过。
 */
fun buildScheduleIcs(
    entries: List<XmuScheduleEntry>,
    calendar: XmuAcademicCalendar?,
): String? {
    calendar ?: return null

    val builder = StringBuilder()
    builder.append("BEGIN:VCALENDAR\r\n")
    builder.append("VERSION:2.0\r\n")
    builder.append("PRODID:-//xmu-assistant//Schedule Export//CN\r\n")
    builder.append("CALSCALE:GREGORIAN\r\n")
    builder.append("X-WR-CALNAME:").append(icsEscape(calendar.displayLabel)).append("\r\n")
    val dtstamp = LocalDateTime.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

    entries.forEach { entry ->
        // weekday 缺失或起止时间为空的排课无法落出有效 DTSTART/DTEND，跳过而不是产出坏事件
        if (entry.weekday !in 1..7) return@forEach
        val startClock = formatXmuTimeOrNull(entry.startTime) ?: return@forEach
        val endClock = formatXmuTimeOrNull(entry.endTime) ?: return@forEach
        val parsed = parseXmuWeekExpression(entry.weeks)
        val weeks = if (parsed.parseable) parsed.weeks else (1..calendar.totalWeeks).toSet()
        splitIcsWeekRuns(weeks).forEach { run ->
            builder.append("BEGIN:VEVENT\r\n")
            builder
                .append("UID:")
                .append(icsUid(entry))
                .append("-w")
                .append(run.firstWeek)
                .append("@xmu-assistant\r\n")
            builder.append("DTSTAMP:").append(dtstamp).append("\r\n")
            val firstDate = dateOfWeekday(calendar, run.firstWeek, entry.weekday)
            val lastDate = dateOfWeekday(calendar, run.lastWeek, entry.weekday)
            // 日期段用 BASIC_ISO_DATE（yyyyMMdd）文本拼接；时间来自排课数据，
            // 补齐秒位成 RFC 5545 的 THHMMSS 浮动本地时间
            val isoDate = DateTimeFormatter.BASIC_ISO_DATE
            builder
                .append("DTSTART:")
                .append(firstDate.format(isoDate))
                .append("T")
                .append(startClock.replace(":", ""))
                .append("00\r\n")
            // RFC 5545 重复事件按「DTEND-DTSTART」差值推导每次实例时长，
            // DTEND 必须取首现日而非段末日，否则单次时长会被拉成长达数月
            builder
                .append("DTEND:")
                .append(firstDate.format(isoDate))
                .append("T")
                .append(endClock.replace(":", ""))
                .append("00\r\n")
            if (run.lastWeek > run.firstWeek) {
                builder
                    .append("RRULE:FREQ=WEEKLY;INTERVAL=")
                    .append(if (run.step == 2) 2 else 1)
                    .append(";BYDAY=")
                    .append(ICS_BYDAY[entry.weekday - 1])
                    .append(";UNTIL=")
                    .append(lastDate.format(isoDate))
                    .append("T235959\r\n")
            }
            builder.append("SUMMARY:").append(icsEscape(entry.courseName.ifBlank { "课程" })).append("\r\n")
            if (entry.room.isNotBlank()) {
                builder.append("LOCATION:").append(icsEscape(entry.room)).append("\r\n")
            }
            val description = buildString {
                if (entry.teacher.isNotBlank()) append("教师：").append(entry.teacher).append('\n')
                append("周次：").append(entry.weeks.ifBlank { "全学期" })
            }
            builder.append("DESCRIPTION:").append(icsEscape(description)).append("\r\n")
            builder.append("END:VEVENT\r\n")
        }
    }

    builder.append("END:VCALENDAR\r\n")
    return builder.toString()
}

private fun dateOfWeekday(calendar: XmuAcademicCalendar, week: Int, weekday: Int): LocalDate =
    calendar.startDate.plusDays((week - 1L) * 7L + (weekday - 1L))

/** 稳定 UID：同学期同排课再次导出 UID 一致，重复导入时日历应用可按 UID 替换去重。 */
private fun icsUid(entry: XmuScheduleEntry): String =
    "${entry.termCode}-${entry.weekday}-${entry.startSection}-" +
        "${entry.courseName.hashCode()}"

private fun formatXmuTimeOrNull(value: Int): String? =
    if (value in 100..2359 && value % 100 < 60) formatXmuTime(value) else null
