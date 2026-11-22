package io.github.qymartin.crosssync.report

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.qymartin.crosssync.R
import io.github.qymartin.crosssync.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Builds one report: refresh data → weekly averages → Claude → parsed report. */
class ReportGenerator(
    private val context: Context,
    private val source: ReportDataSource = FitnessReportSource(context),
) {
    suspend fun generate(kind: ReportKind): Report {
        val profile = ReportProfile(context)
        val key = profile.apiKey ?: throw KeyInvalidException("还没有填写 API 密钥")
        val height = profile.heightCm ?: throw PermanentException("还没有填写身高")
        source.refresh()
        val p = ReportPeriods.build(kind, LocalDateTime.now(), source.days(), height)
        if (!p.hasData()) throw PermanentException("这一期没有数据，所以没有生成报告")
        val system = ClaudeClient.systemPrompt(p, profile.age() ?: 0, profile.sex ?: "male", profile.ethnicity ?: "other")
        val user = "以下是${p.title}的数据（JSON）：\n" + ReportPeriods.payload(p).toString(2)
        return withContext(Dispatchers.IO) {
            val client = ClaudeClient(key)
            val model = client.latestSonnet()
            parse(client.write(model, system, user), p, model)
        }
    }

    private fun parse(o: JSONObject, p: Period, model: String): Report {
        val grade = o.optString("grade")
        if (grade !in ClaudeClient.GRADES) throw RetryableException("回复里的评级不对")
        val f = o.optJSONArray("findings")
        val g = o.optJSONArray("goals")
        val findings = (0 until (f?.length() ?: 0)).map { f!!.getJSONObject(it) }
            .map { Finding(it.optString("title"), it.optString("body")) }.filter { it.body.isNotBlank() }
        val goals = (0 until (g?.length() ?: 0)).map { g!!.getJSONObject(it) }
            .map { Goal(it.optString("theme"), it.optString("action"), it.optString("target")) }
            .filter { it.action.isNotBlank() }
            .sortedBy { ClaudeClient.THEMES.indexOf(it.theme).let { i -> if (i < 0) 99 else i } }
        val summary = o.optString("summary")
        if (summary.isBlank() || findings.isEmpty()) throw RetryableException("回复内容不完整")
        return Report(
            p.key, p.title, p.compare, p.goalsTitle, System.currentTimeMillis(), model,
            grade, o.optString("direction"), summary, findings, goals,
        )
    }
}

class ReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (inputData.getString(KEY_MODE) == MODE_WATCHDOG) {
            ReportScheduler.ensure(ctx)
            return Result.success()
        }
        val kind = ReportKind.valueOf(inputData.getString(KEY_KIND) ?: return Result.success())
        val auto = inputData.getBoolean(KEY_AUTO, true)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val store = ReportStore.get(ctx)
        if (!ReportProfile(ctx).complete()) return Result.success()

        fun fail(message: String) {
            store.saveFailure(kind, Failure(System.currentTimeMillis(), message))
            if (auto) ReportNotifier.show(ctx, kind, "${kind.label}生成失败", "打开 CrossSync 查看原因")
        }

        try {
            val r = ReportGenerator(ctx).generate(kind)
            store.saveReport(kind, r)
            if (auto) ReportNotifier.show(ctx, kind, "${kind.label}已生成", "打开 CrossSync 查看")
        } catch (e: CancellationException) {
            throw e
        } catch (e: KeyInvalidException) {
            store.setNeedsKey(true)
            fail(e.message ?: "API 密钥无效")
        } catch (e: RetryableException) {
            if (auto && attempt < RETRY_DELAYS_MIN.size) {
                ReportScheduler.retryLater(ctx, kind, attempt + 1, RETRY_DELAYS_MIN[attempt])
            } else {
                fail(e.message ?: "生成失败")
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
        return Result.success()
    }

    companion object {
        const val KEY_MODE = "mode"
        const val KEY_KIND = "kind"
        const val KEY_AUTO = "auto"
        const val KEY_ATTEMPT = "attempt"
        const val MODE_WATCHDOG = "watchdog"
        /** Automatic retries after a failure: 30 minutes, 2 hours, 6 hours. */
        val RETRY_DELAYS_MIN = listOf(30L, 120L, 360L)
    }
}

/** When reports are made: weekly on Sunday 15:00, monthly on the 1st 15:00, yearly by button only. */
object ReportScheduler {
    private const val WATCHDOG = "report-watchdog"

    fun workName(k: ReportKind) = "report-${k.key}"
    fun retryName(k: ReportKind) = "report-${k.key}-retry"

    /**
     * Arms the alarms and starts any scheduled report that is due and not yet made
     * (also catches up after the phone was off at 15:00). Safe to call often.
     */
    fun ensure(context: Context) {
        val now = LocalDateTime.now()
        listOf(ReportKind.WEEK, ReportKind.MONTH).forEach { arm(context, it, now) }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReportWorker>(12, TimeUnit.HOURS)
                .setInputData(workDataOf(ReportWorker.KEY_MODE to ReportWorker.MODE_WATCHDOG))
                .build(),
        )
        if (!ReportProfile(context).complete()) return
        val store = ReportStore.get(context)
        listOf(ReportKind.WEEK, ReportKind.MONTH).forEach { k ->
            val key = ReportPeriods.expectedKey(k, now) ?: return@forEach
            if (store.report(k)?.periodKey == key || store.attempted(k) == key) return@forEach
            store.markAttempted(k, key)
            enqueue(context, workName(k), k, auto = true, attempt = 0, delayMin = 0, policy = ExistingWorkPolicy.KEEP)
        }
    }

    /** After the setup form is saved: anything that failed (e.g. on a bad key) is tried again. */
    fun onProfileSaved(context: Context) {
        val store = ReportStore.get(context)
        store.setNeedsKey(false)
        val now = LocalDateTime.now()
        listOf(ReportKind.WEEK, ReportKind.MONTH).forEach { k ->
            val key = ReportPeriods.expectedKey(k, now)
            if (store.failure(k) != null && store.report(k)?.periodKey != key) store.markAttempted(k, "")
        }
        ensure(context)
    }

    /** The year button and the Retry button: one attempt now, no automatic retries. */
    fun runNow(context: Context, k: ReportKind) {
        WorkManager.getInstance(context).cancelUniqueWork(retryName(k))
        ReportStore.get(context).setRetryAt(k, 0)
        enqueue(context, workName(k), k, auto = false, attempt = 0, delayMin = 0, policy = ExistingWorkPolicy.REPLACE)
    }

    fun retryLater(context: Context, k: ReportKind, attempt: Int, delayMin: Long) {
        ReportStore.get(context).setRetryAt(k, System.currentTimeMillis() + delayMin * 60_000)
        enqueue(context, retryName(k), k, auto = true, attempt = attempt, delayMin = delayMin, policy = ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(
        context: Context, name: String, k: ReportKind, auto: Boolean, attempt: Int, delayMin: Long,
        policy: ExistingWorkPolicy,
    ) {
        val req = OneTimeWorkRequestBuilder<ReportWorker>()
            // Waits for a network connection; being offline at 15:00 is not a failure.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMin, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    ReportWorker.KEY_KIND to k.name,
                    ReportWorker.KEY_AUTO to auto,
                    ReportWorker.KEY_ATTEMPT to attempt,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, req)
    }

    private fun arm(context: Context, k: ReportKind, now: LocalDateTime) {
        val next = ReportPeriods.nextDue(k, now) ?: return
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 100 + k.ordinal, Intent(context, ReportAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class ReportAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReportScheduler.ensure(context)
}

class ReportBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ReportScheduler.ensure(context)
}

/** Report notifications carry generic text only, never any health data. */
object ReportNotifier {
    private const val CHANNEL = "report"

    @android.annotation.SuppressLint("MissingPermission")
    fun show(context: Context, kind: ReportKind, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "健康报告", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 10 + kind.ordinal,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_REPORT)
                .putExtra(EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tab_report)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(10 + kind.ordinal, n)
    }

    const val EXTRA_KIND = "report_kind"
}
