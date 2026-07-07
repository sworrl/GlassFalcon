// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.AirSenseTarget
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.ui.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Self-centered ADS-B / AirSense radar scope (a PPI, like the round scopes in air-traffic control):
 * the aircraft sits at the middle and every manned target the drone's receiver reports is plotted
 * at its real bearing and distance, drawn as a chevron pointing the way it's actually tracking,
 * tinted by collision-threat level, and labelled with its ICAO id, relative altitude and range.
 * Concentric range rings + a slow sweep give the familiar radar read. This is a pure Compose Canvas
 * (no map tiles), so it works with no network and no basemap — it only needs traffic data, which
 * today comes from live AirSense frames or the synthetic radar-preview seeder.
 *
 * Heading-up (default) rotates the whole picture so the drone's nose is always at the top, the way
 * a pilot thinks; North-up locks north to the top instead.
 */
@Composable
fun RadarScreen(vm: FlightViewModel) {
    val airSense by vm.airSense.collectAsState()
    val drone by vm.drone.collectAsState()
    val lastKnown by vm.lastKnown.collectAsState()
    val home by vm.homePoint.collectAsState()
    val phone by vm.phoneLoc.collectAsState()

    var headingUp by remember { mutableStateOf(true) }

    val targets = airSense.targets.filter { it.valid }

    // Own-ship centre: live fix → last-known → home → phone GPS. With none of those (e.g. bench
    // preview with no GPS at all), fall back to the centroid of the targets so the relative
    // geometry still renders — labelled "relative" so it isn't mistaken for a real fix.
    val ownFix = if (drone.hasGpsFix && drone.lat != 0.0) drone.lat to drone.lon else null
    val center = ownFix ?: lastKnown ?: home ?: phone?.let { it.first to it.second }
        ?: targets.takeIf { it.isNotEmpty() }?.let { ts -> ts.map { it.lat }.average() to ts.map { it.lon }.average() }
    val relativeOnly = ownFix == null

    // Farthest target sets the outer ring; snap up to a tidy range so ring labels read cleanly.
    val farthest = targets.maxOfOrNull { effectiveDistanceM(center, it) } ?: 0.0
    val rangeM = niceRange(farthest)

    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3800, easing = LinearEasing)),
        label = "sweepAngle",
    )

    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f; isFakeBoldText = true
        }
    }
    val ringPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f; color = android.graphics.Color.parseColor("#5566AA")
        }
    }

    Column(Modifier.fillMaxSize().background(Navy).padding(12.dp)) {
        // ── Header ──
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AIRSENSE RADAR", color = DjiGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
            Text("${targets.size} target${if (targets.size == 1) "" else "s"}", color = TextSec, fontSize = 12.sp)
            if (airSense.maxWarningLevel >= 2)
                Text("⚠ TRAFFIC", color = DjiRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            val label = if (headingUp) "HEADING-UP" else "NORTH-UP"
            OutlinedButton(onClick = { headingUp = !headingUp }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(label, color = DjiCyan, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Scope ──
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = min(cx, cy) - 30f
                val heading = drone.yaw
                // Rotation applied to a WORLD bearing to get a screen angle (0 = up, clockwise).
                fun screenAngle(worldDeg: Float) = if (headingUp) worldDeg - heading else worldDeg

                drawRadarGrid(cx, cy, r, rangeM, ringPaint, ringPaint.color)

                // North marker (rotates in heading-up mode so it always points true north).
                val nAng = screenAngle(0f)
                val nPos = polar(cx, cy, r + 2f, nAng)
                labelPaint.color = android.graphics.Color.parseColor("#8899BB")
                drawContext.canvas.nativeCanvas.drawText("N", nPos.x - 8f, nPos.y + 8f, labelPaint)

                // Sweep wedge — a soft trailing gradient rotating around the scope.
                rotate(sweep, pivot = Offset(cx, cy)) {
                    val shader = SweepGradientShader(
                        center = Offset(cx, cy),
                        colors = listOf(Color.Transparent, DjiGreen.copy(alpha = 0.0f), DjiGreen.copy(alpha = 0.22f)),
                        colorStops = listOf(0f, 0.85f, 1f),
                    )
                    drawCircle(brush = androidx.compose.ui.graphics.ShaderBrush(shader), radius = r, center = Offset(cx, cy))
                }

                // Targets.
                for (t in targets) {
                    val bearing = bearingDeg(center, t)
                    val dist = effectiveDistanceM(center, t)
                    val rr = (dist / rangeM).toFloat().coerceIn(0f, 1f) * r
                    val ang = screenAngle(bearing.toFloat())
                    val p = polar(cx, cy, rr, ang)
                    val col = warnColor(t.warningLevel)
                    // Chevron pointing the target's own track (rotated into the display frame).
                    val chevronDeg = screenAngle(t.headingDeg.toFloat())
                    drawChevron(p, chevronDeg, col)
                    // Label: ICAO on top, relative-altitude + range under it.
                    labelPaint.color = col.toArgbInt()
                    val relSign = if (t.relAltM >= 0) "+" else ""
                    drawContext.canvas.nativeCanvas.apply {
                        drawText(t.icao.ifBlank { "—" }, p.x + 14f, p.y - 2f, labelPaint)
                        drawText("$relSign${t.relAltM}m ${fmtKm(dist)}", p.x + 14f, p.y + 22f, labelPaint)
                    }
                }

                // Own ship at the centre — a filled chevron (points up in heading-up, points to the
                // actual heading in north-up).
                val ownDeg = if (headingUp) 0f else heading
                drawOwnShip(Offset(cx, cy), ownDeg)
            }

            if (targets.isEmpty()) {
                Text(
                    if (airSense.active) "Scanning — no traffic in range" else "No AirSense data yet",
                    color = TextSec, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                )
            }
            if (relativeOnly && targets.isNotEmpty()) {
                Text(
                    "No own-GPS fix — relative geometry only",
                    color = DjiAmber, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }

        // ── Legend ──
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RANGE ${fmtKm(rangeM)}", color = TextSec, fontSize = 11.sp)
            LegendDot(DjiAmber, "advisory")
            LegendDot(Orange, "caution")
            LegendDot(DjiRed, "warning")
            Spacer(Modifier.weight(1f))
            Text("receive-only · advisory, not separation", color = TextSec, fontSize = 9.sp)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(9.dp)) { drawCircle(color) }
        Text(label, color = TextSec, fontSize = 11.sp)
    }
}

// ── Drawing helpers ──────────────────────────────────────────────────────────

private fun DrawScope.drawRadarGrid(cx: Float, cy: Float, r: Float, rangeM: Double, textPaint: android.graphics.Paint, ringArgb: Int) {
    val grid = DjiGreen.copy(alpha = 0.22f)
    // Range rings + labels at each quarter.
    for (i in 1..4) {
        val rr = r * i / 4f
        drawCircle(grid, radius = rr, center = Offset(cx, cy), style = Stroke(width = 1.5f))
        val ringM = rangeM * i / 4.0
        drawContext.canvas.nativeCanvas.drawText(fmtKm(ringM), cx + 4f, cy - rr + 22f, textPaint)
    }
    // Cross axes.
    drawLine(grid, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1f)
    drawLine(grid, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1f)
    // Bearing ticks every 30°.
    for (d in 0 until 360 step 30) {
        val a = Math.toRadians(d.toDouble())
        val inner = Offset(cx + (r - 10f) * sin(a).toFloat(), cy - (r - 10f) * cos(a).toFloat())
        val outer = Offset(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat())
        drawLine(grid, inner, outer, strokeWidth = 1f)
    }
}

private fun DrawScope.drawChevron(p: Offset, deg: Float, color: Color) {
    rotate(deg, pivot = p) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(p.x, p.y - 11f)
            lineTo(p.x + 8f, p.y + 8f)
            lineTo(p.x, p.y + 3f)
            lineTo(p.x - 8f, p.y + 8f)
            close()
        }
        drawPath(path, color)
    }
    // Faint threat halo so a warning target reads even at a glance.
    drawCircle(color.copy(alpha = 0.18f), radius = 16f, center = p)
}

private fun DrawScope.drawOwnShip(p: Offset, deg: Float) {
    rotate(deg, pivot = p) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(p.x, p.y - 13f)
            lineTo(p.x + 9f, p.y + 9f)
            lineTo(p.x, p.y + 4f)
            lineTo(p.x - 9f, p.y + 9f)
            close()
        }
        drawPath(path, DjiGreen)
    }
    drawCircle(DjiGreen.copy(alpha = 0.9f), radius = 3f, center = p)
}

// ── Math helpers ─────────────────────────────────────────────────────────────

private fun polar(cx: Float, cy: Float, r: Float, deg: Float): Offset {
    val a = Math.toRadians(deg.toDouble())
    return Offset(cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat())
}

/** Flat-earth bearing (deg, 0 = north, clockwise) from [center] to [t]. 0 if no center. */
private fun bearingDeg(center: Pair<Double, Double>?, t: AirSenseTarget): Double {
    if (center == null) return 0.0
    val (lat, lon) = center
    val dLat = t.lat - lat
    val dLon = (t.lon - lon) * cos(Math.toRadians(lat))
    return (Math.toDegrees(atan2(dLon, dLat)) + 360.0) % 360.0
}

/** Prefer the target's own reported distance; fall back to a haversine from the centre. */
private fun effectiveDistanceM(center: Pair<Double, Double>?, t: AirSenseTarget): Double {
    if (t.distanceM > 0) return t.distanceM.toDouble()
    if (center == null) return 0.0
    val (lat, lon) = center
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat))
    val dy = (t.lat - lat) * mPerDegLat
    val dx = (t.lon - lon) * mPerDegLon
    return hypot(dx, dy)
}

/** Round the outer range up to a clean value for readable ring labels. */
private fun niceRange(maxM: Double): Double {
    val steps = doubleArrayOf(500.0, 1000.0, 2000.0, 3000.0, 5000.0, 10_000.0, 20_000.0, 40_000.0)
    return steps.firstOrNull { it >= maxM } ?: maxM.coerceAtLeast(500.0)
}

private fun fmtKm(m: Double): String =
    if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${m.toInt()}m"

private fun warnColor(level: Int): Color = when {
    level >= 3 -> DjiRed
    level == 2 -> Orange
    else -> DjiAmber
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
