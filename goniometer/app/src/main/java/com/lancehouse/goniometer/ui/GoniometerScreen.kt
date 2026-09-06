package com.lancehouse.goniometer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lancehouse.goniometer.SessionPhase
import com.lancehouse.goniometer.SessionResult

/**
 * Two very different layouts:
 *  - IDLE / RECORDING: just the two big buttons. Nothing here needs to be
 *    read — it's operated by feel. (SPEC.md §7)
 *  - STOPPED: the results screen — big primary number, small secondary
 *    numbers, the line graph, and Clear.
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
    onStartStopPressed: () -> Unit,
    onMarkPressed: () -> Unit,
    onClearPressed: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (phase == SessionPhase.RECORDING) Color(0xFFFFF3E0) else Color.White)
    ) {
        when (phase) {
            SessionPhase.STOPPED -> ResultsScreen(result, onClearPressed)
            else -> CaptureScreen(phase, onStartStopPressed, onMarkPressed)
        }
    }
}

@Composable
private fun CaptureScreen(
    phase: SessionPhase,
    onStartStopPressed: () -> Unit,
    onMarkPressed: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = if (phase == SessionPhase.RECORDING) "Recording…" else "Ready",
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Big buttons — findable without looking. Volume Up/Down do the same
        // thing physically (see MainActivity.onKeyDown).
        BigButton(
            text = if (phase == SessionPhase.RECORDING) "STOP" else "START",
            color = if (phase == SessionPhase.RECORDING) Color(0xFFC62828) else Color(0xFF2E7D32),
            weight = 2f,
            onClick = onStartStopPressed,
        )
        BigButton(
            text = "MARK",
            color = Color(0xFF1565C0),
            weight = 1.4f,
            enabled = phase == SessionPhase.RECORDING,
            onClick = onMarkPressed,
        )
    }
}

/**
 * Note: this must be called from within a ColumnScope (relies on
 * Modifier.weight), which both call sites (CaptureScreen, ResultsScreen)
 * satisfy.
 */
@Composable
private fun ColumnScope.BigButton(
    text: String,
    color: Color,
    weight: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier
            .fillMaxWidth()
            .weight(weight)
            .padding(vertical = 4.dp),
    ) {
        Text(text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultsScreen(result: SessionResult?, onClearPressed: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        if (result == null || result.samples.isEmpty()) {
            Text("No data captured.", fontSize = 20.sp)
        } else {
            Text(
                text = result.primaryLabel,
                fontSize = 20.sp,
                color = Color.Gray,
            )
            Text(
                text = "${"%.1f".format(result.primaryRangeDeg)}°",
                fontSize = 88.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for ((label, value) in result.secondaryChannels()) {
                    Column {
                        Text(label, fontSize = 14.sp, color = Color.Gray)
                        Text("${"%.1f".format(value)}°", fontSize = 22.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            RomChart(
                samples = result.samples,
                marks = result.marks,
                primaryChannelIndex = result.primaryChannelIndex,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        BigButton(text = "CLEAR", color = Color(0xFF616161), weight = 1f, onClick = onClearPressed)
    }
}
