// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.ui.*
import java.io.File

@Composable
fun OffloadScreen(vm: FlightViewModel) {
    val status by vm.offload.status.collectAsState()
    val app    by vm.app.collectAsState()

    val defaultDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        .let { File(it, "GlassFalcon") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Status ────────────────────────────────────────────────────────────
        if (status.running) {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transfer in progress", color = Gold, fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = {
                            if (status.totalFiles > 0)
                                status.transferredFiles.toFloat() / status.totalFiles else 0f
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = Gold,
                    )
                    Text("${status.transferredFiles}/${status.totalFiles} files  " +
                         "· ${status.currentFile}",
                        color = TextSec, fontSize = 11.sp)
                    val mb = status.bytesTransferred / 1_000_000.0
                    val totalMb = status.totalBytes / 1_000_000.0
                    Text("${"%.1f".format(mb)} / ${"%.1f".format(totalMb)} MB",
                        color = TextSec, fontSize = 11.sp)
                }
            }
        }

        // ── WiFi offload ──────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Drone WiFi Offload", color = TextSec, fontSize = 12.sp)
                Text(
                    "Connect phone to drone WiFi (192.168.2.1), " +
                    "no USB required. Downloads JPEGs from DCIM.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                var droneIp by remember { mutableStateOf("192.168.2.1") }
                OutlinedTextField(
                    value = droneIp, onValueChange = { droneIp = it },
                    label = { Text("Drone IP", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.startWifiOffload(defaultDir, droneIp) },
                    enabled = !status.running,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                ) { Text("Start WiFi Offload → ${defaultDir.name}", color = Gold) }
            }
        }

        // ── SD card FTP probe (experimental) ─────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SD Card FTP Probe (experimental)", color = TextSec, fontSize = 12.sp)
                Text(
                    "Older DJI aircraft expose an FTP server for direct SD card access, " +
                    "confirmed working on Mavic Pro/Spark/Phantom 4/Inspire 2, but NOT " +
                    "confirmed on this aircraft (Mavic 2 Pro). This just tests whether the " +
                    "port answers at all, a real media gallery only gets built once this " +
                    "actually says yes.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                var ftpHost by remember { mutableStateOf("192.168.42.2") }
                OutlinedTextField(
                    value = ftpHost, onValueChange = { ftpHost = it },
                    label = { Text("Host (192.168.42.2 USB, 192.168.2.1 WiFi)", fontSize = 10.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.probeSdCardFtp(ftpHost) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Border),
                ) { Text("Test FTP access", color = TextPri) }
            }
        }

        // ── ADB offload ───────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ADB Offload (requires Assistant Unlock)", color = TextSec, fontSize = 12.sp)
                Text(
                    "Send ADB unlock from Device tab first. Pulls " +
                    "/sdcard/DCIM/DJI/ via adb pull.",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                Button(
                    onClick = { vm.startAdbOffload(defaultDir) },
                    enabled = !status.running,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Border),
                ) { Text("ADB Pull → ${defaultDir.name}", color = TextPri) }
            }
        }

        // ── ODM photogrammetry ────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3D Processing, NodeODM", color = TextSec, fontSize = 12.sp)
                Text(
                    "Submit offloaded images to a NodeODM server for photogrammetry " +
                    "(DSM, DTM, orthophoto, 3D mesh). Run nodeodm on PC: " +
                    "docker run -p 3000:3000 opendronemap/nodeodm",
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp,
                )
                var odmUrl by remember { mutableStateOf("http://192.168.1.1:3000") }
                OutlinedTextField(
                    value = odmUrl, onValueChange = { odmUrl = it },
                    label = { Text("NodeODM URL", fontSize = 11.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (status.odmTaskId != null) {
                    Surface(color = Border.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small) {
                        Row(Modifier.padding(8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Task: ${status.odmTaskId}", color = TextSec, fontSize = 10.sp)
                            Text(status.odmStatus, color = Gold, fontSize = 10.sp)
                        }
                    }
                }
                Button(
                    onClick = { vm.submitToODM(defaultDir, odmUrl) },
                    enabled = !status.running && defaultDir.exists(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Green.copy(alpha = 0.15f)),
                ) { Text("Submit ${defaultDir.name} to ODM", color = Green, fontSize = 12.sp) }
            }
        }

        // ── Log ───────────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp),
            colors = CardDefaults.cardColors(containerColor = DarkBg),
        ) {
            Box(Modifier.padding(8.dp)) {
                Text(status.log.takeLast(30).joinToString("\n"),
                    color = TextSec, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}
