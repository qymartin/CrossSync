package io.github.qymartin.crosssync.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.qymartin.crosssync.core.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object Scheduler {
    const val WORK_NAME = "sync"
    private const val WATCHDOG = "watchdog"
    const val BACKFILL_DAYS = 180L

    /** Arms the exact alarm for the next daily sync time and the watchdog. */
    fun scheduleDaily(context: Context) {
        val s = Settings(context)
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(s.syncHour, s.syncMinute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        s.nextRunAt = at

        // Safety net if the alarm is ever lost: every 12 h, run if the daily run is overdue.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(networkConstraint())
                .setInputData(workDataOf(SyncWorker.KEY_MODE to SyncWorker.MODE_WATCHDOG))
                .build(),
        )
    }

    /** Daily run: the last 7 complete days. */
    fun runDaily(context: Context, trigger: String) {
        val y = LocalDate.now().minusDays(1)
        enqueue(context, y.minusDays(6), y, force = false, trigger = trigger, mode = SyncWorker.MODE_DAILY)
    }

    /** First-time import of the last 180 days, in 30-day chunks so each job stays short. */
    fun runBackfill(context: Context) {
        val y = LocalDate.now().minusDays(1)
        var start = y.minusDays(BACKFILL_DAYS - 1)
        while (!start.isAfter(y)) {
            val end = minOf(start.plusDays(29), y)
            enqueue(context, start, end, force = false, trigger = "历史回填")
            start = end.plusDays(1)
        }
        Settings(context).backfillQueued = true
    }

    fun runRange(context: Context, from: LocalDate, to: LocalDate, force: Boolean, trigger: String) {
        val y = LocalDate.now().minusDays(1)
        var start = from
        val last = minOf(to, y)
        while (!start.isAfter(last)) {
            val end = minOf(start.plusDays(29), last)
            enqueue(context, start, end, force, trigger)
            start = end.plusDays(1)
        }
    }

    private fun enqueue(
        context: Context, from: LocalDate, to: LocalDate, force: Boolean, trigger: String,
        mode: String = SyncWorker.MODE_RANGE,
    ) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_MODE to mode,
                    SyncWorker.KEY_FROM to from.toString(),
                    SyncWorker.KEY_TO to to.toString(),
                    SyncWorker.KEY_FORCE to force,
                    SyncWorker.KEY_TRIGGER to trigger,
                )
            )
            .build()
        // Jobs run one after another, never in parallel.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun networkConstraint() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.runDaily(context, "每日定时")
        Scheduler.scheduleDaily(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleDaily(context)
    }
}
