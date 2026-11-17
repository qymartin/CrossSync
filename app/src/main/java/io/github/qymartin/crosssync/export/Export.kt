package io.github.qymartin.crosssync.export

import android.content.Context
import io.github.qymartin.crosssync.fitness.FitnessExport
import io.github.qymartin.crosssync.fitness.FitnessStore
import io.github.qymartin.crosssync.sync.Ledger
import io.github.qymartin.crosssync.sync.SyncExport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One value of one data type on one day: the unit of the export file. */
data class ExportRow(
    val date: String,
    val type: String,
    val value: Double,
    val unit: String,
    val source: String,
    val status: String = "",
    val syncedAt: Long? = null,
    val error: String? = null,
)

/** Each module exports its own data through this; modules never read each other's data. */
interface ExportProvider {
    fun exportRows(): List<ExportRow>
}

/**
 * Builds the single CSV that holds everything CrossSync has processed. New modules or new
 * data types only add providers or rows: the columns stay the same.
 */
object Exporter {
    fun providers(context: Context): List<ExportProvider> = listOf(
        SyncExport(Ledger.get(context)),
        FitnessExport(FitnessStore.get(context)),
    )

    fun csv(context: Context): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        val rows = providers(context).flatMap { it.exportRows() }.sortedWith(compareBy({ it.date }, { it.type }, { it.source }))
        val sb = StringBuilder("date,type,value,unit,source,status,synced_at,error\n")
        rows.forEach { r ->
            val value = if (r.value % 1.0 == 0.0) r.value.toLong().toString() else String.format(Locale.US, "%.2f", r.value)
            val synced = r.syncedAt?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: ""
            val err = (r.error ?: "").replace("\"", "\"\"")
            sb.append("${r.date},${r.type},$value,${r.unit},${r.source},${r.status},$synced,\"$err\"\n")
        }
        return sb.toString()
    }
}
