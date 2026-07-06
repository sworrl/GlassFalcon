// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glassfalcon.core.FlightViewModel
import dev.glassfalcon.ui.components.glass
import dev.glassfalcon.ui.screens.*

@Composable
fun GlassFalconRoot(vm: FlightViewModel, onSettingsVisibilityChanged: (Boolean) -> Unit = {}) {
    GlassFalconTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            GlassFalconLayout(vm, onSettingsVisibilityChanged)
        }
    }
}

@Composable
private fun GlassFalconLayout(vm: FlightViewModel, onSettingsVisibilityChanged: (Boolean) -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    // Settings is the one screen allowed out of the app's landscape lock, see
    // MainActivity.setSettingsOpen for why (plain scrollable content, reflows fine in
    // portrait, unlike the flight HUD's hand-tuned instrument layout).
    LaunchedEffect(showSettings) { onSettingsVisibilityChanged(showSettings) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var accepted by remember { mutableStateOf(dev.glassfalcon.core.ConsentStore.hasAccepted(context)) }
    // Companion ("map-only HUD" spotter/passenger device) is a persisted per-install choice,
    // checked before anything else touches DUML/RC, a companion device never has flight
    // controls at all, it only ever talks to a CompanionServer over the network (see
    // CompanionScreen/CompanionSync). No disclaimer gate here either: nothing on this screen
    // can fly, arm, or command the aircraft, so the flight-legality disclaimer doesn't apply.
    var companionMode by remember { mutableStateOf(dev.glassfalcon.core.CompanionModeStore.isCompanionMode(context)) }
    if (companionMode) {
        CompanionModeScreen(onExitCompanionMode = { companionMode = false })
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Primary view: full-screen flight / video
        MainScreen(vm, onOpenSettings = { showSettings = true })

        // Settings overlay, old tabbed UI behind a gear press
        if (showSettings) {
            SettingsOverlay(vm, onClose = { showSettings = false })
        }

        // First-launch-only disclaimer, blocks everything behind it until explicitly
        // accepted, then never shows again (persisted via ConsentStore). See its own doc
        // comment for why this exists instead of any in-app enforcement of flight limits.
        if (!accepted) {
            DisclaimerModal(onAccept = {
                dev.glassfalcon.core.ConsentStore.setAccepted(context)
                accepted = true
            })
        }

        // Flight Summary, auto-shown the instant a flight lands (see
        // FlightViewModel.justLandedRecord's doc comment). Drawn last so it sits above
        // everything else, including Settings if it happened to be open mid-flight.
        val justLanded by vm.justLandedRecord.collectAsState()
        val allRecords by vm.flightRecords.collectAsState()
        justLanded?.let { record ->
            FlightSummaryOverlay(record, allRecords, onDismiss = { vm.dismissFlightSummary() })
        }
    }
}

@Composable
private fun DisclaimerModal(onAccept: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Navy, RoundedCornerShape(12.dp))
                .border(1.dp, Gold.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            Text("Before you fly", color = Gold, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "GlassFalcon is free, open-source software with no company, server, account " +
                "system, or monetization behind it. There is no mechanism by which it, or " +
                "anyone, could verify what certifications, waivers, or local authorizations " +
                "you hold to operate an aircraft in any given airspace, at any given height, " +
                "in any given country.",
                color = TextSec, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Because of that, GlassFalcon does not try to enforce compliance. Any limit it " +
                "shows you, your aircraft's own configured height/radius limits, nearby FAA " +
                "airspace ceilings, anything else, is informational, pulled from the " +
                "aircraft's own reported settings or public data. None of it is a guarantee, " +
                "and none of it is a lock GlassFalcon puts between you and your own aircraft. " +
                "A tool built to second-guess a pilot who actually holds the relevant " +
                "authorization would be worse than one that trusts you to know your own " +
                "obligations.",
                color = TextSec, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "You are solely responsible for complying with all laws and regulations that " +
                "apply wherever you operate your aircraft. By continuing, you confirm that " +
                "you will only fly within those rules, and that you, not GlassFalcon, not " +
                "its authors, are accountable if you don't.",
                color = TextPri, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Gold.copy(alpha = 0.25f)),
            ) { Text("I understand and accept", color = Gold) }
        }
    }
}

/** Raw cutout rect + full screen size, window-relative pixels, the shared live source both
 *  [rowCutoutInset] calls below read from. Same OS API as the flight HUD's own
 *  `rememberCutoutRectPx` (see MainScreen.kt), not a hand-maintained per-device table. */
private data class CutoutInfo(val rect: android.graphics.Rect, val screenW: Int, val screenH: Int)

@Composable
private fun rememberCutoutInfo(): CutoutInfo? {
    val view = LocalView.current
    var info by remember { mutableStateOf<CutoutInfo?>(null) }
    DisposableEffect(view) {
        fun refresh() {
            val r = view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull()
            info = if (r != null && view.width > 0 && view.height > 0) CutoutInfo(r, view.width, view.height) else null
        }
        refresh()
        val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        view.addOnLayoutChangeListener(listener)
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }
    return info
}

/**
 * Per-ROW cutout dodge, not a blanket inset on the whole Settings screen, the earlier version
 * padded the entire outer Column by the cutout's width, which shrinks every row including the
 * ones nowhere near vertically overlapping the actual hole (the same "insetting more than
 * necessary" mistake the flight HUD itself already learned not to make). This only nudges a
 * SPECIFIC row's own horizontal padding, and only when that row's own measured vertical span
 * ([rowTop]..[rowBottom], window-relative, pass boundsInWindow() from that row's own
 * onGloballyPositioned) actually overlaps the cutout's vertical span. Every row that doesn't
 * overlap it gets zero padding and uses the true full screen width, exactly as it should.
 */
private fun rowCutoutInset(info: CutoutInfo?, rowTop: Float, rowBottom: Float, density: Density): Pair<Dp, Dp> {
    if (info == null) return 0.dp to 0.dp
    val c = info.rect
    val overlaps = c.top < rowBottom && c.bottom > rowTop
    if (!overlaps) return 0.dp to 0.dp
    val distLeft = c.left
    val distRight = info.screenW - c.right
    val margin = 4
    return with(density) {
        if (distLeft <= distRight) (c.right + margin).toDp() to 0.dp
        else 0.dp to (info.screenW - c.left + margin).toDp()
    }
}

@Composable
private fun SettingsOverlay(vm: FlightViewModel, onClose: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        "Camera"      to Icons.Filled.Camera,
        "Gimbal"      to Icons.Filled.Adjust,
        "Telemetry"   to Icons.Filled.BarChart,
        "Mission"     to Icons.Filled.Map,
        "Offload"     to Icons.Filled.Download,
        "Gallery"     to Icons.Filled.PhotoLibrary,
        "Device"      to Icons.Filled.Settings,
        "Firmware"    to Icons.Filled.Info,
        "Local & LLM" to Icons.Filled.Mic,
        "Voice"       to Icons.Filled.RecordVoiceOver,
        "Plugins"     to Icons.Filled.Extension,
        "Flights"     to Icons.Filled.EmojiEvents,
    )

    // These screens use default Material3 sizing (built for a normal portrait phone UI) while
    // the rest of the app is hand-tuned small/dense for a landscape HUD, side by side that
    // makes Settings look oversized. Scaling the density down brings it in line with the
    // flight view's density without having to re-tune every control in every settings screen.
    val scaledDensity = LocalDensity.current.let { Density(it.density * 0.95f, it.fontScale * 0.95f) }
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
    // Full screen used everywhere, cutout dodge is now per-row (see rowCutoutInset's doc
    // comment) instead of one blanket inset on this whole Column, which used to shrink every
    // row including the ones nowhere near the hole.
    val cutoutInfo = rememberCutoutInfo()
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
    ) {
        // Close row
        var closeRowTop by remember { mutableStateOf(0f) }
        var closeRowBottom by remember { mutableStateOf(0f) }
        val (closeStart, closeEnd) = rowCutoutInset(cutoutInfo, closeRowTop, closeRowBottom, density)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .glass(shape = RectangleShape, tint = Gold)
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    closeRowTop = b.top; closeRowBottom = b.bottom
                }
                .padding(start = closeStart, end = closeEnd)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text("← Back to Flight", color = Gold)
            }
            Spacer(Modifier.weight(1f))
            Text("v${dev.glassfalcon.BuildConfig.VERSION_NAME.replace('-', ' ')}",
                color = TextSec, fontSize = 12.sp)
        }
        HorizontalDivider(color = Border)

        var tabRowTop by remember { mutableStateOf(0f) }
        var tabRowBottom by remember { mutableStateOf(0f) }
        val (tabStart, tabEnd) = rowCutoutInset(cutoutInfo, tabRowTop, tabRowBottom, density)
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor   = Color.Transparent,
            contentColor     = Gold,
            edgePadding      = 0.dp,
            divider          = { HorizontalDivider(color = Border) },
            modifier         = Modifier.glass(shape = RectangleShape, tint = Gold, baseAlpha = 0.5f)
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    tabRowTop = b.top; tabRowBottom = b.bottom
                }
                .padding(start = tabStart, end = tabEnd),
        ) {
            tabs.forEachIndexed { i, (label, icon) ->
                Tab(
                    selected = selectedTab == i,
                    onClick  = { selectedTab = i },
                    icon     = { Icon(icon, contentDescription = null) },
                    text     = { Text(label) },
                    selectedContentColor   = Gold,
                    unselectedContentColor = TextSec,
                )
            }
        }

        // Content itself gets no cutout handling, these are scrollable Column/Card screens
        // (Camera/Gimbal/etc.), and the cutout is a small feature confined to the top band the
        // two rows above already cover; the same live per-row pattern is there to reuse
        // (rowCutoutInset) if a specific control in a specific screen ever needs it too.
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> CameraScreen(vm)
                1 -> GimbalScreen(vm)
                2 -> TelemetryScreen(vm)
                3 -> MissionScreen(vm)
                4 -> OffloadScreen(vm)
                5 -> GalleryScreen(vm, onGoToOffload = { selectedTab = 4 })
                6 -> DeviceScreen(vm)
                7 -> DeviceInfoScreen(vm)
                8 -> AiSettingsScreen(vm)
                9 -> VoiceSettingsScreen(vm)
                10 -> PluginsScreen(vm)
                11 -> FlightRecordsScreen(vm)
            }
        }
    }
    }
}
