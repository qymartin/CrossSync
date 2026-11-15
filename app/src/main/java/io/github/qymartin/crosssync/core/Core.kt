package io.github.qymartin.crosssync.core

import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Data types CrossSync knows about. Adding a type = add an entry here,
 * teach the Source to fetch it and the Sink to write it.
 */
enum class Metric(val key: String, val label: String, val unit: String) {
    TOTAL_CALORIES("total_calories", "总卡路里消耗", "kcal");

    companion object {
        fun fromKey(key: String): Metric? = entries.firstOrNull { it.key == key }
    }
}

/** The one shape every Source produces and every Sink consumes: one value per day. */
data class DailyValue(
    val metric: Metric,
    val date: LocalDate,
    val value: Double,
    /** UTC offset of the user's local day, so midnight lands on the right instant. */
    val zoneOffset: ZoneOffset,
)

/** Where data comes from (today: Garmin Connect). */
interface Source {
    /** Short stable id, used in record ids — never change it once data is written. */
    val id: String
    fun isReady(): Boolean

    /** Value for one day, or null when the source has no data for that day. */
    suspend fun fetch(metric: Metric, date: LocalDate): DailyValue?
}

/** Where data goes (today: Health Connect). */
interface Sink {
    /** Permissions / prerequisites still missing for these metrics (empty = ready). */
    suspend fun missing(metrics: Set<Metric>): Set<String>

    /** Insert, or replace the record that has the same [recordId]. Throws on failure. */
    suspend fun upsert(value: DailyValue, recordId: String, version: Long)

    /** Delete a record this app wrote earlier. Throws on failure. */
    suspend fun delete(metric: Metric, recordId: String)
}

fun recordId(sourceId: String, metric: Metric, date: LocalDate) = "$sourceId-${metric.key}-$date"

/** The user must sign in again; retrying will not help. */
class AuthRequiredException(message: String) : Exception(message)

/** Temporary problem (network, rate limit); retrying later may help. */
class TemporaryException(message: String, cause: Throwable? = null) : IOException(message, cause)
