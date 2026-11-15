package io.github.qymartin.crosssync.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What CrossSync last wrote for one metric on one day. */
data class LedgerEntry(
    val metric: String,
    val date: String,
    val value: Double,
    val recordId: String,
    val version: Long,
    val syncedAt: Long,
    val ok: Boolean,
    val error: String? = null,
)

/** One sync run, for the in-app log. */
data class RunLog(
    val at: Long,
    val trigger: String,
    val written: Int,
    val skipped: Int,
    val deleted: Int,
    val failed: List<String>,
    val message: String?,
    val from: String = "",
    val to: String = "",
) {
    /** Finished with nothing failed. */
    val ok: Boolean get() = message == null && failed.isEmpty()
}

/**
 * The app's own record of what it wrote, kept as one small JSON file
 * (a year of daily values is a few hundred rows, so no database needed).
 */
class Ledger private constructor(private val file: File) {
    private val entries = linkedMapOf<String, LedgerEntry>()
    private val runs = mutableListOf<RunLog>()

    init {
        load()
    }

    private fun key(metric: String, date: String) = "$metric|$date"

    @Synchronized
    fun get(metric: String, date: String): LedgerEntry? = entries[key(metric, date)]

    @Synchronized
    fun put(e: LedgerEntry) {
        entries[key(e.metric, e.date)] = e
    }

    @Synchronized
    fun remove(metric: String, date: String) {
        entries.remove(key(metric, date))
    }

    @Synchronized
    fun all(): List<LedgerEntry> = entries.values.sortedWith(compareByDescending<LedgerEntry> { it.date }.thenBy { it.metric })

    @Synchronized
    fun runs(): List<RunLog> = runs.toList()

    @Synchronized
    fun addRun(r: RunLog) {
        runs.add(0, r)
        while (runs.size > MAX_RUNS) runs.removeAt(runs.size - 1)
        save()
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        root.put("entries", JSONArray().apply {
            entries.values.forEach { e ->
                put(JSONObject().apply {
                    put("metric", e.metric); put("date", e.date); put("value", e.value)
                    put("recordId", e.recordId); put("version", e.version)
                    put("syncedAt", e.syncedAt); put("ok", e.ok)
                    if (e.error != null) put("error", e.error)
                })
            }
        })
        root.put("runs", JSONArray().apply {
            runs.forEach { r ->
                put(JSONObject().apply {
                    put("at", r.at); put("trigger", r.trigger); put("written", r.written)
                    put("skipped", r.skipped); put("deleted", r.deleted)
                    put("failed", JSONArray(r.failed))
                    if (r.message != null) put("message", r.message)
                    put("from", r.from); put("to", r.to)
                })
            }
        })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            root.optJSONArray("entries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    put(
                        LedgerEntry(
                            metric = o.getString("metric"), date = o.getString("date"),
                            value = o.getDouble("value"), recordId = o.getString("recordId"),
                            version = o.getLong("version"), syncedAt = o.getLong("syncedAt"),
                            ok = o.getBoolean("ok"), error = o.optString("error").ifEmpty { null },
                        )
                    )
                }
            }
            root.optJSONArray("runs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val failed = o.optJSONArray("failed")
                    runs.add(
                        RunLog(
                            at = o.getLong("at"), trigger = o.getString("trigger"),
                            written = o.getInt("written"), skipped = o.getInt("skipped"),
                            deleted = o.getInt("deleted"),
                            failed = List(failed?.length() ?: 0) { failed!!.getString(it) },
                            message = o.optString("message").ifEmpty { null },
                            from = o.optString("from"), to = o.optString("to"),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // A corrupt ledger only costs one extra rewrite of each day; start fresh.
            entries.clear(); runs.clear()
        }
    }

    @Synchronized
    fun toCsv(): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val sb = StringBuilder("date,metric,value,record_id,synced_at,status,error\n")
        all().sortedBy { it.date }.forEach { e ->
            val err = (e.error ?: "").replace("\"", "\"\"")
            sb.append("${e.date},${e.metric},${"%.1f".format(java.util.Locale.US, e.value)},${e.recordId},")
            sb.append("${fmt.format(Instant.ofEpochMilli(e.syncedAt))},${if (e.ok) "ok" else "failed"},\"$err\"\n")
        }
        return sb.toString()
    }

    companion object {
        private const val MAX_RUNS = 30

        @Volatile
        private var instance: Ledger? = null

        fun get(context: Context): Ledger = instance ?: synchronized(this) {
            instance ?: Ledger(File(context.applicationContext.filesDir, "ledger.json")).also { instance = it }
        }
    }
}
