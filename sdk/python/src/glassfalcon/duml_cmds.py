# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
DUML command builders and response parsers for decoded wm240 modules.

Device type constants (from comm_mkdupc.py COMM_DEV_TYPE):
  CAMERA     = 0x01   Camera (Ambarella)
  FC         = 0x03   Flight Controller
  GIMBAL     = 0x04   Gimbal
  MOBILE_APP = 0x02   Phone app (us)
  PC         = 0x0a   Ground/desktop assistant tool (DJI Assistant 2), NOT us

MOBILE_APP and PC are distinct roles in DJI's own device-type enum. A wm240
bench test (2026-07-01) sending as PC got zero ACK / zero state change from
the flight controller for a flight-authority command; sending as MOBILE_APP
is untested but is the technically correct identity for this app to claim.

cmd_type:
  REQ = 0x40   request (no ACK required unless bit set)
  ACK = 0x80   response/ACK

Each helper returns a (cmd_set, cmd_id, payload) tuple suitable for
  duml.send(src=MOBILE_APP, dst=<target>, cmd_type=REQ, *cmd).
"""

import struct

# ── Device addresses (confirmed via version-inquiry discovery on wm240) ───────
CAM        = 0x01   # Camera, "WM240"            (Ambarella H2)
FC         = 0x03   # Flight Controller, "163DF7" (firmware 24a0714)
GIMB       = 0x04   # Gimbal, "GB11"
HDVT       = 0x09   # DJI P1 HDVT, OcuSync 2.0 HD Video Transmission module
MOBILE_APP = 0x02   # Phone app (us), see module docstring
PC         = 0x0a   # Ground/desktop assistant tool (DJI Assistant 2), NOT us
BATT       = 0x0b   # Battery, "BA01WM240BAT"
ESC        = 0x0c   # Motor ESCs, "WM240_ESC_V7" (4× in 1)
TOF        = 0x12   # Obstacle avoidance ToF, "WM240_TOF_v2"
AC         = 0x28   # Aircraft Computer, "WM240 AC Ver.A" (also answers 0x40)

REQ   = 0x40
ACK   = 0x80

# ── General (cmd_set 0x00) ───────────────────────────────────────────────────

def ping():
    return (0x00, 0x00, b"")

def version_inquiry():
    return (0x00, 0x01, b"\x00")

def reboot_chip():
    """Reboot the target module."""
    return (0x00, 0x0b, b"")

def get_device_state():
    return (0x00, 0x0c, b"")

def get_serial_number():
    return (0x00, 0x51, b"")

def query_device_info():
    return (0x00, 0xff, b"")

def led_control(r: int, g: int, b: int, blink_hz: int = 0):
    """
    Set LED color/blink on the target module.
    Values 0-255. blink_hz 0 = solid.
    Payload format from dji-dumlv1-general.lua 0x48.
    """
    return (0x00, 0x48, bytes([r & 0xff, g & 0xff, b & 0xff, blink_hz & 0xff]))

def parse_version_inquiry(payload: bytes) -> dict:
    """Parse cmd_set=0x00 cmd_id=0x01 response."""
    if len(payload) < 30:
        return {"raw": payload.hex()}
    unk0 = payload[0]
    unk1 = payload[1]
    hw_ver = payload[2:18].rstrip(b"\x00").decode("ascii", "replace")
    ldr = struct.unpack_from("<BBBB", payload, 18)
    app = struct.unpack_from("<BBBB", payload, 22)
    return {
        "hw_version":  hw_ver,
        "loader_fw":   f"{ldr[3]:02d}.{ldr[2]:02d}.{ldr[1]:02d}.{ldr[0]:02d}",
        "app_fw":      f"{app[3]:02d}.{app[2]:02d}.{app[1]:02d}.{app[0]:02d}",
        "flags":       struct.unpack_from("<I", payload, 26)[0],
    }

def parse_serial_number(payload: bytes) -> str:
    return payload.rstrip(b"\x00").decode("ascii", "replace")

# ── Camera (cmd_set 0x01) ────────────────────────────────────────────────────

def camera_capture_photo():
    """cmd_id=0x01, take a single photo."""
    return (0x01, 0x01, bytes([0x01]))

def camera_start_record():
    """cmd_id=0x02, start video recording."""
    return (0x01, 0x02, bytes([0x02]))

def camera_stop_record():
    """cmd_id=0x02, stop video recording."""
    return (0x01, 0x02, bytes([0x03]))

def camera_set_mode(mode: int):
    """
    cmd_id=0x10, Camera Work Mode:
      0=Photo  1=Video  2=Playback  3=Download  4=Broadcast
    CONFIRMED live 2026-07-03 via kprobe capture: payload 01/00 matched a real Photo<->Video
    toggle in DJI GO4, on cmd_set=0x02, not 0x01, which this project used before that capture.
    """
    return (0x02, 0x10, bytes([mode]))

def camera_set_iso(iso_idx: int):
    """
    cmd_id=0x2a, ISO index:
      0=Auto 1=100 2=200 3=400 4=800 5=1600 6=3200 7=6400 8=12800
    """
    return (0x02, 0x2a, bytes([iso_idx]))

def camera_set_shutter(shutter_idx: int):
    """
    cmd_id=0x28, Shutter speed index (0=auto, 1=1/8000 .. 20=1/30 .. 30=1s .. see table).
    Index table from dji-dumlv1-camera.lua and DJI SDK.
    """
    return (0x02, 0x28, bytes([shutter_idx]))

def camera_set_aperture(aperture_idx: int):
    """cmd_id=0x26, Aperture index (0=f/1.7 .. 22=f/11 for wm240 Hasselblad)."""
    return (0x02, 0x26, bytes([aperture_idx]))

def camera_set_ev(ev_idx: int):
    """
    cmd_id=0x2e, EV bias index:
      0=-3.0  2=-2.0  4=-1.0  6=0.0  8=+1.0  10=+2.0  12=+3.0
    (step 0.5 EV, index*0.5 - 3.0)
    """
    return (0x02, 0x2e, bytes([ev_idx]))

def camera_set_white_balance(wb_idx: int):
    """
    cmd_id=0x2c, White balance:
      0=Auto 1=Sunny 2=Cloudy 3=Indoor 4=Fluorescent 5=Custom
    """
    return (0x02, 0x2c, bytes([wb_idx]))

def camera_set_exposure_mode(mode: int):
    """
    cmd_id=0x1e, Exposure mode:
      0=Program/Auto  1=ShutterPriority  2=AperturePriority  3=Manual
    """
    return (0x02, 0x1e, bytes([mode]))

def camera_set_color_mode(mode: int):
    """
    cmd_id=0x42, Digital/color filter:
      0=None 1=Art 2=BlackAndWhite 3=Bright 4=DLog 5=Portrait 6=Vivid 7=Landscape
    """
    return (0x02, 0x42, bytes([mode]))

def camera_set_anti_flicker(mode: int):
    """cmd_id=0x46, Anti-flicker: 0=Auto 1=50Hz 2=60Hz."""
    return (0x02, 0x46, bytes([mode]))

def camera_set_focus_mode(mode: int):
    """cmd_id=0x24, Focus mode: 0=MF 1=AF 2=AFC."""
    return (0x02, 0x24, bytes([mode]))

def camera_get_system_state():
    """cmd_id=0x70, Request camera system state push."""
    return (0x02, 0x70, b"")

def camera_get_sdcard_info():
    """cmd_id=0x71, Request SD card info."""
    return (0x02, 0x71, b"")

def camera_format_sdcard():
    """cmd_id=0x72, Format SD card."""
    return (0x02, 0x72, b"")

def parse_camera_state(payload: bytes) -> dict:
    """Decode cmd_id=0x80 push (Camera State Info). Requesting cmd_ids (system_state/
    sdcard_info above) moved to cmd_set=0x02 after the 2026-07-03 kprobe capture, whether the
    resulting push keeps cmd_set=0x01 or also moves to 0x02 is unconfirmed (no push observed
    yet), so callers should check for 0x80 on either cmd_set."""
    if len(payload) < 4:
        return {}
    out = {"mode": payload[0]}
    if len(payload) >= 2:
        out["recording"] = bool(payload[1] & 0x01)
        out["photo_busy"] = bool(payload[1] & 0x02)
    if len(payload) >= 3:
        out["sd_inserted"] = bool(payload[2] & 0x01)
        out["sd_error"] = bool(payload[2] & 0x02)
    return out

CAMERA_MODE_NAMES = {0: "Photo", 1: "Video", 2: "Playback", 3: "Download"}
ISO_NAMES = ["Auto","100","200","400","800","1600","3200","6400","12800"]
EXP_MODE_NAMES = ["Program/Auto","Shutter Priority","Aperture Priority","Manual"]
WB_NAMES = ["Auto","Sunny","Cloudy","Indoor","Fluorescent","Custom"]
COLOR_NAMES = ["None","Art","B&W","Bright","D-Log","Portrait","Vivid","Landscape"]
FLICKER_NAMES = ["Auto","50 Hz","60 Hz"]
FOCUS_NAMES = ["MF","AF","AFC"]
SHUTTER_NAMES = [
    "Auto","1/8000","1/6400","1/5000","1/4000","1/3200","1/2500","1/2000",
    "1/1600","1/1250","1/1000","1/800","1/640","1/500","1/400","1/320",
    "1/240","1/200","1/160","1/120","1/100","1/80","1/60","1/50","1/40",
    "1/30","1/25","1/20","1/15","1/12","1/10","1/8","1/6","1/5","1/4",
    "1/3","0.4","0.5","0.6","0.8","1s","1.3s","1.6s","2s","2.5s","3s",
    "4s","5s","6s","7s","8s",
]
APERTURE_NAMES = ["f/1.7","f/2.0","f/2.2","f/2.5","f/2.8","f/3.2","f/3.5",
                  "f/4.0","f/4.5","f/5.0","f/5.6","f/6.3","f/7.1","f/8.0",
                  "f/9.0","f/10","f/11"]

# ── Flight Controller (cmd_set 0x03) ─────────────────────────────────────────

def flyc_get_status():
    """cmd_id=0x01, FlyC Status Get."""
    return (0x03, 0x01, b"")

def flyc_emergency_stop():
    """
    cmd_id=0x0e, Emergency Stop.
    Kills motors immediately (if armed). Use with extreme caution.
    """
    return (0x03, 0x0e, b"")

def flyc_motor_ctrl(arm: bool):
    """
    cmd_id=0x0b, Motor Work Status Set.
    arm=True  → spin up motors (pre-arm)
    arm=False → disarm motors
    Payload is 1 byte: 0x01=arm, 0x00=disarm
    """
    return (0x03, 0x0b, bytes([0x01 if arm else 0x00]))

def flyc_set_home_point(lat: float, lon: float, alt: float = 0.0):
    """
    cmd_id=0x31, UAV Home Point Set.
    Coordinates in degrees * 1e7 (int32).
    """
    payload = struct.pack("<iiH",
                         int(lat * 1e7), int(lon * 1e7), int(alt * 10))
    return (0x03, 0x31, payload)

def flyc_get_device_info():
    """cmd_id=0x37, Device Info Get (SN, hardware version, etc)."""
    return (0x03, 0x37, b"")

def flyc_request_sn():
    """cmd_id=0x36, Request SN."""
    return (0x03, 0x36, b"")

def flyc_set_rc_lost_action(action: int):
    """
    cmd_id=0x3b, RC Lost Action Set (failsafe).
    0=Hover  1=Land  2=GoHome
    """
    return (0x03, 0x3b, bytes([action]))

def flyc_get_rc_lost_action():
    """cmd_id=0x3c, RC Lost Action Get."""
    return (0x03, 0x3c, b"")

def flyc_get_osd_data():
    """cmd_id=0x43, OSD General Data Get (same as telemetry broadcast)."""
    return (0x03, 0x43, b"")

def flyc_joystick(roll: float, pitch: float, throttle: float, yaw: float):
    """
    cmd_id=0x29, Virtual joystick input.
    All values -1.0 .. +1.0 (center = 0.0).
    Mapped to int16: -10000..10000.
    """
    def scale(v): return max(-10000, min(10000, int(v * 10000)))
    payload = struct.pack("<hhhh",
                         scale(roll), scale(pitch),
                         scale(throttle), scale(yaw))
    return (0x03, 0x29, payload)

def flyc_assistant_unlock(unlock: bool = True):
    """
    cmd_id=0xdf, Assistant Unlock Handler.
    Puts FC into 'assistant mode' which grants elevated access including
    ADB enablement on some firmware versions.
    payload byte: 0x01=unlock, 0x00=lock
    """
    return (0x03, 0xdf, bytes([0x01 if unlock else 0x00]))

def flyc_set_led(mode: int):
    """
    cmd_id=0xba, Forearm LED Status Set.
    0=Off  1=On  2=Flash
    """
    return (0x03, 0xba, bytes([mode]))

def flyc_get_battery_status():
    """cmd_id=0x51, FlyC Battery Status Get."""
    return (0x03, 0x51, b"")

def flyc_return_home():
    """cmd_id=0x0d, Go Home. Payload 0x01. UNVERIFIED for wm240, see warning below."""
    return (0x03, 0x0d, bytes([0x01]))

def flyc_function_control(cmd: int):
    """
    cmd_id=0x2a, FLYC Function Control (sets wm610_app_command.function_command).

    Undocumented upstream, dji-firmware-tools' own dissector (dji-dumlv1-flyc.lua)
    declares this payload an opaque raw byte with no enum attached, and no vendored
    research repo had a wm240-confirmed trigger table. The takeoff trigger (0x01) is now
    confirmed by direct capture; see flyc_auto_takeoff(). Other trigger values under this
    same cmd_id remain unconfirmed.
    """
    return (0x03, 0x2a, bytes([cmd & 0xff]))

def flyc_auto_takeoff():
    """
    CONFIRMED via kprobe capture of a genuine DJI GO 4 v4.3.64 takeoff over the same AOA
    link (2026-07-02): real payload is 0x01, not the earlier 0x0b guess (which was
    pattern-matched from unrelated OSD telemetry-readback enums, a category error, not
    just a wrong-generation opcode). Verified end-to-end: FC accepted the frame and
    engaged the motors (caught by the aircraft's own no-propeller safety check in the
    props-off test this was captured during).
    """
    return flyc_function_control(0x01)

def flyc_auto_land():
    """
    UNVERIFIED, 0x0c is still an unconfirmed guess. The takeoff capture also caught one
    0x03/0xfe (payload 0x00) about 11.6s after the takeoff trigger, possibly a stop/cancel
    the app sent after the no-prop warning, but that's not enough evidence to claim it's
    the land trigger. Needs the same kprobe-capture treatment as takeoff before trusting it.
    """
    return flyc_function_control(0x0c)

def flyc_activation_info(app_id: int = 0, app_level: int = 0, app_version: int = 0):
    """
    cmd_id=0x61, FlyC Activation Info. Payload: app_id/app_level/app_version, u32 LE each.
    Documented in dji-dumlv1-flyc.lua but never exercised by this SDK. Firmware tracks
    "Product not activation, stop motor" as an explicit motor-start failure reason, so
    lack of activation is a real gating condition in this firmware family
    (generation-unconfirmed for wm240). Worth trying before flyc_function_control() in
    the next bench session. app_id/app_level/app_version are unknown for this project, 
    0 is a placeholder, not a verified value.
    """
    return (0x03, 0x61, struct.pack("<III", app_id, app_level, app_version))

def flyc_activation_exec(app_id: int = 0, app_level: int = 0, app_version: int = 0):
    """cmd_id=0x62, FlyC Activation Exec. Same payload shape as flyc_activation_info()."""
    return (0x03, 0x62, struct.pack("<III", app_id, app_level, app_version))

# ── Config table by-hash (0x03/0xf8 read, 0x03/0xf9 write) ──────────────────────
# DJI's own generic FC-parameter tuning pathway (same one DJI Assistant 2 uses), fully
# byte-level dissected upstream (dji-dumlv1-flyc.lua), HIGH confidence on the framing here.
# What's NOT confirmed: the exact value size/type for any given hash ("depends on parameter",
# undocumented per-hash), read a hash back first and inspect the returned byte count before
# trusting a write. GlassFalcon never enforces distance/height/speed limits itself; this exists
# to read and raise the aircraft's OWN limit, not to add a client-side cap of any kind.
#
# Request:  hash u32 LE                          (write also appends value bytes)
# Response: status u8, hash u32 LE, value bytes

PARAM_HASH_MAX_HEIGHT = 0x0371238a            # g_config.flying_limit.max_height_0
PARAM_HASH_MAX_RADIUS = 0x425c0a94            # g_config.flying_limit.max_radius_0
PARAM_HASH_MIN_HEIGHT = 0x0438298a            # g_config.flying_limit.min_height_0
PARAM_HASH_HEIGHT_LIMIT_ENABLED = 0xae52d19a  # g_config.advanced_function.height_limit_enabled_0
PARAM_HASH_RADIUS_LIMIT_ENABLED = 0x7ece6d19  # g_config.advanced_function.radius_limit_enabled_0
PARAM_HASH_NOVICE_MAX_HEIGHT = 0xd9ab9f79     # g_config.novice_cfg.max_height_0
PARAM_HASH_NOVICE_MAX_RADIUS = 0x18968688     # g_config.novice_cfg.max_radius_0

def flyc_read_param_by_hash(name_hash: int):
    """cmd_id=0xf8, Config Table Read Param By Hash."""
    return (0x03, 0xf8, struct.pack("<I", name_hash))

def flyc_write_param_by_hash(name_hash: int, value: bytes):
    """cmd_id=0xf9, Config Table Write Param By Hash. `value`'s size/type depends on the
    parameter and is not documented per-hash, confirm via flyc_read_param_by_hash() first."""
    return (0x03, 0xf9, struct.pack("<I", name_hash) + value)

def parse_param_by_hash(payload: bytes) -> dict:
    """Decode a 0xf8/0xf9 response: {status, hash, value (raw bytes)}."""
    if len(payload) < 5:
        return {"status": payload[0] if payload else None, "raw": payload.hex()}
    status, name_hash = struct.unpack_from("<BI", payload, 0)
    return {"status": status, "hash": hex(name_hash), "value": payload[5:], "raw": payload.hex()}

def parse_flyc_status(payload: bytes) -> dict:
    """Decode cmd_set=0x03 cmd_id=0x01 response."""
    if len(payload) < 1:
        return {}
    return {"status_byte": payload[0], "raw": payload.hex()}

def parse_device_info(payload: bytes) -> dict:
    if len(payload) < 20:
        return {"raw": payload.hex()}
    sn = payload[:10].rstrip(b"\x00").decode("ascii", "replace")
    return {"serial": sn, "raw": payload.hex()}

RC_LOST_NAMES = ["Hover", "Land", "Go Home"]

# ── RC (cmd_set 0x06) ────────────────────────────────────────────────────────

def rc_push_buzzer(pattern: int = 1):
    """cmd_id=0xf7, RC Push Buzzer To MCU. UNVERIFIED/EXPERIMENTAL, not kprobe-confirmed
    like flyc_auto_takeoff() was. Lead comes from a community dissector's RC_UART_CMD_TEXT
    table (cmd_set 0x06 = "RC", matching this SDK's convention that a module's cmd_set
    number equals its dst address); no documented payload layout, so `pattern` is a guess
    at a single-byte tone selector. Low risk to try, worst case is silence or the wrong
    tone, not a flight-safety issue, so this ships experimental rather than waiting on a
    capture. Pass dst=0x06 to send_cmd(), RC, not the flight controller default (0x03)."""
    return (0x06, 0xf7, bytes([pattern]))

# ── Gimbal (cmd_set 0x04) ────────────────────────────────────────────────────

def gimbal_get_position():
    """cmd_id=0x02, Get current gimbal position (pitch/roll/yaw)."""
    return (0x04, 0x02, b"")

def gimbal_abs_angle(pitch_deg: float, roll_deg: float = 0.0,
                     yaw_deg: float = 0.0, time_ds: int = 5):
    """
    cmd_id=0x0a, Gimbal Ext Ctrl Degree (absolute angle set).
    Angles in degrees. time_ds = move duration, 0.1s units (range ~1..10).
    Pitch range: -90 (down) to +30 (up).

    Layout per dji-firmware-tools (fields it lists as Unknown0/2/4/6/8/9) is
    10 bytes: three int16 angles (deg*10), one int16 reserved (*100), a uint8
    flag byte, and a uint8 duration. The previous 8-byte packing dropped the
    reserved+flag fields, so time/flags bled into the angle fields and the
    gimbal moved erratically. Axis order (pitch/roll/yaw) is unverified.
    """
    payload = struct.pack("<hhhhBB",
                         int(pitch_deg * 10),
                         int(roll_deg  * 10),
                         int(yaw_deg   * 10),
                         0,                 # reserved (unknown *100)
                         0x00,              # flag fields
                         max(1, min(10, time_ds)))
    return (0x04, 0x0a, payload)

def gimbal_speed(pitch_rate: int = 0, roll_rate: int = 0, yaw_rate: int = 0):
    """
    cmd_id=0x0c, Gimbal Ext Ctrl Accel (speed/rate control).
    Rates are signed int16, units = 0.1 deg/s. Range approx -3600..3600.
    """
    payload = struct.pack("<hhh",
                         int(pitch_rate), int(roll_rate), int(yaw_rate))
    return (0x04, 0x0c, payload)

def gimbal_calibrate():
    """cmd_id=0x08, Gimbal Calibration (auto-calibrate)."""
    return (0x04, 0x08, b"")

def gimbal_set_mode(mode: int):
    """
    cmd_id=0x0d, Gimbal Suspend/Resume.
    0=Suspend  1=Resume
    Also used to set follow mode via cmd_id=0x4c:
    Set mode via pitch push 0x05 params.
    """
    return (0x04, 0x0d, bytes([mode]))

def gimbal_lock(locked: bool):
    """cmd_id=0x39, Gimbal Lock toggle."""
    return (0x04, 0x39, bytes([0x01 if locked else 0x00]))

def gimbal_get_temp():
    """cmd_id=0x45, Gimbal Get Temperature."""
    return (0x04, 0x45, b"")

def gimbal_reset_and_set_mode(mode: int):
    """
    cmd_id=0x4c, Gimbal Reset And Set Mode.
    0=YawNoFollow  1=FPV  2=YawFollow
    """
    return (0x04, 0x4c, bytes([mode]))

def parse_gimbal_params(payload: bytes) -> dict:
    """Decode cmd_set=0x04 cmd_id=0x05 push (Gimbal Params)."""
    if len(payload) < 6:
        return {}
    pitch = struct.unpack_from("<h", payload, 0)[0] * 0.1
    roll  = struct.unpack_from("<h", payload, 2)[0] * 0.1
    yaw   = struct.unpack_from("<h", payload, 4)[0] * 0.1
    out = {"pitch": pitch, "roll": roll, "yaw": yaw}
    if len(payload) >= 7:
        mode_byte = payload[6]
        mode_map = {0x00: "YawNoFollow", 0x40: "FPV", 0x80: "YawFollow", 0xc0: "AutoCalibrate"}
        out["mode"] = mode_map.get(mode_byte & 0xc0, f"0x{mode_byte:02x}")
        out["stuck"] = bool(mode_byte & 0x40)
        out["calib"] = bool(mode_byte & 0x08)
    return out

GIMBAL_MODE_NAMES = ["YawNoFollow", "FPV", "YawFollow"]

# ── HDVT / OcuSync 2.0 (device 0x09 "DJI P1 HDVT", cmd_set 0x09) ─────────
# Discovered via live DUML device scan on wm240 serial (ttyACM0).
# cmd_set=0x09 is the OcuSync 2.0 link control command set.

def hdvt_get_link_status():
    """
    cmd_set=0x09, cmd_id=0x21, OcuSync 2.0 link status.
    Returns 25 bytes: [status(1)] [unknown(1)] [bitrate?(1)] [rssi?(1)]
                      [channel?(1)] [link_quality?(1)] [zeros(19)]
    Accessible without MSDK auth.
    """
    return (0x09, 0x21, b"")

def hdvt_get_country_code():
    """
    cmd_set=0x07, cmd_id=0x19, OcuSync 2.0 country code.
    Returns 3 bytes: [status(1)] [cc[0](1)] [cc[1](1)]  e.g. 0x00 0x55 0x53 = "US"
    Accessible without MSDK auth (empty payload only).
    """
    return (0x07, 0x19, b"")

def parse_hdvt_link_status(payload: bytes) -> dict:
    """Decode cmd_set=0x09, cmd_id=0x21 response from HDVT module."""
    if len(payload) < 6:
        return {"raw": payload.hex()}
    return {
        "status":        payload[0],
        "unknown1":      payload[1],
        "bitrate_idx":   payload[2],
        "rssi_raw":      payload[3],   # 0xf2 observed; encoding TBD
        "channel":       payload[4],
        "link_quality":  payload[5],
        "raw":           payload.hex(),
    }

def parse_hdvt_country_code(payload: bytes) -> str:
    """Decode cmd_set=0x07, cmd_id=0x19 response (empty payload only)."""
    if len(payload) < 3 or payload[0] != 0x00:
        return "??"
    return "".join(chr(b) for b in payload[1:3] if 32 <= b < 127)

# ── Aircraft Computer (device 0x28 "WM240 AC Ver.A") ─────────────────────────

def ac_ping():
    """cmd_set=0x00, cmd_id=0x00, ping the main aircraft computer."""
    return (0x00, 0x00, b"")

# ── Battery (device 0x0b "BA01WM240BAT") ─────────────────────────────────────

def batt_get_status():
    """cmd_set=0x03, cmd_id=0x51, battery status from the battery module directly."""
    return (0x03, 0x51, b"")
