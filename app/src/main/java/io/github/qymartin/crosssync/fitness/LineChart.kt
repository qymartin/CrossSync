package io.github.qymartin.crosssync.fitness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.log10
import kotlin.math.pow
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
    /** Suffix for this line's axis labels, e.g. "%" or "h". */
    val unit: String = "",
)


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
    val scheme = MaterialTheme.colorScheme
    val GridColor = scheme.outlineVariant
    val LabelColor = scheme.onSurfaceVariant
    val TitleColor = scheme.onSurface
    val tipBg = scheme.inverseSurface
    val tipFg = scheme.inverseOnSurface
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

        val left = axisFor(shown.filter { !it.rightAxis }, null)
        val right = if (left == null) null else axisFor(shown.filter { it.rightAxis }, left.n)
        if (shown.isEmpty() || left == null || n == 0) {
            drawCentered(measurer, if (shown.isEmpty()) "未选择数据，点上方按钮选择要显示的项目" else "暂无数据",
                Offset(size.width / 2, padT + plotH / 2), TextStyle(color = LabelColor, fontSize = 13.sp))
            return@Canvas
        }
        val xAt = { i: Int -> padL + if (n == 1) plotW / 2 else i * plotW / (n - 1) }
        val yAt = { v: Double, a: Axis -> (padT + plotH - (v - a.lo) / (a.hi - a.lo) * plotH).toFloat() }

        // grid + y labels
        val leftUnit = shown.first { !it.rightAxis }.unit
        val rightUnit = shown.firstOrNull { it.rightAxis }?.unit ?: ""
        for (k in 0..left.n) {
            val y = padT + plotH - k * plotH / left.n
            drawLine(GridColor, Offset(padL, y), Offset(padL + plotW, y), 1.dp.toPx())
            drawRightAligned(measurer, axisLabel(left.lo + k * left.step) + leftUnit, Offset(padL - 6.dp.toPx(), y), small)
            if (right != null) {
                drawLeftAligned(measurer, axisLabel(right.lo + k * right.step) + rightUnit, Offset(padL + plotW + 6.dp.toPx(), y), small)
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
                    width = (if (line.dashed) 2.0 else 2.5).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                ),
            )
        }

        // tapped point
        selected?.let { s ->
            if (s >= n) return@let
            val x = xAt(s)
            drawLine(LabelColor, Offset(x, padT), Offset(x, padT + plotH), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val rows = mutableListOf(labels[s] to tipFg)
            shown.forEach { line ->
                val v = line.values.getOrNull(s)
                rows += "${line.name}：${v?.let(line.format) ?: "—"}" to line.color
                if (v != null) drawCircle(TitleColor, 4.dp.toPx(), Offset(x, yAt(v, if (line.rightAxis) right!! else left)))
            }
            drawTooltip(measurer, rows, x, padT, padL, padL + plotW, tipBg, tipFg)
        }
    }
}

/** An axis from [lo] in [n] equal steps of [step]. */
private data class Axis(val lo: Double, val step: Double, val n: Int) {
    val hi: Double get() = lo + step * n
}

/** Smallest "nice" whole step (1, 2, 5, 10, 20, 25, 50, ...) that is at least [raw]. */
private fun niceStep(raw: Double): Double {
    val p = 10.0.pow(floor(log10(max(raw, 1e-9))))
    for (m in listOf(1.0, 2.0, 2.5, 5.0, 10.0)) {
        val v = m * p
        if (v >= raw && v >= 1.0 && v % 1.0 == 0.0) return v
    }
    return max(1.0, 10 * p)
}

/**
 * Axis for these lines: the data takes up about the middle half of the height (so lines are
 * not overly steep), never goes below 0, ticks are whole numbers, and there are always 5 labels.
 * The right axis passes the left axis' [intervals] so both line up with the same grid.
 */
private fun axisFor(lines: List<ChartLine>, intervals: Int?): Axis? {
    val vals = lines.flatMap { it.values.filterNotNull() }
    if (vals.isEmpty()) return null
    val mn = vals.min()
    val mx = vals.max()
    val pad = maxOf((mx - mn) * 0.5, abs(vals.average()) * 0.03, 1.0)
    val low = max(0.0, mn - pad)
    val high = mx + pad
    // Always 4 steps = 5 labels, on every chart and both axes.
    val n = intervals ?: 4
    var step = niceStep((high - low) / n)
    var lo = floor(low / step) * step
    while (lo + n * step < high) {
        step = niceStep(step * 1.01)
        lo = floor(low / step) * step
    }
    return Axis(lo, step, n)
}

private fun axisLabel(v: Double) = String.format(java.util.Locale.US, "%,.0f", v)

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

private fun DrawScope.drawTooltip(
    m: TextMeasurer, rows: List<Pair<String, Color>>, x: Float, top: Float, minX: Float, maxX: Float, bg: Color, fg: Color,
) {
    val layouts = rows.mapIndexed { i, (t, c) ->
        m.measure(t, TextStyle(color = if (i == 0) fg else c, fontSize = 12.sp,
            fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal))
    }
    val pad = 8.dp.toPx()
    val w = layouts.maxOf { it.size.width } + pad * 2
    val h = layouts.sumOf { it.size.height } + pad * 2
    val bx = min(max(x - w / 2, minX), maxX - w).coerceAtLeast(0f)
    drawRoundRect(bg.copy(alpha = 0.94f), Offset(bx, top), Size(w, h.toFloat()), CornerRadius(8.dp.toPx()))
    var y = top + pad
    layouts.forEach { l ->
        drawText(l, topLeft = Offset(bx + pad, y))
        y += l.size.height
    }
}
