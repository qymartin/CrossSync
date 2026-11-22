package io.github.qymartin.crosssync.report

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val dueFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val BACKGROUND_READ = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

private fun at(ms: Long) = timeFmt.format(Instant.ofEpochMilli(ms))

@Composable
fun ReportScreen(openKind: ReportKind? = null) {
    val ctx = LocalContext.current
    val store = remember { ReportStore.get(ctx) }
    val profile = remember { ReportProfile(ctx) }
    val scope = rememberCoroutineScope()
    // Reading the counter makes the screen redraw whenever the store is saved (also from the worker).
    store.changes.collectAsState().value

    var kind by rememberSaveable { mutableStateOf(openKind ?: ReportKind.WEEK) }
    LaunchedEffect(openKind) { openKind?.let { kind = it } }
    var profileOk by remember { mutableStateOf(profile.complete()) }

    val wm = remember { WorkManager.getInstance(ctx) }
    val main by wm.getWorkInfosForUniqueWorkFlow(ReportScheduler.workName(kind)).collectAsState(initial = emptyList())
    val retry by wm.getWorkInfosForUniqueWorkFlow(ReportScheduler.retryName(kind)).collectAsState(initial = emptyList())
    val running = (main + retry).any { it.state == WorkInfo.State.RUNNING }
    val waitingNet = main.any { it.state == WorkInfo.State.ENQUEUED }

    val report = store.report(kind)
    val failure = store.failure(kind)
    val retryAt = store.retryAt(kind).takeIf { it > System.currentTimeMillis() }

    // After the setup form: ask once for background reads, so scheduled reports see fresh data.
    val bgLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        ReportScheduler.onProfileSaved(ctx)
    }
    fun afterSetup() {
        profileOk = true
        scope.launch {
            val ask = try {
                HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE &&
                    BACKGROUND_READ !in HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
            } catch (e: Exception) {
                false
            }
            if (ask) bgLauncher.launch(setOf(BACKGROUND_READ)) else ReportScheduler.onProfileSaved(ctx)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // On the unfolded inner screen the text column stays at a readable width.
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("CrossSync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Report · 健康分析", style = MaterialTheme.typography.bodyMedium)

                KindSelector(kind) { kind = it }

                InfoCard(kind, report, retryAt, running)

                when {
                    running -> Progress("正在生成${kind.label}…")
                    waitingNet && retryAt == null -> Progress("等待网络连接后生成${kind.label}…")
                }

                if (failure != null && !running) FailureCard(kind, failure, report != null) { ReportScheduler.runNow(ctx, kind) }

                if (report != null) ReportArticle(report) else if (!running) EmptyCard(kind)
            }
        }
        if (!profileOk || store.needsKey) SetupOverlay(profile, store.needsKey) { afterSetup() }
    }
}

@Composable
private fun Progress(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun KindSelector(kind: ReportKind, onPick: (ReportKind) -> Unit) {
    Surface(
        modifier = Modifier.semantics { contentDescription = "报告类型" },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ReportKind.entries.forEach { k ->
                val on = k == kind
                Surface(
                    onClick = { onPick(k) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier = Modifier.heightIn(min = 40.dp).semantics { role = Role.RadioButton; selected = on },
                ) {
                    Text(
                        k.label,
                        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(kind: ReportKind, report: Report?, retryAt: Long?, running: Boolean) {
    val ctx = LocalContext.current
    val now = LocalDateTime.now()
    val y = LocalDate.now().year
    val title = report?.title ?: when (kind) {
        ReportKind.WEEK -> "周报"
        ReportKind.MONTH -> "月报"
        ReportKind.YEAR -> "$y 年年报"
    }
    val compare = report?.compare ?: when (kind) {
        ReportKind.WEEK -> "对比前 3 周"
        ReportKind.MONTH -> "对比最近 3 个月"
        ReportKind.YEAR -> "对比 ${y - 4}–$y（月均值）"
    }
    val generated = report?.let { "生成于 ${at(it.generatedAt)}" }
    val schedule = when (kind) {
        ReportKind.YEAR -> "手动生成 · 上次生成：${report?.let { at(it.generatedAt) } ?: "无"}"
        else -> listOfNotNull(
            generated,
            "下次 ${dueFmt.format(ReportPeriods.nextDue(kind, now)!!)}",
            retryAt?.let { "上次没有成功，${at(it)} 自动重试" },
        ).joinToString(" · ")
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(compare, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(schedule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (kind == ReportKind.YEAR) {
                Button(
                    onClick = { ReportScheduler.runNow(ctx, ReportKind.YEAR) },
                    enabled = !running,
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(if (report == null) "生成年报（${y - 4}–$y 月均值）" else "重新生成年报") }
            }
        }
    }
}

@Composable
private fun FailureCard(kind: ReportKind, f: Failure, hasOld: Boolean, onRetry: () -> Unit) {
    val which = when (kind) {
        ReportKind.WEEK -> "本周周报"
        ReportKind.MONTH -> "本月月报"
        ReportKind.YEAR -> "年报"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${which}生成失败", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "${at(f.at)} · ${f.message}。" + if (hasOld) "下面保留的是上一份${kind.label}。" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyCard(kind: ReportKind) {
    Card {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("还没有${kind.label}", style = MaterialTheme.typography.bodyLarge)
            Text(
                when (kind) {
                    ReportKind.WEEK -> "每周日 15:00 用上一周（周日到周六）的周均值自动生成；新周报会替换旧的。"
                    ReportKind.MONTH -> "每月 1 日 15:00 用上个月的周均值自动生成；新月报会替换旧的。"
                    ReportKind.YEAR -> "年报不会自动生成。点上方按钮，用最近 5 年的月均值生成一份；新年报会替换旧的。"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportArticle(r: Report) {
    val dark = isSystemInDarkTheme()
    val (bg, fg) = when (r.grade) {
        "良好" -> if (dark) Color(0xFF1F3B26) to Color(0xFFA8DAB0) else Color(0xFFD7F0DC) to Color(0xFF1B5E20)
        "一般" -> if (dark) Color(0xFF3D3417) to Color(0xFFF2D58A) else Color(0xFFFFF0C2) to Color(0xFF6D4C00)
        else -> if (dark) Color(0xFF4A1F1C) to Color(0xFFF2B8B5) else Color(0xFFFFDAD6) to Color(0xFF8C1D18)
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("综合评价", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    r.grade, color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 3.dp),
                )
                Text(r.direction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(r.summary, style = MaterialTheme.typography.bodyLarge)

            Text("主要发现", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            r.findings.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (f.title.isNotBlank()) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(f.body, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (r.goals.isNotEmpty()) {
                Text(r.goalsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                r.goals.forEach { g ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(g.theme, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("行动：${g.action}", style = MaterialTheme.typography.bodyMedium)
                            Text("目标：${g.target}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}

/** First-time form (and again only if the API key is rejected). Saved values are never shown. */
@Composable
private fun SetupOverlay(profile: ReportProfile, keyRejected: Boolean, onSaved: () -> Unit) {
    val needHeight = profile.heightCm == null
    val needBirth = profile.birthYear == null
    val needSex = profile.sex == null
    val needEth = profile.ethnicity == null

    var height by rememberSaveable { mutableStateOf("") }
    var birth by rememberSaveable { mutableStateOf("") }
    var sex by rememberSaveable { mutableStateOf("male") }
    var eth by rememberSaveable { mutableStateOf("asian") }
    var key by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .imePadding().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.widthIn(max = 400.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(if (keyRejected) "重新填写 API 密钥" else "分析参考资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (keyRejected) "API 密钥无效或已被撤销。填写新的密钥后会重新生成失败的报告。"
                    else "只填一次，加密保存在本机，不在界面显示。发给 Claude 的只有算好的年龄和 BMI，不含身高和出生年份。",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (needHeight) {
                    OutlinedTextField(height, { height = it }, label = { Text("身高（cm）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                if (needBirth) {
                    OutlinedTextField(birth, { birth = it }, label = { Text("出生年份（每年 1 月 1 日自动加一岁）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                if (needSex) Choice("性别", listOf("male" to "男", "female" to "女"), sex) { sex = it }
                if (needEth) Choice("族裔", listOf("asian" to "亚裔", "other" to "其他"), eth) { eth = it }
                OutlinedTextField(key, { key = it.trim() }, label = { Text("Anthropic API 密钥") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(
                    onClick = {
                        val h = height.toDoubleOrNull()
                        val b = birth.toIntOrNull()
                        val thisYear = LocalDate.now().year
                        error = when {
                            needHeight && (h == null || h !in 100.0..250.0) -> "身高请填 100–250 之间的数字"
                            needBirth && (b == null || b !in 1900..thisYear) -> "出生年份请填 4 位数字，例如 1991"
                            !key.startsWith("sk-ant-") -> "API 密钥应以 sk-ant- 开头"
                            else -> null
                        }
                        if (error == null) {
                            if (needHeight) profile.heightCm = h
                            if (needBirth) profile.birthYear = b
                            if (needSex) profile.sex = sex
                            if (needEth) profile.ethnicity = eth
                            profile.apiKey = key
                            onSaved()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, text) -> FilterChip(selected = v == value, onClick = { onPick(v) }, label = { Text(text) }) }
        }
    }
}
