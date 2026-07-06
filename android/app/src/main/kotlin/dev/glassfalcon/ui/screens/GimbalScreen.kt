// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*
import kotlin.math.min

@Composable
fun GimbalScreen(vm: FlightViewModel) {
    val app    by vm.app.collectAsState()
    val gimbal by vm.gimbal.collectAsState()

    Row(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── ADI / Attitude display ────────────────────────────────────────
        Column(
            modifier = Modifier.width(240.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("GIMBAL ATTITUDE", color = TextSec, fontSize = 10.sp, letterSpacing = 1.sp)
            ArtificialHorizon(
                pitch = gimbal.pitch,
                roll  = gimbal.roll,
                modifier = Modifier.size(200.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AttVal("P", gimbal.pitch)
                AttVal("R", gimbal.roll)
                AttVal("Y", gimbal.yaw)
            }
            Text("Mode: ${gimbal.mode}", color = Gold, fontSize = 11.sp)
        }

        // ── Controls ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Quick presets
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quick Positions", color = TextSec, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.gimbalForward() },
                            enabled = app.connected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Border),
                        ) { Text("Forward  0°", fontSize = 12.sp) }
                        Button(
                            onClick = { vm.gimbalNadir() },
                            enabled = app.connected,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Border),
                        ) { Text("Nadir  -90°", fontSize = 12.sp) }
                    }
                }
            }

            // Manual abs angle
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Manual Angle", color = TextSec, fontSize = 11.sp)
                    var pitchStr by remember { mutableStateOf("0.0") }
                    var rollStr  by remember { mutableStateOf("0.0") }
                    var yawStr   by remember { mutableStateOf("0.0") }
                    var timeStr  by remember { mutableStateOf("20") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AngleField("Pitch", pitchStr, Modifier.weight(1f)) { pitchStr = it }
                        AngleField("Roll",  rollStr,  Modifier.weight(1f)) { rollStr  = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AngleField("Yaw",  yawStr,  Modifier.weight(1f)) { yawStr  = it }
                        AngleField("Time (0.1s)", timeStr, Modifier.weight(1f)) { timeStr = it }
                    }
                    Button(
                        onClick = {
                            if (app.connected) {
                                vm.duml.sendGimbal(dev.glassfalcon.core.Gimbal.absAngle(
                                    pitchStr.toFloatOrNull() ?: 0f,
                                    rollStr.toFloatOrNull()  ?: 0f,
                                    yawStr.toFloatOrNull()   ?: 0f,
                                    timeStr.toIntOrNull()    ?: 20,
                                ))
                                vm.log("Gimbal → P=$pitchStr R=$rollStr Y=$yawStr t=$timeStr")
                            }
                        },
                        enabled = app.connected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                    ) { Text("Send Angle", color = Gold) }
                }
            }

            // Mode + maintenance
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Maintenance", color = TextSec, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.gimbalCalibrate() },
                            enabled = app.connected,
                            modifier = Modifier.weight(1f),
                        ) { Text("Calibrate", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { vm.duml.sendGimbal(dev.glassfalcon.core.Gimbal.lock(true)) },
                            enabled = app.connected,
                            modifier = Modifier.weight(1f),
                        ) { Text("Lock", fontSize = 12.sp) }
                        OutlinedButton(
                            onClick = { vm.duml.sendGimbal(dev.glassfalcon.core.Gimbal.setMode(1)) },
                            enabled = app.connected,
                            modifier = Modifier.weight(1f),
                        ) { Text("Follow", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttVal(label: String, deg: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSec, fontSize = 10.sp)
        Text("${"%.1f".format(deg)}°", color = TextPri, fontSize = 14.sp)
    }
}

@Composable
private fun AngleField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
fun ArtificialHorizon(pitch: Float, roll: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r    = min(size.width, size.height) / 2f
        val cx   = size.width / 2
        val cy   = size.height / 2
        val pxPp = r / 45f       // pixels per degree pitch

        drawCircle(Border, radius = r, style = Stroke(1f))

        rotate(degrees = -roll, pivot = Offset(cx, cy)) {
            // Sky / ground horizon split by pitch
            val horizY = cy + pitch * pxPp
            // Sky
            drawRect(
                color    = DarkBg,
                topLeft  = Offset(cx - r, cy - r),
                size     = androidx.compose.ui.geometry.Size(r * 2, r),
            )
            // Ground
            drawRect(
                color    = Border,
                topLeft  = Offset(cx - r, cy),
                size     = androidx.compose.ui.geometry.Size(r * 2, r),
            )
            // Horizon line
            drawLine(Gold, Offset(cx - r, horizY), Offset(cx + r, horizY), strokeWidth = 2f)

            // Pitch lines ±10, ±20, ±30
            for (d in intArrayOf(-30, -20, -10, 10, 20, 30)) {
                val ly = horizY - d * pxPp
                val hw = if (d % 20 == 0) r * 0.35f else r * 0.2f
                drawLine(TextSec, Offset(cx - hw, ly), Offset(cx + hw, ly), strokeWidth = 1f)
            }
        }

        // Fixed aircraft symbol
        drawLine(Gold, Offset(cx - r * 0.45f, cy), Offset(cx - r * 0.1f, cy), strokeWidth = 3f)
        drawLine(Gold, Offset(cx + r * 0.1f, cy), Offset(cx + r * 0.45f, cy), strokeWidth = 3f)
        drawCircle(Gold, radius = 5f, center = Offset(cx, cy))
    }
}
