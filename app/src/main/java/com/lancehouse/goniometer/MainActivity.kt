package com.lancehouse.goniometer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.lancehouse.goniometer.ui.GoniometerScreen
import java.io.File

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

    // Measurements kept from this clinic visit so far (Stop -> Keep), not
    // yet written to disk. Saved together as one file — see doSaveCluster.
    private val cluster = mutableStateListOf<CapturedMeasurement>()

    // True while showing the cluster review/save screen instead of the
    // normal capture screen. Only reachable from IDLE.
    private var showCluster by mutableStateOf(false)

    // True while showing the "saved sessions" history/send screen. Only
    // reachable from IDLE, independent of showCluster.
    private var showHistory by mutableStateOf(false)
    private var savedSessions by mutableStateOf<List<SavedSessionMeta>>(emptyList())
    private var sentSessionNames by mutableStateOf<Set<String>>(emptySet())
    private var exportedSessionNames by mutableStateOf<Set<String>>(emptySet())

    // Non-null while editing a saved session's labels (reopened from
    // history). File/name identify which saved file to overwrite on Save;
    // the measurements list is a working copy, discarded on Cancel.
    private var editingSessionFile: File? = null
    private var editingSessionName by mutableStateOf<String?>(null)
    private val editingMeasurements = mutableStateListOf<CapturedMeasurement>()

    // Non-null while VIEWING a saved session read-only (full charts + numbers,
    // no editing). Reached by tapping a history row; independent of the
    // label-edit flow. viewingUnreadable == true when the file wouldn't parse.
    private var viewingSessionName by mutableStateOf<String?>(null)
    private val viewingMeasurements = mutableStateListOf<CapturedMeasurement>()
    private var viewingUnreadable by mutableStateOf(false)

    // Filename (without extension) the cluster was last saved to, or null.
    // Shown briefly on the cluster screen, reset when the cluster changes.
    private var savedFileName by mutableStateOf<String?>(null)

    // Spoken (or manually typed/corrected) label for the current/last
    // session, e.g. "Left shoulder abduction". Reset on a fresh Start.
    private var voiceLabel by mutableStateOf("")

    // AROM/PROM for the NEXT measurement kept (top-row toggle). Deliberately
    // sticky across reps within a visit — clinics typically batch several
    // AROM measurements then several PROM ones, not alternate every rep.
    // Reset to the AROM default only when a visit's cluster is cleared/saved.
    private var romType by mutableStateOf(RomType.AROM)

    // Which cluster item the NEXT voice result should update, or null to
    // update [voiceLabel] (the in-progress/just-stopped measurement).
    // Lets the same recognizer be re-triggered from the cluster review
    // screen to correct an already-kept measurement's label.
    private var voiceTargetClusterIndex: Int? = null

    // Which editingMeasurements item the NEXT voice result should update, if
    // the session-edit screen (not the cluster review screen) triggered it.
    // Checked before voiceTargetClusterIndex in applyVoiceResult.
    private var voiceTargetEditingIndex: Int? = null

    private lateinit var voiceRecognizer: VoiceLabelRecognizer
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var lastRawQuaternion: Quaternion = Quaternion.IDENTITY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        haptics = Haptics(this)
        voiceRecognizer = VoiceLabelRecognizer(this) { text -> applyVoiceResult(text) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        refreshSavedSessions()

        setContent {
            GoniometerScreen(
                phase = controller.phase,
                result = controller.lastResult,
                revision = revision, // read to force recomposition on change
                lastPatientCode = SessionStorage.lastPatientCode(this),
                label = voiceLabel,
                cluster = cluster,
                romType = romType,
                showCluster = showCluster,
                savedFileName = savedFileName,
                showHistory = showHistory,
                savedSessions = savedSessions,
                sentSessionNames = sentSessionNames,
                exportedSessionNames = exportedSessionNames,
                editingSessionName = editingSessionName,
                editingMeasurements = editingMeasurements,
                viewingSessionName = viewingSessionName,
                viewingMeasurements = viewingMeasurements,
                viewingUnreadable = viewingUnreadable,
                onStartStopPressed = { doToggleStartStop() },
                onMarkPressed = { doMark() },
                onKeepPressed = { doKeep() },
                onDiscardPressed = { doDiscard() },
                onLabelChanged = { voiceLabel = it },
                onToggleRomTypePressed = { doToggleRomType() },
                onRetryVoicePressed = { doRetryVoice() },
                onReviewPressed = { showCluster = true },
                onBackFromClusterPressed = { showCluster = false },
                onDeleteMeasurementPressed = { index -> doDeleteMeasurement(index) },
                onSaveClusterPressed = { code -> doSaveCluster(code) },
                onClearClusterPressed = { doClearCluster() },
                onMeasurementLabelChanged = { index, newLabel -> doUpdateMeasurementLabel(index, newLabel) },
                onRetryVoiceForMeasurementPressed = { index -> doRetryVoiceForMeasurement(index) },
                onHistoryPressed = { doOpenHistory() },
                onBackFromHistoryPressed = { showHistory = false },
                onSendSessionPressed = { sessionName -> doSendSession(sessionName) },
                onExportSessionPressed = { sessionName -> doExportSession(sessionName) },
                onSendChartsPressed = { sessionName -> doSendCharts(sessionName) },
                onDeleteSessionPressed = { sessionName -> doDeleteSession(sessionName) },
                onEditSessionPressed = { sessionName -> doOpenSessionEdit(sessionName) },
                onEditingLabelChanged = { index, newLabel -> doUpdateEditingLabel(index, newLabel) },
                onRetryVoiceForEditingPressed = { index -> doRetryVoiceForEditingMeasurement(index) },
                onCancelSessionEditPressed = { doCloseSessionEdit() },
                onSaveSessionEditPressed = { doSaveSessionEdit() },
                onViewSessionPressed = { sessionName -> doOpenSessionView(sessionName) },
                onBackFromViewPressed = { doCloseSessionView() },
                onEditFromViewPressed = { doEditFromView() },
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
        voiceRecognizer.release()
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
        // Start/Stop only toggles from IDLE/RECORDING — once STOPPED, Keep
        // or Discard (below) is what returns to IDLE, matching the reviewed
        // "look at the result before deciding" flow.
        if (controller.phase == SessionPhase.STOPPED) return
        val wasRecording = controller.phase == SessionPhase.RECORDING
        val wasIdle = controller.phase == SessionPhase.IDLE
        controller.toggleStartStop(nowMs(), lastRawQuaternion)
        if (wasRecording) haptics.stop() else haptics.start()
        if (wasIdle) {
            voiceLabel = ""
            doRetryVoice() // capture the spoken movement name right as the rep begins
        }
        revision++
    }

    private fun applyVoiceResult(text: String) {
        val editIndex = voiceTargetEditingIndex
        if (editIndex != null && editIndex in editingMeasurements.indices) {
            editingMeasurements[editIndex] = editingMeasurements[editIndex].copy(label = text)
            return
        }
        val index = voiceTargetClusterIndex
        if (index != null && index in cluster.indices) {
            cluster[index] = cluster[index].copy(label = text)
        } else {
            voiceLabel = text
        }
    }

    /** Re-captures the label for the in-progress/just-stopped measurement (Results screen mic button). */
    private fun doRetryVoice() {
        voiceTargetClusterIndex = null
        voiceTargetEditingIndex = null
        startVoiceRecognitionIfPermitted()
    }

    /** Re-captures the label for an already-kept cluster item (cluster review screen mic button). */
    private fun doRetryVoiceForMeasurement(index: Int) {
        voiceTargetClusterIndex = index
        voiceTargetEditingIndex = null
        startVoiceRecognitionIfPermitted()
    }

    /** Re-captures the label for a saved session's measurement (session-edit screen mic button). */
    private fun doRetryVoiceForEditingMeasurement(index: Int) {
        voiceTargetEditingIndex = index
        voiceTargetClusterIndex = null
        startVoiceRecognitionIfPermitted()
    }

    private fun startVoiceRecognitionIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            voiceRecognizer.start()
        }
    }

    private fun doToggleRomType() {
        romType = if (romType == RomType.AROM) RomType.PROM else RomType.AROM
    }

    private fun doUpdateMeasurementLabel(index: Int, newLabel: String) {
        if (index !in cluster.indices) return
        cluster[index] = cluster[index].copy(label = newLabel)
    }

    private fun doMark() {
        if (controller.phase != SessionPhase.RECORDING) return
        controller.mark(nowMs())
        haptics.mark()
        revision++
    }

    /** Adds the just-stopped measurement to the cluster and returns to IDLE, ready for the next rep. */
    private fun doKeep() {
        val result = controller.lastResult ?: return
        cluster.add(CapturedMeasurement(voiceLabel, result, romType))
        controller.clear()
        haptics.mark()
        savedFileName = null
        voiceLabel = ""
        revision++
    }

    /** Drops the just-stopped measurement and returns to IDLE without adding it to the cluster. */
    private fun doDiscard() {
        controller.clear()
        haptics.clear()
        voiceLabel = ""
        revision++
    }

    private fun doDeleteMeasurement(index: Int) {
        if (index !in cluster.indices) return
        cluster.removeAt(index)
        savedFileName = null
    }

    private fun doSaveCluster(patientCode: String) {
        if (cluster.isEmpty()) return
        val file = SessionStorage.saveCluster(this, patientCode, cluster.toList())
        savedFileName = file.nameWithoutExtension
        cluster.clear()
        romType = RomType.AROM
        refreshSavedSessions()
    }

    private fun doClearCluster() {
        cluster.clear()
        savedFileName = null
        romType = RomType.AROM
        showCluster = false
    }

    private fun doOpenHistory() {
        refreshSavedSessions()
        showHistory = true
    }

    private fun refreshSavedSessions() {
        savedSessions = SessionStorage.listSavedSessions(this)
        val names = savedSessions.map { it.sessionName }
        sentSessionNames = names.filter { SessionStorage.isSent(this, it) }.toSet()
        exportedSessionNames = names.filter { SessionStorage.isExported(this, it) }.toSet()
    }

    private fun doSendSession(sessionName: String) {
        val meta = savedSessions.find { it.sessionName == sessionName } ?: return
        SessionSharing.send(this, meta.file)
        SessionStorage.markSent(this, sessionName)
        sentSessionNames = sentSessionNames + sessionName
    }

    /** USB fallback: writes into the public Downloads folder for wifi that blocks GSConnect (see SPEC notes). */
    private fun doExportSession(sessionName: String) {
        val meta = savedSessions.find { it.sessionName == sessionName } ?: return
        DownloadsExporter.exportToDownloads(this, meta.file) ?: return
        SessionStorage.markExported(this, sessionName)
        exportedSessionNames = exportedSessionNames + sessionName
    }

    /** Hands the session's chart PNGs to KDE Connect as image files (re-rendering them if an older session predates charts). */
    private fun doSendCharts(sessionName: String) {
        val meta = savedSessions.find { it.sessionName == sessionName } ?: return
        val charts = SessionStorage.ensureChartFiles(this, meta)
        if (charts.isNotEmpty()) SessionSharing.sendCharts(this, charts)
    }

    private fun doDeleteSession(sessionName: String) {
        val meta = savedSessions.find { it.sessionName == sessionName } ?: return
        SessionStorage.deleteSession(this, meta)
        refreshSavedSessions()
    }

    private fun doOpenSessionEdit(sessionName: String) {
        val meta = savedSessions.find { it.sessionName == sessionName } ?: return
        val loaded = SessionStorage.loadCluster(meta) ?: return
        editingMeasurements.clear()
        editingMeasurements.addAll(loaded)
        editingSessionFile = meta.file
        editingSessionName = sessionName
    }

    private fun doUpdateEditingLabel(index: Int, newLabel: String) {
        if (index !in editingMeasurements.indices) return
        editingMeasurements[index] = editingMeasurements[index].copy(label = newLabel)
    }

    private fun doCloseSessionEdit() {
        editingSessionFile = null
        editingSessionName = null
        editingMeasurements.clear()
    }

    private fun doSaveSessionEdit() {
        val file = editingSessionFile ?: return
        val name = editingSessionName ?: return
        SessionStorage.updateLabels(this, file, name, editingMeasurements.toList())
        SessionStorage.invalidateChartFiles(this, name) // labels are drawn into the PNGs
        doCloseSessionEdit()
        refreshSavedSessions()
    }

    // --- Read-only session review (tap a history row) ---------------------

    private fun doOpenSessionView(sessionName: String) {
        val meta = savedSessions.find { it.sessionName == sessionName } ?: return
        val loaded = SessionStorage.loadCluster(meta)
        viewingMeasurements.clear()
        if (loaded != null) viewingMeasurements.addAll(loaded)
        viewingUnreadable = loaded == null
        viewingSessionName = sessionName
    }

    private fun doCloseSessionView() {
        viewingSessionName = null
        viewingMeasurements.clear()
        viewingUnreadable = false
    }

    /** Jump from the read-only view straight into the label-edit screen for the same session. */
    private fun doEditFromView() {
        val name = viewingSessionName ?: return
        doCloseSessionView()
        doOpenSessionEdit(name)
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}
