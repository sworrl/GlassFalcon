// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui.screens

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.BatteryManager
import android.provider.Settings
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import dev.glassfalcon.ui.IbmPlexMono
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.glassfalcon.BuildConfig
import dev.glassfalcon.R
import dev.glassfalcon.core.ActiveTrackMode
import dev.glassfalcon.core.ActiveTrackStatus
import dev.glassfalcon.core.CopilotMode
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.core.FlyC
import dev.glassfalcon.core.ObstacleState
import dev.glassfalcon.core.TapFlyMode
import dev.glassfalcon.core.TapFlyStatus
import dev.glassfalcon.ui.components.glass
import dev.glassfalcon.ui.components.glassSource
import dev.glassfalcon.ui.Gold
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.BlendMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeChild
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

private val BarBg    = Color(0xBB000000)
private val PanelBg  = Color(0x88000000)
private val DjiGreen = Color(0xFF00CC44)
private val DjiAmber = Color(0xFFFFAA00)
private val DjiRed   = Color(0xFFFF3333)
private val DjiCyan  = Color(0xFF33CCFF)
private val TextPri  = Color.White
private val TextSec  = Color(0xFFCCCCCC)

// HudGlassSurface's own band depths, shared with anything else that needs to line up with the
// glass frame's exact inner edge (e.g. ObstacleEdgeGlow's domes), so there's one source of truth
// instead of the same three numbers duplicated wherever they're needed.
private val HUD_TOP_BAND = 36.dp
private val HUD_BOTTOM_BAND = 34.dp
private val HUD_SIDE_BAND = 56.dp

/** What's actually rendered on this phone's own screen, see [displayMode]'s doc comment at its
 *  declaration in [MainScreen] for why this matters beyond just the phone itself. */
private enum class DisplayMode { FULL, MINIMAL, CAMERA_ONLY, MAP_ONLY }

// Mirrors sdk/python/src/glassfalcon/duml_cmds.py's *_NAMES tables, same indices, same DUML
// setter cmd_ids (Camera.setIso/setShutter/setEv/setWb/setAperture). No read-back exists for
// any of these (the 0x80 push only carries mode/recording/sd status), so tapping cycles the
// index and shows what was last SENT, not a confirmed camera-reported value.
private val ISO_NAMES = listOf("Auto","100","200","400","800","1600","3200","6400","12800")
private val WB_NAMES = listOf("Auto","Sunny","Cloudy","Indoor","Fluorescent","Custom")
private val APERTURE_NAMES = listOf("f/1.7","f/2.0","f/2.2","f/2.5","f/2.8","f/3.2","f/3.5",
    "f/4.0","f/4.5","f/5.0","f/5.6","f/6.3","f/7.1","f/8.0","f/9.0","f/10","f/11")
private val EV_NAMES = listOf("-3.0","-2.5","-2.0","-1.5","-1.0","-0.5","0.0","+0.5","+1.0","+1.5","+2.0","+2.5","+3.0")
// Per dji-dumlv1-camera.lua's CAMERA_SHOT_INFO_FUSELAGE_FOCUS_MODE_ENUM; index 0 and 2 confirmed
// live via kprobe capture 2026-07-03 (see DumlCommands.kt), 1 and 3 not directly observed.
private val FOCUS_NAMES = listOf("MF", "AF", "AFC", "MF-Fine")
private val SHUTTER_NAMES = listOf(
    "Auto","1/8000","1/6400","1/5000","1/4000","1/3200","1/2500","1/2000",
    "1/1600","1/1250","1/1000","1/800","1/640","1/500","1/400","1/320",
    "1/240","1/200","1/160","1/120","1/100","1/80","1/60","1/50","1/40",
    "1/30","1/25","1/20","1/15","1/12","1/10","1/8","1/6","1/5","1/4",
    "1/3","0.4","0.5","0.6","0.8","1s","1.3s","1.6s","2s","2.5s","3s","4s","5s","6s","7s","8s",
)

/** TextureView stretches the texture to fill the view by default, which distorts the real
 *  aspect ratio. Correct it by shrinking the over-stretched axis only, pillarbox/letterbox
 *  bars rather than a squashed or stretched image. [videoRes] should be the decoder's actual
 *  negotiated output size (see VideoDecoder.resolution), not a hardcoded guess. */
private fun scaleVideoToFit(view: TextureView, videoRes: Pair<Int, Int>) {
    val surfW = view.width
    val surfH = view.height
    if (surfW <= 0 || surfH <= 0) return
    val (vidW, vidH) = videoRes
    if (vidW <= 0 || vidH <= 0) return
    val videoAspect = vidW.toFloat() / vidH.toFloat()
    val viewAspect  = surfW.toFloat() / surfH.toFloat()
    val m = android.graphics.Matrix()
    if (viewAspect > videoAspect)
        m.setScale(videoAspect / viewAspect, 1f, surfW / 2f, surfH / 2f)
    else
        m.setScale(1f, viewAspect / videoAspect, surfW / 2f, surfH / 2f)
    view.setTransform(m)
}

@Composable
fun MainScreen(vm: FlightViewModel, onOpenSettings: () -> Unit) {
    val app    by vm.app.collectAsState()
    val drone  by vm.drone.collectAsState()
    val timer  by vm.flightTimer.collectAsState()
    val phone  by vm.phoneLoc.collectAsState()
    val droneLinked by vm.droneLinked.collectAsState()
    val warnings by vm.warnings.collectAsState()
    // Map starts hidden so its GL context doesn't init on the main thread at launch
    // (that contends with video decode + the landscape rotation → startup ANR). Tap 🗺 MAP.
    var mapSize by remember { mutableStateOf(MapSize.HIDDEN) }
    // What this phone's own screen shows, relevant beyond just the phone itself because
    // Android's built-in screen-cast/Smart View mirrors whatever's actually on-screen: picking a
    // mode here is how a TV paired via the phone's own system Cast feature ends up showing "just
    // the camera" or "just the map" instead of the full instrument HUD, with no separate
    // streaming pipeline of our own, see rememberCastLauncher's doc comment.
    var displayMode by remember { mutableStateOf(DisplayMode.FULL) }
    // Focus mode: false = auto (AFC continuous), true = manual. In AF a single tap on the feed
    // sets a spot-focus point (with the reticle); in MF it doesn't (there's nothing to point at).
    var manualFocus by remember { mutableStateOf(false) }
    // Take-off is a 3-step commit: small button → full-screen instructional modal → the
    // guarded slide inside it. Keeps the always-visible flight HUD uncluttered (DJI GO 4-style)
    // while still requiring the same deliberate flip-then-drag before motors arm.
    var showTakeoffModal by remember { mutableStateOf(false) }
    // Status detail tray, collapsed by default so the always-on top bar stays a one-line
    // strip; tapping ANY badge in it (GPS, airspace, battery, temp, link, light, radar, mode)
    // expands the same shared tray rather than each needing its own popover.
    var statusTrayOpen by remember { mutableStateOf(false) }
    // Haze is OFF. It only works by blurring a registered source, and the only source worth
    // blurring here is the video, but registering a TextureView as a Haze source blurs the ENTIRE
    // live feed (it can't composite cleanly into Haze's capture layer). With no source, the glass
    // panels' hazeChild renders its tint over nothing, which is the black that was stuck in the
    // glass texture. Null everywhere → every panel uses its plain tint+gradient fallback: light,
    // clear glass with no blur and no black. The ambient blur behind the feed is a separate Image.
    val hazeState: HazeState? = null
    val videoRes by vm.video.resolution.collectAsState()
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    // Ambient-blur backdrop: a small, throttled snapshot of the live feed, drawn behind the sharp
    // (letterboxed) video and scaled to FILL, so the side gaps show a blurred extension of the feed
    // instead of black slabs. Grabbing a TextureView bitmap is a GPU→CPU copy, so keep it tiny and
    // infrequent, this is decorative fill, not a second live view.
    var blurFill by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.connected, displayMode) {
        while (isActive && app.connected && displayMode != DisplayMode.MAP_ONLY) {
            val tv = textureViewRef
            if (tv != null && tv.isAvailable && tv.width > 0) {
                runCatching { tv.getBitmap(160, 90)?.asImageBitmap() }.getOrNull()?.let { blurFill = it }
            }
            delay(300)
        }
    }

    // Re-letterbox whenever the REAL decoded resolution changes, this fires after the
    // TextureView is already up and sized (the decoder only learns the true size once it
    // sees the stream's SPS), so surface-lifecycle callbacks alone won't re-trigger it.
    LaunchedEffect(videoRes) {
        textureViewRef?.let { tv -> if (tv.width > 0 && tv.height > 0) scaleVideoToFit(tv, videoRes) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        if (!app.connected) {
            // ── No-connection state ────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.glass_falcon_logo),
                    contentDescription = "Glass Falcon",
                    modifier = Modifier.size(220.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("GLASS FALCON", color = DjiGreen, fontSize = 24.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Spacer(Modifier.height(6.dp))
                Text("Connect controller via USB", color = TextSec, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                // Camera/Gimbal/etc. settings screens are still useful without a live link, 
                // reviewing/pre-setting capture mode, checking gallery, browsing device info, 
                // same as DJI GO 4 lets you enter Camera settings before a drone is connected.
                // Individual controls inside those screens already gate on `app.connected`
                // at the point of actually sending a command, so nothing here can reach the
                // drone with no link, this only unblocks navigation, not drone actions.
                OutlinedButton(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DjiGreen),
                ) { Text("⚙  Camera / Settings (no drone required)", fontSize = 13.sp) }
                Spacer(Modifier.height(10.dp))
                // Puts the full flight HUD up with no real RC/drone attached, for iterating on
                // the glass frame/tapes/gauges without needing hardware plugged in every time.
                // Renders identically to a real "controller connected, drone off" session (no
                // telemetry ever arrives either way), so nothing here can command a real aircraft.
                OutlinedButton(
                    onClick = { vm.enterPreviewMode() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSec),
                ) { Text("▶  Preview HUD (no hardware)", fontSize = 13.sp) }
                Spacer(Modifier.height(20.dp))
                Text("v${BuildConfig.VERSION_NAME.replace('-', ' ')}", color = TextSec.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            return@Box
        }

        // Blurred ambient fill behind the sharp feed, fills the letterbox/pillarbox gaps with a
        // heavily-blurred, screen-filling copy of the video so the outer edges read as a soft
        // frosted-glass extension of the scene rather than black bars. The sharp letterboxed feed
        // draws on top, so only the gaps ever show this.
        if (displayMode != DisplayMode.MAP_ONLY) {
            blurFill?.let { bmp ->
                Image(
                    bitmap = bmp, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(40.dp),
                )
                // Light frost over the blurred fill so the side gaps always read as pale frosted
                // glass, never a dark smear even when the feed's edge happens to be dark.
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.16f)))
            }
        }

        // ── Live camera feed (full screen, TextureView for correct color compositing) ──
        // Map-only casting hides this entirely rather than just drawing over it, the decoder
        // keeps running underneath regardless (attachVideoSurface/detachVideoSurface only fire
        // on this TextureView's own surface lifecycle), so switching back to any other mode
        // resumes showing live video immediately, no reconnect needed.
        if (displayMode != DisplayMode.MAP_ONLY) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        textureViewRef = this
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                vm.attachVideoSurface(android.view.Surface(st))
                                // w/h are the SurfaceTexture dims == view dims set by TextureView.applyUpdate().
                                // Letterbox-fit using whatever resolution is known so far (falls back to the
                                // 1280x720 hint until the decoder reports the real negotiated size, at which
                                // point the LaunchedEffect above re-applies this with the true aspect ratio).
                                scaleVideoToFit(this@apply, videoRes)
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, newW: Int, newH: Int) {
                                scaleVideoToFit(this@apply, videoRes)
                            }
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                vm.detachVideoSurface(); return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                // NO glassSource/haze on the video: marking a TextureView as a Haze source wraps
                // it in a capture graphicsLayer it can't composite into cleanly, which blurred the
                // ENTIRE live feed (not just behind the panels). The glass panels fall back to a
                // plain tint, a sharp, readable feed beats frosted panels for a pilot.
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Always visible regardless of DisplayMode, otherwise switching to Minimal/Camera/Map
        // would strand the pilot with no way back to Full without leaving the flight screen.
        // Bottom-start (above the takeoff button, below the speed tape's own labels) rather than
        // a top corner, the top bar's own status pills (PREVIEW/GPS/etc) already occupy every
        // top corner, and being drawn later in the same parent Box, they'd win every tap in a
        // shared region regardless of this control's own bounds.
        CastControl(
            displayMode = displayMode, onModeChange = { displayMode = it },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 112.dp),
        )

        // ── Vision/obstacle radar (cmd 0x6a), drawn as ephemeral glows hugging the four
        // screen edges rather than a boxed widget, so it reads ambiently instead of competing
        // for attention with the rest of the HUD. Sits above the video, below every other
        // control (declared before the cutout-safe Box below). Only relevant to actually flying
        // the aircraft, so it's part of the same FULL-only chrome as the flight controls below, 
        // Minimal/Camera/Map casting modes are for spectators, not obstacle avoidance. ──
        val obstacle by vm.obstacle.collectAsState()
        val radarRing by vm.radarRing.collectAsState()
        val obstacleDensity = LocalDensity.current
        if (displayMode == DisplayMode.FULL) ObstacleEdgeGlow(
            obstacle,
            frameTopPx = with(obstacleDensity) { HUD_TOP_BAND.toPx() },
            frameBottomPx = with(obstacleDensity) { HUD_BOTTOM_BAND.toPx() },
            showRing = radarRing,
            modifier = Modifier.fillMaxSize(),
        )

        // ── ActiveTrack / TapFly, see ActiveTrackController.kt / TapFlyController.kt for the
        // control loops. tapFlyArmPending is UI-only state: TapFlyController.start() needs the
        // actual tap coordinate up front, so "armed, waiting for the next video tap" has nowhere
        // to live in the controller itself. ──
        val activeTrackStatus by vm.activeTrack.status.collectAsState()
        val tapFlyStatus by vm.tapFly.status.collectAsState()
        var tapFlyArmPending by remember { mutableStateOf(false) }
        LaunchedEffect(tapFlyStatus.mode) { if (tapFlyStatus.mode == TapFlyMode.FLYING) tapFlyArmPending = false }

        // Tap-to-select (ActiveTrack) / tap-to-set-bearing (TapFly) / tap-to-cancel (TapFly
        // in flight) on the video. Declared BEFORE the button/HUD layer below on purpose, this
        // Box only actually consumes a tap while one of those is relevant, so an ordinary tap
        // that lands on a HUD button still gets it (buttons are declared later = higher hit-test
        // priority, same convention RightCameraPanel's own doc comment below relies on); this one
        // only ever sees taps that land on otherwise-empty video.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(activeTrackStatus.mode, tapFlyArmPending, tapFlyStatus.mode) {
                    val wantsTap = activeTrackStatus.mode == ActiveTrackMode.SEARCHING ||
                        tapFlyArmPending || tapFlyStatus.mode == TapFlyMode.FLYING
                    if (!wantsTap) return@pointerInput
                    detectTapGestures { offset ->
                        val xFrac = (offset.x / size.width).coerceIn(0f, 1f)
                        val yFrac = (offset.y / size.height).coerceIn(0f, 1f)
                        when {
                            activeTrackStatus.mode == ActiveTrackMode.SEARCHING ->
                                vm.selectActiveTrackTarget(xFrac, yFrac)
                            tapFlyArmPending -> {
                                vm.startTapFly(xFrac)
                                tapFlyArmPending = false
                            }
                            tapFlyStatus.mode == TapFlyMode.FLYING -> vm.stopTapFly()
                        }
                    }
                },
        )

        // ── Gimbal control: long-press then drag = a virtual joystick that sets gimbal SPEED
        // (DJI GO 4's confirmed 0x04/0x0c "Gimbal Ext Ctrl Accel", captured 2026-07-05). Speed, not
        // absolute angle, is why this is smooth where the old absAngle version was jumpy. Big
        // CARDINAL deadzones (like GO 4): a drag within 30° of an axis snaps to pure yaw or pure
        // pitch, so isolating up/down or left/right is easy; only the 30–60° diagonal wedge moves
        // both. Squared expo on displacement (GO 4's own curve) gives a soft centre and fast edges.
        // Double-tap recenters forward; single tap = spot AF (AF mode only). Long-press requirement
        // keeps a normal swipe (e.g. the map PIP) from ever nudging the gimbal, the old crash. ──
        val wantsVisionTap = activeTrackStatus.mode == ActiveTrackMode.SEARCHING ||
            tapFlyArmPending || tapFlyStatus.mode == TapFlyMode.FLYING
        if (displayMode == DisplayMode.FULL && droneLinked && !wantsVisionTap) {
            val density = LocalDensity.current
            var focusPoint by remember { mutableStateOf<Offset?>(null) }
            var focusTick by remember { mutableStateOf(0) }
            val reticle = remember { Animatable(0f) }
            LaunchedEffect(focusTick) {
                if (focusTick > 0) { reticle.snapTo(0f); reticle.animateTo(1f, tween(650)) }
            }
            var gimbalOrigin by remember { mutableStateOf<Offset?>(null) }
            var gimbalDefl by remember { mutableStateOf(Offset.Zero) }   // snapped, for the reticle
            val ringPx = with(density) { 46.dp.toPx() }
            // Pinch-zoom idle-stop: 200 ms after the last pinch step, send optics-zoom STOP.
            var lastZoomMs by remember { mutableStateOf(0L) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(120)
                    if (lastZoomMs != 0L && System.currentTimeMillis() - lastZoomMs > 200) {
                        vm.zoomStop(); lastZoomMs = 0L
                    }
                }
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(manualFocus) {
                        detectTapGestures(
                            onDoubleTap = { vm.gimbalForward() },
                            onTap = if (!manualFocus) {
                                { off: Offset ->
                                    vm.tapFocus((off.x / size.width).coerceIn(0f, 1f),
                                                (off.y / size.height).coerceIn(0f, 1f))
                                    focusPoint = off; focusTick++
                                }
                            } else null,
                        )
                    }
                    // Pinch-to-zoom (Mavic 2 ZOOM optical zoom; no-op on the Pro). Two-finger pinch
                    // drives the continuous optics-zoom command; a 200 ms idle sends STOP. Doesn't
                    // conflict with the 1-finger tap/long-press-drag gimbal gestures above.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom > 1.012f) { vm.zoomStart(inDir = true); lastZoomMs = System.currentTimeMillis() }
                            else if (zoom < 0.988f) { vm.zoomStart(inDir = false); lastZoomMs = System.currentTimeMillis() }
                        }
                    }
                    .pointerInput(Unit) {
                        val deadzonePx = with(density) { 14.dp.toPx() }
                        val maxPx = with(density) { 200.dp.toPx() }
                        fun spd(px: Float): Int {
                            val n = (px / maxPx).coerceIn(-1f, 1f)
                            return (kotlin.math.sign(n) * n * n * 1600f).toInt()   // squared expo
                        }
                        detectDragGesturesAfterLongPress(
                            onDragStart = { gimbalOrigin = it; gimbalDefl = Offset.Zero },
                            onDragEnd = { gimbalOrigin = null; gimbalDefl = Offset.Zero; vm.gimbalStop() },
                            onDragCancel = { gimbalOrigin = null; gimbalDefl = Offset.Zero; vm.gimbalStop() },
                        ) { change, _ ->
                            change.consume()
                            val origin = gimbalOrigin
                            if (origin != null) {
                                var d = change.position - origin
                                if (kotlin.math.hypot(d.x, d.y) < deadzonePx) {
                                    gimbalDefl = Offset.Zero; vm.gimbalStop()
                                } else {
                                    // Cardinal snap: pure axis unless in the diagonal wedge (30–60°).
                                    val ang = kotlin.math.atan2(kotlin.math.abs(d.y), kotlin.math.abs(d.x))
                                    if (ang < (Math.PI / 6).toFloat()) d = Offset(d.x, 0f)          // → yaw
                                    else if (ang > (Math.PI / 3).toFloat()) d = Offset(0f, d.y)     // → pitch
                                    gimbalDefl = d
                                    // Drag up (−y) tilts up; drag right (+x) pans right.
                                    vm.gimbalSpeed(pitch = spd(-d.y), yaw = spd(d.x))
                                }
                            }
                        }
                    },
            ) {
                val pt = focusPoint
                val prog = reticle.value
                if (pt != null && prog < 1f) {
                    Canvas(Modifier.fillMaxSize()) {
                        val half = 46.dp.toPx() * (1f - 0.30f * prog)
                        val col = DjiGreen.copy(alpha = 1f - prog)
                        val sw = 2.dp.toPx()
                        val arm = half * 0.42f
                        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
                            val cx = pt.x + sx * half; val cy = pt.y + sy * half
                            drawLine(col, Offset(cx, cy), Offset(cx - sx * arm, cy), strokeWidth = sw)
                            drawLine(col, Offset(cx, cy), Offset(cx, cy - sy * arm), strokeWidth = sw)
                        }
                    }
                }
                // Bespoke gimbal reticle, a joystick ring at the long-press origin, cardinal ticks,
                // and a deflection dot that snaps to the active axis. Only while dragging.
                val origin = gimbalOrigin
                if (origin != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val col = DjiGreen
                        val dark = Color.Black.copy(alpha = 0.55f)
                        // Ring (dark under-stroke for legibility over bright video, then green).
                        drawCircle(dark, radius = ringPx, center = origin, style = Stroke(width = 4.dp.toPx()))
                        drawCircle(col.copy(alpha = 0.85f), radius = ringPx, center = origin, style = Stroke(width = 2.dp.toPx()))
                        // Cardinal ticks, the snap axes; highlight whichever axis is active.
                        val pureYaw = gimbalDefl.x != 0f && gimbalDefl.y == 0f
                        val purePitch = gimbalDefl.y != 0f && gimbalDefl.x == 0f
                        val tick = 8.dp.toPx()
                        fun axisTick(dx: Float, dy: Float, hot: Boolean) {
                            val a = Offset(origin.x + dx * ringPx, origin.y + dy * ringPx)
                            val b = Offset(origin.x + dx * (ringPx + tick), origin.y + dy * (ringPx + tick))
                            drawLine(if (hot) col else col.copy(alpha = 0.4f), a, b, strokeWidth = if (hot) 3.dp.toPx() else 1.5.dp.toPx())
                        }
                        axisTick(1f, 0f, pureYaw); axisTick(-1f, 0f, pureYaw)
                        axisTick(0f, 1f, purePitch); axisTick(0f, -1f, purePitch)
                        // Centre dot + deflection dot (clamped to the ring), with a connecting line.
                        drawCircle(col.copy(alpha = 0.5f), radius = 3.dp.toPx(), center = origin)
                        val mag = kotlin.math.hypot(gimbalDefl.x, gimbalDefl.y)
                        if (mag > 1f) {
                            val k = (ringPx / mag).coerceAtMost(1f)
                            val dot = Offset(origin.x + gimbalDefl.x * k, origin.y + gimbalDefl.y * k)
                            drawLine(col.copy(alpha = 0.7f), origin, dot, strokeWidth = 2.dp.toPx())
                            drawCircle(dark, radius = 7.dp.toPx(), center = dot)
                            drawCircle(col, radius = 5.dp.toPx(), center = dot)
                        }
                    }
                }
            }
        }

        // AF / MF focus buttons. AF = continuous autofocus (tap the feed to spot-focus); MF = manual
        // (holds the last focus). The active mode is highlighted green. These belong with the
        // camera controls (they're a camera setting), so they sit at the top-right directly under
        // the capture-button cluster (RightCameraPanel) rather than orphaned on the bottom-left.
        if (displayMode == DisplayMode.FULL && droneLinked) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 176.dp, end = 58.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(width = 34.dp, height = 28.dp)
                        .glass(shape = RoundedCornerShape(6.dp), baseAlpha = if (!manualFocus) 0.4f else 0.18f)
                        .clickable { manualFocus = false; vm.setCameraFocus(2) },
                    contentAlignment = Alignment.Center,
                ) { Text("AF", color = if (!manualFocus) DjiGreen else TextSec, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier.size(width = 34.dp, height = 28.dp)
                        .glass(shape = RoundedCornerShape(6.dp), baseAlpha = if (manualFocus) 0.4f else 0.18f)
                        .clickable { manualFocus = true; vm.setCameraFocus(0) },
                    contentAlignment = Alignment.Center,
                ) { Text("MF", color = if (manualFocus) DjiGreen else TextSec, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // This used to inset the WHOLE HUD box by the display cutout, which shrinks every edge
        // of the usable screen uniformly even though a punch-hole camera only ever occupies a
        // small band at the top-center. The video feed already goes full-bleed behind the hole;
        // every other control here should too, except the one row that actually lives in the
        // same vertical band as the hole, the top bar, which gets its own top-only cutout
        // inset below instead of dragging the side tapes/bottom compass/camera panel inward
        // with it. ──
        Box(Modifier.fillMaxSize()) {
        // Every Text in the HUD layer gets an omnidirectional dark GLOW (a centered halo, offset
        // 0,0, not a directional drop shadow). With the glass fully clear, this halo is the only
        // thing keeping text readable, and it has to work over ANY camera content: bright sky,
        // white wall, dark ground, foliage. A soft black halo around bright text gives a
        // dark→light edge on every side of every glyph, so it separates from a light background
        // AND stays visible on a dark one, without sampling the video (which would cost a
        // GPU→CPU read per frame and flicker as the scene moves). Large blur + near-opaque black
        // is the "subtitle over any footage" trick. Canvas-drawn labels (tape ticks, compass)
        // carry the same via Paint.setShadowLayer at 0,0.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                shadow = Shadow(Color.Black.copy(alpha = 0.95f), Offset(0f, 0f), blurRadius = 9f),
            ),
        ) {

        // ── ONE shared glass surface for the top bar + both tapes + compass, drawn FIRST so
        // everything else in this Box renders its own content on top of it. See HudGlassSurface/
        // HudFrameShape's doc comments: this replaced 4 separately-backgrounded panels + a
        // decorative outline, which never actually read as "welded" no matter how the seam
        // itself was tuned, with one real continuous surface (one clip, one blur, one fill).
        // Real configured ceiling from the Device tab's Flight Limits read (see FlyC.ParamHash),
        // not a hardcoded 120m, falls back to DJI's common regional default only if that
        // hasn't been read yet, or came back in a byte width/range this can't sanity-check.
        // Computed here (not down by AltitudeTape like before) because this surface needs it
        // too, and it's cheap enough to just share the one value.
        val flightLimits by vm.flightLimits.collectAsState()
        val maxHeightM = flightLimits[FlyC.ParamHash.MAX_HEIGHT]?.let { raw ->
            val parsed = when (raw.size) {
                4 -> java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).float
                2 -> java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toFloat()
                else -> Float.NaN
            }
            parsed.takeIf { it.isFinite() && it in 10f..500f }
        } ?: 120f
        // Instrument chrome (this glass frame, top bar, warnings, tapes, compass, status tray), 
        // shown in FULL and MINIMAL; Camera-only/Map-only casting is meant to be a clean
        // spectator view with no HUD clutter at all.
        val showInstruments = displayMode == DisplayMode.FULL || displayMode == DisplayMode.MINIMAL
        // Obstacle warning bled into the frame bands, same color/closeness language as the domes
        // (ObstacleEdgeGlow), so the whole HUD frame reacts to a close object, not just the domes.
        val obWarn: (Int?) -> Pair<Color, Float>? = { v ->
            if (obstacle.valid && v != null) {
                val c = when { v < 50 -> DjiRed; v < 150 -> DjiAmber; else -> DjiGreen }
                c to (1f - (v.coerceIn(0, 300) / 300f))
            } else null
        }
        if (showInstruments) HudGlassSurface(
            speedColor = if (drone.speed < 0.5f) TextSec else if (drone.speed > 15f) DjiAmber else DjiGreen,
            altColor = when {
                drone.altRel < 5f -> DjiRed
                drone.altRel > maxHeightM -> DjiRed
                drone.altRel > maxHeightM * 0.85f -> DjiAmber
                drone.altRel < 15f -> DjiAmber
                else -> DjiGreen
            },
            headingColor = headingColor((((if (drone.yaw.isFinite()) drone.yaw else 0f) % 360f) + 360f) % 360f),
            hazeState = hazeState,
            blurFill = blurFill,
            frontWarn = obWarn(obstacle.frontClosest),
            backWarn = obWarn(obstacle.backClosest),
            modifier = Modifier.fillMaxSize(),
        )

        // ── Top status bar ─────────────────────────────────────────────────
        val ambientTempC by vm.ambientTempC.collectAsState()
        val weatherNow by vm.weather.collectAsState()
        val isRecording by vm.isRecording.collectAsState()
        val ledMode by vm.ledMode.collectAsState()
        val currentAirspace by vm.currentAirspace.collectAsState()
        val homePoint by vm.homePoint.collectAsState()
        // drone.homeDist is a DroneState field that's never actually populated by any decoded
        // DUML frame (always 0), computing it from the same home-point/haversine pair already
        // used for DualGps's "distance to pilot" is real data instead of a silent always-zero.
        val homeDistM = homePoint?.let { if (drone.hasGpsFix) haversineM(it.first, it.second, drone.lat, drone.lon).toFloat() else null }
        val (phoneBattPct, phoneBattTempC) = rememberPhoneBatteryInfo()
        if (showInstruments) TopBar(
            // Only this bar dodges the cutout, top-only, so it doesn't also eat into the
            // left/right edges where there's no hole to avoid.
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)),
            hazeState = hazeState,
            connected = app.connected,
            previewMode = app.host == "preview",
            droneLinked = droneLinked,
            battPct  = drone.battPct,
            battMv   = drone.battMv,
            battTempC = drone.battTempC,
            phoneBattPct = phoneBattPct,
            phoneBattTempC = phoneBattTempC,
            windMps = weatherNow?.windMps,
            windFromDeg = weatherNow?.windFromDeg,
            windGustMps = weatherNow?.windGustMps,
            weatherTempC = weatherNow?.tempC,
            hasGpsFix = drone.hasGpsFix,
            gpsSats = drone.gpsSats,
            flycState = drone.flycState,
            homeDistM = homeDistM,
            isRecording = isRecording,
            ledMode = ledMode,
            obstacleValid = obstacle.valid,
            currentAirspace = currentAirspace,
            timer    = timer,
            estopEnabled = app.connected,
            onEstop  = { vm.emergencyStop() },
            statusTrayOpen = statusTrayOpen,
            onToggleStatusTray = { statusTrayOpen = !statusTrayOpen },
        )

        // ── Warnings (signal loss / battery / RTH), top-center under the bar ──
        // Height tracked live (not a guessed fixed value) so VisionModeOverlay's own banner
        // below can stack under it dynamically, the warnings list can be 0, 1, or several
        // stacked pills, and a fixed offset would either overlap it or leave a gap when it's
        // shorter/taller/absent.
        var warningsBannerHeightPx by remember { mutableStateOf(0) }
        if (showInstruments) WarningsBanner(warnings, onDismissLowBattery = { vm.dismissLowBatteryWarning() },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 42.dp)
                .onGloballyPositioned { warningsBannerHeightPx = if (warnings.isEmpty()) 0 else it.size.height })

        // ── Drone + pilot(phone) GPS, top-left ──────────────────────────────
        // ── Airspeed tape (left edge) / Altitude tape (right edge), full-height aircraft-PFD
        // style instruments, declared before the GPS/camera panels below so those still render
        // on top of the tape where they'd otherwise overlap near the top of the screen.
        //
        // Neither tape shifts as a whole block anymore, a fixed/whole-panel shift away from
        // the cutout reads as "the screen got smaller," which is exactly the complaint that
        // killed the first version of this. Instead each tape gets the cutout's exact rect
        // (live from the OS, not a hand-maintained per-device database, see
        // rememberCutoutRectPx's doc comment) and nudges only the individual number labels that
        // would otherwise render underneath it; the tape's own bounding box, and everything else
        // on screen, stays exactly where it always was. ──
        val cutoutPx = rememberCutoutRectPx()
        if (showInstruments) SpeedTape(drone.speed, hazeState, cutoutPx, modifier = Modifier.align(Alignment.CenterStart))

        // maxHeightM computed up top now (HudGlassSurface needs it too), reused here as-is.
        if (showInstruments) AltitudeTape(drone.altRel, hazeState, cutoutPx, maxHeightM, modifier = Modifier.align(Alignment.CenterEnd))

        if (showInstruments && statusTrayOpen) {
            StatusTray(
                // High zIndex so the tray is a true top-layer overlay, it's declared before the
                // flight controls, so without this it rendered UNDERNEATH the takeoff button.
                modifier = Modifier.zIndex(30f).align(Alignment.TopStart).padding(top = 42.dp, start = 60.dp),
                droneLat = drone.lat, droneLon = drone.lon, droneFix = drone.hasGpsFix, phone = phone,
                battTempC = drone.battTempC, ambientTempC = ambientTempC,
                connected = app.connected, droneLinked = droneLinked, previewMode = app.host == "preview",
                battPct = drone.battPct, battMv = drone.battMv,
                ledMode = ledMode, obstacleValid = obstacle.valid,
                flycState = drone.flycState, homeDistM = homeDistM,
                currentAirspace = currentAirspace,
                hazeState = hazeState,
            )
        }

        // ── Compass / heading, a full-width tape flush with the true bottom edge, matching
        // the aircraft-PFD look of the speed/altitude tapes instead of a small floating ball.
        // 56dp start/end padding, exactly the tapes' own width, not a wider margin, so this
        // bar's ends touch the tapes' inner edges with no gap: welded, not just adjacent.
        // The flight-action row's own bottom padding (below) is raised to clear it. ──
        if (showInstruments) HeadingTape(drone.yaw, hazeState,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 56.dp, end = 56.dp))

        // ── Attitude / artificial horizon (pitch + roll relative to earth), centered over the
        // video. Heading (compass, above) says where the nose points; this says how the airframe
        // is tilted. FULL-only chrome like the flight controls: it's a piloting instrument, not
        // something a spectator casting view needs. ──
        if (displayMode == DisplayMode.FULL) AttitudeIndicator(
            pitch = drone.pitch, roll = drone.roll,
            heading = (((if (drone.yaw.isFinite()) drone.yaw else 0f) % 360f) + 360f) % 360f,
            speedColor = if (drone.speed < 0.5f) TextSec else if (drone.speed > 15f) DjiAmber else DjiGreen,
            altColor = when {
                drone.altRel < 5f -> DjiRed
                drone.altRel > maxHeightM -> DjiRed
                drone.altRel > maxHeightM * 0.85f -> DjiAmber
                drone.altRel < 15f -> DjiAmber
                else -> DjiGreen
            },
            modifier = Modifier.align(Alignment.Center),
        )

        // ── Flight actions (floating, no obscuring bottom bar) ── FULL only: these command the
        // aircraft, which has no place in a Minimal/Camera/Map spectator view. ──
        val inAir = drone.inAir   // real FLYC in-air bit, not baro altitude
        if (displayMode == DisplayMode.FULL) Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // start/end clear the 56.dp speed/altitude tapes at each edge, this used to be
                // 16.dp, which sat the take-off button (and RTH/settings) right on top of them.
                // bottom clears the heading tape, now flush with the true bottom edge.
                .padding(bottom = 56.dp, start = 64.dp, end = 64.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Guarded flight-control switch: flip the safety guard, then drag the full
            // travel to take off / land (label tracks actual flight state)
            // Take-off / land require a LIVE drone link (fresh telemetry), not merely an
            // open transport, otherwise the switch can arm over a dead connection and a
            // command is sent into the void (the original failed flight test).
            //
            // Once airborne the full-width slider collapses to a small "LAND" pill to give
            // the screen space back, tapping it re-expands the guarded slider rather than
            // landing immediately, so committing to land still takes the same deliberate
            // flip-then-drag gesture as before.
            var landExpanded by remember { mutableStateOf(false) }
            LaunchedEffect(inAir) { if (!inAir) landExpanded = false }

            when {
                inAir && landExpanded -> GuardedSlideSwitch(
                    label = "LAND", color = DjiAmber, enabled = droneLinked,
                    slideUp = false, length = 230.dp, hazeState = hazeState,
                ) { vm.autoLand() }
                inAir -> SmallBtn("▼ LAND", DjiAmber, droneLinked) { landExpanded = true }
                else -> TakeoffPill(enabled = droneLinked, hazeState = hazeState) { showTakeoffModal = true }
            }

            if (app.copilotMode == CopilotMode.AI_ASSISTED) CoPilotPttButton(vm, hazeState)
        }

        // ── Map / RTH / Menu cluster, bunched together right against the compass instead of
        // floating separately with a big gap above it (Map used to be its own button over by
        // the takeoff/land slider; RTH/Menu used to sit 56dp above the true bottom edge). Map is
        // on top of Menu per request. Glass-styled like the rest of the HUD, one shared panel
        // rather than 3 separate pill buttons. ──
        // Once airborne the take-off pill collapses to a small LAND button, freeing the
        // bottom-centre. The whole map/RTH/menu assembly slides LEFT into that space so it sits
        // close to the HUD centre in flight, and slides back out to the corner on the ground.
        // Base position nudged down + left from the old end=64/bottom=38 per request.
        val navSlide by animateDpAsState(if (inAir) 130.dp else 0.dp, tween(400), label = "navSlide")
        if (displayMode == DisplayMode.FULL) NavCluster(
            mapShown = mapSize != MapSize.HIDDEN,
            onToggleMap = { mapSize = if (mapSize == MapSize.HIDDEN) MapSize.SMALL else MapSize.HIDDEN },
            onRth = { vm.sendRth() },
            rthEnabled = droneLinked,
            onOpenSettings = onOpenSettings,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 80.dp + navSlide, bottom = 34.dp),
        )

        // ── ActiveTrack / TapFly entry points, stacked directly above the Map/RTH/Menu
        // cluster (same end=64dp column, clearing the altitude tape), rather than a third
        // floating pill elsewhere. Exact placement (164dp up from the bottom) is a judgment
        // call, not verified against a real device screen. ──
        if (displayMode == DisplayMode.FULL) VisionModesCluster(
            activeTrackMode = activeTrackStatus.mode,
            tapFlyMode = tapFlyStatus.mode,
            tapFlyArmPending = tapFlyArmPending,
            enabled = droneLinked,
            onToggleActiveTrack = {
                if (activeTrackStatus.mode == ActiveTrackMode.OFF) {
                    tapFlyArmPending = false   // only one of the two vision modes armed at a time
                    textureViewRef?.let { vm.armActiveTrack(it) }
                } else {
                    vm.stopActiveTrack()
                }
            },
            onToggleTapFly = {
                when {
                    tapFlyStatus.mode == TapFlyMode.FLYING -> vm.stopTapFly()
                    tapFlyArmPending -> tapFlyArmPending = false
                    else -> {
                        vm.stopActiveTrack()   // only one of the two vision modes armed at a time
                        tapFlyArmPending = true
                    }
                }
            },
            hazeState = hazeState,
            // Same left-slide as the nav cluster below it so the two stay a single aligned column.
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 80.dp + navSlide, bottom = 160.dp),
        )

        // ── Camera settings (top-right) ── declared AFTER the flight-action row (RTH/settings
        // gear) on purpose: when the tray is open it can extend down far enough to visually
        // overlap those buttons, and Compose gives tap priority to whichever composable was
        // declared later in the same parent, this used to be declared earlier, so taps on the
        // tray's bottom rows (Focus/AE) were silently swallowed by RTH/settings underneath. ──
        // No cutout handling needed here anymore, the altitude tape it used to overlap no
        // longer shifts as a whole block (see above), so this panel's original fixed position
        // is correct again unconditionally.
        if (displayMode == DisplayMode.FULL) RightCameraPanel(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 58.dp),
            vm = vm,
            hazeState = hazeState,
        )

        // ── Resizable flight map (DJI-style corner PIP → fullscreen) ── show/hide is normally
        // driven by the NavCluster's 🗺 button above, bunched with RTH/Menu near the compass, 
        // Map-only casting overrides that entirely to force it fullscreen regardless of the
        // pilot's own PIP size/visibility choice on the phone, and Camera-only hides it outright
        // (a spectator "just the camera" view showing a map PIP over it would defeat the point).
        if (displayMode != DisplayMode.CAMERA_ONLY) FlightMapContainer(
            vm, if (displayMode == DisplayMode.MAP_ONLY) MapSize.FULL else mapSize,
            onSize = { mapSize = it }, phoneLocation = phone,
        )

        // Drawn last so it sits above the entire HUD, including the map.
        if (displayMode == DisplayMode.FULL && showTakeoffModal) {
            TakeoffConfirmModal(
                enabled = droneLinked,
                hazeState = hazeState,
                onConfirm = { vm.autoTakeoff(); showTakeoffModal = false },
                onCancel = { showTakeoffModal = false },
            )
        }

        // ── ActiveTrack bbox + TRACKING/TAPFLY status banner + STOP button. Drawn late so it
        // sits above the rest of the HUD; NOT above CaptureCueOverlay's flash (that's rare/
        // instant, this needs to stay visible/tappable throughout the whole mode). ──
        if (displayMode == DisplayMode.FULL) VisionModeOverlay(
            activeTrackStatus = activeTrackStatus,
            tapFlyStatus = tapFlyStatus,
            tapFlyArmPending = tapFlyArmPending,
            onCancelActiveTrack = { vm.stopActiveTrack() },
            onCancelTapFly = { tapFlyArmPending = false; vm.stopTapFly() },
            warningsBannerHeightPx = warningsBannerHeightPx,
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize(),
        )

        // ── Always-on BETA version tag, a beta build must announce its version at all times, in
        // every display mode (even the clean Camera/Map spectator views that hide the rest of the
        // chrome), so a bug report or field test can always be tied to an exact build. Placed
        // bottom-CENTER in the narrow gap between the compass tape and the take-off control: the
        // rounded display corners (~153px radius) clip anything in a corner, and the take-off
        // button owns bottom-center lower down, so this centered sliver is the one always-clear
        // spot. Fully rounded pill. ──
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 38.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "v${BuildConfig.VERSION_NAME.replace('-', ' ')}",
                color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono,
            )
        }

        // ── RC button-press banner, when a physical controller button is pressed, announce which
        // button and what it's bound to at the top of the HUD. Toggles that latch ON (landing
        // light, record, PTT-held) stay up; momentary ones fade after a couple seconds. ──
        RcButtonFlashOverlay(vm, Modifier.align(Alignment.TopCenter).padding(top = 44.dp))

        // ── Capture confirmation, a photo/record command fired from a physical RC button has
        // no other feedback path (the pilot's eyes are on the sky, not the settings tray), so
        // this fires for on-screen taps too rather than special-casing the RC path. Drawn last:
        // topmost of the whole HUD layer, above even the takeoff modal. ──
        CaptureCueOverlay(vm, Modifier.fillMaxSize())
        } // end drop-shadowed text scope
        } // end full-bleed HUD layer (only TopBar insets for the cutout, see above)
    }
}

/** Map / RTH / Menu, bunched into one small glass-styled vertical stack instead of 3 separate
 *  floating pills scattered around the screen, Map on top of Menu per request, all close
 *  against the compass tape rather than 56dp+ above it. */
@Composable
private fun NavCluster(
    mapShown: Boolean, onToggleMap: () -> Unit,
    onRth: () -> Unit, rthEnabled: Boolean,
    onOpenSettings: () -> Unit,
    hazeState: HazeState?, modifier: Modifier,
) {
    Column(
        modifier.glass(shape = RoundedCornerShape(10.dp), baseAlpha = 0.22f, haze = hazeState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NavClusterIcon(R.drawable.ic_hud_map, if (mapShown) DjiGreen else TextSec, true, onToggleMap)
        NavClusterIcon(R.drawable.ic_hud_rth, DjiAmber, rthEnabled, onRth)
        NavClusterIcon(R.drawable.ic_hud_menu, Gold, true, onOpenSettings)
    }
}

@Composable
private fun NavClusterIcon(iconRes: Int, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes), contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) color else color.copy(alpha = 0.35f)),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** ActiveTrack / TapFly arm buttons, same glass-cluster styling as [NavCluster], text glyphs
 *  rather than dedicated icon drawables (neither mode has art yet). Color tracks state: dim/idle,
 *  amber while armed-and-waiting-for-a-tap, green while actually locked-on/flying. */
@Composable
private fun VisionModesCluster(
    activeTrackMode: ActiveTrackMode,
    tapFlyMode: TapFlyMode,
    tapFlyArmPending: Boolean,
    enabled: Boolean,
    onToggleActiveTrack: () -> Unit,
    onToggleTapFly: () -> Unit,
    hazeState: HazeState?,
    modifier: Modifier,
) {
    Column(
        modifier.glass(shape = RoundedCornerShape(10.dp), baseAlpha = 0.22f, haze = hazeState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val atColor = when (activeTrackMode) {
            ActiveTrackMode.OFF -> TextSec
            ActiveTrackMode.SEARCHING -> DjiAmber
            ActiveTrackMode.LOCKED -> DjiGreen
        }
        val tfColor = when {
            tapFlyMode == TapFlyMode.FLYING -> DjiGreen
            tapFlyArmPending -> DjiAmber
            else -> TextSec
        }
        VisionModeIcon(R.drawable.ic_hud_activetrack, atColor, enabled, onToggleActiveTrack)
        VisionModeIcon(R.drawable.ic_hud_tapfly, tfColor, enabled, onToggleTapFly)
    }
}

@Composable
private fun VisionModeIcon(iconRes: Int, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes), contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) color else color.copy(alpha = 0.35f)),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Locked-subject bbox + a single status/cancel banner shared by both modes (never more than one
 *  of ActiveTrack/TapFly can be active at a time, see FlightViewModel's mutual-exclusion doc
 *  comment). [ActiveTrackStatus.bboxFrac] is already in the TextureView's own displayed-fraction
 *  space, see ActiveTrackController's doc comment on why no second letterbox correction is
 *  needed on top of that. */
@Composable
private fun VisionModeOverlay(
    activeTrackStatus: ActiveTrackStatus,
    tapFlyStatus: TapFlyStatus,
    tapFlyArmPending: Boolean,
    onCancelActiveTrack: () -> Unit,
    onCancelTapFly: () -> Unit,
    warningsBannerHeightPx: Int,
    hazeState: HazeState?,
    modifier: Modifier,
) {
    Box(modifier) {
        if (activeTrackStatus.mode == ActiveTrackMode.LOCKED) {
            activeTrackStatus.bboxFrac?.let { b ->
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        color = DjiGreen,
                        topLeft = Offset(b.left * size.width, b.top * size.height),
                        size = Size((b.right - b.left) * size.width, (b.bottom - b.top) * size.height),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }

        val banner: Pair<String, Color>? = when {
            activeTrackStatus.mode == ActiveTrackMode.LOCKED ->
                "● TRACKING, holding position, panning to subject" to DjiGreen
            activeTrackStatus.mode == ActiveTrackMode.SEARCHING ->
                activeTrackStatus.label to DjiAmber
            tapFlyArmPending -> "TapFly armed, tap the video to set a bearing" to DjiAmber
            tapFlyStatus.mode == TapFlyMode.FLYING ->
                "● ${tapFlyStatus.label} (${tapFlyStatus.elapsedMs / 1000}s)" to DjiGreen
            else -> null
        }
        val onCancel: (() -> Unit)? = when {
            activeTrackStatus.mode != ActiveTrackMode.OFF -> onCancelActiveTrack
            tapFlyArmPending || tapFlyStatus.mode == TapFlyMode.FLYING -> onCancelTapFly
            else -> null
        }
        // 92.dp clears the top bar itself; if WarningsBanner (also TopCenter, starting at
        // top=42.dp) is showing 1+ warnings, its real measured height (passed in, not a guessed
        // fixed value, the warnings list can be short or stack several pills) pushes this
        // banner further down so the two never overlap, growing/shrinking with it live.
        if (banner != null) {
            val (label, color) = banner
            val density = LocalDensity.current
            val topPad = 92.dp + with(density) { warningsBannerHeightPx.toDp() }
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = topPad)
                    .glass(shape = RoundedCornerShape(10.dp), tint = color, baseAlpha = 0.24f, haze = hazeState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp))
                if (onCancel != null) {
                    TextButton(onClick = onCancel) {
                        Text("STOP", color = DjiRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Small semi-transparent trigger button, DJI GO 4-style compact auto-takeoff affordance.
 *  Opens [TakeoffConfirmModal] rather than arming directly. */
@Composable
private fun TakeoffPill(enabled: Boolean, hazeState: HazeState? = null, onClick: () -> Unit) {
    Box(Modifier.glass(shape = RoundedCornerShape(20.dp), tint = DjiGreen, baseAlpha = 0.18f, haze = hazeState)) {
        TextButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = DjiGreen),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("▲  TAKE OFF", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }
}

/** Push-to-talk co-pilot button, hold to ask a question, release to send it. Requests
 *  RECORD_AUDIO on first press rather than at app launch, since the co-pilot is opt-in and
 *  most pilots who never enable it should never see a mic permission prompt at all. Shows the
 *  live transcript while listening and the answer once it comes back, in a glass pill above
 *  the button so it doesn't collide with anything else on the HUD. */
@Composable
private fun CoPilotPttButton(vm: FlightViewModel, hazeState: HazeState? = null) {
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val listening by vm.coPilotListening.collectAsState()
    val thinking by vm.coPilotThinking.collectAsState()
    val transcript by vm.coPilotTranscript.collectAsState()
    val answer by vm.coPilotAnswer.collectAsState()

    Box {
        // Transcript/answer pill, floats above the mic button so it never gets clipped by
        // the bottom edge, only shown while there's something to show.
        val message = when {
            listening -> transcript.ifBlank { "Listening…" }
            thinking -> "Thinking…"
            answer.isNotBlank() -> answer
            else -> null
        }
        if (message != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, -56.dp.roundToPx()) }
                    .widthIn(max = 220.dp)
                    .glass(shape = RoundedCornerShape(10.dp), tint = DjiGreen, baseAlpha = 0.28f, haze = hazeState)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(message, color = TextPri, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }

        Box(
            Modifier
                .size(44.dp)
                .glass(shape = CircleShape, tint = if (listening) DjiRed else DjiGreen, baseAlpha = 0.22f, haze = hazeState)
                .pointerInput(micGranted) {
                    detectTapGestures(onPress = {
                        if (!micGranted) {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        vm.startCoPilotListening()
                        tryAwaitRelease()
                        vm.stopCoPilotListening()
                    })
                },
            contentAlignment = Alignment.Center,
        ) { Text(if (listening) "●" else "🎙", fontSize = if (listening) 20.sp else 16.sp, color = if (listening) Color.White else DjiGreen) }
    }
}

/** Full-screen confirmation step for auto take-off: explains what's about to happen, then
 *  requires arming + a long deliberate throw of [AircraftThrottleLever] to actually take off.
 *  Tapping the scrim does nothing (deliberately no tap-outside-to-dismiss), only "Cancel" or
 *  completing the throw leaves this screen, so a stray tap can't both dismiss and miss the
 *  real guard. Laid out as text-beside-lever (not stacked) so the lever can use nearly the
 *  full screen height for its travel, this is meant to be a full/serious take-off test, not
 *  a quick flick. */
@Composable
private fun TakeoffConfirmModal(
    enabled: Boolean, hazeState: HazeState? = null, onConfirm: () -> Unit, onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .pointerInput(Unit) { detectTapGestures { /* swallow taps on the scrim */ } },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(28.dp)
                .glass(shape = RoundedCornerShape(16.dp), tint = DjiGreen, baseAlpha = 0.25f, haze = hazeState)
                .padding(24.dp),
        ) {
            // Fill nearly the whole modal height with the lever's travel, a long, deliberate
            // throw for a full take-off test, not a quick flick. Capped so very tall/short
            // screens both still get a sane, fully-reachable control.
            val leverLength = (maxHeight - 40.dp).coerceIn(220.dp, 520.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(modifier = Modifier.widthIn(max = 240.dp)) {
                    Text("▲ AUTO TAKE-OFF", color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "The drone will arm its motors and climb to a safe hover height on its own. " +
                        "Confirm only once the area is clear and props are exactly as expected.",
                        color = TextSec, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(20.dp))
                    TextButton(onClick = onCancel) { Text("Cancel", color = TextSec, fontSize = 13.sp) }
                }
                AircraftThrottleLever(
                    label = "TAKE OFF", enabled = enabled, length = leverLength,
                ) { onConfirm() }
            }
        }
    }
}

/**
 * Top status strip, every field here is real decoded telemetry, matching the density of a
 * DJI GO 4 OSD bar but never inventing a reading GlassFalcon doesn't actually have (no RC/video
 * signal-strength bars or satellite count: no DUML frame for either has been decoded yet).
 */
@Composable
private fun HudIcon(res: Int, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    // Directional DROP SHADOW on the glyph, not a symmetric halo. The camera on this aircraft
    // mostly points at bright sky, and a soft omnidirectional glow washes out against a bright
    // background, there's no dark surround for it to sit in. A hard dark shadow cast DOWN-RIGHT
    // from each glyph instead gives a crisp dark anchor exactly the way text reads on a sunny day:
    // two offset passes (a soft wider one + a tighter dense one), both cast the same direction so
    // they compound into one shadow rather than a blur. Offset is a touch larger than the blur so
    // the shape stays a shadow, not a halo.
    val dx = 1.4.dp; val dy = 2.6.dp
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(res), contentDescription = null,
            colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.85f)),
            modifier = Modifier.size(size).offset(dx, dy).blur(3.dp),
        )
        Image(
            painter = painterResource(res), contentDescription = null,
            colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.95f)),
            modifier = Modifier.size(size).offset(dx * 0.6f, dy * 0.6f).blur(1.dp),
        )
        // Top-lit specular edge: a brightened copy nudged up ~0.6dp, so only a thin bright rim
        // peeks above the tinted glyph, the icon reads as etched/embossed metal catching light
        // from above, not a flat single-colour stamp. Drawn under the main glyph.
        Image(
            painter = painterResource(res), contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.55f)),
            modifier = Modifier.size(size).offset(y = (-0.6).dp),
        )
        Image(
            painter = painterResource(res), contentDescription = null,
            colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size),
        )
    }
}

/** A thin vertical hairline separating logical groups of glyphs in the top bar so the ~15 badges
 *  read as a few tidy clusters (status | mode | telemetry | power) instead of one dense run. */
@Composable
private fun HudDivider() {
    Box(
        Modifier
            .padding(horizontal = 7.dp)
            .width(1.dp)
            .height(16.dp)
            .background(TextSec.copy(alpha = 0.22f)),
    )
}

@Composable
private fun TopBar(
    modifier: Modifier,
    connected: Boolean,
    previewMode: Boolean,
    droneLinked: Boolean,
    battPct: Int,
    battMv: Int,
    battTempC: Float?,
    phoneBattPct: Int,
    phoneBattTempC: Float?,
    windMps: Float? = null,
    windFromDeg: Float? = null,
    windGustMps: Float? = null,
    weatherTempC: Float? = null,
    hasGpsFix: Boolean,
    gpsSats: Int = 0,
    flycState: Int,
    homeDistM: Float?,
    isRecording: Boolean,
    ledMode: Int,
    obstacleValid: Boolean,
    currentAirspace: dev.glassfalcon.core.AirspaceInfo?,
    timer: String,
    estopEnabled: Boolean,
    onEstop: () -> Unit,
    statusTrayOpen: Boolean,
    onToggleStatusTray: () -> Unit,
    hazeState: HazeState? = null,
) {
    // Override the HUD's global omnidirectional glow (see the CompositionLocalProvider up in
    // GlassFalconRoot's HUD Box) with a directional DROP SHADOW for the whole top bar. The camera
    // mostly frames bright sky here, and a soft glow washes out against it; a hard dark shadow
    // cast downward off each glyph/number is what stays legible, matching HudIcon's own switch to
    // a drop shadow just above.
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(
            shadow = Shadow(Color.Black, Offset(1f, 3f), blurRadius = 3f),
        ),
    ) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            // No background of its own anymore, HudGlassSurface (drawn once, shared by this +
            // both tapes + the compass) provides the actual glass fill/blur underneath; this
            // bar only draws its own content on top of it now. Minimal 3dp side inset, just
            // enough of a gap from the physical screen edge to read as intentional, per request.
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Every badge in this bar opens the SAME status tray (see StatusTray), one tap target
        // behavior to remember instead of a different popover per item, requested repeatedly.
        val tapTray = Modifier.clickable(onClick = onToggleStatusTray)

        // Real link state: RC link (transport) + drone telemetry presence. Preview mode gets its
        // own label/color (cyan, matching WarningsBanner's preview color), it reports
        // `connected = true` internally (see FlightViewModel.enterPreviewMode) so the rest of
        // the HUD renders exactly like a real "controller only" session, but that also means
        // this pill would otherwise show the exact same "RC ONLY" amber a real controller
        // connection does, with nothing distinguishing a simulated session from real hardware.
        val (linkLabel, linkColor) = when {
            previewMode -> "PREVIEW" to DjiCyan
            !connected  -> "NO LINK" to DjiRed
            droneLinked -> "RC+DRONE" to DjiGreen
            else        -> "RC ONLY" to DjiAmber
        }
        Surface(color = linkColor.copy(alpha = 0.25f), shape = MaterialTheme.shapes.small, modifier = tapTray) {
            Text(" ● $linkLabel ", color = linkColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
        }
        Spacer(Modifier.width(6.dp))
        Surface(
            color = (if (statusTrayOpen) (if (hasGpsFix) DjiGreen else DjiAmber) else Color.Transparent).copy(alpha = if (statusTrayOpen) 0.25f else 0f),
            shape = MaterialTheme.shapes.small,
            modifier = tapTray,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                // Sat count colors by fix quality: <6 red (no reliable fix), 6–9 amber (marginal),
                // 10+ green (solid). A number the pilot can watch climb before trusting GPS modes.
                val satColor = when {
                    !hasGpsFix || gpsSats < 6 -> DjiRed
                    gpsSats < 10 -> DjiAmber
                    else -> DjiGreen
                }
                HudIcon(R.drawable.ic_hud_mode_gps, satColor, 12.dp)
                Spacer(Modifier.width(3.dp))
                Text(
                    if (hasGpsFix) "GPS $gpsSats ▾" else "no GPS ($gpsSats) ▾",
                    color = satColor, fontSize = 10.sp,
                )
            }
        }
        // Current airspace, small footprint (class + ceiling only, not the full name, which
        // lives in the tray now) since this is real FAA data (Class_Airspace's own CLASS/
        // UPPER_VAL fields, confirmed live) queried whenever there's a GPS fix, not gated
        // behind the co-pilot. Tap for the full name + class + floor/ceiling in the status tray.
        currentAirspace?.let { a ->
            Spacer(Modifier.width(6.dp))
            val ceilingText = a.upperFt?.let { "${it.roundToInt()}ft" } ?: a.upperDesc?.takeIf { it.isNotBlank() }
            val shortLabel = listOfNotNull(a.classCode?.let { "Cls $it" }, ceilingText?.let { "≤$it" })
                .joinToString(" ").ifBlank { "?" }
            Surface(color = DjiAmber.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small, modifier = tapTray) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    HudIcon(R.drawable.ic_hud_airspace, DjiAmber, 12.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(shortLabel, color = DjiAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Distance to home, real, from the same GPS home-point/haversine calc as the status
        // tray's "distance to pilot" line (DroneState.homeDist itself is never populated by
        // any decoded DUML frame, so this is computed live instead of trusting that field).
        homeDistM?.let {
            Spacer(Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
                HudIcon(R.drawable.ic_hud_home, TextSec, 12.dp)
                Spacer(Modifier.width(3.dp))
                Text("${"%.0f".format(it)}m", color = TextSec, fontSize = 10.sp)
            }
        }
        if (isRecording) {
            Spacer(Modifier.width(8.dp))
            Text("● REC", color = DjiRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        // Landing light, real state (setLed's own single source of truth, not a locally
        // guessed toggle; see FlightViewModel.ledMode's doc comment), not just shown when on:
        // an explicit "off" reading is what makes this trustworthy as a real status rather than
        // an easy-to-miss disappearing badge.
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
            HudIcon(
                if (ledMode != 0) R.drawable.ic_hud_light_on else R.drawable.ic_hud_light_off,
                if (ledMode != 0) Gold else TextSec, 13.dp,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                if (ledMode != 0) "ON" else "off",
                color = if (ledMode != 0) Gold else TextSec, fontSize = 10.sp,
                fontWeight = if (ledMode != 0) FontWeight.Bold else FontWeight.Normal,
            )
        }
        // Vision radar, whether the obstacle-sensor frame (cmd 0x6a) is actually arriving at
        // all, independent of whether anything is currently close enough to glow. A pilot
        // relying on the edge-glow for "nothing nearby" needs to be able to tell that apart
        // from "the sensor feed itself is dead."
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
            HudIcon(R.drawable.ic_hud_radar, if (obstacleValid) DjiGreen else TextSec, 12.dp)
            Spacer(Modifier.width(2.dp))
            Text(if (obstacleValid) "OK" else "--", color = if (obstacleValid) DjiGreen else TextSec, fontSize = 10.sp)
        }

        HudDivider()
        Spacer(Modifier.weight(1f))

        // Flight mode, real FC state (flyc_state, low 7 bits of OSD General byte 30) rather
        // than a hasGpsFix-based guess. Confirmed enum values (dji-dumlv1-flyc.lua
        // FLYC_OSD_GENERAL_FLYC_STATE_ENUM): 0x1f=SPORT, 0x06=GPS_Atti, 0x01=Atti, 0x00=Manual.
        // SPORT gets its own color rather than reusing GPS's green: stock DJI firmware raises
        // the speed cap in Sport mode by itself (this project found no separate "speed limit"
        // toggle to unlock, see Flight Limits card, which only covers height/radius), so this
        // pill is how a pilot confirms "yes, I'm actually in the mode where that's true" rather
        // than just wanting it to be true.
        val (modeLabel, modeIcon, modeColor) = when (flycState and 0x7f) {
            0x1f -> Triple("SPORT", R.drawable.ic_hud_mode_sport, DjiRed)
            0x06 -> Triple("GPS", R.drawable.ic_hud_mode_gps, DjiGreen)
            0x01 -> Triple("ATTI", R.drawable.ic_hud_mode_atti, DjiAmber)
            0x00 -> Triple("MANUAL", R.drawable.ic_hud_mode_manual, DjiAmber)
            else -> if (hasGpsFix) Triple("GPS", R.drawable.ic_hud_mode_gps, DjiGreen)
                    else Triple("ATTI", R.drawable.ic_hud_mode_atti, DjiAmber)
        }
        // SPORT gets a subtle pulse + soft glow, the one mode where "notice this at a glance"
        // actually matters (it's the one where the speed cap is off), the others just sit flat.
        val isSport = (flycState and 0x7f) == 0x1f
        val sportPulse = rememberInfiniteTransition(label = "sportPulse")
        val pulseAlpha by sportPulse.animateFloat(
            initialValue = 0.22f, targetValue = 0.55f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "sportPulse",
        )
        Box(contentAlignment = Alignment.Center, modifier = tapTray) {
            if (isSport) {
                Box(
                    Modifier.size(56.dp, 28.dp)
                        .background(Brush.radialGradient(listOf(DjiRed.copy(alpha = pulseAlpha * 0.5f), Color.Transparent))),
                )
            }
            Surface(color = modeColor.copy(alpha = if (isSport) pulseAlpha else 0.25f), shape = MaterialTheme.shapes.small) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    HudIcon(modeIcon, modeColor, 12.dp)
                    Spacer(Modifier.width(3.dp))
                    Text(modeLabel, color = modeColor, fontSize = 10.sp, fontWeight = if (isSport) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Today's date, the phone's own clock, formatted once per composition (a session never
        // spans midnight often enough to justify a live-ticking recompute here). Both the date and
        // the flight timer now carry a VIBRANT date-derived color instead of flat grey: the hue is
        // driven by the day of the year (a full trip around the wheel across the year) and the
        // time of day nudges its brightness (darker pre-dawn, brightest midday), so the cluster
        // reads as a lively, always-changing accent rather than dead instrument text.
        val dateText = remember {
            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MMM d")).uppercase()
        }
        val dateColor = remember {
            val now = java.time.LocalDateTime.now()
            val hue = (now.dayOfYear / 366f) * 360f
            // Value dips to ~0.72 around 03:00 and peaks ~1.0 around 15:00, a gentle day/night
            // brightness swing on top of the date hue.
            val h = now.hour + now.minute / 60f
            val value = 0.86f + 0.14f * kotlin.math.cos(((h - 15f) / 24f) * 2f * Math.PI.toFloat())
            Color.hsv(hue, 0.7f, value.coerceIn(0.7f, 1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
            HudIcon(R.drawable.ic_hud_calendar, dateColor, 12.dp)
            Spacer(Modifier.width(3.dp))
            Text(dateText, color = dateColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono)
        }
        Spacer(Modifier.width(10.dp))

        // Live wall-clock time (ticking every second), the "clock" glyph used to show the flight
        // timer, which sits at 00:00 until takeoff and read as a broken clock. The flight timer is
        // still available on the flight-records screen; up here a real time-of-day clock is what a
        // pilot actually expects.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
            HudIcon(R.drawable.ic_hud_clock, dateColor, 12.dp)
            Spacer(Modifier.width(3.dp))
            Text(dev.glassfalcon.core.Units.clock(nowMs), color = dateColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono)
        }
        HudDivider()

        // Onboard (battery pack) temperature, real telemetry, not the phone's. Absent until
        // the smart battery's first dynamic-data frame arrives.
        battTempC?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
                HudIcon(R.drawable.ic_hud_thermometer, TextSec, 12.dp)
                Spacer(Modifier.width(3.dp))
                Text(dev.glassfalcon.core.Units.temp(c), color = TextSec, fontSize = 10.sp, fontFamily = IbmPlexMono)
            }
            Spacer(Modifier.width(10.dp))
        }

        // Pack voltage alongside percentage, real telemetry (battery_dynamic_data), same
        // 0-means-not-synced gate as the percentage below it.
        if (battMv > 0) {
            Text("${"%.1f".format(battMv / 1000f)}V", color = TextSec, fontSize = 10.sp, fontFamily = IbmPlexMono, modifier = tapTray)
            Spacer(Modifier.width(8.dp))
        }

        // 0 = smart battery not yet synced (on the ground / pre-activation), show ", "
        // rather than a false red 0% alarm. See project_battery_activation_gate memory.
        val battColor = when { battPct > 50 -> DjiGreen; battPct > 20 -> DjiAmber; battPct > 0 -> DjiRed; else -> TextSec }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
            HudIcon(R.drawable.ic_hud_battery, battColor, 16.dp)
            Spacer(Modifier.width(3.dp))
            // Drone battery is the number that ends flights, sized up from the rest of the bar.
            Text(if (battPct > 0) "$battPct%" else ", ", color = battColor, fontSize = 15.sp,
                fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono)
        }

        // Phone's own battery + temp, ONE bespoke phone icon with both metrics on one line
        // (not two separate icon+text pairs) since they're both "how's THIS device holding up,"
        // not two independent readings worth their own icons. A dead or overheating phone ends
        // the flight just as surely as a dead aircraft battery does, and this screen never
        // showed either before.
        if (phoneBattPct >= 0 || phoneBattTempC != null) {
            Spacer(Modifier.width(10.dp))
            val phoneColor = when { phoneBattPct > 50 -> DjiGreen; phoneBattPct > 20 -> DjiAmber; phoneBattPct >= 0 -> DjiRed; else -> TextSec }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
                HudIcon(R.drawable.ic_hud_phone, phoneColor, 12.dp)
                Spacer(Modifier.width(3.dp))
                val parts = listOfNotNull(
                    phoneBattPct.takeIf { it >= 0 }?.let { "$it%" },
                    phoneBattTempC?.let { dev.glassfalcon.core.Units.temp(it) },
                )
                Text(parts.joinToString(" · "), color = phoneColor, fontSize = 10.sp, fontFamily = IbmPlexMono)
            }
        }

        // Forecast ambient air temp + surface wind (Windy), grouped since they're one source. This
        // is the true outside-air temperature, distinct from the thermometer-icon battery temp and
        // the phone-icon device temp. The wind arrow points the way the wind blows (from-bearing +
        // 180); its color steps at the Mavic 2's limits: green calm, amber sporty, red past the
        // ~10.7 m/s (24 mph) rated wind resistance where it fights to hold position.
        if (weatherTempC != null || windMps != null) {
            Spacer(Modifier.width(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = tapTray) {
                weatherTempC?.let {
                    Text(dev.glassfalcon.core.Units.temp(it), color = TextSec,
                        fontSize = 10.sp, fontFamily = IbmPlexMono)
                    if (windMps != null) Spacer(Modifier.width(5.dp))
                }
                if (windMps != null && windFromDeg != null) {
                    // AVG and GUST get DIFFERENT dynamic color schemes so they never read as one
                    // number: avg is the familiar green→amber→red at the Mavic's 6/10.7 m/s marks;
                    // gust runs cyan→amber→red with amber a touch earlier (7), since a gust is the
                    // sharper danger. Shown side-by-side in a pill with a divider between them.
                    val avgColor = when { windMps > 10.7f -> DjiRed; windMps > 6f -> DjiAmber; else -> DjiGreen }
                    val gust = windGustMps
                    val gustColor = when { gust == null -> avgColor; gust > 10.7f -> DjiRed; gust > 7f -> DjiAmber; else -> DjiCyan }
                    // Pulse glow on any change, punch scaling with the worse of the two.
                    val worst = maxOf(windMps, gust ?: windMps)
                    val windGlow = remember { Animatable(0f) }
                    LaunchedEffect(windMps, gust) { windGlow.snapTo(1f); windGlow.animateTo(0f, tween(1000, easing = LinearEasing)) }
                    val speedFrac = (worst / 12f).coerceIn(0f, 1f)
                    val shadowPx = with(LocalDensity.current) {
                        ((2.5f + speedFrac * 3f) + windGlow.value * (5f + speedFrac * 9f)).dp.toPx()
                    }
                    val windStyle = LocalTextStyle.current.copy(shadow = Shadow(Color.Black, Offset(1f, 2f), blurRadius = shadowPx))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(Color(0x59000000), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("↑", color = avgColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.rotate(windFromDeg + 180f), style = windStyle)
                        Spacer(Modifier.width(3.dp))
                        Text(dev.glassfalcon.core.Units.windSpeed(windMps), color = avgColor,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono, style = windStyle)
                        if (gust != null) {
                            Spacer(Modifier.width(5.dp))
                            Box(Modifier.width(1.dp).height(13.dp).background(Color.White.copy(alpha = 0.35f)))
                            Spacer(Modifier.width(5.dp))
                            Text("G${dev.glassfalcon.core.Units.windSpeed(gust)}", color = gustColor,
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono, style = windStyle)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(10.dp))
        CompactEstop(estopEnabled, onEstop)
    }
    } // end top-bar drop-shadow text scope
}

/** E-STOP, sized to live in the 36dp top bar instead of floating over the video feed.
 *  Two deliberate taps (arm, then confirm within 3s) stand in for the old drag-slide guard, 
 *  still can't fire from one accidental tap, just without the vertical space a full
 *  [GuardedSlideSwitch] needs. */
@Composable
private fun CompactEstop(enabled: Boolean, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    LaunchedEffect(enabled) { if (!enabled) armed = false }

    Surface(
        color = if (armed) DjiRed else DjiRed.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(enabled = enabled) {
            if (armed) { onConfirm(); armed = false } else armed = true
        },
    ) {
        Text(
            if (armed) " TAP TO CONFIRM " else " ⏹ E-STOP ",
            color = if (armed) Color.Black else if (enabled) DjiRed else TextSec,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun LeftTelemetry(
    modifier: Modifier,
    altM: Float,
    vSpeed: Float,
    hSpeed: Float,
    homeDist: Float,
) {
    Column(
        modifier = modifier.width(72.dp).glass(shape = RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        // Altitude
        Text(if (altM.isFinite()) "${"%.1f".format(altM)}" else "--",
            color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Text("m", color = TextSec, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        val vArrow = if (vSpeed >= 0) "↑" else "↓"
        Text(if (vSpeed.isFinite()) "$vArrow ${"%.1f".format(kotlin.math.abs(vSpeed))}" else "-- m/s",
            color = TextSec, fontSize = 11.sp)
        Text("m/s", color = TextSec, fontSize = 9.sp)
        Spacer(Modifier.height(12.dp))
        // Ground speed
        val safeSpeed = if (hSpeed.isFinite() && hSpeed < 200f) hSpeed else 0f
        Text("${"%.1f".format(safeSpeed)}",
            color = TextPri, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Text("m/s", color = TextSec, fontSize = 9.sp)
        Spacer(Modifier.height(12.dp))
        // Home distance
        Text("🏠", fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Text("${"%.0f".format(homeDist)}m", color = TextSec, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
    }
}

/** Default view is just the capture button and a small photo/video toggle underneath it, a
 *  small "⚙" beneath that opens a glass tray with SD status and the ISO/shutter/EV/WB/aperture
 *  settings, since those are adjusted rarely compared to just pressing the shutter. The whole
 *  thing can be turned off from Settings → Camera (`app.captureButtonEnabled`) for pilots who
 *  don't want it on the flight HUD at all. */
@Composable
private fun RightCameraPanel(modifier: Modifier, vm: FlightViewModel, hazeState: HazeState? = null) {
    val app by vm.app.collectAsState()
    if (!app.captureButtonEnabled) return

    // Shared with FlightViewModel (not local Compose state) so an RC-button toggle and this
    // on-screen one stay in sync instead of each guessing independently which mode we're in.
    val cameraMode by vm.cameraMode.collectAsState()
    val isVideo = cameraMode == 1
    val isRecording by vm.isRecording.collectAsState()
    val camState by vm.cameraState.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    var showFormatConfirm by remember { mutableStateOf(false) }
    // No DUML read-back exists for these (see ISO_NAMES doc comment), index of what was last
    // SENT, cycled by tapping the row. Starts at "Auto"/0 to match the camera's own power-on
    // default rather than implying a confirmed reading.
    var isoIdx by remember { mutableStateOf(0) }
    var shutterIdx by remember { mutableStateOf(0) }
    var evIdx by remember { mutableStateOf(6) }   // 6 = 0.0 EV
    var wbIdx by remember { mutableStateOf(0) }
    var apertureIdx by remember { mutableStateOf(0) }
    // Focus/AE-lock cmd_set CONFIRMED via kprobe capture 2026-07-03 (see DumlCommands.kt), 
    // these two actually work on real hardware, unlike most of the tray above.
    var focusIdx by remember { mutableStateOf(2) }  // 2 = ContinuousAuto (AFC), the camera's default
    var aeLocked by remember { mutableStateOf(false) }

    // Ask for a fresh SD/recording state read the moment the tray is opened, rather than
    // waiting up to 3s for the background poll in monitorLinkAndWarnings().
    LaunchedEffect(settingsOpen) { if (settingsOpen) vm.requestCameraState() }

    // Slow continuous hue cycle for the capture button's glass tint, purely decorative, not
    // tied to any drone data (unlike the compass's heading-based color), a "glass ball" look
    // per request, still readable as red-while-recording since that overrides the cycling tint.
    val hueTransition = rememberInfiniteTransition(label = "captureHue")
    val hue by hueTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(10_000, easing = LinearEasing), RepeatMode.Restart),
        label = "captureHue",
    )
    val ballColor = Color.hsv(hue, 0.8f, 1f)

    // Opens LEFTWARD out of the button cluster itself, not downward off a separate small arrow
    // floating below it, the tray and the buttons read as one attached unit, and expanding
    // horizontally toward screen-center (rather than growing off the bottom into the flight
    // controls) is what "taking up more of the screen but staying see-through" actually needs:
    // a real glass modal, not a bigger dropdown.
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedVisibility(
            visible = settingsOpen,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(tween(220)),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(tween(160)),
        ) {
            Column(
                modifier = Modifier.width(280.dp).glass(shape = RoundedCornerShape(16.dp), haze = hazeState, baseAlpha = 0.16f).padding(14.dp),
            ) {
                Text("CAMERA", color = TextSec, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                // Real ground truth from the camera's own state push, this is the actual
                // reason capture/record can silently do nothing: no card, or a card error.
                // Spelled out in full now that there's room, instead of a terse "SD: OK".
                when {
                    !camState.received -> Text("SD card: checking for card…", color = TextSec, fontSize = 11.sp)
                    !camState.sdInserted -> Text("⚠ No SD card inserted", color = DjiRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    camState.sdError -> Text("⚠ SD card error, reformat or replace", color = DjiRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    else -> Text("SD card: inserted, OK", color = DjiGreen, fontSize = 11.sp)
                }
                Text(
                    "Capacity/free-space aren't shown, no confirmed byte layout for the " +
                    "SD-info response yet (see sdk docs). This aircraft has no separate " +
                    "internal storage.",
                    color = TextSec, fontSize = 9.sp, lineHeight = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0x33FFFFFF))
                Spacer(Modifier.height(4.dp))

                // Tap any row to cycle its value and send the real DUML setter. Full names now
                // that there's room for them, not single-letter abbreviations.
                CamRow("Shutter", SHUTTER_NAMES[shutterIdx]) {
                    shutterIdx = (shutterIdx + 1) % SHUTTER_NAMES.size; vm.setCameraShutter(shutterIdx)
                }
                CamRow("ISO", ISO_NAMES[isoIdx]) {
                    isoIdx = (isoIdx + 1) % ISO_NAMES.size; vm.setCameraIso(isoIdx)
                }
                CamRow("EV", EV_NAMES[evIdx]) {
                    evIdx = (evIdx + 1) % EV_NAMES.size; vm.setCameraEv(evIdx)
                }
                CamRow("White Balance", WB_NAMES[wbIdx]) {
                    wbIdx = (wbIdx + 1) % WB_NAMES.size; vm.setCameraWb(wbIdx)
                }
                CamRow("Aperture", APERTURE_NAMES[apertureIdx]) {
                    apertureIdx = (apertureIdx + 1) % APERTURE_NAMES.size; vm.setCameraAperture(apertureIdx)
                }
                CamRow("Focus", FOCUS_NAMES[focusIdx]) {
                    focusIdx = (focusIdx + 1) % FOCUS_NAMES.size; vm.setCameraFocus(focusIdx)
                }
                CamRow("AE Lock", if (aeLocked) "🔒 Locked" else "Unlocked") {
                    aeLocked = !aeLocked; vm.setCameraAeLock(aeLocked)
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Color(0x33FFFFFF))
                Text(
                    "FORMAT SD CARD", color = DjiRed, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp).clickable(enabled = app.connected) { showFormatConfirm = true },
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Capture / Record button, always visible, the one thing this panel exists for. A
            // translucent glass ball (real backdrop blur via hazeState) slowly cycling through
            // color, rather than a flat white disc, so it reads as part of the glass HUD language
            // instead of a bolted-on opaque control sitting on top of it. Noticeably higher
            // alpha + saturation than every other glass panel on purpose: this is the one
            // primary tap target in the whole cluster, not an ambient status readout, and the
            // general "clear glass" pass made it too subtle to find at a glance.
            val ballTint = if (isVideo && isRecording) DjiRed else ballColor
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .glass(
                        shape = CircleShape,
                        tint = ballTint,
                        baseAlpha = 0.65f,
                        haze = hazeState,
                    )
                    .border(2.dp, ballTint.copy(alpha = 0.85f), CircleShape)
                    .clickable(enabled = app.connected) {
                        if (!isVideo) vm.capturePhoto() else vm.toggleRecord()
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (isVideo && isRecording) {
                    Box(Modifier.size(16.dp).background(Color.White))
                } else {
                    // Centered mode icon so the button itself says "this is what pressing me
                    // does" instead of being a blank glass disc, camera for photo mode, video
                    // camera for video mode, in a soft glass chip rather than a flat glyph.
                    Box(
                        Modifier.size(30.dp).glass(shape = CircleShape, baseAlpha = 0.35f, haze = hazeState),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(if (!isVideo) R.drawable.ic_hud_camera_photo else R.drawable.ic_hud_camera_video),
                            contentDescription = null, modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            // Small photo/video toggle pill right under the button.
            Row(
                Modifier.width(66.dp).glass(shape = RoundedCornerShape(20.dp), baseAlpha = 0.16f, haze = hazeState),
            ) {
                Box(
                    modifier = Modifier.weight(1f).background(if (!isVideo) DjiGreen else Color.Transparent, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .clickable { if (isRecording) vm.stopRecord(); vm.setCameraMode(0) }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("📷", fontSize = 10.sp) }
                Box(
                    modifier = Modifier.weight(1f).background(if (isVideo) DjiRed else Color.Transparent, RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                        .clickable { vm.setCameraMode(1) }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("🎥", fontSize = 10.sp) }
            }
            Spacer(Modifier.height(4.dp))

            // Settings tray toggle, attached directly under the buttons it belongs to. An arrow
            // that flips direction reads as "expand/collapse this tray" at a glance; a gear reads
            // as "go somewhere else to configure something," which is what pilots kept expecting.
            Box(
                Modifier.size(22.dp).glass(shape = CircleShape, baseAlpha = 0.16f, haze = hazeState)
                    .clickable { settingsOpen = !settingsOpen },
                contentAlignment = Alignment.Center,
            ) { Text(if (settingsOpen) "▸" else "◂", color = TextSec, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }

    if (showFormatConfirm) {
        FormatSdConfirmDialog(
            onConfirm = { vm.formatSdCard(); showFormatConfirm = false },
            onCancel = { showFormatConfirm = false },
        )
    }
}

/** Destructive action, irreversibly erases every photo/video on the card, so it gets the same
 *  explicit two-step confirmation as E-STOP/take-off rather than firing on a single tap. */
@Composable
private fun FormatSdConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f))
            .pointerInput(Unit) { detectTapGestures { /* swallow taps on the scrim */ } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(28.dp).glass(shape = RoundedCornerShape(16.dp), tint = DjiRed, baseAlpha = 0.25f)
                .padding(24.dp).widthIn(max = 280.dp),
        ) {
            Text("⚠ FORMAT SD CARD", color = DjiRed, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("This erases every photo and video on the drone's memory card. This cannot be undone.",
                color = TextPri, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel) { Text("Cancel", color = TextSec) }
                Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = DjiRed)) {
                    Text("Format", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CamRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSec, fontSize = 10.sp)
        Text(value, color = Gold, fontSize = 10.sp)
    }
}

@Composable
private fun BottomStrip(
    modifier: Modifier,
    vm: FlightViewModel,
    onOpenSettings: () -> Unit,
    onToggleMap: () -> Unit,
) {
    val app by vm.app.collectAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(BarBg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Map show/hide toggle
        Box(
            Modifier
                .size(width = 80.dp, height = 44.dp)
                .background(Color(0xFF111111))
                .clickable(onClick = onToggleMap),
            contentAlignment = Alignment.Center,
        ) {
            Text("🗺 MAP", color = DjiGreen, fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))

        // RTH, same live-link gate as the primary RTH button (MainScreen row).
        val droneLinked by vm.droneLinked.collectAsState()
        OutlinedButton(
            onClick = { vm.sendRth() },
            enabled = droneLinked,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DjiAmber),
        ) { Text("🏠 RTH", fontSize = 11.sp) }

        // Settings gear
        IconButton(onClick = onOpenSettings) {
            Text("⚙", color = TextSec, fontSize = 18.sp)
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SmallBtn(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
    ) { Text(label, fontSize = 10.sp) }
}

/**
 * Flight-control guarded switch, models a covered cockpit toggle.
 *
 *  1. A coloured safety guard sits over the track. Tap it and the guard flips up
 *     (rotates away on its top hinge) to ARM the control.
 *  2. Once armed, the thumb must be dragged the ENTIRE travel to fire [onConfirm].
 *     Releasing before the far end snaps the thumb back; a short partial drag never
 *     triggers anything.
 *  3. If left armed and idle for a few seconds the guard auto-recloses, so the
 *     control never sits live and unattended.
 *
 *  Used for both take-off/land and emergency-stop, anything that spins or cuts motors.
 */
@Composable
private fun GuardedSlideSwitch(
    label: String,
    color: Color,
    enabled: Boolean,
    slideUp: Boolean,     // true: drag bottom→top to confirm (takeoff/rising); false: top→bottom (landing/descending)
    length: Dp = 150.dp,  // travel distance along the slide axis
    hazeState: HazeState? = null,
    onConfirm: () -> Unit,
) {
    val thumbHeightDp = 56.dp
    val thumbPx = with(LocalDensity.current) { thumbHeightDp.toPx() }
    var armed    by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }  // px traveled toward the confirm end, regardless of direction
    var trackPx  by remember { mutableStateOf(1f) }  // travel = trackHeight - thumb
    val lift     by animateFloatAsState(if (armed) 1f else 0f, label = "guard")
    val baseColor = if (enabled) color else TextSec
    val arrow = if (slideUp) "▲" else "▼"

    // Auto-reclose the guard after inactivity so it never stays armed unattended.
    LaunchedEffect(armed, progress) {
        if (armed && progress < 1f) { delay(4000); if (progress < 1f) armed = false }
    }
    // Drop the guard the moment the link is lost.
    LaunchedEffect(enabled) { if (!enabled) { armed = false; progress = 0f } }

    // This control sits close enough to the bottom edge that the drag-to-confirm gesture was
    // landing inside Android's system nav gesture strip, so a takeoff/land/e-stop drag could
    // get eaten by the OS "swipe to exit app" gesture instead of reaching us. Excluding this
    // view's own bounds from system gesture handling fixes that without having to push the
    // whole control up with extra padding (which just gives the space right back).
    val view = LocalView.current
    Box(
        Modifier.width(96.dp).height(length)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                view.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                )
            }
    ) {
        // ── Track + draggable thumb (live once armed) ──
        // The drag gesture is on the WHOLE track, not just the small thumb, so an imprecise
        // finger start anywhere in the pill still grabs it, not just a tiny hit-target.
        Box(
            Modifier.fillMaxSize()
                .glass(shape = RoundedCornerShape(10.dp), tint = baseColor, baseAlpha = 0.25f, haze = hazeState)
                .onSizeChanged { trackPx = (it.height - thumbPx).coerceAtLeast(1f) }
                .pointerInput(enabled, armed, slideUp) {
                    if (!enabled || !armed) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd    = { if (progress < trackPx * 0.985f) progress = 0f },
                        onDragCancel = { progress = 0f },
                    ) { change, delta ->
                        change.consume()
                        val d = if (slideUp) -delta else delta   // up-drag is negative screen-Y
                        progress = (progress + d).coerceIn(0f, trackPx)
                        if (progress >= trackPx * 0.985f) { onConfirm(); progress = 0f; armed = false }
                    }
                },
            contentAlignment = if (slideUp) Alignment.BottomCenter else Alignment.TopCenter,
        ) {
            val frac = (progress / trackPx).coerceIn(0f, 1f)
            // Fill grows from the thumb's resting edge toward the confirm end, up from the
            // bottom for takeoff, down from the top for landing.
            Box(Modifier.align(if (slideUp) Alignment.BottomCenter else Alignment.TopCenter)
                .fillMaxWidth().fillMaxHeight(frac)
                .clip(RoundedCornerShape(10.dp)).background(baseColor.copy(alpha = 0.30f)))
            Text("$label\n$arrow", color = baseColor.copy(alpha = 0.85f),
                fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 16.sp,
                modifier = Modifier.align(Alignment.Center))
            // Thumb offset: at progress=0 it rests at the start edge (bottom for slide-up, top
            // for slide-down); at progress=trackPx it's traveled the full length to the other end.
            // Purely visual now, the drag gesture lives on the track above, not this Box.
            val thumbY = if (slideUp) -progress else progress
            Box(
                Modifier.offset { IntOffset(0, thumbY.roundToInt()) }
                    .padding(4.dp).fillMaxWidth().height(thumbHeightDp)
                    .clip(RoundedCornerShape(8.dp)).background(baseColor),
                contentAlignment = Alignment.Center,
            ) { Text(arrow, color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }

        // ── Safety guard cover (flips up on tap to arm) ──
        if (lift < 0.999f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0f)  // hinge along the top edge
                        rotationX   = 100f * lift
                        translationY = -size.height * 0.12f * lift
                        alpha = 1f - lift
                        cameraDistance = 16f * density
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(baseColor.copy(alpha = 0.92f))
                    .border(2.dp, Color(0xFF141414), RoundedCornerShape(8.dp))
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { armed = true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🛡 $label\nFLIP TO ARM", color = Color.Black,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, lineHeight = 12.sp)
            }
        }
    }
}

/**
 * Same arm-then-drag-full-travel logic as [GuardedSlideSwitch] (copied rather than shared, 
 * this one is only ever used for the take-off modal's full-test throw, and duplicating the
 * ~15 lines of gesture math is cheaper than adding a "look" parameter to the flight-critical
 * shared control and risking a regression there), styled to look like a real thrust-lever
 * quadrant: a metal-bezel rail with tick marks and a machined-looking handle, instead of a
 * flat colored pill. The hazard-striped safety gate stands in for a real throttle's finger lift.
 */
@Composable
private fun AircraftThrottleLever(label: String, enabled: Boolean, length: Dp, onConfirm: () -> Unit) {
    val thumbHeightDp = 64.dp
    val thumbPx = with(LocalDensity.current) { thumbHeightDp.toPx() }
    var armed    by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var trackPx  by remember { mutableStateOf(1f) }
    val lift     by animateFloatAsState(if (armed) 1f else 0f, label = "throttleGuard")
    val litColor = if (enabled) DjiGreen else TextSec

    LaunchedEffect(armed, progress) {
        if (armed && progress < 1f) { delay(4000); if (progress < 1f) armed = false }
    }
    LaunchedEffect(enabled) { if (!enabled) { armed = false; progress = 0f } }

    val view = LocalView.current
    Box(
        Modifier.width(110.dp).height(length)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                view.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                )
            }
    ) {
        // ── Metal bezel + quadrant slot + tick marks ──
        Box(
            Modifier.fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF3a3d40), Color(0xFF1c1e20), Color(0xFF313436))))
                .border(1.dp, Color(0xFF5a5d60), RoundedCornerShape(10.dp))
                .onSizeChanged { trackPx = (it.height - thumbPx).coerceAtLeast(1f) }
                .pointerInput(enabled, armed) {
                    if (!enabled || !armed) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd    = { if (progress < trackPx * 0.985f) progress = 0f },
                        onDragCancel = { progress = 0f },
                    ) { change, delta ->
                        change.consume()
                        progress = (progress - delta).coerceIn(0f, trackPx)  // always slides up
                        if (progress >= trackPx * 0.985f) { onConfirm(); progress = 0f; armed = false }
                    }
                },
        ) {
            // Quadrant slot (the machined groove the lever rides in) + tick marks
            Canvas(Modifier.fillMaxSize()) {
                val slotW = 10.dp.toPx()
                val cx = size.width / 2f
                drawRoundRect(
                    color = Color(0xFF0a0a0a),
                    topLeft = Offset(cx - slotW / 2f, thumbPx / 2f),
                    size = androidx.compose.ui.geometry.Size(slotW, size.height - thumbPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(slotW / 2f),
                )
                val ticks = 6
                for (i in 0..ticks) {
                    val y = thumbPx / 2f + (size.height - thumbPx) * (i / ticks.toFloat())
                    drawLine(Color(0xFF8a8d90).copy(alpha = 0.7f),
                        Offset(cx - 22.dp.toPx(), y), Offset(cx - slotW, y), strokeWidth = 1.5.dp.toPx())
                    drawLine(Color(0xFF8a8d90).copy(alpha = 0.7f),
                        Offset(cx + slotW, y), Offset(cx + 22.dp.toPx(), y), strokeWidth = 1.5.dp.toPx())
                }
            }
            Text("T/O", color = Color(0xFF9a9da0), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
            Text("IDLE", color = Color(0xFF9a9da0), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))

            // Progress glow up the slot as the lever advances
            val frac = (progress / trackPx).coerceIn(0f, 1f)
            if (frac > 0f) {
                Box(Modifier.align(Alignment.BottomCenter).width(10.dp).fillMaxHeight(frac * 0.94f)
                    .padding(bottom = thumbHeightDp / 2)
                    .background(litColor.copy(alpha = 0.55f), RoundedCornerShape(5.dp)))
            }

            // ── The lever handle itself, machined metal knob with a lit accent band ──
            val thumbY = -progress
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .offset { IntOffset(0, thumbY.roundToInt()) }
                    .width(76.dp).height(thumbHeightDp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(
                        listOf(Color(0xFF6b6e70), Color(0xFF2a2c2e), Color(0xFF525558), Color(0xFF2a2c2e), Color(0xFF6b6e70))
                    ))
                    .border(1.dp, Color(0xFF1a1a1a), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(10.dp).background(litColor.copy(alpha = 0.9f)))
                Text("▲", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Hazard-striped safety gate (flips up on tap to arm) ──
        if (lift < 0.999f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        rotationX   = 100f * lift
                        translationY = -size.height * 0.12f * lift
                        alpha = 1f - lift
                        cameraDistance = 16f * density
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .drawWithContent {
                        drawContent()
                        // Diagonal hazard stripes, red/black, the "lift to arm" cover look.
                        val stripeW = 14.dp.toPx()
                        var x = -size.height
                        while (x < size.width) {
                            drawLine(Color(0xFFcc2222), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stripeW)
                            x += stripeW * 2
                        }
                    }
                    .background(Color.Black.copy(alpha = 0.15f))
                    .border(2.dp, Color(0xFF1a1a1a), RoundedCornerShape(10.dp))
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        detectTapGestures { armed = true }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.glass(shape = RoundedCornerShape(6.dp), baseAlpha = 0.3f).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text("🛡 $label\nFLIP TO ARM", color = Color.White,
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, lineHeight = 12.sp)
                }
            }
        }
    }
}

// ── DJI-style HUD widgets ────────────────────────────────────────────────────

/** The phone's OWN battery level + temperature (not the drone's/aircraft battery, which is a
 *  separate real telemetry field already on the top bar), read via the standard sticky
 *  `ACTION_BATTERY_CHANGED` broadcast, the normal no-permission-required way to get both on
 *  Android, refreshed live via a registered receiver rather than a one-shot read (matters on a
 *  screen that's meant to stay up for a whole flight). Deliberately NOT extended to "controller
 *  temp" or motor/ESC temps some DJI apps show, no DUML frame decoding either has ever been
 *  found/confirmed for those on this aircraft (see MediaRepository.kt's doc comment for the same
 *  "don't guess, only real decoded data" rule applied elsewhere); the drone's own smart-battery
 *  temp (`battTempC`, already on this bar) is the only aircraft-side temperature this project has
 *  ever actually decoded. pct is -1 until the first broadcast lands; tempC is null likewise. */
@Composable
private fun rememberPhoneBatteryInfo(): Pair<Int, Float?> {
    val context = LocalContext.current
    var pct by remember { mutableStateOf(-1) }
    var tempC by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        fun update(intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) pct = level * 100 / scale
            val tenthsC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
            if (tenthsC != Int.MIN_VALUE) tempC = tenthsC / 10f
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = update(intent)
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        update(context.registerReceiver(receiver, filter))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return pct to tempC
}

/** The physical punch-hole camera's bounding rect, in raw pixels relative to the window, read
 *  live from the OS on every layout pass rather than kept in a hand-maintained per-model
 *  database. A static list would need updating for every new phone and would silently be wrong
 *  for any device not in it; `View.rootWindowInsets` already knows the exact answer for
 *  whichever device this happens to be running on, cutout shape included, for free. Null on a
 *  device/orientation with no cutout (most non-punch-hole phones, or the side without one in
 *  this app's landscape lock). */
@Composable
private fun rememberCutoutRectPx(): androidx.compose.ui.geometry.Rect? {
    val view = LocalView.current
    var rect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    DisposableEffect(view) {
        fun refresh() {
            val r = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull()
            rect = r?.let {
                androidx.compose.ui.geometry.Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
            }
        }
        refresh()
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return rect
}

/** Loads the bundled IBM Plex Mono once for the raw-Canvas tick-label text on the PFD tapes and
 *  heading tape, those are drawn via `nativeCanvas.drawText`/`android.graphics.Paint`, not
 *  Compose `Text()`, so they don't pick up MaterialTheme's Typography automatically the way the
 *  rest of the HUD's numeric readouts do; this is the same font applied by hand at the Paint
 *  level, for the same "consistent digit width" reason. */
@Composable
private fun rememberMonoTypeface(): android.graphics.Typeface? {
    val context = LocalContext.current
    return remember { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular) }
}

/** Shared aircraft-PFD-style vertical tape renderer: a full-height scrolling scale with the
 *  current value fixed at vertical center (like a real altimeter/airspeed tape), dense minor
 *  ticks with major ticks labeled, and color driven by [colorFor] so out-of-normal-range bands
 *  read at a glance. [span] is how many value-units are visible top-to-bottom at once.
 *  [cutoutPx] (window-relative, from [rememberCutoutRectPx]) lets the tape nudge just the number
 *  labels that would otherwise land on top of the physical cutout, instead of shifting the whole
 *  tape and shrinking the usable screen for every device regardless of whether it even has a
 *  cutout on this side. [gaugeMax] is this tape's own reference ceiling for the rivet's fill
 *  ring (e.g. speed's own span, altitude's amber-band threshold), not a hard limit, just what
 *  "full" means for that ring. Own background wash (a per-tape gradient behind the ticks) was
 *  removed, it drew independently of [HudGlassSurface]'s shared fill and its hard rectangular
 *  edge was an actual, confirmed seam right at the tape's boundary with the top/bottom bars;
 *  [colorFor]'s per-tick coloring already carries the same "which category is this value in"
 *  information without a second overlapping fill. */
@Composable
private fun PfdTape(
    value: Float, unit: String, span: Float, majorStep: Float, minorStep: Float,
    colorFor: (Float) -> Color, alignEdge: Alignment, hazeState: HazeState?,
    cutoutPx: androidx.compose.ui.geometry.Rect?, gaugeMax: Float,
    modifier: Modifier,
) {
    val safe = if (value.isFinite()) value else 0f
    val monoTypeface = rememberMonoTypeface()
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var boxSizePx by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    Box(
        modifier
            // Touches the top bar (36dp, no gap). Runs all the way to the true bottom edge, 
            // the heading tape only spans the middle of the screen (56dp start/end padding),
            // so stopping this tape at bottom=34dp left a bare 56x34dp black rectangle in each
            // bottom corner, under the tape's own column where the heading tape never reaches.
            // The take-off/RTH controls have their own start/end padding to clear this tape
            // horizontally, so there's nothing else below worth reserving space for.
            .padding(top = 36.dp)
            .width(56.dp)
            .fillMaxHeight()
            // No background of its own anymore, HudGlassSurface provides the shared glass
            // fill/blur underneath both tapes + the top bar + the compass; this tape only
            // draws its own ticks/labels/rivet on top of it now.
            // Placed last so it reports this Box's FINAL bounds (post-padding/width/height), 
            // the same coordinate space the Canvas below draws in, not its bounds before those
            // modifiers ran.
            .onGloballyPositioned {
                originInWindow = it.boundsInWindow().topLeft
                boxSizePx = androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())
            },
    ) {
        // Cutout rect translated into THIS tape's local coordinate space, null (no dodge, no
        // rivet) unless the physical hole actually falls within this specific tape's bounds.
        // Computed once here (not separately inside the Canvas below) so the Canvas's rivet
        // drawing, the label dodge, and the relocated readout below all agree on the same rect
        // and the same hugging radius.
        val density = LocalDensity.current
        val cutoutLocal = cutoutPx?.translate(-originInWindow.x, -originInWindow.y)
            ?.takeIf { it.overlaps(androidx.compose.ui.geometry.Rect(Offset.Zero, boxSizePx)) }
        // Hug the actual hole tightly, a bezel with real breathing room around it reads as
        // "decoration floating near the camera"; hugging it close reads as "this rivet IS the
        // camera," which is the actual joke. MIN of the two axes, not max: confirmed live via
        // `adb shell dumpsys window displays` that the OS-reported boundingRect for a round
        // single-lens punch-hole is NOT itself square (e.g. 200x109px raw on the Pixel 10 Pro
        // XL, against a lens the cutout's own path spec draws at 96px diameter), the OS pads
        // one axis with extra safe-area clearance well beyond the actual glass. Using the larger
        // axis for both dimensions of a circular ring inherits that padding on the axis that
        // never had it, drawing a rivet roughly 2x the size of the real lens. The smaller axis is
        // consistently much closer to the true diameter.
        val holeR = cutoutLocal?.let { with(density) { (minOf(it.width, it.height) / 2f) + 0.5.dp.toPx() } } ?: 0f
        // Thin ring TOUCHING the hole directly, not floating off it with a gap, ringStrokePx is
        // the ring's own thickness (~2dp, a thin band rather than the old 6dp band that read as
        // a wide 20px halo standing off the camera), and ringR (its centerline radius) is set so
        // the ring's INNER edge lands exactly on holeR with zero gap.
        val ringStrokePx = with(density) { 2.dp.toPx() }
        val ringR = holeR + ringStrokePx / 2f
        var rivetOriginInWindow by remember { mutableStateOf(Offset.Zero) }
        // The tape itself is only 56dp wide, the ring needs a little more room than that on
        // whichever side faces INTO the screen (away from the true edge), or it gets clipped by
        // this narrow Canvas's own bounds. Drawn in its own wider, separately-aligned Canvas
        // below instead of the ticks/labels one, so it's never clipped regardless of exactly
        // how close to that inner edge the real hole happens to sit on this device.
        val rivetExtraRoom = with(density) { 26.dp.toPx() }

        Canvas(Modifier.fillMaxSize()) {
            val dodgeMargin = 3.dp.toPx()

            val h = size.height
            val pxPerUnit = h / span
            val centerY = h / 2f

            val firstMinor = kotlin.math.floor((safe - span / 2f) / minorStep) * minorStep
            val lastMinor = safe + span / 2f
            var u = firstMinor
            while (u <= lastMinor) {
                val y = centerY - (u - safe) * pxPerUnit
                val isMajor = kotlin.math.abs(u % majorStep) < 0.01f || kotlin.math.abs(u % majorStep - majorStep) < 0.01f
                val c = colorFor(u)
                val tickLen = if (isMajor) 20.dp.toPx() else 11.dp.toPx()
                // Each tick is a bright colored stroke wrapped in a dark under-stroke that's both
                // wider AND blurred, an omnidirectional dark halo around the line, so the hash
                // stays legible over ANY camera content (bright sky, dark ground) with the glass
                // itself fully clear. The blur (nativeCanvas Paint + setShadowLayer at 0,0) makes
                // it a soft glow rather than a hard second line.
                val (tickStart, tickEnd) =
                    if (alignEdge == Alignment.CenterEnd) Offset(size.width - tickLen, y) to Offset(size.width, y)
                    else Offset(0f, y) to Offset(tickLen, y)
                val glowPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(150, 0, 0, 0)
                    strokeWidth = 5.dp.toPx()
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    isAntiAlias = true
                    setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
                }
                drawContext.canvas.nativeCanvas.drawLine(tickStart.x, tickStart.y, tickEnd.x, tickEnd.y, glowPaint)
                drawLine(c, tickStart, tickEnd, strokeWidth = 3.dp.toPx())
                if (isMajor) {
                    val paint = android.graphics.Paint().apply {
                        color = c.toArgb()
                        textSize = 13.sp.toPx()
                        isAntiAlias = true
                        typeface = monoTypeface
                        isFakeBoldText = true
                        setShadowLayer(5.dp.toPx(), 0f, 0f, android.graphics.Color.argb(230, 0, 0, 0))
                        textAlign = if (alignEdge == Alignment.CenterEnd) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
                    }
                    // Only this number's own x-position dodges, the rest of the tape (ticks,
                    // other labels) stays exactly where it always was. If this label's row
                    // falls within the rivet ring's vertical span, push it in past the ring's
                    // far edge instead of the usual small offset from the tick.
                    val dodging = cutoutLocal != null && y >= cutoutLocal.center.y - ringR - dodgeMargin && y <= cutoutLocal.center.y + ringR + dodgeMargin
                    val tx = if (dodging) {
                        if (alignEdge == Alignment.CenterEnd) size.width - (ringR * 2) - tickLen - dodgeMargin
                        else tickLen + (ringR * 2) + dodgeMargin
                    } else if (alignEdge == Alignment.CenterEnd) size.width - tickLen - 4.dp.toPx() else tickLen + 4.dp.toPx()
                    drawContext.canvas.nativeCanvas.drawText(u.roundToInt().toString(), tx, y + 4.dp.toPx(), paint)
                }
                u += minorStep
            }
            // Center reference line marking the exact current value, bright white over a blurred
            // dark glow so it reads on any background.
            val refGlow = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(150, 0, 0, 0)
                strokeWidth = 4.5.dp.toPx()
                isAntiAlias = true
                setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
            }
            drawContext.canvas.nativeCanvas.drawLine(0f, centerY, size.width, centerY, refGlow)
            drawLine(Color.White, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.5.dp.toPx())
        }

        // The punch-hole camera, dressed as a panel rivet with a live gauge ring and a curved
        // readout following its own edge, drawn in its OWN wider Canvas (not the ticks one
        // above) so the ring is never clipped by the narrow 56dp tape on whichever side faces
        // inward. Only appears on whichever tape's bounds the OS-reported cutout rect actually
        // falls within, live, every frame, flip the phone the other way in its mount and this
        // simply activates on the other tape instead, no separate orientation tracking needed.
        if (cutoutLocal != null) {
            Canvas(
                Modifier
                    .align(alignEdge)
                    .fillMaxHeight()
                    .width(56.dp + with(density) { rivetExtraRoom.toDp() })
                    .onGloballyPositioned { rivetOriginInWindow = it.boundsInWindow().topLeft },
            ) {
                val local = cutoutPx!!.translate(-rivetOriginInWindow.x, -rivetOriginInWindow.y)
                val center = local.center
                val r = holeR
                val gaugeColor = colorFor(safe)
                val fraction = (safe / gaugeMax).coerceIn(0f, 1f)

                // Dim full track, then the live fill arc on top of it, same "how full is this
                // gauge" language as a fuel/battery ring, tracking this tape's actual value.
                // Thin (~2dp) and touching the hole directly (see ringR's own doc comment), 
                // the earlier 6dp band standing 1.5dp+ off the hole read as a wide ~20px halo
                // floating near the camera rather than a ring actually on it.
                drawArc(
                    color = Color.White.copy(alpha = 0.15f), startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(center.x - ringR, center.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                    style = Stroke(width = ringStrokePx, cap = StrokeCap.Round),
                )
                drawArc(
                    color = gaugeColor, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                    topLeft = Offset(center.x - ringR, center.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                    style = Stroke(width = ringStrokePx, cap = StrokeCap.Round),
                )
                // The rivet doubles as an actual mini radial gauge for whichever tape it happens
                // to sit on, 12 clock-face ticks around the ring, plus a bright reticle needle
                // at the SAME fraction the fill arc above already tracks, so it reads as one
                // instrument (linear tape + round dial showing the identical live value) instead
                // of a plain decorative ring. Lives on this shared PfdTape, so it activates on
                // whichever tape (speed or altitude) the OS-reported cutout falls on this frame, 
                // flip the phone the other way and it's already drawing on the other tape too,
                // no separate per-orientation copy needed.
                val ringOuterR = ringR + ringStrokePx / 2f
                val tickInnerR = ringOuterR + 1.dp.toPx()
                val tickOuterR = tickInnerR + 3.dp.toPx()
                for (i in 0 until 12) {
                    val angleRad = Math.toRadians((-90.0 + 360.0 * i / 12))
                    val dx = kotlin.math.cos(angleRad).toFloat()
                    val dy = kotlin.math.sin(angleRad).toFloat()
                    drawLine(
                        color = Color.White.copy(alpha = 0.30f),
                        start = Offset(center.x + dx * tickInnerR, center.y + dy * tickInnerR),
                        end = Offset(center.x + dx * tickOuterR, center.y + dy * tickOuterR),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                val needleRad = Math.toRadians((-90.0 + 360.0 * fraction))
                val ndx = kotlin.math.cos(needleRad).toFloat()
                val ndy = kotlin.math.sin(needleRad).toFloat()
                drawLine(
                    color = gaugeColor,
                    start = Offset(center.x + ndx * (ringR - ringStrokePx), center.y + ndy * (ringR - ringStrokePx)),
                    end = Offset(center.x + ndx * (tickOuterR + 2.dp.toPx()), center.y + ndy * (tickOuterR + 2.dp.toPx())),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
                // Beveled metal bezel bridging the gauge ring and the physical hole.
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF565C64), Color(0xFF2A2E33), Color(0xFF16181B)), center = center, radius = r),
                    radius = r, center = center,
                )
                drawCircle(Color.Black, radius = r * 0.7f, center = center)
                // Specular highlight + flat-head screw slot, the details that sell "rivet."
                drawArc(
                    color = Color.White.copy(alpha = 0.35f), startAngle = 200f, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(center.x - r - 1.dp.toPx(), center.y - r - 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((r + 1.dp.toPx()) * 2, (r + 1.dp.toPx()) * 2),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawLine(
                    Color(0xFF4A4F55), Offset(center.x - r * 0.5f, center.y), Offset(center.x + r * 0.5f, center.y),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
            // Readout chip, OUTSIDE the ring entirely (not curved text following the ring's edge, 
            // that landed ON/inside the physically-opaque hole itself, invisible). Two earlier
            // placements each failed: a value-keyed "elevator" drifted the number across the live
            // camera PiP; then pinning it BESIDE the lens (offset inward toward screen center)
            // pushed it ~ring+40dp in from the edge, straight under RightCameraPanel/NavCluster,
            // which sit just inside this tape's own column (end≈58dp) and are drawn AFTER the tape,
            // so they covered it. Fixed by keeping the chip inside the tape's OWN narrow edge strip
            // and offsetting it VERTICALLY (above the lens, or below if there's no room above)
            // instead of inward, the edge strip is never under those inset panels. Centered
            // horizontally on the lens and clamped to the tape width so it can't spill inward.
            // cutoutLocal is already translated into this Box's coordinate frame (NOT the wider
            // rivet Canvas's, that mismatch is what put an earlier version on the ring itself).
            val ringCenterLocal = cutoutLocal.center
            val ringOuterR = ringR + ringStrokePx / 2f
            val boxW = 48.dp; val boxH = 24.dp
            val boxWpx = with(density) { boxW.toPx() }
            val boxHpx = with(density) { boxH.toPx() }
            val clearancePx = with(density) { 8.dp.toPx() }
            val topMarginPx = with(density) { 4.dp.toPx() }
            // Prefer sitting ABOVE the lens; drop below only if there isn't room above.
            val aboveY = ringCenterLocal.y - ringOuterR - clearancePx - boxHpx
            val chipY = if (aboveY >= topMarginPx) aboveY else ringCenterLocal.y + ringOuterR + clearancePx
            // Horizontally centered on the lens, clamped so the whole chip stays within the tape
            // column (never past its inner edge into the panel zone).
            val chipX = (ringCenterLocal.x - boxWpx / 2f).coerceIn(0f, (boxSizePx.width - boxWpx).coerceAtLeast(0f))
            Box(
                Modifier
                    .offset { IntOffset(chipX.roundToInt(), chipY.roundToInt()) }
                    .size(width = boxW, height = boxH)
                    // Opaque near-black chip with a thin status-colored border and status-colored
                    // text, reads cleanly over ANY camera content (bright sky, dark ground) where
                    // the old translucent colored-fill + black-text chip washed out. Color still
                    // carries the range (red/amber/green), just as the border+text instead of a
                    // see-through background.
                    .background(Color(0xE6101418), RoundedCornerShape(5.dp))
                    .border(1.5.dp, colorFor(safe), RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("%.1f".format(safe), color = colorFor(safe), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono, maxLines = 1)
            }
        }
        // On a tape with no cutout on it, the readout stays the plain center box it always
        // was, the curved-along-the-ring version above only makes sense once there's an
        // actual ring to follow.
        if (cutoutLocal == null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .background(colorFor(safe).copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text("%.1f".format(safe), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(unit, color = TextSec, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
    }
}

@Composable
private fun SpeedTape(speed: Float, hazeState: HazeState?, cutoutPx: androidx.compose.ui.geometry.Rect?, modifier: Modifier) {
    PfdTape(
        value = speed, unit = "m/s", span = 20f, majorStep = 5f, minorStep = 1f,
        colorFor = { v -> if (v < 0.5f) TextSec else if (v > 15f) DjiAmber else DjiGreen },
        alignEdge = Alignment.CenterStart, hazeState = hazeState, cutoutPx = cutoutPx, gaugeMax = 20f,
        modifier = modifier,
    )
}

@Composable
private fun AltitudeTape(
    altitude: Float, hazeState: HazeState?, cutoutPx: androidx.compose.ui.geometry.Rect?,
    maxHeightM: Float, modifier: Modifier,
) {
    PfdTape(
        value = altitude, unit = "m AGL", span = 60f, majorStep = 10f, minorStep = 2f,
        // Scaled to the real configured ceiling instead of a flat 120m: red/amber now mean
        // "near or past THIS aircraft's actual limit," not an arbitrary fixed number.
        colorFor = { v -> when {
            v < 5f -> DjiRed
            v > maxHeightM -> DjiRed
            v > maxHeightM * 0.85f -> DjiAmber
            v < 15f -> DjiAmber
            else -> DjiGreen
        } },
        alignEdge = Alignment.CenterEnd, hazeState = hazeState, cutoutPx = cutoutPx, gaugeMax = maxHeightM,
        modifier = modifier,
    )
}

/**
 * Small always-visible control (regardless of [DisplayMode]) for what this phone's own screen
 * shows, plus a shortcut into Android's own Cast/Smart View picker. GlassFalcon has no streaming
 * pipeline of its own here, Android's built-in screen-cast already mirrors whatever's on the
 * phone's display to a paired TV/receiver over the OS's own transport; the only thing worth
 * building is (a) letting the pilot choose WHAT that mirrored content is (full HUD vs. a clean
 * view for spectators) without leaving the flight screen, and (b) a one-tap shortcut into the
 * system Cast picker instead of digging through Settings/Quick Settings mid-flight.
 * `Settings.ACTION_CAST_SETTINGS` is a public, documented intent action (API 21+) that opens the
 * OS's own Cast device picker directly.
 */
@Composable
private fun CastControl(displayMode: DisplayMode, onModeChange: (DisplayMode) -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box(modifier) {
        Box(
            Modifier.size(28.dp).glass(shape = RoundedCornerShape(6.dp), baseAlpha = 0.3f)
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) { Text("📺", fontSize = 13.sp) }
        if (expanded) {
            Column(
                Modifier.padding(top = 32.dp).width(180.dp)
                    .glass(shape = RoundedCornerShape(8.dp), baseAlpha = 0.4f).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("SCREEN SHOWS", color = TextSec, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                listOf(
                    DisplayMode.FULL to "Full HUD",
                    DisplayMode.MINIMAL to "Minimal HUD",
                    DisplayMode.CAMERA_ONLY to "Camera only",
                    DisplayMode.MAP_ONLY to "Map only",
                ).forEach { (mode, label) ->
                    Text(
                        (if (mode == displayMode) "● " else "○ ") + label,
                        color = if (mode == displayMode) DjiGreen else TextPri, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onModeChange(mode); expanded = false },
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "📡  Cast to TV…", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clickable {
                        expanded = false
                        try {
                            context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                        } catch (_: Exception) {
                            // Some OEM builds don't ship this exact settings screen, the Quick
                            // Settings Cast tile the pilot already knows is the fallback.
                        }
                    },
                )
            }
        }
    }
}

/**
 * All-around vision radar, front(A)/back(B) each render as a semicircular halo whose FLAT edge
 * sits flush against the glass HUD frame's own inner boundary (so it reads as growing OUT of the
 * glass, not a separate floating shape) and whose curved edge bulges into the open video, sized
 * and colored by how close the reading is. A flat rectangular edge-gradient (the previous
 * design) didn't have this "the glass itself is responding" quality, a dome anchored right at
 * the frame seam does. A slow breathing pulse (faster/stronger the closer the obstacle) gives it
 * the "live sonar ping" read the flat version lacked. Left/right get only a faint dashed
 * hairline at their edges: the real wm240 has lateral vision sensors too (DJI's own spec confirms
 * it, active in ActiveTrack/POI/Tripod modes), GlassFalcon just has no DUML frame decoded for
 * them yet, so the gap stays visible rather than fabricating a glow with no data behind it.
 * Which channel (A/B) is physically front vs. back is not independently confirmed, see
 * ObstacleState's doc comment in Telemetry.kt, front/back is the most plausible mapping, not an
 * asserted fact. [frameTopPx]/[frameBottomPx] are HudFrameShape's own top/bottom band depths (in
 * raw px) so the dome's flat edge lines up EXACTLY with the glass frame's inner edge regardless
 * of screen size, not a guessed fixed inset.
 */
@Composable
private fun ObstacleEdgeGlow(o: ObstacleState, frameTopPx: Float, frameBottomPx: Float, showRing: Boolean, modifier: Modifier) {
    if (!o.valid) return
    fun colorFor(v: Int): Color = when { v < 50 -> DjiRed; v < 150 -> DjiAmber; else -> DjiGreen }
    // Closeness ramps from 0 (300cm+, faint) to 1 (0cm, fully bright), still ambient, just a
    // touch more present than the original near-invisible floor/height so it actually registers.
    fun closeness(v: Int) = (1f - (v.coerceIn(0, 300) / 300f)).coerceIn(0.16f, 1f)

    // "Open"-filtering and channel-closest logic now live on ObstacleState itself (Telemetry.kt)
    // so TapFlyController's stop-on-obstacle check shares the exact same reading, not a second
    // hand-copied version of it.
    val front = o.frontClosest
    val back  = o.backClosest

    // Shared breathing clock, a single slow cycle (1..0..1) that both domes sample at their own
    // closeness-scaled rate, rather than each running its own independent animation.
    val pulse = rememberInfiniteTransition(label = "obstaclePulse")
    val pulsePhase by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "obstaclePulsePhase",
    )
    // LIVE wobble clock, this used to be a rememberInfiniteTransition on a fixed 7-second
    // RepeatMode.Restart loop, which meant the ENTIRE waveform replayed bit-for-bit identically
    // every single cycle, forever, regardless of what the sensor actually reported, a genuinely
    // canned animation with no connection to real data. Replaced with a clock that never restarts
    // (morphMs only ever accumulates) and whose RATE reacts to how much the raw channel readings
    // are actually changing right now: near-idle when the two beams hold steady, visibly busier
    // the moment a reading starts moving. `rememberUpdatedState` lets this long-lived effect keep
    // reading the LATEST ObstacleState every frame without needing to restart when `o` changes
    // (restarting on every new DUML frame would itself reintroduce a stutter/reset artifact).
    val latestObstacle = rememberUpdatedState(o)
    var morphMs by remember { mutableStateOf(0f) }
    var frontActivity by remember { mutableStateOf(0f) }
    var backActivity by remember { mutableStateOf(0f) }
    var leftActivity by remember { mutableStateOf(0f) }
    var rightActivity by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastFrameMs = 0L
        var lastA1 = latestObstacle.value.channelA1; var lastA2 = latestObstacle.value.channelA2
        var lastB1 = latestObstacle.value.channelB1; var lastB2 = latestObstacle.value.channelB2
        var lastC = latestObstacle.value.channelC; var lastD = latestObstacle.value.channelD
        while (true) {
            withFrameMillis { now ->
                val dt = if (lastFrameMs == 0L) 16L else (now - lastFrameMs).coerceIn(1, 100)
                lastFrameMs = now
                val cur = latestObstacle.value
                val dA = kotlin.math.abs(cur.channelA1 - lastA1) + kotlin.math.abs(cur.channelA2 - lastA2)
                val dB = kotlin.math.abs(cur.channelB1 - lastB1) + kotlin.math.abs(cur.channelB2 - lastB2)
                val dC = kotlin.math.abs(cur.channelC - lastC)
                val dD = kotlin.math.abs(cur.channelD - lastD)
                lastA1 = cur.channelA1; lastA2 = cur.channelA2; lastB1 = cur.channelB1; lastB2 = cur.channelB2
                lastC = cur.channelC; lastD = cur.channelD
                // Spike on real change (scaled so a ~60cm combined shift in one update saturates
                // it), decay smoothly every frame otherwise, a "how alive is this reading right
                // now" signal that genuinely tracks the sensor, not a metronome.
                frontActivity = (frontActivity * 0.985f + dA / 60f).coerceIn(0f, 1f)
                backActivity = (backActivity * 0.985f + dB / 60f).coerceIn(0f, 1f)
                leftActivity = (leftActivity * 0.985f + dC / 60f).coerceIn(0f, 1f)
                rightActivity = (rightActivity * 0.985f + dD / 60f).coerceIn(0f, 1f)
                val liveActivity = maxOf(maxOf(frontActivity, backActivity), maxOf(leftActivity, rightActivity))
                morphMs += dt * (0.12f + liveActivity * 1.1f)
            }
        }
    }
    val morphPhase = morphMs / 1000f

    // Everything the bubble needs is ANIMATED so it reads as one live blob that glides, not a
    // shape that snaps between discrete sensor readings. Lean eases side-to-side; closeness eases
    // the size/alpha (to 0 = fades out when that side clears). All via animateFloatAsState so the
    // motion is continuous even though the underlying readings arrive in steps.
    val frontLeanA by animateFloatAsState(o.frontLean ?: 0f, tween(650, easing = LinearEasing), label = "frontLean")
    val backLeanA by animateFloatAsState(o.backLean ?: 0f, tween(650, easing = LinearEasing), label = "backLean")
    val frontCloseA by animateFloatAsState(front?.let { closeness(it) } ?: 0f, tween(450, easing = LinearEasing), label = "frontClose")
    val backCloseA by animateFloatAsState(back?.let { closeness(it) } ?: 0f, tween(450, easing = LinearEasing), label = "backClose")
    val frontColor = front?.let { colorFor(it) } ?: DjiGreen
    val backColor = back?.let { colorFor(it) } ?: DjiGreen
    // Lateral left/right, the C/D single-beam sensors (see ObstacleState.leftClosest/rightClosest).
    val leftCloseA by animateFloatAsState(o.leftClosest?.let { closeness(it) } ?: 0f, tween(450, easing = LinearEasing), label = "leftClose")
    val rightCloseA by animateFloatAsState(o.rightClosest?.let { closeness(it) } ?: 0f, tween(450, easing = LinearEasing), label = "rightClose")
    val leftColor = o.leftClosest?.let { colorFor(it) } ?: DjiGreen
    val rightColor = o.rightClosest?.let { colorFor(it) } ?: DjiGreen

    // The DUML vision frame gives exactly TWO independent sub-beam distances per direction
    // (channelX1/X2, see ObstacleState's doc comment in Telemetry.kt: no depth camera, no point
    // cloud, just two numbers per side). That's the full "shape" detail that actually exists, so
    // the blob's own asymmetry is driven DIRECTLY from these two real readings per side, whichever
    // sub-beam sees something closer visibly swells that side of the outline, an open sub-beam lets
    // that side recede, instead of a decorative wobble unrelated to what the sensor saw.
    fun subCloseness(v: Int) = if (v <= 0 || v >= 990) 0f else (1f - (v.coerceIn(0, 300) / 300f))
    val frontLeftA by animateFloatAsState(subCloseness(o.channelA1), tween(450, easing = LinearEasing), label = "frontLeft")
    val frontRightA by animateFloatAsState(subCloseness(o.channelA2), tween(450, easing = LinearEasing), label = "frontRight")
    val backLeftA by animateFloatAsState(subCloseness(o.channelB1), tween(450, easing = LinearEasing), label = "backLeft")
    val backRightA by animateFloatAsState(subCloseness(o.channelB2), tween(450, easing = LinearEasing), label = "backRight")

    // Never fully opaque anywhere in the radar, core, aura, or ring, so the pilot can always see
    // straight through every part of it to the video/obstacle behind, even at 0cm and full
    // breathing peak. Urgency is still conveyed by color, size, and pulse speed; it never blocks
    // the view outright.
    val MAX_FILL_ALPHA = 0.5f
    val MAX_RING_ALPHA = 0.6f

    Box(modifier) {
        // A closer reading pulses faster (up to ~3x) and with more amplitude, an object at 20cm
        // should feel more urgent than one at 250cm, not just brighter.
        fun breathe(c: Float): Float {
            val speed = 1f + c * 2f
            val t = (pulsePhase * speed) % 1f
            val wave = kotlin.math.sin(t * 2f * Math.PI.toFloat()) * 0.5f + 0.5f
            return 1f - c * 0.25f * wave
        }

        Canvas(Modifier.fillMaxSize()) {
            // Builds a smooth, organic MOUND anchored to the HUD frame edge: its flat base sits
            // exactly on [baseY] (the glass seam) and only its curved crest bulges into the video,
            // so the shape can never read as a free-floating ball, it visibly grows OUT of the
            // frame it's attached to. Points are sampled along a half-outline (0..π); each point's
            // height is perturbed by TWO things: (1) the REAL directional signal, leftAmt/rightAmt
            // are the actual sub-beam closeness readings for this side (channelX1/X2), blended by
            // how far left/right this point sits, so the flank facing the closer beam visibly
            // swells while the flank facing an open beam recedes; and (2) a light organic wobble
            // (two sine harmonics, scaled by [wobbleAmp], pass 0 for a perfectly smooth dome) so
            // it still reads as living rather than a static lump. The two base endpoints have zero
            // height by construction (sin(0)=sin(π)=0), so the base stays welded to the seam no
            // matter how hard the crest churns. Stitched via "quadratic through midpoints" for a
            // soft lobed crest, then closed with a straight line along the seam.
            // [alongC]/[edge] are the mound's center ALONG its base and the fixed edge coordinate
            // it's welded to; [grow] (+1/-1) is which way it bulges off that edge; [horizontal]
            // swaps the two axes so the SAME machinery draws a top/bottom mound (grows vertically)
            // or a left/right mound (grows horizontally), used for the lateral C/D sensors.
            fun moundPath(alongC: Float, edge: Float, halfSpan: Float, h: Float, grow: Float, horizontal: Boolean, seed: Float, leftAmt: Float, rightAmt: Float, activity: Float, wobbleAmp: Float): Path {
                val n = 10
                val t = (morphPhase + seed) * 2f * Math.PI.toFloat()
                // Idle baseline keeps the crest subtly alive even with a perfectly steady
                // reading (so it never looks frozen/dead), but the wobble genuinely intensifies
                // only when the real sub-beam values are actively changing right now.
                val ampScale = (0.45f + activity * 1.3f) * wobbleAmp
                val pts = (0..n).map { i ->
                    val ang = Math.PI.toFloat() * i / n
                    // sideT: 1 at the base end facing full screen-right, 0 at full screen-left, 
                    // independent of grow direction, since left/right on screen never flips.
                    val sideT = (kotlin.math.cos(ang) + 1f) / 2f
                    val directional = 1f + 0.55f * (leftAmt * (1f - sideT) + rightAmt * sideT)
                    val wob = directional *
                        (1f + 0.10f * ampScale * kotlin.math.sin(t * 1.3f + i * 2.1f) + 0.05f * ampScale * kotlin.math.sin(t * 2.2f + i * 1.4f + 1.7f))
                    val a = alongC + kotlin.math.cos(ang) * halfSpan          // position along the base
                    val g = edge + grow * kotlin.math.sin(ang) * h * wob      // height into the video
                    if (horizontal) Offset(g, a) else Offset(a, g)
                }
                fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val path = Path()
                path.moveTo(pts.first().x, pts.first().y)
                for (i in 1 until pts.size - 1) {
                    val m = mid(pts[i], pts[i + 1])
                    path.quadraticBezierTo(pts[i].x, pts[i].y, m.x, m.y)
                }
                path.lineTo(pts.last().x, pts.last().y)
                path.close()
                return path
            }

            // A swelling anchored to the glass seam, an organic wobbling crest around a small
            // STABLE core, both flat-based on the frame edge. The core is a smooth half-dome at a
            // fixed position/size (it's the "usable" half: a pilot can still read exactly where the
            // reading is and gauge its size at a glance); the crest around it is the amorphous,
            // organic part whose asymmetry conforms to the real per-side beam data. The base is
            // deliberately much wider than the mound is tall, another "the frame itself is
            // swelling" cue, a tall narrow shape would drift back toward reading as a ball.
            fun bubble(
                cm: Int?, closenessA: Float, leanA: Float, color: Color, edgeY: Float, bulgeDown: Boolean, seed: Float,
                leftAmt: Float, rightAmt: Float, activity: Float,
            ) {
                if (closenessA <= 0.02f) return
                val breath = breathe(closenessA)
                // How far the crest reaches into the video, and the half-width of the seam it
                // grows from.
                val h = (36.dp.toPx() + closenessA * 140.dp.toPx()) * breath
                val halfW = h * 1.7f
                // Travels up to ~34% of the width either side of center.
                val cx = size.width / 2f + leanA * size.width * 0.34f
                // Capped well under 1, every fill in this mound stays see-through, no matter how
                // close/urgent the reading, so the video is never fully blocked by the radar itself.
                val alpha = (closenessA * 0.8f * breath).coerceIn(0f, MAX_FILL_ALPHA)

                val grow = if (bulgeDown) 1f else -1f
                val outer = moundPath(cx, edgeY, halfW, h, grow, horizontal = false, seed, leftAmt, rightAmt, activity, wobbleAmp = 1f)
                // Glow radiates FROM the seam point outward, so the brightest part is where the
                // shape meets the glass and it fades as it reaches into the video, light leaking
                // out of the frame, not a sphere lit from its own center.
                drawPath(
                    outer,
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = alpha),
                        0.55f to color.copy(alpha = (alpha * 0.4f).coerceAtMost(MAX_FILL_ALPHA)),
                        1f to Color.Transparent,
                        center = Offset(cx, edgeY), radius = h * 1.6f,
                    ),
                )
                // The stable core, the same anchored mound at ~45% scale with zero wobble, so it
                // stays a calm smooth dome. This is what makes the shape "usable": the reading's
                // exact position and relative size are always readable off this even while the
                // outer crest churns around it.
                val core = moundPath(cx, edgeY, halfW * 0.45f, h * 0.45f, grow, horizontal = false, seed, leftAmt, rightAmt, activity, wobbleAmp = 0f)
                drawPath(core, color = color.copy(alpha = alpha))
                // Optional crisp rim (radarRing toggle), traces the SAME mound outline (including
                // its base along the seam, which reads as the glass edge itself lighting up), so
                // enabling it reinforces the anchored shape instead of flattening it into a circle.
                if (showRing) drawPath(outer, color = color.copy(alpha = (alpha * 1.2f).coerceAtMost(MAX_RING_ALPHA)), style = Stroke(width = 2.2.dp.toPx()))
                // Inside ~1.5 m the mound also states the actual distance, following it as it moves.
                if (cm != null && cm < 150) {
                    val paint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = 14.sp.toPx()
                        isAntiAlias = true
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        setShadowLayer(4.dp.toPx(), 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
                    }
                    val textY = edgeY + (if (bulgeDown) 1f else -1f) * h * 0.5f
                    drawContext.canvas.nativeCanvas.drawText("⚠ %.1f m".format(cm / 100f), cx, textY + 5.sp.toPx(), paint)
                }
            }

            // Lateral (left/right) mound, the C/D single-beam sensors. Grows horizontally OUT of
            // the left or right screen edge with the same anchored-mound look as front/back. These
            // channels have no L/R sub-pair (one beam each), so there's no directional skew to feed
            //, the shape is a clean symmetric swell whose size/color/pulse still track the real
            // distance. Only drawn when the channel is actually reporting (closenessA > 0).
            fun sideBubble(cm: Int?, closenessA: Float, color: Color, edgeX: Float, fromLeft: Boolean, seed: Float, activity: Float) {
                if (closenessA <= 0.02f) return
                val breath = breathe(closenessA)
                val h = (36.dp.toPx() + closenessA * 140.dp.toPx()) * breath
                val halfSpan = h * 1.7f
                val cy = size.height / 2f
                val alpha = (closenessA * 0.8f * breath).coerceIn(0f, MAX_FILL_ALPHA)
                val grow = if (fromLeft) 1f else -1f
                val outer = moundPath(cy, edgeX, halfSpan, h, grow, horizontal = true, seed, leftAmt = 0f, rightAmt = 0f, activity, wobbleAmp = 1f)
                drawPath(
                    outer,
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = alpha),
                        0.55f to color.copy(alpha = (alpha * 0.4f).coerceAtMost(MAX_FILL_ALPHA)),
                        1f to Color.Transparent,
                        center = Offset(edgeX, cy), radius = h * 1.6f,
                    ),
                )
                val core = moundPath(cy, edgeX, halfSpan * 0.45f, h * 0.45f, grow, horizontal = true, seed, 0f, 0f, activity, wobbleAmp = 0f)
                drawPath(core, color = color.copy(alpha = alpha))
                if (showRing) drawPath(outer, color = color.copy(alpha = (alpha * 1.2f).coerceAtMost(MAX_RING_ALPHA)), style = Stroke(width = 2.2.dp.toPx()))
                if (cm != null && cm < 150) {
                    val paint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = 14.sp.toPx()
                        isAntiAlias = true
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        setShadowLayer(4.dp.toPx(), 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
                    }
                    val textX = edgeX + grow * h * 0.5f
                    drawContext.canvas.nativeCanvas.drawText("⚠ %.1f m".format(cm / 100f), textX, cy + 5.sp.toPx(), paint)
                }
            }

            // Front/back mounds are CENTERED (lean 0, symmetric swell): live data proved the front
            // channel reports only a single forward distance with no left/right discrimination
            // (a fixed obstacle read the same beam whether it was left or right of the aircraft),
            // so faking a lean off the always-open 2nd sub-beam just slammed the mound to one side.
            // Only the lateral C/D sensors (sideBubble) carry real left/right, and only in ActiveTrack.
            bubble(front, frontCloseA, 0f, frontColor, frameTopPx, bulgeDown = true, seed = 0f, leftAmt = 0f, rightAmt = 0f, activity = frontActivity)
            bubble(back, backCloseA, 0f, backColor, size.height - frameBottomPx, bulgeDown = false, seed = 0.41f, leftAmt = 0f, rightAmt = 0f, activity = backActivity)
            sideBubble(o.leftClosest, leftCloseA, leftColor, 0f, fromLeft = true, seed = 0.73f, activity = leftActivity)
            sideBubble(o.rightClosest, rightCloseA, rightColor, size.width, fromLeft = false, seed = 0.29f, activity = rightActivity)
        }
        // Faint "lateral sensor lives here" hairlines, the C/D left/right sensors only carry data
        // in low-speed ActiveTrack, so most of the time there's no mound to draw; the hairline
        // marks where one WOULD grow. Hidden on a side the moment that side is actually reporting,
        // so it never sits underneath (and fights) its own live mound.
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 14f))
        if (leftCloseA <= 0.02f) Canvas(Modifier.fillMaxHeight().width(2.dp).align(Alignment.CenterStart)) {
            drawLine(TextSec.copy(alpha = 0.18f), Offset(1f, 0f), Offset(1f, size.height), strokeWidth = 2f, pathEffect = dash)
        }
        if (rightCloseA <= 0.02f) Canvas(Modifier.fillMaxHeight().width(2.dp).align(Alignment.CenterEnd)) {
            drawLine(TextSec.copy(alpha = 0.18f), Offset(1f, 0f), Offset(1f, size.height), strokeWidth = 2f, pathEffect = dash)
        }
    }
}

/** Top-of-HUD banner announcing a physical RC button press, which button (C1/C2/…) and the
 *  item it's bound to. Sticky flashes (a toggle that latched ON, PTT held) stay up; momentary
 *  ones auto-clear after 2.5 s. See FlightViewModel.emitButtonFlash. */
@Composable
private fun RcButtonFlashOverlay(vm: FlightViewModel, modifier: Modifier) {
    val flash by vm.rcButtonFlash.collectAsState()
    var shown by remember { mutableStateOf(flash) }
    LaunchedEffect(flash) {
        if (flash != null) shown = flash
        val f = flash
        if (f != null && !f.sticky) { delay(2500); vm.clearButtonFlash(f.id) }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = flash != null,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val f = shown
        if (f != null) {
            val col = if (f.sticky) DjiGreen else Gold
            Box(
                Modifier.background(Color(0xE6101418), RoundedCornerShape(50))
                    .border(1.5.dp, col, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(f.text, color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono)
            }
        }
    }
}

/** Visual confirmation for a capture command, the same event [FlightViewModel] uses to trigger
 *  a phone shutter/record sound and a best-effort RC240 buzz (see `fireCaptureCue`), so a
 *  physical-button press gets feedback even though the pilot's eyes are on the aircraft, not
 *  this screen. A quick white flash for a photo (mirrors a real camera shutter); a short banner
 *  for record start/stop, since "recording" is a state change worth a beat longer than a flash. */
@Composable
private fun CaptureCueOverlay(vm: FlightViewModel, modifier: Modifier) {
    val cue by vm.captureCue.collectAsState()
    var flashOn by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Pair<String, Color>?>(null) }

    LaunchedEffect(cue) {
        when (cue?.first) {
            FlightViewModel.CaptureCue.PHOTO -> {
                flashOn = true
                delay(80)
                flashOn = false
            }
            FlightViewModel.CaptureCue.RECORD_START -> {
                banner = "● RECORDING" to DjiRed
                delay(1200)
                banner = null
            }
            FlightViewModel.CaptureCue.RECORD_STOP -> {
                banner = "■ RECORDING STOPPED" to TextSec
                delay(1200)
                banner = null
            }
            null -> {}
        }
    }
    val flashAlpha by animateFloatAsState(
        if (flashOn) 0.85f else 0f,
        animationSpec = tween(if (flashOn) 30 else 220),
        label = "captureFlash",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        if (flashAlpha > 0.01f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
        banner?.let { (text, color) ->
            Surface(color = color.copy(alpha = 0.88f), shape = MaterialTheme.shapes.medium) {
                Text(
                    text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// 8-point compass rose, letters instead of numbers at the intercardinals so a pilot can read
// "which way am I pointed" at a glance without having memorized what 135° means.
private val COMPASS_POINTS = mapOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")

// One fixed color per cardinal, smoothly blended between the two nearest as heading changes, 
// a pilot glancing at the pointer/bar color alone (not reading the number) still gets a coarse
// "which way" cue, and a snap heading swing reads as a color shift, not just a number changing.
private val COMPASS_COLORS = listOf(
    DjiGreen,           // N
    Color(0xFF00E5FF),  // E, cyan
    Color(0xFFFF00C8),  // S, magenta
    Color(0xFF8A2BE2),  // W, violet
)
private fun headingColor(heading: Float): Color {
    val step = heading / 90f
    val i = kotlin.math.floor(step).toInt().mod(4)
    val frac = step - kotlin.math.floor(step)
    // hsvBlend (hue rotation), not lerp() (straight RGB interpolation), RGB-blending e.g.
    // cyan and magenta passes through a dull, muddy blue-gray instead of a vivid intermediate
    // hue. This is the same "mesh gradient" quality request as HudFrameOutline's corner blend:
    // organic, cloud-like color transitions, not a flat linear fade through gray.
    return hsvBlend(COMPASS_COLORS[i], COMPASS_COLORS[(i + 1) % 4], frac)
}

/** Pushes a color toward vivid, full-ish saturation and value while keeping its hue, so a
 *  dynamic instrument color bleeds into the glass as a bold wash rather than a muted tint. */
private fun vividify(c: Color): Color {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
    return Color.hsv(hsv[0], (hsv[1] * 1.35f).coerceAtMost(1f), (hsv[2] * 1.1f).coerceIn(0f, 1f))
}

/** Hue-circle interpolation instead of a straight RGB lerp, mixing e.g. violet and green in
 *  RGB space passes through a muddy gray/brown midpoint that reads as "the color just stops"
 *  rather than an actual blend. Rotating hue the short way around the wheel (and lerping
 *  saturation/value normally) keeps the transition visibly colorful the whole way through. */
private fun hsvBlend(a: Color, b: Color, t: Float): Color {
    val ha = FloatArray(3).also { android.graphics.Color.colorToHSV(a.toArgb(), it) }
    val hb = FloatArray(3).also { android.graphics.Color.colorToHSV(b.toArgb(), it) }
    var dh = hb[0] - ha[0]
    if (dh > 180f) dh -= 360f
    if (dh < -180f) dh += 360f
    val h = (ha[0] + dh * t + 360f) % 360f
    val s = ha[1] + (hb[1] - ha[1]) * t
    val v = ha[2] + (hb[2] - ha[2]) * t
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
}

/** The top bar + both PFD tapes + heading compass's actual physical shape: the full screen
 *  minus the video-facing opening in the middle (inset by the tapes' own 56dp width and the
 *  bars' 36dp/34dp height). A frame/donut outline via even-odd fill. This exists so the 4
 *  panels can share ONE clip + ONE haze blur + ONE fill pass (see [HudGlassSurface]) instead of
 *  each drawing its own independent background, that was the actual reason a "welded" look
 *  never held up no matter how the seam/border was tuned: 4 separately-blurred, separately-
 *  tinted regions are still 4 rendered surfaces placed next to each other, not one continuous
 *  material, regardless of how well their edge colors happen to match. */
private class HudFrameShape(
    private val topHPx: Float, private val bottomHPx: Float, private val sideWPx: Float,
    private val outerCornerRadiusPx: Float,
) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val path = Path().apply {
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
            // Outer boundary follows the PHYSICAL display's own rounded corners (see
            // [rememberScreenCornerRadiusPx]) instead of a sharp rectangle, a hard 90° corner
            // drawn against a screen that's actually rounded reads as visibly wrong right at the
            // 4 corners regardless of how correct the rest of the shape is.
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                    androidx.compose.ui.geometry.CornerRadius(outerCornerRadiusPx),
                ),
            )
            addRect(androidx.compose.ui.geometry.Rect(sideWPx, topHPx, size.width - sideWPx, size.height - bottomHPx))
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/** The real per-device corner radius the OS reports for the physical display (API 31+ via
 *  [android.view.RoundedCorner]), most modern phones are NOT sharp-cornered rectangles, and a
 *  HUD frame that assumes 90° corners looks visibly wrong against a rounded screen right at the
 *  4 corners. Falls back to a conservative fixed radius on older API levels where this can't be
 *  queried, rather than assuming square. */
@Composable
private fun rememberScreenCornerRadiusPx(): Float {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view) {
        val fallback = with(density) { 32.dp.toPx() }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val insets = view.rootWindowInsets
            listOfNotNull(
                insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT),
                insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT),
                insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
                insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            ).maxOfOrNull { it.radius.toFloat() } ?: fallback
        } else fallback
    }
}

/**
 * ONE shared glass surface for the top bar + both PFD tapes + heading compass, a single clip
 * + single Haze blur pass + single fill, instead of 4 panels each drawing their own background
 * (see [HudFrameShape]'s doc comment for why that never actually welded no matter how the seam
 * was tuned). The 4 individual panel composables (TopBar/PfdTape/HeadingTape) draw ONLY their
 * own content (ticks, text, the rivet) with no background of their own anymore, this is what
 * they all sit on top of.
 *
 * The fill itself is 4 adjoining rectangles (top/right/bottom/left band, exactly matching each
 * panel's own real footprint) each with its own gradient sweeping through a real hue rotation
 * (see [hsvBlend], not a flat RGB lerp) from one corner's blend to the next, "the colors of the
 * areas change with the data" was the actual ask: the live speed/altitude/heading colors blend
 * continuously across the shared glass itself, not just along a decorative line drawn on top of
 * 4 independently-colored panels.
 */
@Composable
private fun HudGlassSurface(
    speedColor: Color, altColor: Color, headingColor: Color, hazeState: HazeState?,
    blurFill: ImageBitmap?,
    // Obstacle warning to BLEED into the band colors: (color, intensity 0..1). frontWarn tints the
    // top band + both sides toward the warning color; backWarn tints the bottom band + both sides.
    // Null when there's no obstacle in range. This is how the radar "extends into all the HUD
    // areas and bleeds into their colors", the frame's own dynamic colors shift toward the
    // warning as something gets close, not just the domes bulging into the video.
    frontWarn: Pair<Color, Float>?, backWarn: Pair<Color, Float>?,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val topHPx = with(density) { HUD_TOP_BAND.toPx() }
    val bottomHPx = with(density) { HUD_BOTTOM_BAND.toPx() }
    val sideWPx = with(density) { HUD_SIDE_BAND.toPx() }
    // Square outer boundary, the physical screen glass does the rounding; a rounded software
    // frame left a root-black crescent in the corners.
    val shape = remember(topHPx, bottomHPx, sideWPx) { HudFrameShape(topHPx, bottomHPx, sideWPx, 0f) }

    // CLEAR glass carrying ONLY the dynamic data colors on its outer edges: speed down the left
    // band, altitude down the right, heading along the bottom compass, a speed<->altitude blend
    // along the top, each strongest at the physical screen edge, fading to clear toward the video.
    // No white frost, no sheen, no black base. On top of that, an obstacle warning BLEEDS its
    // color into the relevant bands (front → top + sides, back → bottom + sides), and pushes their
    // edge alpha up, so a close object floods the whole frame with red/amber, not just the domes.
    // Corners are covered by both adjoining bands whose transparent colors blend naturally.
    fun bleed(base: Color, warn: Pair<Color, Float>?): Color =
        warn?.let { androidx.compose.ui.graphics.lerp(base, it.first, (it.second * 0.8f).coerceIn(0f, 0.8f)) } ?: base
    val strongestWarn = listOfNotNull(frontWarn, backWarn).maxByOrNull { it.second }
    val topBlend = bleed(hsvBlend(speedColor, altColor, 0.5f), frontWarn)
    // The compass color reads muted at the tape's own alpha, so saturate it for the glass bleed, 
    // the vibrant heading hue should visibly wash up into the bottom band the compass rides on,
    // matching how speed/altitude flood the left/right bands. Bump saturation and hold full value.
    val bottomCol = bleed(vividify(headingColor), backWarn)
    val leftCol = bleed(speedColor, strongestWarn)
    val rightCol = bleed(altColor, strongestWarn)
    val warnBoost = strongestWarn?.let { it.second * 0.35f } ?: 0f

    Box(modifier.clip(shape)) {
        Canvas(Modifier.fillMaxSize()) {
            val edgeAlpha = (0.4f + warnBoost).coerceAtMost(0.85f)
            // Top band, color at the top screen edge (y=0) fading down to clear at its inner edge.
            drawRect(
                Brush.verticalGradient(
                    0f to topBlend.copy(alpha = edgeAlpha), 1f to Color.Transparent,
                    startY = 0f, endY = topHPx,
                ),
                topLeft = Offset(0f, 0f), size = Size(size.width, topHPx),
            )
            // Bottom band (compass), color at the bottom screen edge fading up to clear. Given a
            // bit more edge alpha than the other bands so the vibrant heading hue clearly bleeds up
            // into the glass the compass tape rides on, per request.
            val compassAlpha = (edgeAlpha + 0.18f).coerceAtMost(0.9f)
            drawRect(
                Brush.verticalGradient(
                    0f to bottomCol.copy(alpha = compassAlpha), 1f to Color.Transparent,
                    startY = size.height, endY = size.height - bottomHPx,
                ),
                topLeft = Offset(0f, size.height - bottomHPx), size = Size(size.width, bottomHPx),
            )
            // Left band (speed), full height so it also tints the left corners; color at the left
            // screen edge fading right to clear.
            drawRect(
                Brush.horizontalGradient(
                    0f to leftCol.copy(alpha = edgeAlpha), 1f to Color.Transparent,
                    startX = 0f, endX = sideWPx,
                ),
                topLeft = Offset(0f, 0f), size = Size(sideWPx, size.height),
            )
            // Right band (altitude), full height; color at the right screen edge fading left.
            drawRect(
                Brush.horizontalGradient(
                    0f to rightCol.copy(alpha = edgeAlpha), 1f to Color.Transparent,
                    startX = size.width, endX = size.width - sideWPx,
                ),
                topLeft = Offset(size.width - sideWPx, 0f), size = Size(sideWPx, size.height),
            )
        }
    }
}

/**
 * HUD artificial horizon, shows the aircraft's attitude relative to the earth: a horizon line
 * and pitch ladder that pitch/roll with the airframe, a fixed boresight (the aircraft itself),
 * and a bank pointer along a top arc. This is the "which way am I oriented" instrument the side
 * tapes (speed/alt) and bottom compass (heading) don't give, heading says which way the nose
 * POINTS, this says how the airframe is TILTED against the ground.
 *
 * Drawn as thin colored strokes over a dark glow (the same clear-glass-plus-glow language as the
 * tapes, no filled background), so it overlays the live video legibly without blocking it. The
 * whole earth-referenced group (horizon + ladder) rotates by -roll and slides vertically by
 * pitch; the boresight and bank scale stay fixed to the screen. Ladder/pointer color escalates
 * green→amber→red with bank angle so an unintended steep bank reads instantly.
 *
 * Roll/pitch come from FLYC OSD general (0x43), in degrees. Sign convention (roll+ = bank right →
 * horizon tilts left-up) is the aviation-standard one but NOT independently confirmed against this
 * airframe's live output, if a real bank shows the horizon tilting the wrong way, flip `rollDir`.
 */
@Composable
private fun AttitudeIndicator(
    pitch: Float, roll: Float, heading: Float,
    speedColor: Color, altColor: Color,
    modifier: Modifier,
) {
    val p = if (pitch.isFinite()) pitch else 0f
    val r = if (roll.isFinite()) roll else 0f
    val rollDir = -1f  // flip to +1f if a live bank tilts the horizon the wrong way
    fun bankColor(absRoll: Float): Color = when { absRoll > 45f -> DjiRed; absRoll > 25f -> DjiAmber; else -> DjiGreen }
    // Dynamic coloring across the instrument, each part keyed to a different live value:
    //   • bank pointer / arc + P·R readout → bank angle (green→amber→red safety escalation)
    //   • horizon + pitch ladder           → altitude/AGL color
    //   • boresight wings                   → ground speed color
    //   • heading readout                   → compass-direction color
    val col = bankColor(kotlin.math.abs(r))
    val hdgColor = headingColor(heading)
    Box(modifier.size(width = 260.dp, height = 200.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val pxPerDeg = size.height / 56f   // ±28° of pitch visible top-to-bottom
            fun glowPaint(strokePx: Float) = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(150, 0, 0, 0)
                strokeWidth = strokePx
                strokeCap = android.graphics.Paint.Cap.ROUND
                isAntiAlias = true
                setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
            }
            fun glowLine(x1: Float, y1: Float, x2: Float, y2: Float, strokePx: Float, c: Color, coreW: Float) {
                drawContext.canvas.nativeCanvas.drawLine(x1, y1, x2, y2, glowPaint(strokePx))
                drawLine(c, Offset(x1, y1), Offset(x2, y2), strokeWidth = coreW)
            }

            // Earth-referenced group: clip to the box, rotate by roll, slide by pitch. Everything
            // drawn inside represents the world and tilts/translates opposite the airframe.
            clipRect {
                rotate(degrees = rollDir * r, pivot = Offset(cx, cy)) {
                    // Vertical shift so the current pitch sits under the fixed center boresight.
                    val yOff = p * pxPerDeg
                    // Horizon line (pitch 0), full width plus overscan so it still spans the box
                    // when rotated. A short center gap keeps it from crossing the boresight.
                    val hy = cy + yOff
                    val over = size.width * 0.9f
                    val gap = 14.dp.toPx()
                    glowLine(cx - over, hy, cx - gap, hy, 5.dp.toPx(), altColor, 2.dp.toPx())
                    glowLine(cx + gap, hy, cx + over, hy, 5.dp.toPx(), altColor, 2.dp.toPx())

                    // Pitch ladder rungs every 10° above and below the horizon; up rungs solid,
                    // down rungs are the same but tick the other way, labeled with the angle.
                    // Colored by altitude (this is the vertical scale), same as the horizon line.
                    val rungHalf = 46.dp.toPx()
                    for (a in intArrayOf(-20, -10, 10, 20)) {
                        val ry = cy + yOff - a * pxPerDeg
                        glowLine(cx - rungHalf, ry, cx - gap, ry, 4.dp.toPx(), altColor.copy(alpha = 0.9f), 1.6.dp.toPx())
                        glowLine(cx + gap, ry, cx + rungHalf, ry, 4.dp.toPx(), altColor.copy(alpha = 0.9f), 1.6.dp.toPx())
                        val lp = android.graphics.Paint().apply {
                            color = altColor.copy(alpha = 0.9f).toArgb()
                            textSize = 10.sp.toPx(); isAntiAlias = true; isFakeBoldText = true
                            textAlign = android.graphics.Paint.Align.CENTER
                            setShadowLayer(4.dp.toPx(), 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
                        }
                        val lbl = kotlin.math.abs(a).toString()
                        drawContext.canvas.nativeCanvas.drawText(lbl, cx - rungHalf - 10.dp.toPx(), ry + 4.dp.toPx(), lp)
                        drawContext.canvas.nativeCanvas.drawText(lbl, cx + rungHalf + 10.dp.toPx(), ry + 4.dp.toPx(), lp)
                    }
                }
            }

            // Fixed boresight, the aircraft itself, screen-locked (does NOT rotate). Classic
            // "-v-" waterline: two wings colored by ground SPEED (it IS the aircraft) and a center
            // pip in bright white so there's always one stable neutral reference.
            val wing = 30.dp.toPx()
            val wingGap = 10.dp.toPx()
            glowLine(cx - wing, cy, cx - wingGap, cy, 5.dp.toPx(), speedColor, 2.5.dp.toPx())
            glowLine(cx + wingGap, cy, cx + wing, cy, 5.dp.toPx(), speedColor, 2.5.dp.toPx())
            drawContext.canvas.nativeCanvas.drawCircle(cx, cy, 2.5.dp.toPx(), android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE; isAntiAlias = true
                setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
            })

            // Bank scale, fixed tick arc across the top; a rolling pointer triangle hangs from the
            // top center and rotates with roll, reading current bank against the fixed ticks.
            val arcR = size.height * 0.42f
            for (tick in intArrayOf(-60, -45, -30, -15, 0, 15, 30, 45, 60)) {
                val ang = Math.toRadians((-90 + tick).toDouble())
                val outer = arcR
                val inner = arcR - (if (tick % 45 == 0) 9.dp.toPx() else 5.dp.toPx())
                val x1 = cx + kotlin.math.cos(ang).toFloat() * outer
                val y1 = cy + kotlin.math.sin(ang).toFloat() * outer
                val x2 = cx + kotlin.math.cos(ang).toFloat() * inner
                val y2 = cy + kotlin.math.sin(ang).toFloat() * inner
                glowLine(x1, y1, x2, y2, 3.5.dp.toPx(), Color.White.copy(alpha = 0.85f), 1.4.dp.toPx())
            }
            // Rolling pointer: a small triangle at the top of the arc, rotated by roll around the
            // center so it swings under whichever bank tick is current.
            rotate(degrees = r, pivot = Offset(cx, cy)) {
                val ty = cy - arcR + 2.dp.toPx()
                val tri = Path().apply {
                    moveTo(cx, ty); lineTo(cx - 6.dp.toPx(), ty + 11.dp.toPx())
                    lineTo(cx + 6.dp.toPx(), ty + 11.dp.toPx()); close()
                }
                drawContext.canvas.nativeCanvas.drawPath(tri.asAndroidPath(), android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(150, 0, 0, 0); isAntiAlias = true
                    setShadowLayer(4.dp.toPx(), 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
                })
                drawPath(tri, color = col)
            }
        }
        // Numeric pitch/roll readout under the instrument, exact degrees for when the ladder's
        // resolution isn't enough. "P" pitch (+up), "R" roll (+right bank). Colored by bank.
        Text(
            "P %+.0f°  R %+.0f°".format(p, r),
            color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // Heading readout at the top, colored by compass direction, the fourth dynamic color, so
        // the instrument carries speed (wings), altitude (ladder), bank (pointer) AND heading at a
        // glance, each in its own live hue.
        Text(
            "%03.0f°".format(heading),
            color = hdgColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** Horizontal heading tape, same aircraft-PFD language as [SpeedTape]/[AltitudeTape] (a
 *  scrolling scale with the current value fixed under a center pointer) instead of the old
 *  small rotating compass ball, which read poorly at a glance and ate a chunk of the video feed
 *  in the middle of the screen. Labeled with the 8-point compass rose (N/NE/E/SE/S/SW/W/NW)
 *  rather than raw degree numbers at the intermediate ticks, so it reads by direction, not by
 *  degrees memorized. */
@Composable
private fun HeadingTape(yaw: Float, hazeState: HazeState?, modifier: Modifier) {
    val heading = (((if (yaw.isFinite()) yaw else 0f) % 360f) + 360f) % 360f
    val hColor = headingColor(heading)
    val monoTypeface = rememberMonoTypeface()
    // No background of its own anymore, HudGlassSurface provides the shared glass fill/blur
    // for this + both tapes + the top bar; this bar only draws its own ticks/pointer/text.
    Box(modifier.height(34.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val pxPerDeg = size.width / 70f
            val centerX = size.width / 2f

            // Three-tier tick hierarchy (was a single 15°-only tier), tertiary every 5°, medium
            // every 15°, tall+labeled every 45° (cardinal/intercardinal). Real aircraft/DJI-style
            // compass tapes also print the numeric heading (in tens of degrees) at the 30°
            // points between cardinals, not just letters at the 8 compass points, that's the
            // "more data" half of this: a pilot can read an exact heading off the tape, not just
            // "roughly NE," without waiting for the numeric readout below to update.
            var d = kotlin.math.floor((heading - 35f) / 5f) * 5f
            val last = heading + 35f
            while (d <= last) {
                val norm = (((d.roundToInt() % 360) + 360) % 360)
                val point = if (norm % 45 == 0) COMPASS_POINTS[norm] else null
                val isCardinal = point != null
                val isMedium = norm % 15 == 0
                val showNumber = !isCardinal && norm % 30 == 0
                val x = centerX + (d - heading) * pxPerDeg
                val tickTop = size.height - when {
                    isCardinal -> 17.dp.toPx()
                    isMedium   -> 13.dp.toPx()
                    else       -> 8.dp.toPx()
                }
                // Each tick + label is colored by ITS OWN heading's live color (headingColor of
                // this tick's own degree), not a flat grey, so the compass color genuinely
                // morphs and gradients across the tape as it scrolls under the pointer, the same
                // per-value color language the speed/alt tapes already speak. Dark under-stroke +
                // text shadow keep every mark crisp against the clear glass behind it.
                val tickColor = headingColor(norm.toFloat())
                val tickGlow = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(150, 0, 0, 0)
                    strokeWidth = (if (isCardinal) 5.dp else 4.dp).toPx()
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    isAntiAlias = true
                    setShadowLayer(3.dp.toPx(), 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
                }
                drawContext.canvas.nativeCanvas.drawLine(x, size.height, x, tickTop, tickGlow)
                drawLine(
                    tickColor.copy(alpha = if (isMedium) 1f else 0.8f),
                    Offset(x, size.height), Offset(x, tickTop),
                    strokeWidth = (if (isCardinal) 2.5.dp else 1.8.dp).toPx(),
                )
                if (point != null) {
                    val cardinal = point.length == 1
                    val paint = android.graphics.Paint().apply {
                        color = tickColor.toArgb()
                        textSize = (if (cardinal) 14.sp else 12.sp).toPx()
                        isAntiAlias = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                        setShadowLayer(5.dp.toPx(), 0f, 0f, android.graphics.Color.argb(230, 0, 0, 0))
                    }
                    drawContext.canvas.nativeCanvas.drawText(point, x, tickTop - 3.dp.toPx(), paint)
                } else if (showNumber) {
                    val paint = android.graphics.Paint().apply {
                        color = tickColor.copy(alpha = 0.9f).toArgb()
                        textSize = 10.5.sp.toPx()
                        isAntiAlias = true
                        typeface = monoTypeface
                        isFakeBoldText = true
                        textAlign = android.graphics.Paint.Align.CENTER
                        setShadowLayer(5.dp.toPx(), 0f, 0f, android.graphics.Color.argb(230, 0, 0, 0))
                    }
                    drawContext.canvas.nativeCanvas.drawText("%02d".format(norm / 10), x, tickTop - 3.dp.toPx(), paint)
                }
                d += 5f
            }
            // Fixed center pointer marking the true current heading, the tape scrolls under it.
            // Colored by headingColor(), wrapped in a dark glow so it reads on any background.
            val pointerPath = Path().apply {
                moveTo(centerX - 6.dp.toPx(), 0f); lineTo(centerX + 6.dp.toPx(), 0f)
                lineTo(centerX, 8.dp.toPx()); close()
            }
            drawContext.canvas.nativeCanvas.drawPath(
                pointerPath.asAndroidPath(),
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(140, 0, 0, 0)
                    isAntiAlias = true
                    setShadowLayer(4.dp.toPx(), 0f, 0f, android.graphics.Color.argb(220, 0, 0, 0))
                },
            )
            drawPath(pointerPath, color = hColor)
        }
        // Numeric heading, centered under the pointer it belongs to (previously off at the
        // tape's left edge, reading as a disconnected readout rather than part of the compass).
        // Bottom-aligned: the top half of the bar is the pointer + letter labels, the bottom
        // half is bare tick lines with nothing else drawn there, so this is the one open spot
        // that doesn't crowd either.
        Text(
            "${heading.roundToInt()}°", color = hColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = IbmPlexMono,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** Single shared expanded panel behind every tappable badge in [TopBar], link, GPS, airspace,
 *  battery/temp, landing light, vision radar, mode all open this SAME tray rather than each
 *  getting its own popover, so "tap anything for more info" has one consistent answer instead
 *  of N different widgets to build and remember. */
@Composable
private fun StatusTray(
    modifier: Modifier, droneLat: Double, droneLon: Double, droneFix: Boolean,
    phone: Triple<Double, Double, Float>?,
    battTempC: Float? = null, ambientTempC: Float? = null,
    connected: Boolean, droneLinked: Boolean, previewMode: Boolean = false,
    battPct: Int, battMv: Int,
    ledMode: Int, obstacleValid: Boolean,
    flycState: Int, homeDistM: Float?,
    currentAirspace: dev.glassfalcon.core.AirspaceInfo?,
    hazeState: HazeState? = null,
) {
    // Scrollable so it always fits ALL its rows regardless of how many are present, and backed by
    // a near-opaque dark scrim (on top of the glass) so the small text reads cleanly over bright
    // video, the tray used to overflow off-screen and its text washed out against sky. Text sizes
    // bumped up throughout (see SECTION/value helpers below).
    Column(
        modifier
            .width(210.dp)
            .heightIn(max = 460.dp)
            .glass(shape = RoundedCornerShape(8.dp), baseAlpha = 0.35f, haze = hazeState)
            .background(Color(0xCC0A0E14), RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text("LINK", color = TextSec, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(
            when { previewMode -> "PREVIEW, no hardware"; !connected -> "NO LINK"; droneLinked -> "RC + DRONE"; else -> "RC ONLY, no drone telemetry" },
            color = when { previewMode -> DjiCyan; !connected -> DjiRed; droneLinked -> DjiGreen; else -> DjiAmber }, fontSize = 12.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text("DRONE GPS", color = DjiGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(if (droneFix) "%.5f, %.5f".format(droneLat, droneLon) else "no fix",
            color = if (droneFix) TextPri else DjiRed, fontSize = 9.sp)
        Spacer(Modifier.height(3.dp))
        Text("PHONE GPS", color = Color(0xFF3399FF), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        if (phone != null) {
            Text("%.5f, %.5f".format(phone.first, phone.second), color = TextPri, fontSize = 9.sp)
            Text("±%.0fm".format(phone.third), color = TextSec, fontSize = 8.sp)
        } else Text("acquiring…", color = TextSec, fontSize = 9.sp)
        if (droneFix && phone != null) {
            val d = haversineM(droneLat, droneLon, phone.first, phone.second)
            Text("↔ %.0f m to pilot".format(d), color = DjiAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        homeDistM?.let { Text("🏠 %.0f m to home".format(it), color = TextSec, fontSize = 9.sp) }

        if (battTempC != null || ambientTempC != null) {
            Spacer(Modifier.height(3.dp))
            Text("CONDITIONS", color = TextSec, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            battTempC?.let { Text("Battery temp: ${dev.glassfalcon.core.Units.temp(it)}", color = TextPri, fontSize = 9.sp) }
            Text(
                ambientTempC?.let { "Ambient temp: ${dev.glassfalcon.core.Units.temp(it)}" } ?: "Ambient temp: no live source",
                color = if (ambientTempC != null) TextPri else TextSec, fontSize = 9.sp,
            )
        }

        Spacer(Modifier.height(3.dp))
        Text("BATTERY", color = TextSec, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(
            if (battPct > 0) "$battPct%" + (if (battMv > 0) " · ${"%.1f".format(battMv / 1000f)}V" else "") else "not synced",
            color = TextPri, fontSize = 9.sp,
        )

        Spacer(Modifier.height(3.dp))
        Text("FLIGHT MODE", color = TextSec, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(
            when (flycState and 0x7f) {
                0x1f -> "SPORT, speed cap lifted"
                0x06 -> "GPS, position-held, normal cap"
                0x01 -> "ATTI, no GPS assist, altitude only"
                0x00 -> "MANUAL, no assist at all"
                else -> "unknown (raw 0x%02x)".format(flycState and 0x7f)
            },
            color = TextPri, fontSize = 9.sp,
        )

        Spacer(Modifier.height(3.dp))
        Text("LANDING LIGHT", color = TextSec, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(if (ledMode != 0) "ON" else "off", color = if (ledMode != 0) Gold else TextPri, fontSize = 9.sp)

        Spacer(Modifier.height(3.dp))
        Text("VISION RADAR", color = TextSec, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(
            if (obstacleValid) "sensor frame arriving, edge glow reflects real readings" else "no sensor frame, edge glow not trustworthy",
            color = if (obstacleValid) DjiGreen else DjiRed, fontSize = 9.sp,
        )

        // Real FAA data, see fetchNearbyAirspaceInfo's doc comment for the live query this
        // comes from (bbox-based "nearby," not exact polygon containment).
        currentAirspace?.let { a ->
            Spacer(Modifier.height(3.dp))
            Text("AIRSPACE", color = DjiAmber, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(a.name, color = TextPri, fontSize = 9.sp)
            a.classCode?.let { Text("Class $it", color = TextPri, fontSize = 9.sp) }
            val ceilingText = a.upperFt?.let { "${it.roundToInt()}ft" } ?: a.upperDesc?.takeIf { it.isNotBlank() }
            val floorText = a.lowerFt?.let { "${it.roundToInt()}ft" } ?: a.lowerDesc?.takeIf { it.isNotBlank() }
            if (floorText != null || ceilingText != null) {
                Text("${floorText ?: "?"} – ${ceilingText ?: "?"}", color = TextPri, fontSize = 9.sp)
            }
        }
    }
}


private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

@Composable
private fun WarningsBanner(warnings: List<String>, onDismissLowBattery: () -> Unit, modifier: Modifier) {
    if (warnings.isEmpty()) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        warnings.forEach { w ->
            val crit = "CRITICAL" in w || "LOST" in w || "LAND NOW" in w
            // Preview mode is informational, not a fault, it gets its own color (neither the
            // amber "something to watch" nor the red "act now" this banner otherwise means) so
            // it can't be mistaken for a real hardware warning.
            val preview = "PREVIEW MODE" in w
            // Only the LOW BATTERY banner is tappable-to-dismiss, CRITICAL BATTERY (too close
            // to skip RTH) and every other warning here (signal loss, IMU, motor-start-fail)
            // stay put regardless of taps.
            val dismissible = "LOW BATTERY" in w
            Surface(
                color = (if (crit) DjiRed else if (preview) DjiCyan else DjiAmber).copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .let { if (dismissible) it.clickable { onDismissLowBattery() } else it },
            ) {
                Text(w, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

private fun signalBars(n: Int): String = "█".repeat(n) + "░".repeat(4 - n)
