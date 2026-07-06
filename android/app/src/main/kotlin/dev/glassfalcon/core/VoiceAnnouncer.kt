// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Categories of spoken callout the pilot can individually mute. Every voice announcement in the
 * app routes through one of these so the co-pilot's chatter is fully tunable, a pilot who only
 * wants battery + warnings can silence everything else, and vice-versa. [defaultOn] is false only
 * for the routine/noisy categories so a fresh install is informative without being talkative.
 */
enum class AnnounceCategory(val id: String, val label: String, val desc: String, val defaultOn: Boolean = true) {
    TAKEOFF_LANDING("takeoff", "Take-off & landing", "\"Taking off\", \"Landed\"."),
    GPS("gps", "GPS status", "GPS ready / signal lost."),
    HOME_POINT("home", "Home point", "\"Home point recorded\"."),
    BATTERY("battery", "Battery", "Low / critical battery, forced-landing notice."),
    WIND_WEATHER("wind", "Wind & weather", "High wind / gust and weather-hazard callouts."),
    OBSTACLE("obstacle", "Obstacles", "Obstacle-ahead / behind proximity alerts.", defaultOn = false),
    MOTOR_SENSOR("motor", "Motor & sensors", "ESC, barometer, IMU, downward-sensor faults."),
    LINK("link", "Link & signal", "Controller-only / drone signal lost."),
    COMMANDS("commands", "Command confirmations", "\"Return to home\", \"Landing\" when you issue them."),
    AIRSPACE("airspace", "Airspace", "\"Entering …\" when nearing controlled/restricted airspace.", defaultOn = false),
    WARNING("warning", "Other warnings", "Anything else the flight controller flags."),
    STATUS("status", "Status reports", "The on-demand full status read-out.");

    companion object { fun byId(id: String) = entries.firstOrNull { it.id == id } }
}

/**
 * Spoken flight-event prompts through the PHONE's speaker, the same idea as DJI GO 4's voice
 * callouts ("Return to Home Point Recorded", "Aircraft Taking Off", …). This is phone-side Android
 * [TextToSpeech], NOT the controller's buzzer (the Mavic 2 RC has only a beeper, no speaker).
 *
 * Enable state (master + per-[AnnounceCategory]) is persisted so a pilot's preferences stick across
 * launches. [announce] is a no-op until the engine has initialised, while muted, and while the
 * category is switched off, so callers can fire events freely without guarding each one.
 */
class VoiceAnnouncer(context: Context) {
    private val prefs = context.getSharedPreferences("glassfalcon_voice", Context.MODE_PRIVATE)

    /** Master switch, silences every category at once when off. */
    @Volatile var enabled: Boolean = prefs.getBoolean("enabled", true)
        set(value) { field = value; prefs.edit().putBoolean("enabled", value).apply() }

    fun categoryEnabled(cat: AnnounceCategory): Boolean =
        prefs.getBoolean("cat_${cat.id}", cat.defaultOn)

    fun setCategoryEnabled(cat: AnnounceCategory, on: Boolean) {
        prefs.edit().putBoolean("cat_${cat.id}", on).apply()
    }

    @Volatile private var ready = false
    private var tts: TextToSpeech? = null

    // Speech rate/pitch, persisted. Default rate is slightly brisk, a busy pilot wants callouts
    // to land fast, not drawl.
    var rate: Float = prefs.getFloat("rate", 1.05f); private set
    var pitch: Float = prefs.getFloat("pitch", 1.0f); private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.language = Locale.US }
                applyVoicePrefs()
                ready = true
            }
        }
    }

    /** Apply saved rate/pitch and pick the voice: the pilot's saved choice if still present, else
     *  the best-sounding one available (default TTS otherwise defaults to a bland low-quality voice). */
    private fun applyVoicePrefs() {
        val t = tts ?: return
        runCatching { t.setSpeechRate(rate) }
        runCatching { t.setPitch(pitch) }
        val saved = prefs.getString("voice_name", null)
        val chosen = saved?.let { name -> t.voices?.firstOrNull { it.name == name } } ?: bestVoice()
        chosen?.let { v -> runCatching { t.voice = v } }
    }

    /** Highest-quality English voice, preferring on-device (works in the field with no signal) and
     *  skipping not-yet-downloaded ones; falls back to any English voice. */
    private fun bestVoice(): Voice? {
        val vs = tts?.voices ?: return null
        fun installed(v: Voice) = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        return vs.filter { it.locale.language == "en" && installed(it) && !it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
            ?: vs.filter { it.locale.language == "en" && installed(it) }.maxByOrNull { it.quality }
    }

    data class VoiceOption(val name: String, val label: String)

    /** English voices worth offering, best-first, labelled by quality + on/offline. */
    fun availableVoices(): List<VoiceOption> {
        val vs = tts?.voices ?: return emptyList()
        return vs.filter { it.locale.language == "en" && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired })
            .map { v ->
                val q = when {
                    v.quality >= Voice.QUALITY_VERY_HIGH -> "very high"
                    v.quality >= Voice.QUALITY_HIGH -> "high"
                    v.quality >= Voice.QUALITY_NORMAL -> "normal"
                    else -> "low"
                }
                val net = if (v.isNetworkConnectionRequired) " · online" else ""
                VoiceOption(v.name, "${v.locale.country.ifBlank { "EN" }} · $q$net")
            }
    }

    fun currentVoiceName(): String? = tts?.voice?.name

    fun setVoice(name: String) {
        val v = tts?.voices?.firstOrNull { it.name == name } ?: return
        runCatching { tts?.voice = v }
        prefs.edit().putString("voice_name", name).apply()
    }

    fun setRate(r: Float) { rate = r.coerceIn(0.6f, 1.8f); runCatching { tts?.setSpeechRate(rate) }; prefs.edit().putFloat("rate", rate).apply() }
    fun setPitch(p: Float) { pitch = p.coerceIn(0.6f, 1.6f); runCatching { tts?.setPitch(pitch) }; prefs.edit().putFloat("pitch", pitch).apply() }

    /** Speak a sample line so the pilot can hear the current voice/rate while tuning. */
    fun preview() {
        if (!ready) return
        runCatching { tts?.speak("Glass Falcon co-pilot. Battery 82 percent, wind 4 gusting 7, altitude 60 meters.", TextToSpeech.QUEUE_FLUSH, null, "preview") }
    }

    /** Speak [text] for [category]. Silently ignored if the engine isn't ready, the master switch
     *  is off, or that category is muted. [urgent] flushes the queue (interrupts) instead of
     *  queueing after the current utterance, for time-critical safety callouts. */
    fun announce(category: AnnounceCategory, text: String, urgent: Boolean = false) {
        if (!enabled || !ready || !categoryEnabled(category)) return
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        runCatching { tts?.speak(text, mode, null, text.hashCode().toString()) }
    }

    /** Map a HUD warning string (with its leading emoji) to the category it belongs to, so the
     *  same per-category mute applies to controller-flagged warnings too. */
    fun warningCategory(warning: String): AnnounceCategory {
        val w = warning.lowercase()
        return when {
            "battery" in w || "land" in w -> AnnounceCategory.BATTERY
            "wind" in w || "weather" in w -> AnnounceCategory.WIND_WEATHER
            "esc" in w || "motor" in w || "barometer" in w || "sensor" in w || "imu" in w -> AnnounceCategory.MOTOR_SENSOR
            "signal" in w || "controller" in w || "link" in w -> AnnounceCategory.LINK
            else -> AnnounceCategory.WARNING
        }
    }

    /** Speak a controller warning through its mapped category (urgent, safety-critical). */
    fun announceWarning(warning: String) {
        val clean = warning.filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
        if (clean.isNotBlank()) announce(warningCategory(warning), clean, urgent = true)
    }

    /** Speak immediately regardless of category muting, for the explicit, user-triggered full
     *  status read-out, which the pilot asked for and so should always be heard (still honours the
     *  master switch). */
    fun speakStatus(text: String) {
        if (!enabled || !ready) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString()) }
    }

    fun shutdown() { runCatching { tts?.shutdown() } }
}
