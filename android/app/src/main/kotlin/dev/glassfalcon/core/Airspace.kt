// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** FAA public airspace data (ArcGIS FeatureServer, keyless, public domain, confirmed live).
 *  Shared between the map overlay (MapScreen.kt) and the co-pilot's airspace callouts
 *  (FlightViewModel.kt) so the query construction lives in exactly one place. */
const val FAA_HOST = "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services"

fun faaQueryUrl(service: String, bbox: DoubleArray): String {
    val (minLon, minLat, maxLon, maxLat) = bbox
    return "$FAA_HOST/$service/FeatureServer/0/query" +
        "?geometry=$minLon,$minLat,$maxLon,$maxLat&geometryType=esriGeometryEnvelope" +
        "&inSR=4326&outSR=4326&spatialRel=esriSpatialRelIntersects&outFields=*&f=geojson"
}

/** ~35km padding for controlled/restricted boundaries, sparse, large polygons, safe to query wide. */
fun wideBbox(lat: Double, lon: Double) = doubleArrayOf(lon - 0.3, lat - 0.3, lon + 0.3, lat + 0.3)

/** Tighter box for the UAS Facility Map altitude grid: many small cells, and a wide box over a
 *  dense metro can exceed the ArcGIS 2000-record page limit (unhandled in this version). */
fun narrowBbox(lat: Double, lon: Double) = doubleArrayOf(lon - 0.1, lat - 0.1, lon + 0.1, lat + 0.1)

/** Round to ~11km so ordinary GPS jitter doesn't refire airspace queries on every fix. */
fun bucket(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0

/** One nearby airspace's name + ceiling, straight from the FAA feature's own fields, real
 *  field names confirmed against the live FeatureServer's own schema (`?f=json` on
 *  Class_Airspace/FeatureServer/0): UPPER_VAL (Double, usually feet per UPPER_UOM), UPPER_DESC
 *  (e.g. "SFC" for surface-based floors, or textual notes for unusual ceilings). [upperFt] is
 *  null when the feature has no numeric ceiling (some Special Use Airspace entries only have a
 *  textual UPPER_DESC like "UNLIMITED"). */
data class AirspaceInfo(
    val name: String, val upperFt: Double?, val upperDesc: String?,
    val classCode: String?, val lowerFt: Double?, val lowerDesc: String?,
)

/**
 * Fetches the FAA Class Airspace + Special Use Airspace info covering [lat]/[lon] "right now", 
 * name for the co-pilot's "Entering X Airspace" callouts and the top bar's airspace badge,
 * ceiling for the top bar's height-limit readout. This is a bounding-box query, not true
 * point-in-polygon containment (that needs real polygon math this pass doesn't do), so treat it
 * as "you're in the neighborhood of this airspace" rather than a precise boundary crossing, 
 * same advisory-only caveat as the map's own airspace overlay.
 */
suspend fun fetchNearbyAirspaceInfo(lat: Double, lon: Double): List<AirspaceInfo> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val result = mutableListOf<AirspaceInfo>()
    val bbox = wideBbox(lat, lon)
    for (service in listOf("Class_Airspace", "Special_Use_Airspace")) {
        try {
            val conn = URL(faaQueryUrl(service, bbox)).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            val features = JSONObject(body).optJSONArray("features") ?: continue
            for (i in 0 until features.length()) {
                val props = features.getJSONObject(i).optJSONObject("properties") ?: continue
                val name = props.optString("NAME").takeIf { it.isNotBlank() }
                    ?: props.optString("COMM_NAME").takeIf { it.isNotBlank() }
                    ?: continue
                // Confirmed live 2026-07-03: this FAA layer uses -9998 (and likely -9999) as a
                // "no numeric value" sentinel rather than omitting the field or nulling it, 
                // a real ceiling is never negative, so reject anything below 0 rather than
                // showing "≤-9998ft" as if it meant something.
                val upperFt = if (props.has("UPPER_VAL") && !props.isNull("UPPER_VAL")) {
                    props.optDouble("UPPER_VAL").takeIf { it.isFinite() && it >= 0 }
                } else null
                val lowerFt = if (props.has("LOWER_VAL") && !props.isNull("LOWER_VAL")) {
                    props.optDouble("LOWER_VAL").takeIf { it.isFinite() && it >= 0 }
                } else null
                val upperDesc = props.optString("UPPER_DESC").takeIf { it.isNotBlank() }
                val lowerDesc = props.optString("LOWER_DESC").takeIf { it.isNotBlank() }
                // Real field, confirmed live 2026-07-03 against Class_Airspace/FeatureServer/0's
                // own schema, plain letter values ("B", "C", "D", ...), only present on the
                // Class_Airspace service (Special_Use_Airspace features won't have it).
                val classCode = props.optString("CLASS").takeIf { it.isNotBlank() }
                result += AirspaceInfo(name, upperFt, upperDesc, classCode, lowerFt, lowerDesc)
            }
        } catch (_: Exception) {
            // Best-effort, a failed airspace lookup shouldn't disrupt the flight or the co-pilot.
        }
    }
    result.distinctBy { it.name }
}

/** Thin wrapper for the co-pilot's voice-callout path, which only ever needed names. */
suspend fun fetchNearbyAirspaceNames(lat: Double, lon: Double): List<String> =
    fetchNearbyAirspaceInfo(lat, lon).map { it.name }
