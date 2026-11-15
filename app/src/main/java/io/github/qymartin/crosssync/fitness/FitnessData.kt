package io.github.qymartin.crosssync.fitness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
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
 */
class FitnessStore private constructor(private val file: File) {
    private val days = TreeMap<LocalDate, DayStats>()

    var lastRefreshAt: Long = 0
        private set
    var fullLoaded: Boolean = false
        private set

    init {
        load()
    }

    @Synchronized
    fun snapshot(): Map<LocalDate, DayStats> = TreeMap(days)

    /** Replace everything in [from, to] with [data] (days missing from [data] are dropped). */
    @Synchronized
    fun replaceRange(from: LocalDate, to: LocalDate, data: Map<LocalDate, DayStats>) {
        days.subMap(from, true, to, true).clear()
        data.forEach { (d, s) -> if (!d.isBefore(from) && !d.isAfter(to) && !s.isEmpty()) days[d] = s }
    }

    @Synchronized
    fun markRefreshed(full: Boolean) {
        lastRefreshAt = System.currentTimeMillis()
        if (full) fullLoaded = true
        save()
    }

    @Synchronized
    fun save() {
        val obj = JSONObject()
        days.forEach { (d, s) ->
            obj.put(d.toString(), JSONArray().apply { s.values().forEach { put(it ?: JSONObject.NULL) } })
        }
        val root = JSONObject()
            .put("lastRefreshAt", lastRefreshAt)
            .put("fullLoaded", fullLoaded)
            .put("days", obj)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            lastRefreshAt = root.optLong("lastRefreshAt")
            fullLoaded = root.optBoolean("fullLoaded")
            val obj = root.getJSONObject("days")
            obj.keys().forEach { k ->
                val a = obj.getJSONArray(k)
                fun v(i: Int): Double? = if (i >= a.length() || a.isNull(i)) null else a.getDouble(i)
                days[LocalDate.parse(k)] = DayStats(v(0), v(1), v(2), v(3), v(4), v(5), v(6))
            }
        } catch (e: Exception) {
            // A broken cache is simply rebuilt from Health Connect on the next refresh.
            days.clear(); lastRefreshAt = 0; fullLoaded = false
        }
    }

    companion object {
        @Volatile
        private var instance: FitnessStore? = null

        fun get(context: Context): FitnessStore = instance ?: synchronized(this) {
            instance ?: FitnessStore(File(context.applicationContext.filesDir, "fitness.json")).also { instance = it }
        }
    }
}

/** Reads from a [FitnessReader] into the [FitnessStore]. */
class FitnessRepository(context: Context, val reader: FitnessReader = HealthConnectFitnessReader(context)) {
    val store = FitnessStore.get(context)

    /**
     * First time: everything since [EARLIEST]. After that: the past 7 days, or back to the
     * day before the last refresh if that is older, so nothing in between is missed.
     * Today is never included (the day is not over yet).
     */
    suspend fun refresh(full: Boolean = false, progress: (String) -> Unit = {}) {
        val yesterday = LocalDate.now().minusDays(1)
        val doFull = full || !store.fullLoaded
        val from = if (doFull) EARLIEST else {
            val last = java.time.Instant.ofEpochMilli(store.lastRefreshAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().minusDays(1)
            minOf(yesterday.minusDays(6), last)
        }
        var start = from
        while (!start.isAfter(yesterday)) {
            val end = minOf(start.withDayOfYear(1).plusYears(1).minusDays(1), yesterday)
            progress(if (doFull) "正在读取 ${start.year} 年数据…" else "正在读取最近数据…")
            store.replaceRange(start, end, reader.read(start, end))
            start = end.plusDays(1)
        }
        store.markRefreshed(doFull)
    }

    companion object {
        val EARLIEST: LocalDate = LocalDate.of(2010, 1, 1)
    }
}
