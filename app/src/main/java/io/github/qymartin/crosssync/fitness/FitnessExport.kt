package io.github.qymartin.crosssync.fitness

import io.github.qymartin.crosssync.export.ExportProvider
import io.github.qymartin.crosssync.export.ExportRow

/** Fitness's part of the export: every daily value in the cache, with where it came from. */
class FitnessExport(private val store: FitnessStore) : ExportProvider {
    override fun exportRows(): List<ExportRow> = store.snapshotWithSource().flatMap { (date, s, source) ->
        listOf(
            Triple("calories", s.calories, "kcal"),
            Triple("weight", s.weightKg, "kg"),
            Triple("body_fat", s.bodyFatPct, "%"),
            Triple("sleep", s.sleepMin, "min"),
            Triple("sleep_light", s.lightPct, "%"),
            Triple("sleep_deep", s.deepPct, "%"),
            Triple("sleep_rem", s.remPct, "%"),
        ).mapNotNull { (type, v, unit) ->
            v?.let { ExportRow(date.toString(), type, it, unit, source) }
        }
    }
}
