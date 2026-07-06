// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.core.Transport

@Composable
fun GfHeader(vm: FlightViewModel) {
    val app   by vm.app.collectAsState()
    val drone by vm.drone.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "GLASS FALCON",
            color = Gold,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 3.sp,
        )

        Spacer(Modifier.width(24.dp))

        // Connection button
        var showDialog by remember { mutableStateOf(false) }
        val transportLabel = when (vm.duml.transport) {
            Transport.USB  -> "USB"
            Transport.TCP  -> "TCP"
            Transport.AOA  -> "AOA"
            Transport.NONE -> ""
        }
        if (app.connected) {
            Button(
                onClick = { vm.disconnect() },
                colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = 0.15f)),
            ) {
                Text("● $transportLabel  Disconnect", color = Red, fontSize = 12.sp)
            }
        } else {
            Button(
                onClick = { showDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Green.copy(alpha = 0.15f)),
            ) {
                Text("○ Connect (TCP)", color = Green, fontSize = 12.sp)
            }
        }

        if (showDialog) ConnectDialog(
            initial = app.host,
            onDismiss = { showDialog = false },
            onConnect = { h, p -> vm.connect(h, p); showDialog = false },
        )

        Spacer(Modifier.width(16.dp))

        // Quick telemetry pills
        if (app.connected) {
            TelPill("BATT", if (drone.battPct > 0) "${drone.battPct}%" else ", ",
                when { drone.battPct > 50 -> Green; drone.battPct > 20 -> Orange; drone.battPct > 0 -> Red; else -> TextPri })
            Spacer(Modifier.width(8.dp))
            TelPill("ALT", "${"%.1f".format(drone.altRel)}m", TextPri)
            Spacer(Modifier.width(8.dp))
            TelPill("SPD", "${"%.1f".format(drone.speed)}m/s", TextPri)
            Spacer(Modifier.width(8.dp))
            TelPill("GPS", if (drone.hasGpsFix) "Fix" else "NoFix",
                if (drone.hasGpsFix) Green else Red)
        }
        Spacer(Modifier.weight(1f))
    }
    HorizontalDivider(color = Border)
}

@Composable
private fun TelPill(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = Panel,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label ", color = TextSec, fontSize = 10.sp)
            Text(value,    color = color,    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                 maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun ConnectDialog(initial: String, onDismiss: () -> Unit,
                          onConnect: (String, Int) -> Unit) {
    var host by remember { mutableStateOf(initial) }
    var port by remember { mutableStateOf("10000") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to Drone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "USB: plug RC240 or drone into phone, connects automatically (no entry needed).\n" +
                    "TCP: enter IP for a Glass Falcon PC relay or drone RNDIS (192.168.42.2:10000).",
                    color = TextSec, fontSize = 12.sp,
                )
                OutlinedTextField(value = host, onValueChange = { host = it },
                                  label = { Text("Host") }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it },
                                  label = { Text("Port") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(host, port.toIntOrNull() ?: 10000) }) {
                Text("Connect", color = Green)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Panel,
    )
}
