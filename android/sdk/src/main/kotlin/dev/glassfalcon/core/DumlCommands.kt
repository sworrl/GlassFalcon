// GlassFalcon, authored and owned by FalconTechnix.
// Copyright (C) 2026 FalconTechnix.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free software released by FalconTechnix under the GNU GPL v3 or later.
// See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

package dev.glassfalcon.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Shorthand: (cmdSet, cmdId, payload)
typealias Cmd = Triple<Int, Int, ByteArray>

fun DumlConnection.send(cmd: Cmd) = send(DumlConnection.FC, cmd.first, cmd.second, cmd.third)
fun DumlConnection.sendCam(cmd: Cmd) = send(DumlConnection.CAM, cmd.first, cmd.second, cmd.third)
fun DumlConnection.sendGimbal(cmd: Cmd) = send(DumlConnection.GIMB, cmd.first, cmd.second, cmd.third)
fun DumlConnection.sendRc(cmd: Cmd) = send(DumlConnection.RC, cmd.first, cmd.second, cmd.third)

// ── General ──────────────────────────────────────────────────────────────────
object General {
    fun ping()           = Cmd(0x00, 0x00, byteArrayOf())
    fun versionInquiry() = Cmd(0x00, 0x01, byteArrayOf(0x00))
    fun reboot()         = Cmd(0x00, 0x0b, byteArrayOf())
    fun getSerial()      = Cmd(0x00, 0x51, byteArrayOf())
}

// ── Camera ───────────────────────────────────────────────────────────────────
// 2026-07-03 kprobe capture (real DJI GO4 session, wm240): cmd_set 0x01 ("SPECIAL" per
// dji-dumlv1-proto's own cmd_set table) is a distinct category from cmd_set 0x02 ("CAMERA")
//, this project previously sent every Camera.* function on 0x01 because that's the module's
// dst address, conflating routing (dst, unchanged) with the embedded cmd_set byte (a separate
// field). Confirmed real, live: mode-set (0x10) payload 01/00 matched an actual Photo<->Video
// toggle, focus-set (0x24) payload 02/00 matched an actual AFC<->MF toggle, and AE-lock (0x68)
// payload 01 matched an actual AE-lock press, all captured under cmd_set=0x02, not 0x01. This
// almost certainly explains the earlier confirmed 0xe0 ("rejected") ACKs on setMode/
// systemState/sdcardInfo when sent under 0x01. capturePhoto/startRecord/stopRecord stay on
// 0x01, those got real 0x00 success ACKs there in the same testing session, unchanged here.
object Camera {
    // Mavic 2 ZOOM optical zoom, "Camera Optics Zoom Mode" (0x01/0xb8, per the camera dissector).
    // Continuous: START zooming with mode 1 (in) / 2 (out), then STOP with mode 0. speed 0x78..0x7e
    // (slow..fast). No-op on the Mavic 2 PRO (no optical zoom). Payload [mode][speed][c=0][d=0].
    const val ZOOM_STOP = 0; const val ZOOM_IN = 1; const val ZOOM_OUT = 2
    fun opticsZoom(mode: Int, speed: Int = 0x7c) =
        Cmd(0x01, 0xb8, byteArrayOf(mode.toByte(), speed.toByte(), 0, 0))

    fun capturePhoto()  = Cmd(0x01, 0x01, byteArrayOf(0x01))
    fun startRecord()   = Cmd(0x01, 0x02, byteArrayOf(0x02))
    fun stopRecord()    = Cmd(0x01, 0x02, byteArrayOf(0x03))
    fun setMode(m: Int) = Cmd(0x02, 0x10, byteArrayOf(m.toByte()))
    fun setIso(i: Int)  = Cmd(0x02, 0x2a, byteArrayOf(i.toByte()))
    fun setShutter(i: Int) = Cmd(0x02, 0x28, byteArrayOf(i.toByte()))
    fun setAperture(i: Int) = Cmd(0x02, 0x26, byteArrayOf(i.toByte()))
    fun setEv(i: Int)   = Cmd(0x02, 0x2e, byteArrayOf(i.toByte()))
    fun setWb(i: Int)   = Cmd(0x02, 0x2c, byteArrayOf(i.toByte()))
    fun setExpMode(i: Int) = Cmd(0x02, 0x1e, byteArrayOf(i.toByte()))
    fun setColor(i: Int) = Cmd(0x02, 0x42, byteArrayOf(i.toByte()))
    // Focus mode values, CONFIRMED live 2026-07-03 (see header comment): 0=Manual 2=ContinuousAuto(AFC).
    // 1=OneAuto 3=ManualFine per dji-dumlv1-camera.lua's enum, not directly observed.
    fun setFocus(i: Int)       = Cmd(0x02, 0x24, byteArrayOf(i.toByte()))
    // Tap-to-focus (spot AF), "Focus Region Set" (0x30). Payload is two little-endian float32:
    // normalized x then y, 0.0..1.0 across the frame. CONFIRMED live 2026-07-04 via kprobe: a
    // dead-center tap emitted exactly (0.5, 0.5); corners matched their screen positions.
    fun setFocusRegion(x: Float, y: Float): Cmd {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(x.coerceIn(0f, 1f)); buf.putFloat(y.coerceIn(0f, 1f))
        return Cmd(0x02, 0x30, buf.array())
    }
    // "AE Lock Status Set", cmd_id AND cmd_set CONFIRMED live 2026-07-03 (see header comment).
    fun setAeLock(locked: Boolean) = Cmd(0x02, 0x68, byteArrayOf(if (locked) 1 else 0))
    fun setAntiFlicker(i: Int) = Cmd(0x02, 0x46, byteArrayOf(i.toByte()))
    fun systemState()          = Cmd(0x02, 0x70, byteArrayOf())
    fun sdcardInfo()           = Cmd(0x02, 0x71, byteArrayOf())
    fun formatSd()             = Cmd(0x02, 0x72, byteArrayOf())
    fun formatSdProgress()     = Cmd(0x02, 0x73, byteArrayOf())
    fun fileDelete(index: Int) = Cmd(0x02, 0x79, byteArrayOf((index and 0xff).toByte(), (index shr 8 and 0xff).toByte()))
    fun videoPlayControl(m: Int) = Cmd(0x02, 0x7a, byteArrayOf(m.toByte()))
    fun thumbnailCtrl(index: Int) = Cmd(0x02, 0x7b, byteArrayOf((index and 0xff).toByte(), (index shr 8 and 0xff).toByte()))
    fun fileSystemInfo()       = Cmd(0x02, 0x98, byteArrayOf())
}

// ── FlyC ─────────────────────────────────────────────────────────────────────
object FlyC {
    fun emergencyStop() = Cmd(0x03, 0x0e, byteArrayOf())
    fun motorCtrl(arm: Boolean) = Cmd(0x03, 0x0b, byteArrayOf(if (arm) 1 else 0))
    fun setRcLostAction(a: Int) = Cmd(0x03, 0x3b, byteArrayOf(a.toByte()))
    fun getRcLostAction() = Cmd(0x03, 0x3c, byteArrayOf())
    // "Forearm LED Status Set" (dji-dumlv1-flyc.lua cmd_id 0xba), the aircraft arm/nav lights,
    // which is what most pilots mean by "landing light." cmd_id framing is confirmed named, but
    // no payload enum is documented anywhere (same tier as the RC buzzer lead), 0/1 here is an
    // on/off guess, not a verified value. Non-safety-critical (worst case: wrong light pattern,
    // not a flight risk), so shipped experimental rather than gated on a live capture.
    fun setLed(m: Int) = Cmd(0x03, 0xba, byteArrayOf(m.toByte()))
    fun assistantUnlock(on: Boolean) = Cmd(0x03, 0xdf, byteArrayOf(if (on) 1 else 0))
    fun returnHome() = Cmd(0x03, 0x0d, byteArrayOf(0x01))

    /**
     * "Send GPS To Flyc" (0x03/0x20, named in dji-dumlv1-flyc.lua). Pushes the MOBILE device's
     * (phone's) GPS position to the flight controller. CAPTURED live from DJI GO 4 v4.3.64 via the
     * acc_write kprobe (2026-07-05), GO 4 sends this every few seconds; GlassFalcon never did,
     * which is why the aircraft stayed clamped at the ~30 m envelope: without the app feeding it a
     * mobile position the FC restricts flight. Decoded 13-byte payload (verified against the live
     * NM coordinates in the capture):
     *   [0]     0x03, fixed type byte
     *   [1..4]  latitude  as int32 LE, degrees × 1e6  (0x0216fdbc = 35.127228 → 35.127°N)
     *   [5..8]  longitude as int32 LE, degrees × 1e6  (0xf9a23534 = -105.943244 → -105.943°W)
     *   [9..12] unix timestamp, seconds, uint32 LE (ticks up each send)
     */
    fun sendGpsToFlyc(lat: Double, lon: Double, unixSec: Long): Cmd {
        val buf = java.nio.ByteBuffer.allocate(13).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(0x03)
        buf.putInt((lat * 1e6).toInt())
        buf.putInt((lon * 1e6).toInt())
        buf.putInt(unixSec.toInt())
        return Cmd(0x03, 0x20, buf.array())
    }
    // FLYC Function Control (0x03/0x2a), sets wm610_app_command.function_command. Undocumented
    // upstream (dji-firmware-tools' dissector declares this an opaque raw byte, no enum), no
    // vendored research repo had a wm240-confirmed trigger value.
    fun functionControl(cmd: Int) = Cmd(0x03, 0x2a, byteArrayOf(cmd.toByte()))
    // CONFIRMED via kprobe capture of a genuine DJI GO 4 v4.3.64 takeoff over the same AOA
    // link (2026-07-02): real payload is 0x01, not the earlier 0x0b guess (which was
    // pattern-matched from unrelated OSD telemetry-readback enums, a category error, not
    // just a wrong-generation opcode). Verified end-to-end: FC accepted the frame and
    // engaged the motors (caught by the aircraft's own no-propeller safety check in the
    // props-off test this was captured during).
    fun autoTakeoff() = functionControl(0x01)
    // UNVERIFIED, 0x0c is still an unconfirmed guess. The takeoff capture also caught one
    // 0x03/0xfe (payload 0x00) about 11.6s after the takeoff trigger, possibly a stop/cancel
    // the app sent after the no-prop warning, but that's not enough evidence to claim it's
    // the land trigger. Needs the same kprobe-capture treatment as takeoff before trusting it.
    fun autoLand()    = functionControl(0x0c)

    // FlyC Activation (0x03/0x61 info, 0x03/0x62 exec), documented in dji-dumlv1-flyc.lua
    // but never exercised by this SDK. Firmware tracks "Product not activation, stop motor"
    // as an explicit motor-start failure reason, so lack of activation is a real gating
    // condition in this firmware family (generation-unconfirmed for wm240). Worth trying
    // before functionControl() in the next bench session. appId/appLevel/appVersion values
    // are unknown for this project, 0 is a placeholder, not a verified value.
    fun activationInfo(appId: Int = 0, appLevel: Int = 0, appVersion: Int = 0): Cmd {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(appId); buf.putInt(appLevel); buf.putInt(appVersion)
        return Cmd(0x03, 0x61, buf.array())
    }
    fun activationExec(appId: Int = 0, appLevel: Int = 0, appVersion: Int = 0): Cmd {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(appId); buf.putInt(appLevel); buf.putInt(appVersion)
        return Cmd(0x03, 0x62, buf.array())
    }
    fun requestSn() = Cmd(0x03, 0x36, byteArrayOf())
    fun deviceInfo() = Cmd(0x03, 0x37, byteArrayOf())

    fun joystick(roll: Float, pitch: Float, throttle: Float, yaw: Float): Cmd {
        fun scale(v: Float) = (v.coerceIn(-1f, 1f) * 10000).toInt().toShort()
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(scale(roll)); buf.putShort(scale(pitch))
        buf.putShort(scale(throttle)); buf.putShort(scale(yaw))
        return Cmd(0x03, 0x29, buf.array())
    }

    /** Set Home Point (0x03/0x31). ⚠️ DO NOT SEND on the Mavic 2 (wm240): live-confirmed 2026-07-05
     *  that sending this RE-LOCKS the 30 m altitude/distance "kid mode" cap, the aircraft records
     *  its own home automatically, and the cap is lifted by the mobile-GPS stream (0x03/0x20), not
     *  by setting home. Kept here only to document the command; the app never calls it. */
    fun setHomePoint(lat: Double, lon: Double, alt: Float = 0f): Cmd {
        val buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt((lat * 1e7).toInt())
        buf.putInt((lon * 1e7).toInt())
        buf.putShort((alt * 10).toInt().toShort())
        return Cmd(0x03, 0x31, buf.array())
    }

    /** Get Home Point (0x03/0x44, catalogued "0x31 Set, 0x44 Get"). Response echoes the FC's
     *  recorded home: home lon/lat as little-endian doubles in RADIANS, followed by other status
     *  and the FC serial (ASCII). Read-only and safe to send (unlike [setHomePoint]). */
    fun getHomePoint(): Cmd = Cmd(0x03, 0x44, ByteArray(0))

    // ── Config table by-hash (0x03/0xf8 read, 0x03/0xf9 write) ────────────────
    // This is DJI's own generic FC-parameter tuning pathway (same one DJI Assistant 2 uses),
    // fully byte-level dissected upstream (dji-dumlv1-flyc.lua), HIGH confidence on the framing
    // below. What's NOT confirmed: the exact value size/type for any given hash (the dissector
    // says "depends on parameter", it doesn't say what each one is), read a hash back first
    // and inspect the returned byte count before trusting a write. This is why GlassFalcon
    // never enforces distance/height/speed limits itself and exposes these as a way to read
    // and raise the aircraft's OWN limit, not a client-side cap of any kind.
    //
    // Request:  hash:u32 LE                              (0xf9 write also appends value:bytes)
    // Response: status:u8, hash:u32 LE, value:bytes
    object ParamHash {
        const val MAX_HEIGHT           = 0x0371238aL  // g_config.flying_limit.max_height_0
        const val MAX_RADIUS           = 0x425c0a94L  // g_config.flying_limit.max_radius_0
        const val MIN_HEIGHT           = 0x0438298aL  // g_config.flying_limit.min_height_0
        const val HEIGHT_LIMIT_ENABLED = 0xae52d19aL  // g_config.advanced_function.height_limit_enabled_0
        const val RADIUS_LIMIT_ENABLED = 0x7ece6d19L  // g_config.advanced_function.radius_limit_enabled_0
        const val NOVICE_MAX_HEIGHT    = 0xd9ab9f79L  // g_config.novice_cfg.max_height_0
        const val NOVICE_MAX_RADIUS    = 0x18968688L  // g_config.novice_cfg.max_radius_0
        // Beginner ("novice") mode master switch, 1-byte bool. HIGH confidence: this exact
        // hash+framing was captured live off DJI GO 4's own Beginner Mode toggle via the acc_write
        // kprobe (Pixel 8 Pro, 2026-07-04), confirmed in isolation, flipping ONLY that one UI
        // switch wrote 0x03/0xf9 hash=0xde9b1b7b value=01/00 and nothing else. The g_config name
        // isn't confirmed (DJI's param-name→hash function is unknown here), but the behavior is:
        // value 0 disables the 30 m height/radius beginner cap.
        const val NOVICE_MODE_ENABLED  = 0xde9b1b7bL
        // Low-battery action thresholds (dji-dumlv1-flyc.lua param-hash table). level_2 = the
        // "low battery" voltage that triggers auto-RETURN-TO-HOME; level_1 = the "critical"
        // voltage that triggers a FORCED AUTO-LANDING. Both are FC-side smart-battery safety, NOT
        // anything GlassFalcon commands. Driving them to the FC's own declared minimum delays both
        // to as late as the firmware allows (near cell cutoff), the FC won't accept "off".
        const val LEVEL_1_VOLTAGE      = 0x5aae5bcdL  // g_config.voltage2.level_1_voltage_0 (force land)
        const val LEVEL_2_VOLTAGE      = 0x5ac75bcdL  // g_config.voltage2.level_2_voltage_0 (RTH)
    }

    fun readParamByHash(hash: Long): Cmd {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(hash.toInt())
        return Cmd(0x03, 0xf8, buf.array())
    }

    fun writeParamByHash(hash: Long, value: ByteArray): Cmd {
        val buf = ByteBuffer.allocate(4 + value.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(hash.toInt())
        buf.put(value)
        return Cmd(0x03, 0xf9, buf.array())
    }

    /** Encode a numeric param value into `size` bytes per its [typeId] (from a ParamInfo probe),
     *  for [writeParamByHash]. Used to write a param's FC-declared min/max back verbatim without
     *  guessing its encoding. */
    fun encodeParamValue(v: Double, typeId: Int, size: Int): ByteArray {
        val n = if (size in 1..8) size else 4
        val bb = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        when {
            typeId == TYPE_FLOAT -> bb.putFloat(v.toFloat())
            typeId == TYPE_DOUBLE && n == 8 -> bb.putDouble(v)
            n == 1 -> bb.put(v.toInt().toByte())
            n == 2 -> bb.putShort(v.toInt().toShort())
            n == 4 -> bb.putInt(v.toLong().toInt())
            n == 8 -> bb.putLong(v.toLong())
        }
        return bb.array()
    }

    data class ParamHashResult(val status: Int, val hash: Long, val value: ByteArray)

    /** Decode a 0xf8/0xf9 response: status:u8, hash:u32 LE, value:bytes. */
    fun parseParamByHash(payload: ByteArray): ParamHashResult? {
        if (payload.size < 5) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val status = buf.get().toInt() and 0xff
        val hash = buf.int.toLong() and 0xffffffffL
        val value = payload.copyOfRange(5, payload.size)
        return ParamHashResult(status, hash, value)
    }

    // ── Param INFO by hash (0x03/0xf7): the device's OWN declared range ───────
    // Ask the FC what a parameter's real bounds are instead of guessing. Request is just the
    // u32 LE hash. This is the actual "probe the aircraft for what we can set" query, captured
    // live going out of DJI GO 4 (0x03/0xf7 with a bare hash), so the request side is confirmed.
    // The response layout is dji-firmware-tools' GetParamInfoU/I/F2015Re (the hash-era protocol
    // this Mavic 2 uses): status u8, type u16, size u16, attribute u16, min/max/default (each a
    // 4-byte field read as unsigned int, signed int, or float per `type`), then a null-terminated
    // name. Response round-trip isn't yet confirmed on wm240, read it back and sanity-check the
    // name/range before trusting min/max for a write.
    fun readParamInfoByHash(hash: Long): Cmd {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(hash.toInt())
        return Cmd(0x03, 0xf7, buf.array())
    }

    // Numeric param types per DJIPayload_FlyController_ParamType.
    private val SIGNED_TYPES = setOf(4, 5, 6, 7)   // byte, short, long, longlong
    private const val TYPE_FLOAT = 8
    private const val TYPE_DOUBLE = 9

    data class ParamInfo(
        val status: Int, val typeId: Int, val size: Int, val attribute: Int,
        val min: Double, val max: Double, val def: Double, val name: String,
    )

    /** Decode a 0x03/0xf7 param-info response. Returns null if too short to hold the fixed header
     *  (status + 3×u16 + 3×4-byte limits = 19 bytes). min/max/def are decoded per `typeId`. */
    fun parseParamInfo(payload: ByteArray): ParamInfo? {
        if (payload.size < 19) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val status = b.get().toInt() and 0xff
        val typeId = b.short.toInt() and 0xffff
        val size = b.short.toInt() and 0xffff
        val attribute = b.short.toInt() and 0xffff
        fun limit(): Double {
            val raw = b.int
            return when {
                typeId == TYPE_FLOAT -> Float.fromBits(raw).toDouble()
                typeId in SIGNED_TYPES -> raw.toDouble()
                else -> (raw.toLong() and 0xffffffffL).toDouble()  // unsigned + bool + double(low word)
            }
        }
        val min = limit(); val max = limit(); val def = limit()
        val name = payload.copyOfRange(19, payload.size)
            .takeWhile { it.toInt() != 0 }.toByteArray().decodeToString()
        return ParamInfo(status, typeId, size, attribute, min, max, def, name)
    }

    // ── Index-based param access (2017 protocol), the WHOLE 643-param config table without needing
    // a per-param hash. Confirmed live on wm240 over /dev/ttyACM0 (2026-07-05) via
    // comm_og_service_tool. NOTE: the FC only answers these to the assistant-tool identity
    // (src=0x0a), NOT the mobile-app 0x02, see DumlConnection.sendAs. FlyC params live in table 0.
    fun getTableAttribs(tableNo: Int = 0): Cmd {
        val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN); b.putShort(tableNo.toShort())
        return Cmd(0x03, 0xe0, b.array())
    }
    /** 0xe0 response: status u16, table_no u16, entries_crc u32, entries_num u32. Returns the
     *  number of params in the table (entries_num) or null. */
    fun parseTableAttribs(payload: ByteArray): Int? {
        if (payload.size < 12) return null
        return ByteBuffer.wrap(payload, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun getParamInfoByIndex(tableNo: Int, index: Int): Cmd {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(tableNo.toShort()); b.putShort(index.toShort())
        return Cmd(0x03, 0xe1, b.array())
    }

    fun readParamByIndex(tableNo: Int, index: Int): Cmd {
        val b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(tableNo.toShort()); b.putShort(1); b.putShort(index.toShort())
        return Cmd(0x03, 0xe2, b.array())
    }
    data class IndexValue(val status: Int, val index: Int, val value: ByteArray)
    /** 0xe2 response: status u16, unknown1 u16, param_index u16, value bytes (offset 6). */
    fun parseReadByIndex(payload: ByteArray): IndexValue? {
        if (payload.size < 6) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val status = b.short.toInt() and 0xffff
        b.short  // unknown1
        val index = b.short.toInt() and 0xffff
        return IndexValue(status, index, payload.copyOfRange(6, payload.size))
    }

    fun writeParamByIndex(tableNo: Int, index: Int, value: ByteArray): Cmd {
        val b = ByteBuffer.allocate(6 + value.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(tableNo.toShort()); b.putShort(1); b.putShort(index.toShort()); b.put(value)
        return Cmd(0x03, 0xe3, b.array())
    }

    /** Encode a numeric value for [writeParamByIndex] per a ParamInfo's typeId/size. */
    fun encodeIndexValue(v: Double, typeId: Int, size: Int): ByteArray = encodeParamValue(v, typeId, size)
}

// ── RC ───────────────────────────────────────────────────────────────────────
object Rc {
    // UNVERIFIED / EXPERIMENTAL, not empirically confirmed like FlyC.autoTakeoff() was.
    // Lead comes from a community dissector (dji-dumlv1-proto.lua RC_UART_CMD_TEXT table):
    // cmd_set=0x06 is the "RC" command group (matches this SDK's own convention that a
    // module's cmd_set number equals its dst address, see FC/CAM/GIMB), cmd_id=0xf7 is
    // named "RC Push Buzzer To MCU" with no documented payload layout. `pattern` is a guess
    // at a single-byte "which tone" selector, untested. Low risk to try (worst case: no
    // sound, or the wrong sound), not safety-critical like a flight command, so shipping
    // this experimental rather than waiting for a kprobe-confirmed capture is reasonable.
    fun pushBuzzer(pattern: Int = 1) = Cmd(0x06, 0xf7, byteArrayOf(pattern.toByte()))
}

// ── Gimbal ───────────────────────────────────────────────────────────────────
object Gimbal {
    fun getPosition() = Cmd(0x04, 0x02, byteArrayOf())

    /**
     * "Gimbal Ext Ctrl Accel" (0x04/0x0c), the SPEED-control command DJI GO 4 uses for on-screen
     * gimbal drag (dji-dumlv1-gimbal.lua names 0x0C "Gimbal Ext Ctrl Accel, Speed Control").
     * CAPTURED from GO 4 via acc_write kprobe (2026-07-05). 7-byte payload, three int16 LE axis
     * speeds + a flag byte:
     *   [0..1] yaw speed   [2..3] roll (always 0 on wm240)   [4..5] pitch speed   [6] flag 0x80
     * Values are angular SPEED (not angle); GO 4 feeds a SQUARED expo of finger displacement
     * (observed values were exact squares: 8²=64, 21²=441, 47²=2209), which is what makes the drag
     * smooth instead of jumpy. Send continuously while dragging; send (0,0) to stop.
     */
    fun speed(pitch: Int, yaw: Int): Cmd {
        val buf = java.nio.ByteBuffer.allocate(7).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.putShort(yaw.toShort())
        buf.putShort(0)            // roll, unused on this airframe
        buf.putShort(pitch.toShort())
        buf.put(0x80.toByte())     // flag: "external speed control active"
        return Cmd(0x04, 0x0c, buf.array())
    }
    fun calibrate()   = Cmd(0x04, 0x08, byteArrayOf())
    fun lock(on: Boolean) = Cmd(0x04, 0x39, byteArrayOf(if (on) 1 else 0))
    fun getTemp()     = Cmd(0x04, 0x45, byteArrayOf())
    fun setMode(m: Int) = Cmd(0x04, 0x4c, byteArrayOf(m.toByte()))

    fun absAngle(pitch: Float, roll: Float = 0f, yaw: Float = 0f, timeDs: Int = 5): Cmd {
        val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort((pitch * 10).toInt().toShort())
        buf.putShort((roll  * 10).toInt().toShort())
        buf.putShort((yaw   * 10).toInt().toShort())
        buf.put(timeDs.toByte())
        return Cmd(0x04, 0x0a, buf.array())
    }
}
