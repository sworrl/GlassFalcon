// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.os.Bundle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.ui.components.glass
import androidx.compose.ui.graphics.RectangleShape
import dev.glassfalcon.ui.*

private val DjiGreen = Color(0xFF00CC44)
private val DjiAmber = Color(0xFFFFAA00)
private val DjiCyan = Color(0xFF33CCFF)

@Composable
fun TelemetryScreen(vm: FlightViewModel) {
    val drone by vm.drone.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // ── Quick stats bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glass(shape = RectangleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatChip("ALT",  "${"%.1f".format(drone.altRel)} m",         TextPri)
            StatChip("SPD",  "${"%.1f".format(drone.speed)} m/s",        TextPri)
            StatChip("DIST", "${"%.0f".format(drone.homeDist)} m",       TextPri)
            StatChip("BATT", "${drone.battPct}%",
                when { drone.battPct > 50 -> Green; drone.battPct > 20 -> Orange; else -> Red })
            StatChip("GPS",  if (drone.hasGpsFix) "Fix" else "No Fix",
                if (drone.hasGpsFix) Green else Red)
        }

        // ── Map ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(2f)
                .background(DarkBg),
        ) {
            if (drone.hasGpsFix) {
                DroneMap(lat = drone.lat, lon = drone.lon, heading = drone.yaw)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Waiting for GPS fix…", color = TextSec)
                }
            }
        }

        // ── Attitude + velocity ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Live telemetry charts, the recent flight track (altitude / speed / battery) drawn
            // as native Compose-Canvas line charts, rolling as new samples arrive. Same component
            // the after-flight report uses; here it plots the last ~120 samples live.
            val track by vm.track.collectAsState()
            val recent = remember(track) { track.takeLast(120) }
            if (recent.size >= 2) {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Live telemetry", color = TextSec, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            dev.glassfalcon.ui.components.TelemetryChart(
                                recent.map { it.alt }, DjiCyan, "ALTITUDE", " m",
                                Modifier.weight(1f).height(80.dp), fmt = { it.toInt().toString() },
                            )
                            dev.glassfalcon.ui.components.TelemetryChart(
                                recent.map { it.speed }, DjiGreen, "SPEED", " m/s",
                                Modifier.weight(1f).height(80.dp), fmt = { "%.1f".format(it) },
                            )
                            val batt = recent.filter { it.battPct in 0..100 }.map { it.battPct.toFloat() }
                            if (batt.isNotEmpty()) dev.glassfalcon.ui.components.TelemetryChart(
                                batt, DjiAmber, "BATTERY", "%",
                                Modifier.weight(1f).height(80.dp), fmt = { it.toInt().toString() }, yFloorZero = false,
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Attitude
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Attitude", color = TextSec, fontSize = 11.sp)
                        TelRow("Roll",  "${drone.roll}°")
                        TelRow("Pitch", "${drone.pitch}°")
                        TelRow("Yaw",   "${drone.yaw}°")
                    }
                }
                // Velocity
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Velocity m/s", color = TextSec, fontSize = 11.sp)
                        TelRow("N", "${"%.2f".format(drone.vx)}")
                        TelRow("E", "${"%.2f".format(drone.vy)}")
                        TelRow("D", "${"%.2f".format(drone.vz)}")
                    }
                }
            }

            // GPS coords
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Lat", color = TextSec, fontSize = 11.sp)
                        Text("${"%.7f".format(drone.lat)}", color = TextPri, fontSize = 13.sp)
                    }
                    Column {
                        Text("Lon", color = TextSec, fontSize = 11.sp)
                        Text("${"%.7f".format(drone.lon)}", color = TextPri, fontSize = 13.sp)
                    }
                    Column {
                        Text("Battery mV", color = TextSec, fontSize = 11.sp)
                        Text("${drone.battMv} mV", color = TextPri, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSec, fontSize = 9.sp)
        Text(value, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun TelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSec, fontSize = 12.sp)
        Text(value, color = TextPri, fontSize = 12.sp)
    }
}

@Composable
private fun DroneMap(lat: Double, lon: Double, heading: Float) {
    val position = LatLng(lat, lon)
    val ctx = LocalContext.current

    // Own the MapView across recompositions and drive its full lifecycle. Previously it
    // was built fresh in the factory with only onCreate() and never started or destroyed,
    // so every time this tab left composition it leaked a native MapView + GL context, 
    // over a long flight of tab-switching that leaks until the app is OOM-killed.
    val mapView = remember {
        MapView(ctx).apply {
            onCreate(Bundle()); onStart(); onResume()
            getMapAsync { map ->
                map.setStyle("https://demotiles.maplibre.org/style.json")
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 16.0))
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    // Map always follows drone, overlay draws the marker in Compose
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            update = { mv ->
                mv.getMapAsync { map ->
                    map.animateCamera(CameraUpdateFactory.newLatLng(position))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Drone symbol at centre (map is always centred on drone)
        Canvas(
            modifier = Modifier.size(40.dp).align(Alignment.Center)
        ) {
            rotate(degrees = heading) {
                val cx = size.width / 2
                val cy = size.height / 2
                // Fuselage
                drawLine(Gold, Offset(cx, cy - size.height * 0.45f),
                    Offset(cx, cy + size.height * 0.35f), strokeWidth = 3f)
                // Wings
                drawLine(Gold, Offset(cx - size.width * 0.45f, cy),
                    Offset(cx + size.width * 0.45f, cy), strokeWidth = 3f)
                // Tail
                drawLine(Gold, Offset(cx - size.width * 0.2f, cy + size.height * 0.3f),
                    Offset(cx + size.width * 0.2f, cy + size.height * 0.3f), strokeWidth = 2f)
                drawCircle(Gold, radius = 4f, center = Offset(cx, cy), style = Stroke(2f))
            }
        }
    }
}
