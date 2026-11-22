package io.github.qymartin.crosssync.report

import android.content.Context
import io.github.qymartin.crosssync.core.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

enum class ReportKind(val key: String, val label: String) {
    WEEK("week", "周报"),
    MONTH("month", "月报"),
    YEAR("year", "年报"),
}

data class Finding(val title: String, val body: String)
data class Goal(val theme: String, val action: String, val target: String)

/** One finished report. Only the latest one per kind is kept. */
data class Report(
    val periodKey: String,
    val title: String,
    val compare: String,
    val goalsTitle: String,
    val generatedAt: Long,
    val model: String,
    val grade: String,
    val direction: String,
    val summary: String,
    val findings: List<Finding>,
    val goals: List<Goal>,
)

data class Failure(val at: Long, val message: String)

/**
 * Reference data for the analysis. Entered once, kept encrypted with the Android Keystore,
 * never shown again. Only the age (worked out here) and BMI leave the phone.
 */
class ReportProfile(context: Context) {
    private val s = SecureStore(context, "report")

    var heightCm: Double?
        get() = s.get("height")?.toDoubleOrNull()
        set(v) = s.put("height", v?.toString())
    var birthYear: Int?
        get() = s.get("birthYear")?.toIntOrNull()
        set(v) = s.put("birthYear", v?.toString())
    /** "male" or "female". */
    var sex: String?
        get() = s.get("sex")
        set(v) = s.put("sex", v)
    /** "asian" or "other". */
    var ethnicity: String?
        get() = s.get("ethnicity")
        set(v) = s.put("ethnicity", v)
    var apiKey: String?
        get() = s.get("apiKey")
        set(v) = s.put("apiKey", v)

    fun complete() = heightCm != null && birthYear != null && sex != null && ethnicity != null && !apiKey.isNullOrBlank()

    /** Goes up by one every 1 January. */
    fun age(today: LocalDate = LocalDate.now()): Int? = birthYear?.let { today.year - it }
}

/**
 * The latest report of each kind plus its generation state, in one small JSON file
 * (reports.json) inside the app. New reports replace old ones; nothing is archived.
 */
class ReportStore private constructor(private val file: File) {
    private val reports = mutableMapOf<ReportKind, Report>()
    private val failures = mutableMapOf<ReportKind, Failure>()
    /** The period a scheduled run was last started for, so it is not started twice. */
    private val attemptedMap = mutableMapOf<ReportKind, String>()
    /** When the next automatic retry is due (0 = none). */
    private val retryMap = mutableMapOf<ReportKind, Long>()

    /** True when the API key was rejected: the setup form is shown again. */
    var needsKey: Boolean = false
        private set

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    init {
        load()
    }

    @Synchronized fun report(k: ReportKind) = reports[k]
    @Synchronized fun failure(k: ReportKind) = failures[k]
    @Synchronized fun attempted(k: ReportKind) = attemptedMap[k]
    @Synchronized fun retryAt(k: ReportKind) = retryMap[k] ?: 0L

    @Synchronized
    fun markAttempted(k: ReportKind, period: String) {
        attemptedMap[k] = period
        save()
    }

    @Synchronized
    fun saveReport(k: ReportKind, r: Report) {
        reports[k] = r
        failures.remove(k)
        retryMap.remove(k)
        save()
    }

    @Synchronized
    fun saveFailure(k: ReportKind, f: Failure) {
        failures[k] = f
        retryMap.remove(k)
        save()
    }

    @Synchronized
    fun setRetryAt(k: ReportKind, at: Long) {
        retryMap[k] = at
        save()
    }

    @Synchronized
    fun setNeedsKey(v: Boolean) {
        needsKey = v
        save()
    }

    @Synchronized
    private fun save() {
        val root = JSONObject().put("needsKey", needsKey)
        ReportKind.entries.forEach { k ->
            val o = JSONObject()
            reports[k]?.let { o.put("report", toJson(it)) }
            failures[k]?.let { o.put("failure", JSONObject().put("at", it.at).put("message", it.message)) }
            attemptedMap[k]?.let { o.put("attempted", it) }
            retryMap[k]?.let { o.put("retryAt", it) }
            root.put(k.key, o)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
        _changes.value = _changes.value + 1
    }

    private fun toJson(r: Report) = JSONObject()
        .put("periodKey", r.periodKey).put("title", r.title).put("compare", r.compare)
        .put("goalsTitle", r.goalsTitle).put("generatedAt", r.generatedAt).put("model", r.model)
        .put("grade", r.grade).put("direction", r.direction).put("summary", r.summary)
        .put("findings", JSONArray().apply { r.findings.forEach { put(JSONObject().put("title", it.title).put("body", it.body)) } })
        .put("goals", JSONArray().apply {
            r.goals.forEach { put(JSONObject().put("theme", it.theme).put("action", it.action).put("target", it.target)) }
        })

    private fun reportOf(o: JSONObject): Report {
        val f = o.optJSONArray("findings") ?: JSONArray()
        val g = o.optJSONArray("goals") ?: JSONArray()
        return Report(
            o.optString("periodKey"), o.optString("title"), o.optString("compare"), o.optString("goalsTitle"),
            o.optLong("generatedAt"), o.optString("model"), o.optString("grade"), o.optString("direction"),
            o.optString("summary"),
            (0 until f.length()).map { f.getJSONObject(it).let { x -> Finding(x.optString("title"), x.optString("body")) } },
            (0 until g.length()).map {
                g.getJSONObject(it).let { x -> Goal(x.optString("theme"), x.optString("action"), x.optString("target")) }
            },
        )
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            needsKey = root.optBoolean("needsKey")
            ReportKind.entries.forEach { k ->
                val o = root.optJSONObject(k.key) ?: return@forEach
                o.optJSONObject("report")?.let { reports[k] = reportOf(it) }
                o.optJSONObject("failure")?.let { failures[k] = Failure(it.optLong("at"), it.optString("message")) }
                o.optString("attempted").takeIf { it.isNotEmpty() }?.let { attemptedMap[k] = it }
                o.optLong("retryAt").takeIf { it > 0 }?.let { retryMap[k] = it }
            }
        } catch (e: Exception) {
            reports.clear(); failures.clear(); attemptedMap.clear(); retryMap.clear()
        }
    }

    companion object {
        @Volatile
        private var instance: ReportStore? = null

        fun get(context: Context): ReportStore = instance ?: synchronized(this) {
            instance ?: ReportStore(File(context.applicationContext.filesDir, "reports.json")).also { instance = it }
        }
    }
}
