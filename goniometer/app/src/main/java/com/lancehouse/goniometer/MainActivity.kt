package com.lancehouse.goniometer

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lancehouse.goniometer.ui.GoniometerScreen

/**
 * Wires together: sensor sampling, volume-key input (SPEC.md §5), the
 * session state machine, and haptic feedback. Screen composition is in
 * ui/GoniometerScreen.kt — this class deliberately keeps that dumb (it
 * just renders a snapshot of SessionController's state).
 */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var haptics: Haptics
    private val controller = SessionController()

    // Recomposition trigger — bumped on every state-changing event.
    private var revision by mutableStateOf(0)

    private var lastRawQuaternion: Quaternion = Quaternion.IDENTITY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        haptics = Haptics(this)

        setContent {
            GoniometerScreen(
                phase = controller.phase,
                result = controller.lastResult,
                revision = revision, // read to force recomposition on change
                onStartStopPressed = { doToggleStartStop() },
                onMarkPressed = { doMark() },
                onClearPressed = { doClear() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // --- Sensor callbacks -------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, event.values)
        val raw = Quaternion.fromFloatArray(q)
        lastRawQuaternion = raw
        controller.onSensorSample(nowMs(), raw)
        if (controller.phase == SessionPhase.RECORDING) revision++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Physical volume-key input (SPEC.md §5) ----------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                doToggleStartStop()
                return true // consume: do not let system volume UI show
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                doMark()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- Actions ------------------------------------------------------------

    private fun doToggleStartStop() {
        val wasRecording = controller.phase == SessionPhase.RECORDING
        controller.toggleStartStop(nowMs(), lastRawQuaternion)
        if (wasRecording) haptics.stop() else haptics.start()
        revision++
    }

    private fun doMark() {
        if (controller.phase != SessionPhase.RECORDING) return
        controller.mark(nowMs())
        haptics.mark()
        revision++
    }

    private fun doClear() {
        controller.clear()
        haptics.clear()
        revision++
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}
