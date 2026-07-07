// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaActionSound
import android.net.Uri
import android.view.Surface
import androidx.documentfile.provider.DocumentFile
import dev.glassfalcon.GlassFalconApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AppState(
    val connected: Boolean = false,
    val host: String = "",
    val port: Int = 10000,
    val log: List<String> = emptyList(),
    val videoUrl: String = "",
    val relayUrl: String = "",
    val claudeApiKey: String = "",
    val geminiApiKey: String = "",
    // Off by default, the co-pilot only ever activates once the pilot explicitly opts in
    // from Settings → Local & LLM. RULE_BASED speaks only fixed, pre-written callouts (battery/
    // GPS/warnings/airspace) via TTS, no LLM involved at all. AI_ASSISTED adds PTT Q&A and
    // drone-action dispatch, backed by on-device Gemini Nano (see NanoCopilot.kt).
    val copilotMode: CopilotMode = CopilotMode.OFF,
    // Cloud Gemini/Claude key UI is hidden behind this (off by default) rather than deleted, 
    // AI_ASSISTED mode uses on-device Nano by default; this is an opt-in fallback for devices
    // without AICore, not a requirement.
    val showCloudAiOptions: Boolean = false,
    // On by default, lets a pilot who doesn't want the capture button on the flight HUD at
    // all turn it off from Settings → Camera, rather than just ignoring it.
    val captureButtonEnabled: Boolean = true,
)

enum class CopilotMode { OFF, RULE_BASED, AI_ASSISTED, GEMINI_CLOUD, HYBRID }

/** One recorded map-track vertex. Carries the metrics needed to color the trail by speed/altitude
 *  AND to chart telemetry over time in the flight report: [tMs] is the wall-clock sample time
 *  (0 on legacy records saved before this field existed → charts fall back to sample-index), and
 *  [battPct] is the drone battery % at the sample (-1 if unknown). */
data class TrackPoint(
    val lat: Double, val lon: Double, val speed: Float, val alt: Float,
    val tMs: Long = 0L, val battPct: Int = -1,
)

class FlightViewModel : ViewModel(), dev.glassfalcon.ui.screens.MapTelemetrySource {
    val duml    = DumlConnection()
    val decoder = TelemetryDecoder()
    val video   = VideoDecoder()
    private val videoListener: (ByteArray) -> Unit = { video.onVideoPayload(it) }

    val mission = MissionEngine(duml, viewModelScope)
    val offload = OffloadManager(viewModelScope)
    // ActiveTrack / TapFly need a live TextureView + ML Kit (not portable to sdk/), so their
    // control loops live here in app/ rather than alongside MissionEngine, see
    // ActiveTrackController.kt / TapFlyController.kt doc comments.
    val activeTrack = ActiveTrackController(duml, viewModelScope)
    val tapFly = TapFlyController(duml, viewModelScope)

    private val _app = MutableStateFlow(AppState())
    val app: StateFlow<AppState> = _app

    override val drone    get() = decoder.drone
    val gimbal   get() = decoder.gimbal
    val obstacle get() = decoder.obstacle
    override val airSense get() = decoder.airSense
    val cameraState get() = decoder.camera
    val rcButtonHistory get() = decoder.rcButtonHistory
    val deviceInfoRaw get() = decoder.deviceInfoRaw

    // ── Customizable RC240 buttons ──────────────────────────────────────────
    private val _rcButtonSlots = MutableStateFlow(RcButtonStore.load(GlassFalconApp.ctx))
    val rcButtonSlots: StateFlow<List<RcButtonSlot>> = _rcButtonSlots
    private val _learningSlot = MutableStateFlow<Int?>(null)  // 1 or 2 while waiting for a press
    val learningSlot: StateFlow<Int?> = _learningSlot
    private var learnBaseline: Int? = null
    private var lastRcPayload: Int? = null
    private val buttonHeld = mutableMapOf<Int, Boolean>()

    /** Starts calibration for a slot: the next raw button-status reading that differs from
     *  whatever's currently being received becomes that slot's signature. No guessing at the
     *  byte layout, the pilot presses the physical button once and GlassFalcon watches for
     *  the change itself. */
    fun startLearningButton(slotIndex: Int) {
        _learningSlot.value = slotIndex
        learnBaseline = lastRcPayload
    }
    fun cancelLearningButton() { _learningSlot.value = null; learnBaseline = null; _guidedCalibration.value = false }

    // ── Guided calibration, walks every slot in one pass instead of separate manual "Learn"
    // taps, so a pilot pressing several controls in sequence gets each one captured cleanly
    // instead of the first press contaminating a single-slot Learn session. ──
    private val _guidedCalibration = MutableStateFlow(false)
    val guidedCalibration: StateFlow<Boolean> = _guidedCalibration

    /** Starts at the first not-yet-learned slot (so re-running after [addRcButtonSlot] doesn't
     *  force re-learning slots that are already good), or slot 1 if everything's learned and
     *  this is a full re-calibration pass. */
    fun startGuidedCalibration() {
        _guidedCalibration.value = true
        val firstBlank = _rcButtonSlots.value.indexOfFirst { it.signature == null } + 1
        startLearningButton(if (firstBlank > 0) firstBlank else 1)
    }

    fun setButtonAction(slotIndex: Int, action: RcButtonAction) {
        val slots = _rcButtonSlots.value.toMutableList()
        slots[slotIndex - 1] = slots[slotIndex - 1].copy(action = action)
        _rcButtonSlots.value = slots
        RcButtonStore.save(GlassFalconApp.ctx, slotIndex, slots[slotIndex - 1])
    }

    /** Adds a new blank slot, for controls beyond the two dedicated custom buttons, like the
     *  5-way dial near the screen (up/down/left/right/press are each their own learnable
     *  signature, same as C1/C2). */
    fun addRcButtonSlot() {
        RcButtonStore.addSlot(GlassFalconApp.ctx)
        _rcButtonSlots.value = RcButtonStore.load(GlassFalconApp.ctx)
    }

    private fun onRcButtonFrame(cmdId: Int, payload: Int) {
        val learning = _learningSlot.value
        if (learning != null) {
            val base = learnBaseline ?: 0
            if (payload != base) {
                val mask = base xor payload
                if (mask != 0) {
                    val sig = RcButtonSignature(cmdId, mask, payload and mask)
                    val slots = _rcButtonSlots.value.toMutableList()
                    slots[learning - 1] = slots[learning - 1].copy(signature = sig)
                    _rcButtonSlots.value = slots
                    RcButtonStore.save(GlassFalconApp.ctx, learning, slots[learning - 1])
                    _learningSlot.value = null
                    learnBaseline = null
                    log("Learned Button $learning: cmd_id 0x%02x mask 0x%02x value 0x%02x".format(cmdId, mask, sig.value))
                    if (_guidedCalibration.value && learning < _rcButtonSlots.value.size) {
                        startLearningButton(learning + 1) // advance to the next slot automatically
                    } else {
                        _guidedCalibration.value = false  // guided run finished, or this was a manual single-slot Learn
                    }
                }
            }
        } else {
            var handled = false
            _rcButtonSlots.value.forEachIndexed { i, slot ->
                val sig = slot.signature ?: return@forEachIndexed
                val nowPressed = sig.matches(payload)
                val wasPressed = buttonHeld[i] ?: false
                if (nowPressed != wasPressed) {
                    buttonHeld[i] = nowPressed
                    fireButtonAction(slot.action, pressed = nowPressed)
                    handled = true
                    if (nowPressed) {
                        // Resulting state for toggles → keeps the banner up (sticky) while ON.
                        val (state, sticky) = when (slot.action) {
                            RcButtonAction.TOGGLE_LANDING_LIGHT -> {
                                val on = _ledMode.value == 0; (if (on) "LIGHT ON" else "LIGHT OFF") to on
                            }
                            RcButtonAction.TOGGLE_RECORD -> {
                                val on = !_isRecording.value; (if (on) "REC ●" else "REC STOP") to on
                            }
                            RcButtonAction.PUSH_TO_TALK -> "PTT, listening" to true
                            else -> null to false
                        }
                        val label = if (slot.action == RcButtonAction.NONE) "unassigned" else slot.action.label
                        emitButtonFlash("C${i + 1} · $label" + (state?.let { " · $it" } ?: ""), sticky)
                    } else if (slot.action == RcButtonAction.PUSH_TO_TALK) {
                        emitButtonFlash("C${i + 1} · PTT released", false)
                    }
                }
            }
            // Uncalibrated press: the raw reading changed to a non-zero value and no LEARNED slot
            // claimed it, so the pilot still gets feedback ("you pressed something, teach it").
            if (!handled && payload != 0 && payload != (lastRcPayload ?: 0)) {
                emitButtonFlash("RC button, calibrate in Settings › RC Buttons", false)
            }
        }
        lastRcPayload = payload
    }

    // Transient on-screen banner for RC button presses (see RcButtonFlash / the HUD overlay).
    @Volatile private var flashSeq = 0L
    private val _rcButtonFlash = MutableStateFlow<RcButtonFlash?>(null)
    val rcButtonFlash: StateFlow<RcButtonFlash?> = _rcButtonFlash
    private fun emitButtonFlash(text: String, sticky: Boolean) {
        flashSeq += 1
        _rcButtonFlash.value = RcButtonFlash(text, sticky, flashSeq)
    }
    /** HUD calls this when a non-sticky flash's timer elapses, or a sticky toggle goes off. */
    fun clearButtonFlash(id: Long) {
        if (_rcButtonFlash.value?.id == id) _rcButtonFlash.value = null
    }

    private fun fireButtonAction(action: RcButtonAction, pressed: Boolean) {
        when (action) {
            RcButtonAction.NONE -> {}
            RcButtonAction.PUSH_TO_TALK -> if (pressed) startCoPilotListening() else stopCoPilotListening()
            RcButtonAction.TOGGLE_LANDING_LIGHT -> if (pressed) setLed(if (_ledMode.value == 0) 1 else 0)
            RcButtonAction.RETURN_HOME -> if (pressed) sendRth()
            RcButtonAction.TAKE_PHOTO -> if (pressed) capturePhoto()
            RcButtonAction.TOGGLE_RECORD -> if (pressed) toggleRecord()
            RcButtonAction.TOGGLE_CAMERA_MODE -> if (pressed) toggleCameraMode()
        }
    }

    // ── Local ambient temperature, phone's own sensor, not the drone's ────────────────────
    // TYPE_AMBIENT_TEMPERATURE is a real hardware sensor that most current phones simply don't
    // ship (it's been rare since well before 2020); null here means "no such sensor on this
    // device," not "still loading", the HUD shows that honestly instead of a fake reading.
    private val _ambientTempC = MutableStateFlow<Float?>(null)
    val ambientTempC: StateFlow<Float?> = _ambientTempC

    // Forecast surface weather at the pilot's position (Windy Point Forecast, see WindyWeather).
    // Wind is a first-class flight-safety readout on a Mavic 2; ambient temp here also backfills
    // the HUD's temperature when the phone has no hardware temperature sensor (most don't).
    private val _weather = MutableStateFlow<WeatherNow?>(null)
    val weather: StateFlow<WeatherNow?> = _weather

    // HUD display prefs (persisted). radarRing toggles the crisp outer rim arc on the obstacle
    // radar domes, the soft glow + color bleed stay; only the hard "circle" outline is optional.
    private val hudPrefs = GlassFalconApp.ctx.getSharedPreferences("glassfalcon_hud", android.content.Context.MODE_PRIVATE)
    private val _radarRing = MutableStateFlow(hudPrefs.getBoolean("radar_ring", true))
    val radarRing: StateFlow<Boolean> = _radarRing
    fun setRadarRing(on: Boolean) {
        hudPrefs.edit().putBoolean("radar_ring", on).apply()
        _radarRing.value = on
    }

    // AI/co-pilot settings persistence. These used to live only in the in-memory AppState, so the
    // co-pilot mode and both API keys reset to defaults on every app restart (the settings screen
    // even warned about it). Now persisted here and loaded once in init so they survive a relaunch.
    private val aiPrefs = GlassFalconApp.ctx.getSharedPreferences("glassfalcon_ai", android.content.Context.MODE_PRIVATE)
    private fun loadAiPrefs() {
        val mode = runCatching { CopilotMode.valueOf(aiPrefs.getString("copilot_mode", CopilotMode.OFF.name)!!) }
            .getOrDefault(CopilotMode.OFF)
        val gem = aiPrefs.getString("gemini_key", "") ?: ""
        val cla = aiPrefs.getString("claude_key", "") ?: ""
        GeminiCoPilot.apiKey = gem
        ClaudeAI.apiKey = cla
        _app.value = _app.value.copy(copilotMode = mode, geminiApiKey = gem, claudeApiKey = cla)
    }

    // On-device flight dump (no-root DUML trace, see FlightDumpRecorder). Records every frame the
    // app sees; can auto-arm on takeoff and disarm on touchdown for hands-off flight-test capture.
    val flightDump = FlightDumpRecorder(GlassFalconApp.ctx)
    private var wasInAirForDump = false
    @Volatile private var afcSentThisLink = false
    fun setWindyKeys(point: String?, map: String?, webcam: String?) {
        WindyWeather.setKeys(GlassFalconApp.ctx, point, map, webcam)
        viewModelScope.launch { fetchWeatherOnce() }
    }
    private suspend fun fetchWeatherOnce() {
        val loc = _phoneLoc.value ?: return
        // Open-Meteo first: keyless, real CURRENT wind + gusts (the fast-changing NM danger), no
        // rate-limit worry. Windy point forecast is the fallback only if Open-Meteo is unreachable
        // and the pilot has set a Windy key, it's a 6-hourly GFS forecast with no gust datum, so
        // it's the weaker read for live conditions but better than nothing offline.
        val w = withContext(Dispatchers.IO) {
            OpenMeteo.fetchCurrent(loc.first, loc.second)
                ?: WindyWeather.pointKey(GlassFalconApp.ctx).takeIf { it.isNotBlank() }
                    ?.let { WindyWeather.fetchPoint(loc.first, loc.second, it) }
        } ?: return
        _weather.value = w
        if (_ambientTempC.value == null) _ambientTempC.value = w.tempC  // backfill only; real sensor wins
    }
    private val sensorManager by lazy {
        GlassFalconApp.ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
    }
    private val ambientTempListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) { _ambientTempC.value = event.values[0] }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }
    private fun startAmbientTempSensor() {
        val sensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_AMBIENT_TEMPERATURE) ?: return
        sensorManager.registerListener(ambientTempListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }

    // Claude AI chat history (role, content)
    private val _chatHistory = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val chatHistory: StateFlow<List<Pair<String, String>>> = _chatHistory

    private val _aiThinking = MutableStateFlow(false)
    val aiThinking: StateFlow<Boolean> = _aiThinking

    private val _aiResponse = MutableStateFlow("")
    val aiResponse: StateFlow<String> = _aiResponse

    private var joystickJob: Job? = null
    @Volatile private var roll     = 0f
    @Volatile private var pitch    = 0f
    @Volatile private var throttle = 0f
    @Volatile private var yaw      = 0f

    private val _flightTimer = MutableStateFlow("00:00")
    val flightTimer: StateFlow<String> = _flightTimer
    private var timerJob: Job? = null
    private var authJob: Job? = null
    private var armMs = 0L

    // ── Map state (lat,lon pairs; kept map-library-agnostic) ──────────────────
    private val _track     = MutableStateFlow<List<TrackPoint>>(emptyList())
    override val track: StateFlow<List<TrackPoint>> = _track
    // Last position the drone reported, deliberately NOT cleared on disconnect,
    // so it survives signal loss ("drone lost? it was last here").
    private val _lastKnown = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastKnown: StateFlow<Pair<Double, Double>?> = _lastKnown
    private val _homePoint = MutableStateFlow<Pair<Double, Double>?>(null)
    override val homePoint: StateFlow<Pair<Double, Double>?> = _homePoint

    // ── Flight records, auto-recorded takeoff→touchdown, every stat a real telemetry read ──
    private val _flightRecords = MutableStateFlow(FlightRecordStore.loadAll())
    val flightRecords: StateFlow<List<FlightRecord>> = _flightRecords

    // Set the instant a flight's record is saved (see trackFlightRecord below), so the HUD can
    // auto-show a Flight Summary screen right after landing instead of the pilot having to dig
    // it out of Settings → Flight Records afterward. Cleared by dismissFlightSummary once shown.
    private val _justLandedRecord = MutableStateFlow<FlightRecord?>(null)
    val justLandedRecord: StateFlow<FlightRecord?> = _justLandedRecord
    fun dismissFlightSummary() { _justLandedRecord.value = null }

    // DJI GO 4 import: finds and copies the pilot's own flight-record files from a
    // user-picked folder (Storage Access Framework, explicit consent, no broad storage
    // permission). Deliberately does NOT decode DJI's proprietary log format; that's a
    // separate reverse-engineering project this pass doesn't attempt rather than fake.
    private val _djiImportedFiles = MutableStateFlow<List<String>>(emptyList())
    val djiImportedFiles: StateFlow<List<String>> = _djiImportedFiles

    fun scanDjiFlightRecords(treeUri: Uri) {
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                val ctx = GlassFalconApp.ctx
                val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return@withContext emptyList()
                val destDir = File(ctx.getExternalFilesDir(null), "dji_imports").apply { mkdirs() }
                val found = mutableListOf<String>()
                fun walk(dir: DocumentFile) {
                    for (f in dir.listFiles()) {
                        if (f.isDirectory) { walk(f); continue }
                        val name = f.name ?: continue
                        // DJI GO 4 flight records are historically .txt (despite being a binary/
                        // encoded format inside) or .DAT, match both, skip anything else.
                        if (!name.endsWith(".txt", true) && !name.endsWith(".dat", true)) continue
                        runCatching {
                            ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                                File(destDir, name).outputStream().use { out -> input.copyTo(out) }
                            }
                            found += name
                        }
                    }
                }
                runCatching { walk(root) }
                found
            }
            _djiImportedFiles.value = names
            log(if (names.isEmpty()) "DJI import: no flight-record files found in that folder"
                else "DJI import: copied ${names.size} file(s), raw files only, not decoded")
        }
    }
    private var recordingStartMs = 0L
    private var recordingTrackStartIdx = 0
    private var recordingMaxAlt = 0f
    private var recordingMaxSpeed = 0f
    private var recordingDistanceM = 0f
    private var recordingBattStart = 0
    private var recordingWarnings = mutableSetOf<String>()
    private var wasInAirForRecording = false

    /** Starts a record on takeoff, accumulates live stats, saves on touchdown. Hooked off the
     *  same `inAir` bit already used for the co-pilot's "Airborne"/"Touched down" callouts, so
     *  a flight's boundaries are defined identically everywhere in the app. */
    private fun trackFlightRecord(d: DroneState) {
        // Hands-off flight-test capture: arm the DUML dump the instant the aircraft leaves the
        // ground, close it on touchdown. Manual start/stop from the debug screen still works; this
        // only fires the auto path when the user has opted into it.
        if (flightDump.autoDumpOnFlight) {
            if (d.inAir && !wasInAirForDump) flightDump.start("auto: takeoff")
            if (!d.inAir && wasInAirForDump) flightDump.stop()
            wasInAirForDump = d.inAir
        }
        if (d.inAir && !wasInAirForRecording) {
            recordingStartMs = System.currentTimeMillis()
            recordingTrackStartIdx = _track.value.size
            recordingMaxAlt = 0f; recordingMaxSpeed = 0f; recordingDistanceM = 0f
            recordingBattStart = d.battPct
            recordingWarnings = mutableSetOf()
        }
        if (d.inAir) {
            if (d.altRel > recordingMaxAlt) recordingMaxAlt = d.altRel
            if (d.speed > recordingMaxSpeed) recordingMaxSpeed = d.speed
            recordingWarnings.addAll(warnings.value)
        }
        if (!d.inAir && wasInAirForRecording) {
            val flightTrack = _track.value.drop(recordingTrackStartIdx)
            for (i in 1 until flightTrack.size) {
                recordingDistanceM += haversineM(
                    flightTrack[i - 1].lat to flightTrack[i - 1].lon,
                    flightTrack[i].lat to flightTrack[i].lon,
                ).toFloat()
            }
            val record = FlightRecord(
                id = recordingStartMs.toString(),
                startedAtMs = recordingStartMs, endedAtMs = System.currentTimeMillis(),
                maxAltM = recordingMaxAlt, maxSpeedMs = recordingMaxSpeed, distanceM = recordingDistanceM,
                battStartPct = recordingBattStart, battEndPct = d.battPct,
                warningsHit = recordingWarnings.toList(), track = flightTrack,
            )
            // Skip near-zero-length "flights" (a brief inAir flicker isn't a real flight worth
            // cluttering the record list with).
            if (record.durationSec >= 3) {
                FlightRecordStore.save(record)
                _flightRecords.value = FlightRecordStore.loadAll()
                _justLandedRecord.value = record
                log("Flight recorded: ${record.durationSec}s, ${record.maxAltM.toInt()}m max alt")
            }
        }
        wasInAirForRecording = d.inAir
    }

    // Pilot/phone GPS (Android LocationManager, fed from MainActivity): lat, lon, accuracy(m)
    private val _phoneLoc = MutableStateFlow<Triple<Double, Double, Float>?>(null)
    val phoneLoc: StateFlow<Triple<Double, Double, Float>?> = _phoneLoc
    fun updatePhoneLocation(lat: Double, lon: Double, accuracyM: Float) {
        _phoneLoc.value = Triple(lat, lon, accuracyM)
    }

    // ── Link awareness (RC vs RC+drone) + flight warnings ──────────────────────
    @Volatile private var lastDroneMs = 0L          // last time fresh drone OSD (0x43) arrived
    private val _droneLinked = MutableStateFlow(false)
    val droneLinked: StateFlow<Boolean> = _droneLinked     // true = drone telemetry flowing
    private val _signalLostAt = MutableStateFlow<String?>(null)
    val signalLostAt: StateFlow<String?> = _signalLostAt   // clock time drone link dropped
    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings
    // Tap-to-dismiss for the LOW BATTERY (16-30%) banner only, CRITICAL BATTERY (1-15%,
    // "LAND NOW") is the "too close to skip RTH" case and is never gated by this, always shown
    // regardless. Cleared automatically once battery recovers above 30%, so a later dip shows
    // the warning fresh instead of staying silently dismissed for the rest of the flight.
    private val _lowBatteryDismissed = MutableStateFlow(false)
    fun dismissLowBatteryWarning() { _lowBatteryDismissed.value = true }

    // TEMP battery diagnostic: tally cmdSet/cmdId frames, dump every 3s to logcat.
    private val _frameTally = HashMap<Int, Int>()
    @Volatile private var _lastTallyMs = 0L

    // ── Flight limits (height/radius), raw bytes per param hash, keyed the same way the FC
    // itself keys them. See FlyC.ParamHash's doc comment for why GlassFalcon reads these back
    // rather than assuming a byte layout: this is the aircraft's OWN configurable limit, not a
    // client-side cap GlassFalcon enforces, so there's nothing here to bypass, only to raise.
    private val _flightLimits = MutableStateFlow<Map<Long, ByteArray>>(emptyMap())
    val flightLimits: StateFlow<Map<Long, ByteArray>> = _flightLimits

    // FC-declared param bounds (min/max/default/name) keyed by hash, from 0x03/0xf7 param-info
    // responses. The response carries no hash, so we correlate it to the last hash we probed.
    private val _paramInfo = MutableStateFlow<Map<Long, FlyC.ParamInfo>>(emptyMap())
    val paramInfo: StateFlow<Map<Long, FlyC.ParamInfo>> = _paramInfo

    // ── Full FC config via index-based param access (0x03/0xe0..0xe3), the whole 643-param table,
    // no per-param hash needed. Sent as the PC/assistant identity (0x0a) since the FC ignores these
    // under the mobile-app identity. Powers the "FC Tuning" screen (sport speed, wind resistance,
    // limits, etc.). FlyC params live in table 0.
    private val _fcParamCount = MutableStateFlow(0)
    val fcParamCount: StateFlow<Int> = _fcParamCount
    private val _fcParamsByIndex = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val fcParamsByIndex: StateFlow<Map<Int, ByteArray>> = _fcParamsByIndex
    private val _fcParamInfoByIndex = MutableStateFlow<Map<Int, FlyC.ParamInfo>>(emptyMap())
    val fcParamInfoByIndex: StateFlow<Map<Int, FlyC.ParamInfo>> = _fcParamInfoByIndex
    @Volatile private var lastIndexProbed: Int? = null

    private fun sendFcCfg(cmd: Triple<Int, Int, ByteArray>) =
        duml.sendAs(DumlConnection.PC, DumlConnection.FC, cmd.first, cmd.second, cmd.third)

    /** Query how many params the FC's table 0 holds (0xe0). */
    fun probeFcTableSize() = sendFcCfg(FlyC.getTableAttribs(0))
    /** Read a param by index: its info (bounds/type/name) AND its current value. */
    fun readFcParam(index: Int) {
        lastIndexProbed = index
        sendFcCfg(FlyC.getParamInfoByIndex(0, index))
        sendFcCfg(FlyC.readParamByIndex(0, index))
    }
    /** Write a param by index (already-encoded bytes), then re-read to confirm. */
    fun writeFcParam(index: Int, value: ByteArray) {
        sendFcCfg(FlyC.writeParamByIndex(0, index, value))
        log("FC param[$index] ← ${value.joinToString("") { "%02x".format(it) }}")
        viewModelScope.launch { delay(300); sendFcCfg(FlyC.readParamByIndex(0, index)) }
    }
    /** Write a numeric value to a param by index, encoded per its probed ParamInfo type/size. */
    fun writeFcParamNumeric(index: Int, value: Double) {
        val info = _fcParamInfoByIndex.value[index] ?: run { log("FC param[$index]: probe it first"); return }
        writeFcParam(index, FlyC.encodeIndexValue(value.coerceIn(info.min, info.max), info.typeId, info.size))
    }

    /** Probe a param by index, then write a target (clamped to the FC's own bounds). Sequential. */
    private suspend fun probeThenSet(index: Int, target: Double) {
        lastIndexProbed = index
        sendFcCfg(FlyC.getParamInfoByIndex(0, index))
        var info = _fcParamInfoByIndex.value[index]
        var t = 0
        while (info == null && t < 15) { delay(150); info = _fcParamInfoByIndex.value[index]; t++ }
        val i = info ?: run { log("FC param[$index]: no probe response (link may not accept 0x0a config)"); return }
        val v = target.coerceIn(i.min, i.max)
        sendFcCfg(FlyC.writeParamByIndex(0, index, FlyC.encodeIndexValue(v, i.typeId, i.size)))
        android.util.Log.i("GF_FCTUNE", "param[%d] '%s' min=%.2f max=%.2f wrote=%.2f".format(index, i.name, i.min, i.max, v))
        log("Tuned ${i.name} → $v")
        delay(300); sendFcCfg(FlyC.readParamByIndex(0, index))
    }

    // Preset tunes via index params (indices from the wm240 param table). "Faster but stable", 
    // raises the SPEED LIMITS toward DJI's tested max, never the control-loop gains.
    fun sportBoost() = viewModelScope.launch {
        probeThenSet(1257, 50.0)   // mode_sport_cfg.tilt_atti_range 35→50° (max 60) = faster horiz
        probeThenSet(1260, 8.0)    // mode_sport_cfg.vert_vel_up 5→8 m/s
        probeThenSet(1261, -6.0)   // mode_sport_cfg.vert_vel_down -3→-6 m/s
    }.let {}
    fun maxWindResistance() = viewModelScope.launch {
        probeThenSet(628, 100.0)   // control.wind_anti_intensity 60→100 (max)
    }.let {}

    // Mavic 2 ZOOM optical zoom (no-op on the Pro). Continuous: start, then stop.
    fun zoomStart(inDir: Boolean) = duml.sendCam(Camera.opticsZoom(if (inDir) Camera.ZOOM_IN else Camera.ZOOM_OUT))
    fun zoomStop() = duml.sendCam(Camera.opticsZoom(Camera.ZOOM_STOP))

    /** Probe the ENTIRE FC param table by index and log each name/type/bounds/value to logcat
     *  (GF_FCDUMP), for comparing configs across firmware versions / airframes (e.g. a friend's
     *  Zoom on newer, more-locked firmware). Slow: ~60 ms/param, several minutes for the full table. */
    fun dumpFcConfig(maxIndex: Int = 1300) {
        viewModelScope.launch {
            probeFcTableSize()
            android.util.Log.i("GF_FCDUMP", "=== dump start, table size=${_fcParamCount.value} ===")
            for (idx in 0..maxIndex) {
                lastIndexProbed = idx
                sendFcCfg(FlyC.getParamInfoByIndex(0, idx)); delay(55)
                sendFcCfg(FlyC.readParamByIndex(0, idx)); delay(55)
                val info = _fcParamInfoByIndex.value[idx]
                val v = _fcParamsByIndex.value[idx]
                if (info != null && info.name.isNotBlank()) {
                    android.util.Log.i("GF_FCDUMP", "idx=%d %s type=%d size=%d min=%.3f max=%.3f val=%s".format(
                        idx, info.name, info.typeId, info.size, info.min, info.max,
                        v?.joinToString("") { "%02x".format(it) } ?: "?"))
                }
            }
            android.util.Log.i("GF_FCDUMP", "=== dump complete ===")
            log("FC config dump complete, see GF_FCDUMP log")
        }
    }
    @Volatile private var lastProbedHash: Long? = null

    // Wall-clock of the last DUML frame of ANY kind, used by the connect watchdog to
    // detect a transport that opened but carries no real link (the CDC-serial-on-RNDIS
    // failure: connection reports "up" but zero frames ever arrive).
    @Volatile private var lastAnyFrameMs = 0L
    @Volatile private var lastOsdSizeLogMs = 0L

    init {
        loadAiPrefs()
        duml.addListener { frame ->
            val now = System.currentTimeMillis()
            lastAnyFrameMs = now
            decoder.feed(frame)
            flightDump.record(frame.cmdSet, frame.cmdId, frame.raw, outbound = false)
            if (frame.cmdSet == 0x03 && frame.cmdId == 0x43) {
                // If drone telemetry just RESUMED after a long gap, the aircraft was power-cycled
                // or re-linked (e.g. a mid-session battery swap), the FC doesn't persist beginner
                // mode "off" across a reboot, so re-arm the auto-disable to re-send on the fresh
                // boot. Without this, only the very first link of the app session got de-capped.
                if (lastDroneMs != 0L && now - lastDroneMs > 5000L) {
                    beginnerAutoSent = false
                    maxHeightRaised = false
                    maxRadiusRaised = false
                    limitsDisabled = false
                    lowBattMinimized = false
                }
                lastDroneMs = now
                // Expert-default "no handholding" posture, all auto-applied once per link: beginner
                // mode off, height/radius ceilings raised, height/radius limit flags disabled, and
                // the FC's low-battery forced-land/RTH thresholds driven to their minimum so it
                // won't fight the pilot home on a low pack. All gated by autoDisableBeginner.
                maybeAutoDisableBeginner()
                maybeRaiseMaxHeight()
                maybeRaiseMaxRadius()
                maybeDisableFlightLimits()
                maybeMinimizeLowBattery()
                // NOTE: no home-point auto-set, sending setHomePoint (0x03/0x31) re-locks the 30 m
                // cap on the wm240; the aircraft records its own home. The mobile-GPS stream
                // (0x03/0x20, see the send loop above) is what lifts the ceiling.
                // "IMU warming" reported stuck for 20+ minutes on real hardware, one likely
                // cause: controller_state (offset 32, a u32) only gets read when
                // payload.size >= 36 (see Telemetry.kt), otherwise the decoder silently keeps
                // the LAST value forever. If real OSD General frames are consistently shorter
                // than 36 bytes, that byte-length assumption is wrong and the warning is frozen
                // on stale (possibly garbage) data, not reflecting the FC's real current state.
                if (now - lastOsdSizeLogMs > 5000) {
                    lastOsdSizeLogMs = now
                    val dv = decoder.drone.value
                    android.util.Log.i("GF_OSD", "cmd 0x43 len=%d flags(used)=0x%08x".format(
                        frame.payload.size, dv.flags))
                    // Kid-mode diagnosis: the FC never reports the ENFORCED ceiling, but flycState
                    // (Atti vs GPS_Atti) and startFailReason (0x0a "Novice without GPS", 0x17
                    // "restricted area") expose WHY it may still be clamping to 30 m even when the
                    // param table reads permissive (the params say what the FC will ACCEPT, not what
                    // it enforces). Logged so a live flight capture shows the real cause.
                    android.util.Log.i("GF_FLYC", "flycState=0x%02x gpsUsed=%b sig=%d startFail=0x%02x(%s)".format(
                        dv.flycState, dv.gpsUsed, dv.gpsSignalLevel, dv.startFailReason, dv.startFailText ?: "ok"))
                }
            }
            // Diagnostic for camera work-mode set: does the camera even ACK this command at
            // all? An ACK with no visible mode change would point at the wrong payload enum;
            // total silence would point at the wrong cmd_id/cmd_set entirely. cmd_set 0x02
            // confirmed correct for this via kprobe capture 2026-07-03 (see DumlCommands.kt).
            if (frame.cmdSet == 0x02 && frame.cmdId == 0x10 && frame.isAck) {
                log("Camera Work Mode Set ACK: ${frame.payload.joinToString(" ") { "%02x".format(it) }}")
            }
            // Index-based FlyC param responses (2017 protocol) for the FC-config/tuning layer:
            //  0xe0 = table attributes (param count), 0xe1 = param info by index, 0xe2 = read value.
            if (frame.cmdSet == 0x03 && frame.cmdId == 0xe0 && frame.isAck) {
                FlyC.parseTableAttribs(frame.payload)?.let { _fcParamCount.value = it }
            }
            if (frame.cmdSet == 0x03 && frame.cmdId == 0xe1 && frame.isAck) {
                FlyC.parseParamInfo(frame.payload)?.let { info ->
                    lastIndexProbed?.let { idx -> _fcParamInfoByIndex.value = _fcParamInfoByIndex.value + (idx to info) }
                }
            }
            if (frame.cmdSet == 0x03 && frame.cmdId == 0xe2 && frame.isAck) {
                FlyC.parseReadByIndex(frame.payload)?.let { r ->
                    if (r.status == 0) _fcParamsByIndex.value = _fcParamsByIndex.value + (r.index to r.value)
                }
            }
            // Flight-limit param-hash ACKs (both the 0xf8 read and 0xf9 write echo the same
            // status/hash/value framing, see FlyC.ParamHash's doc comment), keep the last
            // known raw bytes per hash so the Device screen can show what the FC actually holds
            // instead of trusting a write blindly.
            if (frame.cmdSet == 0x03 && (frame.cmdId == 0xf8 || frame.cmdId == 0xf9) && frame.isAck) {
                FlyC.parseParamByHash(frame.payload)?.let { r ->
                    _flightLimits.value = _flightLimits.value + (r.hash to r.value)
                    log("Limit hash 0x%08x = %s (status %d)".format(
                        r.hash, r.value.joinToString(" ") { "%02x".format(it) }, r.status))
                    // Full logcat mirror of EVERY flight-limit param ACK (not just novice) so the
                    // exact param holding the 30 m cap is visible live, novice already reads OFF,
                    // so the clamp is some OTHER limit (max_height / a *_limit_enabled flag). u16 LE
                    // decoded inline for the numeric ones.
                    if (frame.cmdId == 0xf8) {
                        val u16 = if (r.value.size >= 2) (r.value[0].toInt() and 0xff) or ((r.value[1].toInt() and 0xff) shl 8) else -1
                        android.util.Log.i("GF_LIMITS", "hash=0x%08x status=%d bytes=%s u16=%d".format(
                            r.hash, r.status, r.value.joinToString("") { "%02x".format(it) }, u16))
                    }
                    // Live diagnostic for the beginner-mode disable (why the 30 m cap survives a
                    // byte-identical-to-GO4 write): log every novice-param ACK to logcat with the
                    // kind (f8 read vs f9 write-echo), status, and value.
                    if (r.hash == FlyC.ParamHash.NOVICE_MODE_ENABLED) {
                        android.util.Log.i("GF_NOVICE", "%s ack status=%d value=%s".format(
                            if (frame.cmdId == 0xf8) "READ" else "WRITE-echo",
                            r.status, r.value.joinToString("") { "%02x".format(it) }))
                        // Only a real READ (0xf8) with status 0 reflects the FC's actual state; the
                        // write-echo (0xf9) just repeats what we sent, so never trust it for confirm.
                        if (frame.cmdId == 0xf8 && r.status == 0 && r.value.isNotEmpty()) {
                            noviceReadValue = r.value[0].toInt() and 0xff
                        }
                    }
                    // Same read-only tracking for MAX_HEIGHT (u16 LE meters) so the auto-raise can
                    // confirm the value the FC actually STORED (its clamp = "what it'll accept"),
                    // not the write-echo.
                    if (r.hash == FlyC.ParamHash.MAX_HEIGHT && frame.cmdId == 0xf8 && r.status == 0 && r.value.size >= 2) {
                        val m = (r.value[0].toInt() and 0xff) or ((r.value[1].toInt() and 0xff) shl 8)
                        maxHeightReadValue = m
                        android.util.Log.i("GF_MAXALT", "READ max_height = ${m}m")
                    }
                    // Same read-only tracking for MAX_RADIUS (u16 LE meters), the auto-raise below
                    // uses it to confirm the FC's stored radius, so distance gets uncapped on
                    // connect the same way altitude does (this was previously missing entirely,
                    // which is why the distance cap kept returning even after beginner mode was off).
                    if (r.hash == FlyC.ParamHash.MAX_RADIUS && frame.cmdId == 0xf8 && r.status == 0 && r.value.size >= 2) {
                        val m = (r.value[0].toInt() and 0xff) or ((r.value[1].toInt() and 0xff) shl 8)
                        maxRadiusReadValue = m
                        android.util.Log.i("GF_MAXRADIUS", "READ max_radius = ${m}m")
                    }
                }
            }
            // Param-INFO responses (0x03/0xf7): the FC's own declared min/max/default + real name.
            // This is the "probe what the aircraft will actually accept" answer, see
            // FlyC.readParamInfoByHash. Keyed by hash so the Device screen shows the true ceiling.
            if (frame.cmdSet == 0x03 && frame.cmdId == 0xf7 && frame.isAck) {
                FlyC.parseParamInfo(frame.payload)?.let { info ->
                    // The response echoes no hash, so key by the hash we most recently probed.
                    lastProbedHash?.let { h -> _paramInfo.value = _paramInfo.value + (h to info) }
                    log("Param info \"%s\": min=%.0f max=%.0f def=%.0f (type %d)".format(
                        info.name, info.min, info.max, info.def, info.typeId))
                }
            }
            // Every camera-module frame we ever receive, ACK or push, on EITHER cmd_set, 0x01
            // ("SPECIAL": capture/record, confirmed working) or 0x02 ("CAMERA": mode/focus/
            // AE-lock/settings/SD-info, confirmed via kprobe capture 2026-07-03, this project
            // sent all of these on 0x01 before that capture, which is why they errored).
            if (frame.cmdSet == 0x01 || frame.cmdSet == 0x02) {
                android.util.Log.i("GF_CAM", "RX cmd_set=0x%02x cmd_id=0x%02x isAck=%b len=%d bytes=%s".format(
                    frame.cmdSet, frame.cmdId, frame.isAck, frame.payload.size,
                    frame.payload.take(24).joinToString(" ") { "%02x".format(it) }))
            }
            if (frame.cmdSet == 0x06 && (frame.cmdId == 0x4c || frame.cmdId == 0x51)) {
                val payloadInt = frame.payload.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
                onRcButtonFrame(frame.cmdId, payloadInt)
            }
            // TEMP diag
            val key = (frame.cmdSet shl 8) or frame.cmdId
            synchronized(_frameTally) {
                _frameTally[key] = (_frameTally[key] ?: 0) + 1
                if (frame.cmdSet == 0x0d) android.util.Log.i("GF_BATT",
                    "0x0d/0x%02x len=%d bytes=%s".format(frame.cmdId, frame.payload.size,
                        frame.payload.take(24).joinToString(" ") { "%02x".format(it) }))
                val now = System.currentTimeMillis()
                if (now - _lastTallyMs > 3000) {
                    _lastTallyMs = now
                    android.util.Log.i("GF_BATT", "frames: " + _frameTally.entries.joinToString(" ") {
                        "%02x/%02x=%d".format(it.key shr 8, it.key and 0xff, it.value) })
                }
            }
        }
        duml.addVideoListener(videoListener)
        // Keep mission engine updated with live drone state, and build the map track.
        viewModelScope.launch {
            decoder.drone.collect { d ->
                mission.attachDroneState(d)
                activeTrack.attachDroneState(d)
                tapFly.attachDroneState(d)
                if (d.hasGpsFix && kotlin.math.abs(d.lat) > 1e-4 && kotlin.math.abs(d.lon) > 1e-4) {
                    val p = d.lat to d.lon
                    _lastKnown.value = p
                    if (_homePoint.value == null) _homePoint.value = p
                    val last = _track.value.lastOrNull()
                    if (last == null || haversineM(last.lat to last.lon, p) > 1.0) {
                        _track.value = (_track.value + TrackPoint(
                            d.lat, d.lon, d.speed, d.altRel, System.currentTimeMillis(), d.battPct,
                        )).takeLast(5000)
                    }
                }
                trackFlightRecord(d)
            }
        }
        // TapFly's obstacle-ahead stop check needs the same live vision/obstacle reading the HUD
        // glow already shows, see ObstacleState.frontClosest.
        viewModelScope.launch { decoder.obstacle.collect { tapFly.attachObstacleState(it) } }
        viewModelScope.launch { monitorLinkAndWarnings() }
        // Mobile-GPS → flight controller push. THE fix for the "stuck at 30 m" cap: a kprobe
        // capture of DJI GO 4 (2026-07-05) showed it streams the phone's GPS to the FC via
        // 0x03/0x20 "Send GPS To Flyc" every few seconds, and GlassFalcon never did, so the FC
        // withheld the full flight envelope and clamped to ~30 m. Send it at 2 Hz whenever we have
        // a link and a phone fix. Uses the phone's own GPS (that's what "mobile GPS" means here).
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                if (_droneLinked.value) {
                    // Prefer the phone's own GPS (true "mobile device" position). Live capture on a
                    // real flight showed this was firing ZERO times, the phone fix was null (no
                    // location permission / provider not started), so the `loc != null` guard
                    // silently skipped it and the aircraft stayed clamped at 30 m. Fall back to the
                    // DRONE's own GPS so the FC always receives a mobile-position frame, and log
                    // each send so it can be verified live via logcat.
                    val phone = _phoneLoc.value
                    val d = decoder.drone.value
                    val (lat, lon, src) = when {
                        phone != null -> Triple(phone.first, phone.second, "phone")
                        d.hasGpsFix -> Triple(d.lat, d.lon, "drone")
                        else -> Triple(Double.NaN, Double.NaN, "none")
                    }
                    if (lat.isFinite() && lon.isFinite()) {
                        duml.send(FlyC.sendGpsToFlyc(lat, lon, System.currentTimeMillis() / 1000))
                        android.util.Log.i("GF_SENDGPS", "sent Send-GPS-to-Flyc (%s) %.6f, %.6f".format(src, lat, lon))
                    } else {
                        android.util.Log.i("GF_SENDGPS", "NO GPS to send (phone null, drone no fix)")
                    }
                }
            }
        }
        // Flight-limit watchdog (diagnostic + self-heal). The one-shot uncap at connect can be
        // rejected by the FC during IMU warmup and then never retried, leaving the aircraft stuck
        // in the 30 m "kid mode" cap for the whole flight. This re-reads the real FC state every
        // 8 s (streaming "Limit hash …" to logcat so the beginner flag + ceilings are always
        // visible for diagnosis) and, if it's still capped, re-asserts the uncap, so a write that
        // the FC refuses early lands as soon as it's ready to accept it. Gated by the same
        // autoDisableBeginner flag, so a deliberate beginner keeps the cap.
        viewModelScope.launch {
            while (isActive) {
                delay(8_000)
                if (_droneLinked.value && autoDisableBeginner) {
                    readFlightLimits()
                    delay(600)   // let the read-backs land before deciding what to re-assert
                    if (noviceReadValue == 1) {
                        android.util.Log.i("GF_NOVICE", "watchdog: still ON, re-asserting beginner OFF")
                        duml.send(FlyC.writeParamByHash(FlyC.ParamHash.NOVICE_MODE_ENABLED, byteArrayOf(0)))
                    }
                    maxHeightReadValue?.let { if (it in 1 until MAX_HEIGHT_TARGET) setMaxHeight(MAX_HEIGHT_TARGET) }
                    maxRadiusReadValue?.let { if (it in 1 until MAX_RADIUS_TARGET) setMaxRadius(MAX_RADIUS_TARGET) }
                    // Keep the expert-default limit flags off (re-assert if the FC shows either on).
                    (_flightLimits.value[FlyC.ParamHash.HEIGHT_LIMIT_ENABLED]?.firstOrNull()?.toInt() ?: 0).let {
                        if (it != 0) duml.send(FlyC.writeParamByHash(FlyC.ParamHash.HEIGHT_LIMIT_ENABLED, byteArrayOf(0)))
                    }
                    (_flightLimits.value[FlyC.ParamHash.RADIUS_LIMIT_ENABLED]?.firstOrNull()?.toInt() ?: 0).let {
                        if (it != 0) duml.send(FlyC.writeParamByHash(FlyC.ParamHash.RADIUS_LIMIT_ENABLED, byteArrayOf(0)))
                    }
                }
            }
        }
        // Weather refresh cadence, adaptive:
        //  - no reading yet:  retry every 15 s until the first fix + reading lands
        //  - airborne:        every 60 s, as fresh as the current-conditions source actually
        //                     updates (~15 min for Open-Meteo), so this is courtesy headroom, not
        //                     waste; NM surface wind/gusts turn fast and this catches each update
        //                     promptly and keeps the reading tied to the drone's live position
        //  - on the ground:   every 5 min, nothing's flying, no need to burn calls
        viewModelScope.launch {
            while (isActive) {
                fetchWeatherOnce()
                val interval = when {
                    _weather.value == null -> 15_000L
                    // Airborne → every 20 s. Open-Meteo's own current-conditions cadence is the
                    // real limit, but polling this often means a fresh gust reading lands within
                    // seconds of it updating, exactly what matters when wind is picking up ahead
                    // of a storm. Well within the keyless quota.
                    decoder.drone.value.inAir -> 20_000L
                    else -> 300_000L
                }
                delay(interval)
            }
        }
        // Real ground truth from the camera's own 0x80 push overrides the optimistic flag
        // capturePhoto()/toggleRecord() set the instant a button is tapped.
        viewModelScope.launch {
            decoder.camera.collect {
                if (it.received) {
                    _isRecording.value = it.recording
                    // Force continuous autofocus once per link so the feed can't come up stuck on a
                    // stale/bad manual-focus position (the "everything's blurry" flight symptom).
                    // The pilot can still switch to MF/AF afterward from the camera tray.
                    if (!afcSentThisLink) {
                        afcSentThisLink = true
                        sendCamLogged(Camera.setFocus(2), "Focus → AFC (auto on link)")
                    }
                }
            }
        }
        startAmbientTempSensor()
    }

    /** 1 Hz watchdog: drone-link freshness, signal-loss timestamp, battery/RTH warnings. */
    private suspend fun monitorLinkAndWarnings() {
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val linked = lastDroneMs != 0L && (now - lastDroneMs) < 3000
            if (_droneLinked.value && !linked) _signalLostAt.value = clockTime(now)  // just dropped
            else if (linked) _signalLostAt.value = null
            _droneLinked.value = linked

            val d = drone.value
            val w = mutableListOf<String>()
            when {
                _app.value.host == "preview" -> w += "◎ PREVIEW MODE, no hardware attached"
                _app.value.connected && lastDroneMs == 0L -> w += "⚠ CONTROLLER ONLY, no drone link"
                _app.value.connected && !linked -> w += "⚠ DRONE SIGNAL LOST" + (_signalLostAt.value?.let { " @ $it" } ?: "")
            }
            // Informational only, GlassFalcon never auto-lands or auto-RTHs. Any forced RTH/land
            // at low battery is the FC's own smart-battery safety (see minimizeLowBatteryActions).
            if (linked && d.battPct in 1..15) w += "🪫 CRITICAL BATTERY ${d.battPct}%, FC MAY FORCE LAND"
            else if (linked && d.battPct in 16..30) {
                if (!_lowBatteryDismissed.value) w += "🔋 LOW BATTERY ${d.battPct}%"
            } else if (linked && d.battPct > 30) {
                _lowBatteryDismissed.value = false  // clear so it shows fresh next time it dips low
            }
            // Confirmed OSD controller_state warning bits, the same status DJI GO 4 surfaces.
            if (linked) {
                if (d.escStall) w += "⚠ ESC STALL, motor blocked"
                if (d.escEmpty) w += "⚠ ESC, insufficient thrust"
                if (d.baroError) w += "⚠ BAROMETER ERROR"
                if (d.ultrasonicError) w += "⚠ DOWNWARD SENSOR ERROR"
                if (d.batteryReqLand) w += "🔋 FC REQUIRING LANDING (low battery)"
                // Tentative wind/attitude warning (undocumented bit 0x200, seen in windy flight).
                if (d.inAir && d.windAngleWarnMaybe) w += "🌬 STRONG WIND / HIGH ANGLE"
                // AirSense (ADS-B / UAT IN): manned aircraft nearby. Receive-only — the FC pushes a
                // 0x11/0x08 warning frame when it flags traffic. Per-target byte layout isn't decoded
                // yet, so this fires on the FC's own warning signal (AirSenseState.maxWarningLevel,
                // floored to 1 on any warning frame). Staleness-gated so a stale frame doesn't stick.
                // Appending to `w` gives both the HUD chip and the edge-triggered voice callout.
                decoder.airSense.value.let { air ->
                    if (air.maxWarningLevel >= 1 && (now - air.lastFrameMs) < 10_000)
                        w += "✈ AIR TRAFFIC NEARBY — AirSense"
                }
                // (No "home not set" warning: the aircraft records its OWN home automatically. Us
                // sending an explicit setHomePoint (0x03/0x31) actually RE-LOCKS the 30 m cap, 
                // live-confirmed 2026-07-05, so we never set it and never warn about it.)
            }
            // Real motor-start-fail reason from the FC itself (offset 38 of OSD General), 
            // this is what DJI GO 4's own pre-flight warnings (including IMU/gyro ones) come
            // from; GlassFalcon never decoded it before, which is why none ever showed.
            if (linked && !d.inAir) {
                // IMU "warming up" warning REMOVED (2026-07-05, per pilot): the FC exposes no real
                // IMU temperature, only the controller_state 0x1000 "imu_preheating" bit, which is
                // demonstrably unreliable (observed stuck 170+ s / 12+ min and reflects nothing on a
                // warm day). With no way to measure actual temperature, the warning was pure noise,
                // so it's gone. Re-add ONLY if a genuine IMU/gyro temperature field is decoded and
                // the warning fires off that real value.
                d.startFailText?.let { w += "⚠ $it, motors won't start" }
            }
            _warnings.value = w

            // Spoken warnings, each routed to its AnnounceCategory so a pilot can mute, say,
            // obstacle chatter while keeping battery/motor callouts. Independent of copilot mode:
            // a controller-flagged fault is flight-safety info, not a co-pilot nicety, so it
            // speaks whenever the voice master switch + that category are on.
            val freshWarnings = w.filter { it !in spokenWarnings }
            freshWarnings.forEach { voice.announceWarning(it) }
            spokenWarnings.clear(); spokenWarnings.addAll(w)

            // Spoken callouts on state transitions (DJI-GO-4-style voice), gated by the voice
            // toggle inside VoiceAnnouncer itself.
            if (linked) {
                if (d.inAir && !voxInAir) voice.announce(AnnounceCategory.TAKEOFF_LANDING, "Taking off", urgent = true)
                else if (!d.inAir && voxInAir) voice.announce(AnnounceCategory.TAKEOFF_LANDING, "Landed")
                voxInAir = d.inAir
                if (d.hasGpsFix && !voxGps) voice.announce(AnnounceCategory.GPS, "G P S ready"); voxGps = d.hasGpsFix
                if (d.batteryReqLand && !voxBattLand) { voice.announce(AnnounceCategory.BATTERY, "Battery low, aircraft will land", urgent = true); voxBattLand = true }
                else if (!d.batteryReqLand) voxBattLand = false
                when {
                    d.battPct in 1..15 && voxBattStage != 2 -> { voice.announce(AnnounceCategory.BATTERY, "Critical battery, land now", urgent = true); voxBattStage = 2 }
                    d.battPct in 16..30 && voxBattStage != 1 -> { voice.announce(AnnounceCategory.BATTERY, "Low battery"); voxBattStage = 1 }
                    d.battPct > 32 -> voxBattStage = 0
                }
                // Obstacle proximity callouts (opt-in category), announce once when something
                // comes inside ~3 m, re-arm only after it clears back past ~5 m so we don't chatter
                // while hovering near a wall.
                val ob = decoder.obstacle.value
                ob.frontClosest?.let { cm ->
                    if (cm < 300 && !voxObstFront) { voice.announce(AnnounceCategory.OBSTACLE, "Obstacle ahead, ${cm / 100} meters"); voxObstFront = true }
                    else if (cm > 500) voxObstFront = false
                }
                ob.backClosest?.let { cm ->
                    if (cm < 300 && !voxObstBack) { voice.announce(AnnounceCategory.OBSTACLE, "Obstacle behind, ${cm / 100} meters"); voxObstBack = true }
                    else if (cm > 500) voxObstBack = false
                }
            } else { voxInAir = false; voxGps = false; voxObstFront = false; voxObstBack = false }

            // Keep camera state (recording/SD status) fresh without the pilot having to open
            // the camera panel first, cheap request, no payload, ~3s cadence.
            if (_app.value.connected && now - lastCameraPollMs > 3000) {
                lastCameraPollMs = now
                requestCameraState()
            }

            // Real airspace + height limit for the top bar, bbox-based ("nearby," not true
            // polygon containment; see fetchNearbyAirspaceInfo's doc comment), throttled so it
            // isn't a network call every second. Runs regardless of copilot mode: this is
            // flight-safety HUD data, not a co-pilot feature, so it shouldn't require opting
            // into voice callouts to see it.
            if (linked && d.hasGpsFix && now - lastAirspaceCheckMs > 20_000) {
                lastAirspaceCheckMs = now
                viewModelScope.launch {
                    val info = fetchNearbyAirspaceInfo(d.lat, d.lon)
                    // The most restrictive nearby ceiling, not just the first result, a pilot
                    // needs the number that actually constrains them when airspaces overlap.
                    _currentAirspace.value = info.filter { it.upperFt != null }.minByOrNull { it.upperFt!! } ?: info.firstOrNull()
                    val names = info.map { it.name }
                    val fresh = names.filter { it !in announcedAirspace }
                    fresh.forEach { voice.announce(AnnounceCategory.AIRSPACE, "Entering $it") }
                    announcedAirspace.clear(); announcedAirspace.addAll(names)
                }
            }

            // ── Co-pilot proactive callouts, fixed, pre-written strings spoken via TTS, no
            // LLM involved. Fires for BOTH copilot modes (RULE_BASED and AI_ASSISTED); only
            // PTT Q&A is mode-gated further down. Off by default, opt in from Settings →
            // Local & LLM. Gated here (not just inside speak()) so a disabled co-pilot does
            // zero extra work too, not merely stays silent.
            if (_app.value.copilotMode != CopilotMode.OFF) {
                // Warnings are now spoken by the categorised VoiceAnnouncer path above (so they
                // work with or without the co-pilot and are individually mutable). The co-pilot
                // block keeps only its own extra transition callouts.
                if (linked) {
                    if (d.inAir && !wasInAir) speak("Airborne")
                    if (!d.inAir && wasInAir) speak("Touched down")
                    wasInAir = d.inAir

                    if (d.hasGpsFix && !hadGpsFix) speak("G P S lock acquired")
                    if (!d.hasGpsFix && hadGpsFix) speak("G P S signal lost")
                    hadGpsFix = d.hasGpsFix
                }
            }
        }
    }

    private fun clockTime(ms: Long) =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))

    // Real airspace the aircraft is currently near (bbox-based, not true polygon containment, 
    // see fetchNearbyAirspaceInfo's doc comment) and its ceiling, for the top bar. Kept separate
    // from the co-pilot's own announcedAirspace/spoken-callout bookkeeping below: this StateFlow
    // updates regardless of copilot mode (it's flight-safety HUD data, not a co-pilot feature),
    // while the voice callout stays opt-in.
    private val _currentAirspace = MutableStateFlow<AirspaceInfo?>(null)
    val currentAirspace: StateFlow<AirspaceInfo?> = _currentAirspace

    // ── AI Co-Pilot (Gemini), proactive voice callouts + push-to-talk Q&A ─────
    private val spokenWarnings = mutableSetOf<String>()
    private var wasInAir = false
    private var hadGpsFix = false
    private val announcedAirspace = mutableSetOf<String>()
    @Volatile private var lastAirspaceCheckMs = 0L
    @Volatile private var lastCameraPollMs = 0L
    private var tts: android.speech.tts.TextToSpeech? = null
    private var speechRecognizer: android.speech.SpeechRecognizer? = null

    private val _coPilotListening = MutableStateFlow(false)
    val coPilotListening: StateFlow<Boolean> = _coPilotListening
    private val _coPilotTranscript = MutableStateFlow("")
    val coPilotTranscript: StateFlow<String> = _coPilotTranscript
    private val _coPilotAnswer = MutableStateFlow("")
    val coPilotAnswer: StateFlow<String> = _coPilotAnswer
    private val _coPilotThinking = MutableStateFlow(false)
    val coPilotThinking: StateFlow<Boolean> = _coPilotThinking

    private fun ttsEngine(): android.speech.tts.TextToSpeech {
        var engine = tts
        if (engine == null) {
            engine = android.speech.tts.TextToSpeech(GlassFalconApp.ctx) {}
            tts = engine
        }
        return engine
    }

    /** Speaks [text] aloud and logs it, every proactive callout and every co-pilot answer
     *  goes through this one path so the debug log always has a full transcript. */
    fun speak(text: String) {
        if (text.isBlank()) return
        ttsEngine().speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, null)
        log("Co-pilot: $text")
    }

    fun setGeminiKey(key: String) {
        GeminiCoPilot.apiKey = key
        aiPrefs.edit().putString("gemini_key", key).apply()
        _app.value = _app.value.copy(geminiApiKey = key)
    }

    fun setCopilotMode(mode: CopilotMode) {
        aiPrefs.edit().putString("copilot_mode", mode.name).apply()
        _app.value = _app.value.copy(copilotMode = mode)
        if (mode == CopilotMode.OFF) { stopCoPilotListening() }
        log("Co-pilot mode → $mode")
    }

    fun setShowCloudAiOptions(on: Boolean) { _app.value = _app.value.copy(showCloudAiOptions = on) }

    fun setCaptureButtonEnabled(on: Boolean) { _app.value = _app.value.copy(captureButtonEnabled = on) }

    // ── On-device Nano availability, checked on demand (Settings screen calls this when the
    // AI Assisted Copilot option is shown, not on every app launch). ──
    private val _nanoStatus = MutableStateFlow<Int?>(null)
    val nanoStatus: StateFlow<Int?> = _nanoStatus
    private val _nanoDownloading = MutableStateFlow(false)
    val nanoDownloading: StateFlow<Boolean> = _nanoDownloading

    fun refreshNanoStatus() {
        viewModelScope.launch {
            _nanoStatus.value = runCatching { NanoCopilot.checkAvailability() }.getOrDefault(com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE)
        }
    }

    fun downloadNanoModel() {
        if (_nanoDownloading.value) return
        _nanoDownloading.value = true
        viewModelScope.launch {
            runCatching { NanoCopilot.download { } }
            _nanoDownloading.value = false
            refreshNanoStatus()
        }
    }

    /** Push-to-talk: call on button-down. Caller (MainActivity) is responsible for having
     *  already obtained RECORD_AUDIO permission, this fails soft (logs, no-op) without it
     *  rather than crashing on a SecurityException. */
    fun startCoPilotListening() {
        // PTT Q&A only exists in AI Assisted mode, plain "Co-Pilot" mode is fixed TTS
        // callouts only, no LLM, by design (see CopilotMode doc comment).
        if (_app.value.copilotMode != CopilotMode.AI_ASSISTED) {
            log("AI Assisted Copilot is off, enable it in Settings → Local & LLM"); return
        }
        if (_coPilotListening.value) return
        val ctx = GlassFalconApp.ctx
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            log("Co-pilot: speech recognition not available on this device")
            return
        }
        val recognizer = speechRecognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(ctx).also { speechRecognizer = it }
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        _coPilotTranscript.value = ""
        _coPilotAnswer.value = ""
        _coPilotListening.value = true
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle) {
                _coPilotListening.value = false
                val text = results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _coPilotTranscript.value = text
                if (text.isNotBlank()) askCoPilot(text)
            }
            override fun onPartialResults(partial: android.os.Bundle) {
                partial.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    _coPilotTranscript.value = it
                }
            }
            override fun onError(error: Int) {
                _coPilotListening.value = false
                log("Co-pilot: didn't catch that (error $error)")
            }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        try {
            recognizer.startListening(intent)
        } catch (e: SecurityException) {
            _coPilotListening.value = false
            log("Co-pilot: microphone permission not granted")
        }
    }

    /** Push-to-talk: call on button-up. */
    fun stopCoPilotListening() {
        speechRecognizer?.stopListening()
        _coPilotListening.value = false
    }

    /** Ask the co-pilot a direct question, grounded in current telemetry/warnings. Used by both
     *  the PTT voice flow and could be wired to a typed-question field the same way.
     *
     *  On-device Nano is the default and only path unless the pilot has both revealed and
     *  configured the (hidden-by-default) cloud fallback, per "no API keys or cloud keys or
     *  any of that" as the default posture, cloud is opt-in, not a requirement. */
    /** The drone's "feelings", a compact natural-language snapshot of everything the copilot
     *  should be aware of: motion, power, GPS quality, attitude, wind/gusts, obstacles, mode, and
     *  any active warnings. Fed to Nano each time it's asked, so its answers are grounded in the
     *  live aircraft state rather than generic knowledge. */
    fun buildFlightContext(): String {
        val d = drone.value
        val home = homePoint.value
        val homeDistM = if (home != null && d.hasGpsFix) haversineM(home, d.lat to d.lon) else null
        val w = _weather.value
        val ob = decoder.obstacle.value
        return buildString {
            append(if (d.inAir) "Airborne. " else "On the ground. ")
            append("Altitude ${"%.0f".format(d.altRel)}m AGL, speed ${"%.1f".format(d.speed)}m/s, climb ${"%.1f".format(-d.vz)}m/s. ")
            append("Battery ${d.battPct}%")
            if (d.battMv > 0) append(" (${"%.1f".format(d.battMv / 1000f)}V)")
            d.battTempC?.let { append(", ${it.toInt()}°C") }
            append(". ")
            append(if (d.hasGpsFix) "GPS locked with ${d.gpsSats} satellites. " else "No GPS fix. ")
            homeDistM?.let { append("Home is ${"%.0f".format(it)}m away. ") }
            append("Heading ${d.yaw.toInt()}°, pitch ${d.pitch.toInt()}°, roll ${d.roll.toInt()}°. ")
            w?.let {
                val compass = arrayOf("N","NE","E","SE","S","SW","W","NW")[
                    ((it.windFromDeg / 45f).toInt() % 8 + 8) % 8]
                append("Wind ${it.windMps.toInt()}m/s from the $compass")
                it.windGustMps?.let { g -> append(" gusting ${g.toInt()}m/s") }
                append(" (aircraft rated to ~10.7m/s). ")
                append("Conditions ${it.conditionText}")
                append(", ${it.tempC.toInt()}°C")
                it.humidityPct?.let { h -> append(", ${h}% humidity") }
                it.cloudPct?.let { c -> append(", ${c}% cloud") }
                it.precipMm?.takeIf { p -> p > 0f }?.let { p -> append(", precip ${"%.1f".format(p)}mm/h") }
                it.visibilityM?.let { v -> append(", visibility ${(v / 1000f).let { km -> "%.1f".format(km) }}km") }
                append(". ")
                if (it.isHazardous) append("WEATHER HAZARD: conditions are marginal/unsafe for a small drone, advise caution or landing. ")
            }
            ob.frontClosest?.let { append("Obstacle ahead at ${it}cm. ") }
            ob.backClosest?.let { append("Obstacle behind at ${it}cm. ") }
            if (warnings.value.isNotEmpty()) append("WARNINGS: ${warnings.value.joinToString("; ")}. ")
        }
    }

    fun askCoPilot(question: String) {
        val mode = _app.value.copilotMode
        if (mode != CopilotMode.AI_ASSISTED && mode != CopilotMode.GEMINI_CLOUD && mode != CopilotMode.HYBRID) {
            log("Copilot Q&A is off, enable AI Assisted, Gemini, or Hybrid in Settings → Local & LLM"); return
        }
        _coPilotThinking.value = true
        viewModelScope.launch {
            val context = buildFlightContext()

            // Hybrid split co-pilot: on-device Nano routes commands INSTANTLY (no network wait for
            // "return home" / "take a photo" / "status"), while cloud Gemini writes the richer spoken
            // answers for everything conversational. Best of both, snappy actions, articulate voice.
            if (mode == CopilotMode.HYBRID) {
                val nano = runCatching {
                    if (NanoCopilot.checkAvailability() == com.google.mlkit.genai.common.FeatureStatus.AVAILABLE)
                        NanoCopilot.ask(question, context) else null
                }.getOrNull()
                if (nano?.action != null) {                 // fast local action, fire now, skip the cloud
                    _coPilotThinking.value = false
                    _coPilotAnswer.value = "▶ ${nano.action.name.replace('_', ' ')}"
                    fireCoPilotAction(nano.action)
                    return@launch
                }
                if (GeminiCoPilot.apiKey.isNotBlank()) {     // conversational, rich cloud answer
                    val g = GeminiCoPilot.ask(question, context)
                    _coPilotThinking.value = false
                    when {
                        g.action != null -> { _coPilotAnswer.value = "▶ ${g.action.name.replace('_', ' ')}"; fireCoPilotAction(g.action) }
                        g.answer != null -> { _coPilotAnswer.value = g.answer; speak(g.answer) }
                        nano?.text?.isNotBlank() == true -> { _coPilotAnswer.value = nano.text; speak(nano.text) } // cloud failed → Nano's words
                        else -> { _coPilotAnswer.value = "Gemini error: ${g.error}"; log("Hybrid cloud error: ${g.error}") }
                    }
                } else {                                     // no key → degrade to Nano's own answer
                    _coPilotThinking.value = false
                    if (nano?.text?.isNotBlank() == true) { _coPilotAnswer.value = nano.text; speak(nano.text) }
                    else _coPilotAnswer.value = "Add a Gemini key in Settings for richer answers, on-device Nano wasn't available here."
                }
                return@launch
            }

            // Gemini-cloud co-pilot, full Gemini as the co-pilot (Q&A + the same safe actions as
            // Nano). Needs the pilot's Gemini key; routes off-device to Google's model.
            if (mode == CopilotMode.GEMINI_CLOUD) {
                if (GeminiCoPilot.apiKey.isBlank()) {
                    _coPilotThinking.value = false
                    _coPilotAnswer.value = "Set a Gemini API key in Settings → Local & LLM to use the Gemini co-pilot."
                    return@launch
                }
                val r = GeminiCoPilot.ask(question, context)
                _coPilotThinking.value = false
                when {
                    r.action != null -> { _coPilotAnswer.value = "▶ ${r.action.name.replace('_', ' ')}"; fireCoPilotAction(r.action) }
                    r.answer != null -> { _coPilotAnswer.value = r.answer; speak(r.answer) }
                    else -> { _coPilotAnswer.value = "Gemini error: ${r.error}"; log("Co-pilot (Gemini) error: ${r.error}") }
                }
                return@launch
            }

            val nanoAvailable = runCatching { NanoCopilot.checkAvailability() }.getOrNull() == com.google.mlkit.genai.common.FeatureStatus.AVAILABLE
            if (nanoAvailable) {
                val result = runCatching { NanoCopilot.ask(question, context) }
                _coPilotThinking.value = false
                result.onSuccess { r ->
                    if (r.action != null) {
                        _coPilotAnswer.value = "▶ ${r.action.name.replace('_', ' ')}"
                        fireCoPilotAction(r.action)
                    } else {
                        _coPilotAnswer.value = r.text
                        speak(r.text)
                    }
                }.onFailure { e ->
                    _coPilotAnswer.value = "Nano error: ${e.message}"
                    log("Co-pilot (Nano) error: ${e.message}")
                }
                return@launch
            }

            // Nano unavailable on this device, only reachable if the pilot explicitly opted
            // into the hidden cloud fallback and set a key; otherwise say so plainly instead
            // of silently failing.
            if (_app.value.showCloudAiOptions && GeminiCoPilot.apiKey.isNotBlank()) {
                val result = GeminiCoPilot.ask(question, context)
                _coPilotThinking.value = false
                if (result.answer != null) {
                    _coPilotAnswer.value = result.answer
                    speak(result.answer)
                } else {
                    _coPilotAnswer.value = "Error: ${result.error}"
                    log("Co-pilot (cloud) error: ${result.error}")
                }
            } else {
                _coPilotThinking.value = false
                _coPilotAnswer.value = "On-device Nano isn't available on this phone. Enable the cloud fallback in Settings → Local & LLM if you want Q&A anyway."
                log("Co-pilot: Nano unavailable, cloud fallback not configured")
            }
        }
    }

    private fun fireCoPilotAction(action: NanoCopilot.DroneAction) {
        speak("On it")
        when (action) {
            NanoCopilot.DroneAction.RETURN_HOME -> sendRth()
            NanoCopilot.DroneAction.TAKE_PHOTO -> capturePhoto()
            NanoCopilot.DroneAction.TOGGLE_RECORD -> toggleRecord()
            // Real toggle, not always-on, mirror the RC-button behaviour so "turn the light off"
            // actually turns it off. (Was hardwired setLed(1).)
            NanoCopilot.DroneAction.TOGGLE_LANDING_LIGHT -> setLed(if (_ledMode.value == 0) 1 else 0)
            // Zoom is a continuous command, pulse it for ~1.2 s per AI "zoom" step (no-op on Pro).
            NanoCopilot.DroneAction.ZOOM_IN -> viewModelScope.launch { zoomStart(true); delay(1200); zoomStop() }
            NanoCopilot.DroneAction.ZOOM_OUT -> viewModelScope.launch { zoomStart(false); delay(1200); zoomStop() }
            NanoCopilot.DroneAction.CENTER_GIMBAL -> gimbalForward()
            NanoCopilot.DroneAction.STATUS_REPORT -> announceStatus()
        }
    }

    private fun haversineM(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(a.first)) * Math.cos(Math.toRadians(b.first)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s))
    }

    fun attachVideoSurface(surface: Surface) { video.start(surface) }
    fun detachVideoSurface() { video.stop() }

    override fun onCleared() {
        super.onCleared()
        duml.removeVideoListener(videoListener)
        duml.stopCapture()
        video.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        sensorManager.unregisterListener(ambientTempListener)
        mediaActionSound.release()
    }

    // ── Raw-stream capture (bench-test evidence for unverified opcodes) ────────

    private var captureStarted = false

    /**
     * One capture file per app session (not per-connect, so a reconnect during a
     * bench test stays in the same file). Written to app-external-files so it can
     * be pulled with a plain `adb pull` (no extra storage permission needed), then
     * copied into captures/ on the host and parsed with tools/usb_capture.py.
     */
    private fun ensureCaptureStarted() {
        if (captureStarted) return
        captureStarted = true
        try {
            val dir = GlassFalconApp.ctx.getExternalFilesDir(null)
            val file = File(dir, "gf_live_cap_${System.currentTimeMillis()}.bin")
            duml.startCapture(file.outputStream().buffered())
            log("Raw capture → ${file.absolutePath}")
        } catch (e: Exception) {
            log("Capture start failed: ${e.message}")
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(host: String, port: Int = 10000) {
        _app.value = _app.value.copy(host = host, port = port)
        log("Connecting to $host:$port …")
        duml.connectTcp(host, port)
        viewModelScope.launch {
            delay(500)
            if (duml.isConnected) {
                _app.value = _app.value.copy(connected = true)
                log("Connected via TCP")
                ensureCaptureStarted()
                startAuthFrameSender()
            } else {
                log("TCP connection failed")
            }
        }
    }

    fun connectUsb(device: UsbDevice, manager: UsbManager) {
        val name = device.productName ?: "device"
        log("USB: opening $name …")
        val ok = duml.connectUsb(device, manager)
        if (ok) {
            _app.value = _app.value.copy(connected = true, host = "usb:${device.deviceName}")
            log("USB connected, DUML channel open")
            ensureCaptureStarted()
            startLinkWatchdog("USB host")
            startAuthFrameSender()
        } else {
            log("USB: no CDC bulk endpoints found on $name")
        }
    }

    /**
     * A transport can open successfully yet carry no real DUML link, this is exactly
     * the failure that grounded the first flight test: the phone came up in USB-host
     * mode and opened a CDC-serial channel over what is not a DUML serial stream, so
     * "connected" went true (arming the takeoff switch) while zero frames ever arrived.
     * Guard against it: if no DUML frame of any kind lands within a few seconds, drop
     * the link instead of leaving a dead-but-armed connection, and point the user at
     * the proven accessory (AOA) path.
     */
    private fun startLinkWatchdog(label: String) {
        val openedAt = System.currentTimeMillis()
        viewModelScope.launch {
            delay(3500)
            if (_app.value.connected && lastAnyFrameMs < openedAt) {
                log("$label link opened but received NO telemetry, dropping.")
                log("Reconnect so the phone enters accessory (AOA) mode.")
                disconnect()
            }
        }
    }

    fun connectAccessory(accessory: UsbAccessory, manager: UsbManager) {
        val name = "${accessory.manufacturer} ${accessory.model}"
        log("AOA: opening $name …")
        val ok = duml.connectAccessory(accessory, manager)
        if (ok) {
            _app.value = _app.value.copy(connected = true, host = "aoa:$name")
            log("AOA connected, 55cc DUML channel open")
            ensureCaptureStarted()
            startLinkWatchdog("AOA")
            startAuthFrameSender()
        } else {
            log("AOA: failed to open accessory $name")
        }
    }

    /** Puts the HUD up with no real transport at all, for visual/layout iteration on the
     *  glass frame, tapes, etc. without needing the RC or drone physically attached. Skips
     *  [ensureCaptureStarted] (there's no real stream worth capturing) and [startLinkWatchdog]
     *  (that watchdog drops the link if no DUML frame arrives within ~3.5s, which is exactly
     *  what would happen here every time, this mode is never supposed to look "linked"). Renders
     *  identically to a real "controller connected, drone off" session (see
     *  monitorLinkAndWarnings's CONTROLLER ONLY branch), since no telemetry frames ever arrive
     *  either way. */
    fun enterPreviewMode() {
        _app.value = _app.value.copy(connected = true, host = "preview")
        // A sample obstacle so the radar + its warning-color bleed into the frame bands are visible
        // in the hardware-free HUD preview. Left/right halves differ so the lean is visible too:
        // front is closer on the LEFT (A1=45 vs A2=150 → leans left); back is closer on the RIGHT
        // (B1=160 vs B2=95 → leans right).
        decoder.setObstacleForPreview(
            ObstacleState(channelA1 = 45, channelA2 = 150, channelB1 = 160, channelB2 = 95, valid = true),
        )
        // Periodically drift the sample readings, a perfectly static preview target can't exercise
        // the radar's "liveliness tracks real sensor change" behavior (ObstacleEdgeGlow now goes
        // calm when readings hold steady and animates more when they actually move). This gives a
        // real, changing signal to react to without any hardware.
        viewModelScope.launch {
            var a1 = 45; var a2 = 150; var b1 = 160; var b2 = 95
            while (_app.value.host == "preview") {
                delay((2000..5000).random().toLong())
                a1 = (a1 + (-30..30).random()).coerceIn(10, 280)
                a2 = (a2 + (-30..30).random()).coerceIn(10, 280)
                b1 = (b1 + (-30..30).random()).coerceIn(10, 280)
                b2 = (b2 + (-30..30).random()).coerceIn(10, 280)
                decoder.setObstacleForPreview(ObstacleState(channelA1 = a1, channelA2 = a2, channelB1 = b1, channelB2 = b2, valid = true))
            }
        }
        log("Preview mode, no hardware attached")
    }

    // ── Dev: synthetic AirSense radar preview ─────────────────────────────────
    // Seeds a few fake ADS-B/UAT targets around the best-known position so the map radar layer +
    // warning-colour steps can be iterated without live traffic (which the drone won't hear indoors,
    // sideways next to a fan). Toggle again to clear. Pure UI seed — never touches the aircraft.
    private var airSensePreviewOn = false
    fun toggleAirSenseRadarPreview(): Boolean {
        airSensePreviewOn = !airSensePreviewOn
        if (!airSensePreviewOn) {
            decoder.setAirSenseForPreview(AirSenseState())
            log("AirSense radar preview OFF")
            return false
        }
        val d = decoder.drone.value
        val base = when {
            d.hasGpsFix && d.lat != 0.0 -> d.lat to d.lon
            _homePoint.value != null    -> _homePoint.value!!
            _phoneLoc.value != null     -> _phoneLoc.value!!.first to _phoneLoc.value!!.second
            else                        -> 35.1929 to -106.3574   // demo fallback (matches capture)
        }
        val cosLat = Math.cos(Math.toRadians(base.first)).coerceAtLeast(0.01)
        fun at(dNorthKm: Double, dEastKm: Double) =
            (base.first + dNorthKm / 111.0) to (base.second + dEastKm / (111.0 * cosLat))
        val (t1Lat, t1Lon) = at(1.2, 0.6)
        val (t2Lat, t2Lon) = at(-0.8, 1.1)
        val (t3Lat, t3Lon) = at(0.3, -0.9)
        val targets = listOf(
            AirSenseTarget("A1B2C3", t1Lat, t1Lon, altM = 900, headingDeg = 210, relAltM = 300, distanceM = 1400, warningLevel = 1, valid = true),
            AirSenseTarget("D4E5F6", t2Lat, t2Lon, altM = 600, headingDeg = 95,  relAltM = 120, distanceM = 1200, warningLevel = 2, valid = true),
            AirSenseTarget("778899", t3Lat, t3Lon, altM = 300, headingDeg = 10,  relAltM = -40, distanceM = 700,  warningLevel = 3, valid = true),
        )
        decoder.setAirSenseForPreview(
            AirSenseState(targets, maxWarningLevel = 3, lastFrameMs = System.currentTimeMillis(),
                lastRawHex = "(synthetic preview)", active = true))
        log("AirSense radar preview ON — 3 synthetic targets")
        return true
    }

    fun disconnect() {
        stopVirtualRc()
        mission.abort()
        activeTrack.stop()
        tapFly.stop()
        timerJob?.cancel(); timerJob = null
        stopAuthFrameSender()
        duml.disconnect()
        beginnerAutoSent = false
        maxHeightRaised = false
        afcSentThisLink = false
        _app.value = _app.value.copy(connected = false, host = "")
        log("Disconnected")
    }

    fun setRelayUrl(url: String) {
        _app.value = _app.value.copy(relayUrl = url, videoUrl = url)
        log("Relay URL → $url")
    }

    fun setClaudeKey(key: String) {
        ClaudeAI.apiKey = key
        aiPrefs.edit().putString("claude_key", key).apply()
        _app.value = _app.value.copy(claudeApiKey = key)
    }

    // ── Virtual RC (20 Hz manual) ─────────────────────────────────────────────

    fun setLeftStick(t: Float, y: Float)  { throttle = t; yaw = y }
    fun setRightStick(r: Float, p: Float) { roll = r; pitch = p }

    fun startVirtualRc() {
        joystickJob?.cancel()
        joystickJob = viewModelScope.launch {
            while (isActive) {
                duml.send(FlyC.joystick(roll, pitch, throttle, yaw))
                delay(50)
            }
        }
        log("Virtual RC started (20 Hz)")
    }

    fun stopVirtualRc() {
        joystickJob?.cancel(); joystickJob = null
        duml.send(FlyC.joystick(0f, 0f, 0f, 0f))
    }

    // ── Mission control ───────────────────────────────────────────────────────

    fun startMission(plan: MissionPlan) {
        stopVirtualRc()
        // ActiveTrack/TapFly send FlyC.joystick() from their own independent loops, same as
        // MissionEngine does, so only one of the three may be driving sticks at a time.
        activeTrack.stop()
        tapFly.stop()
        mission.start(plan)
        log("Mission started: ${plan.name} (${plan.waypoints.size} waypoints, ${plan.estimatedPhotos} photos)")
    }

    fun abortMission() {
        mission.abort()
        log("Mission aborted")
    }

    // ── ActiveTrack / TapFly, see ActiveTrackController.kt / TapFlyController.kt for the
    // actual control loops; these are thin logged wrappers, same pattern as Mission control
    // above. Both stop any running Mission first, MissionEngine, ActiveTrackController, and
    // TapFlyController each send FlyC.joystick() from their own coroutine, so only one may be
    // active at a time or their commands would race. ──

    fun armActiveTrack(view: android.view.TextureView) {
        stopVirtualRc()
        mission.abort()
        tapFly.stop()
        activeTrack.arm(view)
        log("ActiveTrack armed, tap a subject on the video")
    }

    fun selectActiveTrackTarget(xFrac: Float, yFrac: Float) {
        activeTrack.selectAtTap(xFrac, yFrac)
    }

    fun stopActiveTrack() {
        activeTrack.stop()
        log("ActiveTrack stopped")
    }

    fun startTapFly(tapXFrac: Float) {
        stopVirtualRc()
        mission.abort()
        activeTrack.stop()
        tapFly.start(tapXFrac)
        log("TapFly started (tap x=${"%.2f".format(tapXFrac)})")
    }

    fun stopTapFly() {
        tapFly.stop()
        log("TapFly stopped")
    }

    fun planGridSurvey(corners: List<Pair<Double, Double>>, altM: Float = 80f,
                       frontOverlap: Float = 75f, sideOverlap: Float = 70f): MissionPlan {
        val area = SurveyArea(corners, altM, frontOverlap, sideOverlap)
        return MissionPlanner.gridSurvey(area).also {
            log("Grid survey planned: ${it.estimatedPhotos} photos, " +
                "${"%.1f".format(it.estimatedAreaM2 / 10000)} ha, " +
                "GSD ${it.gsdCm} cm/px")
        }
    }

    fun planOrbit(lat: Double, lon: Double, radiusM: Float, altM: Float): MissionPlan {
        return MissionPlanner.orbit(lat, lon, radiusM, altM).also {
            log("Orbit planned: ${it.estimatedPhotos} photos, r=${radiusM}m")
        }
    }

    // ── QuickShot-style patterns, see MissionPlanner's doc comment on why these need no new
    // DUML opcode at all: they're flown through the same virtual-stick mission engine as
    // Grid/Orbit above, just different waypoint shapes. ──

    fun planDronie(subjectLat: Double, subjectLon: Double, startAltM: Float, awayBearingDeg: Float, distanceM: Float, climbM: Float): MissionPlan {
        return MissionPlanner.dronie(subjectLat, subjectLon, startAltM, awayBearingDeg, distanceM, climbM).also {
            log("Dronie planned: ${distanceM}m away, +${climbM}m climb")
        }
    }

    fun planHelix(lat: Double, lon: Double, radiusM: Float, altStartM: Float, altEndM: Float, turns: Float): MissionPlan {
        return MissionPlanner.helix(lat, lon, radiusM, altStartM, altEndM, turns).also {
            log("Helix planned: r=${radiusM}m, ${altStartM}m→${altEndM}m, ${"%.1f".format(turns)} turns")
        }
    }

    fun planRocket(lat: Double, lon: Double, startAltM: Float, climbM: Float): MissionPlan {
        return MissionPlanner.rocket(lat, lon, startAltM, climbM).also {
            log("Rocket planned: +${climbM}m straight up")
        }
    }

    fun planBoomerang(lat: Double, lon: Double, radiusM: Float, altM: Float): MissionPlan {
        return MissionPlanner.boomerang(lat, lon, radiusM, altM).also {
            log("Boomerang planned: r=${radiusM}m loop")
        }
    }

    // ── Claude AI ─────────────────────────────────────────────────────────────

    fun askClaude(userMessage: String) {
        if (ClaudeAI.apiKey.isBlank()) {
            log("Claude API key not set, enter it in Mission → Settings")
            return
        }
        val newHistory = _chatHistory.value + ("user" to userMessage)
        _chatHistory.value = newHistory
        _aiThinking.value = true
        _aiResponse.value = ""

        viewModelScope.launch {
            val sb = StringBuilder()
            try {
                ClaudeAI.chat(newHistory, drone.value) { token ->
                    sb.append(token)
                    _aiResponse.value = sb.toString()
                }.collect {}
                _chatHistory.value = newHistory + ("assistant" to sb.toString())
            } catch (e: Exception) {
                _chatHistory.value = newHistory + ("assistant" to "Error: ${e.message}")
            } finally {
                _aiThinking.value = false
            }
        }
    }

    fun planMissionWithClaude(request: String) {
        if (ClaudeAI.apiKey.isBlank()) {
            log("Claude API key not set")
            return
        }
        log("Claude: planning mission…")
        _aiThinking.value = true
        viewModelScope.launch {
            try {
                val d = drone.value
                val result = ClaudeAI.planMission(request, d.lat, d.lon, d)
                if (result.plan != null) {
                    log("Claude plan: ${result.plan.name}, ${result.plan.waypoints.size} waypoints")
                    if (result.notes.isNotBlank()) log("Claude notes: ${result.notes}")
                    // Expose plan for UI to confirm before executing
                    _pendingPlan.value = result.plan
                } else {
                    log("Claude planning failed: ${result.error ?: result.notes}")
                }
            } finally {
                _aiThinking.value = false
            }
        }
    }

    private val _pendingPlan = MutableStateFlow<MissionPlan?>(null)
    val pendingPlan: StateFlow<MissionPlan?> = _pendingPlan

    fun acceptPendingPlan() {
        _pendingPlan.value?.let { startMission(it) }
        _pendingPlan.value = null
    }

    fun discardPendingPlan() { _pendingPlan.value = null }

    // ── Companion "map-only HUD" broadcast, see CompanionSync.kt ──────────────
    // This device (the one actually flying) broadcasts its live telemetry over the LAN/
    // tether/ad-hoc so a 2nd phone can watch drone position without touching flight controls.
    private var companionServer: CompanionServer? = null
    private val _companionClientCount = MutableStateFlow(0)
    val companionClientCount: StateFlow<Int> = _companionClientCount
    private val _companionBroadcasting = MutableStateFlow(false)
    val companionBroadcasting: StateFlow<Boolean> = _companionBroadcasting

    fun startCompanionBroadcast() {
        if (companionServer != null) return
        val server = CompanionServer(GlassFalconApp.ctx, this)
        companionServer = server
        server.start(viewModelScope)
        viewModelScope.launch { server.clientCount.collect { _companionClientCount.value = it } }
        _companionBroadcasting.value = true
        log("Companion broadcast started")
    }

    fun stopCompanionBroadcast() {
        companionServer?.stop()
        companionServer = null
        _companionBroadcasting.value = false
        _companionClientCount.value = 0
        log("Companion broadcast stopped")
    }

    // ── Offload ───────────────────────────────────────────────────────────────

    fun startWifiOffload(destDir: File, droneIp: String = "192.168.2.1") {
        offload.startWifiOffload(droneIp, destDir)
        log("WiFi offload → ${destDir.absolutePath}")
    }

    fun startAdbOffload(destDir: File) {
        offload.startAdbOffload(destDir)
        log("ADB offload → ${destDir.absolutePath}")
    }

    fun submitToODM(imageDir: File, odmUrl: String) {
        offload.submitToODM(imageDir, odmUrl)
        log("ODM submit: ${imageDir.name} → $odmUrl")
    }

    fun probeSdCardFtp(host: String) = offload.probeFtpAccess(host)

    // ── Gallery (local media browser) ───────────────────────────────────────────
    // Browses the exact same DCIM/GlassFalcon folder the Offload section above downloads into
    // (see OffloadScreen's `defaultDir`), no drone connection needed, this is just a directory
    // listing of files already on the phone. See MediaRepository.kt's doc comment for why a
    // live drone-SD-card gallery is NOT built here: none of the candidate transports for that
    // are confirmed working on this aircraft yet.
    val mediaRepo: MediaRepository = LocalFolderMediaRepository(
        GlassFalconApp.ctx,
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM)
            .let { File(it, "GlassFalcon") },
    )

    fun refreshGallery() = mediaRepo.refresh()

    fun deleteMedia(item: MediaItem) {
        if (mediaRepo.delete(item)) log("Deleted ${item.filename}")
        else log("Failed to delete ${item.filename}")
    }

    fun deleteMedia(itemsToDelete: Collection<MediaItem>) {
        val n = mediaRepo.delete(itemsToDelete)
        log("Deleted $n of ${itemsToDelete.size} item${if (itemsToDelete.size == 1) "" else "s"}")
    }

    // ── Actions (delegated) ───────────────────────────────────────────────────

    // Every outgoing camera command logged with its real cmd_id/payload bytes, paired with the
    // GF_CAM RX logging in init{}, together they show, live, whether the camera module answers
    // any of this at all, which is the actual open question after capture/SD/settings all came
    // back non-functional on real hardware.
    private fun sendCamLogged(cmd: Cmd, label: String) {
        duml.sendCam(cmd)
        android.util.Log.i("GF_CAM", "TX cmd_id=0x%02x bytes=%s (%s)".format(
            cmd.second, cmd.third.joinToString(" ") { "%02x".format(it) }, label))
        log(label)
    }

    // Lifted out of RightCameraPanel's local Compose state so an RC-button toggle (below) and
    // the on-screen toggle share one source of truth instead of two independently-guessed
    // "what mode am I in" flags. mode 0=Photo 1=Video, per Camera.setMode()'s enum.
    private val _cameraMode = MutableStateFlow(0)
    val cameraMode: StateFlow<Int> = _cameraMode
    fun setCameraMode(i: Int)     { _cameraMode.value = i; sendCamLogged(Camera.setMode(i), "Camera mode → $i") }
    fun toggleCameraMode()        { setCameraMode(if (_cameraMode.value == 0) 1 else 0) }
    fun setCameraIso(i: Int)      { sendCamLogged(Camera.setIso(i), "ISO → idx $i") }
    fun setCameraShutter(i: Int)  { sendCamLogged(Camera.setShutter(i), "Shutter → idx $i") }
    fun setCameraEv(i: Int)       { sendCamLogged(Camera.setEv(i), "EV → idx $i") }
    fun setCameraWb(i: Int)       { sendCamLogged(Camera.setWb(i), "WB → idx $i") }
    fun setCameraAperture(i: Int) { sendCamLogged(Camera.setAperture(i), "Aperture → idx $i") }
    fun setCameraFocus(i: Int)    { sendCamLogged(Camera.setFocus(i), "Focus mode → idx $i") }
    // Tap-to-focus at a normalized (0..1) point on the video, see Camera.setFocusRegion.
    fun tapFocus(x: Float, y: Float) { sendCamLogged(Camera.setFocusRegion(x, y), "Tap focus @ %.2f,%.2f".format(x, y)) }
    fun setCameraAeLock(locked: Boolean) { sendCamLogged(Camera.setAeLock(locked), if (locked) "AE locked" else "AE unlocked") }
    fun setAntiFlicker(i: Int)    { sendCamLogged(Camera.setAntiFlicker(i), "Flicker → ${if (i==0) "50" else "60"}Hz") }

    // cmd_id 0x70/0x71 are requests, the camera answers with the 0x80 Camera State Info push
    // that TelemetryDecoder parses into `cameraState` (mode/recording/sd_inserted/sd_error).
    // Polled from monitorLinkAndWarnings() while connected so the panel reflects real state
    // instead of the optimistic "I clicked it so it must be on" flag it used to.
    fun requestCameraState() {
        sendCamLogged(Camera.systemState(), "Camera state requested")
        sendCamLogged(Camera.sdcardInfo(), "SD card info requested")
    }
    fun formatSdCard() { sendCamLogged(Camera.formatSd(), "⚠ SD card format commanded") }

    // EXPERT: push the FC's low-battery auto-RTH (level_2) and forced-auto-land (level_1) voltage
    // thresholds to the FC's OWN declared minimum, the latest possible point the firmware will
    // let those forced actions trigger (near cell cutoff). GlassFalcon never commands the land/RTH
    // itself; this is the only lever on the FC's smart-battery safety. It CANNOT fully disable it
    // (the FC clamps to a minimum that protects the pack), and past that the aircraft WILL drop.
    // Probes each param first so the exact min + encoding come from the FC, never a guess.
    // Once-per-link auto-apply of the low-battery minimize, part of the expert-default posture.
    // Gated by autoDisableBeginner (the expert master flag) so a pilot who wants stock safety can
    // turn it all off in one place.
    @Volatile private var lowBattMinimized = false
    private fun maybeMinimizeLowBattery() {
        if (!autoDisableBeginner || lowBattMinimized) return
        lowBattMinimized = true
        minimizeLowBatteryActions()
    }

    fun minimizeLowBatteryActions() {
        viewModelScope.launch {
            for (hash in listOf(FlyC.ParamHash.LEVEL_2_VOLTAGE, FlyC.ParamHash.LEVEL_1_VOLTAGE)) {
                probeParamRange(hash)
                var info = _paramInfo.value[hash]
                var tries = 0
                while (info == null && tries < 20) { delay(150); info = _paramInfo.value[hash]; tries++ }
                if (info != null) {
                    val bytes = FlyC.encodeParamValue(info.min, info.typeId, info.size)
                    duml.send(FlyC.writeParamByHash(hash, bytes))
                    android.util.Log.i("GF_BATT", "0x%08x '%s' min=%.0f max=%.0f type=%d size=%d → wrote min".format(
                        hash, info.name, info.min, info.max, info.typeId, info.size))
                    log("Low-battery %s → FC min %.0f".format(info.name.ifBlank { "0x%08x".format(hash) }, info.min))
                    delay(300)
                    duml.send(FlyC.readParamByHash(hash))
                } else {
                    log("Low-battery hash 0x%08x, no FC response to probe".format(hash))
                }
                delay(300)
            }
        }
    }

    // Spoken flight-event callouts through the phone speaker (like DJI GO 4's voice). Toggle in
    // Settings. Event transitions are announced from monitorLinkAndWarnings; explicit commands
    // announce here.
    val voice = VoiceAnnouncer(GlassFalconApp.ctx)
    @Volatile private var voxInAir = false
    @Volatile private var voxGps = false
    @Volatile private var voxBattLand = false
    @Volatile private var voxBattStage = 0
    @Volatile private var voxObstFront = false
    @Volatile private var voxObstBack = false

    /** On-demand spoken status read-out, the "list off any useful info, especially warnings" ask.
     *  Composes one utterance from live state (altitude, speed, battery, GPS, home distance, wind)
     *  and every active warning, then speaks it immediately regardless of per-category muting (the
     *  pilot explicitly asked for it). Triggered from the Voice settings screen and by asking the
     *  co-pilot for a "status"/"report". */
    fun announceStatus() {
        val d = drone.value
        val home = homePoint.value
        val w = _weather.value
        val parts = buildList {
            add(if (d.inAir) "Airborne" else "On the ground")
            if (d.inAir) {
                add("altitude ${d.altRel.toInt()} meters")
                add("speed ${d.speed.toInt()} meters per second")
            }
            add("battery ${d.battPct} percent")
            add(if (d.hasGpsFix) "G P S locked, ${d.gpsSats} satellites" else "no G P S fix")
            if (home != null && d.hasGpsFix) add("home ${haversineM(home, d.lat to d.lon).toInt()} meters away")
            w?.let {
                val wind = StringBuilder("wind ${it.windMps.toInt()}")
                it.windGustMps?.let { g -> wind.append(" gusting ${g.toInt()}") }
                wind.append(" meters per second")
                add(wind.toString())
            }
            val warns = warnings.value
            if (warns.isEmpty()) add("no active warnings")
            else {
                add("${warns.size} ${if (warns.size == 1) "warning" else "warnings"}")
                warns.forEach { add(it.filter { c -> c.isLetterOrDigit() || c.isWhitespace() }.trim()) }
            }
        }
        voice.speakStatus(parts.joinToString(". ") + ".")
    }

    fun sendRth()         { duml.send(FlyC.returnHome()); log("RTH commanded"); voice.announce(AnnounceCategory.COMMANDS, "Return to home", urgent = true) }
    // Rc.pushBuzzer() is unverified/experimental (see its doc comment), fired best-effort
    // alongside the flight command so DJI GO 4's audible take-off/land cue can be evaluated
    // on real hardware without gating on it being confirmed first.
    fun autoTakeoff()     { duml.send(FlyC.autoTakeoff()); duml.sendRc(Rc.pushBuzzer()); log("Auto take-off commanded") }
    fun autoLand()        { duml.send(FlyC.autoLand()); duml.sendRc(Rc.pushBuzzer()); log("Auto landing commanded"); voice.announce(AnnounceCategory.COMMANDS, "Landing", urgent = true) }
    fun emergencyStop()   { mission.abort(); duml.send(FlyC.emergencyStop()); log("EMERGENCY STOP") }
    fun startAuthFrameSender() {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            android.util.Log.i("Mavic2Auth", "auth frame sender started at connection time (1 Hz)")
            while (isActive) {
                sendMavic2AuthFrame()
                delay(1000)
            }
        }
    }

    fun stopAuthFrameSender() {
        authJob?.cancel()
        authJob = null
    }

    fun motorArm() {
        android.util.Log.i("FlightViewModel", "motorArm() called")
        duml.send(FlyC.motorCtrl(true))
        armMs = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - armMs) / 1000L
                _flightTimer.value = "%02d:%02d".format(elapsed / 60, elapsed % 60)
                delay(1000)
            }
        }
        log("Motor ARM")
    }
    fun motorDisarm() {
        duml.send(FlyC.motorCtrl(false))
        timerJob?.cancel(); timerJob = null
        log("Motor DISARM")
    }

    /**
     * Send Mavic 2 firmware authentication frame (0x11/0x43).
     * Lifts the 30m altitude cap when sent at ~1 Hz during armed flight.
     * Frame payload: nonce (32) || device_token (16) || HMAC-SHA256(key, nonce||token) (32).
     */
    private fun sendMavic2AuthFrame() {
        try {
            val frame = Mavic2Auth.generateFrame()
            android.util.Log.i("Mavic2Auth", "sending 0x11/0x43 frame (${frame.size} bytes)")
            duml.send(DumlConnection.FC, 0x11, 0x43, frame)
            log("Mavic2Auth: sent 0x11/0x43 frame (${frame.size} bytes)")
        } catch (e: Exception) {
            android.util.Log.e("Mavic2Auth", "ERROR: ${e.message}")
            log("Mavic2Auth ERROR: ${e.message}")
        }
    }

    /** Manual test: send a single 0x11/0x43 authentication frame immediately. */
    fun testMavic2AuthFrame() {
        try {
            val frame = Mavic2Auth.generateFrame()
            duml.send(DumlConnection.FC, 0x11, 0x43, frame)
            log("Mavic2Auth TEST: sent 0x11/0x43 frame (${frame.size} bytes)")
        } catch (e: Exception) {
            log("Mavic2Auth TEST ERROR: ${e.message}")
        }
    }

    // (No setHomePoint wrapper: sending FlyC.setHomePoint / 0x03/0x31 re-locks the 30 m cap on the
    // wm240, the aircraft self-records home. Kept out of the app entirely so nothing can call it.)
    fun setFailsafe(a: Int) { duml.send(FlyC.setRcLostAction(a)); log("Failsafe → ${listOf("Hover","Land","GoHome")[a]}") }
    // Single source of truth for LED state, updated here regardless of which entry point set
    // it (RC-button toggle, FlightScreen's Off/On/Flash buttons, the Nano copilot action), 
    // previously the RC-button toggle tracked its own private on/off bool that could silently
    // drift out of sync with whatever FlightScreen's buttons last actually sent.
    private val _ledMode = MutableStateFlow(0)   // 0=Off, 1=On, 2=Flash
    val ledMode: StateFlow<Int> = _ledMode
    fun setLed(m: Int) {
        duml.send(FlyC.setLed(m)); _ledMode.value = m
        log("LED → ${listOf("Off","On","Flash")[m]}")
    }

    // ── Capture confirmation, a physical RC-button press has no other feedback path, so the
    // pilot needs a cue that isn't buried in the settings tray they probably can't see mid-flight.
    // Three channels, fired together: an on-screen flash (guaranteed, phone-side), a system
    // shutter/record sound (guaranteed, phone-side, same MediaActionSound stock camera apps
    // use), and a best-effort buzz on the RC240 itself via the same experimental Rc.pushBuzzer()
    // already used for takeoff/land, see its doc comment for why the pattern byte is a guess.
    enum class CaptureCue { PHOTO, RECORD_START, RECORD_STOP }
    private val _captureCue = MutableStateFlow<Pair<CaptureCue, Long>?>(null)
    val captureCue: StateFlow<Pair<CaptureCue, Long>?> = _captureCue
    private val mediaActionSound by lazy { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    private fun fireCaptureCue(cue: CaptureCue, soundId: Int, buzzPattern: Int) {
        _captureCue.value = cue to System.currentTimeMillis()
        mediaActionSound.play(soundId)
        duml.sendRc(Rc.pushBuzzer(buzzPattern))
    }

    fun capturePhoto() {
        sendCamLogged(Camera.capturePhoto(), "📷 Photo")
        fireCaptureCue(CaptureCue.PHOTO, MediaActionSound.SHUTTER_CLICK, buzzPattern = 2)
        viewModelScope.launch { delay(400); requestCameraState() }
    }
    // Recording state used to live only as local Compose state inside RightCameraPanel, moved
    // here so anything else (the RC custom-button toggle) can read/flip it too, not just the
    // on-screen record button. Reconciled against the real 0x80 push in init{} below, since the
    // command going out is not proof the camera actually started/stopped recording.
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording
    fun startRecord() {
        sendCamLogged(Camera.startRecord(), "⏺ Record start"); _isRecording.value = true
        fireCaptureCue(CaptureCue.RECORD_START, MediaActionSound.START_VIDEO_RECORDING, buzzPattern = 3)
        viewModelScope.launch { delay(400); requestCameraState() }
    }
    fun stopRecord() {
        sendCamLogged(Camera.stopRecord(), "⏹ Record stop"); _isRecording.value = false
        fireCaptureCue(CaptureCue.RECORD_STOP, MediaActionSound.STOP_VIDEO_RECORDING, buzzPattern = 4)
        viewModelScope.launch { delay(400); requestCameraState() }
    }
    fun toggleRecord()    { if (_isRecording.value) stopRecord() else startRecord() }
    fun gimbalNadir()     { duml.sendGimbal(Gimbal.absAngle(-90f, 0f, 0f, 15)); log("Gimbal → nadir") }
    private var gimbalRecenterJob: kotlinx.coroutines.Job? = null
    // Recenter to level-forward by SLEWING with the confirmed speed command (0x04/0x0c), the same
    // path the drag-to-aim uses, instead of the absolute-angle command (0x04/0x0a), which the
    // Mavic 2 silently ignored (that's why double-tap-to-center did nothing). Closed loop on the
    // live gimbal pitch: drive toward 0° until within ~1.5°, bail if the angle diverges (wrong sign
    // or no telemetry) rather than run to the mechanical stop, and always send a final stop.
    fun gimbalForward() {
        gimbalRecenterJob?.cancel()
        gimbalRecenterJob = viewModelScope.launch {
            log("Gimbal → recenter")
            var prevAbs = Float.MAX_VALUE
            var i = 0
            while (i++ < 80) {
                val pitch = decoder.gimbal.value.pitch
                if (!pitch.isFinite()) break
                val absP = kotlin.math.abs(pitch)
                if (absP < 1.5f) break
                if (absP > prevAbs + 2f) break
                prevAbs = absP
                duml.sendGimbal(Gimbal.speed(pitch = (-pitch * 30f).toInt().coerceIn(-1200, 1200), yaw = 0))
                delay(40)
            }
            duml.sendGimbal(Gimbal.speed(0, 0))
        }
    }
    fun gimbalCalibrate() { duml.sendGimbal(Gimbal.calibrate()); log("Gimbal calibrate") }

    /** Continuous drag-to-aim, called repeatedly (throttled) as the pilot's finger moves across
     *  the video, an absolute angle command each time, not a delta, since that's the only form
     *  Gimbal.absAngle sends; the caller (see MainScreen's video drag handler) is responsible for
     *  accumulating drag distance onto the gimbal's own last-known real angle. A short `timeDs`
     *  (the FC's own "reach this angle over N deciseconds" parameter) keeps motion smooth between
     *  calls instead of snapping, without needing this to fire on every single pixel of movement. */
    fun aimGimbal(pitchDeg: Float, yawDeg: Float) {
        duml.sendGimbal(Gimbal.absAngle(pitchDeg.coerceIn(-90f, 30f), 0f, yawDeg.coerceIn(-20f, 20f), 2))
    }

    /** On-screen gimbal SPEED control (GO 4's 0x04/0x0c). pitch/yaw are signed speeds; (0,0) stops.
     *  Clamped to GO 4's observed ±1800 range. See Gimbal.speed for the captured format. */
    fun gimbalSpeed(pitch: Int, yaw: Int) {
        duml.sendGimbal(Gimbal.speed(pitch.coerceIn(-1800, 1800), yaw.coerceIn(-1800, 1800)))
    }
    fun gimbalStop() { duml.sendGimbal(Gimbal.speed(0, 0)) }
    fun assistantUnlock() { duml.send(FlyC.assistantUnlock(true)); log("Assistant unlock sent") }

    // ── Flight limits (height/radius), expert override ─────────────────────
    private val FLIGHT_LIMIT_HASHES = listOf(
        FlyC.ParamHash.HEIGHT_LIMIT_ENABLED, FlyC.ParamHash.RADIUS_LIMIT_ENABLED,
        FlyC.ParamHash.MAX_HEIGHT, FlyC.ParamHash.MAX_RADIUS, FlyC.ParamHash.MIN_HEIGHT,
        FlyC.ParamHash.NOVICE_MAX_HEIGHT, FlyC.ParamHash.NOVICE_MAX_RADIUS,
        FlyC.ParamHash.NOVICE_MODE_ENABLED,
    )
    fun readFlightLimits() {
        FLIGHT_LIMIT_HASHES.forEach { duml.send(FlyC.readParamByHash(it)) }
        log("→ Flight limits requested")
    }
    // Writes 0 to both "_enabled" flags. Every other "_enabled"-suffixed hash in DJI's own
    // param table is a single byte (0/1), see ParamHash's doc comment, but this exact pair
    // was never read-back-confirmed on real hardware before writing, so this re-reads
    // immediately after so the Device screen shows what the FC actually now holds rather than
    // what GlassFalcon hoped it wrote.
    fun disableFlightLimits() {
        duml.send(FlyC.writeParamByHash(FlyC.ParamHash.HEIGHT_LIMIT_ENABLED, byteArrayOf(0)))
        duml.send(FlyC.writeParamByHash(FlyC.ParamHash.RADIUS_LIMIT_ENABLED, byteArrayOf(0)))
        log("⚠ Height/Radius limit-enabled → 0 (expert override)")
        viewModelScope.launch { delay(400); readFlightLimits() }
    }

    // EXPERT-DEFAULT (professional operators): auto-disable the height/radius limit-enable flags on
    // every link-up, same as the manual "Disable Flight Limits" override but hands-off. Fires once
    // per link, re-armed on a power-cycle gap, gated by the same autoDisableBeginner master flag so
    // turning that off restores ALL default caps. The operator is responsible for airspace rules,
    // VLOS, and RC range at whatever envelope the aircraft will now fly, this only removes the
    // aircraft's OWN configured caps, exactly like DJI GO 4's own unlock. NOTE: this does NOT lift a
    // GEO-zone or no-GPS/ATTI altitude cap (those aren't these params, see the live diagnosis).
    @Volatile private var limitsDisabled = false
    private fun maybeDisableFlightLimits() {
        if (!autoDisableBeginner || limitsDisabled) return
        limitsDisabled = true
        viewModelScope.launch {
            android.util.Log.i("GF_LIMITS", "expert-default: disabling height/radius limit flags on link-up")
            log("Expert mode: flight limits auto-disabled on link-up")
            repeat(6) {
                duml.send(FlyC.writeParamByHash(FlyC.ParamHash.HEIGHT_LIMIT_ENABLED, byteArrayOf(0)))
                duml.send(FlyC.writeParamByHash(FlyC.ParamHash.RADIUS_LIMIT_ENABLED, byteArrayOf(0)))
                delay(1500)
            }
        }
    }

    // Beginner Mode ("kid mode"): DJI's 30 m height/radius training cap for a first flight.
    // Writes the master switch (0xde9b1b7b, captured live off GO 4, see ParamHash) to `on`, then
    // re-reads so the Device screen shows what the FC actually now holds rather than a hopeful write.
    fun setBeginnerMode(on: Boolean) {
        duml.send(FlyC.writeParamByHash(FlyC.ParamHash.NOVICE_MODE_ENABLED, byteArrayOf(if (on) 1 else 0)))
        log(if (on) "Beginner mode → ON" else "Beginner mode → OFF")
        viewModelScope.launch { delay(400); duml.send(FlyC.readParamByHash(FlyC.ParamHash.NOVICE_MODE_ENABLED)) }
    }

    // (Removed 2026-07-05: the entire "auto-set home point" cluster, maybeSetHomePoint /
    // recordHomeNow / _homeRecorded / homeAutoSent / maybePcUnlock. Sending the aircraft an explicit
    // setHomePoint (0x03/0x31) RE-LOCKS the 30 m cap on the wm240; the aircraft records its own home
    // automatically. See glassfalcon-30m-cap notes. forceUnlockPc below stays, it only writes the
    // limit params as PC identity and is safe/useful.)

    /** Re-assert every flight-limit unlock under the PC identity (0x0a), the DJI-Assistant channel.
     *  The FC honours certain config writes ONLY as PC and silently drops them under MOBILE_APP
     *  (0x02); the rooted phone reaches PC via /dev/ttyACM0, we reach it over the RC link with
     *  sendAs(PC,...). This is the software equivalent of the rooted device's unlock, and the prime
     *  suspect for why the 30 m cap cleared on the rooted P8 but not the mobile path. Reads back as
     *  PC too, so GF_LIMITS shows whether the FC accepted it. */
    fun forceUnlockPc() {
        android.util.Log.i("GF_UNLOCK", "force-unlock: writing limits as PC identity (0x0a)")
        log("Force-unlock: re-writing limits as PC (Assistant channel)")
        val h = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(MAX_HEIGHT_TARGET.toShort()).array()
        val r = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(MAX_RADIUS_TARGET.toShort()).array()
        sendFcCfg(FlyC.writeParamByHash(FlyC.ParamHash.NOVICE_MODE_ENABLED, byteArrayOf(0)))
        sendFcCfg(FlyC.writeParamByHash(FlyC.ParamHash.HEIGHT_LIMIT_ENABLED, byteArrayOf(0)))
        sendFcCfg(FlyC.writeParamByHash(FlyC.ParamHash.RADIUS_LIMIT_ENABLED, byteArrayOf(0)))
        sendFcCfg(FlyC.writeParamByHash(FlyC.ParamHash.MAX_HEIGHT, h))
        sendFcCfg(FlyC.writeParamByHash(FlyC.ParamHash.MAX_RADIUS, r))
        viewModelScope.launch {
            delay(500)
            sendFcCfg(FlyC.readParamByHash(FlyC.ParamHash.NOVICE_MODE_ENABLED))
            sendFcCfg(FlyC.readParamByHash(FlyC.ParamHash.MAX_HEIGHT))
        }
    }


    // Auto-disable beginner mode once per link, the first time a real drone frame arrives. The FC
    // doesn't reliably persist "off" across power cycles, so GO 4 re-sends it each session; we do
    // the same so a returning pilot isn't silently re-capped at 30 m. Gated to fire once so it
    // can't fight a deliberate re-enable from the Device screen mid-session. `autoDisableBeginner`
    // lets a genuine beginner keep the cap by turning this off.
    var autoDisableBeginner = true
    @Volatile private var beginnerAutoSent = false
    // Last value from a real 0xf8 READ of the novice param (status 0 only), NOT the 0xf9 write-echo,
    // which just parrots back the byte we sent and would falsely "confirm" a write the FC rejected.
    // Set in the param-ACK handler above; the auto-disable loop trusts only this.
    @Volatile var noviceReadValue: Int? = null
    private fun maybeAutoDisableBeginner() {
        if (!autoDisableBeginner || beginnerAutoSent) return
        beginnerAutoSent = true
        // A byte-identical-to-GO4 write (`0x03/0xf9 de9b1b7b 00`) still left the 30 m cap on across
        // a real flight, so a short burst of retries wasn't enough. The FC almost certainly rejects
        // config-table writes until it finishes initialising (IMU warmup can take 1–2 min), so:
        //  - retry for up to ~3 min, not ~20 s;
        //  - confirm ONLY via a real READ (noviceReadValue==0), never the write-echo;
        //  - log each step to logcat (GF_NOVICE) so a live capture shows exactly what the FC returns.
        viewModelScope.launch {
            val hash = FlyC.ParamHash.NOVICE_MODE_ENABLED
            noviceReadValue = null
            android.util.Log.i("GF_NOVICE", "auto-disable loop start")
            log("Beginner mode auto-disabling on link-up")
            repeat(90) { attempt ->
                if (noviceReadValue == 0) {
                    android.util.Log.i("GF_NOVICE", "confirmed OFF after $attempt tries")
                    log("Beginner mode confirmed OFF")
                    return@launch
                }
                duml.send(FlyC.writeParamByHash(hash, byteArrayOf(0)))
                delay(400)
                duml.send(FlyC.readParamByHash(hash))
                android.util.Log.i("GF_NOVICE", "try $attempt: sent write+read, lastRead=$noviceReadValue")
                delay(1600)
            }
            android.util.Log.i("GF_NOVICE", "GAVE UP, still not confirmed OFF (lastRead=$noviceReadValue)")
            log("Beginner mode: auto-disable unconfirmed after retries")
        }
    }
    // Max altitude ceiling. Value is a u16 LE in meters, confirmed live off GO 4's own slider
    // (50 m → 32 00, 500 m → f4 01). We don't clamp client-side; the FC enforces its own bounds
    // (probe them with probeParamRange). Reads back so the Device screen reflects the FC's truth.
    fun setMaxHeight(meters: Int) {
        val v = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(meters.toShort()).array()
        duml.send(FlyC.writeParamByHash(FlyC.ParamHash.MAX_HEIGHT, v))
        log("Max altitude → ${meters}m")
        viewModelScope.launch { delay(400); duml.send(FlyC.readParamByHash(FlyC.ParamHash.MAX_HEIGHT)) }
    }

    // Auto-raise the height ceiling on connect so the aircraft is climb-ready with no manual step, 
    // same "uncap on connect" intent as the beginner auto-disable (gated by the same flag, so
    // turning that off keeps ALL the default caps). We ask for 500 m; the FC clamps to its OWN
    // declared max and the read-back reveals what it actually stored ("what it'll accept"). Fires
    // once per link, re-armed on a drone power-cycle gap. The limit PERSISTS on the FC (confirmed by
    // capturing GO 4 flying past 30 m while sending zero limit writes, see FINDINGS/memory), so
    // this is idempotent reinforcement, not a per-flight fight.
    private val MAX_HEIGHT_TARGET = 1000
    @Volatile var maxHeightReadValue: Int? = null
    @Volatile private var maxHeightRaised = false
    private fun maybeRaiseMaxHeight() {
        if (!autoDisableBeginner || maxHeightRaised) return
        maxHeightRaised = true
        viewModelScope.launch {
            val v = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(MAX_HEIGHT_TARGET.toShort()).array()
            android.util.Log.i("GF_MAXALT", "auto-raise start (target ${MAX_HEIGHT_TARGET}m)")
            log("Max altitude auto-raising to ${MAX_HEIGHT_TARGET}m on link-up")
            repeat(30) { attempt ->
                val cur = maxHeightReadValue
                if (cur != null && cur >= MAX_HEIGHT_TARGET) {
                    android.util.Log.i("GF_MAXALT", "confirmed ${cur}m after $attempt tries")
                    log("Max altitude confirmed ${cur}m")
                    return@launch
                }
                duml.send(FlyC.writeParamByHash(FlyC.ParamHash.MAX_HEIGHT, v))
                delay(400)
                duml.send(FlyC.readParamByHash(FlyC.ParamHash.MAX_HEIGHT))
                android.util.Log.i("GF_MAXALT", "try $attempt: sent write+read, lastRead=$maxHeightReadValue")
                delay(1600)
            }
            // The FC clamped below our target, that clamp IS its real ceiling. Not a failure.
            android.util.Log.i("GF_MAXALT", "settled at FC ceiling ${maxHeightReadValue}m (clamped below ${MAX_HEIGHT_TARGET})")
            log("Max altitude at FC ceiling: ${maxHeightReadValue}m")
        }
    }

    // Max distance (radius) ceiling, the exact mirror of setMaxHeight. u16 LE meters, same as
    // MAX_HEIGHT's confirmed encoding. The FC enforces its own bounds; the read-back reveals what
    // it actually stored.
    fun setMaxRadius(meters: Int) {
        val v = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(meters.toShort()).array()
        duml.send(FlyC.writeParamByHash(FlyC.ParamHash.MAX_RADIUS, v))
        log("Max distance → ${meters}m")
        viewModelScope.launch { delay(400); duml.send(FlyC.readParamByHash(FlyC.ParamHash.MAX_RADIUS)) }
    }

    // Auto-raise the DISTANCE ceiling on connect, the missing twin of maybeRaiseMaxHeight. Without
    // this, altitude was uncapped on connect but distance never was, so the radius cap kept coming
    // back every session even with beginner mode off. Same once-per-link gating, same flag, same
    // "ask high and let the FC clamp to its own real max" pattern. 8000 m is the top of this
    // airframe's declared max_radius range (see project memory); the FC clamps below if it won't
    // accept that, and the read-back reports the true ceiling.
    private val MAX_RADIUS_TARGET = 8000
    @Volatile var maxRadiusReadValue: Int? = null
    @Volatile private var maxRadiusRaised = false
    private fun maybeRaiseMaxRadius() {
        if (!autoDisableBeginner || maxRadiusRaised) return
        maxRadiusRaised = true
        viewModelScope.launch {
            val v = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(MAX_RADIUS_TARGET.toShort()).array()
            android.util.Log.i("GF_MAXRADIUS", "auto-raise start (target ${MAX_RADIUS_TARGET}m)")
            log("Max distance auto-raising to ${MAX_RADIUS_TARGET}m on link-up")
            repeat(30) { attempt ->
                val cur = maxRadiusReadValue
                if (cur != null && cur >= MAX_RADIUS_TARGET) {
                    android.util.Log.i("GF_MAXRADIUS", "confirmed ${cur}m after $attempt tries")
                    log("Max distance confirmed ${cur}m")
                    return@launch
                }
                duml.send(FlyC.writeParamByHash(FlyC.ParamHash.MAX_RADIUS, v))
                delay(400)
                duml.send(FlyC.readParamByHash(FlyC.ParamHash.MAX_RADIUS))
                android.util.Log.i("GF_MAXRADIUS", "try $attempt: sent write+read, lastRead=$maxRadiusReadValue")
                delay(1600)
            }
            android.util.Log.i("GF_MAXRADIUS", "settled at FC ceiling ${maxRadiusReadValue}m (clamped below ${MAX_RADIUS_TARGET})")
            log("Max distance at FC ceiling: ${maxRadiusReadValue}m")
        }
    }

    // Probe the aircraft for a param's real min/max/default + name (0x03/0xf7). One at a time so
    // the response (which carries no hash) correlates to the right request via lastProbedHash.
    fun probeParamRange(hash: Long) {
        lastProbedHash = hash
        duml.send(FlyC.readParamInfoByHash(hash))
        log("→ Probe range for hash 0x%08x".format(hash))
    }
    // Probe the whole flight-limit set sequentially so each response lands against its own hash.
    fun probeFlightLimitRanges() {
        viewModelScope.launch {
            FLIGHT_LIMIT_HASHES.forEach { h -> probeParamRange(h); delay(250) }
        }
    }

    fun rawSend(dst: Int, cs: Int, ci: Int, payload: ByteArray) {
        duml.send(dst, cs, ci, payload)
        log("→ dst=0x${dst.toString(16)} set=0x${cs.toString(16)} id=0x${ci.toString(16)}")
    }

    fun log(msg: String) {
        val cur = _app.value.log.takeLast(199) + msg
        _app.value = _app.value.copy(log = cur)
    }
}
