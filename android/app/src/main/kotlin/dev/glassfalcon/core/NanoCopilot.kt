// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.collect

/**
 * On-device Gemini Nano via AICore/ML Kit GenAI, no API key, no network call. Backs the
 * "AI Assisted Copilot" mode. `com.google.mlkit:genai-prompt:1.0.0-beta2` is a real, published
 * artifact (verified against dl.google.com/android/maven2 and decompiled directly, the class/
 * method names below are not a guess against unread docs).
 *
 * Devices without AICore (most phones as of 2026, this needs a Tensor-class or similarly
 * capable chip) report [FeatureStatus.UNAVAILABLE] from [checkAvailability] rather than
 * throwing; that's the runtime gate the Settings screen uses to decide whether "AI Assisted
 * Copilot" is even selectable, per the "sanity check the device before allowing copilot" ask.
 */
object NanoCopilot {
    private var client: GenerativeModel? = null
    private fun client(): GenerativeModel = client ?: Generation.getClient().also { client = it }

    /** One of [FeatureStatus.AVAILABLE]/[DOWNLOADABLE]/[DOWNLOADING]/[UNAVAILABLE]. */
    suspend fun checkAvailability(): Int = client().checkStatus()

    /** Runs the model download to completion, reporting bytes downloaded so far, the API
     *  doesn't expose a total, so this is "bytes so far", not a 0..1 fraction. */
    suspend fun download(onProgress: (Long) -> Unit) {
        client().download().collect { status ->
            if (status is DownloadStatus.DownloadProgress) onProgress(status.totalBytesDownloaded)
        }
    }

    /** Drone actions Nano can trigger directly. No native function-calling exists in this API
     *  version (GenerateContentRequest has no tools/functions parameter), this is prompt-
     *  engineered tool use: the model is told to answer with a bare "ACTION: <NAME>" line when
     *  the request matches one of these, and [ask] parses that back out, the same pattern used
     *  before native tool-calling APIs existed. This is the "blueprint" the pilot's own hooks
     *  are exposed through. */
    // The action set the copilot can trigger. Deliberately NON-flight-path-critical (no takeoff,
    // land, or movement via voice) except RETURN_HOME, which is the one safe "get out of trouble"
    // command. Camera/gimbal/zoom + light are all reversible and safe to hand to the model.
    enum class DroneAction {
        RETURN_HOME, TAKE_PHOTO, TOGGLE_RECORD, TOGGLE_LANDING_LIGHT,
        ZOOM_IN, ZOOM_OUT, CENTER_GIMBAL, STATUS_REPORT,
    }

    data class Result(val text: String, val action: DroneAction?)

    suspend fun ask(question: String, flightContext: String): Result {
        val prompt = buildString {
            appendLine("You are GlassFalcon's on-device flight co-pilot for a DJI Mavic 2. You have intimate, live awareness of the aircraft's condition AND the current weather (wind, gusts, temperature, precipitation, cloud, visibility, conditions). Be concise, warm, and safety-first, one or two short sentences. If the aircraft is in a risky state (low battery, high wind or gusts near/over the ~10.7m/s rating, precipitation, poor visibility, storms, obstacle, weak GPS), proactively say so and, when the weather is marginal, recommend landing or holding. Reason about the weather like a pilot: gusts matter more than sustained wind, cold drains the battery faster, and rain/fog is a no-fly.")
            appendLine("Live aircraft state: $flightContext")
            appendLine("If the pilot's request clearly maps to one of these actions, reply with ONLY the single line 'ACTION: <NAME>', using exactly one of: RETURN_HOME, TAKE_PHOTO, TOGGLE_RECORD, TOGGLE_LANDING_LIGHT, ZOOM_IN, ZOOM_OUT, CENTER_GIMBAL, STATUS_REPORT. Use STATUS_REPORT when the pilot asks for a status, report, read-out, or 'how are we doing'. Otherwise just answer conversationally.")
            appendLine("Pilot: $question")
        }
        val response = client().generateContent(prompt)
        val text = response.candidates.firstOrNull()?.text.orEmpty().trim()
        val action = text.takeIf { it.startsWith("ACTION:") }
            ?.let { runCatching { DroneAction.valueOf(it.removePrefix("ACTION:").trim()) }.getOrNull() }
        return Result(text, action)
    }

    fun close() { client?.close(); client = null }
}
