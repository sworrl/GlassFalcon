// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Gemini API client for the in-flight voice co-pilot, separate from [ClaudeAI] (mission
 * planning) since the user specifically wants Gemini for the co-pilot voice/PTT flow. Same
 * pattern as ClaudeAI: set [apiKey] at runtime from a settings field, never bake a real key
 * into the app or repo. No key set → every call fails fast with a clear error instead of an
 * opaque network exception.
 */
object GeminiCoPilot {
    var apiKey: String = ""
    private const val MODEL = "gemini-2.5-pro"
    private fun url() = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"

    private val SYSTEM_PREAMBLE = """
You are GlassFalcon's flight co-pilot for a DJI Mavic 2 Pro (wm240). You have intimate, live
awareness of the aircraft's condition AND the current weather (wind, gusts, temperature,
precipitation, cloud, visibility). Be concise, warm, and safety-first, one or two short sentences
unless the pilot asks for detail. Proactively flag risky states: low battery, high wind or gusts
near/over the ~10.7 m/s rating, precipitation, poor visibility, storms, obstacles, or weak GPS.
Reason about weather like a pilot: gusts matter more than sustained wind, cold drains the battery
faster, rain/fog is a no-fly.

You CAN trigger a small set of safe aircraft actions. If the pilot's request clearly maps to one,
reply with ONLY the single line 'ACTION: <NAME>', using exactly one of: RETURN_HOME, TAKE_PHOTO,
TOGGLE_RECORD, TOGGLE_LANDING_LIGHT, ZOOM_IN, ZOOM_OUT, CENTER_GIMBAL, STATUS_REPORT. Use
STATUS_REPORT when the pilot asks for a status, report, read-out, or "how are we doing". For
anything else (including flying somewhere or landing, which only the pilot's sticks can do), just
answer conversationally.
""".trimIndent()

    /** One-shot request → answer or action, given a snapshot of current flight context. Mirrors
     *  [NanoCopilot.ask]'s prompt-engineered tool use so cloud Gemini is a full co-pilot, not just
     *  a Q&A fallback: an 'ACTION: <NAME>' reply is parsed back into a [NanoCopilot.DroneAction]. */
    suspend fun ask(question: String, context: String): CoPilotResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext CoPilotResult(error = "Gemini API key not set")
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply { put(JSONObject().apply { put("text", SYSTEM_PREAMBLE) }) })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Current flight state:\n$context\n\nPilot asks: $question") })
                    })
                })
            })
        }
        try {
            val conn = (URL(url()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8000
                readTimeout = 15000
                outputStream.write(body.toString().toByteArray())
            }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                return@withContext CoPilotResult(error = "Gemini HTTP ${conn.responseCode}: ${err.take(180)}")
            }
            val resp = JSONObject(conn.inputStream.bufferedReader().readText())
            val text = resp.optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.trim()
            if (text.isNullOrBlank()) return@withContext CoPilotResult(error = "Gemini returned no answer")
            val action = text.takeIf { it.startsWith("ACTION:") }
                ?.let { runCatching { NanoCopilot.DroneAction.valueOf(it.removePrefix("ACTION:").trim()) }.getOrNull() }
            if (action != null) CoPilotResult(action = action) else CoPilotResult(answer = text)
        } catch (e: Exception) {
            CoPilotResult(error = e.message ?: "network error")
        }
    }
}

data class CoPilotResult(
    val answer: String? = null,
    val error: String? = null,
    val action: NanoCopilot.DroneAction? = null,
)
