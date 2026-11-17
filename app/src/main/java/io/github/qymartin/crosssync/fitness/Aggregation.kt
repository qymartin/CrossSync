package io.github.qymartin.crosssync.fitness

import java.time.LocalDate
import java.time.YearMonth

enum class Grain(val label: String, val sub: String) {
    DAY("日", "每日数值"),
    MONTH("月", "每月日平均"),
    YEAR("年", "每年日平均"),
}

/** What one grain looks like on the x axis, with one value list per [Field]. */
data class Series(
    val labels: List<String>,
    val xTitle: String,
    val values: Map<Field, List<Double?>>,
)

object Aggregation {
    /**
     * DAY: the 21 days ending yesterday.
     * MONTH: the last 12 months including this one, each the daily average of that month.
     * YEAR: every year with at least one data point, each the daily average of that year.
     * Averages only count days that have a value; today is never included.
     */
    fun build(days: Map<LocalDate, DayStats>, grain: Grain, today: LocalDate = LocalDate.now()): Series {
        val y = today.minusDays(1)
        val buckets: List<Pair<String, List<DayStats>>> = when (grain) {
            Grain.DAY -> (20 downTo 0).map { y.minusDays(it.toLong()) }
                .map { d -> d.toString() to listOfNotNull(days[d]) }
            Grain.MONTH -> (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }.map { ym ->
                ym.toString() to days.filterKeys { YearMonth.from(it) == ym && !it.isAfter(y) }.values.toList()
            }
            Grain.YEAR -> days.filterKeys { !it.isAfter(y) }.entries
                .groupBy({ it.key.year }, { it.value }).toSortedMap()
                .map { (year, list) -> year.toString() to list }
        }
        val labels = buckets.map { it.first }
        val values = Field.entries.associateWith { f ->
            buckets.map { (_, list) ->
                val v = list.mapNotNull(f.get)
                if (v.isEmpty()) null else v.average()
            }
        }
        val range = if (labels.isEmpty()) "暂无数据" else "${labels.first()} 至 ${labels.last()}"
        return Series(labels, "时间粒度：${grain.label} · $range", values)
    }
}
