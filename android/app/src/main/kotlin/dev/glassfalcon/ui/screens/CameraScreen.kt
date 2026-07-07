// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.ui.components.glass
import dev.glassfalcon.ui.*

@Composable
fun CameraScreen(vm: FlightViewModel) {
    val app by vm.app.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // ── Video player ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(DarkBg),
            contentAlignment = Alignment.Center,
        ) {
            if (app.videoUrl.isNotBlank()) {
                VideoPlayer(url = app.videoUrl, modifier = Modifier.fillMaxSize())
            } else {
                Text("No stream, connect and set relay URL", color = TextSec)
            }
        }

        // ── Controls ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Show/hide the capture button on the flight HUD entirely, some pilots would
            // rather fly a clean video-only view and use this Settings screen for capture.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Show capture button on HUD", color = TextPri, fontSize = 13.sp)
                Switch(
                    checked = app.captureButtonEnabled,
                    onCheckedChange = { vm.setCaptureButtonEnabled(it) },
                )
            }

            // Relay URL field
            var relayInput by remember { mutableStateOf(app.relayUrl) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = relayInput,
                    onValueChange = { relayInput = it },
                    label = { Text("Relay / RTSP URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.setRelayUrl(relayInput) },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("Set", color = Gold) }
            }

            // Photo / Record row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.capturePhoto() },
                    enabled = app.connected,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Panel),
                ) { Text("📷  Photo", color = TextPri) }

                var recording by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        if (recording) { vm.stopRecord(); recording = false }
                        else { vm.startRecord(); recording = true }
                    },
                    enabled = app.connected,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording) Red.copy(alpha = 0.25f) else Panel),
                ) {
                    Text(
                        if (recording) "⏹  Stop Rec" else "⏺  Record",
                        color = if (recording) Red else TextPri,
                    )
                }
            }

            // Mode
            CamDropdown(
                label = "Mode",
                options = listOf("Photo", "Video", "Timelapse", "Slow Mo", "HyperLapse"),
                enabled = app.connected,
            ) { vm.setCameraMode(it) }

            // ISO
            CamDropdown(
                label = "ISO",
                options = listOf("Auto", "100", "200", "400", "800", "1600", "3200", "6400"),
                enabled = app.connected,
            ) { vm.setCameraIso(it) }

            // Shutter
            CamDropdown(
                label = "Shutter",
                options = listOf("Auto", "1/25", "1/50", "1/100", "1/200", "1/400", "1/800", "1/2000"),
                enabled = app.connected,
            ) { vm.setCameraShutter(it) }

            // EV, defaults to index 3 ("0.0"), the camera's own power-on default, not index 0
            // ("-3.0"), CamDropdown's own default assumes "first option = sensible default",
            // true for every other row here (Auto/Photo) but not this one.
            CamDropdown(
                label = "EV",
                options = listOf("-3.0","-2.0","-1.0","0.0","+1.0","+2.0","+3.0"),
                enabled = app.connected,
                initial = 3,
            ) { vm.setCameraEv(it) }

            // White Balance
            CamDropdown(
                label = "White Balance",
                options = listOf("Auto", "Sunny", "Cloudy", "Incandescent", "Fluorescent", "Custom"),
                enabled = app.connected,
            ) { vm.setCameraWb(it) }

            // Anti-flicker
            CamDropdown(
                label = "Anti-flicker",
                options = listOf("50 Hz", "60 Hz"),
                enabled = app.connected,
            ) { vm.setAntiFlicker(it) }

            // Exposure program (P/S/A/M). Indices follow DJI's standard exposure-mode enum
            // ordering; the camera echoes the accepted value in its state push.
            CamDropdown(
                label = "Exposure",
                options = listOf("Auto (P)", "Shutter", "Aperture", "Manual"),
                enabled = app.connected,
            ) { vm.setCameraExpMode(it) }

            // Color / look profile. Normal for a graded-in-camera look, D-Cinelike/D-Log for a
            // flat profile to grade later. Indices follow the DJI color-style enum order.
            CamDropdown(
                label = "Color profile",
                options = listOf("Normal", "D-Cinelike", "D-Log"),
                enabled = app.connected,
            ) { vm.setCameraColor(it) }
        }
    }
}

@Composable
fun VideoPlayer(url: String, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            playWhenReady = true
        }
    }
    LaunchedEffect(url) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                useController = false
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun CamDropdown(
    label: String,
    options: List<String>,
    enabled: Boolean,
    initial: Int = 0,
    onSelect: (Int) -> Unit,
) {
    var selected by remember { mutableIntStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSec, fontSize = 12.sp, modifier = Modifier.width(100.dp))
        Box {
            OutlinedButton(
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(options[selected], fontSize = 12.sp) }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.glass(),
            ) {
                options.forEachIndexed { i, opt ->
                    DropdownMenuItem(
                        text = { Text(opt, color = TextPri, fontSize = 12.sp) },
                        onClick = { selected = i; expanded = false; onSelect(i) },
                    )
                }
            }
        }
    }
}
