// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

data class Waypoint(
    val lat: Double,
    val lon: Double,
    val altM: Float,
    val capturePhoto: Boolean = false,
    val gimbalPitch: Float = -90f,   // default: nadir for mapping
    val waitMs: Long = 0L,
    val label: String = "",
    // When set, MissionEngine.flyToWp yaws toward this absolute heading instead of the
    // direction of travel, what a POI/orbit/QuickShot leg needs (camera locked on the
    // subject throughout the leg, not just facing wherever the drone happens to be flying).
    val yawOverrideDeg: Float? = null,
)

enum class MissionState {
    IDLE, ARMING, TAKEOFF, FLYING, AT_WAYPOINT, CAPTURING,
    RETURN_HOME, LANDING, COMPLETE, ABORTED
}

data class MissionStatus(
    val state: MissionState = MissionState.IDLE,
    val currentWpIdx: Int = 0,
    val totalWaypoints: Int = 0,
    val photosTaken: Int = 0,
    val distanceTravelledM: Float = 0f,
    val etaSeconds: Int = 0,
    val log: List<String> = emptyList(),
)

data class SurveyArea(
    val corners: List<Pair<Double, Double>>,  // lat/lon pairs (convex polygon)
    val altM: Float = 100f,
    val frontOverlapPct: Float = 75f,
    val sideOverlapPct: Float = 70f,
    val speedMs: Float = 5f,
)

data class MissionPlan(
    val waypoints: List<Waypoint>,
    val survey: SurveyArea? = null,
    val name: String = "Mission",
    val estimatedPhotos: Int = 0,
    val estimatedFlightMinutes: Float = 0f,
    val estimatedAreaM2: Float = 0f,
    val gsdCm: Float = 0f,             // ground sampling distance
)
