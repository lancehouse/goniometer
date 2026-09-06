package com.lancehouse.goniometer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.lancehouse.goniometer.Mark
import com.lancehouse.goniometer.Sample

/**
 * Custom Canvas line chart — deliberately no external charting dependency,
 * to keep the first build simple. Draws all three angle channels over time,
 * emphasizes [primaryChannelIndex], and renders mark timestamps as ticks.
 */
@Composable
fun RomChart(
    samples: List<Sample>,
    marks: List<Mark>,
    primaryChannelIndex: Int,
    modifier: Modifier = Modifier,
) {
    val primaryColor = Color(0xFF2E7D32)
    val secondaryColors = listOf(Color(0xFF90A4AE), Color(0xFFB0BEC5))
    var secondaryIdx = 0

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        if (samples.size < 2) return@Canvas

        val tMin = samples.first().tMs.toFloat()
        val tMax = samples.last().tMs.toFloat().coerceAtLeast(tMin + 1f)

        val allValues = samples.flatMap { listOf(it.roll, it.pitch, it.yaw) }
        val vMin = allValues.min()
        val vMax = allValues.max().let { if (it == vMin) vMin + 1f else it }

        fun x(tMs: Long): Float = (tMs - tMin) / (tMax - tMin) * size.width
        fun y(v: Float): Float = size.height - (v - vMin) / (vMax - vMin) * size.height

        fun channelValue(s: Sample, channel: Int) = when (channel) {
            0 -> s.roll
            1 -> s.pitch
            else -> s.yaw
        }

        for (channel in 0..2) {
            val color = if (channel == primaryChannelIndex) {
                primaryColor
            } else {
                secondaryColors[secondaryIdx++ % secondaryColors.size]
            }
            val strokeWidth = if (channel == primaryChannelIndex) 5f else 2.5f

            var prev: Offset? = null
            for (s in samples) {
                val p = Offset(x(s.tMs), y(channelValue(s, channel)))
                val prevPoint = prev
                if (prevPoint != null) {
                    drawLine(color, prevPoint, p, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                }
                prev = p
            }
        }

        // Mark ticks: vertical line + dot at the top, on the shared time axis.
        val markColor = Color(0xFFEF6C00)
        for (m in marks) {
            val mx = x(m.tMs)
            drawLine(
                color = markColor,
                start = Offset(mx, 0f),
                end = Offset(mx, size.height),
                strokeWidth = 2f,
            )
            drawCircle(color = markColor, radius = 6f, center = Offset(mx, 8f))
        }
    }
}
