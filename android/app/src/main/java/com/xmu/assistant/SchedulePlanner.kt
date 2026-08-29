package com.xmu.assistant

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The academic system gives us a free-form Chinese week expression. Keep the
 * parser deliberately forgiving: a malformed expression must not make a
 * course disappear from the user's timetable.
 */
data class ParsedWeekExpression(
    val weeks: Set<Int>,
    val parseable: Boolean,
)

/** 教学周上界（覆盖 19 周长学期；与 XmuScheduleClient 当前周次/手动周次同口径）。
 *  解析器按此钳制范围端点：畸形超大范围（如 1-2147483647周）全量展开会
 *  在周次切换/Widget/ICS 导出的调用线程上 OOM/卡死。 */
internal const val MAX_XMU_WEEK = 25

fun parseXmuWeekExpression(value: String): ParsedWeekExpression {
    val source = value.trim()
    if (source.isBlank()) return ParsedWeekExpression(emptySet(), parseable = false)

    val tokens = source
        .replace("，", ",")
        .replace("、", ",")
        .replace("；", ",")
        .replace(";", ",")
        .replace("/", ",")
        .replace("|", ",")
        .split(',')
        .map { it.trim().replace(Regex("\\s+"), "") }
        .filter(String::isNotBlank)
    val parityMarkers = tokens.mapNotNull { token ->
        when {
            token.contains("单双") -> null
            token.contains("单周") || token.endsWith("单") -> 1
            token.contains("双周") || token.endsWith("双") -> 0
            else -> null
        }
    }.distinct()
    // Some XMU responses put the parity marker in its own suffix token:
    // "1-16周,单周". Only apply it globally when there is one unambiguous
    // marker; mixed expressions must be handled token by token.
    val globalParity = parityMarkers.singleOrNull()
    val result = linkedSetOf<Int>()
    var sawValidRange = false
    tokens.forEach { token ->
        val hasOdd = token.contains("单周") || token.endsWith("单")
        val hasEven = token.contains("双周") || token.endsWith("双")
        val parity = when {
            hasOdd && !hasEven -> 1
            hasEven && !hasOdd -> 0
            else -> globalParity
        }
        val ranges = WEEK_RANGE_PATTERN.findAll(token).toList()
        if (ranges.isNotEmpty()) {
            ranges.forEach { match ->
                val start = match.groupValues[1].toIntOrNull()
                val end = match.groupValues[2].toIntOrNull()
                if (start != null && end != null && start > 0 && end >= start) {
                    (start..end)
                        .filter { week -> week <= MAX_XMU_WEEK && (parity == null || week % 2 == parity) }
                        .forEach(result::add)
                    sawValidRange = true
                }
            }
        } else {
            // Also accept "第1周、第3周" and other single-week forms.
            WEEK_NUMBER_PATTERN.findAll(token)
                .mapNotNull { it.value.toIntOrNull()?.takeIf { week -> week > 0 } }
                .forEach { week ->
                    if (parity == null || week % 2 == parity) result += week
                    sawValidRange = true
                }
            }
    }
    return ParsedWeekExpression(result, parseable = sawValidRange && result.isNotEmpty())
}

private val WEEK_RANGE_PATTERN =
    Regex("""(\d+)\s*(?:-|–|—|－|~|～|到)\s*(\d+)""")

private val WEEK_NUMBER_PATTERN = Regex("""\d+""")

fun isXmuScheduleEntryActiveInWeek(entry: XmuScheduleEntry, week: Int): Boolean {
    val parsed = parseXmuWeekExpression(entry.weeks)
    // Missing/unknown week metadata is retained rather than silently hiding a
    // course that the academic system did return.
    return !parsed.parseable || week in parsed.weeks
}

fun List<XmuScheduleEntry>.forWeek(week: Int): List<XmuScheduleEntry> =
    filter { isXmuScheduleEntryActiveInWeek(it, week) }

/**
 * 预解析整学期课表的周次表达式，返回「命中周 → 该周课程」的索引。
 * 切换周次时直接查索引，避免对每条记录重复跑正则解析。
 * 上限取 25，覆盖可能的 19 周长学期（如 20241/20251），避免第 19 周课程被丢。
 */
fun indexXmuScheduleByWeek(entries: List<XmuScheduleEntry>): Map<Int, List<XmuScheduleEntry>> {
    if (entries.isEmpty()) return emptyMap()
    val maxWeek = 25
    val buckets = Array(maxWeek + 1) { ArrayList<XmuScheduleEntry>() }
    for (entry in entries) {
        val parsed = parseXmuWeekExpression(entry.weeks)
        if (parsed.parseable) {
            for (week in parsed.weeks) {
                if (week in 1..maxWeek) buckets[week] += entry
            }
        } else {
            // 周次不可解析时保留显示（与 forWeek 语义一致）
            for (week in 1..maxWeek) buckets[week] += entry
        }
    }
    return buckets.indices.associateWith { buckets[it] }
}

data class XmuAcademicCalendar(
    val termCode: String,
    val academicYearLabel: String,
    val semesterLabel: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalWeeks: Int = 18,
) {
    val displayLabel: String
        get() = "$academicYearLabel $semesterLabel"
}

enum class XmuTermPhase {
    BEFORE,
    DURING,
    AFTER,
    UNKNOWN,
}

data class XmuAcademicWeek(
    val phase: XmuTermPhase,
    val week: Int?,
    val totalWeeks: Int,
    val date: LocalDate,
)

/**
 * 厦大官方校历数据表（来源：厦门大学官方校历，dblab.xmu.edu.cn 转载）。
 *
 * 每个学期的上课日期与周数都不同（2023-2026 秋季分别是 9/11、9/2、9/1、9/7，
 * 周数 18/19），无法用公式推算，必须查表。termCode 格式：学年 4 位 + 学期 1 位
 * （1=秋季，2=春季，3=夏季/短学期）。
 *
 * 覆盖当前在校生可能遇到的学期；未知学期由调用方用反推兜底。
 */
private val XMU_CALENDARS: Map<String, XmuAcademicCalendar> = buildMap {
    // 2023-2024 学年
    put("20231", XmuAcademicCalendar("20231", "2023—2024学年", "第一学期", LocalDate.of(2023, 9, 11), LocalDate.of(2024, 1, 13), 18))
    put("20232", XmuAcademicCalendar("20232", "2023—2024学年", "第二学期", LocalDate.of(2024, 2, 26), LocalDate.of(2024, 6, 22), 17))
    // 2024-2025 学年
    put("20241", XmuAcademicCalendar("20241", "2024—2025学年", "第一学期", LocalDate.of(2024, 9, 2), LocalDate.of(2025, 1, 11), 19))
    put("20242", XmuAcademicCalendar("20242", "2024—2025学年", "第二学期", LocalDate.of(2025, 2, 17), LocalDate.of(2025, 6, 21), 18))
    // 2025-2026 学年
    put("20251", XmuAcademicCalendar("20251", "2025—2026学年", "第一学期", LocalDate.of(2025, 9, 1), LocalDate.of(2026, 1, 10), 19))
    put("20252", XmuAcademicCalendar("20252", "2025—2026学年", "第二学期", LocalDate.of(2026, 3, 2), LocalDate.of(2026, 6, 27), 17))
    // 2026-2027 学年
    put("20261", XmuAcademicCalendar("20261", "2026—2027学年", "第一学期", LocalDate.of(2026, 9, 7), LocalDate.of(2027, 1, 9), 18))
    put("20262", XmuAcademicCalendar("20262", "2026—2027学年", "第二学期", LocalDate.of(2027, 2, 15), LocalDate.of(2027, 6, 12), 17))
}

/**
 * 已知学期直接查官方校历表；未知学期返回 null，由调用方反推兜底
 * （通过 getZcxx 的当前周次 + 当天日期推算开学日）。
 */
fun xmuAcademicCalendarForTerm(termCode: String): XmuAcademicCalendar? =
    XMU_CALENDARS[termCode]

/**
 * 从系统返回的「当前周次」+ 当天日期反推学期日历（未知学期的兜底方案）。
 *
 * 原理：教务系统 getZcxx.do 返回 currentZc（当前教学周），若当前在教学周内，
 * 则开学日 = 当天日期所在周的周一 - (currentZc - 1) * 7 天。
 *
 * @param termCode 学期代码（用于构造 label）
 * @param currentWeek 系统返回的当前周次（1..19）
 * @param today 当天日期
 * @param totalWeeks 学期总周数（未知时默认 19：厦大秋季多为 18/19 周，
 *                   取 19 可避免 19 周长学期的第 19 周课程被钳制丢失）
 */
fun xmuCalendarInferredFromCurrentWeek(
    termCode: String,
    currentWeek: Int,
    today: LocalDate,
    totalWeeks: Int = 19,
): XmuAcademicCalendar {
    val week = currentWeek.coerceIn(1, totalWeeks)
    // 当天所在周的周一
    val mondayOfToday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    val startDate = mondayOfToday.minusDays((week - 1L) * 7L)
    // 学年标签以学期代码的起始年为准（春季/短学期开课在新一年，startDate.year 会多 1；
    // 例如 20252 → "2025—2026学年 第二学期"，而非 "2026—2027"）
    val startYear = termCode.take(4).toIntOrNull() ?: startDate.year
    val academicYearLabel = "$startYear—${startYear + 1}学年"
    val semesterLabel = when (termCode.lastOrNull()) {
        '2' -> "第二学期"
        '3' -> "夏季学期"
        else -> "第一学期"
    }
    return XmuAcademicCalendar(
        termCode = termCode,
        academicYearLabel = academicYearLabel,
        semesterLabel = semesterLabel,
        startDate = startDate,
        endDate = startDate.plusWeeks((totalWeeks - 1).toLong()).plusDays(6),
        totalWeeks = totalWeeks,
    )
}

/**
 * 尝试从「系统当前周次」+ 当天日期反推学期日历，并做合理性校验。
 *
 * 校验规则：
 * 1. 反推的开学日必须不晚于今天，且距今不超过窗口天数。窗口取
 *    [maxStartAgeDays] 与「当前周次 × 7 + 7」的较大者：学期早期沿用
 *    固定上限（防寒暑假误推），学期中后段（第 9 周以后）仍能反推，
 *    保证表外学期整学期都能自动校准开学日；
 * 2. 开学日必须落在合理的开学时间窗内（秋季 9/1-10/15，夏季 6/1-7/31，
 *    春季 1/15-4/30），防止寒暑假期间手动设置周次把开学日反推到假期里。
 * 校验通过才返回日历，否则返回 null（等待下一次刷新校准）。
 */
fun xmuTryInferCalendar(
    termCode: String,
    currentWeek: Int,
    today: LocalDate,
    totalWeeks: Int = 19,
    maxStartAgeDays: Long = 60L,
): XmuAcademicCalendar? {
    val inferred = xmuCalendarInferredFromCurrentWeek(termCode, currentWeek, today, totalWeeks)
    val daysSinceStart = ChronoUnit.DAYS.between(inferred.startDate, today)
    // 第 N 周时开学日距今约 (N-1)*7 天；窗口取上限与「周次×7+7」较大者，
    // 既保留暑假误推防护，又让第 9 周以后的学期中后段也能反推。
    val windowDays = maxOf(maxStartAgeDays, currentWeek.coerceAtLeast(1) * 7L + 7L)
    if (daysSinceStart !in 0L..windowDays) return null
    // 开学时间窗按学期类型区分：
    // - 秋季（...1）：9/1-10/15（厦大秋季开学均为 9 月初：9/1、9/2、9/7、9/11；
    //   暑假 currentZc 返回 1 时若窗口含 8 月会把 8 月误认为开学，必须排除）；
    // - 夏季短学期（...3）：6/1-7/31（厦大短学期 6 月底-7 月底，8 月已在暑假）；
    // - 春季（...2）：1/15-4/30。
    val month = inferred.startDate.monthValue
    val inWindow = when {
        termCode.endsWith("1") ->
            month == 9 || (month == 10 && inferred.startDate.dayOfMonth <= 15)
        termCode.endsWith("3") ->
            month == 6 || month == 7
        else ->
            // 注释约定窗口 1/15-4/30：1 月需做日期下限（寒假初误推防护）；
            // 4 月只有 30 天，month == 4 即满足上限
            (month == 1 && inferred.startDate.dayOfMonth >= 15) || month == 2 || month == 3 || month == 4
    }
    return if (inWindow) inferred else null
}

fun xmuAcademicWeekFor(
    calendar: XmuAcademicCalendar?,
    date: LocalDate,
): XmuAcademicWeek = when {
    calendar == null -> XmuAcademicWeek(XmuTermPhase.UNKNOWN, null, 0, date)
    date.isBefore(calendar.startDate) -> XmuAcademicWeek(
        XmuTermPhase.BEFORE,
        null,
        calendar.totalWeeks,
        date,
    )
    date.isAfter(calendar.endDate) -> XmuAcademicWeek(
        XmuTermPhase.AFTER,
        null,
        calendar.totalWeeks,
        date,
    )
    else -> {
        val week = (ChronoUnit.DAYS.between(calendar.startDate, date) / 7L).toInt() + 1
        XmuAcademicWeek(XmuTermPhase.DURING, week.coerceIn(1, calendar.totalWeeks), calendar.totalWeeks, date)
    }
}

fun xmuWeekStart(calendar: XmuAcademicCalendar?, week: Int): LocalDate? =
    calendar?.startDate?.plusDays((week - 1L) * 7L)

fun xmuWeekDateRange(calendar: XmuAcademicCalendar?, week: Int): String {
    val start = xmuWeekStart(calendar, week) ?: return "日期待设置"
    val end = start.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
    return "${formatter.format(start)}—${formatter.format(end)}"
}

fun xmuWeekdayFrom(date: LocalDate): Int =
    when (date.dayOfWeek.value) {
        DayOfWeek.MONDAY.value -> 1
        DayOfWeek.TUESDAY.value -> 2
        DayOfWeek.WEDNESDAY.value -> 3
        DayOfWeek.THURSDAY.value -> 4
        DayOfWeek.FRIDAY.value -> 5
        DayOfWeek.SATURDAY.value -> 6
        else -> 7
    }

fun xmuWeekdayShort(weekday: Int): String = when (weekday) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> weekday.toString()
}
