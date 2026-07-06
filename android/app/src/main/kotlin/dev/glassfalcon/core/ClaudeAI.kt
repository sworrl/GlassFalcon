// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Claude API client.
 *
 * Supports:
 *  - streaming text responses (chat)
 *  - tool_use for structured mission planning
 *
 * Set apiKey via ClaudeAI.apiKey before use.
 */
object ClaudeAI {
    var apiKey: String = ""
    // A real, released model ID, the previous "claude-sonnet-4-6" doesn't exist and 404s. Update
    // this if a newer Sonnet ships; the mission planner is the only caller and needs a valid ID.
    private const val MODEL = "claude-sonnet-4-5"
    private const val API_URL = "https://api.anthropic.com/v1/messages"

    // ── System prompt for flight AI ───────────────────────────────────────────

    private val FLIGHT_SYSTEM = """
You are Glass Falcon AI, an expert autonomous drone flight planner and advisor for a DJI Mavic 2 Pro (wm240).

Camera specs: Hasselblad L-Format 20MP, HFOV≈65°, VFOV≈44°, focal length 28mm equiv.
DUML virtual RC scale: ±10000 int16. You control pitch/roll/throttle/yaw.
For photogrammetry: minimum 70% front overlap, 70% side overlap. GSD target ≤ 3 cm/px.

When the user asks to map an area, generate a precise grid survey plan using the create_mission tool.
When advising during flight, respond concisely, the pilot is busy.
Always prioritise safety: check battery, wind, altitude limits.
RTH altitude default 60m. Never plan below 30m in unknown terrain.
""".trimIndent()

    // ── Tools ─────────────────────────────────────────────────────────────────

    private val TOOLS = JSONArray().apply {
        put(JSONObject().apply {
            put("name", "create_mission")
            put("description", "Create a drone mapping/flight mission with waypoints")
            put("input_schema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name",         JSONObject().apply { put("type","string"); put("description","Mission name") })
                    put("mission_type", JSONObject().apply { put("type","string"); put("enum", JSONArray().apply { put("grid_survey"); put("orbit"); put("waypoints") }) })
                    put("altitude_m",   JSONObject().apply { put("type","number"); put("description","Flight altitude in metres above home") })
                    put("front_overlap_pct", JSONObject().apply { put("type","number"); put("description","Front photo overlap %") })
                    put("side_overlap_pct",  JSONObject().apply { put("type","number"); put("description","Side photo overlap %") })
                    put("speed_ms",     JSONObject().apply { put("type","number"); put("description","Target flight speed m/s") })
                    put("area_corners", JSONObject().apply {
                        put("type","array")
                        put("description","Survey area corners as [[lat,lon], ...] list")
                        put("items", JSONObject().apply {
                            put("type","array")
                            put("items", JSONObject().apply { put("type","number") })
                        })
                    })
                    put("orbit_radius_m", JSONObject().apply { put("type","number"); put("description","Orbit radius for orbit missions") })
                    put("orbit_center",   JSONObject().apply {
                        put("type","array")
                        put("description","Orbit center [lat,lon]")
                        put("items", JSONObject().apply { put("type","number") })
                    })
                    put("gimbal_pitch", JSONObject().apply { put("type","number"); put("description","Gimbal pitch angle (-90=nadir, 0=forward)") })
                    put("notes", JSONObject().apply { put("type","string"); put("description","Safety/planning notes for the pilot") })
                })
                put("required", JSONArray().apply { put("name"); put("mission_type"); put("altitude_m") })
            })
        })
    }

    // ── Chat (streaming) ──────────────────────────────────────────────────────

    fun chat(
        messages: List<Pair<String, String>>,   // role → content
        telemetry: DroneState? = null,
        onToken: (String) -> Unit,
    ): Flow<String> = channelFlow {
        val body = JSONObject().apply {
            put("model",      MODEL)
            put("max_tokens", 2048)
            put("stream",     true)
            put("system", buildSystemPrompt(telemetry))
            put("tools", TOOLS)
            put("messages", JSONArray().apply {
                for ((role, content) in messages) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            })
        }

        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("anthropic-version", "2023-06-01")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("content-type", "application/json")
            doOutput = true
            outputStream.write(body.toString().toByteArray())
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream))
        val sb = StringBuilder()
        reader.use { r ->
            r.forEachLine { line ->
                if (!line.startsWith("data: ")) return@forEachLine
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") return@forEachLine
                try {
                    val obj = JSONObject(data)
                    when (obj.optString("type")) {
                        "content_block_delta" -> {
                            val delta = obj.optJSONObject("delta")
                            val text = delta?.optString("text") ?: return@forEachLine
                            sb.append(text)
                            trySend(text)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }.flowOn(Dispatchers.IO)

    // ── Mission planning (non-streaming, returns tool_use result) ─────────────

    suspend fun planMission(
        userRequest: String,
        currentLat: Double,
        currentLon: Double,
        droneState: DroneState?,
    ): MissionPlanResult {
        val body = JSONObject().apply {
            put("model",      MODEL)
            put("max_tokens", 4096)
            put("system", buildSystemPrompt(droneState))
            put("tools", TOOLS)
            put("tool_choice", JSONObject().apply { put("type", "auto") })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", """
Current drone position: lat=$currentLat, lon=$currentLon
Current altitude: ${droneState?.altRel ?: 0}m AGL
Battery: ${droneState?.battPct ?: 0}%

User request: $userRequest
""".trimIndent())
                })
            })
        }

        return withIO {
            try {
                val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("anthropic-version", "2023-06-01")
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("content-type", "application/json")
                    doOutput = true
                    outputStream.write(body.toString().toByteArray())
                }

                // A non-2xx (bad/expired key, 429) makes inputStream throw, read errorStream
                // instead and surface it, rather than letting the IOException crash the
                // planning coroutine (its caller has no catch).
                if (conn.responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    return@withIO MissionPlanResult(error = "Claude HTTP ${conn.responseCode}: ${err.take(180)}")
                }
                val resp = conn.inputStream.bufferedReader().readText()
                parseMissionPlanResult(resp, currentLat, currentLon)
            } catch (e: Exception) {
                MissionPlanResult(error = e.message ?: "network error")
            }
        }
    }

    // ── Parse tool_use response into a MissionPlan ────────────────────────────

    private fun parseMissionPlanResult(
        resp: String, currentLat: Double, currentLon: Double
    ): MissionPlanResult {
        return try {
            val obj = JSONObject(resp)
            val content = obj.getJSONArray("content")
            var notes = ""
            var plan: MissionPlan? = null

            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> notes += block.optString("text")
                    "tool_use" -> {
                        if (block.optString("name") == "create_mission") {
                            val input = block.getJSONObject("input")
                            plan = buildMissionFromTool(input, currentLat, currentLon)
                        }
                    }
                }
            }
            MissionPlanResult(plan = plan, notes = notes)
        } catch (e: Exception) {
            MissionPlanResult(error = e.message)
        }
    }

    private fun buildMissionFromTool(
        input: JSONObject, currentLat: Double, currentLon: Double
    ): MissionPlan {
        val missionType   = input.optString("mission_type", "grid_survey")
        val altM          = input.optDouble("altitude_m", 80.0).toFloat()
        val frontOverlap  = input.optDouble("front_overlap_pct", 75.0).toFloat()
        val sideOverlap   = input.optDouble("side_overlap_pct", 70.0).toFloat()
        val speedMs       = input.optDouble("speed_ms", 5.0).toFloat()
        val gimbalPitch   = input.optDouble("gimbal_pitch", -90.0).toFloat()

        return when (missionType) {
            "orbit" -> {
                // optDouble returns NaN (not null) for a missing/malformed element, so a
                // bare ?: does NOT catch it, an orbit_center of [] would yield NaN GPS and
                // a mission full of NaN waypoints. Fall back to current position on non-finite.
                val center = input.optJSONArray("orbit_center")
                val lat = center?.optDouble(0)?.takeIf { it.isFinite() } ?: currentLat
                val lon = center?.optDouble(1)?.takeIf { it.isFinite() } ?: currentLon
                val radius = input.optDouble("orbit_radius_m", 30.0).toFloat()
                MissionPlanner.orbit(lat, lon, radius, altM, gimbalPitch = gimbalPitch)
            }
            else -> {
                val cornersArr = input.optJSONArray("area_corners")
                val corners: List<Pair<Double, Double>> = if (cornersArr != null && cornersArr.length() >= 2) {
                    (0 until cornersArr.length()).map {
                        val c = cornersArr.getJSONArray(it)
                        c.getDouble(0) to c.getDouble(1)
                    }
                } else {
                    // Default 200×200 m box around current position
                    val d = 0.001
                    listOf(
                        currentLat - d to currentLon - d,
                        currentLat + d to currentLon + d,
                    )
                }
                MissionPlanner.gridSurvey(SurveyArea(
                    corners = corners,
                    altM = altM,
                    frontOverlapPct = frontOverlap,
                    sideOverlapPct  = sideOverlap,
                    speedMs = speedMs,
                ))
            }
        }
    }

    private fun buildSystemPrompt(state: DroneState?): String {
        val tel = state?.let {
            "\n\nLive telemetry: alt=${it.altRel}m speed=${it.speed}m/s " +
            "batt=${it.battPct}% lat=${it.lat} lon=${it.lon}"
        } ?: ""
        return FLIGHT_SYSTEM + tel
    }

    private suspend fun <T> withIO(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}

data class MissionPlanResult(
    val plan: MissionPlan? = null,
    val notes: String = "",
    val error: String? = null,
)
