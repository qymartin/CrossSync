package io.github.qymartin.crosssync.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.qymartin.crosssync.healthconnect.HealthConnectSink
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Reads calories, weight, body fat and sleep from Health Connect.
 *
 * When several apps wrote the same kind of data for a day, one app is used per day
 * (never summed across apps): calories prefer CrossSync, then Garmin Connect;
 * sleep prefers Garmin Connect, then CrossSync; otherwise the app with the most data.
 * Weight and body fat are averaged over all readings of the day.
 */
class HealthConnectFitnessReader(context: Context) : FitnessReader {
    private val ctx = context.applicationContext
    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    override fun requiredPermissions(): Set<String> = setOf(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        // Without this, Health Connect only returns the last 30 days.
        READ_HISTORY,
    )

    override suspend fun missingPermissions(): Set<String> {
        if (!HealthConnectSink.isAvailable(ctx)) return setOf(HealthConnectSink.UNAVAILABLE)
        return requiredPermissions() - client.permissionController.getGrantedPermissions()
    }

    override suspend fun read(from: LocalDate, to: LocalDate): Map<LocalDate, DayStats> {
        // Start a day early so a night that ends on `from` is included.
        val start = from.minusDays(1).atStartOfDay(zone).toInstant()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant()
        val inRange = { d: LocalDate -> !d.isBefore(from) && !d.isAfter(to) }

        val calories = pickPerDay(
            readAll(TotalCaloriesBurnedRecord::class, start, end).map {
                Triple(dateOf(it.startTime, it.startZoneOffset), it.metadata.dataOrigin.packageName, it.energy.inKilocalories)
            },
            listOf(ctx.packageName, GARMIN),
        ).mapValues { (_, v) -> v.sum() }

        val weight = readAll(WeightRecord::class, start, end)
            .groupBy({ dateOf(it.time, it.zoneOffset) }, { it.weight.inKilograms })
            .mapValues { it.value.average() }

        val fat = readAll(BodyFatRecord::class, start, end)
            .groupBy({ dateOf(it.time, it.zoneOffset) }, { it.percentage.value })
            .mapValues { it.value.average() }

        val nights = pickPerDay(
            readAll(SleepSessionRecord::class, start, end).map {
                Triple(dateOf(it.endTime, it.endZoneOffset), it.metadata.dataOrigin.packageName, it)
            },
            listOf(GARMIN, ctx.packageName),
            weight = { s -> Duration.between(s.startTime, s.endTime).toMinutes().toDouble() },
        ).mapValues { (_, sessions) -> sleepOf(sessions) }

        val dates = (calories.keys + weight.keys + fat.keys + nights.keys).filter(inRange).toSortedSet()
        return dates.associateWith { d ->
            val n = nights[d]
            DayStats(calories[d], weight[d], fat[d], n?.total, n?.light, n?.deep, n?.rem)
        }.filterValues { !it.isEmpty() }
    }

    private data class Night(val total: Double?, val light: Double?, val deep: Double?, val rem: Double?)

    /** Total sleep = light + deep + REM (+ unstaged "asleep"); awake time is not counted. */
    private fun sleepOf(sessions: List<SleepSessionRecord>): Night {
        var light = 0.0; var deep = 0.0; var rem = 0.0; var other = 0.0
        sessions.forEach { s ->
            if (s.stages.isEmpty()) {
                other += Duration.between(s.startTime, s.endTime).toMinutes()
            } else s.stages.forEach { st ->
                val m = Duration.between(st.startTime, st.endTime).toMinutes().toDouble()
                when (st.stage) {
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> light += m
                    SleepSessionRecord.STAGE_TYPE_DEEP -> deep += m
                    SleepSessionRecord.STAGE_TYPE_REM -> rem += m
                    SleepSessionRecord.STAGE_TYPE_SLEEPING -> other += m
                    else -> Unit // awake, out of bed, unknown
                }
            }
        }
        val total = light + deep + rem + other
        if (total <= 0) return Night(null, null, null, null)
        val staged = light + deep + rem > 0
        return Night(
            total,
            if (staged) light / total * 100 else null,
            if (staged) deep / total * 100 else null,
            if (staged) rem / total * 100 else null,
        )
    }

    /** For each day keep the records of a single app: the first in [priority], else the one with most data. */
    private fun <T> pickPerDay(
        items: List<Triple<LocalDate, String, T>>,
        priority: List<String>,
        weight: (T) -> Double = { (it as? Double) ?: 1.0 },
    ): Map<LocalDate, List<T>> =
        items.groupBy { it.first }.mapValues { (_, dayItems) ->
            val byApp = dayItems.groupBy({ it.second }, { it.third })
            val app = priority.firstOrNull { it in byApp }
                ?: byApp.maxByOrNull { (_, v) -> v.sumOf(weight) }!!.key
            byApp.getValue(app)
        }

    private fun dateOf(t: Instant, offset: ZoneOffset?): LocalDate =
        t.atOffset(offset ?: zone.rules.getOffset(t)).toLocalDate()

    private suspend fun <T : Record> readAll(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 5000,
                    pageToken = token,
                )
            )
            out += resp.records
            token = resp.pageToken
        } while (token != null)
        return out
    }

    companion object {
        const val GARMIN = "com.garmin.android.apps.connectmobile"
        const val READ_HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    }
}
