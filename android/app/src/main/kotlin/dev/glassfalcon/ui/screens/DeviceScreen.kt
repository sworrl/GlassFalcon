// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*

// Matches the same DJI colors used elsewhere (each screen file keeps its own copy, there's no
// shared constant for these in Theme.kt).
private val DjiGreen = Color(0xFF00CC44)
private val DjiRed   = Color(0xFFFF3333)

@Composable
fun DeviceScreen(vm: FlightViewModel) {
    val app by vm.app.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── General commands ─────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("General", color = TextSec, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevButton("Ping",    enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.duml.send(General.ping()); vm.log("→ Ping")
                    }
                    DevButton("Version", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.duml.send(General.versionInquiry()); vm.log("→ Version inquiry")
                    }
                    DevButton("Serial",  enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.duml.send(General.getSerial()); vm.log("→ Get serial")
                    }
                }
            }
        }

        // ── HUD display ──────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HUD Display", color = TextSec, fontSize = 11.sp)
                val radarRing by vm.radarRing.collectAsState()
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Warning radar outer ring", color = TextPri, fontSize = 13.sp)
                        Text(
                            "The crisp outline arc on the obstacle domes. Off keeps the soft glow + color bleed.",
                            color = TextSec, fontSize = 10.sp,
                        )
                    }
                    Switch(checked = radarRing, onCheckedChange = { vm.setRadarRing(it) })
                }
            }
        }

        // ── Flight controller ────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Flight Controller", color = TextSec, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevButton("FC Info", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.duml.send(FlyC.deviceInfo()); vm.log("→ FC device info")
                    }
                    DevButton("FC Serial", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.duml.send(FlyC.requestSn()); vm.log("→ FC serial")
                    }
                }
            }
        }

        // ── Assistant unlock ─────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Assistant Unlock (ADB Access)", color = TextSec, fontSize = 11.sp)
                Text(
                    "Sends DUML cmd 0x03/0xDF with flag=1 to enable ADB on compatible firmware.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.assistantUnlock() },
                        enabled = app.connected,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                    ) { Text("Unlock", color = Gold) }
                    OutlinedButton(
                        onClick = {
                            vm.duml.send(FlyC.assistantUnlock(false)); vm.log("→ Unlock OFF")
                        },
                        enabled = app.connected,
                        modifier = Modifier.weight(1f),
                    ) { Text("Re-lock", fontSize = 12.sp) }
                }
            }
        }

        // ── Reboot ───────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Reboot / Module Select", color = TextSec, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FC" to 0x03, "Camera" to 0x01, "Gimbal" to 0x04).forEach { (name, dst) ->
                        OutlinedButton(
                            onClick = {
                                vm.rawSend(dst, 0x00, 0x0b, byteArrayOf())
                                vm.log("→ Reboot $name (0x${dst.toString(16)})")
                            },
                            enabled = app.connected,
                            modifier = Modifier.weight(1f),
                        ) { Text("↻ $name", fontSize = 11.sp) }
                    }
                }
            }
        }

        // ── Raw DUML console ─────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Raw DUML Console", color = TextSec, fontSize = 11.sp)
                var dstHex by remember { mutableStateOf("03") }
                var setHex by remember { mutableStateOf("00") }
                var idHex  by remember { mutableStateOf("00") }
                var payHex by remember { mutableStateOf("") }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HexField("Dst",     dstHex, Modifier.weight(1f)) { dstHex = it }
                    HexField("Set",     setHex, Modifier.weight(1f)) { setHex = it }
                    HexField("ID",      idHex,  Modifier.weight(1f)) { idHex  = it }
                }
                OutlinedTextField(
                    value = payHex,
                    onValueChange = { payHex = it },
                    label = { Text("Payload (hex, space-separated)", fontSize = 11.sp) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val dst = dstHex.removePrefix("0x").toIntOrNull(16) ?: return@Button
                        val cs  = setHex.removePrefix("0x").toIntOrNull(16) ?: return@Button
                        val ci  = idHex.removePrefix("0x").toIntOrNull(16)  ?: return@Button
                        val tokens = payHex.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
                        // toInt(16) throws on a bad token and would crash the app; validate
                        // all bytes first and bail with a log on any invalid hex.
                        val parsed = tokens.map { it.removePrefix("0x").toIntOrNull(16) }
                        if (parsed.any { it == null }) { vm.log("Raw send: invalid hex in payload"); return@Button }
                        val pay = parsed.map { it!!.toByte() }.toByteArray()
                        vm.rawSend(dst, cs, ci, pay)
                    },
                    enabled = app.connected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Border),
                ) { Text("Send", color = TextPri) }
            }
        }

        // ── Flight limits (height/radius), expert override ────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Flight Limits (Expert)", color = TextSec, fontSize = 11.sp)
                Text(
                    "Reads/writes the FC's OWN height and radius limit config over the generic " +
                    "param-hash channel DJI Assistant 2 uses (0x03/0xF8 read, 0x03/0xF9 write), " +
                    "not a client-side cap GlassFalcon enforces. Disabling assumes the two " +
                    "\"_enabled\" flags are single-byte booleans like every other confirmed hash " +
                    "in this table; that specific assumption hasn't been read-back-confirmed on " +
                    "real hardware yet, so verify the read-back below after disabling.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                val limits by vm.flightLimits.collectAsState()
                val ranges by vm.paramInfo.collectAsState()
                val rows = listOf(
                    "Beginner mode"        to FlyC.ParamHash.NOVICE_MODE_ENABLED,
                    "Height limit enabled" to FlyC.ParamHash.HEIGHT_LIMIT_ENABLED,
                    "Radius limit enabled" to FlyC.ParamHash.RADIUS_LIMIT_ENABLED,
                    "Max height"           to FlyC.ParamHash.MAX_HEIGHT,
                    "Max radius"           to FlyC.ParamHash.MAX_RADIUS,
                    "Min height"           to FlyC.ParamHash.MIN_HEIGHT,
                    "Novice max height"    to FlyC.ParamHash.NOVICE_MAX_HEIGHT,
                    "Novice max radius"    to FlyC.ParamHash.NOVICE_MAX_RADIUS,
                )
                rows.forEach { (label, hash) ->
                    val raw = limits[hash]
                    val info = ranges[hash]
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, color = TextSec, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        // FC-declared range, if we've probed it, the real answer to "what can we set"
                        info?.let {
                            Text(
                                "[%.0f–%.0f]".format(it.min, it.max),
                                color = DjiGreen, fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                        Text(
                            raw?.joinToString(" ") { "%02x".format(it) } ?: ", ",
                            color = if (raw != null) TextPri else TextSec, fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
                var confirmDisable by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevButton("Read", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.readFlightLimits()
                    }
                    DevButton("Probe range", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.probeFlightLimitRanges()
                    }
                    Button(
                        onClick = { confirmDisable = true },
                        enabled = app.connected,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = DjiRed.copy(alpha = 0.2f)),
                    ) { Text("Disable limits", color = DjiRed, fontSize = 12.sp) }
                }
                // Beginner mode off + max-altitude presets, the two things a returning pilot
                // most wants off/raised. "Set max alt" uses the probed ceiling if we have it.
                val maxAltCeiling = ranges[FlyC.ParamHash.MAX_HEIGHT]?.max?.toInt() ?: 500
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevButton("Beginner OFF", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.setBeginnerMode(false)
                    }
                    DevButton("Max alt ${maxAltCeiling}m", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.setMaxHeight(maxAltCeiling)
                    }
                }
                // (No "Record Home Point" button: sending setHomePoint (0x03/0x31) RE-LOCKS the 30 m
                // altitude cap on the wm240, confirmed live 2026-07-05. The aircraft records its
                // own home automatically; we must never send it. Left the note as a tripwire.)
                // Re-write every limit under the PC / DJI-Assistant identity (0x0a), the channel a
                // rooted phone reaches over serial. The FC honours some writes only as PC, so this
                // is the likely lever for the 30 m cap that clears on the rooted device.
                DevButton("🔓 Force Unlock (PC identity)", enabled = app.connected, modifier = Modifier.fillMaxWidth()) {
                    vm.forceUnlockPc()
                }
                // Free-entry limit test, type ANY value (including 0 to attempt "no limit") and
                // write it straight to the FC. The FC clamps to its own real bounds and the
                // read-back row above shows exactly what it stored, so this is the way to probe
                // what the aircraft will actually accept (e.g. is 1000 m real, or does firmware cap
                // at 500?) without relying on presets. 0 is passed through verbatim: whether the FC
                // treats it as "no limit" or literally 0 is exactly what you're testing.
                Text(
                    "Set custom limit (m), 0 attempts \"no limit\"; FC clamps to its own bounds, " +
                    "confirm with the read-back above:",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                LimitEntryRow(
                    label = "Max height",
                    enabled = app.connected,
                    onSet = { vm.setMaxHeight(it) },
                )
                LimitEntryRow(
                    label = "Max distance",
                    enabled = app.connected,
                    onSet = { vm.setMaxRadius(it) },
                )
                // Low-battery forced-action override. GlassFalcon never auto-lands/RTHs, this
                // lowers the FC's OWN smart-battery RTH (level_2) and forced-land (level_1) voltage
                // thresholds to the FC's declared minimum, so those forced actions hold off to the
                // latest point the firmware allows. It cannot fully disable them and past the
                // minimum the aircraft WILL come down on its own.
                var confirmBatt by remember { mutableStateOf(false) }
                Text(
                    "The aircraft's OWN low-battery auto-RTH / forced-landing is a DJI FC safety, " +
                    "not GlassFalcon. This pushes both thresholds to the FC minimum (as late as " +
                    "firmware allows, cannot be fully turned off):",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Button(
                    onClick = { confirmBatt = true },
                    enabled = app.connected,
                    colors = ButtonDefaults.buttonColors(containerColor = DjiRed.copy(alpha = 0.2f)),
                ) { Text("Minimize low-battery RTH / forced land", color = DjiRed, fontSize = 12.sp) }
                if (confirmBatt) {
                    AlertDialog(
                        onDismissRequest = { confirmBatt = false },
                        title = { Text("Minimize low-battery safety?") },
                        text = {
                            Text(
                                "This delays the aircraft's auto-RTH and forced landing to the FC's " +
                                "lowest allowed battery voltage. Past that point it will lose power " +
                                "and fall, there is no recovery. Only do this if you accept that risk " +
                                "and are maintaining full manual control.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmBatt = false; vm.minimizeLowBatteryActions() }) {
                                Text("Minimize", color = DjiRed)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmBatt = false }) { Text("Cancel") } },
                    )
                }
                // FC tuning presets via the index-based param protocol, "faster but stable"
                // (raises DJI's own speed LIMITS, never the control-loop gains). Needs a link that
                // honors the 0x0a assistant identity (direct USB, or the RC path if it forwards it).
                Text(
                    "FC tuning (index params), Sport Boost raises Sport tilt/vertical-speed limits " +
                    "toward the FC max; Wind Resistance maxes wind_anti_intensity:",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevButton("⚡ Sport Boost", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.sportBoost()
                    }
                    DevButton("🌬 Max Wind Resist", enabled = app.connected, modifier = Modifier.weight(1f)) {
                        vm.maxWindResistance()
                    }
                }
                // Full-config probe, dumps the entire FC param table (name/type/bounds/value) to
                // the GF_FCDUMP log, for comparing firmware versions / a friend's Zoom on newer,
                // more-locked firmware. Takes a few minutes.
                DevButton("🔎 Probe / dump full FC config", enabled = app.connected, modifier = Modifier.fillMaxWidth()) {
                    vm.dumpFcConfig()
                }
                if (confirmDisable) {
                    AlertDialog(
                        onDismissRequest = { confirmDisable = false },
                        title = { Text("Disable height/radius limits?") },
                        text = {
                            Text(
                                "This raises the aircraft's own configured ceiling and radius " +
                                "caps, not a GlassFalcon-side check. Only do this if you already " +
                                "know your local airspace rules and can maintain VLOS/RC range " +
                                "at whatever height or distance the aircraft will now let you fly.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmDisable = false; vm.disableFlightLimits() }) {
                                Text("Disable", color = DjiRed)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmDisable = false }) { Text("Cancel") } },
                    )
                }
            }
        }

        // ── RC physical button research ──────────────────────────────────
        // cmd_set=0x06/cmd_id=0x4c ("RC Pro Custom Buttons Status Get/Push") and 0x51 ("RC
        // Push To Glass") are named in the community dissector as the RC's own button-state
        // push, but no byte layout is documented for either. This shows the raw hex live so a
        // press-and-watch session can find which byte/bit maps to which physical button (C1/
        // C2/shutter/record), press one button at a time and see what changes below.
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("RC Buttons (raw, unconfirmed layout)", color = TextSec, fontSize = 11.sp)
                Text(
                    "Press physical RC buttons one at a time and watch which byte changes, " +
                    "this is how we'll find the real button mapping for co-pilot PTT.",
                    color = TextSec, fontSize = 9.sp, lineHeight = 12.sp,
                )
                val history by vm.rcButtonHistory.collectAsState()
                if (history.isEmpty()) {
                    Text("No button-status frame seen yet", color = TextSec, fontSize = 11.sp)
                } else {
                    val now = System.currentTimeMillis()
                    // Newest first, most recent change highlighted, that's the one that just
                    // fired from whatever button was pressed a moment ago.
                    history.reversed().forEachIndexed { i, entry ->
                        val secAgo = (now - entry.changedAtMs) / 1000
                        Text(
                            "cmd_id 0x%02x: %s  (%ds ago)".format(entry.cmdId, entry.hex, secAgo),
                            color = if (i == 0) DjiGreen else TextSec,
                            fontSize = if (i == 0) 13.sp else 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        // ── Companion "map-only HUD" ──────────────────────────────────────
        // A 2nd phone/tablet on the same LAN/tether/ad-hoc network can watch live drone
        // position without any flight controls of its own, see CompanionSync.kt. This
        // device is the one flying: broadcasting just streams telemetry out, it doesn't
        // change anything about this device's own controls.
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Companion Mode (map-only 2nd device)", color = TextSec, fontSize = 11.sp)
                val broadcasting by vm.companionBroadcasting.collectAsState()
                val clientCount by vm.companionClientCount.collectAsState()
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Broadcast telemetry to companions", color = TextPri, fontSize = 12.sp)
                        Text(
                            if (broadcasting) "${clientCount} connected" else "off, this device keeps flying either way",
                            color = if (broadcasting) DjiGreen else TextSec, fontSize = 10.sp,
                        )
                    }
                    Switch(
                        checked = broadcasting,
                        onCheckedChange = { if (it) vm.startCompanionBroadcast() else vm.stopCompanionBroadcast() },
                    )
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                OutlinedButton(
                    onClick = {
                        dev.glassfalcon.core.CompanionModeStore.setCompanionMode(context, true)
                        (context as? android.app.Activity)?.recreate()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Make THIS device a companion (map-only) instead", fontSize = 11.sp) }
            }
        }

        // ── Log ─────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
            colors = CardDefaults.cardColors(containerColor = DarkBg),
        ) {
            val logs by vm.app.collectAsState()
            Box(Modifier.padding(8.dp)) {
                Text(
                    logs.log.takeLast(60).joinToString("\n"),
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun DevButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, fontSize = 11.sp)
    }
}

/** Numeric entry + Set for one flight-limit param. Accepts any non-negative integer including 0;
 *  Set is disabled while the field is empty or non-numeric so a stray write can't send garbage. */
@Composable
private fun LimitEntryRow(label: String, enabled: Boolean, onSet: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed >= 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { s -> text = s.filter { it.isDigit() }.take(5) },
            label = { Text(label, fontSize = 10.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        DevButton("Set", enabled = enabled && valid, modifier = Modifier.width(72.dp)) {
            parsed?.let(onSet)
        }
    }
}

@Composable
private fun HexField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 10.sp) },
        singleLine = true,
        modifier = modifier,
    )
}
