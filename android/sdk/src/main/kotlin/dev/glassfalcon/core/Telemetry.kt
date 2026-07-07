// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class DroneState(
    val uptimeMs:    Long  = 0,
    val vx:          Float = 0f,   // m/s N
    val vy:          Float = 0f,   // m/s E
    val vz:          Float = 0f,   // m/s D
    val lat:         Double = 0.0,
    val lon:         Double = 0.0,
    val altRel:      Float = 0f,   // m rel ground
    val flags:       Int   = 0,    // FLYC controller_state bitfield
    val flycState:   Int   = 0,    // FC state machine (lower 7 bits)
    val homeDist:    Float = 0f,   // m
    val roll:        Float = 0f,   // deg
    val pitch:       Float = 0f,
    val yaw:         Float = 0f,
    val battPct:     Int   = 0,
    val battMv:      Int   = 0,
    // GPS satellite count, OSD General offset 36 (confirmed by the reference decoder
    // sdk/python/src/glassfalcon/telemetry.py: `gps_sats = p[36]`).
    val gpsSats:     Int   = 0,
    val connected:   Boolean = false,
    // OSD General offset 38, low 7 bits, "why can't the motors start right now" reason code.
    // 0 = no reason / allow start. See START_FAIL_REASONS below for the full enum (confirmed
    // against dji-dumlv1-flyc.lua's FLYC_OSD_GENERAL_START_FAIL_REASON_ENUM, real firmware
    // strings, not a guess). This is what DJI GO 4's pre-flight warnings are driven by, 
    // GlassFalcon never decoded this field before, which is why no gyro/IMU warning ever showed.
    val startFailReason: Int = 0,
    // Smart battery pack temperature, bytes [17..18] of battery_dynamic_data (0x0d/0x02),
    // 0.1C units, signed. Null until the first battery_dynamic_data frame arrives.
    val battTempC: Float? = null,
) {
    val speed: Float get() = sqrt(vx * vx + vy * vy + vz * vz).let { if (it.isFinite() && it < 200f) it else 0f }
    val hasGpsFix: Boolean get() =
        lat.isFinite() && lon.isFinite() && kotlin.math.abs(lat) > 1e-4 && kotlin.math.abs(lon) > 1e-4
    // controller_state bits (dji-firmware-tools flyc_osd_general)
    val inAir:   Boolean get() = (flags and 0x04) != 0
    val onGround:Boolean get() = (flags and 0x02) != 0
    val motorOn: Boolean get() = (flags and 0x08) != 0
    // Bit 0x1000 of controller_state, nominally "E Still Heating" / IMU preheating. This bit is
    // UNRELIABLE/STUCK: observed frozen high for 12+ minutes on a warm, ready aircraft, and the FC
    // exposes no real IMU temperature to cross-check it against. The IMU "warming up" warning was
    // removed 2026-07-05 because the bit was pure noise, not a usable pre-takeoff gate.
    val imuPreheating: Boolean get() = (flags and 0x1000) != 0
    // Confirmed OSD General controller_state warning bits (dji-dumlv1-flyc.lua flyc_osd_general
    // e_* fields). These are what DJI GO 4's own status warnings are driven by.
    val batteryReqLand: Boolean get() = (flags and 0x400) != 0        // "Landing required, battery low"
    val escStall:       Boolean get() = (flags and 0x8000000) != 0    // ESC reports motor blocked
    val escEmpty:       Boolean get() = (flags and 0x10000000) != 0   // ESC not enough force
    val baroError:      Boolean get() = (flags and 0x4000000) != 0    // barometer error
    val ultrasonicError:Boolean get() = (flags and 0x20000) != 0      // ultrasonic sensor error
    val gpsUsed:        Boolean get() = (flags and 0x8000) != 0       // GPS used as velocity sensor
    val gpsSignalLevel: Int     get() = (flags and 0x3C0000) ushr 18  // 0..~15 sat signal level
    // TENTATIVE: bit 0x200 is undocumented in the community dissector and was seen toggling during
    // a windy flight (live capture 2026-07-05), the most likely home for the strong-wind /
    // excessive-attitude warning. Surfaced as such but flagged unconfirmed until correlated with
    // an on-screen warning.
    val windAngleWarnMaybe: Boolean get() = (flags and 0x200) != 0
    val startFailText: String? get() = START_FAIL_REASONS[startFailReason]
}

/** Real firmware strings (dji-dumlv1-flyc.lua FLYC_OSD_GENERAL_START_FAIL_REASON_ENUM), only
 *  the entries worth surfacing as a pilot-facing warning are kept human-readable here; 0x00
 *  ("None/Allow start") is intentionally absent so a lookup miss means "nothing to report." */
val START_FAIL_REASONS: Map<Int, String> = mapOf(
    0x01 to "Compass error", 0x05 to "IMU needs advanced calibration", 0x06 to "IMU SN error",
    0x07 to "Temperature calibration not ready", 0x08 to "Compass calibration in progress",
    0x09 to "Attitude error", 0x0a to "Novice mode without GPS", 0x15 to "Attitude limit",
    0x17 to "In flight-restricted area", 0x19 to "ESC error", 0x1a to "IMU is initializing",
    0x1d to "IMU calibration in progress", 0x1f to "Gyroscope is stuck", 0x20 to "Accelerometer is stuck",
    0x21 to "Compass is stuck", 0x24 to "Compass reading abnormally high", 0x25 to "Gyro bias too large",
    0x26 to "Accelerometer bias too large", 0x27 to "Compass noise too large", 0x2d to "GPS disconnected",
    0x3d to "IMU not connected", 0x46 to "Gyro abnormal", 0x47 to "Barometer abnormal",
    0x48 to "Compass abnormal", 0x49 to "GPS abnormal", 0x54 to "Gimbal gyro abnormal",
    0x5d to "IMU calibration finished", 0x62 to "Engine start failed",
)

/**
 * Camera State Info push (cmd_set=0x01, cmd_id=0x80), mirrors sdk/python/duml_cmds.py's
 * parse_camera_state, same byte layout (mode/recording/photo_busy at payload[1],
 * sd_inserted/sd_error at payload[2]). This is the only ground truth for "is the SD card
 * even in the drone" and "did the record command actually take", camera_capture_photo() and
 * camera_start_record()/stop_record() have no separate ACK payload worth decoding, so this
 * push (requested via Camera.systemState()) is how the UI tells real state from a command that
 * silently went nowhere.
 */
data class CameraState(
    val mode: Int = 0,
    val recording: Boolean = false,
    val photoBusy: Boolean = false,
    val sdInserted: Boolean = false,
    val sdError: Boolean = false,
    val received: Boolean = false,
)

data class GimbalState(
    val pitch: Float = 0f,
    val roll:  Float = 0f,
    val yaw:   Float = 0f,
    val mode:  String = "Unknown",
)

/**
 * Vision/obstacle distance data (cmd_set=0x03, cmd_id=0x6a), undocumented in community
 * dissectors (which wrongly claim a 1-byte payload). Byte layout confirmed against 647 real
 * captured frames; field meanings confirmed by the 2026-07-05 moving-obstacle test:
 *
 *   byte 0:      status bitfield (dji-firmware-tools flyc_flyc_avoid): bit0 visual_sensor_enable,
 *                bit1 visual_sensor_work, bit2 in_stop, always 0x01 in the stationary bench
 *                samples (enabled, not yet "working"/producing a stop). NOT a channel value.
 *   bytes 1-4:   channel A, two u16 LE sub-values (A1/A2). A1 = FRONT distance (a single,
 *                centered value). A2 = the arc's second vision beam, which is UNUSED hardware on
 *                the wm240 (permanently ~open); there is NO left/right sub-resolution within the arc.
 *   bytes 5-8:   channel B, two u16 LE sub-values (B1/B2). B1 = REAR distance; B2 = unused
 *                second beam (permanently ~open), same as A2.
 *   bytes 9-10:  channel C, one u16 LE value (lateral LEFT sensor), always 0 so far
 *   bytes 11-12: channel D, one u16 LE value (lateral RIGHT sensor), always 0 so far
 *
 * The 4-and-4-and-2-and-2 byte split matches DJI's own stated sensor tiering exactly (confirmed
 * via DJI GO 4's "Obstacle Avoidance Status" screen): forward+backward vision always has richer
 * data, left+right (C/D) only populates in low-speed ActiveTrack, consistent with C/D reading
 * zero in an ordinary (non-ActiveTrack) capture.
 *
 * **A = front, B = rear are CONFIRMED (2026-07-05 moving-obstacle test):** approaching an
 * obstacle from the front moved A off its open value while B stayed open, and vice versa for the
 * rear. The "open/no obstacle in range" reading is ~1000. UI hides any channel sitting at the
 * ~1000 open value instead of drawing it as a real (green) reading, see `ObstacleRadar` in
 * `MainScreen.kt`.
 */
data class ObstacleState(
    val channelA1: Int = 0, val channelA2: Int = 0,
    val channelB1: Int = 0, val channelB2: Int = 0,
    val channelC:  Int = 0, val channelD:  Int = 0,
    // Byte 0 status bits, named by dji-firmware-tools' flyc_flyc_avoid dissector: whether the
    // vision-avoidance system is switched ON (enable) and currently producing readings (work).
    // Previously dropped on the floor, the UI drew distances but couldn't say whether the
    // sensor was even active, so a channel sitting at the "open" value was indistinguishable
    // from a channel that's simply disabled.
    val sensorEnabled: Boolean = false,
    val sensorWorking: Boolean = false,
    val valid: Boolean = false,
) {
    // ~1000 is the sensor's "open"/no-obstacle-in-range reading (confirmed live in flight, see
    // the class doc comment above), filter it out so callers don't mistake "nothing in range"
    // for an actual close reading. Shared here (rather than duplicated per-caller) so
    // MainScreen's ObstacleEdgeGlow and TapFlyController's stop-on-obstacle check can't drift
    // out of sync on what "closest" means.
    private fun isOpen(v: Int) = v <= 0 || v >= 990
    private fun closest(v1: Int, v2: Int): Int? {
        val a = if (isOpen(v1)) null else v1
        val b = if (isOpen(v2)) null else v2
        return listOfNotNull(a, b).minOrNull()
    }
    /** Channel A = front, channel B = rear, confirmed by the 2026-07-05 moving-obstacle test
     *  (see the class doc comment above). */
    val frontClosest: Int? get() = closest(channelA1, channelA2)
    val backClosest: Int? get() = closest(channelB1, channelB2)
    /** Lateral left/right, channels C/D, each a single beam. Per DJI GO 4's own Obstacle
     *  Avoidance Status screen these only carry data in low-speed ActiveTrack, so they read "open"
     *  (null here) most of the time; the radar draws a side mound only when they're actually
     *  reporting something in range. Front/back (A/B) is now confirmed, but which of C/D is
     *  physically left vs. right is still unconfirmed, swap here if a live low-speed ActiveTrack
     *  side-approach test shows it reversed. */
    val leftClosest:  Int? get() = if (isOpen(channelC)) null else channelC
    val rightClosest: Int? get() = if (isOpen(channelD)) null else channelD

    // Left/right lean of a front (or back) obstacle. On the wm240 the second channel per direction
    // (X2) is UNUSED hardware, permanently "open", not a right half, so in practice only X1 ever
    // reads and a single-beam reading means "obstacle in this arc", centered (which is what the body
    // does: it returns 0f). The both-halves branch below is retained only for hardware that does
    // have a real split beam. Returns -1 (hard left) .. +1 (hard right), or null when neither half
    // sees anything in range. When both read, the CLOSER half pulls the lean toward it (difference
    // of the two closeness values).
    private fun lean(left: Int, right: Int): Float? {
        val l = if (isOpen(left)) null else left
        val r = if (isOpen(right)) null else right
        fun close(v: Int) = 1f - (v.coerceIn(0, 300) / 300f)
        return when {
            // Both halves read → real left/right bias (drones that actually have a split beam).
            l != null && r != null -> (close(r) - close(l)).coerceIn(-1f, 1f)
            // Only ONE half reads. On the Mavic 2 the second beam (A2/B2) is unused hardware and is
            // permanently "open", so a single-beam reading means "obstacle in this arc", NOT
            // "obstacle hard to one side", snapping to ±1 drew a dead-ahead wall shoved off-centre,
            // which is exactly why the radar didn't read as directional. Centre it in its arc.
            l != null || r != null -> 0f
            else -> null
        }
    }
    val frontLean: Float? get() = lean(channelA1, channelA2)
    val backLean:  Float? get() = lean(channelB1, channelB2)
}

/**
 * One AirSense (ADS-B / UAT IN) traffic target the aircraft's receiver reported to the app over
 * DUML cmd_set 0x11 ("ADSB"). AirSense is receive-ONLY: the drone hears manned aircraft on
 * 1090ES / 978 UAT and pushes them up so the pilot can stay clear. The drone does NOT transmit
 * ADS-B, so this never shows the drone itself.
 *
 * ⚠️ FIELD LAYOUT UNCONFIRMED. The o-gs dissector's ADSB_UART_CMD_DISSECT table is empty and we
 * have no live capture with real traffic yet (needs an outdoor flight near manned aircraft, or a
 * DJI `.DAT` flight log). So [TelemetryDecoder.decodeADS_BItem] currently returns nothing
 * and the map radar is driven by [TelemetryDecoder.setAirSenseForPreview] synthetic targets; every
 * real 0x11 frame is logged raw (GF_ADSB) so the offsets can be reversed the moment traffic is
 * seen. Same raw-hex-until-confirmed approach as [RcButtonRaw] / [DeviceInfoRaw]. Field set mirrors
 * DJI MSDK's AirSenseAirplaneState (public API, not app code): position, altitude, heading,
 * relative altitude, distance, and a 0..4 warning level.
 */
data class AirSenseTarget(
    val icao: String = "",        // ICAO 24-bit address / flight code, hex, when decoded
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altM: Int = 0,            // target barometric/GPS altitude, metres
    val headingDeg: Int = 0,      // target ground track, degrees true
    val relAltM: Int = 0,         // target altitude relative to the drone (+ = above)
    val distanceM: Int = 0,       // slant/horizontal distance to the drone, metres
    val warningLevel: Int = 0,    // 0 = none .. 4 = most urgent (MSDK AirSenseWarningLevel)
    val valid: Boolean = false,
)

/** Snapshot of AirSense state: the current target list plus the loudest warning and the raw hex
 *  of the last 0x11 frame (for the reverse-engineering panel). [active] is true once any 0x11
 *  frame has been seen this link, so the UI can distinguish "AirSense present but quiet" from
 *  "no AirSense hardware / not licensed on this airframe". [isWarningActive] reflects whether
 *  there is currently a non-zero threat level from any target or explicit warning frame. */
data class AirSenseState(
    val targets: List<AirSenseTarget> = emptyList(),
    val maxWarningLevel: Int = 0,
    val isWarningActive: Boolean = false,
    val lastFrameMs: Long = 0L,
    val lastRawHex: String = "",
    val active: Boolean = false,
)

/**
 * Raw payload of the RC's "Custom Buttons Status" push (cmd_set=0x06, cmd_id=0x4c or 0x51, 
 * dji-dumlv1-proto.lua RC_UART_CMD_TEXT names them "RC Pro Custom Buttons Status Get/Push" and
 * "RC Push To Glass" respectively, no byte-level dissector for either). UNCONFIRMED which byte
 * or bit changes for which physical button (C1/C2/shutter/record), this just exposes the raw
 * hex and which cmd_id it came from so a live press-and-watch session can find the mapping,
 * the same empirical method used for the takeoff opcode, but without needing root/kprobes this
 * time since GlassFalcon already receives these frames natively as their intended recipient.
 * See DeviceScreen's "RC Buttons (raw)" panel.
 */
data class RcButtonRaw(val cmdId: Int = 0, val hex: String = "", val changedAtMs: Long = 0L)

/** Raw (undecoded) response to a device/firmware-info query, see DeviceInfoScreen. This
 *  project's own rule (confirmed opcodes/layouts only, never guessed, see the ADS-B/AeroScope
 *  research in project memory for the same principle applied elsewhere) means the actual
 *  firmware-version STRING inside these payloads isn't parsed: no confirmed byte layout for the
 *  version-inquiry/device-info ACK exists in this project (unlike e.g. FLYC OSD general, which
 *  was captured and confirmed via kprobe). Showing the real hex live is honest; inventing a
 *  parser for a format nobody's confirmed would not be. */
data class DeviceInfoRaw(val label: String, val hex: String, val receivedAtMs: Long)

/**
 * OSD Home Point Data (cmd_set=0x03, cmd_id=0x42), ~2 Hz broadcast.
 * Contains home location, return-to-home status, and limit flags.
 * Layout: 34 bytes (new firmware).
 *
 * Byte Layout:
 *   0-7:   Home Longitude (f64 LE, radians) → convert to degrees
 *   8-15:  Home Latitude (f64 LE, radians) → convert to degrees
 *   16-19: Home Altitude (f32 LE, meters)
 *   20-21: Home State Flags (u16 LE, bitfield)
 *   22-23: Go-Home Height (u16 LE, meters)
 *
 * Home State Flags (offset 20-21):
 *   0x01: home_point_set
 *   0x02: go_home_mode (RTH active)
 *   0x10: reach_limit_distance
 *   0x20: reach_limit_height
 *   0x800: beginner_mode
 */
data class HomeData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altM: Float = 0f,
    val homePointSet: Boolean = false,
    val goHomeMode: Boolean = false,
    val goHomeHeight: Int = 0,
    val beginnerMode: Boolean = false,
    val atDistLimit: Boolean = false,
    val atHeightLimit: Boolean = false,
    val valid: Boolean = false,
)

/**
 * RC (Remote Controller) Battery Status (cmd_set=0x06, cmd_id=0x57), ~2 Hz broadcast.
 * Controller battery: percentage and voltage.
 *
 * Byte Layout (TBD from live capture):
 *   - Battery percentage (%)
 *   - Battery voltage (mV)
 *   - Battery current (mA, optional)
 */
data class RCBatteryData(
    val batteryPercent: Int = 0,
    val batteryMv: Int = 0,
    val batteryCurrent: Int = 0,
    val valid: Boolean = false,
)

/**
 * RC GPS Location (cmd_set=0x06, cmd_id=0x57), ~2 Hz broadcast.
 * Remote controller GPS position and signal quality.
 *
 * Byte Layout (TBD from live capture):
 *   - RC GPS latitude/longitude (degrees)
 *   - GPS satellite count
 *   - Accuracy (meters)
 */
data class RCLocationData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val gpsSats: Int = 0,
    val accuracy: Float = 0f,
    val valid: Boolean = false,
)

/**
 * Flight Limit State (cmd_set=0x03, cmd_id=0xXX), ~2 Hz broadcast.
 * Current altitude and distance ceiling limits.
 *
 * Byte Layout (TBD from firmware analysis):
 *   - Current height limit (meters)
 *   - Current distance limit (meters)
 *   - Height limit reason
 *   - Distance limit reason
 */
data class FlightLimitData(
    val heightLimit: Int = 0,
    val distanceLimit: Int = 0,
    val heightLimitReason: String = "",
    val distanceLimitReason: String = "",
    val valid: Boolean = false,
)

/**
 * Smart Battery Cell Voltages (cmd_set=0x0d, cmd_id=0xXX), ~2 Hz broadcast.
 * Individual cell voltage readings for battery health monitoring.
 *
 * Byte Layout (TBD from live capture):
 *   - Cell count
 *   - Individual cell voltages (mV)
 *   - Min/max cell voltage
 */
data class BatteryCellData(
    val cellCount: Int = 0,
    val cellVoltages: List<Float> = emptyList(),
    val minCellV: Float = 0f,
    val maxCellV: Float = 0f,
    val valid: Boolean = false,
)

/**
 * RC Custom Button State (cmd_set=0x06, cmd_id=0x4c or 0x51).
 * Decoded button press state (currently captured as raw hex via RcButtonRaw).
 * Byte mapping and layout unconfirmed.
 */
data class RCButtonState(
    val c1Pressed: Boolean = false,
    val c2Pressed: Boolean = false,
    val shutterHalf: Boolean = false,
    val shutterFull: Boolean = false,
    val recordToggle: Boolean = false,
    val dialValue: Int = 0,
    val valid: Boolean = false,
)

class TelemetryDecoder {
    private val _drone    = MutableStateFlow(DroneState())
    private val _gimbal   = MutableStateFlow(GimbalState())
    private val _obstacle = MutableStateFlow(ObstacleState())
    private val _airSense = MutableStateFlow(AirSenseState())
    private val _camera   = MutableStateFlow(CameraState())
    // Short history (not just the latest value) so a press-and-watch session shows a clean
    // before/after diff instead of one value flickering in place, much easier to correlate
    // "I just pressed C1" with "this byte changed" when they're on screen at the same time.
    private val _rcButtonHistory = MutableStateFlow<List<RcButtonRaw>>(emptyList())
    private val _deviceInfoRaw = MutableStateFlow<Map<String, DeviceInfoRaw>>(emptyMap())
    private val _homeData = MutableStateFlow(HomeData())
    private val _rcBattery = MutableStateFlow(RCBatteryData())
    private val _rcLocation = MutableStateFlow(RCLocationData())
    private val _flightLimits = MutableStateFlow(FlightLimitData())
    private val _batteryCells = MutableStateFlow(BatteryCellData())
    private val _rcButtonState = MutableStateFlow(RCButtonState())

    val drone:    StateFlow<DroneState>    = _drone
    val gimbal:   StateFlow<GimbalState>   = _gimbal
    val obstacle: StateFlow<ObstacleState> = _obstacle
    val airSense: StateFlow<AirSenseState> = _airSense
    /** Inject an obstacle reading directly, used by the hardware-free HUD Preview so the radar
     *  and its warning-color bleed are visible/tunable without a real aircraft in range. */
    fun setObstacleForPreview(o: ObstacleState) { _obstacle.value = o }
    /** Push a synthetic AirSense snapshot for radar UI iteration without live traffic. */
    fun setAirSenseForPreview(s: AirSenseState) { _airSense.value = s }
    val camera:   StateFlow<CameraState>   = _camera
    val rcButtonHistory: StateFlow<List<RcButtonRaw>> = _rcButtonHistory
    val deviceInfoRaw: StateFlow<Map<String, DeviceInfoRaw>> = _deviceInfoRaw
    val homeData: StateFlow<HomeData> = _homeData
    val rcBattery: StateFlow<RCBatteryData> = _rcBattery
    val rcLocation: StateFlow<RCLocationData> = _rcLocation
    val flightLimits: StateFlow<FlightLimitData> = _flightLimits
    val batteryCells: StateFlow<BatteryCellData> = _batteryCells
    val rcButtonState: StateFlow<RCButtonState> = _rcButtonState

    // TEMP incoming-frame logger (remove after decode): logs each frame whose payload CHANGES, so
    // an event-driven frame like a strong-wind / excessive-angle warning stands out when it
    // appears. Skips the known high-rate telemetry (OSD, battery, obstacle, gimbal, RC buttons)
    // that changes every packet and would drown the log. Correlate timestamps with the 1 fps
    // screenshots to pin which frame carries the warning.
    private val rxLog = HashMap<Int, String>()
    private fun logRxFrame(frame: DumlFrame) {
        // OSD general: log ONLY the flags/status tail (offset 30 onward) when it changes, that's
        // where a wind/attitude warning bit would live, ignoring the attitude that ticks every
        // frame. This is the most likely home for the "strong wind / excessive angle" warning.
        if (frame.cmdSet == 0x03 && frame.cmdId == 0x43 && frame.payload.size >= 42) {
            val tail = frame.payload.copyOfRange(30, minOf(frame.payload.size, 60)).joinToString("") { "%02x".format(it) }
            if (rxLog[-1] != tail) {
                rxLog[-1] = tail
                android.util.Log.i("GF_RX", "0x03/0x43-flags@30 %s".format(tail))
            }
            return
        }
        val noisy = (frame.cmdSet == 0x0d) ||                           // battery
            (frame.cmdSet == 0x03 && frame.cmdId == 0x6a) ||           // obstacle
            (frame.cmdSet == 0x04 && frame.cmdId == 0x05) ||           // gimbal params
            (frame.cmdSet == 0x06)                                      // RC buttons/status (re-muted)
        if (noisy) return
        val key = (frame.cmdSet shl 8) or frame.cmdId
        val hex = frame.payload.joinToString("") { "%02x".format(it) }
        if (rxLog[key] != hex) {
            rxLog[key] = hex
            android.util.Log.i("GF_RX", "0x%02x/0x%02x %s".format(frame.cmdSet, frame.cmdId, hex))
        }
    }

    fun feed(frame: DumlFrame) {
        logRxFrame(frame)
        val buf = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        when {
            frame.cmdSet == 0x03 && frame.cmdId == 0x43 && frame.payload.size >= 30 -> {
                // FLYC OSD general data (real Mavic 2 layout, per usb_capture.py + dji-firmware-tools):
                //  0:lon(double,rad) 8:lat(double,rad) 16:height(i16,0.1m)
                //  18:vgx 20:vgy 22:vgz (i16,0.1m/s) 24:pitch 26:roll 28:yaw (i16,0.1°)
                //  30:ctrl_info(u8, flyc_state=0x7F/no_rc=0x80) 31:latest_cmd(u8) 32:controller_state(u32)
                // (matches docs/protocol.md, docs/duml-capture.md, and the reference Python decoder
                // sdk/python/src/glassfalcon/telemetry.py, this was previously off by one byte, so
                // inAir/onGround/motorOn never reflected reality.)
                val bb = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                val lonRad = bb.getDouble(0)
                val latRad = bb.getDouble(8)
                val height = bb.getShort(16) * 0.1f
                val vgx = bb.getShort(18) * 0.1f
                val vgy = bb.getShort(20) * 0.1f
                val vgz = bb.getShort(22) * 0.1f
                val pitch = bb.getShort(24) * 0.1f
                val roll  = bb.getShort(26) * 0.1f
                val yaw   = bb.getShort(28) * 0.1f
                val flycState = if (frame.payload.size > 30) frame.payload[30].toInt() and 0x7f else 0
                val ctrlState = if (frame.payload.size >= 36) bb.getInt(32) else _drone.value.flags
                // Battery % rides at byte 40 of OSD general (flyc_osd_general_batt_remain;
                // confirmed by tools/usb_capture.py pl[40] and the dji-firmware-tools dissector).
                // 0 = smart battery not yet synced, keep the last good value rather than flashing 0%.
                val battPct = if (frame.payload.size > 40) frame.payload[40].toInt() and 0xff else 0
                // Motor-start-fail reason, offset 38 low 7 bits (dji-dumlv1-flyc.lua:
                // flyc_osd_general_start_fail_reason, mask 0x7f), this is the field DJI GO 4's
                // pre-flight warnings (including IMU/gyro ones) are actually driven by.
                val startFailReason = if (frame.payload.size > 38) frame.payload[38].toInt() and 0x7f else 0
                // GPS satellite count, offset 36 (reference decoder telemetry.py: gps_sats = p[36]).
                val gpsSats = if (frame.payload.size > 36) frame.payload[36].toInt() and 0xff else 0
                val lat = Math.toDegrees(latRad)
                val lon = Math.toDegrees(lonRad)
                _drone.value = _drone.value.copy(
                    vx = vgx, vy = vgy, vz = vgz,
                    lat = if (latRad.isFinite()) lat else 0.0,
                    lon = if (lonRad.isFinite()) lon else 0.0,
                    altRel = if (height.isFinite()) height else 0f,
                    flags = ctrlState, flycState = flycState,
                    roll = roll, pitch = pitch, yaw = yaw,
                    battPct = if (battPct in 1..100) battPct else _drone.value.battPct,
                    gpsSats = if (gpsSats in 0..40) gpsSats else _drone.value.gpsSats,
                    startFailReason = startFailReason,
                    connected = true,
                )
            }
            frame.cmdSet == 0x0d && frame.cmdId == 0x02 && frame.payload.size > 20 -> {
                // Smart battery dynamic data (dji-dumlv1-proto battery_dynamic_data), the
                // REAL state of charge. OSD byte 40 stays 0 on the ground (activation gate),
                // but the smart battery broadcasts this at ~2 Hz regardless.
                //  [0] index  [1..4] pack voltage u32 mV  [5..8] current i32 mA
                //  [9..12] full cap  [13..16] remain cap  [17..18] temp(0.1°C)
                //  [19] cell count  [20] state-of-charge %   (verified: 0x39=57% == 1653/2905mAh)
                val mv   = buf.getInt(1)
                val soc  = frame.payload[20].toInt() and 0xff
                val temp = buf.getShort(17) * 0.1f
                if (soc in 1..100) _drone.value = _drone.value.copy(
                    battPct = soc,
                    battMv  = if (mv in 1..30000) mv else _drone.value.battMv,
                    battTempC = if (temp in -40f..85f) temp else _drone.value.battTempC,
                )
            }
            // REMOVED: a 0x03/0x0d,0x10 "attitude" decode that read 3 floats into roll/pitch/yaw.
            // The dji-firmware-tools dissector names these 0x0d="Statistical Info Save" and
            // 0x10="A2 Push Common", NOT attitude, so those floats were garbage overwriting the
            // real aircraft attitude and making the HUD attitude indicator wrong/erratic. Aircraft
            // attitude comes ONLY from the confirmed FLYC OSD General (0x03/0x43) above.
            frame.cmdSet == 0x03 && frame.cmdId in intArrayOf(0x35, 0x36) && frame.payload.size >= 4 -> {
                val pct = frame.payload[0].toInt() and 0xff
                val mv  = if (frame.payload.size >= 4)
                    ByteBuffer.wrap(frame.payload, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
                    else 0
                if (pct in 1..100) _drone.value = _drone.value.copy(battPct = pct, battMv = mv)
            }
            frame.cmdSet == 0x04 && frame.cmdId == 0x05 && frame.payload.size >= 6 -> {
                val pitch = ByteBuffer.wrap(frame.payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.1f
                val roll  = ByteBuffer.wrap(frame.payload, 2, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.1f
                val yaw   = ByteBuffer.wrap(frame.payload, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.1f
                val modeByte = if (frame.payload.size >= 7) frame.payload[6].toInt() and 0xff else 0
                val mode = when (modeByte and 0xc0) {
                    0x00 -> "YawNoFollow"; 0x40 -> "FPV"; 0x80 -> "YawFollow"; else -> "Calib"
                }
                _gimbal.value = GimbalState(pitch, roll, yaw, mode)
            }
            frame.cmdSet == 0x03 && frame.cmdId == 0x6a && frame.payload.size >= 13 -> {
                // LAYOUT CONFIRMED FROM LIVE DATA (2026-07-05, Pixel 10, moving-obstacle test):
                //   off1 (byte 1-2)  = FRONT distance, read a fixed Jeep at 25cm whether it was to
                //                      the left OR the right, so it's a single forward distance, NOT
                //                      a directional left/right beam.
                //   off5 (byte 5-6)  = REAR distance, swept 25..993 as objects passed behind.
                //   off3, off7       = sit at 1000 (open) permanently on this airframe, a second
                //                      sub-beam per channel that this hardware never populates.
                //   off9, off11 (C/D)= lateral left/right, always 0 outside low-speed ActiveTrack.
                // So there is NO usable horizontal (left/right) obstacle position in normal flight;
                // front and back are each a single centered distance. channelA=front, channelB=rear.
                val status = frame.payload[0].toInt() and 0xff
                _obstacle.value = ObstacleState(
                    channelA1 = buf.getShort(1).toInt() and 0xffff,   // FRONT distance (off1)
                    channelA2 = buf.getShort(3).toInt() and 0xffff,   // front 2nd beam (open on wm240)
                    channelB1 = buf.getShort(5).toInt() and 0xffff,   // REAR distance (off5)
                    channelB2 = buf.getShort(7).toInt() and 0xffff,   // rear 2nd beam (open on wm240)
                    channelC  = buf.getShort(9).toInt() and 0xffff,
                    channelD  = buf.getShort(11).toInt() and 0xffff,
                    sensorEnabled = (status and 0x01) != 0,
                    sensorWorking = (status and 0x02) != 0,
                    valid = true,
                ).also { obs ->
                    // TEMP live-verification log (remove after front/back + left/right confirmed):
                    // dump the RAW u16 at each byte offset so ground-truth ("Jeep in front-right")
                    // can be matched to the exact channel/sub-beam that moves off the ~1000 open
                    // value. Only logged when SOMETHING is actually in range, to keep it quiet.
                    val raw = intArrayOf(
                        buf.getShort(1).toInt() and 0xffff, buf.getShort(3).toInt() and 0xffff,
                        buf.getShort(5).toInt() and 0xffff, buf.getShort(7).toInt() and 0xffff,
                        buf.getShort(9).toInt() and 0xffff, buf.getShort(11).toInt() and 0xffff,
                    )
                    if (raw.any { it in 1..989 }) {
                        android.util.Log.i("GF_OBSTACLE",
                            "off1=%d off3=%d off5=%d off7=%d off9=%d off11=%d  status=0x%02x  hex=%s".format(
                                raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], status,
                                frame.payload.joinToString(" ") { "%02x".format(it) }))
                    }
                }
            }
            (frame.cmdSet == 0x01 || frame.cmdSet == 0x02) && frame.cmdId == 0x80 && frame.payload.isNotEmpty() -> {
                // See CameraState doc comment, matches sdk/python/duml_cmds.py's parse_camera_state.
                // Accepting either cmd_set: the 2026-07-03 kprobe capture confirmed camera admin
                // REQUESTS (mode/focus/AE-lock/system-state) actually go out on 0x02, not 0x01
                // as this project assumed before, whether the resulting 0x80 PUSH itself stays
                // on 0x01 or also moved to 0x02 is unconfirmed, so both are accepted here.
                val b1 = if (frame.payload.size >= 2) frame.payload[1].toInt() and 0xff else 0
                val b2 = if (frame.payload.size >= 3) frame.payload[2].toInt() and 0xff else 0
                _camera.value = CameraState(
                    mode = frame.payload[0].toInt() and 0xff,
                    recording = (b1 and 0x01) != 0,
                    photoBusy = (b1 and 0x02) != 0,
                    sdInserted = (b2 and 0x01) != 0,
                    sdError = (b2 and 0x02) != 0,
                    received = true,
                )
            }
            frame.cmdSet == 0x06 && (frame.cmdId == 0x4c || frame.cmdId == 0x51) -> {
                val hex = frame.payload.joinToString(" ") { "%02x".format(it) }
                if (hex != _rcButtonHistory.value.lastOrNull()?.hex) {
                    _rcButtonHistory.value = (_rcButtonHistory.value + RcButtonRaw(frame.cmdId, hex, System.currentTimeMillis())).takeLast(20)
                }
            }
            frame.cmdSet == 0x03 && frame.cmdId == 0x42 && frame.payload.size >= 22 -> {
                // OSD Home Point Data (cmd_set=0x03, cmd_id=0x42), ~2 Hz broadcast.
                // Contains home location, RTH status, and limit flags.
                // Byte layout: 0-7(lon), 8-15(lat), 16-19(alt), 20-21(flags), 22-23(go-home height)
                val bb = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                val lonRad = bb.getDouble(0)
                val latRad = bb.getDouble(8)
                val altM = if (frame.payload.size >= 20) bb.getFloat(16) else 0f
                val flags = if (frame.payload.size >= 22) bb.getShort(20).toInt() and 0xffff else 0
                val goHomeHeight = if (frame.payload.size >= 24) bb.getShort(22).toInt() and 0xffff else 0
                _homeData.value = HomeData(
                    lat = Math.toDegrees(latRad),
                    lon = Math.toDegrees(lonRad),
                    altM = if (altM.isFinite()) altM else 0f,
                    homePointSet = (flags and 0x01) != 0,
                    goHomeMode = (flags and 0x02) != 0,
                    goHomeHeight = goHomeHeight,
                    beginnerMode = (flags and 0x800) != 0,
                    atDistLimit = (flags and 0x10) != 0,
                    atHeightLimit = (flags and 0x20) != 0,
                    valid = true,
                )
            }
            frame.cmdSet == 0x06 && frame.cmdId == 0x57 && frame.payload.isNotEmpty() -> {
                // RC Battery and GPS Location (cmd_set=0x06, cmd_id=0x57), ~2 Hz broadcast.
                // Exact byte layout TBD from live capture; placeholder handler captures the frame
                // for reverse-engineering. Both battery % and GPS may be in this frame or separate.
                android.util.Log.i("GF_RC_STATUS", "0x06/0x57 len=%d %s".format(
                    frame.payload.size,
                    frame.payload.joinToString(" ") { "%02x".format(it) }
                ))
                // TODO: Decode battery percent, voltage, and GPS location once byte layout confirmed
            }
            frame.cmdSet == 0x0d && frame.cmdId != 0x02 && frame.payload.isNotEmpty() -> {
                // Battery Cell Voltage Data (cmd_set=0x0d, cmd_id=0xXX), ~2 Hz broadcast.
                // Exact cmd_id and byte layout TBD from firmware analysis or live capture.
                // Placeholder logs the frame for reverse-engineering.
                android.util.Log.i("GF_BATT_CELLS", "0x0d/0x%02x len=%d".format(
                    frame.cmdId,
                    frame.payload.size
                ))
                // TODO: Decode individual cell voltages once byte layout confirmed
            }
            // ── Device/firmware-info query ACKs (see DeviceInfoRaw's doc comment, raw hex
            // only, no confirmed byte layout to parse a version string from). Gated on
            // frame.isAck, unlike the PUSH-type cases above: these are direct request/response
            // pairs (GlassFalcon has to send the query first), not the FC broadcasting
            // unprompted, so only the ACK reply, not some coincidental PUSH sharing the same
            // cmd_id, should ever populate this. cmd_set=0x03/cmd_id=0x36 (FlyC.requestSn, "FC
            // Serial") is deliberately NOT captured here: that exact cmd_set/cmd_id pair is
            // already claimed above for the smart-battery percentage PUSH, and without a
            // confirmed way to tell the two apart on the wire, capturing it would risk
            // misattributing one as the other. ──
            // ── AirSense (ADS-B / UAT IN) traffic. cmd_set 0x11: 0x02 Push Data (target list),
            // 0x08 Push Warning, 0x09 Push Original. Receive-only — see AirSenseTarget's doc.
            // Every frame is logged raw so the (currently unknown) target byte layout can be
            // reversed from a live outdoor capture; the mere arrival of a 0x08 Push Warning is a
            // real "traffic flagged by the FC" signal we surface even before decoding it. ──
            frame.cmdSet == 0x11 && frame.cmdId in intArrayOf(0x02, 0x08, 0x09) -> {
                val hex = frame.payload.joinToString(" ") { "%02x".format(it) }
                android.util.Log.i("GF_ADSB", "0x11/0x%02x len=%d %s".format(frame.cmdId, frame.payload.size, hex))
                val decoded = if (frame.cmdId != 0x08) decodeADS_BItem(frame.payload)
                              else _airSense.value.targets
                // Push Warning with no decodable level still means "traffic warning" → floor at 1.
                val warnFromFrame = if (frame.cmdId == 0x08) maxOf(1, decodeADS_BWarning(frame.payload)) else 0
                val maxWarn = maxOf(warnFromFrame, decoded.maxOfOrNull { it.warningLevel } ?: 0)
                _airSense.value = AirSenseState(
                    targets = decoded,
                    maxWarningLevel = maxWarn,
                    isWarningActive = maxWarn > 0,
                    lastFrameMs = System.currentTimeMillis(),
                    lastRawHex = hex,
                    active = true,
                )
            }
            frame.cmdSet == 0x00 && frame.cmdId == 0x01 && frame.isAck ->
                captureDeviceInfo("Version Inquiry", frame)
            frame.cmdSet == 0x00 && frame.cmdId == 0x51 && frame.isAck ->
                captureDeviceInfo("Serial (General)", frame)
            frame.cmdSet == 0x03 && frame.cmdId == 0x37 && frame.isAck ->
                captureDeviceInfo("FC Device Info", frame)
        }
    }

    private fun captureDeviceInfo(label: String, frame: DumlFrame) {
        val hex = frame.payload.joinToString(" ") { "%02x".format(it) }
        _deviceInfoRaw.value = _deviceInfoRaw.value + (label to DeviceInfoRaw(label, hex, System.currentTimeMillis()))
    }

    /**
     * Decode ADS-B aircraft list from cmd_set=0x11, cmd_id=0x02 (GetPushData) or 0x09 (GetPushOriginal).
     *
     * Expected fields per target (from DJI JADX decompilation DataADSBGetPushData):
     * - ICAOAddress: 24-bit hex string (e.g., "A00001")
     * - callsign: 8-character ASCII
     * - latitude: WGS-84 decimal degrees (double)
     * - longitude: WGS-84 decimal degrees (double)
     * - altitude: barometric or GNSS altitude in meters (float)
     * - heading: ground track 0-359 degrees (float)
     * - hSpeed: horizontal speed m/s (float)
     * - vSpeed: vertical speed m/s (float, positive = climb)
     * - NIC: Navigation Integrity Category 0-11 (int)
     * - NACP: Navigation Accuracy Category - Position (int)
     *
     * ⚠️ BYTE LAYOUT UNCONFIRMED. The exact wire format is unknown (no live traffic capture yet).
     * Raw frames are logged at GF_ADSB tag; dissector can be filled in from a real outdoor flight
     * or DJI flight log capture. Returns empty for now; when implementing, gate on plausible lat/lon
     * (±90°) so a mis-parsed record never places a phantom aircraft.
     */
    private fun decodeADS_BItem(@Suppress("UNUSED_PARAMETER") payload: ByteArray): List<AirSenseTarget> =
        emptyList()

    /**
     * Decode ADS-B collision warning from cmd_set=0x11, cmd_id=0x08 (GetPushWarning).
     *
     * Expected fields per DJI JADX decompilation DataADSBGetPushWarning:
     * - warningType: DJIWarningType enum (0=None, 1..4=warning levels, 100=OTHER)
     * - list: Array of FlightItem (threatening aircraft)
     *   - ICAOAddress, latitude, longitude, altitude, heading, speed
     *   - distance: meters to threat
     *   - remainTime: seconds until conflict
     *   - warningLevel: individual threat severity
     *
     * Returns the system-wide warning level (0..4). Most urgent level wins.
     *
     * ⚠️ BYTE LAYOUT UNCONFIRMED. Wire format unknown (no live traffic capture yet).
     * Returns 0 for now; caller floors frame arrival at level 1 regardless, so warning path still
     * fires. Dissector can be refined from a real outdoor flight or DJI flight log.
     */
    private fun decodeADS_BWarning(@Suppress("UNUSED_PARAMETER") payload: ByteArray): Int = 0
}
