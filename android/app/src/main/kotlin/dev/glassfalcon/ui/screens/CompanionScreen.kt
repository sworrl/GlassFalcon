// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.CompanionClient
import dev.glassfalcon.core.CompanionModeStore

private val DjiGreen = Color(0xFF00CC44)
private val DjiAmber = Color(0xFFFFAA00)
private val Navy = Color(0xFF0B0F14)

/**
 * The whole point of companion mode: a 2nd phone/tablet with no DUML link of its own, showing
 * only live drone position, a spotter or passenger can watch the flight on a map without
 * touching (or being able to touch) any flight control, which stays exclusively on the primary
 * phone. Discovers a [dev.glassfalcon.core.CompanionServer] via NSD on the shared LAN/tether/
 * ad-hoc network; a manual host:port field is the fallback for networks where mDNS discovery
 * doesn't reach (common with AP/client isolation on some routers).
 */
@Composable
fun CompanionModeScreen(onExitCompanionMode: () -> Unit) {
    val context = LocalContext.current
    val client = remember { CompanionClient(context) }
    val scope = rememberCoroutineScope()

    val discovered by client.discovered.collectAsState()
    val connected by client.connected.collectAsState()
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        client.startDiscovery()
        onDispose { client.stopDiscovery(); client.disconnect() }
    }

    Box(Modifier.fillMaxSize().background(Navy)) {
        if (connected) {
            FlightMap(client, Modifier.fillMaxSize(), compact = false)
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(color = DjiGreen.copy(alpha = 0.25f), shape = MaterialTheme.shapes.small) {
                    Text(" ● COMPANION, MAP ONLY ", color = DjiGreen, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
                TextButton(onClick = { client.disconnect() }) { Text("Disconnect", color = DjiAmber, fontSize = 11.sp) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Companion Mode", color = DjiGreen, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Map-only HUD, no flight controls here. Connect to the phone that's " +
                    "actually flying, on the same Wi-Fi/hotspot/tether network.",
                    color = Color(0xFFAAAAAA), fontSize = 12.sp,
                )
                Spacer(Modifier.height(20.dp))

                if (discovered.isEmpty()) {
                    Text("Searching for a GlassFalcon primary device…", color = Color(0xFF888888), fontSize = 12.sp)
                } else {
                    discovered.forEach { d ->
                        Surface(
                            color = Color(0xFF16181B), shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(d.name, color = Color.White, fontSize = 13.sp)
                                    Text("${d.host}:${d.port}", color = Color(0xFF888888), fontSize = 10.sp)
                                }
                                Button(
                                    onClick = { client.connect(scope, d.host, d.port) },
                                    colors = ButtonDefaults.buttonColors(containerColor = DjiGreen.copy(alpha = 0.25f)),
                                ) { Text("Connect", color = DjiGreen, fontSize = 12.sp) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("Not found automatically? Enter its address manually:", color = Color(0xFF888888), fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = manualHost, onValueChange = { manualHost = it },
                        label = { Text("Host / IP", fontSize = 10.sp) }, singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = manualPort, onValueChange = { manualPort = it },
                        label = { Text("Port", fontSize = 10.sp) }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { manualPort.toIntOrNull()?.let { p -> client.connect(scope, manualHost, p) } },
                    enabled = manualHost.isNotBlank() && manualPort.toIntOrNull() != null,
                    colors = ButtonDefaults.buttonColors(containerColor = DjiGreen.copy(alpha = 0.2f)),
                ) { Text("Connect manually", color = DjiGreen, fontSize = 12.sp) }

                Spacer(Modifier.height(28.dp))
                TextButton(onClick = {
                    CompanionModeStore.setCompanionMode(context, false)
                    onExitCompanionMode()
                }) { Text("Exit companion mode → back to normal flight app", color = Color(0xFF888888), fontSize = 11.sp) }
            }
        }
    }
}
