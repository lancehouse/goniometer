package com.lancehouse.goniometer.ui

import com.lancehouse.goniometer.Sample

/**
 * Compose-free chart coordinate math, shared by the on-screen [RomChart] and
 * the off-screen PNG renderer (ChartRenderer) so the two can never silently
 * drift. Only the geometry is shared — each surface does its own drawing and
 * its own text (Compose TextMeasurer vs android.graphics.Paint).
 */

/** Time/value extents of a sample series, used to map data -> pixels. */
data class ChartBounds(
    val tMinMs: Float,
    val tMaxMs: Float,
    val vMin: Float,
    val vMax: Float,
) {
    val tSpan: Float get() = (tMaxMs - tMinMs).coerceAtLeast(1f)
    val vSpan: Float get() = (vMax - vMin).coerceAtLeast(1f)
}

/**
 * Extents across all three channels. [padFraction] adds head/foot room so the
 * traces don't touch the frame; [includeZero] keeps the 0° baseline on-screen
 * even when the whole recording sits above or below it.
 */
fun computeBounds(
    samples: List<Sample>,
    padFraction: Float = 0.08f,
    includeZero: Boolean = true,
): ChartBounds {
    if (samples.isEmpty()) return ChartBounds(0f, 1f, -1f, 1f)

    val tMin = samples.first().tMs.toFloat()
    val tMax = samples.last().tMs.toFloat().coerceAtLeast(tMin + 1f)

    var vMin = Float.MAX_VALUE
    var vMax = -Float.MAX_VALUE
    for (s in samples) {
        for (v in floatArrayOf(s.roll, s.pitch, s.yaw)) {
            if (v < vMin) vMin = v
            if (v > vMax) vMax = v
        }
    }
    if (includeZero) {
        vMin = minOf(vMin, 0f)
        vMax = maxOf(vMax, 0f)
    }
    if (vMax <= vMin) vMax = vMin + 1f

    val pad = (vMax - vMin) * padFraction
    return ChartBounds(tMin, tMax, vMin - pad, vMax + pad)
}

/** roll / pitch / yaw by channel index (0/1/2). */
fun channelValue(s: Sample, channel: Int): Float = when (channel) {
    0 -> s.roll
    1 -> s.pitch
    else -> s.yaw
}

/** Data time -> x pixel within [0, widthPx]. */
fun normX(tMs: Long, b: ChartBounds, widthPx: Float): Float =
    (tMs - b.tMinMs) / b.tSpan * widthPx

/** Data degrees -> y pixel within [0, heightPx] (y grows downward). */
fun normY(v: Float, b: ChartBounds, heightPx: Float): Float =
    heightPx - (v - b.vMin) / b.vSpan * heightPx

/** Per-channel extremes and when they occurred, for callouts and captions. */
data class ChannelPeak(
    val minV: Float,
    val maxV: Float,
    val minTMs: Long,
    val maxTMs: Long,
) {
    val rangeDeg: Float get() = maxV - minV
}

fun channelPeak(samples: List<Sample>, channel: Int): ChannelPeak {
    if (samples.isEmpty()) return ChannelPeak(0f, 0f, 0L, 0L)
    var minV = Float.MAX_VALUE
    var maxV = -Float.MAX_VALUE
    var minT = samples.first().tMs
    var maxT = samples.first().tMs
    for (s in samples) {
        val v = channelValue(s, channel)
        if (v < minV) { minV = v; minT = s.tMs }
        if (v > maxV) { maxV = v; maxT = s.tMs }
    }
    return ChannelPeak(minV, maxV, minT, maxT)
}

/**
 * "Nice" gridline step for a value span (1/2/5 x 10^n) targeting ~[target]
 * lines. Used for the degree axis on both chart surfaces.
 */
fun niceStep(span: Float, target: Int = 5): Float {
    if (span <= 0f) return 1f
    val raw = span / target
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble())).toDouble()).toFloat()
    val norm = raw / mag
    val step = when {
        norm < 1.5f -> 1f
        norm < 3f -> 2f
        norm < 7f -> 5f
        else -> 10f
    }
    return step * mag
}
