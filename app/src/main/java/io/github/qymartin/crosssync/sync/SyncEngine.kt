package io.github.qymartin.crosssync.sync

import io.github.qymartin.crosssync.core.Metric
import io.github.qymartin.crosssync.core.Sink
import io.github.qymartin.crosssync.core.Source
import io.github.qymartin.crosssync.core.recordId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.abs

/**
 * Copies daily values from a [Source] to a [Sink], one day at a time.
 *
 * Rules:
 *  - value unchanged since last successful write -> skip (unless [force])
 *  - value new or changed, or last write failed  -> upsert with a newer version
 *  - source has no value but we wrote one before -> delete ours
 *
 * Source errors (auth, network) abort the run; progress so far is kept in the ledger.
 * Sink errors are recorded per day and reported, and that day is retried next run.
 */
class SyncEngine(
    private val source: Source,
    private val sink: Sink,
    private val ledger: Ledger,
) {
    data class Result(val written: Int, val skipped: Int, val deleted: Int, val failed: List<String>)

    suspend fun run(
        metrics: Set<Metric>,
        from: LocalDate,
        to: LocalDate,
        force: Boolean,
        throttleMs: Long = 600,
    ): Result {
        var written = 0
        var skipped = 0
        var deleted = 0
        val failed = mutableListOf<String>()

        for (metric in metrics) {
            var date = from
            while (!date.isAfter(to)) {
                val d = date.toString()
                val id = recordId(source.id, metric, date)
                val prev = ledger.get(metric.key, d)
                val value = source.fetch(metric, date)

                if (value == null) {
                    if (prev != null) {
                        try {
                            sink.delete(metric, prev.recordId)
                            ledger.remove(metric.key, d)
                            deleted++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed += "$d ${metric.label} 删除失败：${e.message ?: e.javaClass.simpleName}"
                        }
                    } else {
                        skipped++
                    }
                } else if (!force && prev != null && prev.ok && abs(prev.value - value.value) < 0.5) {
                    skipped++
                } else {
                    val version = System.currentTimeMillis()
                    try {
                        sink.upsert(value, id, version)
                        ledger.put(LedgerEntry(metric.key, d, value.value, id, version, version, ok = true))
                        written++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        ledger.put(LedgerEntry(metric.key, d, value.value, id, prev?.version ?: 0, version, ok = false, error = msg))
                        failed += "$d ${metric.label} 写入失败：$msg"
                    }
                }
                ledger.save()
                date = date.plusDays(1)
                if (!date.isAfter(to)) delay(throttleMs)
            }
        }
        return Result(written, skipped, deleted, failed)
    }
}
