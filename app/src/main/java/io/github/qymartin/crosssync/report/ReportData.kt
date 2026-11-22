package io.github.qymartin.crosssync.report

import android.content.Context
import io.github.qymartin.crosssync.fitness.DayStats
import io.github.qymartin.crosssync.fitness.FitnessRepository
import io.github.qymartin.crosssync.fitness.RefreshMode
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.math.roundToLong

/** Where the Report module gets daily values from. It only reads; today that is the Fitness cache. */
interface ReportDataSource {
    /** Bring the data up to date if possible (failures are ignored: the cached data is used). */
    suspend fun refresh()
    fun days(): Map<LocalDate, DayStats>
}

class FitnessReportSource(context: Context) : ReportDataSource {
    private val repo = FitnessRepository(context)

    override suspend fun refresh() {
        try {
            repo.refresh(RefreshMode.AUTO)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No permission or Health Connect busy: use what is cached.
        }
    }

    override fun days() = repo.store.snapshot()
}

/** The values a report is written from. Each is a weekly average (monthly average in the yearly report). */
enum class RMetric(val label: String, val decimals: Int) {
    CALORIES("总消耗(kcal)", 0),
    WEIGHT("体重(kg)", 1),
    BMI("BMI", 1),
    BODY_FAT("体脂(%)", 1),
    SLEEP("总睡眠(小时)", 2),
    LIGHT("浅睡占比(%)", 1),
    DEEP("深睡占比(%)", 1),
    REM("REM占比(%)", 1),
}

/** One weekly (or monthly) average. Metrics with no data are left out. */
data class Avg(val label: String, val values: Map<RMetric, Double>)

data class Group(val label: String, val rows: List<Avg>)

/** Everything one report is made from. The last group is the period being reported on. */
data class Period(
    val kind: ReportKind,
    val key: String,
    val title: String,
    val compare: String,
    val goalsTitle: String,
    val unit: String,
    val groups: List<Group>,
) {
    fun hasData() = groups.lastOrNull()?.rows?.isNotEmpty() == true
}

object ReportPeriods {
    val DUE_TIME: LocalTime = LocalTime.of(15, 0)
    private val md = DateTimeFormatter.ofPattern("MM-dd")

    /** The last Saturday before [today] (a week runs Sunday to Saturday). */
    fun lastCompleteSaturday(today: LocalDate): LocalDate =
        today.minusDays((today.dayOfWeek.value % 7 + 1).toLong())

    /** The most recent scheduled run at or before [now]. */
    fun lastDue(kind: ReportKind, now: LocalDateTime): LocalDateTime? {
        val today = now.toLocalDate()
        return when (kind) {
            ReportKind.WEEK -> {
                var sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
                if (sunday == today && now.toLocalTime() < DUE_TIME) sunday = sunday.minusWeeks(1)
                sunday.atTime(DUE_TIME)
            }
            ReportKind.MONTH -> {
                var first = today.withDayOfMonth(1)
                if (first == today && now.toLocalTime() < DUE_TIME) first = first.minusMonths(1)
                first.atTime(DUE_TIME)
            }
            ReportKind.YEAR -> null
        }
    }

    fun nextDue(kind: ReportKind, now: LocalDateTime): LocalDateTime? = when (kind) {
        ReportKind.WEEK -> lastDue(kind, now)!!.plusWeeks(1)
        ReportKind.MONTH -> lastDue(kind, now)!!.plusMonths(1)
        ReportKind.YEAR -> null
    }

    /** Which period the scheduled report due at or before [now] covers. */
    fun expectedKey(kind: ReportKind, now: LocalDateTime): String? {
        val due = lastDue(kind, now) ?: return null
        return when (kind) {
            ReportKind.WEEK -> due.toLocalDate().minusDays(1).toString()
            ReportKind.MONTH -> YearMonth.from(due).minusMonths(1).toString()
            ReportKind.YEAR -> null
        }
    }

    /**
     * Builds the period for a report generated at [now].
     *  - week: the week ending the Saturday before the last Sunday 15:00, plus the 3 weeks before it;
     *  - month: the month before the last 1st 15:00, plus the 2 months before it, as weekly averages
     *    (a week belongs to the month its Saturday falls in);
     *  - year: this year so far and the 4 years before, as monthly averages of the weekly averages.
     */
    fun build(kind: ReportKind, now: LocalDateTime, days: Map<LocalDate, DayStats>, heightCm: Double): Period {
        val h = heightCm / 100.0
        return when (kind) {
            ReportKind.WEEK -> {
                val sat = lastDue(kind, now)!!.toLocalDate().minusDays(1)
                val weeks = (3 downTo 0).map { sat.minusWeeks(it.toLong()) }
                val rows = weeks.mapNotNull { weekAvg(days, it, h) }
                val (before, current) = rows.partition { it.label != weekLabel(sat) }
                Period(
                    kind, sat.toString(),
                    "周报 · ${md.format(sat.minusDays(6))} 至 ${md.format(sat)}",
                    "对比前 3 周", "下周目标与行动", "周均值",
                    listOf(Group("前 3 周", before), Group("本周", current)),
                )
            }
            ReportKind.MONTH -> {
                val ym = YearMonth.from(lastDue(kind, now)!!).minusMonths(1)
                val months = (2 downTo 0).map { ym.minusMonths(it.toLong()) }
                val groups = months.map { m -> Group(m.toString(), saturdaysIn(m).mapNotNull { weekAvg(days, it, h) }) }
                Period(
                    kind, ym.toString(),
                    "${ym.year} 年 ${ym.monthValue} 月月报",
                    "对比 ${months.first().monthValue}–${ym.monthValue} 月", "${ym.plusMonths(1).monthValue} 月目标与行动", "周均值",
                    groups,
                )
            }
            ReportKind.YEAR -> {
                val today = now.toLocalDate()
                val lastSat = lastCompleteSaturday(today)
                val y = today.year
                val groups = (y - 4..y).map { year ->
                    Group(year.toString(), (1..12).mapNotNull { mo ->
                        val m = YearMonth.of(year, mo)
                        val weeks = saturdaysIn(m).filter { !it.isAfter(lastSat) }.mapNotNull { weekAvg(days, it, h) }
                        mean(m.toString(), weeks)
                    })
                }
                Period(
                    kind, y.toString(), "$y 年年报", "对比 ${y - 4}–$y（月均值）", "未来 12 个月目标与行动", "月均值",
                    groups,
                )
            }
        }
    }

    private fun saturdaysIn(m: YearMonth): List<LocalDate> {
        var d = m.atDay(1)
        while (d.dayOfWeek != DayOfWeek.SATURDAY) d = d.plusDays(1)
        return generateSequence(d) { it.plusWeeks(1) }.takeWhile { YearMonth.from(it) == m }.toList()
    }

    private fun weekLabel(sat: LocalDate) = "${sat.minusDays(6)}~${md.format(sat)}"

    /** Average of the days that have data in the week ending [sat]. null if the whole week is empty. */
    private fun weekAvg(days: Map<LocalDate, DayStats>, sat: LocalDate, heightM: Double): Avg? {
        val week = (6 downTo 0).mapNotNull { days[sat.minusDays(it.toLong())] }
        fun avg(f: (DayStats) -> Double?) = week.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        val v = mutableMapOf<RMetric, Double>()
        avg { it.calories }?.let { v[RMetric.CALORIES] = it }
        avg { it.weightKg }?.let { v[RMetric.WEIGHT] = it; v[RMetric.BMI] = it / (heightM * heightM) }
        avg { it.bodyFatPct }?.let { v[RMetric.BODY_FAT] = it }
        avg { d -> d.sleepMin?.let { it / 60.0 } }?.let { v[RMetric.SLEEP] = it }
        avg { it.lightPct }?.let { v[RMetric.LIGHT] = it }
        avg { it.deepPct }?.let { v[RMetric.DEEP] = it }
        avg { it.remPct }?.let { v[RMetric.REM] = it }
        return if (v.isEmpty()) null else Avg(weekLabel(sat), v)
    }

    /** Average of several averages, per metric, over the ones that have it. */
    private fun mean(label: String, rows: List<Avg>): Avg? {
        val v = RMetric.entries.mapNotNull { m ->
            rows.mapNotNull { it.values[m] }.takeIf { it.isNotEmpty() }?.average()?.let { m to it }
        }.toMap()
        return if (v.isEmpty()) null else Avg(label, v)
    }

    private fun round(m: RMetric, v: Double): Any =
        if (m.decimals == 0) v.roundToLong() else (v * 10.0.pow(m.decimals)).roundToLong() / 10.0.pow(m.decimals)

    private fun metricsJson(values: Map<RMetric, Double>) = JSONObject().apply {
        RMetric.entries.forEach { m -> values[m]?.let { put(m.label, round(m, it)) } }
    }

    /**
     * What is sent to Claude: the averages and the key numbers worked out here
     * (group averages, change, highest and lowest, nights under 7 hours). No daily values.
     */
    fun payload(p: Period): JSONObject {
        val data = JSONArray()
        p.groups.forEach { g -> g.rows.forEach { r -> data.put(metricsJson(r.values).put("分组", g.label).put("周期", r.label)) } }

        val groupAvgs = p.groups.map { g -> g.label to mean(g.label, g.rows) }
        val avgJson = JSONArray()
        groupAvgs.forEach { (label, a) -> if (a != null) avgJson.put(metricsJson(a.values).put("分组", label)) }

        val key = JSONObject().put("各分组的平均值", avgJson)

        val last = groupAvgs.lastOrNull()?.second
        val prev = groupAvgs.dropLast(1).lastOrNull()?.second
        if (last != null && prev != null) {
            val change = RMetric.entries.mapNotNull { m ->
                val a = last.values[m]; val b = prev.values[m]
                if (a != null && b != null) m to (a - b) else null
            }.toMap()
            key.put("${groupAvgs.last().first} 相比 ${groupAvgs[groupAvgs.size - 2].first} 的变化", metricsJson(change))
        }

        val all = p.groups.flatMap { it.rows }
        val high = JSONObject(); val low = JSONObject()
        RMetric.entries.forEach { m ->
            val withM = all.filter { m.of(it) != null }
            withM.maxByOrNull { m.of(it)!! }?.let { high.put(m.label, JSONObject().put("周期", it.label).put("值", round(m, m.of(it)!!))) }
            withM.minByOrNull { m.of(it)!! }?.let { low.put(m.label, JSONObject().put("周期", it.label).put("值", round(m, m.of(it)!!))) }
        }
        key.put("最高", high).put("最低", low)

        val under7 = JSONArray()
        p.groups.forEach { g ->
            val s = g.rows.mapNotNull { it.values[RMetric.SLEEP] }
            if (s.isNotEmpty()) under7.put(JSONObject().put("分组", g.label).put("低于7小时", s.count { it < 7.0 }).put("共", s.size))
        }
        key.put("总睡眠低于7小时的${if (p.unit == "周均值") "周" else "月"}数", under7)

        return JSONObject()
            .put("报告", p.kind.label)
            .put("报告周期", p.title)
            .put("数据单位", p.unit)
            .put("数据", data)
            .put("本地算好的关键数字", key)
    }

    private fun RMetric.of(a: Avg) = a.values[this]
}
