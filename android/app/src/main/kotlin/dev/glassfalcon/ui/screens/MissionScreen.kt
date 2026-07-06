// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*
import dev.glassfalcon.ui.components.glass
import androidx.compose.ui.graphics.RectangleShape
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MissionScreen(vm: FlightViewModel) {
    val app          by vm.app.collectAsState()
    val missionStatus by vm.mission.status.collectAsState()
    val pendingPlan  by vm.pendingPlan.collectAsState()
    val drone        by vm.drone.collectAsState()
    val droneLinked  by vm.droneLinked.collectAsState()

    Row(Modifier.fillMaxSize()) {
        // ── Left panel: planning + Claude ────────────────────────────────────
        Column(
            modifier = Modifier
                .width(340.dp)
                .fillMaxHeight()
                .background(DarkBg)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── API key ───────────────────────────────────────────────────────
            var apiKeyInput by remember { mutableStateOf(app.claudeApiKey) }
            if (app.claudeApiKey.isBlank()) {
                Card(modifier = Modifier.glass(), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Claude API Key", color = TextSec, fontSize = 11.sp)
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("sk-ant-…", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { vm.setClaudeKey(apiKeyInput) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                        ) { Text("Save Key", color = Gold, fontSize = 12.sp) }
                    }
                }
            }

            // ── Claude mission planning ───────────────────────────────────────
            ClaudeMissionPanel(vm)

            // ── Manual survey builder ─────────────────────────────────────────
            ManualSurveyPanel(vm, drone)

            // ── Pending plan confirmation ─────────────────────────────────────
            if (pendingPlan != null) {
                PendingPlanCard(pendingPlan!!, vm, droneLinked, drone.hasGpsFix)
            }
        }

        // ── Right panel: map + mission status ─────────────────────────────────
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Mission status bar
            MissionStatusBar(missionStatus)
            // Map
            Box(Modifier.weight(1f).fillMaxWidth().background(DarkBg)) {
                MissionMap(drone = drone, missionStatus = missionStatus)
            }
            // Mission log
            MissionLog(missionStatus.log)
        }
    }
}

// ── Claude chat panel ─────────────────────────────────────────────────────────

@Composable
private fun ClaudeMissionPanel(vm: FlightViewModel) {
    val chatHistory  by vm.chatHistory.collectAsState()
    val aiThinking   by vm.aiThinking.collectAsState()
    val aiResponse   by vm.aiResponse.collectAsState()
    var chatInput    by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("Claude Flight AI", color = Gold, fontSize = 12.sp)
                if (aiThinking) CircularProgressIndicator(
                    modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Gold)
            }

            // Chat messages
            val listState = rememberLazyListState()
            LaunchedEffect(chatHistory.size) {
                if (chatHistory.isNotEmpty()) listState.animateScrollToItem(chatHistory.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(chatHistory) { (role, content) ->
                    val isUser = role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            color = if (isUser) Gold.copy(alpha = 0.2f) else Border,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.widthIn(max = 260.dp),
                        ) {
                            Text(
                                content.take(500),
                                color = TextPri, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (aiThinking && aiResponse.isNotEmpty()) {
                    item {
                        Surface(color = Border, shape = MaterialTheme.shapes.small) {
                            Text(aiResponse, color = TextSec, fontSize = 11.sp,
                                modifier = Modifier.padding(8.dp).widthIn(max = 260.dp))
                        }
                    }
                }
            }

            // Input row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    placeholder = { Text("Map the north field at 80m…", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            if (chatInput.isNotBlank()) {
                                vm.askClaude(chatInput)
                                chatInput = ""
                            }
                        },
                        enabled = !aiThinking,
                        colors = ButtonDefaults.buttonColors(containerColor = Border),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Chat", fontSize = 11.sp) }
                    Button(
                        onClick = {
                            if (chatInput.isNotBlank()) {
                                vm.planMissionWithClaude(chatInput)
                                chatInput = ""
                            }
                        },
                        enabled = !aiThinking,
                        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.25f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("Plan", color = Gold, fontSize = 11.sp) }
                }
            }
        }
    }
}

/** Grid/Orbit are the original survey builder modes; Dronie/Helix/Rocket/Boomerang are the
 *  QuickShot-style patterns (see MissionPlanner's doc comment on why no DUML opcode is needed
 *  for these, they're flown via the same virtual-stick mission engine, just different shapes).
 *  ActiveTrack/TapFly aren't here: both need live subject-tracking vision, out of scope. */
private enum class MissionMode(val label: String) {
    GRID("Grid"), ORBIT("Orbit"), DRONIE("Dronie"), HELIX("Helix"), ROCKET("Rocket"), BOOMERANG("Boomerang"),
}

// ── Manual survey builder ─────────────────────────────────────────────────────

@Composable
private fun ManualSurveyPanel(vm: FlightViewModel, drone: DroneState) {
    val droneLinked by vm.droneLinked.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Manual Survey Builder", color = TextSec, fontSize = 12.sp)
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "▲" else "▼", color = TextSec, fontSize = 12.sp)
                }
            }
            if (expanded) {
                var nwLat by remember { mutableStateOf("${drone.lat + 0.001}") }
                var nwLon by remember { mutableStateOf("${drone.lon - 0.001}") }
                var seLat by remember { mutableStateOf("${drone.lat - 0.001}") }
                var seLon by remember { mutableStateOf("${drone.lon + 0.001}") }
                var altStr by remember { mutableStateOf("80") }
                var frontOv by remember { mutableStateOf("75") }
                var sideOv  by remember { mutableStateOf("70") }
                var orbitR by remember { mutableStateOf("30") }
                var mode by remember { mutableStateOf(MissionMode.GRID) }
                // Shared by every QuickShot-style pattern below (Dronie/Helix/Rocket/Boomerang)
                //, they're all just different flight paths around/away from the same subject.
                var awayBearing by remember { mutableStateOf("${drone.yaw.toInt()}") }
                var distanceM by remember { mutableStateOf("40") }
                var climbM by remember { mutableStateOf("20") }
                var altEndStr by remember { mutableStateOf("40") }
                var turnsStr by remember { mutableStateOf("1.0") }

                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MissionMode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m },
                            label = { Text(m.label, fontSize = 11.sp) })
                    }
                }
                SurveyField("Alt (m)", altStr) { altStr = it }
                when (mode) {
                    MissionMode.GRID -> {
                        SurveyField("NW Lat", nwLat)  { nwLat = it }
                        SurveyField("NW Lon", nwLon)  { nwLon = it }
                        SurveyField("SE Lat", seLat)  { seLat = it }
                        SurveyField("SE Lon", seLon)  { seLon = it }
                        SurveyField("Front overlap %", frontOv) { frontOv = it }
                        SurveyField("Side overlap %",  sideOv)  { sideOv  = it }
                    }
                    MissionMode.ORBIT -> {
                        SurveyField("Orbit radius (m)", orbitR) { orbitR = it }
                        Text("Center: ${drone.lat.fmt()} / ${drone.lon.fmt()}", color = TextSec, fontSize = 10.sp)
                    }
                    MissionMode.DRONIE -> {
                        SurveyField("Away bearing (°)", awayBearing) { awayBearing = it }
                        SurveyField("Distance (m)", distanceM) { distanceM = it }
                        SurveyField("Climb (m)", climbM) { climbM = it }
                        Text("Subject: ${drone.lat.fmt()} / ${drone.lon.fmt()}, current heading defaults the away bearing", color = TextSec, fontSize = 10.sp)
                    }
                    MissionMode.HELIX -> {
                        SurveyField("Radius (m)", orbitR) { orbitR = it }
                        SurveyField("End alt (m)", altEndStr) { altEndStr = it }
                        SurveyField("Turns", turnsStr) { turnsStr = it }
                        Text("Center: ${drone.lat.fmt()} / ${drone.lon.fmt()}, start alt is the Alt field above", color = TextSec, fontSize = 10.sp)
                    }
                    MissionMode.ROCKET -> {
                        SurveyField("Climb (m)", climbM) { climbM = it }
                        Text("Subject (straight down below the shot): ${drone.lat.fmt()} / ${drone.lon.fmt()}", color = TextSec, fontSize = 10.sp)
                    }
                    MissionMode.BOOMERANG -> {
                        SurveyField("Loop radius (m)", orbitR) { orbitR = it }
                        Text("Center: ${drone.lat.fmt()} / ${drone.lon.fmt()}", color = TextSec, fontSize = 10.sp)
                    }
                }
                Button(
                    onClick = {
                        val alt = altStr.toFloatOrNull() ?: 80f
                        val plan = when (mode) {
                            MissionMode.GRID -> vm.planGridSurvey(
                                corners = listOf(
                                    (nwLat.toDoubleOrNull() ?: drone.lat + 0.001) to (nwLon.toDoubleOrNull() ?: drone.lon - 0.001),
                                    (seLat.toDoubleOrNull() ?: drone.lat - 0.001) to (seLon.toDoubleOrNull() ?: drone.lon + 0.001),
                                ),
                                altM = alt,
                                frontOverlap = frontOv.toFloatOrNull() ?: 75f,
                                sideOverlap  = sideOv.toFloatOrNull()  ?: 70f,
                            )
                            MissionMode.ORBIT -> vm.planOrbit(drone.lat, drone.lon, orbitR.toFloatOrNull() ?: 30f, alt)
                            MissionMode.DRONIE -> vm.planDronie(
                                drone.lat, drone.lon, alt,
                                awayBearing.toFloatOrNull() ?: drone.yaw,
                                distanceM.toFloatOrNull() ?: 40f, climbM.toFloatOrNull() ?: 20f,
                            )
                            MissionMode.HELIX -> vm.planHelix(
                                drone.lat, drone.lon, orbitR.toFloatOrNull() ?: 30f,
                                alt, altEndStr.toFloatOrNull() ?: (alt + 40f), turnsStr.toFloatOrNull() ?: 1f,
                            )
                            MissionMode.ROCKET -> vm.planRocket(drone.lat, drone.lon, alt, climbM.toFloatOrNull() ?: 40f)
                            MissionMode.BOOMERANG -> vm.planBoomerang(drone.lat, drone.lon, orbitR.toFloatOrNull() ?: 30f, alt)
                        }
                        vm.startMission(plan)
                    },
                    // An autonomous mission drives motors and climb, never let it launch
                    // without a live drone link AND a real GPS fix. Every mode here defaults
                    // its coordinates to drone.lat/lon, which are 0,0 (null island) with no fix.
                    enabled = droneLinked && drone.hasGpsFix,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("Build & Execute", color = Gold, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SurveyField(label: String, value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSec, fontSize = 10.sp, modifier = Modifier.width(100.dp))
        OutlinedTextField(value = value, onValueChange = onChange,
            singleLine = true, modifier = Modifier.weight(1f))
    }
}

// ── Pending plan confirmation ─────────────────────────────────────────────────

@Composable
private fun PendingPlanCard(
    plan: MissionPlan, vm: FlightViewModel, droneLinked: Boolean, hasGpsFix: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Gold.copy(alpha = 0.08f)),
         border = CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Claude Mission Plan", color = Gold, fontSize = 12.sp)
            Text(plan.name, color = TextPri, fontSize = 13.sp)
            Text("${plan.waypoints.size} waypoints  ·  ${plan.estimatedPhotos} photos  ·  " +
                 "${"%.1f".format(plan.estimatedFlightMinutes)} min  ·  " +
                 "${plan.gsdCm} cm/px GSD", color = TextSec, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.acceptPendingPlan() },
                    // Same live-link + GPS-fix gate as the manual survey builder, an
                    // autonomous mission drives motors and climb and must never launch
                    // over a dead link or without a real position fix.
                    enabled = droneLinked && hasGpsFix,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Green.copy(alpha = 0.25f)),
                ) { Text("Execute", color = Green, fontSize = 12.sp) }
                OutlinedButton(onClick = { vm.discardPendingPlan() }, modifier = Modifier.weight(1f)) {
                    Text("Discard", fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Mission status bar ────────────────────────────────────────────────────────

@Composable
private fun MissionStatusBar(status: MissionStatus) {
    val stateColor = when (status.state) {
        MissionState.IDLE, MissionState.COMPLETE -> TextSec
        MissionState.ABORTED -> Red
        MissionState.FLYING, MissionState.TAKEOFF -> Green
        MissionState.CAPTURING -> Gold
        else -> TextPri
    }
    Row(
        modifier = Modifier.fillMaxWidth().glass(shape = RectangleShape).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(status.state.name, color = stateColor, fontSize = 12.sp)
        if (status.totalWaypoints > 0) {
            LinearProgressIndicator(
                progress = { status.currentWpIdx.toFloat() / status.totalWaypoints },
                modifier = Modifier.weight(1f).height(6.dp),
                color = Gold,
            )
            Text("${status.currentWpIdx}/${status.totalWaypoints}", color = TextSec, fontSize = 11.sp)
            Text("📷 ${status.photosTaken}", color = TextSec, fontSize = 11.sp)
            Text("ETA ${status.etaSeconds}s", color = TextSec, fontSize = 11.sp)
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
    HorizontalDivider(color = Border)
}

// ── Mission log ───────────────────────────────────────────────────────────────

@Composable
private fun MissionLog(log: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        colors = CardDefaults.cardColors(containerColor = DarkBg),
    ) {
        val state = rememberLazyListState()
        LaunchedEffect(log.size) { if (log.isNotEmpty()) state.animateScrollToItem(log.size - 1) }
        LazyColumn(state = state, modifier = Modifier.padding(8.dp)) {
            items(log) { msg ->
                Text(msg, color = TextSec, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

// ── Mission map ───────────────────────────────────────────────────────────────

@Composable
private fun MissionMap(drone: DroneState, missionStatus: MissionStatus) {
    if (!drone.hasGpsFix) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Waiting for GPS…", color = TextSec)
        }
        return
    }
    val position = LatLng(drone.lat, drone.lon)
    val ctx = LocalContext.current

    // Own the MapView and drive its full lifecycle so it isn't leaked (native MapView +
    // GL context) each time the Mission tab leaves composition, see the same fix in
    // TelemetryScreen.DroneMap.
    val mapView = remember {
        MapView(ctx).apply {
            onCreate(Bundle()); onStart(); onResume()
            getMapAsync { map ->
                map.setStyle("https://demotiles.maplibre.org/style.json")
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 17.0))
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    AndroidView(
        factory = { mapView },
        update = { mv ->
            mv.getMapAsync { map ->
                map.animateCamera(CameraUpdateFactory.newLatLng(position))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun Double.fmt() = "%.5f".format(this)
