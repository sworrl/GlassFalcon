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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.AnnounceCategory
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.ui.*

private val DjiGreen = Color(0xFF00CC44)

/**
 * Voice / spoken-callout settings, the "fully tunable announcements" screen. A master switch, a
 * per-[AnnounceCategory] switch so the pilot hears exactly the callouts they want, and a "Read
 * status now" button that speaks a full live read-out (altitude, battery, GPS, wind, and every
 * active warning), the same read-out the co-pilot gives when asked for a "status".
 */
@Composable
fun VoiceSettingsScreen(vm: FlightViewModel) {
    // VoiceAnnouncer is pref-backed (not a StateFlow); mirror its state locally so the switches
    // recompose. Writes go straight through to the announcer, which persists them.
    var master by remember { mutableStateOf(vm.voice.enabled) }
    // A tick to force recompute of the per-category switches after a master toggle.
    var rev by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Spoken callouts", color = TextPri, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("Voice announcements through this phone's speaker.", color = TextSec, fontSize = 10.sp)
                    }
                    Switch(
                        checked = master,
                        onCheckedChange = { vm.voice.enabled = it; master = it; rev++ },
                        colors = SwitchDefaults.colors(checkedTrackColor = DjiGreen),
                    )
                }
                HorizontalDivider(color = Color(0x22FFFFFF))
                Button(
                    onClick = { vm.announceStatus() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("🔊  Read full status now", color = Gold, fontSize = 13.sp) }
                Text(
                    "Speaks a live read-out, altitude, speed, battery, GPS, home distance, wind, and " +
                    "every active warning. Also available by asking the AI co-pilot for a \"status\".",
                    color = TextSec, fontSize = 9.sp, lineHeight = 12.sp,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice quality", color = TextSec, fontSize = 11.sp)
                val voices = remember { vm.voice.availableVoices() }
                var current by remember { mutableStateOf(vm.voice.currentVoiceName()) }
                var menuOpen by remember { mutableStateOf(false) }
                val currentLabel = voices.firstOrNull { it.name == current }?.label ?: "Best available"
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Voice: $currentLabel", color = TextPri, fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        voices.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v.label, fontSize = 12.sp) },
                                onClick = { vm.voice.setVoice(v.name); current = v.name; menuOpen = false; vm.voice.preview() },
                            )
                        }
                    }
                }
                if (voices.isEmpty()) Text(
                    "Only your engine's default voice is available. For higher quality, install more voices " +
                    "in Android Settings → Text-to-speech, then reopen this screen.",
                    color = TextSec, fontSize = 9.sp, lineHeight = 12.sp,
                )
                var rate by remember { mutableStateOf(vm.voice.rate) }
                Text("Speed: ${"%.2f".format(rate)}×", color = TextSec, fontSize = 10.sp)
                Slider(
                    value = rate, onValueChange = { rate = it; vm.voice.setRate(it) },
                    valueRange = 0.6f..1.8f,
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold),
                )
                Button(
                    onClick = { vm.voice.preview() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("🔊 Test voice", color = Gold, fontSize = 12.sp) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("What to announce", color = TextSec, fontSize = 11.sp)
                Text(
                    "Mute any category you don't want to hear. Safety callouts (battery, motor & " +
                    "sensor faults) are on by default; routine chatter (obstacles, airspace) is off.",
                    color = TextSec, fontSize = 9.sp, lineHeight = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                key(rev) {
                    AnnounceCategory.entries.filter { it != AnnounceCategory.STATUS }.forEach { cat ->
                        CategoryRow(cat, vm, master)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(cat: AnnounceCategory, vm: FlightViewModel, masterOn: Boolean) {
    var on by remember(cat) { mutableStateOf(vm.voice.categoryEnabled(cat)) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(cat.label, color = if (masterOn) TextPri else TextSec, fontSize = 13.sp)
            Text(cat.desc, color = TextSec, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Switch(
            checked = on && masterOn,
            enabled = masterOn,
            onCheckedChange = { vm.voice.setCategoryEnabled(cat, it); on = it },
            colors = SwitchDefaults.colors(checkedTrackColor = DjiGreen),
        )
    }
}
