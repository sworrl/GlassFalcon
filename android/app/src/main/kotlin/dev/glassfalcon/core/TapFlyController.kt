// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

enum class TapFlyMode { OFF, FLYING }

data class TapFlyStatus(
    val mode: TapFlyMode = TapFlyMode.OFF,
    val label: String = "",
    val elapsedMs: Long = 0,
)

/**
 * TapFly (forward-only, straight-bearing flavor): tap a point on the video, the drone flies
 * forward along the real-world compass bearing that screen point corresponds to, holding
 * altitude, until something stops it. No autonomous obstacle-avoidance path re-routing, this
 * is the simple "keep flying this one bearing" mode, not DJI's full TapFly.
 *
 * Bearing math reuses [MissionPlanner.HFOV_DEG] (the wm240's confirmed 65° horizontal FOV,
 * already used for grid-survey footprint math) rather than a second hardcoded copy of that
 * constant, a tap at the frame's horizontal fraction [0,1] is converted to an angle offset via
 * atan(x-offset * tan(HFOV/2)), the standard rectilinear-lens model, then added to the drone's
 * current compass yaw to get a fixed target bearing.
 *
 * Control loop mirrors MissionEngine's P-controller style (same YAW_KP/ALT_KP philosophy), 
 * these gains are a reasonable starting point carried over from that controller, NOT
 * independently re-tuned or flight-verified for this specific mode.
 */
class TapFlyController(
    private val duml: DumlConnection,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(TapFlyStatus())
    val status: StateFlow<TapFlyStatus> = _status

    private var droneState: DroneState = DroneState()
    private var obstacleState: ObstacleState = ObstacleState()
    @Volatile private var lastStateMs = 0L
    private val STALE_MS = 1500L

    fun attachDroneState(state: DroneState) {
        droneState = state
        lastStateMs = System.currentTimeMillis()
    }
    fun attachObstacleState(state: ObstacleState) { obstacleState = state }

    private fun telemetryFresh() =
        lastStateMs != 0L && System.currentTimeMillis() - lastStateMs < STALE_MS

    // Tuning, carried over from MissionEngine's own constants (see its doc comment), not
    // independently re-verified against real flight for this mode.
    private val YAW_KP = 0.8f
    private val ALT_KP = 0.4f
    private val PITCH_CAP = 0.35f          // capped forward-stick fraction, conservative,
                                            // unverified exact value; a "modest speed" choice
    private val YAW_ALIGN_THRESHOLD = 30f  // degrees, don't drive forward until roughly on-bearing
    private val STICK_MS = 50L             // 20Hz, matches MissionEngine / virtual-RC
    private val MAX_DURATION_MS = 18_000L  // backstop: 15-20s asked for, picked the middle
    private val MAX_DISTANCE_M = 50f       // backstop: ~50m asked for
    private val OBSTACLE_STOP_CM = 50      // same close-range value as ObstacleEdgeGlow's red
                                            // threshold (MainScreen.kt), reused via
                                            // ObstacleState.frontClosest, not a new convention

    private var flyJob: Job? = null
    private var targetBearingDeg = 0.0
    private var targetAltM = 0f
    private var startLat = 0.0
    private var startLon = 0.0
    private var startMs = 0L

    /** Starts flying forward along the bearing that [tapXFrac] (0..1 across the video's width)
     *  corresponds to, from the drone's current yaw and position. No-ops if already flying, or
     *  if there's no live GPS-fixed telemetry to compute/hold a bearing against. */
    fun start(tapXFrac: Float) {
        if (_status.value.mode != TapFlyMode.OFF) return
        if (!telemetryFresh() || !droneState.hasGpsFix) {
            _status.value = _status.value.copy(label = "TapFly: no live GPS-fixed telemetry, not starting")
            return
        }
        val xOffset = (tapXFrac - 0.5f) * 2.0   // -1 (left edge) .. +1 (right edge)
        val offsetDeg = Math.toDegrees(atan(xOffset * tan(Math.toRadians(MissionPlanner.HFOV_DEG / 2))))
        targetBearingDeg = ((droneState.yaw.toDouble() + offsetDeg) % 360 + 360) % 360
        targetAltM = droneState.altRel
        startLat = droneState.lat; startLon = droneState.lon
        startMs = System.currentTimeMillis()
        _status.value = TapFlyStatus(
            mode = TapFlyMode.FLYING,
            label = "TAPFLY, flying bearing %.0f°".format(targetBearingDeg),
        )
        flyJob = scope.launch { flyLoop() }
    }

    /** User-initiated cancel, also called on every auto-stop condition. */
    fun stop() {
        flyJob?.cancel(); flyJob = null
        duml.send(FlyC.joystick(0f, 0f, 0f, 0f))
        _status.value = TapFlyStatus()
    }

    private suspend fun flyLoop() {
        while (flyJob?.isActive == true) {
            val now = System.currentTimeMillis()

            if (!telemetryFresh()) { stopFromLoop("TapFly cancelled, telemetry went stale"); return }
            if (now - startMs > MAX_DURATION_MS) { stopFromLoop("TapFly stopped, max duration reached"); return }

            val distM = MissionPlanner.haversine(startLat, startLon, droneState.lat, droneState.lon).toFloat()
            if (distM > MAX_DISTANCE_M) { stopFromLoop("TapFly stopped, max distance reached"); return }

            val front = obstacleState.frontClosest
            if (front != null && front < OBSTACLE_STOP_CM) { stopFromLoop("TapFly stopped, obstacle ahead"); return }

            val headingErr = MissionPlanner.normalizeAngle(targetBearingDeg - droneState.yaw.toDouble()).toFloat()
            val yawOut = (headingErr / 90f * YAW_KP).coerceIn(-1f, 1f)
            // Same discipline as MissionEngine.flyToWp: don't drive forward while still mid-turn
            // onto the target bearing.
            val pitchOut = if (abs(headingErr) < YAW_ALIGN_THRESHOLD) PITCH_CAP else 0f
            val altErr = targetAltM - droneState.altRel
            val throttleOut = (altErr * ALT_KP).coerceIn(-0.4f, 0.4f)

            duml.send(FlyC.joystick(0f, pitchOut, throttleOut, yawOut))
            _status.value = _status.value.copy(elapsedMs = now - startMs)
            delay(STICK_MS)
        }
    }

    /** Called from within [flyLoop] itself, don't cancel flyJob here, just zero sticks, update
     *  status, and return (the loop ends on its own via the caller's `return`). */
    private fun stopFromLoop(label: String) {
        duml.send(FlyC.joystick(0f, 0f, 0f, 0f))
        _status.value = TapFlyStatus(label = label)
        flyJob = null
    }
}
