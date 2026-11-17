package io.github.qymartin.crosssync.sync

import io.github.qymartin.crosssync.core.Metric
import io.github.qymartin.crosssync.export.ExportProvider
import io.github.qymartin.crosssync.export.ExportRow

/** Sync's part of the export: every value written to Health Connect, with its status. */
class SyncExport(private val ledger: Ledger) : ExportProvider {
    override fun exportRows(): List<ExportRow> = ledger.all().map { e ->
        ExportRow(
            date = e.date,
            type = e.metric,
            value = e.value,
            unit = Metric.fromKey(e.metric)?.unit ?: "",
            source = "sync→health_connect",
            status = if (e.ok) "ok" else "failed",
            syncedAt = e.syncedAt,
            error = e.error,
        )
    }
}
