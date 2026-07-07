// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import dev.glassfalcon.core.bucket
import dev.glassfalcon.core.faaQueryUrl
import dev.glassfalcon.core.narrowBbox
import dev.glassfalcon.core.wideBbox
import dev.glassfalcon.ui.components.glass
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import dev.glassfalcon.core.AirSenseState
import dev.glassfalcon.core.AirSenseTarget
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import dev.glassfalcon.core.TrackPoint
import kotlinx.coroutines.flow.StateFlow
import dev.glassfalcon.core.DroneState
import java.io.File
import java.net.URI

/** Everything [FlightMap] actually reads, split out from a concrete [FlightViewModel]
 *  dependency so the same map rendering can be fed by a companion device's network stream
 *  instead of a live DUML link (see CompanionSync.kt's CompanionClient). */
interface MapTelemetrySource {
    val drone: StateFlow<DroneState>
    val track: StateFlow<List<TrackPoint>>
    val homePoint: StateFlow<Pair<Double, Double>?>
    val lastKnown: StateFlow<Pair<Double, Double>?>
    val airSense: StateFlow<AirSenseState>
}

/** DJI-style corner-map sizing. */
enum class MapSize { HIDDEN, SMALL, LARGE, FULL }

fun MapSize.next(): MapSize = when (this) {
    MapSize.HIDDEN -> MapSize.SMALL
    MapSize.SMALL  -> MapSize.LARGE
    MapSize.LARGE  -> MapSize.FULL
    MapSize.FULL   -> MapSize.SMALL
}

private val DjiGreen = Color(0xFF00CC44)
private val DjiAmber = Color(0xFFFFAA00)
private val DjiRed   = Color(0xFFFF3333)
private val DjiCyan  = Color(0xFF33CCFF)
private val US_CENTER = LatLng(39.5, -98.35)

/** Which metric colors the flight trail. */
enum class TrailMetric(val label: String, val unit: String, val max: Float) {
    SPEED(label = "SPD", unit = "m/s", max = 20f),
    ALTITUDE(label = "ALT", unit = "m", max = 120f),
}

/** Sequential blue ramp (light→dark), five stops spanning the metric's 0..max domain. */
private val TRAIL_RAMP = listOf("#86b6ef", "#5598e7", "#2a78d6", "#1c5cab", "#104281")

private fun trailValue(p: TrackPoint, metric: TrailMetric): Float = when (metric) {
    TrailMetric.SPEED    -> p.speed
    TrailMetric.ALTITUDE -> p.alt
}

/** Line-color expression: linear-interpolates the ramp over the metric's segment property. */
private fun trailColorExpression(metric: TrailMetric): Expression {
    val stops = TRAIL_RAMP.mapIndexed { i, hex ->
        Expression.stop(metric.max * i / (TRAIL_RAMP.size - 1), Expression.color(android.graphics.Color.parseColor(hex)))
    }
    return Expression.interpolate(Expression.linear(), Expression.get("m"), *stops.toTypedArray())
}

/** Line-WIDTH expression, driven by whichever metric ISN'T currently coloring the line, this
 *  is how both speed and altitude show on the SAME line simultaneously instead of needing the
 *  toggle to pick just one: color encodes one, thickness encodes the other (a real cartographic
 *  technique for two variables on one line, not a new invention). 2px at the metric's minimum,
 *  9px at its max, thin/thin-ish most of the flight, visibly thick during genuine extremes. */
private fun trailWidthExpression(metric: TrailMetric): Expression {
    val other = if (metric == TrailMetric.SPEED) TrailMetric.ALTITUDE else TrailMetric.SPEED
    return Expression.interpolate(
        Expression.linear(), Expression.get("w"),
        Expression.stop(0f, 2f), Expression.stop(other.max, 9f),
    )
}

/** One 2-point line segment per consecutive track pair, tagged with BOTH metrics at its start
 *  point, "m" drives color (the metric the toggle picked), "w" drives width (the other one),
 *  so a single line segment always carries both speed and altitude, not just whichever one the
 *  toggle currently shows as color. */
private fun trackToColoredSegments(track: List<TrackPoint>, metric: TrailMetric): FeatureCollection {
    if (track.size < 2) return FeatureCollection.fromFeatures(emptyArray())
    val other = if (metric == TrailMetric.SPEED) TrailMetric.ALTITUDE else TrailMetric.SPEED
    val features = track.zipWithNext { a, b ->
        Feature.fromGeometry(
            LineString.fromLngLats(listOf(Point.fromLngLat(a.lon, a.lat), Point.fromLngLat(b.lon, b.lat)))
        ).apply {
            addNumberProperty("m", trailValue(a, metric).coerceIn(0f, metric.max))
            addNumberProperty("w", trailValue(a, other).coerceIn(0f, other.max))
        }
    }
    return FeatureCollection.fromFeatures(features)
}

// FAA airspace query helpers (faaQueryUrl/wideBbox/narrowBbox/bucket) now live in
// dev.glassfalcon.core.Airspace.kt, shared with the co-pilot's airspace callouts.
// OpenAIP (global baseline) is a deliberate follow-up: it needs a user-supplied API key and
// this app has no secret-storage convention yet, so it's out of scope for this pass.

private const val AIRSPACE_RESTRICTED = "#FF3333" // prohibited / restricted / danger / special-use
private const val AIRSPACE_CONTROLLED = "#FFAA00" // Class B/C/D/E surface
private const val AIRSPACE_CAUTION    = "#FFAA00" // LAANC ceiling 50-200ft
private const val AIRSPACE_CLEAR      = "#00CC44" // LAANC ceiling 300-400ft

/** LAANC ceiling (ft AGL) → traffic-light fill, matching the FAA/B4UFLY convention pilots
 *  already recognize: 0ft red, 50-200ft amber, 300-400ft green. */
private fun ceilingColorExpression(): Expression = Expression.step(
    Expression.toNumber(Expression.get("CEILING")),
    Expression.color(android.graphics.Color.parseColor(AIRSPACE_RESTRICTED)),
    Expression.stop(50, Expression.color(android.graphics.Color.parseColor(AIRSPACE_CAUTION))),
    Expression.stop(300, Expression.color(android.graphics.Color.parseColor(AIRSPACE_CLEAR))),
)

/**
 * Resizable flight-map container. Renders ONE [FlightMap] whose box size changes
 * with [size] (so the MapView persists across SMALL↔LARGE↔FULL); HIDDEN removes it.
 */
@Composable
fun FlightMapContainer(
    vm: MapTelemetrySource, size: MapSize, onSize: (MapSize) -> Unit,
    phoneLocation: Triple<Double, Double, Float>? = null,
) {
    if (size == MapSize.HIDDEN) return
    var trailMetric by remember { mutableStateOf(TrailMetric.SPEED) }
    // Auto-zoom re-centers/re-zooms on every telemetry tick, which fights a manual pinch/pan
    // within a second, this lets the pilot freeze it and fly the map manually. Alpha lets the
    // map sit as a semi-transparent HUD layer instead of only an opaque PIP/fullscreen panel.
    var autoZoom by remember { mutableStateOf(true) }
    var mapAlpha by remember { mutableStateOf(1f) }
    // User-dragged offset from the default bottom-end anchor, SMALL and LARGE share one offset
    // (switching between them shouldn't reset a position the pilot just chose), FULL ignores it
    // entirely (always centered/fullscreen). Plain `remember`, not persisted to disk: this is a
    // per-session placement, not a setting worth surviving an app restart.
    var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }
    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    Box(Modifier.fillMaxSize().onGloballyPositioned { containerSizePx = it.size }) {
        val boxMod = when (size) {
            // Anchored bottom-END (not start) so this never overlaps the takeoff/land
            // guarded slider, which lives at the bottom-start corner of the flight controls.
            MapSize.SMALL -> Modifier.padding(end = 12.dp, bottom = 70.dp).size(190.dp, 130.dp)
            MapSize.LARGE -> Modifier.padding(end = 12.dp, bottom = 70.dp).size(380.dp, 250.dp)
            MapSize.FULL  -> Modifier.fillMaxSize()
            else          -> Modifier
        }
        val align = if (size == MapSize.FULL) Alignment.Center else Alignment.BottomEnd
        val pipWidthPx = with(density) { (if (size == MapSize.LARGE) 380.dp else 190.dp).toPx() }
        val pipHeightPx = with(density) { (if (size == MapSize.LARGE) 250.dp else 130.dp).toPx() }
        // Default anchor already sits at the screen's own edges (12dp/70dp in from bottom-end),
        // so dragging can only move the PIP TOWARD the opposite edges, never further past its
        // own default position, clamped to keep the whole PIP on-screen at all times.
        val maxLeftPx = -(containerSizePx.width - pipWidthPx - with(density) { 12.dp.toPx() })
        val maxUpPx = -(containerSizePx.height - pipHeightPx - with(density) { 70.dp.toPx() })

        Box(
            Modifier
                .align(align)
                .then(boxMod)
                .then(
                    if (size == MapSize.FULL) Modifier
                    else Modifier.offset { IntOffset(dragOffsetPx.x.roundToInt(), dragOffsetPx.y.roundToInt()) }
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, DjiGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                )
        ) {
            FlightMap(vm, Modifier.fillMaxSize(), compact = size != MapSize.FULL,
                trailMetric = trailMetric, onTrailMetric = { trailMetric = it },
                autoZoom = autoZoom, onAutoZoom = { autoZoom = it }, mapAlpha = mapAlpha,
                phoneLocation = phoneLocation)

            // ── size controls ──
            if (size == MapSize.FULL) {
                TextButton(
                    onClick = { onSize(MapSize.SMALL) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Text("✕ FPV", color = Color.White, fontWeight = FontWeight.Bold) }
            } else {
                // Tap anywhere on a small map to expand a step, unchanged from before, plain
                // `.clickable`, not a custom gesture detector: an `awaitEachGesture`-based manual
                // tap/drag disambiguator was tried here first and, for reasons that didn't show
                // up in any log line (its own coroutine body never even started, independent of
                // touch, a real, reproducible Compose oddity with this specific modifier
                // combination, not just a logic bug), silently never fired at all. Rather than
                // keep chasing it, drag gets its OWN dedicated handle below instead of sharing
                // this surface, no tap/drag conflict to disambiguate in the first place.
                Box(Modifier.matchParentSize().clickable { onSize(size.next()) })
                // Size controls + trail pill, added AFTER the expand-catcher above so they sit
                // on top and actually receive their own taps instead of the catcher eating them
                // first, this is what made the "✕" hide button unreachable before: it was
                // composed BEFORE the full-size catcher, so every tap on it triggered an expand
                // instead of a hide.
                Row(
                    Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PipBtn(if (autoZoom) "🔍" else "🔒") { autoZoom = !autoZoom }  // auto vs. frozen zoom
                    PipBtn(when { mapAlpha > 0.9f -> "◼"; mapAlpha > 0.5f -> "◒"; else -> "◻" }) {
                        mapAlpha = when { mapAlpha > 0.9f -> 0.55f; mapAlpha > 0.5f -> 0.25f; else -> 1f }
                    }
                    PipBtn("⤢") { onSize(size.next()) }     // expand
                    PipBtn("✕") { onSize(MapSize.HIDDEN) }  // hide
                }
                // Dedicated drag handle for repositioning, its own small touch target, not
                // shared with the tap-to-expand catcher above, so there's no gesture to
                // disambiguate: this area ONLY ever drags.
                Box(
                    Modifier.align(Alignment.TopStart).padding(4.dp)
                        .size(28.dp)
                        .glass(shape = RoundedCornerShape(4.dp), baseAlpha = 0.25f)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val next = dragOffsetPx + dragAmount
                                // Before the container is measured, containerSizePx is 0 so
                                // maxLeftPx/maxUpPx compute POSITIVE. The old code fed that straight
                                // into coerceIn(min>max) → crash; the first fix clamped both bounds
                                // to 0 → no crash but the PIP couldn't move at all (this is the "it
                                // doesn't drag" report). Now: only clamp once we actually have a
                                // measured container; until then, let it move freely.
                                dragOffsetPx = if (containerSizePx.width > 0 && containerSizePx.height > 0) {
                                    Offset(
                                        next.x.coerceIn(minOf(maxLeftPx, 0f), 0f),
                                        next.y.coerceIn(minOf(maxUpPx, 0f), 0f),
                                    )
                                } else next
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("⠿", color = Color.White, fontSize = 14.sp) }
                Box(Modifier.align(Alignment.TopStart).padding(top = 36.dp, start = 4.dp)) {
                    TrailLegend(trailMetric, expanded = false, onChange = { trailMetric = it })
                }
            }
        }
    }
}

/** A single tappable status/toggle chip in the FULL-map control bar. Bright accent when [on],
 *  dimmed grey when off, so its state reads at a glance. */
@Composable
private fun MapChip(label: String, accent: Color, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) accent else Color.Gray,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PipBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(22.dp).glass(shape = RoundedCornerShape(4.dp), baseAlpha = 0.25f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 12.sp) }
}

/** The MapLibre map itself: live position, breadcrumb track, home & last-known markers.
 *  [trailMetric]/[onTrailMetric] are hoisted to the caller, in compact (SMALL/LARGE) sizing
 *  the toggle pill has to be layered above [FlightMapContainer]'s tap-to-expand catcher, so it
 *  can't be rendered from in here where it would sit underneath that catcher and never receive
 *  its own taps. */
@Composable
fun FlightMap(
    vm: MapTelemetrySource, modifier: Modifier, compact: Boolean,
    trailMetric: TrailMetric = TrailMetric.SPEED, onTrailMetric: (TrailMetric) -> Unit = {},
    autoZoom: Boolean = true, onAutoZoom: (Boolean) -> Unit = {}, mapAlpha: Float = 1f,
    // Static flight-record replay wants the WHOLE track framed once on load (a short flight's
    // path was showing as a barely-visible speck, the live-flight autoZoom path re-centers on
    // "current position" every tick at a live-flying zoom level, which for a fixed historical
    // track just zooms in tight on its last point instead of fitting the path that's actually
    // there to look at). Live flight/Companion mode leave this false and keep the existing
    // follow-current-position behavior.
    fitTrackOnLoad: Boolean = false,
    // Falls back to the PHONE's own GPS when there's no drone position to show at all, view-
    // only mode / RC-only-no-drone-telemetry / drone powered off all leave drone/lastKnown/home
    // empty, and centering on a random US_CENTER placeholder is worse than centering on where
    // the pilot (and phone) actually are right now.
    phoneLocation: Triple<Double, Double, Float>? = null,
) {
    val ctx = LocalContext.current
    val drone     by vm.drone.collectAsState()
    val track     by vm.track.collectAsState()
    val home      by vm.homePoint.collectAsState()
    val lastKnown by vm.lastKnown.collectAsState()
    val airSense  by vm.airSense.collectAsState()

    val styleUri = remember { resolveStyleUri(ctx) }
    val offline  = remember { styleUri.startsWith("asset://") }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var airspaceOn by remember { mutableStateOf(true) }
    // New map-overhaul controls (session state; not persisted this pass).
    var basemap by remember { mutableStateOf(Basemap.DARK) }
    var radarOn by remember { mutableStateOf(true) }
    var ringsOn by remember { mutableStateOf(true) }

    val mapView = remember {
        MapView(ctx).apply {
            onCreate(Bundle()); onStart(); onResume()
            getMapAsync { map ->
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isLogoEnabled = false
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(US_CENTER, 3.5))
                mapRef = map
            }
        }
    }

    // Style load + reload. Driven by (mapRef, basemap) so switching basemap re-loads the tiles and
    // re-populates every GlassFalcon overlay via the shared populateGfStyle(); styleReady flips
    // false→true which re-fires the data-push effects below so nothing goes blank after a switch.
    LaunchedEffect(mapRef, basemap) {
        val map = mapRef ?: return@LaunchedEffect
        styleReady = false
        map.setStyle(basemapStyle(ctx, basemap)) { style ->
            populateGfStyle(style, trailMetric)
            styleReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }

    // MapLibre's own pan/zoom/rotate gestures are enabled by default and, being a real native
    // View's own touch handling (not a Compose gesture), can claim touches within the map's
    // bounds before the Compose-level tap-to-expand/drag-to-reposition overlays in
    // FlightMapContainer ever see them, a tiny 190x130dp PIP was never meant to be pannable
    // anyway (autoZoom already keeps it centered on the aircraft); real interactive map gestures
    // only make sense once it's FULL size. Disabling them in compact mode is what actually lets
    // those Compose-level overlays receive taps/drags at all.
    LaunchedEffect(compact, mapRef) {
        mapRef?.uiSettings?.setAllGesturesEnabled(!compact)
    }

    LaunchedEffect(styleReady, trailMetric) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        (map.style?.getLayer("gf-track-l") as? LineLayer)?.setProperties(
            PropertyFactory.lineColor(trailColorExpression(trailMetric)),
            PropertyFactory.lineWidth(trailWidthExpression(trailMetric)),
        )
    }

    // Airspace: show/hide on toggle, and refresh the FAA query bbox when the effective
    // position (drone fix, else last-known, else home, else the PHONE's own GPS) moves to a
    // new ~11km bucket. Phone GPS as the last resort covers view-only mode / RC-only-no-drone /
    // drone powered off, where there's no drone position at all to fall back through.
    // Keying on the BUCKETED pair (not raw home/lastKnown) is load-bearing: those StateFlows
    // update on every telemetry tick (~50Hz per the RC240 link), so keying on their raw
    // values restarted this effect on every single tick, cancelling each ArcGIS request
    // before it ever finished (confirmed via logcat: nothing but a rapid-fire "Canceled"
    // storm, ~10 requests/sec, none completing). Bucketing first is what makes this fire
    // only when we've actually moved to a new ~11km cell.
    val effPos = (if (drone.hasGpsFix && drone.lat != 0.0) drone.lat to drone.lon else null)
        ?: lastKnown ?: home ?: phoneLocation?.let { it.first to it.second }
    val airspaceBucket = effPos?.let { bucket(it.first) to bucket(it.second) }
    LaunchedEffect(styleReady, airspaceOn, airspaceBucket) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val vis = PropertyFactory.visibility(if (airspaceOn) Property.VISIBLE else Property.NONE)
        listOf("gf-faa-sua-fill", "gf-faa-sua-line", "gf-faa-class-fill", "gf-faa-class-line", "gf-faa-grid-fill")
            .forEach { style.getLayer(it)?.setProperties(vis) }
        if (!airspaceOn) return@LaunchedEffect
        val pos = effPos ?: return@LaunchedEffect
        val wide = wideBbox(pos.first, pos.second)
        val narrow = narrowBbox(pos.first, pos.second)
        (style.getSource("gf-faa-sua") as? GeoJsonSource)?.setUri(URI(faaQueryUrl("Special_Use_Airspace", wide)))
        (style.getSource("gf-faa-class") as? GeoJsonSource)?.setUri(URI(faaQueryUrl("Class_Airspace", wide)))
        (style.getSource("gf-faa-grid") as? GeoJsonSource)?.setUri(URI(faaQueryUrl("FAA_UAS_FacilityMap_Data", narrow)))
    }

    var hasFitTrackOnce by remember { mutableStateOf(false) }

    LaunchedEffect(styleReady, track, trailMetric, drone.lat, drone.lon, drone.speed, drone.yaw, home, lastKnown, airSense, radarOn, ringsOn) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        val trackSrc = style.getSource("gf-track") as? GeoJsonSource
        trackSrc?.setGeoJson(trackToColoredSegments(track, trailMetric))
        (style.getSource("gf-home") as? GeoJsonSource)?.setGeoJson(pointFc(home))
        (style.getSource("gf-last") as? GeoJsonSource)?.setGeoJson(pointFc(lastKnown))
        val dronePos = if (drone.hasGpsFix && drone.lat != 0.0) drone.lat to drone.lon else null
        (style.getSource("gf-drone") as? GeoJsonSource)?.setGeoJson(pointFc(dronePos))
        (style.getSource("gf-drone-hdg") as? GeoJsonSource)?.setGeoJson(droneHdgFc(dronePos, drone.yaw))
        // ADS-B blips only when the radar is on; range rings centered on the best-known position.
        (style.getSource("gf-adsb") as? GeoJsonSource)?.setGeoJson(
            airSenseFc(if (radarOn) airSense.targets else emptyList()))
        (style.getSource("gf-rings") as? GeoJsonSource)?.setGeoJson(
            ringsFc(if (ringsOn) (dronePos ?: effPos) else null, listOf(500, 1000, 2000)))

        if (fitTrackOnLoad) {
            // Frame the WHOLE recorded path once, not "current position at a live-flying zoom"
            //, this only needs to happen the first time the track is available, a static
            // replay's bounds never change afterward.
            if (!hasFitTrackOnce && track.size >= 2) {
                hasFitTrackOnce = true
                val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().apply {
                    track.forEach { include(LatLng(it.lat, it.lon)) }
                }.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
            return@LaunchedEffect
        }

        // Skip the forced re-center/re-zoom entirely when autoZoom is off, this is what was
        // fighting manual pinch/pan before: it fired on every telemetry tick regardless of
        // whatever zoom level the pilot had just set by hand.
        if (autoZoom) {
            (dronePos ?: lastKnown ?: home ?: phoneLocation?.let { it.first to it.second })?.let {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.first, it.second), zoomForSpeed(drone.speed)))
            }
        }
    }

    Box(modifier.background(Color(0xFF0B0B0B))) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().alpha(mapAlpha))
        if (!compact) {
            // Horizontally scrollable so every control stays reachable no matter how many chips
            // are added or how narrow the screen — nothing runs off the edge and disappears.
            Row(
                Modifier.fillMaxWidth().glass(shape = RectangleShape)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("MAP", color = DjiGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                MapChip("⛰ ${basemap.label}", DjiCyan, on = true) { basemap = basemap.next() }
                MapChip(if (radarOn) "◎ RADAR" else "radar off", DjiGreen, radarOn) { radarOn = !radarOn }
                MapChip(if (ringsOn) "◯ RINGS" else "rings off", DjiCyan, ringsOn) { ringsOn = !ringsOn }
                MapChip(if (airspaceOn) "AIRSPACE" else "airspace off", DjiAmber, airspaceOn) { airspaceOn = !airspaceOn }
                MapChip(if (autoZoom) "AUTO-ZOOM" else "zoom locked", DjiGreen, autoZoom) { onAutoZoom(!autoZoom) }
                if (!drone.hasGpsFix) Text("NO GPS", color = DjiRed, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                TrailLegend(trailMetric, expanded = true, onChange = onTrailMetric)
                Text(if (offline) "offline" else "online", color = if (offline) DjiGreen else DjiAmber, fontSize = 10.sp)
            }
        }
    }
}

/** Trail color-key: tap to cycle SPEED↔ALTITUDE. `expanded` shows the gradient bar + labels
 *  (FULL map); compact map (SMALL/LARGE pip) gets just the metric pill, no room for more. */
@Composable
private fun TrailLegend(metric: TrailMetric, expanded: Boolean, onChange: (TrailMetric) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .then(if (expanded) Modifier else Modifier.glass(shape = RoundedCornerShape(4.dp), baseAlpha = 0.25f))
            .clickable { onChange(if (metric == TrailMetric.SPEED) TrailMetric.ALTITUDE else TrailMetric.SPEED) }
            .padding(if (expanded) PaddingValues(0.dp) else PaddingValues(4.dp)),
    ) {
        Text(metric.label, color = DjiGreen, fontWeight = FontWeight.Bold, fontSize = if (expanded) 11.sp else 9.sp)
        if (expanded) {
            Canvas(Modifier.size(width = 70.dp, height = 8.dp).clip(RoundedCornerShape(2.dp))) {
                drawRect(Brush.horizontalGradient(TRAIL_RAMP.map { Color(android.graphics.Color.parseColor(it)) }))
            }
            Text("0–${metric.max.toInt()} ${metric.unit}", color = Color.White, fontSize = 9.sp)
        }
    }
}

private fun circle(id: String, src: String, color: String, r: Float) =
    CircleLayer(id, src).withProperties(
        PropertyFactory.circleColor(color),
        PropertyFactory.circleRadius(r),
        PropertyFactory.circleStrokeColor("#000000"),
        PropertyFactory.circleStrokeWidth(1.5f),
    )

private fun pointFc(p: Pair<Double, Double>?): FeatureCollection =
    if (p == null) FeatureCollection.fromFeatures(emptyArray())
    else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(p.second, p.first)))

/** One point per valid AirSense target. Carries the full modeled state (warn level, heading,
 *  altitude, relative altitude, distance, ICAO) so the radar can rotate the blip to the aircraft's
 *  track, tint it by threat, and print a callsign/relative-altitude label — the AirSenseTarget
 *  model already has all of this; the old renderer threw everything but lat/lon/warn away. */
private fun airSenseFc(targets: List<AirSenseTarget>): FeatureCollection =
    FeatureCollection.fromFeatures(targets.filter { it.valid }.map { t ->
        val relSign = if (t.relAltM >= 0) "+" else ""
        val distKm = t.distanceM / 1000.0
        val props = com.google.gson.JsonObject().apply {
            addProperty("warn", t.warningLevel)
            addProperty("icao", t.icao)
            addProperty("hdg", t.headingDeg)
            addProperty("alt", t.altM)
            addProperty("relalt", t.relAltM)
            addProperty("dist", t.distanceM)
            // Pre-composed so the SymbolLayer needs no string-concat expression: "ICAO\n+120m ·1.4km".
            addProperty("label", "${t.icao}\n$relSign${t.relAltM}m ·%.1fkm".format(distKm))
        }
        Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat), props)
    })

/** Concentric range rings centered on [center] at the given radii (metres) — the "radar scope"
 *  overlay. Each ring is a 64-point polygon approximated with a local flat-earth metres→degrees
 *  conversion (fine at these radii/latitudes); carries its radius label for the ring legend. */
private fun ringsFc(center: Pair<Double, Double>?, radiiM: List<Int>): FeatureCollection {
    if (center == null) return FeatureCollection.fromFeatures(emptyArray())
    val (lat, lon) = center
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
    val feats = radiiM.map { rM ->
        val pts = (0..64).map { i ->
            val a = Math.toRadians(i * 360.0 / 64.0)
            Point.fromLngLat(
                lon + (rM * sin(a)) / mPerDegLon,
                lat + (rM * cos(a)) / mPerDegLat,
            )
        }
        Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addNumberProperty("r", rM) }
    }
    return FeatureCollection.fromFeatures(feats)
}

/** The drone as a heading-carrying feature so a rotated chevron can point where its nose is. */
private fun droneHdgFc(pos: Pair<Double, Double>?, yawDeg: Float): FeatureCollection {
    if (pos == null) return FeatureCollection.fromFeatures(emptyArray())
    val props = com.google.gson.JsonObject().apply { addProperty("yaw", yawDeg.toDouble()) }
    return FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(pos.second, pos.first), props))
}

/** A small upward-pointing SDF triangle used for heading-rotated markers (ADS-B traffic + the
 *  drone's own nose). SDF so a single white bitmap can be tinted per-layer/per-feature via
 *  iconColor; drawn pointing "north" (up) so iconRotate = compass heading works directly. */
private fun triangleBitmap(sizePx: Int = 36): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL
    }
    val path = android.graphics.Path().apply {
        moveTo(sizePx / 2f, sizePx * 0.08f)
        lineTo(sizePx * 0.86f, sizePx * 0.92f)
        lineTo(sizePx * 0.14f, sizePx * 0.92f)
        close()
    }
    c.drawPath(path, p)
    return bmp
}

/** Which basemap the FULL map draws under the GlassFalcon overlays. Dark matches the HUD; Streets
 *  is a labeled day map; Satellite is Esri World Imagery (public raster) for terrain/obstacle scan. */
enum class Basemap(val label: String) { DARK("Dark"), STREETS("Streets"), SATELLITE("Satellite") }

fun Basemap.next(): Basemap = Basemap.entries[(ordinal + 1) % Basemap.entries.size]

/** Build the MapLibre style for a basemap. Falls back to the bundled offline style when there's
 *  no network (all three online basemaps need tiles). Satellite is an inline raster style. */
private fun basemapStyle(ctx: android.content.Context, basemap: Basemap): Style.Builder {
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val online = cm.getNetworkCapabilities(cm.activeNetwork)
        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    if (!online) return Style.Builder().fromUri("asset://offline_style.json")
    return when (basemap) {
        Basemap.DARK    -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")
        Basemap.STREETS -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
        Basemap.SATELLITE -> Style.Builder().fromJson(
            """{"version":8,"sources":{"sat":{"type":"raster",""" +
            """"tiles":["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],""" +
            """"tileSize":256,"maxzoom":19,"attribution":"Esri"}},""" +
            """"layers":[{"id":"sat","type":"raster","source":"sat"}]}"""
        )
    }
}

/** Adds every GlassFalcon overlay (track, markers, airspace, range rings, ADS-B radar, drone
 *  heading) to a freshly-loaded [style]. Extracted so both the initial load and a basemap switch
 *  populate the style identically. Runtime-guarded per group so one failing add can't blank the
 *  whole overlay set. */
private fun populateGfStyle(style: Style, trailMetric: TrailMetric) {
    runCatching {
        style.addSource(GeoJsonSource("gf-track"))
        style.addSource(GeoJsonSource("gf-home"))
        style.addSource(GeoJsonSource("gf-last"))
        style.addSource(GeoJsonSource("gf-drone"))
        style.addSource(GeoJsonSource("gf-drone-hdg"))
        style.addSource(GeoJsonSource("gf-rings"))
        style.addLayer(LineLayer("gf-track-l", "gf-track").withProperties(
            PropertyFactory.lineColor(trailColorExpression(trailMetric)),
            PropertyFactory.lineWidth(trailWidthExpression(trailMetric))))
        style.addLayer(circle("gf-last-l", "gf-last", "#FF3333", 6f))
        style.addLayer(circle("gf-home-l", "gf-home", "#3399FF", 7f))
        style.addLayer(circle("gf-drone-l", "gf-drone", "#00CC44", 8f))
    }

    // FAA airspace overlay (public data; corrected to real position by the LaunchedEffect).
    runCatching {
        val initBbox = US_CENTER.latitude to US_CENTER.longitude
        style.addSource(GeoJsonSource("gf-faa-sua", URI(faaQueryUrl("Special_Use_Airspace", wideBbox(initBbox.first, initBbox.second)))))
        style.addSource(GeoJsonSource("gf-faa-class", URI(faaQueryUrl("Class_Airspace", wideBbox(initBbox.first, initBbox.second)))))
        style.addSource(GeoJsonSource("gf-faa-grid", URI(faaQueryUrl("FAA_UAS_FacilityMap_Data", narrowBbox(initBbox.first, initBbox.second)))))
        style.addLayer(FillLayer("gf-faa-grid-fill", "gf-faa-grid").withProperties(
            PropertyFactory.fillColor(ceilingColorExpression()), PropertyFactory.fillOpacity(0.2f)))
        style.addLayer(FillLayer("gf-faa-class-fill", "gf-faa-class").withProperties(
            PropertyFactory.fillColor(android.graphics.Color.parseColor(AIRSPACE_CONTROLLED)), PropertyFactory.fillOpacity(0.15f)))
        style.addLayer(LineLayer("gf-faa-class-line", "gf-faa-class").withProperties(
            PropertyFactory.lineColor(AIRSPACE_CONTROLLED), PropertyFactory.lineWidth(1f)))
        style.addLayer(FillLayer("gf-faa-sua-fill", "gf-faa-sua").withProperties(
            PropertyFactory.fillColor(android.graphics.Color.parseColor(AIRSPACE_RESTRICTED)), PropertyFactory.fillOpacity(0.25f)))
        style.addLayer(LineLayer("gf-faa-sua-line", "gf-faa-sua").withProperties(
            PropertyFactory.lineColor(AIRSPACE_RESTRICTED), PropertyFactory.lineWidth(1.5f)))
    }

    // Range rings (radar scope), drawn above airspace, below the markers/traffic.
    runCatching {
        style.addLayer(LineLayer("gf-rings-l", "gf-rings").withProperties(
            PropertyFactory.lineColor("#33CCFF"),
            PropertyFactory.lineWidth(1f),
            PropertyFactory.lineOpacity(0.35f),
            PropertyFactory.lineDasharray(arrayOf(2f, 3f))))
    }

    // Shared SDF chevron for heading-rotated markers.
    runCatching { style.addImage("gf-tri", triangleBitmap(), true) }

    // AirSense (ADS-B / UAT IN) traffic radar. Halo ring + heading-rotated chevron tinted by
    // threat level + a callsign/relative-altitude label. Added LAST so it sits on top.
    runCatching {
        style.addSource(GeoJsonSource("gf-adsb"))
        style.addLayer(CircleLayer("gf-adsb-ring", "gf-adsb").withProperties(
            PropertyFactory.circleColor(adsbWarnColor()),
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleOpacity(0.18f)))
        style.addLayer(SymbolLayer("gf-adsb-sym", "gf-adsb").withProperties(
            PropertyFactory.iconImage("gf-tri"),
            PropertyFactory.iconSize(0.6f),
            PropertyFactory.iconRotate(Expression.toNumber(Expression.get("hdg"))),
            PropertyFactory.iconColor(adsbWarnColor()),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)))
        style.addLayer(SymbolLayer("gf-adsb-label", "gf-adsb").withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(9f),
            PropertyFactory.textColor(adsbWarnColor()),
            PropertyFactory.textHaloColor("#000000"),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP)))
    }

    // Drone nose-direction chevron on top of the drone dot.
    runCatching {
        style.addLayer(SymbolLayer("gf-drone-hdg-l", "gf-drone-hdg").withProperties(
            PropertyFactory.iconImage("gf-tri"),
            PropertyFactory.iconSize(0.5f),
            PropertyFactory.iconRotate(Expression.toNumber(Expression.get("yaw"))),
            PropertyFactory.iconColor("#00CC44"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)))
    }
}

/** Radar blip colour by AirSense warning level: amber (low) → orange (2) → red (3+). */
private fun adsbWarnColor(): Expression =
    Expression.step(
        Expression.toNumber(Expression.get("warn")),
        Expression.color(android.graphics.Color.parseColor("#FFCC00")),
        Expression.stop(2, Expression.color(android.graphics.Color.parseColor("#FF7700"))),
        Expression.stop(3, Expression.color(android.graphics.Color.parseColor("#FF2222"))),
    )

/** Smart auto-zoom: tightest useful zoom when stationary, pull back as ground speed rises
 *  so faster flight shows more of what's ahead. ~18.5 at rest → ~14 at 25 m/s. */
private fun zoomForSpeed(speed: Float): Double {
    val s = (if (speed.isFinite()) speed else 0f).coerceIn(0f, 25f)
    return 18.5 - (s / 25.0) * 4.5
}

/**
 * Real basemap when there's a network path, a bundled zero-network fallback otherwise, there
 * used to be a "download the US pack for offline" UI string with no actual download mechanism
 * behind it anywhere in the codebase, so offline mode was silently unreachable. A genuinely
 * offline full cartographic basemap (real roads/terrain/labels) for even just the US is many
 * GB, not something an APK can reasonably bundle, so the honest offline fallback bundled
 * here (assets/offline_style.json) is a plain dark background with NO tile source at all: no
 * road/terrain detail, but the track line, drone/home/last-known markers, and airspace overlay
 * (if that request also succeeds/fails independently) all still render correctly on top of it.
 * "dark" (confirmed real OpenFreeMap style, background rgb(12,12,12)) replaces the previous
 * "liberty" for the online case, liberty's pale cream/tan land polygons read flat and washed
 * out against the rest of GlassFalcon's dark glass HUD; dark matches it.
 */
private fun resolveStyleUri(ctx: android.content.Context): String {
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val hasNetwork = cm.getNetworkCapabilities(cm.activeNetwork)
        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    return if (hasNetwork) "https://tiles.openfreemap.org/styles/dark" else "asset://offline_style.json"
}
