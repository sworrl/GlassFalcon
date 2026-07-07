// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.glassfalcon.core.*
import dev.glassfalcon.ui.*
import dev.glassfalcon.ui.components.glass
import dev.glassfalcon.ui.components.TelemetryChart
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.*

private val DjiGreen = Color(0xFF00CC44)
private val DjiAmber = Color(0xFFFFAA00)

/**
 * Flight Records, every flight GlassFalcon has seen takeoff-to-touchdown, with concrete
 * milestone badges (not generic praise) and a way to hand a record to another device without
 * any server in between. DJI GO 4 import is scoped honestly: this can find and list the
 * pilot's own flight-record files on-device with their consent, but does not decode DJI's
 * proprietary log format, that's a real reverse-engineering project on its own, not something
 * to fake with invented numbers.
 */
@Composable
fun FlightRecordsScreen(vm: FlightViewModel) {
    val records by vm.flightRecords.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<FlightRecord?>(null) }

    val djiFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.scanDjiFlightRecords(uri)
    }

    if (selected != null) {
        // Single-page, NO-scroll dashboard, FlightRecordDetail fills the screen and lays itself
        // out as a two-column dashboard (map + stat tiles | telemetry charts + badges). It sizes
        // everything with weights so it fits any screen without scrolling.
        Box(Modifier.fillMaxSize()) {
            FlightRecordDetail(selected!!, records, onBack = { selected = null }, onShare = { shareRecord(context, it) })
        }
        return
    }

    // Single LazyColumn for the whole screen (header content as item{} blocks, flight list as
    // items(records)), a plain Column wrapped in verticalScroll can't contain a LazyColumn
    // (unbounded height crashes it); this is the standard way to get one continuously
    // scrollable screen that also virtualizes a long flight list.
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // ── Career totals: a stat strip, not a plain list, the headline numbers a pilot
            // actually wants to see at a glance, each with its own accent tick above it. ──
            val totals = FlightBadges.totals(records)
            Row(
                Modifier.fillMaxWidth().glass(shape = RoundedCornerShape(12.dp), tint = DjiGreen, baseAlpha = 0.2f)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                totals.entries.take(4).forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(28.dp).height(2.dp).background(DjiGreen))
                        Spacer(Modifier.height(6.dp))
                        Text(value, color = DjiGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = TextSec, fontSize = 9.sp)
                    }
                }
            }
        }

        item {
            Box(Modifier.fillMaxWidth().glass(shape = RoundedCornerShape(12.dp), tint = Gold, baseAlpha = 0.18f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("IMPORT FROM DJI GO 4", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(
                        "Finds your own flight-record files on this device (with your consent via " +
                        "the system folder picker, usually DJI/dji.go.v4/FlightRecord). This lists " +
                        "and copies the raw files; it does not decode DJI's proprietary log format " +
                        "yet, so imported flights show as files, not as GlassFalcon-style stats.",
                        color = TextSec, fontSize = 10.sp, lineHeight = 13.sp,
                    )
                    Button(
                        onClick = { djiFolderLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
                    ) { Text("Pick DJI Flight Record Folder", color = Gold, fontSize = 12.sp) }
                    val djiFiles by vm.djiImportedFiles.collectAsState()
                    if (djiFiles.isNotEmpty()) {
                        Text("${djiFiles.size} file(s) found and copied into GlassFalcon's storage:",
                            color = TextSec, fontSize = 10.sp)
                        djiFiles.take(5).forEach { Text("• $it", color = TextSec, fontSize = 10.sp) }
                    }
                }
            }
        }

        item {
            Text("FLIGHTS (${records.size})", color = TextSec, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        if (records.isEmpty()) {
            item {
                Text("No flights recorded yet, one saves automatically on every takeoff→landing.",
                    color = TextSec, fontSize = 12.sp)
            }
        } else {
            items(records) { r ->
                val badges = FlightBadges.forRecord(r, records)
                Box(
                    Modifier.fillMaxWidth()
                        .glass(shape = RoundedCornerShape(10.dp), tint = if (badges.isNotEmpty()) DjiAmber else DjiGreen, baseAlpha = 0.2f)
                        .clickableCard { selected = r },
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(dateFmt(r.startedAtMs), color = TextPri, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("${r.durationSec / 60}m ${r.durationSec % 60}s", color = TextSec, fontSize = 12.sp)
                        }
                        Text(
                            "Max alt ${r.maxAltM.toInt()}m · Max speed %.1f m/s · %.0fm · %d%% batt used"
                                .format(r.maxSpeedMs, r.distanceM, r.battUsedPct),
                            color = TextSec, fontSize = 10.sp,
                        )
                        if (badges.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                badges.forEach { b -> BadgeChip(b.title) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeChip(title: String) {
    Box(
        Modifier.glass(shape = RoundedCornerShape(20.dp), tint = DjiAmber, baseAlpha = 0.25f)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text("🏅 $title", color = DjiAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
}

/** Static replay source for [FlightRecordDetail]'s map, feeds the record's own saved [TrackPoint]
 *  list through the exact same [MapTelemetrySource]/[FlightMap] the live flight HUD and
 *  Companion mode use, just with StateFlows that never change after construction instead of a
 *  live DUML link. "Drone" position is the flight's last recorded point (where it landed);
 *  "home" is its first (where it took off), this record has no separately-stored home point. */
private class RecordMapSource(record: FlightRecord) : MapTelemetrySource {
    private val last = record.track.lastOrNull()
    override val drone = MutableStateFlow(
        if (last != null) DroneState(lat = last.lat, lon = last.lon, vx = last.speed, vy = 0f, vz = 0f)
        else DroneState()
    )
    override val track = MutableStateFlow(record.track)
    override val homePoint = MutableStateFlow(record.track.firstOrNull()?.let { it.lat to it.lon })
    override val lastKnown = MutableStateFlow(last?.let { it.lat to it.lon })
    // Recorded flights don't carry AirSense traffic yet; empty flow satisfies the map source.
    override val airSense = MutableStateFlow(dev.glassfalcon.core.AirSenseState())
}

private val DjiCyan = Color(0xFF33CCFF)

@Composable
fun FlightRecordDetail(
    record: FlightRecord, allRecords: List<FlightRecord>, onBack: () -> Unit, onShare: (FlightRecord) -> Unit,
) {
    val badges = FlightBadges.forRecord(record, allRecords)
    val altSeries = remember(record.id) { record.track.map { it.alt } }
    val spdSeries = remember(record.id) { record.track.map { it.speed } }
    val battSeries = remember(record.id) { record.track.filter { it.battPct in 0..100 }.map { it.battPct.toFloat() } }
    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Thin header row: back + date on the left, share on the right.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back", color = Gold) }
                Text(dateFmt(record.startedAtMs), color = TextPri, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onShare(record) },
                colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.2f)),
            ) { Text("Share", color = Gold, fontSize = 12.sp) }
        }
        // Dashboard fills the rest: LEFT = track map + stat tiles, RIGHT = telemetry charts + badges.
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1.05f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, DjiGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                ) {
                    if (record.track.size >= 2) {
                        val mapSource = remember(record.id) { RecordMapSource(record) }
                        FlightMap(mapSource, Modifier.fillMaxSize(), compact = true, fitTrackOnLoad = true)
                    } else {
                        Box(Modifier.fillMaxSize().glass(shape = RoundedCornerShape(12.dp), tint = DjiGreen, baseAlpha = 0.2f), contentAlignment = Alignment.Center) {
                            Text("No GPS track recorded", color = TextSec, fontSize = 12.sp)
                        }
                    }
                }
                // Derived stats computed from the recorded track, average speed, peak climb rate,
                // and how far the aircraft ever got from its launch point (a more useful "range"
                // number than total path length, which the DISTANCE tile already covers).
                val avgSpeed = if (record.durationSec > 0) record.distanceM / record.durationSec else 0f
                val maxClimb = record.track.zipWithNext().maxOfOrNull { (a, b) ->
                    val dt = (b.tMs - a.tMs) / 1000f
                    if (dt > 0.1f) (b.alt - a.alt) / dt else 0f
                } ?: 0f
                val maxFromHome = record.track.firstOrNull()?.let { s ->
                    record.track.maxOfOrNull { p ->
                        val dLat = Math.toRadians(p.lat - s.lat)
                        val dLon = Math.toRadians(p.lon - s.lon) * kotlin.math.cos(Math.toRadians(s.lat))
                        6371000.0 * kotlin.math.hypot(dLat, dLon)
                    } ?: 0.0
                } ?: 0.0
                // Stat tiles, a 3×3 grid of the flight's key numbers.
                val tiles = listOf(
                    Triple("DURATION", "${record.durationSec / 60}:${(record.durationSec % 60).toString().padStart(2, '0')}", DjiGreen),
                    Triple("MAX ALT", "${record.maxAltM.toInt()} m", DjiCyan),
                    Triple("MAX SPD", "%.1f".format(record.maxSpeedMs), DjiGreen),
                    Triple("AVG SPD", "%.1f".format(avgSpeed), DjiGreen),
                    Triple("MAX CLIMB", "%+.1f".format(maxClimb), DjiCyan),
                    Triple("FARTHEST", "%.0f m".format(maxFromHome), DjiCyan),
                    Triple("PATH", "%.0f m".format(record.distanceM), DjiCyan),
                    Triple("BATT USED", "${record.battUsedPct}%", DjiAmber),
                    Triple("POINTS", record.track.size.toString(), DjiGreen),
                )
                tiles.chunked(3).forEach { rowTiles ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowTiles.forEach { StatTile(it.first, it.second, it.third, Modifier.weight(1f)) }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TelemetryChart(altSeries, DjiCyan, "ALTITUDE", " m", Modifier.fillMaxWidth().weight(1f), fmt = { it.toInt().toString() })
                TelemetryChart(spdSeries, DjiGreen, "SPEED", " m/s", Modifier.fillMaxWidth().weight(1f), fmt = { "%.1f".format(it) })
                if (battSeries.isNotEmpty()) {
                    TelemetryChart(battSeries, DjiAmber, "BATTERY", "%", Modifier.fillMaxWidth().weight(1f), fmt = { it.toInt().toString() }, yFloorZero = false)
                }
                // Badges + any warnings, compact, at the bottom of the right column.
                if (badges.isNotEmpty() || record.warningsHit.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().glass(shape = RoundedCornerShape(10.dp), tint = DjiAmber, baseAlpha = 0.2f)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            badges.forEach { b -> Text("🏅 ${b.title}", color = DjiAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            record.warningsHit.take(2).forEach { Text("⚠ $it", color = DjiAmber.copy(alpha = 0.85f), fontSize = 10.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Box(modifier.glass(shape = RoundedCornerShape(8.dp), tint = accent, baseAlpha = 0.18f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(label, color = TextSec, fontSize = 8.sp, letterSpacing = 0.5.sp)
            Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono)
        }
    }
}

/**
 * Full-screen "Flight Summary" shown automatically the instant a flight's record is saved (see
 * [dev.glassfalcon.core.FlightViewModel.justLandedRecord]), the same content as tapping into a
 * record from Settings → Flight Records, just surfaced immediately instead of the pilot having to
 * go dig it out afterward. Declared over the whole HUD (see its call site in GlassFalconRoot), so
 * if the phone's screen is being mirrored to a TV (see the Cast entry point in MainScreen), this
 * is what shows up there too, no separate "send to TV" step, it's just whatever's on the phone.
 */
@Composable
fun FlightSummaryOverlay(record: FlightRecord, allRecords: List<FlightRecord>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // No-scroll: the detail IS a full-screen dashboard now. Just frame it on the Navy backdrop.
    Box(Modifier.fillMaxSize().background(Navy)) {
        FlightRecordDetail(
            record = record, allRecords = allRecords,
            onBack = onDismiss, onShare = { shareRecord(context, it) },
        )
    }
}

private fun dateFmt(ms: Long) = dev.glassfalcon.core.Units.dateTime(ms)

/** Hands the record's JSON file to Android's native Share sheet, Quick Share/Nearby Share
 *  shows up there automatically on Pixel devices, giving device-to-device transfer over direct
 *  WiFi/Bluetooth with no relay server, without GlassFalcon having to implement discovery
 *  itself. A FileProvider is required to share a file URI to another app on modern Android. */
private fun shareRecord(context: android.content.Context, record: FlightRecord) {
    val file = FlightRecordStore.file(record)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share flight record"))
}

private fun Modifier.clickableCard(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
