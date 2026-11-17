package io.github.qymartin.crosssync.fitness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/** One day of fitness data. null = no data that day. */
data class DayStats(
    val calories: Double? = null,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val sleepMin: Double? = null,
    val lightPct: Double? = null,
    val deepPct: Double? = null,
    val remPct: Double? = null,
) {
    fun values() = listOf(calories, weightKg, bodyFatPct, sleepMin, lightPct, deepPct, remPct)
    fun isEmpty() = values().all { it == null }
}

/** The values the Fitness charts can show. */
enum class Field(val get: (DayStats) -> Double?) {
    CALORIES({ it.calories }),
    WEIGHT({ it.weightKg }),
    BODY_FAT({ it.bodyFatPct }),
    SLEEP_HOURS({ d -> d.sleepMin?.let { it / 60.0 } }),
    LIGHT({ it.lightPct }),
    DEEP({ it.deepPct }),
    REM({ it.remPct }),
}

/** Where Fitness data is read from (today: Health Connect). Read-only by design. */
interface FitnessReader {
    fun requiredPermissions(): Set<String>

    /** Permissions still missing (empty = ready). */
    suspend fun missingPermissions(): Set<String>

    /** One entry per local date in [from, to] that has any data. */
    suspend fun read(from: LocalDate, to: LocalDate): Map<LocalDate, DayStats>
}

/**
 * Local cache of daily fitness values, one small JSON file on the phone
 * (same approach as the sync ledger). Charts are drawn from here.
 *
 * Two layers, split at [CUTOFF]:
 *  - history: imported once from a Google Health export, used for days before the cutoff;
 *  - days: read from Health Connect, used for days from the cutoff on.
 */
class FitnessStore private constructor(private val file: File) {
    private val days = TreeMap<LocalDate, DayStats>()
    private val history = TreeMap<LocalDate, DayStats>()

    var lastRefreshAt: Long = 0
        private set
    /** Health Connect has been read at least once since the cutoff. */
    var hcLoaded: Boolean = false
        private set
    var historySource: String? = null
        private set

    init {
        load()
    }

    @Synchronized
    fun snapshot(): Map<LocalDate, DayStats> = TreeMap<LocalDate, DayStats>().apply {
        putAll(history.headMap(CUTOFF, false))
        putAll(days.tailMap(CUTOFF, true))
    }

    @Synchronized
    fun hasHistory() = history.isNotEmpty()

    @Synchronized
    fun historyFirstDay(): LocalDate? = history.keys.firstOrNull()

    /** Replace Health Connect data in [from, to] (never before the cutoff) with [data]. */
    @Synchronized
    fun replaceRange(from: LocalDate, to: LocalDate, data: Map<LocalDate, DayStats>) {
        val start = maxOf(from, CUTOFF)
        if (start.isAfter(to)) return
        days.subMap(start, true, to, true).clear()
        data.forEach { (d, s) -> if (!d.isBefore(start) && !d.isAfter(to) && !s.isEmpty()) days[d] = s }
    }

    @Synchronized
    fun markRefreshed() {
        lastRefreshAt = System.currentTimeMillis()
        hcLoaded = true
        save()
    }

    /**
     * One-off import of a "crosssync-history" file (made from Google Takeout).
     * Keeps only days before the cutoff. Returns the number of days imported.
     */
    @Synchronized
    fun importHistory(text: String): Int {
        val root = JSONObject(text)
        require(root.optString("format") == "crosssync-history") { "不是 CrossSync 历史数据文件" }
        val obj = root.getJSONObject("days")
        val imported = TreeMap<LocalDate, DayStats>()
        obj.keys().forEach { k ->
            val d = LocalDate.parse(k)
            val s = statsOf(obj.getJSONArray(k))
            if (d.isBefore(CUTOFF) && !s.isEmpty()) imported[d] = s
        }
        require(imported.isNotEmpty()) { "文件里没有 ${CUTOFF} 以前的数据" }
        history.clear()
        history.putAll(imported)
        historySource = root.optString("source").ifEmpty { "导入文件" }
        save()
        return imported.size
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
            .put("lastRefreshAt", lastRefreshAt)
            .put("hcLoaded", hcLoaded)
            .put("days", toJson(days))
            .put("history", toJson(history))
        historySource?.let { root.put("historySource", it) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun toJson(map: Map<LocalDate, DayStats>) = JSONObject().apply {
        map.forEach { (d, s) -> put(d.toString(), JSONArray().apply { s.values().forEach { put(it ?: JSONObject.NULL) } }) }
    }

    private fun statsOf(a: JSONArray): DayStats {
        fun v(i: Int): Double? = if (i >= a.length() || a.isNull(i)) null else a.getDouble(i)
        return DayStats(v(0), v(1), v(2), v(3), v(4), v(5), v(6))
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            lastRefreshAt = root.optLong("lastRefreshAt")
            // 1.1 caches used "fullLoaded"; their data from the cutoff on is still valid.
            hcLoaded = root.optBoolean("hcLoaded", root.optBoolean("fullLoaded"))
            historySource = root.optString("historySource").ifEmpty { null }
            root.optJSONObject("days")?.let { o ->
                o.keys().forEach { k ->
                    val d = LocalDate.parse(k)
                    if (!d.isBefore(CUTOFF)) days[d] = statsOf(o.getJSONArray(k)) // drop pre-cutoff Health Connect data
                }
            }
            root.optJSONObject("history")?.let { o ->
                o.keys().forEach { k -> history[LocalDate.parse(k)] = statsOf(o.getJSONArray(k)) }
            }
        } catch (e: Exception) {
            // A broken cache: Health Connect data is re-read on the next refresh.
            days.clear(); history.clear(); lastRefreshAt = 0; hcLoaded = false; historySource = null
        }
    }

    companion object {
        /** Before this day: imported Google Health history. From this day on: Health Connect. */
        val CUTOFF: LocalDate = LocalDate.of(2026, 7, 1)

        @Volatile
        private var instance: FitnessStore? = null

        fun get(context: Context): FitnessStore = instance ?: synchronized(this) {
            instance ?: FitnessStore(File(context.applicationContext.filesDir, "fitness.json")).also { instance = it }
        }
    }
}

enum class RefreshMode {
    /** Opening the page with data older than 6 hours: re-check the last 14 days. */
    AUTO,
    /** The refresh button: re-check the last 6 months. */
    MANUAL,
}

/** Reads from a [FitnessReader] into the [FitnessStore]. Never reads before the cutoff. */
class FitnessRepository(context: Context, val reader: FitnessReader = HealthConnectFitnessReader(context)) {
    val store = FitnessStore.get(context)

    suspend fun refresh(mode: RefreshMode, progress: (String) -> Unit = {}) {
        val cutoff = FitnessStore.CUTOFF
        val yesterday = LocalDate.now().minusDays(1)
        val window = when (mode) {
            RefreshMode.AUTO -> yesterday.minusDays(13)
            RefreshMode.MANUAL -> yesterday.minusMonths(6)
        }
        // Never read yet: everything since the cutoff. Otherwise the window, stretched back
        // to the last refresh if that was longer ago, so no days are skipped.
        val lastDay = Instant.ofEpochMilli(store.lastRefreshAt).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(1)
        val from = if (!store.hcLoaded) cutoff else maxOf(cutoff, minOf(window, lastDay))
        var start = from
        while (!start.isAfter(yesterday)) {
            val end = minOf(start.withDayOfYear(1).plusYears(1).minusDays(1), yesterday)
            progress("正在读取 Health Connect（$start 至 $end）…")
            store.replaceRange(start, end, reader.read(start, end))
            start = end.plusDays(1)
        }
        store.markRefreshed()
    }
}
