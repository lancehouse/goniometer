package com.lancehouse.goniometer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.lancehouse.goniometer.ui.channelPeak
import com.lancehouse.goniometer.ui.channelValue
import com.lancehouse.goniometer.ui.computeBounds
import com.lancehouse.goniometer.ui.niceStep
import com.lancehouse.goniometer.ui.normX
import com.lancehouse.goniometer.ui.normY
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Off-screen chart renderer for PNG export. Plain android.graphics so it needs
 * no view hierarchy and no Compose 1.7 (the project BOM is 1.6.x). Output is a
 * fixed pixel size with px-derived type/strokes — a clinical artifact shouldn't
 * change with screen density. Coordinate math is shared with the on-screen
 * RomChart via ui/ChartGeometry.kt so the two can't drift.
 */
object ChartRenderer {
    const val WIDTH_PX = 1600
    const val HEIGHT_PX = 900

    private const val MARGIN_L = 100f
    private const val MARGIN_R = 40f
    private const val MARGIN_T = 72f
    private const val AXIS_H = 44f      // time-axis strip under the plot
    private const val CAPTION_H = 150f  // caption band at the bottom

    private val COL_PRIMARY = 0xFF2E7D32.toInt()
    private val COL_SECONDARY = intArrayOf(0xFF7A96A3.toInt(), 0xFF9AA7AE.toInt())
    private val COL_MARK = 0xFFEF6C00.toInt()
    private val COL_GRID = 0xFFE0E0E0.toInt()
    private val COL_AXIS = 0xFF9E9E9E.toInt()
    private val COL_TEXT = 0xFF212121.toInt()
    private val COL_MUTED = 0xFF616161.toInt()
    private val COL_BASELINE = 0xFFBDBDBD.toInt()

    /** PNG bytes for one measurement's chart. */
    fun renderPng(measurement: CapturedMeasurement, sessionName: String): ByteArray {
        val bmp = render(measurement, sessionName)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    fun render(measurement: CapturedMeasurement, sessionName: String): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val result = measurement.result
        val samples = result.samples

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COL_TEXT }

        // --- Title ---
        text.textSize = 34f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(
            "${measurement.label.ifBlank { "(unlabelled)" }}   ·   ${measurement.romType.name}",
            MARGIN_L, 44f, text,
        )
        text.typeface = Typeface.DEFAULT
        text.textSize = 22f
        text.color = COL_MUTED
        c.drawText(sessionName, MARGIN_L, MARGIN_T - 6f, text)
        text.color = COL_TEXT

        val plotLeft = MARGIN_L
        val plotRight = WIDTH_PX - MARGIN_R
        val plotTop = MARGIN_T
        val plotBottom = HEIGHT_PX - CAPTION_H - AXIS_H
        val plotW = plotRight - plotLeft
        val plotH = plotBottom - plotTop

        if (samples.size < 2) {
            text.textSize = 26f
            text.color = COL_MUTED
            c.drawText("not enough samples to plot", plotLeft, plotTop + plotH / 2f, text)
            return bmp
        }

        val bounds = computeBounds(samples)
        fun sx(tMs: Long): Float = plotLeft + normX(tMs, bounds, plotW)
        fun sy(v: Float): Float = plotTop + normY(v, bounds, plotH)

        // --- Degree gridlines + labels (0° emphasised) ---
        val step = niceStep(bounds.vSpan, target = 6)
        text.textSize = 20f
        var gv = ceil(bounds.vMin / step) * step
        while (gv <= bounds.vMax) {
            val y = sy(gv)
            val isZero = abs(gv) < 0.01f
            stroke.color = if (isZero) COL_BASELINE else COL_GRID
            stroke.strokeWidth = if (isZero) 2f else 1f
            c.drawLine(plotLeft, y, plotRight, y, stroke)
            text.color = COL_MUTED
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${Math.round(gv)}°", plotLeft - 10f, y + 7f, text)
            text.textAlign = Paint.Align.LEFT
            gv += step
        }

        // --- Plot frame ---
        stroke.color = COL_AXIS
        stroke.strokeWidth = 1.5f
        c.drawRect(plotLeft, plotTop, plotRight, plotBottom, stroke)

        // --- Time axis ticks + labels ---
        val durS = ((bounds.tMaxMs - bounds.tMinMs) / 1000f).coerceAtLeast(0.001f)
        val tStep = niceStep(durS, target = 8).coerceAtLeast(0.1f)
        text.textAlign = Paint.Align.CENTER
        text.color = COL_MUTED
        var t = 0f
        while (t <= durS + 0.001f) {
            val x = plotLeft + t / durS * plotW
            stroke.color = COL_AXIS
            stroke.strokeWidth = 1.5f
            c.drawLine(x, plotBottom, x, plotBottom + 8f, stroke)
            c.drawText("${"%.1f".format(t)}s", x, plotBottom + 32f, text)
            t += tStep
        }
        text.textAlign = Paint.Align.LEFT

        // --- Traces (primary drawn last / on top) ---
        val order = (0..2).sortedBy { if (it == result.primaryChannelIndex) 1 else 0 }
        var secIdx = 0
        for (ch in order) {
            val isPrimary = ch == result.primaryChannelIndex
            stroke.color = if (isPrimary) COL_PRIMARY else COL_SECONDARY[secIdx++ % COL_SECONDARY.size]
            stroke.strokeWidth = if (isPrimary) 4f else 2f
            var px = sx(samples[0].tMs)
            var py = sy(channelValue(samples[0], ch))
            for (i in 1 until samples.size) {
                val nx = sx(samples[i].tMs)
                val ny = sy(channelValue(samples[i], ch))
                c.drawLine(px, py, nx, ny, stroke)
                px = nx; py = ny
            }
        }

        // --- Marks ---
        stroke.color = COL_MARK
        stroke.strokeWidth = 2f
        fill.color = COL_MARK
        text.color = COL_MARK
        text.textAlign = Paint.Align.CENTER
        text.textSize = 18f
        result.marks.forEachIndexed { i, m ->
            val x = sx(m.tMs)
            c.drawLine(x, plotTop, x, plotBottom, stroke)
            c.drawCircle(x, plotTop + 8f, 6f, fill)
            c.drawText("M${i + 1}", x, plotTop - 6f, text)
        }
        text.textAlign = Paint.Align.LEFT

        // --- Legend (top-right inside plot) ---
        val legendX = plotRight - 240f
        var legendY = plotTop + 26f
        var legSec = 0
        text.textSize = 20f
        for (ch in 0..2) {
            val isPrimary = ch == result.primaryChannelIndex
            fill.color = if (isPrimary) COL_PRIMARY else COL_SECONDARY[legSec++ % COL_SECONDARY.size]
            c.drawRect(legendX, legendY - 14f, legendX + 26f, legendY + 2f, fill)
            text.color = COL_TEXT
            c.drawText(CHANNEL_LABELS[ch] + if (isPrimary) "  (primary)" else "", legendX + 36f, legendY, text)
            legendY += 26f
        }

        // --- Caption band ---
        val capY0 = HEIGHT_PX - CAPTION_H + 8f
        stroke.color = COL_GRID
        stroke.strokeWidth = 1f
        c.drawLine(MARGIN_L, capY0 - 8f, WIDTH_PX - MARGIN_R, capY0 - 8f, stroke)

        val peak = channelPeak(samples, result.primaryChannelIndex)
        val totalSweep = samples.maxOf { it.totalAngleDeg } - samples.minOf { it.totalAngleDeg }

        text.color = COL_TEXT
        text.textSize = 40f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText("${"%.1f".format(result.primaryRangeDeg)}°  ${result.primaryLabel}", MARGIN_L, capY0 + 34f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 22f
        text.color = COL_MUTED
        c.drawText("lacks ${"%.0f".format(result.deficitToFullDeg)}° of full 180°", MARGIN_L, capY0 + 64f, text)
        c.drawText(
            result.secondaryChannels().joinToString("     ") { "${it.first} ${"%.1f".format(it.second)}°" },
            MARGIN_L, capY0 + 92f, text,
        )
        c.drawText(
            "peak ${"%.1f".format(peak.maxV)}° @ ${"%.1f".format(peak.maxTMs / 1000f)}s     " +
                "min ${"%.1f".format(peak.minV)}° @ ${"%.1f".format(peak.minTMs / 1000f)}s     " +
                "total sweep ${"%.1f".format(totalSweep)}° (cross-check)",
            MARGIN_L, capY0 + 120f, text,
        )

        return bmp
    }
}
