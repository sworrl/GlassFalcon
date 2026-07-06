// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlin.math.*

object MissionPlanner {

    // wm240 Hasselblad L-Format 20MP: 13.2mm×8.8mm sensor, 28mm equiv focal length
    // Actual focal length ≈ 10.26mm. HFOV≈65°, VFOV≈44°
    // HFOV_DEG is public (not private), TapFlyController (app/, a different Gradle module)
    // reuses this exact constant for its tap→bearing angle math instead of hardcoding a second
    // copy that could drift out of sync with this one.
    const val HFOV_DEG = 65.0
    private const val VFOV_DEG = 44.0
    private const val EARTH_R  = 6_371_000.0  // metres

    // ── Grid/lawnmower survey ─────────────────────────────────────────────────

    fun gridSurvey(area: SurveyArea): MissionPlan {
        val corners = area.corners
        require(corners.size >= 2) { "Need at least 2 corner points" }

        // Bounding box
        val minLat = corners.minOf { it.first }
        val maxLat = corners.maxOf { it.first }
        val minLon = corners.minOf { it.second }
        val maxLon = corners.maxOf { it.second }

        val alt = area.altM.toDouble()

        // Camera footprint at this altitude
        val footprintW = 2 * alt * tan(Math.toRadians(HFOV_DEG / 2))  // metres
        val footprintH = 2 * alt * tan(Math.toRadians(VFOV_DEG / 2))

        // Spacing between lines (side overlap)
        val lineSpacingM = footprintW * (1.0 - area.sideOverlapPct / 100.0)
        // Spacing between photos (front overlap)
        val photoSpacingM = footprintH * (1.0 - area.frontOverlapPct / 100.0)

        // Area dimensions in metres
        val widthM  = haversine(minLat, minLon, minLat, maxLon)
        val heightM = haversine(minLat, minLon, maxLat, minLon)

        // Metres per degree at this latitude
        val mPerDegLat = EARTH_R * Math.PI / 180.0
        val mPerDegLon = EARTH_R * Math.PI / 180.0 * cos(Math.toRadians((minLat + maxLat) / 2))

        val latStep = lineSpacingM / mPerDegLat
        val photoLatStep = photoSpacingM / mPerDegLat

        val waypoints = mutableListOf<Waypoint>()
        var lineIdx = 0
        var lat = minLat + latStep / 2

        while (lat < maxLat) {
            val goingEast = lineIdx % 2 == 0
            var lon = if (goingEast) minLon else maxLon

            // Photo waypoints along this line
            while (if (goingEast) lon < maxLon else lon > minLon) {
                waypoints += Waypoint(
                    lat = lat,
                    lon = lon,
                    altM = area.altM,
                    capturePhoto = true,
                    gimbalPitch = -90f,
                    label = "L${lineIdx}",
                )
                val stepLon = photoSpacingM / mPerDegLon
                lon += if (goingEast) stepLon else -stepLon
            }

            lat += latStep
            lineIdx++
        }

        val areaM2 = widthM * heightM
        val gsdCm = (alt / 20.0 * 2.4).toFloat()   // rough: 2.4 cm/px at 20m for wm240

        // Estimate flight time: travel distance at speed + photo intervals
        var totalDistM = 0.0
        for (i in 1 until waypoints.size) {
            totalDistM += haversine(waypoints[i-1].lat, waypoints[i-1].lon,
                                    waypoints[i].lat, waypoints[i].lon)
        }
        val flightMinutes = (totalDistM / area.speedMs / 60f).toFloat()

        return MissionPlan(
            waypoints       = waypoints,
            survey          = area,
            name            = "Grid Survey",
            estimatedPhotos = waypoints.count { it.capturePhoto },
            estimatedFlightMinutes = flightMinutes,
            estimatedAreaM2 = areaM2.toFloat(),
            gsdCm           = gsdCm,
        )
    }

    // ── Orbit ─────────────────────────────────────────────────────────────────

    fun orbit(
        centerLat: Double, centerLon: Double,
        radiusM: Float, altM: Float,
        steps: Int = 36,
        captureEvery: Int = 3,
        gimbalPitch: Float = -30f,
    ): MissionPlan {
        val waypoints = orbitWaypoints(centerLat, centerLon, radiusM, radiusM, altM, altM, steps, captureEvery, gimbalPitch, "Orbit")
        val circumferenceM = 2 * Math.PI * radiusM
        return MissionPlan(
            waypoints = waypoints, name = "Orbit",
            estimatedPhotos = waypoints.count { it.capturePhoto },
            estimatedFlightMinutes = (circumferenceM / 3f / 60f).toFloat(),
            estimatedAreaM2 = (Math.PI * radiusM * radiusM).toFloat(),
        )
    }

    /** Shared circle-of-waypoints builder, [radiusM]/[altM] can each be given a start/end pair
     *  so callers can make the radius or altitude drift across the loop (that's all [helix] is:
     *  the same circle with altM interpolated). Every waypoint's yaw is locked onto the center
     *  ([Waypoint.yawOverrideDeg]) so the camera stays on the subject throughout, this is what
     *  makes it an actual POI/orbit shot instead of just a polygon flight path that happens to
     *  be circular; MissionEngine.flyToWp resolves the movement itself (roll/pitch mix) against
     *  the true travel bearing independently of where the nose is pointed. */
    private fun orbitWaypoints(
        centerLat: Double, centerLon: Double,
        radiusStartM: Float, radiusEndM: Float,
        altStartM: Float, altEndM: Float,
        steps: Int, captureEvery: Int, gimbalPitch: Float, label: String,
    ): List<Waypoint> {
        val mPerDegLat = EARTH_R * Math.PI / 180.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(centerLat))
        return (0..steps).map { i ->
            val frac = i.toFloat() / steps
            val angleDeg = 360.0 * frac
            val rad = Math.toRadians(angleDeg)
            val r = radiusStartM + (radiusEndM - radiusStartM) * frac
            val alt = altStartM + (altEndM - altStartM) * frac
            val lat = centerLat + (r * cos(rad)) / mPerDegLat
            val lon = centerLon + (r * sin(rad)) / mPerDegLon
            Waypoint(
                lat = lat, lon = lon, altM = alt,
                capturePhoto = i % captureEvery == 0,
                gimbalPitch = gimbalPitch,
                label = "$label$i",
                // r can hit exactly 0 at the very first/last step of a boomerang-style loop
                // (start/end AT the center), bearing() is undefined with zero displacement,
                // so just skip the override there and let flyToWp fall back to travel-bearing.
                yawOverrideDeg = if (r > 0.5f) bearing(lat, lon, centerLat, centerLon).toFloat() else null,
            )
        }
    }

    // ── QuickShot-style patterns ────────────────────────────────────────────────
    // DJI's stock QuickShot modes (Dronie/Circle/Helix/Rocket/Boomerang) are onboard-firmware
    // features triggered by an FC opcode this project has never confirmed exists or what it'd
    // take (see project notes on DUML file/mission opcodes, same discipline against guessing
    // applies here). GlassFalcon doesn't need that opcode at all, though: it already flies
    // missions itself via virtual-stick commands (MissionEngine/flyToWp), the exact same
    // confirmed, already-working control path grid surveys and orbits use, so each of these
    // shots is just a parameterized flight path built from that primitive, not a trigger for
    // DJI's onboard flight-mode logic. ActiveTrack and TapFly are NOT here: both need the FC's
    // (or the phone's own) live subject-tracking vision, a fundamentally different integration
    // point than "fly this path," and are out of scope for this pass.

    /** Classic "selfie fly-away": climbs and pulls straight back from the subject while the
     *  camera stays locked on it. [awayBearingDeg] is the compass direction to retreat along, 
     *  the caller already knows which way the drone is facing/came from; there's no vision
     *  system here to infer "away from subject" automatically. */
    fun dronie(
        subjectLat: Double, subjectLon: Double, startAltM: Float,
        awayBearingDeg: Float, distanceM: Float = 40f, climbM: Float = 20f,
        steps: Int = 12,
    ): MissionPlan {
        val mPerDegLat = EARTH_R * Math.PI / 180.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(subjectLat))
        val rad = Math.toRadians(awayBearingDeg.toDouble())
        val waypoints = (0..steps).map { i ->
            val frac = i.toFloat() / steps
            val d = distanceM * frac
            val lat = subjectLat + (d * cos(rad)) / mPerDegLat
            val lon = subjectLon + (d * sin(rad)) / mPerDegLon
            Waypoint(
                lat = lat, lon = lon, altM = startAltM + climbM * frac,
                capturePhoto = i == steps,
                gimbalPitch = -20f,
                label = "Dronie$i",
                yawOverrideDeg = if (d > 0.5f) bearing(lat, lon, subjectLat, subjectLon).toFloat() else null,
            )
        }
        return MissionPlan(
            waypoints = waypoints, name = "Dronie",
            estimatedPhotos = 1,
            estimatedFlightMinutes = (distanceM / 4f / 60f).toFloat(),
        )
    }

    /** Orbit that also climbs (or descends) across the loop, same circle [orbit] flies, with
     *  altitude interpolated start→end instead of held constant. */
    fun helix(
        centerLat: Double, centerLon: Double,
        radiusM: Float, altStartM: Float, altEndM: Float,
        turns: Float = 1f, stepsPerTurn: Int = 36,
        captureEvery: Int = 3, gimbalPitch: Float = -20f,
    ): MissionPlan {
        val steps = (stepsPerTurn * turns).toInt().coerceAtLeast(1)
        // orbitWaypoints always sweeps exactly one 0..360 loop over `steps`, scale radius
        // constant and drive multi-turn coverage via the caller's own step count instead.
        val waypoints = (0..steps).map { i ->
            val frac = i.toFloat() / steps
            val angleDeg = 360.0 * turns * frac
            val rad = Math.toRadians(angleDeg)
            val mPerDegLat = EARTH_R * Math.PI / 180.0
            val mPerDegLon = mPerDegLat * cos(Math.toRadians(centerLat))
            val lat = centerLat + (radiusM * cos(rad)) / mPerDegLat
            val lon = centerLon + (radiusM * sin(rad)) / mPerDegLon
            val alt = altStartM + (altEndM - altStartM) * frac
            Waypoint(
                lat = lat, lon = lon, altM = alt,
                capturePhoto = i % captureEvery == 0,
                gimbalPitch = gimbalPitch,
                label = "Helix$i",
                yawOverrideDeg = bearing(lat, lon, centerLat, centerLon).toFloat(),
            )
        }
        val circumferenceM = 2 * Math.PI * radiusM * turns
        return MissionPlan(
            waypoints = waypoints, name = "Helix",
            estimatedPhotos = waypoints.count { it.capturePhoto },
            estimatedFlightMinutes = (circumferenceM / 3f / 60f).toFloat(),
        )
    }

    /** Straight up from directly over the subject, camera nadir, the "Rocket" shot. No yaw
     *  override: climbing straight up with the camera pointed straight down makes the nose
     *  direction irrelevant to the framing. */
    fun rocket(subjectLat: Double, subjectLon: Double, startAltM: Float, climbM: Float = 40f, steps: Int = 10): MissionPlan {
        val waypoints = (0..steps).map { i ->
            val frac = i.toFloat() / steps
            Waypoint(
                lat = subjectLat, lon = subjectLon, altM = startAltM + climbM * frac,
                capturePhoto = i == steps, gimbalPitch = -90f, label = "Rocket$i",
            )
        }
        return MissionPlan(
            waypoints = waypoints, name = "Rocket",
            estimatedPhotos = 1,
            estimatedFlightMinutes = (climbM / 3f / 60f).toFloat(),
        )
    }

    /** Out-and-back loop around the subject, camera locked on it throughout, radius ramps
     *  0→[radiusM] on the way out, holds for the loop, then back to 0 on the way back in, so
     *  the path both starts AND ends exactly at the subject's own position (an oval loop, not
     *  a fixed-radius circle like [orbit]). */
    fun boomerang(centerLat: Double, centerLon: Double, radiusM: Float, altM: Float, steps: Int = 48, gimbalPitch: Float = -20f): MissionPlan {
        val mPerDegLat = EARTH_R * Math.PI / 180.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(centerLat))
        val waypoints = (0..steps).map { i ->
            val frac = i.toFloat() / steps
            val angleDeg = 360.0 * frac
            val rad = Math.toRadians(angleDeg)
            // Radius envelope: ramps up over the first quarter, holds full size for the
            // middle half, ramps back down over the last quarter, a smooth oval rather than
            // a sharp in/out spike right at the seam.
            val envelope = when {
                frac < 0.25f -> frac / 0.25f
                frac > 0.75f -> (1f - frac) / 0.25f
                else -> 1f
            }
            val r = radiusM * envelope
            val lat = centerLat + (r * cos(rad)) / mPerDegLat
            val lon = centerLon + (r * sin(rad)) / mPerDegLon
            Waypoint(
                lat = lat, lon = lon, altM = altM,
                capturePhoto = i == steps / 2,   // one capture at the far point of the loop
                gimbalPitch = gimbalPitch,
                label = "Boomerang$i",
                yawOverrideDeg = if (r > 0.5f) bearing(lat, lon, centerLat, centerLon).toFloat() else null,
            )
        }
        return MissionPlan(
            waypoints = waypoints, name = "Boomerang",
            estimatedPhotos = 1,
            estimatedFlightMinutes = ((Math.PI * radiusM) / 3f / 60f).toFloat(),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun normalizeAngle(a: Double): Double = ((a + 180) % 360 + 360) % 360 - 180
}
