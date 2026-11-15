package io.github.qymartin.crosssync.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.qymartin.crosssync.core.Settings
import io.github.qymartin.crosssync.garmin.GarminAuth
import io.github.qymartin.crosssync.garmin.LoginActivity
import io.github.qymartin.crosssync.healthconnect.HealthConnectSink
import io.github.qymartin.crosssync.core.Metric
import io.github.qymartin.crosssync.sync.Ledger
import io.github.qymartin.crosssync.sync.RunLog
import io.github.qymartin.crosssync.sync.Notifier
import io.github.qymartin.crosssync.sync.Scheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        Scheduler.scheduleDaily(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val ledger = remember { Ledger.get(ctx) }
    var tick by remember { mutableIntStateOf(0) }

    // Refresh whenever the screen comes back or a sync job changes state.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val works by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(Scheduler.WORK_NAME) }
        .collectAsState(initial = emptyList())
    val running = works.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    LaunchedEffect(works) { tick++ }

    val t = tick // read so everything below re-reads on refresh
    val loggedIn = remember(t) { GarminAuth(ctx).isLoggedIn() }
    var hcMissing by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(t) {
        val missing = HealthConnectSink(ctx).missing(settings.enabledMetrics)
        hcMissing = missing
        // First time both sides are ready: import the last 180 days automatically.
        if (loggedIn && missing.isEmpty() && !settings.backfillQueued) {
            Scheduler.runBackfill(ctx)
            toast(ctx, "已开始回填过去 180 天")
        }
    }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) {
            if (settings.lastErrorNeedsLogin) settings.lastError = null
            toast(ctx, "Garmin 登录成功")
        }
        tick++
    }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(ledger.toCsv().toByteArray()) }
            toast(ctx, "已导出")
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val login = { loginLauncher.launch(Intent(ctx, LoginActivity::class.java)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CrossSync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Garmin → Health Connect", style = MaterialTheme.typography.bodyMedium)

        settings.lastError?.let { err ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("同步出现问题", fontWeight = FontWeight.Bold)
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (settings.lastErrorNeedsLogin) Button(onClick = login) { Text("重新登录") }
                        else Button(onClick = { retryFailed(ctx, ledger); tick++ }) { Text("重试") }
                        TextButton(onClick = { settings.lastError = null; tick++ }) { Text("忽略") }
                    }
                }
            }
        }

        Section("Garmin Connect") {
            if (loggedIn) {
                Text("已登录：${settings.displayName ?: "（用户名获取中）"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = login) { Text("重新登录") }
                    TextButton(onClick = {
                        GarminAuth(ctx).logout(); settings.displayName = null; tick++
                    }) { Text("退出登录") }
                }
            } else {
                Text("未登录。登录在 Garmin 官方页面完成，CrossSync 不保存你的密码。")
                Button(onClick = login) { Text("登录 Garmin") }
            }
        }

        Section("Health Connect") {
            val missing = hcMissing
            when {
                missing == null -> Text("检查中…")
                HealthConnectSink.UNAVAILABLE in missing -> Text("这台手机上 Health Connect 不可用。")
                missing.isEmpty() -> Text("已授权写入 ✓")
                else -> Button(onClick = { hcLauncher.launch(HealthConnectSink.permissionsFor(settings.enabledMetrics)) }) {
                    Text("授权写入")
                }
            }
            OutlinedButton(onClick = {
                runCatching { ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            }) { Text("打开 Health Connect 设置") }
            Text(
                "建议：关闭 Garmin Connect 自带的 Health Connect 同步；在「数据来源和优先级」里把 CrossSync 排第一；「自动删除」选从不。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("同步设置") {
            SubTitle("同步数据类型")
            Metric.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, Modifier.weight(1f))
                    Switch(checked = m in settings.enabledMetrics, onCheckedChange = { on ->
                        settings.enabledMetrics = if (on) settings.enabledMetrics + m else settings.enabledMetrics - m
                        tick++
                    })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SubTitle("自动同步")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每天 %02d:%02d".format(settings.syncHour, settings.syncMinute), Modifier.weight(1f))
                TextButton(onClick = {
                    TimePickerDialog(ctx, { _, h, m ->
                        settings.syncHour = h; settings.syncMinute = m
                        Scheduler.scheduleDaily(ctx); tick++
                    }, settings.syncHour, settings.syncMinute, true).show()
                }) { Text("修改时间") }
            }
            Text("每次同步过去 7 个完整日：与 Garmin 一致的跳过，不一致的覆盖。", style = MaterialTheme.typography.bodySmall)
            if (settings.nextRunAt > 0) {
                Text("下次同步：${timeFmt.format(Instant.ofEpochMilli(settings.nextRunAt))}", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SubTitle("手动同步")
            if (running) Text("同步进行中…", color = MaterialTheme.colorScheme.primary)
            Button(onClick = { Scheduler.runDaily(ctx, "手动同步"); toast(ctx, "已开始同步"); tick++ },
                modifier = Modifier.fillMaxWidth()) { Text("同步过去 7 天") }
            OutlinedButton(onClick = {
                pickRange(ctx) { from, to ->
                    Scheduler.runRange(ctx, from, to, force = true, trigger = "指定范围")
                    toast(ctx, "已开始同步 $from ~ $to"); tick++
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("同步指定日期范围（强制覆盖）") }
        }

        Section("导出") {
            Text("把 CrossSync 写入过的全部数据导出为 CSV 文件，作为独立备份。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { exportLauncher.launch("crosssync-${LocalDate.now()}.csv") },
                modifier = Modifier.fillMaxWidth()) { Text("导出 CSV") }
        }

        Section("同步记录") {
            val runs = ledger.runs()
            val ok = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)
            val bad = MaterialTheme.colorScheme.error
            val last = runs.firstOrNull()
            if (last == null) {
                Text("暂无记录")
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("最近一次同步", fontWeight = FontWeight.Bold)
                        Text(
                            if (last.ok) "成功" else "失败",
                            color = if (last.ok) ok else bad,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        Text("时间：${timeFmt.format(Instant.ofEpochMilli(last.at))}")
                        Text("范围：${rangeOf(last.from, last.to)}")
                        Text("方式：${last.trigger}")
                        Text(detailOf(last), color = if (last.ok) MaterialTheme.colorScheme.onSurfaceVariant else bad)
                    }
                }
            }
            if (runs.size > 1) SubTitle("历史")
            runs.drop(1).take(20).forEach { r ->
                Row {
                    Text(if (r.ok) "✓ " else "✗ ", color = if (r.ok) ok else bad, fontWeight = FontWeight.Bold)
                    Text(
                        "${timeFmt.format(Instant.ofEpochMilli(r.at))}  ${r.trigger}  ${rangeOf(r.from, r.to)}\n${detailOf(r)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubTitle(text: String) = Text(text, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)

private fun rangeOf(from: String, to: String) = if (from.isEmpty()) "—" else if (from == to) from else "$from ~ $to"

private fun detailOf(r: RunLog): String = r.message
    ?: ("写入 ${r.written}，未变化 ${r.skipped}，删除 ${r.deleted}" +
        if (r.failed.isNotEmpty()) "，失败 ${r.failed.size}：\n" + r.failed.joinToString("\n") else "")

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** Re-run the dates that failed (they are not marked ok, so they get rewritten), else a normal daily run. */
private fun retryFailed(ctx: Context, ledger: Ledger) {
    val failed = ledger.all().filter { !it.ok }.map { LocalDate.parse(it.date) }
    if (failed.isEmpty()) Scheduler.runDaily(ctx, "手动重试")
    else Scheduler.runRange(ctx, failed.min(), failed.max(), force = false, trigger = "重试失败项")
    toast(ctx, "已开始重试")
}

private fun pickRange(ctx: Context, onPicked: (LocalDate, LocalDate) -> Unit) {
    val y = LocalDate.now().minusDays(1)
    DatePickerDialog(ctx, { _, y1, m1, d1 ->
        val from = LocalDate.of(y1, m1 + 1, d1)
        DatePickerDialog(ctx, { _, y2, m2, d2 ->
            val to = LocalDate.of(y2, m2 + 1, d2)
            if (to.isBefore(from)) toast(ctx, "结束日期不能早于开始日期") else onPicked(from, to)
        }, y.year, y.monthValue - 1, y.dayOfMonth).apply { setTitle("结束日期") }.show()
    }, y.year, y.monthValue - 1, y.dayOfMonth).apply { setTitle("开始日期") }.show()
}
