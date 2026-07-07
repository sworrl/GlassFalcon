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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// File-private accent colours, matching the per-screen convention used across the app (these two
// aren't in the shared Theme.kt palette).
private val DjiGreen = Color(0xFF00CC44)
private val DjiRed   = Color(0xFFFF3333)

/**
 * Root-gated kprobe capture UI. Mirrors the ADB control surface ([KprobeReceiver]) for on-device
 * driving: arm a wide/max probe, connect DJI GO4, dump the trace, tear down. Captures land in
 * `Android/data/<pkg>/files/kprobe/` for `adb pull` + `captures/parse_wide.py`. On a non-rooted
 * device every action returns a "NO ROOT" line and nothing runs.
 */
@Composable
fun DevToolsScreen(@Suppress("UNUSED_PARAMETER") vm: FlightViewModel) {
    val scope = rememberCoroutineScope()
    var rooted by remember { mutableStateOf(RootShell.isRooted()) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            output = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "error" } }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Dev Tools — kprobe capture", color = Gold, fontSize = 18.sp)
        Text(
            if (rooted) "root: DETECTED — capture available" else "root: NOT DETECTED — capture disabled",
            color = if (rooted) DjiGreen else DjiRed, fontSize = 13.sp,
        )
        OutlinedButton(onClick = {
            rooted = RootShell.isRooted(recheck = true)
            run { KprobeCapture.status() }
        }) { Text("Re-check root / show status") }

        HorizontalDivider(color = Border)
        Text("Arm a probe (bigger buffer than the old 32 B/frame):", color = TextSec, fontSize = 12.sp)
        KprobeCapture.PROFILES.forEach { p ->
            OutlinedButton(
                enabled = rooted && !busy,
                onClick = { run { KprobeCapture.arm(p.name) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Arm ${p.name} — ${p.note}", fontSize = 11.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = rooted && !busy, onClick = { run { KprobeCapture.dump("cap") } }) { Text("Dump trace") }
            OutlinedButton(enabled = rooted && !busy, onClick = { run { KprobeCapture.teardown() } }) { Text("Teardown") }
        }

        HorizontalDivider(color = Border)
        Text("Same, over ADB (debug pkg dev.glassfalcon.debug):", color = TextSec, fontSize = 12.sp)
        Text(
            "am broadcast -a dev.glassfalcon.KPROBE \\\n" +
                "  -n dev.glassfalcon.debug/dev.glassfalcon.core.KprobeReceiver \\\n" +
                "  --es action arm --es profile tx-max",
            color = TextSec, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        )

        HorizontalDivider(color = Border)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            output.ifEmpty { "—" },
            color = TextPri, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
    }
}
