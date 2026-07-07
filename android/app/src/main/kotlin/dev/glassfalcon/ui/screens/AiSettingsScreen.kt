// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*
import dev.glassfalcon.ui.components.glass

// Matches the same DJI-green used elsewhere (MainScreen.kt/MapScreen.kt each keep their own
// copy too, there's no shared constant for it in Theme.kt).
private val DjiGreen = Color(0xFF00CC44)
private val DjiRed = Color(0xFFFF3333)

/**
 * All AI/co-pilot configuration lives here, off the main flight HUD, the co-pilot is opt-in
 * (defaults off) and every key a pilot enters stays on-device, sent only to the model provider
 * that key belongs to, never to a GlassFalcon-run server (there isn't one).
 */
@Composable
fun AiSettingsScreen(vm: FlightViewModel) {
    val app by vm.app.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Voice Co-Pilot", color = TextSec, fontSize = 11.sp)
                Text("Off by default. Two modes, pick one:", color = TextSec, fontSize = 10.sp)

                CopilotModeRow(
                    selected = app.copilotMode == CopilotMode.OFF,
                    title = "Off", desc = "No callouts, no listening.",
                    onClick = { vm.setCopilotMode(CopilotMode.OFF) },
                )
                CopilotModeRow(
                    selected = app.copilotMode == CopilotMode.RULE_BASED,
                    title = "Co-Pilot", desc = "Speaks fixed, pre-written callouts (battery, GPS, warnings, airspace) via text-to-speech. No LLM, no network, nothing to configure.",
                    onClick = { vm.setCopilotMode(CopilotMode.RULE_BASED) },
                )
                CopilotModeRow(
                    selected = app.copilotMode == CopilotMode.AI_ASSISTED,
                    title = "AI Assisted Copilot (on-device Nano)", desc = "Everything Co-Pilot does, plus push-to-talk questions and drone-action requests (\"return home\", \"take a photo\") answered by on-device Gemini Nano, no API key, no network call.",
                    onClick = { vm.setCopilotMode(CopilotMode.AI_ASSISTED) },
                )
                CopilotModeRow(
                    selected = app.copilotMode == CopilotMode.GEMINI_CLOUD,
                    title = "Gemini Copilot (cloud)", desc = "Uses Google's full Gemini model as the co-pilot, the same voice Q&A and drone actions, but far more capable than on-device Nano. Needs your Gemini API key; sends flight context to Google's cloud each request.",
                    onClick = { vm.setCopilotMode(CopilotMode.GEMINI_CLOUD) },
                )
                CopilotModeRow(
                    selected = app.copilotMode == CopilotMode.HYBRID,
                    title = "Hybrid (Nano + Gemini)", desc = "Best of both: on-device Nano recognises your spoken commands INSTANTLY (\"return home\", \"take a photo\") with no network wait, while cloud Gemini writes the richer, better-worded answers for everything conversational. Needs both Nano on this phone and a Gemini key.",
                    onClick = { vm.setCopilotMode(CopilotMode.HYBRID) },
                )

                if (app.copilotMode == CopilotMode.AI_ASSISTED || app.copilotMode == CopilotMode.HYBRID) {
                    Spacer(Modifier.height(4.dp))
                    NanoStatusPanel(vm)
                }

                if (app.copilotMode == CopilotMode.GEMINI_CLOUD || app.copilotMode == CopilotMode.HYBRID) {
                    Spacer(Modifier.height(4.dp))
                    var gemKey by remember { mutableStateOf(app.geminiApiKey) }
                    OutlinedTextField(
                        value = gemKey, onValueChange = { gemKey = it },
                        label = { Text("Gemini API key", fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.setGeminiKey(gemKey) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                    ) { Text(if (app.geminiApiKey.isBlank()) "Save Key" else "Update Key", color = Gold, fontSize = 12.sp) }
                    Text(
                        if (app.geminiApiKey.isBlank()) "No key set yet, the Gemini co-pilot needs one. Get a free key at aistudio.google.com."
                        else "✓ Key saved on this device. Gemini is now the co-pilot for push-to-talk.",
                        color = if (app.geminiApiKey.isBlank()) DjiRed else DjiGreen, fontSize = 9.sp, lineHeight = 12.sp,
                    )
                }
            }
        }

        // Typed co-pilot Q&A. Push-to-talk was the only way to ask the co-pilot anything; this
        // types the same question straight into askCoPilot() for quiet environments or when the
        // mic isn't practical. Answer + thinking state are the same flows the voice path uses.
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask the Co-Pilot (typed)", color = TextSec, fontSize = 11.sp)
                val answer by vm.coPilotAnswer.collectAsState()
                val thinking by vm.coPilotThinking.collectAsState()
                var question by remember { mutableStateOf("") }
                val canAsk = question.isNotBlank() &&
                    app.copilotMode != CopilotMode.OFF && app.copilotMode != CopilotMode.RULE_BASED
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("Type a question for the co-pilot…", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.askCoPilot(question.trim()); question = "" },
                    enabled = canAsk,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("Ask", color = Gold, fontSize = 12.sp) }
                when {
                    app.copilotMode == CopilotMode.OFF || app.copilotMode == CopilotMode.RULE_BASED ->
                        Text("Pick an AI co-pilot mode above to enable questions.", color = TextSec, fontSize = 9.sp)
                    thinking -> Text("Thinking…", color = Gold, fontSize = 12.sp)
                    answer.isNotBlank() -> Text(answer, color = TextPri, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }

        // Windy weather keys. GlassFalcon shows a live weather readout when a Windy API key is
        // set; there was no field to enter one. Point-forecast and map keys are separate Windy
        // products, so both are offered; leave a field blank to send none for it.
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Weather (Windy API)", color = TextSec, fontSize = 11.sp)
                val weather by vm.weather.collectAsState()
                var pointKey by remember { mutableStateOf("") }
                var mapKey by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = pointKey, onValueChange = { pointKey = it },
                    label = { Text("Windy Point-Forecast key", fontSize = 10.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mapKey, onValueChange = { mapKey = it },
                    label = { Text("Windy Map key (optional)", fontSize = 10.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.setWindyKeys(pointKey.trim().ifBlank { null }, mapKey.trim().ifBlank { null }, null) },
                    enabled = pointKey.isNotBlank() || mapKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("Save keys & fetch", color = Gold, fontSize = 12.sp) }
                Text(
                    if (weather != null) "✓ Weather data loaded." else "No weather yet. A free key is at api.windy.com.",
                    color = if (weather != null) DjiGreen else TextSec, fontSize = 9.sp,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Controller Buttons", color = TextSec, fontSize = 11.sp)
                Text(
                    "The RC240's two dedicated custom triggers (C1/C2) plus anything else worth " +
                    "mapping, like the 5-way dial near the screen (up/down/left/right/press are " +
                    "each their own signature). Not the shutter/record/RTH/pause controls, which " +
                    "are fixed-function already. We haven't confirmed which raw byte means which " +
                    "physical control yet, so instead of guessing: press ONE control at a time " +
                    "while learning, pressing several in a row only captures the first one. " +
                    "Defaults: Button 1 → Push-to-Talk, Button 2 → Toggle Landing Light.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 13.sp,
                )
                val slots by vm.rcButtonSlots.collectAsState()
                val learning by vm.learningSlot.collectAsState()
                val guided by vm.guidedCalibration.collectAsState()

                if (learning != null) {
                    val zoneIdx = (learning!! - 1).coerceIn(0, RC_ZONE_LABELS.lastIndex)
                    val learnedZones = slots.withIndex()
                        .filter { it.value.signature != null }.map { it.index }.toSet()
                    Text(
                        if (guided) "Guided setup, press: ${RC_ZONE_LABELS[zoneIdx]}"
                        else "Press the physical control for Button $learning now…",
                        color = DjiGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                    RcControllerDiagram(
                        activeZone = if (guided) zoneIdx else null,
                        learnedZones = learnedZones,
                        modifier = Modifier.padding(vertical = 8.dp).align(Alignment.CenterHorizontally),
                    )
                    TextButton(onClick = { vm.cancelLearningButton() }) {
                        Text("Cancel", color = TextSec, fontSize = 11.sp)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.startGuidedCalibration() }) {
                            Text("▶ Guided setup (all buttons, one at a time)", color = Gold, fontSize = 11.sp)
                        }
                    }
                }

                slots.forEachIndexed { i, slot ->
                    val slotIndex = i + 1
                    ButtonSlotRow(
                        slotIndex = slotIndex, slot = slot, isLearning = learning == slotIndex,
                        onLearn = { vm.startLearningButton(slotIndex) },
                        onCancelLearn = { vm.cancelLearningButton() },
                        onActionChange = { vm.setButtonAction(slotIndex, it) },
                    )
                }

                if (!guided) {
                    TextButton(onClick = { vm.addRcButtonSlot() }) {
                        Text("+ Add another button (e.g. a dial direction)", color = TextSec, fontSize = 11.sp)
                    }
                }
            }
        }

        // Cloud LLM code is hidden here, not deleted, AI Assisted Copilot uses on-device Nano
        // by default and needs no key at all. This only matters on a phone without AICore.
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setShowCloudAiOptions(!app.showCloudAiOptions) },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (app.showCloudAiOptions) "▾ " else "▸ ") + "Advanced: cloud AI (optional)",
                        color = TextSec, fontSize = 11.sp,
                    )
                }
                if (app.showCloudAiOptions) {
                    Text(
                        "Not needed for AI Assisted Copilot, that runs entirely on-device. Only " +
                        "useful as a fallback on a phone without Gemini Nano. Setting a key here " +
                        "means questions route to Google's cloud instead of staying on the phone.",
                        color = TextSec, fontSize = 9.sp, lineHeight = 12.sp,
                    )
                    HorizontalDivider(color = Color(0x33FFFFFF))

                    Text("Cloud model, Gemini (co-pilot Q&A fallback)", color = TextSec, fontSize = 11.sp)
                    var geminiInput by remember { mutableStateOf(app.geminiApiKey) }
                    OutlinedTextField(
                        value = geminiInput,
                        onValueChange = { geminiInput = it },
                        label = { Text("Gemini API key", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.setGeminiKey(geminiInput) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                    ) { Text(if (app.geminiApiKey.isBlank()) "Save Key" else "Update Key", color = Gold, fontSize = 12.sp) }
                    if (app.geminiApiKey.isNotBlank()) {
                        Text("Key saved on this device (persists across restarts).",
                            color = TextSec, fontSize = 9.sp)
                    }

                    HorizontalDivider(color = Color(0x33FFFFFF))
                    Text("Cloud model, Claude (mission planning)", color = TextSec, fontSize = 11.sp)
                    Text(
                        "Set from the Mission tab, a separate key, used only for natural-language " +
                        "mission planning, not the co-pilot.",
                        color = TextSec, fontSize = 10.sp,
                    )
                    Text(
                        if (app.claudeApiKey.isBlank()) "Not set" else "Key set",
                        color = if (app.claudeApiKey.isBlank()) TextSec else DjiGreen, fontSize = 11.sp,
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("On-device (local)", color = TextSec, fontSize = 11.sp)
                Text(
                    "Push-to-talk speech recognition runs via the phone's own on-device speech " +
                    "engine where available, no separate setup. Text answers and drone-action " +
                    "requests in AI Assisted Copilot mode run on Gemini Nano via AICore, entirely " +
                    "on-device, see the status above once that mode is selected.",
                    color = TextSec, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun CopilotModeRow(selected: Boolean, title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = DjiGreen))
        Column(Modifier.padding(top = 10.dp)) {
            Text(title, color = TextPri, fontSize = 13.sp)
            Text(desc, color = TextSec, fontSize = 10.sp, lineHeight = 13.sp)
        }
    }
}

/** Sanity-checks the device before letting the pilot lean on AI Assisted Copilot, per the
 *  explicit ask to check devices expose Nano before allowing this mode, rather than silently
 *  failing on first PTT press mid-flight. */
@Composable
private fun NanoStatusPanel(vm: FlightViewModel) {
    val status by vm.nanoStatus.collectAsState()
    val downloading by vm.nanoDownloading.collectAsState()
    LaunchedEffect(Unit) { vm.refreshNanoStatus() }

    Column(
        Modifier.fillMaxWidth().glass(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), baseAlpha = 0.2f).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            downloading -> Text("Downloading on-device model…", color = Gold, fontSize = 11.sp)
            status == null -> Text("Checking this device for Gemini Nano…", color = TextSec, fontSize = 11.sp)
            status == com.google.mlkit.genai.common.FeatureStatus.AVAILABLE ->
                Text("✓ Gemini Nano ready on this device", color = DjiGreen, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            status == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADABLE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Gemini Nano is supported but not downloaded yet.", color = Gold, fontSize = 11.sp)
                Button(onClick = { vm.downloadNanoModel() }, colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f))) {
                    Text("Download on-device model", color = Gold, fontSize = 11.sp)
                }
            }
            status == com.google.mlkit.genai.common.FeatureStatus.DOWNLOADING -> Text("Model download in progress…", color = Gold, fontSize = 11.sp)
            else -> Text(
                "This phone doesn't support Gemini Nano, AI Assisted Copilot's Q&A won't work " +
                "here. Fixed-callout mode (\"Co-Pilot\") still works on any phone. Use the " +
                "Advanced cloud fallback below if you want Q&A anyway on this device.",
                color = DjiRed, fontSize = 10.sp, lineHeight = 13.sp,
            )
        }
    }
}

// Fixed physical-position order the guided flow steps through, the RC240 has exactly these
// seven learnable controls (see the Controller Buttons card's own doc text), so unlike the
// slot-action mapping (deliberately learned, never hardcoded), the SET of physical zones and
// their layout on the actual hardware is fixed and known, only which raw byte fires for each
// one is unconfirmed, which is what the "press it now" learning step itself resolves.
private val RC_ZONE_LABELS = listOf(
    "Left shoulder trigger (C1)", "Right shoulder trigger (C2)",
    "5-way dial, Up", "5-way dial, Down", "5-way dial, Left", "5-way dial, Right", "5-way dial, Press",
)

/** A schematic (not photorealistic) top-down RC240 diagram, RetroArch-controller-mapping style:
 *  the zone currently being learned pulses green, already-learned zones sit dim gold, everything
 *  else stays neutral. Layout corrected against real reference photos of the physical unit
 *  (2026-07-03, including a clear well-lit close-up): C1/C2 are shoulder buttons on the TOP/BACK
 *  edge near the antenna mounts, not the front face, drawn here as a separate top strip, like a
 *  3/4-angle view. The Home (RTH) and Pause buttons are fixed-function, not learnable, but are
 *  drawn dimly for orientation since they're the most visible landmarks on the real unit, Pause
 *  sits right at the screen's bottom-left corner, Home above that, so the screen itself is drawn
 *  too (it was missing before, which made "next to the screen" impossible to see). The "5-way
 *  dial" is one small knurled wheel tucked at the screen's bottom-right corner next to the right
 *  stick, not a spread-out d-pad, so its five zones are drawn tight together instead of a
 *  full-size cross. */
@Composable
private fun RcControllerDiagram(activeZone: Int?, learnedZones: Set<Int>, modifier: Modifier = Modifier) {
    Column(modifier.width(300.dp)) {
        // Top/back edge strip, where the antennas mount and C1/C2 actually live.
        Box(
            Modifier.fillMaxWidth().height(46.dp)
                .background(Color(0xFF12161B), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        ) {
            AntennaStub(Modifier.align(Alignment.TopStart).padding(start = 36.dp))
            AntennaStub(Modifier.align(Alignment.TopEnd).padding(end = 36.dp))
            ZoneChip("C1", 0, activeZone, learnedZones, Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 6.dp))
            ZoneChip("C2", 1, activeZone, learnedZones, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 6.dp))
        }
        // Front face, screen, sticks, and the fixed Home/Pause buttons for orientation only.
        Box(
            Modifier.fillMaxWidth().height(150.dp)
                .background(Color(0xFF1A1F26), RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
        ) {
            // The screen, previously undrawn, which made "buttons next to the screen" have
            // nothing to anchor to.
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 10.dp).size(width = 140.dp, height = 80.dp)
                    .background(Color.Black, RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
            )
            // Home (RTH) above the screen's top-left corner; Pause right at its bottom-left
            // corner, exact placement confirmed against a clear reference photo.
            FixedButtonDot("H", Modifier.align(Alignment.TopStart).padding(start = 112.dp, top = 8.dp))
            FixedButtonDot("⏸", Modifier.align(Alignment.TopStart).padding(start = 118.dp, top = 82.dp))
            ControllerKnob(Modifier.align(Alignment.BottomStart).padding(start = 34.dp, bottom = 18.dp))
            ControllerKnob(Modifier.align(Alignment.BottomEnd).padding(end = 90.dp, bottom = 10.dp))

            // Compact 5-way dial, tight cluster at the screen's bottom-right corner.
            Box(Modifier.align(Alignment.TopEnd).padding(end = 15.dp, top = 88.dp).size(48.dp)) {
                ZoneChip("▲", 2, activeZone, learnedZones, Modifier.align(Alignment.TopCenter), size = 18.dp)
                ZoneChip("▼", 3, activeZone, learnedZones, Modifier.align(Alignment.BottomCenter), size = 18.dp)
                ZoneChip("◀", 4, activeZone, learnedZones, Modifier.align(Alignment.CenterStart), size = 18.dp)
                ZoneChip("▶", 5, activeZone, learnedZones, Modifier.align(Alignment.CenterEnd), size = 18.dp)
                ZoneChip("●", 6, activeZone, learnedZones, Modifier.align(Alignment.Center), size = 18.dp)
            }
        }
    }
}

@Composable
private fun ControllerKnob(modifier: Modifier) {
    Box(
        modifier.size(40.dp).background(Color(0xFF2A313B), CircleShape)
            .border(1.dp, Color(0x22FFFFFF), CircleShape),
    )
}

@Composable
private fun AntennaStub(modifier: Modifier) {
    Box(modifier.width(10.dp).height(20.dp).background(Color(0xFF3A4048), RoundedCornerShape(3.dp)))
}

/** Fixed-function button (Home/Pause), shown dim and non-interactive, purely as a landmark so
 *  the diagram reads as "this is the real unit," not a control the guided flow will ever ask
 *  the pilot to press. */
@Composable
private fun FixedButtonDot(label: String, modifier: Modifier) {
    Box(
        modifier.size(22.dp).background(Color(0xFF2A313B), CircleShape)
            .border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = TextSec, fontSize = 9.sp) }
}

@Composable
private fun ZoneChip(
    label: String, zoneIndex: Int, activeZone: Int?, learnedZones: Set<Int>, modifier: Modifier,
    size: androidx.compose.ui.unit.Dp = 26.dp,
) {
    val isActive = activeZone == zoneIndex
    val isLearned = zoneIndex in learnedZones
    val color = when { isActive -> DjiGreen; isLearned -> Gold; else -> TextSec }
    val scale by animateFloatAsState(if (isActive) 1.3f else 1f, label = "zoneScale")
    Box(
        modifier.size(size).graphicsLayer(scaleX = scale, scaleY = scale)
            .background(color.copy(alpha = if (isActive) 0.35f else 0.16f), CircleShape)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = if (size < 22.dp) 8.sp else 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ButtonSlotRow(
    slotIndex: Int, slot: RcButtonSlot, isLearning: Boolean,
    onLearn: () -> Unit, onCancelLearn: () -> Unit, onActionChange: (RcButtonAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glass(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), baseAlpha = 0.2f)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Button $slotIndex", color = TextPri, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text(slot.action.label, color = Gold, fontSize = 12.sp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    RcButtonAction.entries.forEach { action ->
                        DropdownMenuItem(text = { Text(action.label) }, onClick = { onActionChange(action); menuOpen = false })
                    }
                }
            }
            if (isLearning) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Press the button now…", color = DjiGreen, fontSize = 10.sp)
                    TextButton(onClick = onCancelLearn) { Text("Cancel", color = TextSec, fontSize = 10.sp) }
                }
            } else {
                Button(
                    onClick = onLearn,
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text(if (slot.signature == null) "Learn" else "Re-learn", color = Gold, fontSize = 11.sp) }
            }
        }
        Text(
            if (slot.signature == null) "Not learned yet" else "Learned: cmd 0x%02x mask 0x%02x".format(slot.signature.cmdId, slot.signature.mask),
            color = TextSec, fontSize = 9.sp,
        )
    }
}
