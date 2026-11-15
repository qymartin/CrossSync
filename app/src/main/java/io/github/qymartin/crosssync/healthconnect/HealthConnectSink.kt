package io.github.qymartin.crosssync.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import io.github.qymartin.crosssync.core.DailyValue
import io.github.qymartin.crosssync.core.Metric
import io.github.qymartin.crosssync.core.Sink
import kotlin.reflect.KClass

class HealthConnectSink(context: Context) : Sink {
    private val appContext = context.applicationContext
    private val client by lazy { HealthConnectClient.getOrCreate(appContext) }

    override suspend fun missing(metrics: Set<Metric>): Set<String> {
        if (!isAvailable(appContext)) return setOf(UNAVAILABLE)
        return permissionsFor(metrics) - client.permissionController.getGrantedPermissions()
    }

    override suspend fun upsert(value: DailyValue, recordId: String, version: Long) {
        // Same clientRecordId + higher version => Health Connect replaces the old record.
        val metadata = Metadata.autoRecorded(
            clientRecordId = recordId,
            clientRecordVersion = version,
            device = Device(type = Device.TYPE_WATCH),
        )
        val start = value.date.atStartOfDay().toInstant(value.zoneOffset)
        val end = value.date.plusDays(1).atStartOfDay().toInstant(value.zoneOffset)
        val record: Record = when (value.metric) {
            Metric.TOTAL_CALORIES -> TotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = value.zoneOffset,
                endTime = end,
                endZoneOffset = value.zoneOffset,
                energy = Energy.kilocalories(value.value),
                metadata = metadata,
            )
        }
        client.insertRecords(listOf(record))
    }

    override suspend fun delete(metric: Metric, recordId: String) {
        client.deleteRecords(
            recordType = recordType(metric),
            recordIdsList = emptyList(),
            clientRecordIdsList = listOf(recordId),
        )
    }

    companion object {
        const val UNAVAILABLE = "health_connect_unavailable"

        fun isAvailable(context: Context) =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        private fun recordType(metric: Metric): KClass<out Record> = when (metric) {
            Metric.TOTAL_CALORIES -> TotalCaloriesBurnedRecord::class
        }

        fun permissionsFor(metrics: Set<Metric>): Set<String> =
            metrics.map { HealthPermission.getWritePermission(recordType(it)) }.toSet()
    }
}
