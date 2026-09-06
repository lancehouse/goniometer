package com.lancehouse.goniometer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Minimal unit-quaternion helper. Convention: Q(w, x, y, z), consistent with
 * SensorManager.getQuaternionFromVector()'s output array [w, x, y, z].
 *
 * We do NOT rely on Android's rotation-matrix/orientation helpers because we
 * need the quaternion multiply + conjugate to express orientation *relative
 * to an arbitrary baseline* (see SPEC.md §2) — the phone is hand-held at a
 * different, unknown starting angle every session, so there is no fixed
 * world-frame mapping we can lean on.
 */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {

    /** Hamilton product: this * other */
    operator fun times(o: Quaternion): Quaternion = Quaternion(
        w = w * o.w - x * o.x - y * o.y - z * o.z,
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w,
    )

    /** For a unit quaternion, conjugate == inverse. */
    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n == 0f) this else Quaternion(w / n, x / n, y / n, z / n)
    }

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /** Matches SensorManager.getQuaternionFromVector() output ordering. */
        fun fromFloatArray(q: FloatArray): Quaternion = Quaternion(q[0], q[1], q[2], q[3])
    }
}

/** roll/pitch/yaw in degrees, all relative to whatever baseline was subtracted. */
data class EulerAnglesDeg(val roll: Float, val pitch: Float, val yaw: Float)

/**
 * Baseline-relative orientation math. Call [setBaseline] once (on Start),
 * then feed every subsequent raw sensor quaternion to [relativeEuler].
 *
 * The Euler decomposition is ZYX (yaw-pitch-roll) applied to the *relative*
 * quaternion, so it's expressed in the phone's own frame at the moment of
 * Start — not the world frame. This is what makes the reading meaningful
 * however awkwardly the phone was being held when Start was pressed.
 *
 * Known limitation (documented in SPEC.md §2): this decomposition can hit
 * gimbal lock if pitch swings close to +/-90 degrees, and a compound motion
 * will show some cross-talk between channels. [totalAngleDeg] is provided
 * as a gimbal-lock-immune fallback (total 3D angular displacement from
 * baseline) in case the three-channel breakdown ever looks wrong on-device.
 */
class RelativeOrientationTracker {
    private var baseline: Quaternion = Quaternion.IDENTITY

    fun setBaseline(raw: Quaternion) {
        baseline = raw.normalized()
    }

    fun relative(raw: Quaternion): Quaternion =
        (baseline.conjugate() * raw.normalized()).normalized()

    fun relativeEuler(raw: Quaternion): EulerAnglesDeg = toEulerDeg(relative(raw))

    fun totalAngleDeg(raw: Quaternion): Float {
        val q = relative(raw)
        val clampedW = q.w.coerceIn(-1f, 1f)
        return Math.toDegrees(2.0 * Math.acos(clampedW.toDouble())).toFloat()
    }

    private fun toEulerDeg(q: Quaternion): EulerAnglesDeg {
        // roll (x-axis)
        val sinrCosp = 2f * (q.w * q.x + q.y * q.z)
        val cosrCosp = 1f - 2f * (q.x * q.x + q.y * q.y)
        val roll = atan2(sinrCosp, cosrCosp)

        // pitch (y-axis) — clamp at gimbal lock rather than NaN from asin
        val sinp = 2f * (q.w * q.y - q.z * q.x)
        val pitch = if (abs(sinp) >= 1f) sign(sinp) * (PI.toFloat() / 2f) else asin(sinp)

        // yaw (z-axis)
        val sinyCosp = 2f * (q.w * q.z + q.x * q.y)
        val cosyCosp = 1f - 2f * (q.y * q.y + q.z * q.z)
        val yaw = atan2(sinyCosp, cosyCosp)

        return EulerAnglesDeg(
            roll = Math.toDegrees(roll.toDouble()).toFloat(),
            pitch = Math.toDegrees(pitch.toDouble()).toFloat(),
            yaw = Math.toDegrees(yaw.toDouble()).toFloat(),
        )
    }
}
