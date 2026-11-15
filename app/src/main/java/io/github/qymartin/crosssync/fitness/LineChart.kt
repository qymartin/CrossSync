package io.github.qymartin.crosssync.fitness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One line on a chart. */
data class ChartLine(
    val name: String,
    val color: Color,
    val values: List<Double?>,
    val rightAxis: Boolean = false,
    val dashed: Boolean = false,
    /** true: join points across missing days (sparse data such as weight). */
    val connectGaps: Boolean = false,
    val format: (Double) -> String,
)

private val GridColor = Color(0xFF2F333C)
private val LabelColor = Color(0xFFA9ADB8)
private val TitleColor = Color(0xFFC9CDD6)

/**
 * Line chart drawn with Canvas. Only [visible] lines are drawn and the axes scale to them;
 * if every visible line belongs to the right axis it moves to the left axis. Tap to see values.
 */
@Composable
fun LineChart(
    labels: List<String>,
    xTitle: String,
    lines: List<ChartLine>,
    visible: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    var selected by remember(labels, visible) { mutableStateOf<Int?>(null) }
    val shown = lines.filterIndexed { i, _ -> i in visible }.let { vis ->
        if (vis.isNotEmpty() && vis.all { it.rightAxis }) vis.map { it.copy(rightAxis = false) } else vis
    }
    val n = labels.size

    Canvas(
        modifier
            .fillMaxWidth()
            .height(232.dp)
            .semantics { contentDescription = "折线图：" + shown.joinToString("、") { it.name } }
            .pointerInput(labels, visible) {
                detectTapGestures { p ->
                    if (n == 0) return@detectTapGestures
                    val padL = 44.dp.toPx()
                    val dual = shown.any { !it.rightAxis } && shown.any { it.rightAxis }
                    val padR = (if (dual) 42 else 12).dp.toPx()
                    val plotW = size.width - padL - padR
                    val i = if (n == 1) 0 else ((p.x - padL) / plotW * (n - 1)).let { Math.round(it).toInt() }.coerceIn(0, n - 1)
                    selected = if (selected == i) null else i
                }
            }
    ) {
        val padL = 44.dp.toPx()
        val dual = shown.any { !it.rightAxis } && shown.any { it.rightAxis }
        val padR = (if (dual) 42 else 12).dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 44.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        val small = TextStyle(color = LabelColor, fontSize = 10.sp)

        // x-axis title, always shown
        drawCentered(measurer, xTitle, Offset(padL + plotW / 2, size.height - 9.dp.toPx()),
            TextStyle(color = TitleColor, fontSize = 11.sp, fontWeight = FontWeight.Medium))

        val left = range(shown.filter { !it.rightAxis })
        val right = range(shown.filter { it.rightAxis })
        if (shown.isEmpty() || left == null || n == 0) {
            drawCentered(measurer, if (shown.isEmpty()) "未选择数据，点上方按钮选择要显示的项目" else "暂无数据",
                Offset(size.width / 2, padT + plotH / 2), TextStyle(color = LabelColor, fontSize = 13.sp))
            return@Canvas
        }
        val xAt = { i: Int -> padL + if (n == 1) plotW / 2 else i * plotW / (n - 1) }
        val yAt = { v: Double, r: ClosedFloatingPointRange<Double> ->
            (padT + plotH - (v - r.start) / (r.endInclusive - r.start) * plotH).toFloat()
        }

        // grid + y labels
        val leftFmt = shown.first { !it.rightAxis }.format
        val rightFmt = shown.firstOrNull { it.rightAxis }?.format
        for (k in 0..3) {
            val y = padT + plotH - k * plotH / 3
            drawLine(GridColor, Offset(padL, y), Offset(padL + plotW, y), 1.dp.toPx())
            val lv = left.start + k * (left.endInclusive - left.start) / 3
            drawRightAligned(measurer, leftFmt(lv), Offset(padL - 6.dp.toPx(), y), small)
            if (right != null && rightFmt != null) {
                val rv = right.start + k * (right.endInclusive - right.start) / 3
                drawLeftAligned(measurer, rightFmt(rv), Offset(padL + plotW + 6.dp.toPx(), y), small)
            }
        }

        // x labels: as many as fit, the latest always shown
        val maxT = max(2, floor(plotW / 74.dp.toPx()).toInt())
        val step = max(1, ceil((n - 1).toDouble() / (maxT - 1)).toInt())
        var i = n - 1
        while (i >= 0) {
            drawCentered(measurer, labels[i], Offset(xAt(i), padT + plotH + 14.dp.toPx()), small)
            i -= step
        }

        // lines
        val dotR = if (n <= 40) 2.5.dp.toPx() else 0f
        shown.forEach { line ->
            val r = if (line.rightAxis) right!! else left
            val path = Path()
            var pen = false
            line.values.forEachIndexed { idx, v ->
                if (v == null) {
                    if (!line.connectGaps) pen = false
                    return@forEachIndexed
                }
                val p = Offset(xAt(idx), yAt(v, r))
                if (pen) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                pen = true
                if (dotR > 0) drawCircle(line.color, dotR, p)
            }
            drawPath(
                path, line.color,
                style = Stroke(
                    width = (if (line.dashed) 2 else 2.5).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                ),
            )
        }

        // tapped point
        selected?.let { s ->
            if (s >= n) return@let
            val x = xAt(s)
            drawLine(Color(0xFF8A90A0), Offset(x, padT), Offset(x, padT + plotH), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val rows = mutableListOf(labels[s] to Color.White)
            shown.forEach { line ->
                val v = line.values.getOrNull(s)
                rows += "${line.name}：${v?.let(line.format) ?: "—"}" to line.color
                if (v != null) drawCircle(Color.White, 4.dp.toPx(), Offset(x, yAt(v, if (line.rightAxis) right!! else left)))
            }
            drawTooltip(measurer, rows, x, padT, padL, padL + plotW)
        }
    }
}

private fun range(lines: List<ChartLine>): ClosedFloatingPointRange<Double>? {
    val vals = lines.flatMap { it.values.filterNotNull() }
    if (vals.isEmpty()) return null
    var lo = vals.min()
    var hi = vals.max()
    val pad = if (abs(hi - lo) < 1e-9) max(1.0, abs(hi) * 0.05) else (hi - lo) * 0.12
    lo -= pad; hi += pad
    return lo..hi
}

private fun DrawScope.drawCentered(m: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val l = m.measure(text, style)
    drawText(l, topLeft = Offset(center.x - l.size.width / 2f, center.y - l.size.height / 2f))
}

private fun DrawScope.drawRightAligned(m: TextMeasurer, text: String, anchor: Offset, style: TextStyle) {
    val l = m.measure(text, style)
    drawText(l, topLeft = Offset(anchor.x - l.size.width, anchor.y - l.size.height / 2f))
}

private fun DrawScope.drawLeftAligned(m: TextMeasurer, text: String, anchor: Offset, style: TextStyle) {
    val l = m.measure(text, style)
    drawText(l, topLeft = Offset(anchor.x, anchor.y - l.size.height / 2f))
}

private fun DrawScope.drawTooltip(m: TextMeasurer, rows: List<Pair<String, Color>>, x: Float, top: Float, minX: Float, maxX: Float) {
    val layouts = rows.mapIndexed { i, (t, c) ->
        m.measure(t, TextStyle(color = if (i == 0) Color.White else c, fontSize = 12.sp,
            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal))
    }
    val pad = 8.dp.toPx()
    val w = layouts.maxOf { it.size.width } + pad * 2
    val h = layouts.sumOf { it.size.height } + pad * 2
    val bx = min(max(x - w / 2, minX), maxX - w).coerceAtLeast(0f)
    drawRoundRect(Color(0xF03A3F4B), Offset(bx, top), Size(w, h.toFloat()), CornerRadius(8.dp.toPx()))
    var y = top + pad
    layouts.forEach { l ->
        drawText(l, topLeft = Offset(bx + pad, y))
        y += l.size.height
    }
}
