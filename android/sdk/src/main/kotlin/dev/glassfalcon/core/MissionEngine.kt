// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

class MissionEngine(
    private val duml: DumlConnection,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(MissionStatus())
    val status: StateFlow<MissionStatus> = _status

    // Navigation tuning constants
    private val YAW_KP    = 0.8f   // yaw proportional gain
    private val ALT_KP    = 0.4f   // altitude P gain
    private val POS_KP    = 0.6f   // horizontal position P gain
    private val MAX_SPEED = 8f     // m/s target horizontal speed
    private val YAW_THRESHOLD = 15f  // degrees, aligned enough to start moving
    private val WP_RADIUS_M   = 3f   // metres, close enough to waypoint

    private var plan: MissionPlan? = null
    private var droneState: DroneState = DroneState()
    private var navJob: Job? = null

    // Freshness of droneState. The nav loops are open-loop P-controllers driving throttle
    // and pitch from telemetry; if telemetry stalls (link loss) the last value freezes and
    // the controller would keep commanding a stale error, e.g. hold full climb. Track the
    // arrival time and bail to neutral sticks the moment it goes stale.
    @Volatile private var lastStateMs = 0L
    private val STALE_MS = 1500L

    fun attachDroneState(state: DroneState) {
        droneState = state
        lastStateMs = System.currentTimeMillis()
    }

    private fun telemetryFresh() =
        lastStateMs != 0L && System.currentTimeMillis() - lastStateMs < STALE_MS

    // ── Start / Stop ─────────────────────────────────────────────────────────

    fun start(p: MissionPlan, startAlt: Float = p.waypoints.firstOrNull()?.altM ?: 50f) {
        if (navJob?.isActive == true) return
        plan = p
        _status.value = MissionStatus(
            state = MissionState.TAKEOFF,
            totalWaypoints = p.waypoints.size,
            log = listOf("Mission start: ${p.name}, ${p.waypoints.size} waypoints"),
        )
        navJob = scope.launch { runMission(p, startAlt) }
    }

    fun abort() {
        navJob?.cancel()
        sendSticks(0f, 0f, 0f, 0f)   // stop
        _status.value = _status.value.copy(state = MissionState.ABORTED)
        log("Mission ABORTED")
    }

    // ── Main mission coroutine ────────────────────────────────────────────────

    private suspend fun runMission(p: MissionPlan, takeoffAlt: Float) {
        // ── 1. Climb to altitude ──────────────────────────────────────────────
        setState(MissionState.TAKEOFF)
        log("Climbing to ${takeoffAlt.toInt()} m…")
        climbToAlt(takeoffAlt)

        // ── 2. Fly each waypoint ──────────────────────────────────────────────
        var photos = 0
        var distTravelled = 0f
        var prevLat = droneState.lat
        var prevLon = droneState.lon

        for ((idx, wp) in p.waypoints.withIndex()) {
            if (!isActive()) break

            setState(MissionState.FLYING)
            _status.value = _status.value.copy(
                currentWpIdx   = idx,
                photosTaken    = photos,
                distanceTravelledM = distTravelled,
                etaSeconds     = estimateEta(p, idx),
            )
            log("→ WP $idx ${wp.label}  (${wp.lat.fmt()}, ${wp.lon.fmt()}) ${wp.altM.toInt()}m")

            // Set gimbal pitch for this leg
            setGimbalPitch(wp.gimbalPitch)

            // Fly to waypoint
            flyToWp(wp)
            if (!isActive()) break

            // Accumulate distance
            distTravelled += MissionPlanner.haversine(prevLat, prevLon, droneState.lat, droneState.lon).toFloat()
            prevLat = droneState.lat; prevLon = droneState.lon

            // Capture
            if (wp.capturePhoto) {
                setState(MissionState.CAPTURING)
                log("📷 Photo $photos at WP $idx")
                if (wp.waitMs > 0) delay(wp.waitMs)
                duml.sendCam(Camera.capturePhoto())
                delay(800)   // shutter + buffer
                photos++
            }
        }

        _status.value = _status.value.copy(photosTaken = photos)

        if (!isActive()) return

        // ── 3. Return to home ─────────────────────────────────────────────────
        log("Mission complete ($photos photos). Returning home…")
        setState(MissionState.RETURN_HOME)
        // Signal the FC to do RTH via DUML. Use the single canonical FlyC.returnHome()
        // builder so there is exactly ONE RTH encoding in the codebase (there used to be
        // a second, conflicting 0x03/0x24 definition here).
        duml.send(FlyC.returnHome())
        delay(2000)

        setState(MissionState.COMPLETE)
        log("✓ Mission complete.")
    }

    // ── Navigation primitives ─────────────────────────────────────────────────

    private suspend fun climbToAlt(targetAlt: Float) {
        val deadline = System.currentTimeMillis() + 60_000L
        while (isActive() && System.currentTimeMillis() < deadline) {
            if (!telemetryFresh()) {          // link lost mid-climb, stop commanding climb
                sendSticks(0f, 0f, 0f, 0f)
                log("Telemetry stale during climb, holding, aborting mission")
                abort(); return
            }
            val err = targetAlt - droneState.altRel
            if (abs(err) < 2f) { sendSticks(0f, 0f, 0f, 0f); return }
            val thr = (err * ALT_KP).coerceIn(-0.7f, 0.7f)
            sendSticks(0f, 0f, thr, 0f)
            delay(50)
        }
    }

    private suspend fun flyToWp(wp: Waypoint) {
        val deadline = System.currentTimeMillis() + 120_000L
        while (isActive() && System.currentTimeMillis() < deadline) {
            if (!telemetryFresh()) {          // link lost mid-leg, stop, don't fly blind
                sendSticks(0f, 0f, 0f, 0f)
                log("Telemetry stale en route, holding, aborting mission")
                abort(); return
            }
            val distM = MissionPlanner.haversine(droneState.lat, droneState.lon, wp.lat, wp.lon).toFloat()
            if (distM < WP_RADIUS_M) {
                sendSticks(0f, 0f, 0f, 0f)
                setState(MissionState.AT_WAYPOINT)
                return
            }

            // Where the nose points and which way the drone actually needs to MOVE are two
            // separate things once yawOverrideDeg is in play (a POI/orbit/QuickShot leg wants
            // the camera locked on its subject while flying tangentially past it, not facing
            // the direction of travel). travelBearing is always the true world-frame heading
            // toward the target lat/lon; yawTarget is what the nose should point at.
            val travelBearing = MissionPlanner.bearing(droneState.lat, droneState.lon, wp.lat, wp.lon)
            val yawTarget = wp.yawOverrideDeg?.toDouble() ?: travelBearing
            val headingErr = MissionPlanner.normalizeAngle(yawTarget - droneState.yaw.toDouble()).toFloat()
            val altErr   = wp.altM - droneState.altRel

            val yawOut  = (headingErr / 90f * YAW_KP).coerceIn(-1f, 1f)
            val thr     = (altErr * ALT_KP).coerceIn(-0.5f, 0.5f)

            // Decompose the movement toward travelBearing into THIS frame's forward/lateral
            // stick axes (relative to current yaw), this is what lets an orbit leg move
            // tangentially around its circle while the nose stays locked on the center: at a
            // 90° nose-to-travel offset (the orbit case) this naturally resolves to pure roll,
            // zero pitch, instead of assuming travel is always straight ahead of the nose.
            val travelAngleDeg = MissionPlanner.normalizeAngle(travelBearing - droneState.yaw.toDouble())
            val travelAngleRad = Math.toRadians(travelAngleDeg)
            val speedFrac = (distM / 20f).coerceAtMost(1f)   // slow down as we approach
            // Guard against flying backward while a plain waypoint leg is still mid-turn (no
            // yawOverride, so nose and travel direction should coincide once yaw catches up;
            // until then don't drive off in whatever direction the nose happens to face).
            val moveScale = if (wp.yawOverrideDeg == null && abs(travelAngleDeg) > YAW_THRESHOLD.toDouble() * 2) 0f else 1f
            val pitchOut = (cos(travelAngleRad) * speedFrac * POS_KP * moveScale).toFloat()
            val rollOut  = (sin(travelAngleRad) * speedFrac * POS_KP * moveScale).toFloat()

            sendSticks(roll = rollOut, pitch = pitchOut, throttle = thr, yaw = yawOut)
            delay(50)
        }
        log("WP timeout, continuing to next")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendSticks(roll: Float, pitch: Float, throttle: Float, yaw: Float) {
        duml.send(FlyC.joystick(roll, pitch, throttle, yaw))
    }

    private fun setGimbalPitch(pitch: Float) {
        duml.sendGimbal(Gimbal.absAngle(pitch, 0f, 0f, 10))
    }

    private fun setState(s: MissionState) {
        _status.value = _status.value.copy(state = s)
    }

    private fun log(msg: String) {
        val cur = (_status.value.log + msg).takeLast(100)
        _status.value = _status.value.copy(log = cur)
    }

    private fun isActive() = navJob?.isActive == true

    private fun estimateEta(p: MissionPlan, currentIdx: Int): Int {
        if (currentIdx >= p.waypoints.size - 1) return 0
        var dist = 0.0
        val wps = p.waypoints
        for (i in currentIdx until wps.size - 1) {
            dist += MissionPlanner.haversine(wps[i].lat, wps[i].lon, wps[i+1].lat, wps[i+1].lon)
        }
        val speed = p.survey?.speedMs?.toDouble() ?: 4.0
        return (dist / speed).toInt()
    }

    private fun Double.fmt() = "%.5f".format(this)
}
