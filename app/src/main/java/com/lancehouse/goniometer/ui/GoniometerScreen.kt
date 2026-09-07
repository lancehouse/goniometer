package com.lancehouse.goniometer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lancehouse.goniometer.CHANNEL_LABELS
import com.lancehouse.goniometer.CapturedMeasurement
import com.lancehouse.goniometer.RomType
import com.lancehouse.goniometer.SavedSessionMeta
import com.lancehouse.goniometer.SessionPhase
import com.lancehouse.goniometer.SessionResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Three layouts:
 *  - IDLE / RECORDING: just the two big buttons, plus a REVIEW chip once the
 *    cluster has something in it. Nothing here needs to be read — it's
 *    operated by feel. (SPEC.md §7)
 *  - STOPPED: single-measurement review right after a rep — big primary
 *    number, chart, editable label, Keep/Discard.
 *  - Cluster review (reachable from IDLE via REVIEW): every kept measurement
 *    from this clinic visit, expandable, deletable, saved together as one file.
 *
 * [revision] is unused directly but reading it as a parameter forces
 * recomposition whenever MainActivity bumps it (new sensor sample, state
 * change, etc.) without needing a full state-holder ViewModel for v1.
 */
@Composable
fun GoniometerScreen(
    phase: SessionPhase,
    result: SessionResult?,
    revision: Int,
    lastPatientCode: String,
    label: String,
    cluster: List<CapturedMeasurement>,
    romType: RomType,
    showCluster: Boolean,
    savedFileName: String?,
    showHistory: Boolean,
    savedSessions: List<SavedSessionMeta>,
    sentSessionNames: Set<String>,
    exportedSessionNames: Set<String>,
    editingSessionName: String?,
    editingMeasurements: List<CapturedMeasurement>,
    viewingSessionName: String?,
    viewingMeasurements: List<CapturedMeasurement>,
    viewingUnreadable: Boolean,
    onStartStopPressed: () -> Unit,
    onMarkPressed: () -> Unit,
    onKeepPressed: () -> Unit,
    onDiscardPressed: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onToggleRomTypePressed: () -> Unit,
    onRetryVoicePressed: () -> Unit,
    onReviewPressed: () -> Unit,
    onBackFromClusterPressed: () -> Unit,
    onDeleteMeasurementPressed: (Int) -> Unit,
    onSaveClusterPressed: (String) -> Unit,
    onClearClusterPressed: () -> Unit,
    onMeasurementLabelChanged: (Int, String) -> Unit,
    onRetryVoiceForMeasurementPressed: (Int) -> Unit,
    onHistoryPressed: () -> Unit,
    onBackFromHistoryPressed: () -> Unit,
    onSendSessionPressed: (String) -> Unit,
    onExportSessionPressed: (String) -> Unit,
    onDeleteSessionPressed: (String) -> Unit,
    onEditSessionPressed: (String) -> Unit,
    onEditingLabelChanged: (Int, String) -> Unit,
    onRetryVoiceForEditingPressed: (Int) -> Unit,
    onCancelSessionEditPressed: () -> Unit,
    onSaveSessionEditPressed: () -> Unit,
    onViewSessionPressed: (String) -> Unit,
    onBackFromViewPressed: () -> Unit,
    onEditFromViewPressed: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (phase == SessionPhase.RECORDING) Color(0xFFFFF3E0) else Color.White)
    ) {
        when {
            editingSessionName != null -> SessionEditScreen(
                sessionName = editingSessionName,
                measurements = editingMeasurements,
                onLabelChanged = onEditingLabelChanged,
                onRetryVoicePressed = onRetryVoiceForEditingPressed,
                onCancelPressed = onCancelSessionEditPressed,
                onSavePressed = onSaveSessionEditPressed,
            )
            viewingSessionName != null -> SessionViewScreen(
                sessionName = viewingSessionName,
                measurements = viewingMeasurements,
                unreadable = viewingUnreadable,
                onBackPressed = onBackFromViewPressed,
                onEditPressed = onEditFromViewPressed,
            )
            phase == SessionPhase.STOPPED -> ResultsScreen(
                result = result,
                label = label,
                romType = romType,
                onKeepPressed = onKeepPressed,
                onDiscardPressed = onDiscardPressed,
                onLabelChanged = onLabelChanged,
                onRetryVoicePressed = onRetryVoicePressed,
            )
            showCluster -> ClusterReviewScreen(
                cluster = cluster,
                lastPatientCode = lastPatientCode,
                savedFileName = savedFileName,
                onBackPressed = onBackFromClusterPressed,
                onDeleteMeasurementPressed = onDeleteMeasurementPressed,
                onSaveClusterPressed = onSaveClusterPressed,
                onClearClusterPressed = onClearClusterPressed,
                onMeasurementLabelChanged = onMeasurementLabelChanged,
                onRetryVoiceForMeasurementPressed = onRetryVoiceForMeasurementPressed,
            )
            showHistory -> HistoryScreen(
                savedSessions = savedSessions,
                sentSessionNames = sentSessionNames,
                exportedSessionNames = exportedSessionNames,
                onBackPressed = onBackFromHistoryPressed,
                onViewSessionPressed = onViewSessionPressed,
                onSendSessionPressed = onSendSessionPressed,
                onExportSessionPressed = onExportSessionPressed,
                onDeleteSessionPressed = onDeleteSessionPressed,
                onEditSessionPressed = onEditSessionPressed,
            )
            else -> CaptureScreen(
                phase = phase,
                clusterSize = cluster.size,
                savedSessionCount = savedSessions.size,
                romType = romType,
                onStartStopPressed = onStartStopPressed,
                onMarkPressed = onMarkPressed,
                onReviewPressed = onReviewPressed,
                onHistoryPressed = onHistoryPressed,
                onToggleRomTypePressed = onToggleRomTypePressed,
            )
        }
    }
}

@Composable
private fun CaptureScreen(
    phase: SessionPhase,
    clusterSize: Int,
    savedSessionCount: Int,
    romType: RomType,
    onStartStopPressed: () -> Unit,
    onMarkPressed: () -> Unit,
    onReviewPressed: () -> Unit,
    onHistoryPressed: () -> Unit,
    onToggleRomTypePressed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phase == SessionPhase.RECORDING) {
                    Text(text = "Recording…", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                }
                RomTypeChip(romType = romType, enabled = phase == SessionPhase.IDLE, onClick = onToggleRomTypePressed)
            }
            if (phase == SessionPhase.IDLE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (savedSessionCount > 0) {
                        Chip(text = "SESSIONS ($savedSessionCount)", color = Color(0xFF616161), onClick = onHistoryPressed)
                    }
                    if (clusterSize > 0) {
                        Chip(text = "REVIEW ($clusterSize)", color = Color(0xFF1565C0), onClick = onReviewPressed)
                    }
                }
            }
        }

        // Big buttons — findable without looking. Volume Up/Down do the same
        // thing physically (see MainActivity.onKeyDown).
        BigButton(
            text = if (phase == SessionPhase.RECORDING) "STOP" else "START",
            color = if (phase == SessionPhase.RECORDING) Color(0xFFC62828) else Color(0xFF2E7D32),
            modifier = Modifier.weight(2f),
            onClick = onStartStopPressed,
        )
        BigButton(
            text = "MARK",
            color = Color(0xFF1565C0),
            modifier = Modifier.weight(1.4f),
            enabled = phase == SessionPhase.RECORDING,
            onClick = onMarkPressed,
        )
    }
}

/** Top-row AROM/PROM toggle — defaults to AROM, tap flips it. Disabled (still visible) once a rep is under way, since the mode is a pre-recording choice. */
@Composable
private fun RomTypeChip(romType: RomType, enabled: Boolean, onClick: () -> Unit) {
    val isProm = romType == RomType.PROM
    Box(
        modifier = Modifier
            .background(if (isProm) Color(0xFFEF6C00) else Color(0xFF2E7D32), shape = RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(if (isProm) "PROM" else "AROM", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Small non-interactive AROM/PROM tag for review screens — the mode is only changed via [RomTypeChip] on the capture screen. */
@Composable
private fun RomTypeBadge(romType: RomType) {
    val isProm = romType == RomType.PROM
    Text(
        text = if (isProm) "PROM" else "AROM",
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(if (isProm) Color(0xFFEF6C00) else Color(0xFF2E7D32), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Chip(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(color, shape = RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun BigButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

/** Editable movement label row, shared by the single-measurement and cluster-row detail views. */
@Composable
private fun LabelRow(label: String, onLabelChanged: (String) -> Unit, onRetryVoicePressed: () -> Unit) {
    var editingLabel by remember { mutableStateOf(false) }
    if (editingLabel) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            OutlinedTextField(
                value = label,
                onValueChange = onLabelChanged,
                label = { Text("Movement") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            LabelIconButton(text = "🎙", color = Color(0xFF1565C0), onClick = onRetryVoicePressed)
            Spacer(modifier = Modifier.width(8.dp))
            LabelIconButton(text = "✓", color = Color(0xFF2E7D32), onClick = { editingLabel = false })
        }
    } else {
        Text(
            text = label.ifBlank { "Tap to add a label" },
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = if (label.isBlank()) Color.Gray else Color.Black,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .clickable { editingLabel = true },
        )
    }
}

/** Primary/secondary numbers + chart + mark chips, shared by the single-measurement and cluster-row detail views. [detailed] adds a legend and axis labels for the read-only View screen. */
@Composable
private fun MeasurementBody(result: SessionResult, detailed: Boolean = false) {
    // 0, 1, or 2 selected marks (tap a chip to toggle; a third tap evicts the
    // oldest). One → single-mark detail. Two → before/after comparison.
    val selected = remember(result) { mutableStateListOf<Int>() }

    Text(text = result.primaryLabel, fontSize = 20.sp, color = Color.Gray)
    Text(
        text = "${"%.1f".format(result.primaryRangeDeg)}°",
        fontSize = 88.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = "lacks ${"%.0f".format(result.deficitToFullDeg)}° of full 180°",
        fontSize = 14.sp,
        color = Color.Gray,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        for ((channelLabel, value) in result.secondaryChannels()) {
            Column {
                Text(channelLabel, fontSize = 14.sp, color = Color.Gray)
                Text("${"%.1f".format(value)}°", fontSize = 22.sp)
            }
        }
    }

    if (result.samples.isNotEmpty()) {
        val peak = channelPeak(result.samples, result.primaryChannelIndex)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "peak ${"%.1f".format(peak.maxV)}° @ ${"%.1f".format(peak.maxTMs / 1000f)}s" +
                "   ·   min ${"%.1f".format(peak.minV)}° @ ${"%.1f".format(peak.minTMs / 1000f)}s",
            fontSize = 13.sp,
            color = Color.Gray,
        )
        // SPEC §2 gimbal-lock-immune cross-check — flag only if it disagrees
        // materially with the three-channel primary range.
        val totalSweep = result.samples.maxOf { it.totalAngleDeg } - result.samples.minOf { it.totalAngleDeg }
        Text(
            text = "total angular sweep ${"%.1f".format(totalSweep)}° (cross-check)",
            fontSize = 12.sp,
            color = Color.Gray,
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    if (detailed) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            for (ch in CHANNEL_LABELS.indices) {
                val isPrimary = ch == result.primaryChannelIndex
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(romChannelColor(ch, result.primaryChannelIndex), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = CHANNEL_LABELS[ch] + if (isPrimary) " (primary)" else "",
                        fontSize = 11.sp,
                        color = Color.Gray,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    RomChart(
        samples = result.samples,
        marks = result.marks,
        primaryChannelIndex = result.primaryChannelIndex,
        selectedMarkIndices = selected.toList(),
        detailed = detailed,
    )

    if (result.marks.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            result.marks.forEachIndexed { i, mark ->
                val angle = mark.sampleIndex.takeIf { it in result.samples.indices }
                    ?.let { channelValue(result.samples[it], result.primaryChannelIndex) }
                MarkChip(
                    index = i,
                    timeMs = mark.tMs,
                    angleDeg = angle,
                    selected = i in selected,
                    onClick = {
                        when {
                            i in selected -> selected.remove(i)
                            selected.size >= 2 -> { selected.removeAt(0); selected.add(i) }
                            else -> selected.add(i)
                        }
                    },
                )
            }
        }

        when (selected.size) {
            1 -> {
                val idx = selected[0]
                val sampleIdx = result.marks[idx].sampleIndex
                if (sampleIdx in result.samples.indices) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MarkDetail(
                        markIndex = idx,
                        sample = result.samples[sampleIdx],
                        primaryChannelIndex = result.primaryChannelIndex,
                    )
                }
            }
            2 -> {
                Spacer(modifier = Modifier.height(12.dp))
                MarkCompare(result = result, markIndexA = selected[0], markIndexB = selected[1])
            }
        }
    }
}

/** Extracted card used by the cluster-review and saved-session-review lists: header row + expandable detail (chart, numbers, marks). [onDelete] null hides the delete affordance (saved-session review is label edits only). */
@Composable
private fun MeasurementCard(
    index: Int,
    measurement: CapturedMeasurement,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onRetryVoicePressed: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onToggleExpand)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RomTypeBadge(romType = measurement.romType)
                    Text(
                        text = measurement.label.ifBlank { "Measurement ${index + 1}" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = "${measurement.result.primaryLabel}: ${"%.1f".format(measurement.result.primaryRangeDeg)}°",
                    fontSize = 14.sp,
                    color = Color.Gray,
                )
            }
            if (onDelete != null) {
                LabelIconButton(text = "✕", color = Color(0xFFB71C1C), onClick = onDelete)
            }
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(16.dp))
            LabelRow(label = measurement.label, onLabelChanged = onLabelChanged, onRetryVoicePressed = onRetryVoicePressed)
            MeasurementBody(measurement.result)
        }
    }
}

/** Before/after readout for two selected marks, ordered by time so deltas read left-to-right. */
@Composable
private fun MarkCompare(result: SessionResult, markIndexA: Int, markIndexB: Int) {
    val mA0 = result.marks[markIndexA]
    val mB0 = result.marks[markIndexB]
    val aFirst = mA0.tMs <= mB0.tMs
    val firstIdx = if (aFirst) markIndexA else markIndexB
    val lastIdx = if (aFirst) markIndexB else markIndexA
    val mFirst = if (aFirst) mA0 else mB0
    val mLast = if (aFirst) mB0 else mA0
    val sFirst = result.samples.getOrNull(mFirst.sampleIndex) ?: return
    val sLast = result.samples.getOrNull(mLast.sampleIndex) ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "M${firstIdx + 1} → M${lastIdx + 1}   ·   Δt ${"%.2f".format((mLast.tMs - mFirst.tMs) / 1000f)}s",
            fontSize = 14.sp,
            color = Color.Gray,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (channel in CHANNEL_LABELS.indices) {
                val isPrimary = channel == result.primaryChannelIndex
                val delta = channelValue(sLast, channel) - channelValue(sFirst, channel)
                Column {
                    Text(CHANNEL_LABELS[channel], fontSize = 12.sp, color = Color.Gray)
                    Text(
                        text = "${if (delta >= 0f) "+" else ""}${"%.1f".format(delta)}°",
                        fontSize = if (isPrimary) 28.sp else 18.sp,
                        fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal,
                        color = if (isPrimary) MaterialTheme.colorScheme.primary else Color.Black,
                    )
                }
            }
        }
    }
}

/** Single-measurement review right after Stop — Keep adds it to the cluster, Discard drops it. */
@Composable
private fun ResultsScreen(
    result: SessionResult?,
    label: String,
    romType: RomType,
    onKeepPressed: () -> Unit,
    onDiscardPressed: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onRetryVoicePressed: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        if (result == null || result.samples.isEmpty()) {
            Text("No data captured.", fontSize = 20.sp)
        } else {
            RomTypeBadge(romType = romType)
            Spacer(modifier = Modifier.height(8.dp))
            LabelRow(label = label, onLabelChanged = onLabelChanged, onRetryVoicePressed = onRetryVoicePressed)
            MeasurementBody(result)
        }

        Spacer(modifier = Modifier.weight(1f))

        Row {
            BigButton(
                text = "DISCARD",
                color = Color(0xFF616161),
                modifier = Modifier.weight(1f),
                onClick = onDiscardPressed,
            )
            BigButton(
                text = "KEEP",
                color = Color(0xFF2E7D32),
                modifier = Modifier.weight(1f),
                enabled = result != null && result.samples.isNotEmpty(),
                onClick = onKeepPressed,
            )
        }
    }
}

/** All measurements kept so far this visit — expandable, deletable, saved together. */
@Composable
private fun ClusterReviewScreen(
    cluster: List<CapturedMeasurement>,
    lastPatientCode: String,
    savedFileName: String?,
    onBackPressed: () -> Unit,
    onDeleteMeasurementPressed: (Int) -> Unit,
    onSaveClusterPressed: (String) -> Unit,
    onClearClusterPressed: () -> Unit,
    onMeasurementLabelChanged: (Int, String) -> Unit,
    onRetryVoiceForMeasurementPressed: (Int) -> Unit,
) {
    var showSaveEntry by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf(lastPatientCode.uppercase()) }
    var expandedIndex by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("< BACK", fontSize = 16.sp, color = Color(0xFF1565C0), modifier = Modifier.clickable(onClick = onBackPressed))
            Text("This visit (${cluster.size})", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(48.dp)) // balance the back label
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (cluster.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Nothing kept yet.", fontSize = 18.sp, color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(cluster.size) { index ->
                    MeasurementCard(
                        index = index,
                        measurement = cluster[index],
                        expanded = expandedIndex == index,
                        onToggleExpand = { expandedIndex = if (expandedIndex == index) null else index },
                        onLabelChanged = { newLabel -> onMeasurementLabelChanged(index, newLabel) },
                        onRetryVoicePressed = { onRetryVoiceForMeasurementPressed(index) },
                        onDelete = {
                            onDeleteMeasurementPressed(index)
                            if (expandedIndex == index) expandedIndex = null
                        },
                    )
                }
            }
        }

        if (savedFileName != null) {
            Text(
                text = "Saved as $savedFileName",
                fontSize = 14.sp,
                color = Color(0xFF2E7D32),
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }

        if (cluster.isNotEmpty()) {
            if (showSaveEntry) {
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase() },
                    label = { Text("Patient code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                )
                Row {
                    BigButton(
                        text = "CANCEL",
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier.weight(1f),
                        onClick = { showSaveEntry = false },
                    )
                    BigButton(
                        text = "CONFIRM",
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f),
                        enabled = codeInput.isNotBlank(),
                        onClick = {
                            onSaveClusterPressed(codeInput)
                            showSaveEntry = false
                        },
                    )
                }
            } else {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    BigButton(
                        text = "CLEAR",
                        color = Color(0xFF9E9E9E),
                        modifier = Modifier.weight(1f),
                        onClick = onClearClusterPressed,
                    )
                    BigButton(
                        text = "SAVE (${cluster.size})",
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f),
                        onClick = { showSaveEntry = true },
                    )
                }
            }
        }
    }
}

/**
 * Read-only review of a saved session, reached by tapping a history row. Every
 * measurement is shown with its full chart (axis labels, legend, 0° baseline),
 * the primary/secondary numbers, and the mark tools. EDIT jumps to
 * [SessionEditScreen] for the same session; this screen writes nothing itself,
 * so viewing can never mutate or lose a saved session.
 */
@Composable
private fun SessionViewScreen(
    sessionName: String,
    measurements: List<CapturedMeasurement>,
    unreadable: Boolean,
    onBackPressed: () -> Unit,
    onEditPressed: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("< BACK", fontSize = 16.sp, color = Color(0xFF1565C0), modifier = Modifier.clickable(onClick = onBackPressed))
            Text("Session", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            if (!unreadable && measurements.isNotEmpty()) {
                Text(
                    "EDIT",
                    fontSize = 16.sp,
                    color = Color(0xFF6A1B9A),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable(onClick = onEditPressed),
                )
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        }
        Text(sessionName, fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        when {
            unreadable -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This session file is damaged and can't be read.", fontSize = 16.sp, color = Color(0xFFB71C1C))
            }
            measurements.isEmpty() -> Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No measurements in this session.", fontSize = 16.sp, color = Color.Gray)
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(measurements.size) { index ->
                    val m = measurements[index]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RomTypeBadge(romType = m.romType)
                            Text(
                                text = m.label.ifBlank { "Measurement ${index + 1}" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        MeasurementBody(m.result, detailed = true)
                    }
                }
            }
        }
    }
}

/**
 * Reopens a saved session for label correction: each row expands to the full
 * chart + numbers + marks, and the movement label can be edited. Cancel
 * discards the working copy; Save rewrites the file atomically and drops the
 * rendered chart PNGs so a later export re-renders them.
 */
@Composable
private fun SessionEditScreen(
    sessionName: String,
    measurements: List<CapturedMeasurement>,
    onLabelChanged: (Int, String) -> Unit,
    onRetryVoicePressed: (Int) -> Unit,
    onCancelPressed: () -> Unit,
    onSavePressed: () -> Unit,
) {
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("< CANCEL", fontSize = 16.sp, color = Color(0xFF1565C0), modifier = Modifier.clickable(onClick = onCancelPressed))
            Text("Review & edit", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(72.dp))
        }
        Text(sessionName, fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        if (measurements.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("This session couldn't be read.", fontSize = 18.sp, color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(measurements.size) { index ->
                    MeasurementCard(
                        index = index,
                        measurement = measurements[index],
                        expanded = expandedIndex == index,
                        onToggleExpand = { expandedIndex = if (expandedIndex == index) null else index },
                        onLabelChanged = { newLabel -> onLabelChanged(index, newLabel) },
                        onRetryVoicePressed = { onRetryVoicePressed(index) },
                        onDelete = null,
                    )
                }
            }
        }

        BigButton(
            text = "SAVE",
            color = Color(0xFF2E7D32),
            modifier = Modifier.padding(top = 8.dp),
            enabled = measurements.isNotEmpty(),
            onClick = onSavePressed,
        )
    }
}

/** Every cluster saved to disk so far — Send hands the file to KDE Connect/GSConnect. */
@Composable
private fun HistoryScreen(
    savedSessions: List<SavedSessionMeta>,
    sentSessionNames: Set<String>,
    exportedSessionNames: Set<String>,
    onBackPressed: () -> Unit,
    onViewSessionPressed: (String) -> Unit,
    onSendSessionPressed: (String) -> Unit,
    onExportSessionPressed: (String) -> Unit,
    onDeleteSessionPressed: (String) -> Unit,
    onEditSessionPressed: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("< BACK", fontSize = 16.sp, color = Color(0xFF1565C0), modifier = Modifier.clickable(onClick = onBackPressed))
            Text("Saved sessions", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.width(48.dp)) // balance the back label
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (savedSessions.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No saved sessions yet.", fontSize = 18.sp, color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(savedSessions.size) { i ->
                    val meta = savedSessions[i]
                    SavedSessionRow(
                        meta = meta,
                        sent = meta.sessionName in sentSessionNames,
                        exported = meta.sessionName in exportedSessionNames,
                        onRowPressed = { onViewSessionPressed(meta.sessionName) },
                        onSendPressed = { onSendSessionPressed(meta.sessionName) },
                        onExportPressed = { onExportSessionPressed(meta.sessionName) },
                        onDeletePressed = { onDeleteSessionPressed(meta.sessionName) },
                        onEditPressed = { onEditSessionPressed(meta.sessionName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedSessionRow(
    meta: SavedSessionMeta,
    sent: Boolean,
    exported: Boolean,
    onRowPressed: () -> Unit,
    onSendPressed: () -> Unit,
    onExportPressed: () -> Unit,
    onDeletePressed: () -> Unit,
    onEditPressed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        val count = meta.measurementCount
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (meta.readable) Modifier.clickable(onClick = onRowPressed) else Modifier),
            ) {
                Text(meta.patientCode, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                if (meta.readable) {
                    Text(
                        text = "$count measurement${if (count == 1) "" else "s"} · ${formatTimestamp(meta.createdMs)} · tap to view",
                        fontSize = 14.sp,
                        color = Color.Gray,
                    )
                } else {
                    Text(
                        text = "damaged file · ${formatTimestamp(meta.createdMs)}",
                        fontSize = 14.sp,
                        color = Color(0xFFB71C1C),
                    )
                }
            }
            LabelIconButton(text = "✕", color = Color(0xFFB71C1C), onClick = onDeletePressed)
        }
        if (!meta.readable) return@Column
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(if (sent) Color(0xFF9E9E9E) else Color(0xFF1565C0), shape = RoundedCornerShape(12.dp))
                    .clickable(onClick = onSendPressed)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(if (sent) "SENT ✓" else "SEND", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .background(if (exported) Color(0xFF9E9E9E) else Color(0xFF2E7D32), shape = RoundedCornerShape(12.dp))
                    .clickable(onClick = onExportPressed)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(if (exported) "EXPORTED ✓" else "EXPORT (USB)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF6A1B9A), shape = RoundedCornerShape(12.dp))
                    .clickable(onClick = onEditPressed)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("EDIT", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date(ms))

@Composable
private fun LabelIconButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(color, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text, fontSize = 20.sp, color = Color.White)
    }
}

@Composable
private fun MarkChip(index: Int, timeMs: Long, angleDeg: Float?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(
                color = if (selected) Color(0xFFEF6C00) else Color(0xFFE0E0E0),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = buildString {
                append("M${index + 1} · ${"%.1f".format(timeMs / 1000f)}s")
                if (angleDeg != null) append(" · ${"%.0f".format(angleDeg)}°")
            },
            color = if (selected) Color.White else Color.Black,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Angle reading at a single selected mark, in the same primary/secondary layout as the overall result. */
@Composable
private fun MarkDetail(markIndex: Int, sample: com.lancehouse.goniometer.Sample, primaryChannelIndex: Int) {
    val values = floatArrayOf(sample.roll, sample.pitch, sample.yaw)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF5F5F5), shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "Mark ${markIndex + 1} at ${"%.1f".format(sample.tMs / 1000f)}s",
            fontSize = 14.sp,
            color = Color.Gray,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (channel in CHANNEL_LABELS.indices) {
                val isPrimary = channel == primaryChannelIndex
                Column {
                    Text(CHANNEL_LABELS[channel], fontSize = 12.sp, color = Color.Gray)
                    Text(
                        "${"%.1f".format(values[channel])}°",
                        fontSize = if (isPrimary) 30.sp else 18.sp,
                        fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal,
                        color = if (isPrimary) MaterialTheme.colorScheme.primary else Color.Black,
                    )
                }
            }
        }
    }
}
