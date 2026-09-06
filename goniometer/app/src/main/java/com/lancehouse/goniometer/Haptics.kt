package com.lancehouse.goniometer

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Distinct, learnable-by-feel patterns per SPEC.md §6. Timings are a
 * starting point only — tune on real hardware.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun start() = oneShot(80)
    fun stop() = oneShot(200)

    fun mark() = pattern(longArrayOf(0, 40, 60, 40))

    fun clear() = pattern(longArrayOf(0, 40, 60, 40, 60, 40))

    private fun oneShot(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun pattern(timingsMs: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timingsMs, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timingsMs, -1)
        }
    }
}
