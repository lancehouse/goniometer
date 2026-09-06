package com.lancehouse.goniometer

/** One sensor sample, angles relative to the session's baseline (see SensorFusion.kt). */
data class Sample(
    val tMs: Long,
    val roll: Float,
    val pitch: Float,
    val yaw: Float,
    val totalAngleDeg: Float,
)

/** A therapist-triggered annotation on the timeline. Unbounded — 0..n per session. */
data class Mark(val tMs: Long, val sampleIndex: Int)

enum class SessionPhase { IDLE, RECORDING, STOPPED }

/** Index into [SessionResult.ranges] / channel arrays: 0=roll, 1=pitch, 2=yaw. */
const val CHANNEL_ROLL = 0
const val CHANNEL_PITCH = 1
const val CHANNEL_YAW = 2

val CHANNEL_LABELS = arrayOf("Range A", "Range B", "Range C")

data class SessionResult(
    val samples: List<Sample>,
    val marks: List<Mark>,
    val ranges: FloatArray, // [roll, pitch, yaw] sweep in degrees
    val primaryChannelIndex: Int,
) {
    val primaryLabel: String get() = CHANNEL_LABELS[primaryChannelIndex]
    val primaryRangeDeg: Float get() = ranges[primaryChannelIndex]

    /** The other two channels, in a stable display order (not sorted), for the smaller readouts. */
    fun secondaryChannels(): List<Pair<String, Float>> =
        CHANNEL_LABELS.indices.filter { it != primaryChannelIndex }
            .map { CHANNEL_LABELS[it] to ranges[it] }
}

/**
 * Computes session results per SPEC.md §4: range = max-min per channel over
 * the whole recording; the widest channel is primary. Marks are NOT used to
 * segment the data — they're pure annotations (SPEC.md §4).
 */
fun computeResult(samples: List<Sample>, marks: List<Mark>): SessionResult {
    if (samples.isEmpty()) {
        return SessionResult(samples, marks, floatArrayOf(0f, 0f, 0f), 0)
    }
    val ranges = FloatArray(3)
    for (channel in 0..2) {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (s in samples) {
            val v = when (channel) {
                CHANNEL_ROLL -> s.roll
                CHANNEL_PITCH -> s.pitch
                else -> s.yaw
            }
            if (v < min) min = v
            if (v > max) max = v
        }
        ranges[channel] = max - min
    }
    val primary = ranges.indices.maxByOrNull { ranges[it] } ?: 0
    return SessionResult(samples, marks, ranges, primary)
}

/**
 * Owns the recording state machine described in SPEC.md §3. Not thread-safe;
 * drive it from the main thread (sensor callbacks + key events both land on
 * the main looper by default, which keeps this simple).
 */
class SessionController {
    var phase: SessionPhase = SessionPhase.IDLE
        private set

    private val orientation = RelativeOrientationTracker()
    private val samples = mutableListOf<Sample>()
    private val marks = mutableListOf<Mark>()
    private var startElapsedMs: Long = 0L
    var lastResult: SessionResult? = null
        private set

    fun start(nowMs: Long, baselineRaw: Quaternion) {
        if (phase == SessionPhase.RECORDING) return
        orientation.setBaseline(baselineRaw)
        samples.clear()
        marks.clear()
        startElapsedMs = nowMs
        phase = SessionPhase.RECORDING
    }

    fun onSensorSample(nowMs: Long, raw: Quaternion) {
        if (phase != SessionPhase.RECORDING) return
        val euler = orientation.relativeEuler(raw)
        val total = orientation.totalAngleDeg(raw)
        samples.add(Sample(nowMs - startElapsedMs, euler.roll, euler.pitch, euler.yaw, total))
    }

    fun mark(nowMs: Long) {
        if (phase != SessionPhase.RECORDING) return
        marks.add(Mark(nowMs - startElapsedMs, samples.size - 1))
    }

    fun stop() {
        if (phase != SessionPhase.RECORDING) return
        phase = SessionPhase.STOPPED
        lastResult = computeResult(samples.toList(), marks.toList())
    }

    /** Start/Stop toggle, wired to Volume Up per SPEC.md §5. */
    fun toggleStartStop(nowMs: Long, baselineRaw: Quaternion) {
        when (phase) {
            SessionPhase.IDLE -> start(nowMs, baselineRaw)
            SessionPhase.RECORDING -> stop()
            SessionPhase.STOPPED -> { /* no-op: use Clear first */ }
        }
    }

    fun clear() {
        phase = SessionPhase.IDLE
        samples.clear()
        marks.clear()
        lastResult = null
    }
}
