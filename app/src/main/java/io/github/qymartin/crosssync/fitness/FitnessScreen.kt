package io.github.qymartin.crosssync.fitness

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import io.github.qymartin.crosssync.healthconnect.HealthConnectSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Line colors; light and dark sets so every line keeps enough contrast on its card. */
private data class Palette(val orange: Color, val blue: Color, val lightBlue: Color, val indigo: Color, val purple: Color)
private val DarkPalette = Palette(Color(0xFFF2A65A), Color(0xFF7AB8FF), Color(0xFFA9C8FF), Color(0xFF6474E8), Color(0xFFD59BF0))
private val LightPalette = Palette(Color(0xFFB85A00), Color(0xFF1565C0), Color(0xFF5B8DEF), Color(0xFF2E3A9E), Color(0xFF9C27B0))

private const val STALE_MS = 6 * 3_600_000L
private val updatedFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun f0(v: Double) = String.format(Locale.US, "%,.0f", v)
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

/** Which lines are hidden, per chart ("chart:line"), remembered across launches. */
private class HiddenPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fitness", Context.MODE_PRIVATE)
    fun load(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()
    fun save(v: Set<String>) = prefs.edit().putStringSet("hidden", v).apply()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FitnessScreen() {
    val ctx = LocalContext.current
    val repo = remember { FitnessRepository(ctx) }
    val hiddenPrefs = remember { HiddenPrefs(ctx) }
    val scope = rememberCoroutineScope()

    var version by remember { mutableIntStateOf(0) }
    var grain by rememberSaveable { mutableStateOf(Grain.DAY) }
    var hidden by remember { mutableStateOf(hiddenPrefs.load()) }
    var missing by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh(full: Boolean) {
        if (busy != null) return
        scope.launch {
            busy = "正在读取…"
            try {
                repo.refresh(full) { busy = it }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "读取 Health Connect 失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
                version++
            }
        }
    }

    suspend fun checkAndMaybeRefresh() {
        val m = repo.reader.missingPermissions()
        missing = m
        val stale = System.currentTimeMillis() - repo.store.lastRefreshAt > STALE_MS
        if (m.isEmpty() && (!repo.store.fullLoaded || stale)) refresh(false)
    }

    val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { checkAndMaybeRefresh() }
    }
    LaunchedEffect(Unit) { checkAndMaybeRefresh() }

    val days = remember(version) { repo.store.snapshot() }
    val series = remember(version, grain) { Aggregation.build(days, grain) }

    fun toggle(key: String) {
        hidden = if (key in hidden) hidden - key else hidden + key
        hiddenPrefs.save(hidden)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CrossSync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Fitness · Health Connect 数据", style = MaterialTheme.typography.bodyMedium)

        val m = missing
        if (m != null && m.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (HealthConnectSink.UNAVAILABLE in m) {
                        Text("这台手机上 Health Connect 不可用。")
                    } else {
                        Text("需要读取权限", fontWeight = FontWeight.Bold)
                        Text("Fitness 只读取 Health Connect 里的总卡路里、体重、体脂率和睡眠，不写入任何数据。请同时允许读取历史数据，才能看到 30 天以前的记录。",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { permLauncher.launch(repo.reader.requiredPermissions()) }) { Text("授权读取") }
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GrainSelector(grain, { grain = it })
            Spacer(Modifier.weight(1f))
            // Manual refresh re-reads all history, so data added to Health Connect later
            // (e.g. a backfill of old months) shows up. Auto refresh only re-reads the last 7 days.
            OutlinedButton(onClick = { refresh(true) }, enabled = busy == null && m?.isEmpty() == true) { Text("刷新") }
        }
        val updated = repo.store.lastRefreshAt.takeIf { it > 0 }?.let { updatedFmt.format(Instant.ofEpochMilli(it)) }
        Text(
            (if (updated != null) "数据更新于 $updated" else "尚未读取数据") + " · 不含今天",
            style = MaterialTheme.typography.bodySmall,
        )
        if (days.isNotEmpty()) {
            Text(coverage(days), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        busy?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { err ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(err, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { refresh(true) }) { Text("重试") }
                        TextButton(onClick = { error = null }) { Text("忽略") }
                    }
                }
            }
        }

        val pal = if (isSystemInDarkTheme()) DarkPalette else LightPalette
        val Orange = pal.orange; val Blue = pal.blue; val LightBlue = pal.lightBlue; val Indigo = pal.indigo; val Purple = pal.purple
        val White = MaterialTheme.colorScheme.onSurface
        val v = series.values
        val charts = listOf(
            "总卡路里" to listOf(
                ChartLine("总卡路里（kcal）", Orange, v.getValue(Field.CALORIES), format = ::f0),
            ),
            "体重 / 体脂率" to listOf(
                ChartLine("体重（kg）", Blue, v.getValue(Field.WEIGHT), connectGaps = true, format = ::f1),
                ChartLine("体脂率（%）", Orange, v.getValue(Field.BODY_FAT), rightAxis = true, dashed = true,
                    connectGaps = true, format = { f1(it) + "%" }),
            ),
            "睡眠" to listOf(
                ChartLine("总睡眠（小时）", White, v.getValue(Field.SLEEP_HOURS), format = { f1(it) + "h" }),
                ChartLine("浅睡 %", LightBlue, v.getValue(Field.LIGHT), rightAxis = true, dashed = true, format = { f0(it) + "%" }),
                ChartLine("深睡 %", Indigo, v.getValue(Field.DEEP), rightAxis = true, dashed = true, format = { f0(it) + "%" }),
                ChartLine("REM %", Purple, v.getValue(Field.REM), rightAxis = true, dashed = true, format = { f0(it) + "%" }),
            ),
        )

        @Composable
        fun ChartAt(ci: Int, modifier: Modifier) {
            val (title, lines) = charts[ci]
            val visible = lines.indices.filter { "$ci:$it" !in hidden }.toSet()
            ChartCard(title, grain.sub, series.labels, series.xTitle, lines, visible, { toggle("$ci:$it") }, modifier)
        }

        // Unfolded inner screen (>= 600dp): first two charts side by side, sleep full width.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 600.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChartAt(0, Modifier.weight(1f))
                        ChartAt(1, Modifier.weight(1f))
                    }
                    ChartAt(2, Modifier.fillMaxWidth())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    charts.indices.forEach { ChartAt(it, Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

/** e.g. "已读取：卡路里 183 天（2026-05-19 起）· 体重 12 天（…）…" — shows how far back each kind of data goes. */
private fun coverage(days: Map<java.time.LocalDate, DayStats>): String {
    val parts = listOf(
        "卡路里" to { d: DayStats -> d.calories }, "体重" to { d: DayStats -> d.weightKg },
        "体脂" to { d: DayStats -> d.bodyFatPct }, "睡眠" to { d: DayStats -> d.sleepMin },
    ).map { (name, get) ->
        val with = days.filterValues { get(it) != null }.keys
        if (with.isEmpty()) "$name 0 天" else "$name ${with.size} 天（${with.min()} 起）"
    }
    return "已读取：" + parts.joinToString(" · ")
}

@Composable
private fun GrainSelector(grain: Grain, onPick: (Grain) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = "时间粒度" },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Grain.entries.forEach { g ->
                val on = g == grain
                Surface(
                    onClick = { onPick(g) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.heightIn(min = 40.dp).semantics { role = Role.RadioButton; selected = on },
                ) {
                    Text(
                        g.label,
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartCard(
    title: String,
    sub: String,
    labels: List<String>,
    xTitle: String,
    lines: List<ChartLine>,
    visible: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier,
) {
    Card(modifier) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEachIndexed { i, line ->
                    val on = i in visible
                    Surface(
                        onClick = { onToggle(i) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.heightIn(min = 40.dp).semantics {
                            role = Role.Checkbox
                            stateDescription = if (on) "已显示" else "已隐藏"
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val c = if (on) line.color else Color(0xFF6B7080)
                            Canvas(Modifier.size(18.dp, 8.dp)) {
                                drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(),
                                    pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null)
                            }
                            Text(line.name, style = MaterialTheme.typography.bodySmall,
                                color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            LineChart(labels, xTitle, lines, visible)
        }
    }
}
