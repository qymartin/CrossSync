package io.github.qymartin.crosssync.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.qymartin.crosssync.core.AuthRequiredException
import io.github.qymartin.crosssync.core.Settings
import io.github.qymartin.crosssync.core.TemporaryException
import io.github.qymartin.crosssync.garmin.GarminSource
import io.github.qymartin.crosssync.healthconnect.HealthConnectSink
import java.time.LocalDate

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        val ledger = Ledger.get(ctx)
        val mode = inputData.getString(KEY_MODE) ?: MODE_RANGE

        if (mode == MODE_WATCHDOG) {
            Scheduler.scheduleDaily(ctx) // re-arm the alarm in case it was lost
            if (GarminSource(ctx).isReady() && System.currentTimeMillis() - settings.lastDailyRunAt > 26 * HOUR) {
                Scheduler.runDaily(ctx, "补跑（定时被系统跳过）")
            }
            return Result.success()
        }

        val from = LocalDate.parse(inputData.getString(KEY_FROM))
        val to = LocalDate.parse(inputData.getString(KEY_TO))
        val force = inputData.getBoolean(KEY_FORCE, false)
        val trigger = inputData.getString(KEY_TRIGGER) ?: "同步"
        val metrics = settings.enabledMetrics
        val label = "$trigger $from ~ $to"

        fun fail(msg: String, needsLogin: Boolean): Result {
            settings.lastError = msg
            settings.lastErrorNeedsLogin = needsLogin
            ledger.addRun(RunLog(System.currentTimeMillis(), label, 0, 0, 0, emptyList(), msg))
            Notifier.alert(ctx, "CrossSync 同步失败", msg)
            return Result.failure()
        }

        if (metrics.isEmpty()) return Result.success()
        val source = GarminSource(ctx)
        val sink = HealthConnectSink(ctx)
        if (!source.isReady()) return fail("尚未登录 Garmin，请打开 CrossSync 登录。", true)
        val missing = sink.missing(metrics)
        if (HealthConnectSink.UNAVAILABLE in missing) return fail("Health Connect 不可用。", false)
        if (missing.isNotEmpty()) return fail("Health Connect 写入权限未授予，请打开 CrossSync 授权。", false)

        return try {
            val r = SyncEngine(source, sink, ledger).run(metrics, from, to, force)
            if (mode == MODE_DAILY) settings.lastDailyRunAt = System.currentTimeMillis()
            val summary = "写入 ${r.written} 条，未变化 ${r.skipped} 条，删除 ${r.deleted} 条"
            if (r.failed.isEmpty()) {
                settings.lastError = null
                ledger.addRun(RunLog(System.currentTimeMillis(), label, r.written, r.skipped, r.deleted, emptyList(), null))
                if (r.written + r.deleted > 0) Notifier.result(ctx, "$label：$summary")
                Result.success()
            } else {
                val msg = "${r.failed.size} 条写入 Health Connect 失败，已安排下次自动重试：\n" + r.failed.joinToString("\n")
                settings.lastError = msg
                settings.lastErrorNeedsLogin = false
                ledger.addRun(RunLog(System.currentTimeMillis(), label, r.written, r.skipped, r.deleted, r.failed, null))
                Notifier.alert(ctx, "CrossSync 覆盖失败", msg)
                Result.success()
            }
        } catch (e: AuthRequiredException) {
            fail(e.message ?: "Garmin 登录已失效", true)
        } catch (e: TemporaryException) {
            if (runAttemptCount < MAX_RETRIES) {
                ledger.addRun(RunLog(System.currentTimeMillis(), label, 0, 0, 0, emptyList(), "${e.message}（稍后重试）"))
                Result.retry()
            } else {
                fail("${e.message}（已重试 $MAX_RETRIES 次）", false)
            }
        } catch (e: Exception) {
            fail("意外错误：${e.javaClass.simpleName} ${e.message ?: ""}", false)
        }
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_FORCE = "force"
        const val KEY_TRIGGER = "trigger"
        const val MODE_DAILY = "daily"
        const val MODE_RANGE = "range"
        const val MODE_WATCHDOG = "watchdog"
        private const val MAX_RETRIES = 3
        private const val HOUR = 3_600_000L
    }
}
