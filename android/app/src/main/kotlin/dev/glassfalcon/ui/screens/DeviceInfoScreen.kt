// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DjiGreen = Color(0xFF00CC44)
private val DjiAmber = Color(0xFFFFAA00)

/**
 * A plain-language "what do we actually know about the connected hardware" screen, separate
 * from DeviceScreen's raw dev-console (that one's for someone actively reverse-engineering the
 * protocol; this one's for "what firmware am I running and what does it/doesn't it do").
 *
 * The honest version of this screen is shorter than a DJI Assistant 2-style firmware panel:
 * GlassFalcon has never confirmed a byte layout for the version-inquiry/device-info ACK payload
 * (unlike e.g. FLYC OSD general, confirmed via kprobe, see project memory), so this shows the
 * REAL raw hex response live rather than inventing a parsed "v01.06.0100"-style string from a
 * format nobody's actually verified. See DeviceInfoRaw's doc comment in Telemetry.kt for why.
 */
@Composable
fun DeviceInfoScreen(vm: FlightViewModel) {
    val app by vm.app.collectAsState()
    val infoMap by vm.deviceInfoRaw.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Connection ──────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Connection", color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                InfoRow("Status", if (app.connected) "Connected" else "Not connected", if (app.connected) DjiGreen else TextSec)
                InfoRow("Transport", app.host.ifBlank { ", " }, TextPri)
            }
        }

        // ── Query buttons + raw responses ──────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Firmware / Device Queries", color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "These send the same DUML queries DJI Assistant 2 uses to read firmware " +
                    "info, but GlassFalcon has never confirmed the response's byte layout on " +
                    "real hardware, so what's shown below is the actual raw response, not a " +
                    "guessed-at version string. Tap a query, then read the hex.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.duml.send(General.versionInquiry()); vm.log("→ Version inquiry") },
                        enabled = app.connected, modifier = Modifier.weight(1f),
                    ) { Text("Version", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { vm.duml.send(General.getSerial()); vm.log("→ Get serial") },
                        enabled = app.connected, modifier = Modifier.weight(1f),
                    ) { Text("Serial", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = { vm.duml.send(FlyC.deviceInfo()); vm.log("→ FC device info") },
                        enabled = app.connected, modifier = Modifier.weight(1f),
                    ) { Text("FC Info", fontSize = 11.sp) }
                }

                val labels = listOf("Version Inquiry", "Serial (General)", "FC Device Info")
                labels.forEach { label ->
                    val entry = infoMap[label]
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(label, color = TextPri, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (entry != null) {
                            Text(entry.hex, color = DjiGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                "received ${dateFmt(entry.receivedAtMs)}",
                                color = TextSec, fontSize = 9.sp,
                            )
                        } else {
                            Text("no response yet", color = TextSec, fontSize = 10.sp)
                        }
                    }
                }

                Text(
                    "FC Serial isn't queryable here: it shares the exact same DUML cmd_set/cmd_id " +
                    "(0x03/0x36) as the smart battery's percentage broadcast, and there's no " +
                    "confirmed way to tell the two apart on the wire without risking one being " +
                    "misread as the other.",
                    color = TextSec, fontSize = 9.sp, lineHeight = 13.sp,
                )
            }
        }

        // ── What this app can't tell you ───────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What GlassFalcon Can't Show You (and why)", color = DjiAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                InfoBullet(
                    "Per-component firmware versions (motor ESC, gimbal, camera, RC240), no " +
                    "DUML command for these has been found/confirmed on this aircraft. DJI " +
                    "Assistant 2 (PC only) reads these over a different transport this project " +
                    "hasn't attempted.",
                )
                InfoBullet(
                    "Whether Remote ID / \"AeroScope\" broadcast is active. This is commonly " +
                    "confused with an app-controllable setting, but it isn't one: DJI's " +
                    "AeroScope broadcast (and, separately, the FAA's Remote ID rule, a " +
                    "different, non-ADS-B standard using Bluetooth/WiFi, not aviation " +
                    "transponder RF) both operate at the aircraft's own radio-link/firmware " +
                    "level, below the USB/DUML channel this app talks over. No GCS app, " +
                    "including DJI's own GO 4, has a toggle for this, and neither will " +
                    "GlassFalcon; there's no command channel to control it from here even in " +
                    "principle. A Mavic 2 Pro (2018) also predates DJI's Remote-ID firmware " +
                    "rollout to newer aircraft, so compliance on this specific airframe (if " +
                    "required in your jurisdiction) would need a separate physical broadcast " +
                    "module, not a software update.",
                )
                InfoBullet(
                    "A parsed, human-readable firmware version string (e.g. \"01.06.0100\"), " +
                    "the raw response above is real, but decoding it into a version number " +
                    "needs the actual byte layout confirmed on real hardware first (the same " +
                    "kprobe-based process this project used for the takeoff/landing opcodes), " +
                    "not a guess.",
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSec, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoBullet(text: String) {
    Text("•  $text", color = TextSec, fontSize = 10.sp, lineHeight = 14.sp)
}

private fun dateFmt(ms: Long) = dev.glassfalcon.core.Units.stamp(ms)
