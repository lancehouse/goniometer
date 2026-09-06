package com.lancehouse.goniometer.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.lancehouse.goniometer.Mark
import com.lancehouse.goniometer.Sample
import kotlin.math.abs
import kotlin.math.ceil

private val PrimaryColor = Color(0xFF2E7D32)
private val SecondaryColors = listOf(Color(0xFF7A96A3), Color(0xFF9AA7AE))
private val MarkColor = Color(0xFFEF6C00)
private val SelectedMarkColor = Color(0xFFD84315)
private val BaselineColor = Color(0xFFBDBDBD)
private val GridColor = Color(0xFFECECEC)

/** Deterministic colour per channel — primary green, others by ascending channel order. Shared so the legend and the traces agree. */
internal fun romChannelColor(channel: Int, primaryIndex: Int): Color {
    if (channel == primaryIndex) return PrimaryColor
    val rank = (0..2).filter { it != primaryIndex }.indexOf(channel).coerceAtLeast(0)
    return SecondaryColors[rank % SecondaryColors.size]
}

/**
 * Custom Canvas line chart — no external charting dependency. Draws all three
 * angle channels over time, emphasises [primaryChannelIndex], renders a 0°
 * baseline, and renders marks as ticks. Coordinate math is shared with the PNG
 * renderer via ChartGeometry so the two don't drift.
 *
 * [detailed] = true (used on the read-only session View screen) adds degree
 * gridlines with °-labels down the left gutter and time labels along the
 * bottom, and makes the chart taller. The small inline uses keep it compact.
 *
 * [selectedMarkIndices] holds 0, 1, or 2 mark indices (tapped in the chip row
 * below the chart). One selected → that mark gets a dot on the primary trace
 * and its angle label. Two selected → a shaded band spans the interval between
 * them.
 */
@Composable
fun RomChart(
    samples: List<Sample>,
    marks: List<Mark>,
    primaryChannelIndex: Int,
    selectedMarkIndices: List<Int> = emptyList(),
    detailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (detailed) 300.dp else 220.dp)
    ) {
        if (samples.size < 2) return@Canvas

        val leftPad = if (detailed) 64f else 0f
        val bottomPad = if (detailed) 28f else 0f
        val plotW = size.width - leftPad
        val plotH = size.height - bottomPad

        val bounds = computeBounds(samples)
        fun px(tMs: Long): Float = leftPad + normX(tMs, bounds, plotW)
        fun py(v: Float): Float = normY(v, bounds, plotH)

        // Gridlines + axis labels (detailed only).
        if (detailed) {
            val degPaint = Paint().apply {
                color = BaselineColor.toArgb()
                textSize = 26f
                isAntiAlias = true
                textAlign = Paint.Align.RIGHT
            }
            val step = niceStep(bounds.vSpan, target = 5)
            var g = ceil(bounds.vMin / step) * step
            while (g <= bounds.vMax) {
                val y = py(g)
                val isZero = abs(g) < 0.01f
                drawLine(
                    if (isZero) BaselineColor else GridColor,
                    Offset(leftPad, y), Offset(size.width, y),
                    strokeWidth = if (isZero) 2f else 1f,
                )
                drawContext.canvas.nativeCanvas.drawText("${Math.round(g)}°", leftPad - 8f, y + 8f, degPaint)
                g += step
            }

            val durS = ((bounds.tMaxMs - bounds.tMinMs) / 1000f).coerceAtLeast(0.001f)
            val tStep = niceStep(durS, target = 6).coerceAtLeast(0.1f)
            val tPaint = Paint().apply {
                color = 0xFF9E9E9E.toInt()
                textSize = 24f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            var t = 0f
            while (t <= durS + 0.001f) {
                val x = (leftPad + t / durS * plotW).coerceIn(leftPad + 12f, size.width - 12f)
                drawContext.canvas.nativeCanvas.drawText("${"%.1f".format(t)}s", x, size.height - 4f, tPaint)
                t += tStep
            }
        } else {
            // Compact mode still shows the 0° reference.
            val y0 = py(0f)
            drawLine(BaselineColor, Offset(leftPad, y0), Offset(size.width, y0), strokeWidth = 1.5f)
        }

        // Shaded interval between two selected marks.
        if (selectedMarkIndices.size == 2) {
            val a = marks.getOrNull(selectedMarkIndices[0])
            val b = marks.getOrNull(selectedMarkIndices[1])
            if (a != null && b != null) {
                val x1 = px(a.tMs)
                val x2 = px(b.tMs)
                drawRect(
                    color = SelectedMarkColor.copy(alpha = 0.10f),
                    topLeft = Offset(minOf(x1, x2), 0f),
                    size = Size(abs(x2 - x1), plotH),
                )
            }
        }

        // Traces — primary drawn last / on top.
        val order = (0..2).sortedBy { if (it == primaryChannelIndex) 1 else 0 }
        for (channel in order) {
            val color = romChannelColor(channel, primaryChannelIndex)
            val strokeWidth = if (channel == primaryChannelIndex) 5f else 2.5f
            var prev: Offset? = null
            for (s in samples) {
                val p = Offset(px(s.tMs), py(channelValue(s, channel)))
                val prevPoint = prev
                if (prevPoint != null) {
                    drawLine(color, prevPoint, p, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                }
                prev = p
            }
        }

        // Mark ticks; selected marks also get a dot on the primary trace + angle.
        val labelPaint = Paint().apply {
            color = SelectedMarkColor.toArgb()
            textSize = 30f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        marks.forEachIndexed { i, m ->
            val isSelected = i in selectedMarkIndices
            val mx = px(m.tMs)
            drawLine(
                color = if (isSelected) SelectedMarkColor else MarkColor,
                start = Offset(mx, 0f),
                end = Offset(mx, plotH),
                strokeWidth = if (isSelected) 3f else 2f,
            )
            drawCircle(
                color = if (isSelected) SelectedMarkColor else MarkColor,
                radius = if (isSelected) 9f else 6f,
                center = Offset(mx, 8f),
            )
            if (isSelected && m.sampleIndex in samples.indices) {
                val v = channelValue(samples[m.sampleIndex], primaryChannelIndex)
                val vy = py(v)
                drawCircle(color = SelectedMarkColor, radius = 8f, center = Offset(mx, vy))
                drawContext.canvas.nativeCanvas.drawText(
                    "${"%.0f".format(v)}°",
                    mx.coerceIn(leftPad + 40f, size.width - 40f),
                    (vy - 14f).coerceAtLeast(30f),
                    labelPaint,
                )
            }
        }
    }
}
