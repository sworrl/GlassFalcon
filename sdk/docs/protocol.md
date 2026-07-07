# GlassFalcon SDK Protocol Reference

Complete technical reference for the DJI DUML protocol, commands, error codes, data models, and telemetry structures extracted from DJI GO 4 v4.3.64 (wm240/Mavic 2 firmware v01.00.0790).

**Last Updated:** 2026-07-06  
**Firmware Tested:** FC v01.00.0790 / RC v01.00.0770  
**Data Extraction:** From decompiled GO4 APK via JADX

---

## Table of Contents

1. [DUML Protocol Frame Format](#duml-protocol-frame-format)
2. [Module ID Map](#module-id-map)
3. [Command Sets Overview](#command-sets-overview)
4. [Critical Commands by Category](#critical-commands-by-category)
5. [Telemetry Data Structures](#telemetry-data-structures)
6. [Error Codes](#error-codes)
7. [Data Models](#data-models)
8. [ADS-B / Remote ID](#ads-b--remote-id)
9. [Known Findings & Implementation Status](#known-findings--implementation-status)

---

## DUML Protocol Frame Format

DUML (DJI Universal Markup Language) is the framed command/response protocol spoken over all transports (USB CDC-ACM, OcuSync downlink, UART). The GlassFalcon SDK implements it in [`glassfalcon/duml.py`](../python/src/glassfalcon/duml.py).

### Frame Layout

```
 0      1      2      3      4    5     6   7    8         9        10      11..n-2   n-2..n
+------+------+------+------+----+----+--------+---------+--------+--------+--------+--------+
| 0x55 | len  | ver/ | CRC8 |src |dst |  seq   | cmd_type| cmd_set| cmd_id | payload| CRC16  |
|      | [7:0]| len  |      |    |    | (LE16) |         |        |        |        | (LE16) |
+------+------+------+------+----+----+--------+---------+--------+--------+--------+--------+
```

| Field | Bytes | Notes |
|---|---|---|
| magic | 1 | Always `0x55` (frame marker) |
| length | 1 + 2 low bits of byte 2 | Total frame length (10 bits), includes header through CRC16 |
| version | high 6 bits of byte 2 | Protocol version (1 for wm240) |
| CRC8 | 1 | Over bytes `[0:3]`, **seed `0x77`** |
| src | 1 | Source module ID (usually `0x02` for mobile app) |
| dst | 1 | Destination module ID (flight controller, camera, gimbal, etc.) |
| seq | 2 (LE) | Sequence number, wraps at `0xFFFF` |
| cmd_type | 1 | `0x40` = request (REQ), `0x80` = ack/response (ACK) |
| cmd_set | 1 | Command group (e.g., `0x03` for flight controller) |
| cmd_id | 1 | Command within group |
| payload | 0..n | Command-specific data |
| CRC16 | 2 (LE) | Over whole frame minus CRC, **seed `0x3692`** |

Build and parse with `build_packet(...)` / `parse_frame(buf) -> (frame|None, consumed)`.  
`DUMLConnection` handles framing, sequencing, and threaded receive.

---

## Module ID Map

| ID | Module | Identifier String | Purpose |
|---|---|---|---|
| `0x01` | Camera | WM240 (Ambarella H2) | Photo/video/settings |
| `0x02` | Mobile App | **us** | SDK control/telemetry client |
| `0x03` | Flight Controller | 163DF7 (fw 24a0714) | Flight, attitude, limits |
| `0x04` | Gimbal | GB11 | Camera mount control |
| `0x09` | HD Video Transmission | DJI P1 HDVT (OcuSync 2.0) | Video downlink |
| `0x0a` | Ground Assistant (Desktop) | DJI Assistant 2 | Legacy desktop tool |
| `0x0b` | Battery | BA01WM240BAT | Smart battery telemetry |
| `0x0c` | Motor ESCs | WM240_ESC_V7 (4-in-1) | Motor control |
| `0x12` | Obstacle Avoidance (ToF) | WM240_TOF_v2 | Forward/backward distance |
| `0x28` | Aircraft Computer | WM240 AC Ver.A | System coordination |

**Default routing:** Requests from app to flight controller use `src=0x02, dst=0x03, cmd_type=0x40`.  
`DUMLConnection.send_cmd()` defaults to this; override `dst=0x01` for camera, `dst=0x04` for gimbal.

---

## Command Sets Overview

**Total:** 30 command sets, 680+ commands across wm240 platform.

| cmd_set | Name | Hex | Count | Purpose | Notes |
|---|---|---|---|---|---|
| 0x00 | COMMON | 0x00 | 52 | Device management, firmware, licensing | Ping, upgrade, device info |
| 0x01 | SPECIAL | 0x01 | 4 | Legacy control (pre-v2 joystick) | Control, JoyStick, LockRcControl |
| **0x02** | **CAMERA** | **0x02** | **126** | **Photo, video, settings, state** | **Mode, ISO, shutter, focus, zoom, WB** |
| **0x03** | **FLYC** | **0x03** | **90** | **Flight control, parameters, limits** | **Takeoff, land, params, geofence** |
| **0x04** | **GIMBAL** | **0x04** | **35** | **Camera gimbal control** | **Angle, speed, calibration, mode** |
| 0x05 | CENTER | 0x05 | 12 | Central coordination | System-level control |
| **0x06** | **RC** | **0x06** | **70** | **Remote control mapping** | **Stick input, button mapping, custom** |
| 0x07 | WIFI | 0x07 | 32 | Wi-Fi settings | Frequency, band, connection |
| **0x09** | **OSD** | **0x09** | **64** | **Telemetry push streams** | **Position, velocity, attitude, battery** |
| **0x0A** | **EYE** | **0x0A** | **73** | **Vision/obstacle detection** | **Avoidance, ranging, tracking** |
| 0x0B | SIMULATOR | 0x0B | 20 | Flight simulator | Test harness |
| **0x0C** | **BATTERY** | **0x0C** | **18** | **Battery management** | **Voltage, temp, health** |
| **0x0D** | **SMARTBATTERY** | **0x0D** | **18** | **Smart battery (latest)** | **Current, capacity, charge cycles** |
| 0x0F | RTK | 0x0F | 30 | Real-time kinematic positioning | Advanced navigation |
| **0x11** | **ADS_B/HMS** | **0x11** | **14** | **Traffic avoidance, device auth** | **Aircraft detection, device handshake** |
| 0x14 | MASS_CENTER_CALI_STATUS | 0x14 | 2 | Center of mass calibration | Aircraft balance |
| 0x21 | HMS | 0x21 | 5 | Hardware management system | Health monitoring |
| 0x23 | SMART_FUNCTION | 0x23 | 2 | Intelligent flight modes | ActiveTrack, gesture, etc. |
| 0x24 | PERCEPTION | 0x24 | 8 | Advanced vision | AI object detection |
| 0x32 | LIDAR | 0x32 | 2 | LiDAR ranging | Range sensing |
| 0xEE | RECOGNIZE | 0xEE | 1 | Object recognition | Computer vision |

**Bold entries** = high-priority for GlassFalcon implementation.

---

## Critical Commands by Category

### Flight Control (FLYC, cmd_set 0x03)

**Total: 90 commands** — Flight arm/disarm, movement control, parameter tuning, geofencing, waypoint missions.

| cmd_id | Name | Payload | Status | Purpose |
|---|---|---|---|---|
| **0x01** | **AutoTakeoff** | `0x01` | ✅ Confirmed | Arm motors, take off to 1.2m hover |
| **0x0C** | **AutoLand** | ? | ⚠️ Unconfirmed | Auto-landing sequence |
| 0x2A | FunctionControl | Complex | ✅ Used for takeoff/landing | Motor arm, emergency stop |
| **0x06** | Joystick | 11 bytes | ✅ Implemented | Analog stick control (pitch/roll/throttle/yaw) |
| **0xF8** | GetParamByHash | 4-byte hash | ✅ Implemented | Read FC parameter by name hash |
| **0xF9** | SetParamByHash | 4-byte hash + value | ✅ Implemented | Write FC parameter by name hash |
| 0x03 | GetParamInfo | 4-byte hash | ✅ Confirmed | Retrieve parameter metadata (0x03/0xf7 in old naming) |
| 0x31 | SetHomePoint | lat/lon/alt | ⚠️ Relocks 30m cap | **DO NOT SEND** — aircraft auto-records home |
| 0x2E | GetPushForbidStatus | - | ✅ Implemented | Receive geofence/flight restriction push data |
| 0x10 | UploadWayPointMission | 74-byte+ | ✅ Implemented | Submit waypoint mission (speed, gimbal, action) |

**Key Parameter Hashes:**
- `MAX_HEIGHT` = `0x0371238a` (max altitude, value type: u16 LE meters)
- `MAX_RADIUS` = `0x425c0a94` (max distance, value type: u16 LE meters)
- `MIN_HEIGHT` = `0x0438298a` (min altitude)
- `HEIGHT_LIMIT_ENABLED` = `0xae52d19a`
- `RADIUS_LIMIT_ENABLED` = `0x7ece6d19`
- `BEGINNER_MODE_ENABLE` = related to novice caps

### Telemetry Push (OSD, cmd_set 0x09)

**Total: 64 commands** — Real-time flight state broadcast at ~10 Hz (and slower for home/battery).

| cmd_id | Name | Size | GlassFalcon | Purpose |
|---|---|---|---|---|
| **0x43** | **OSD General** | **42+ bytes** | ✅ Fully captured | Position, velocity, attitude, battery, state |
| **0x42** | **OSD Home Point** | **34 bytes** | ❌ Missing | Home location, RTH status, go-home height |
| 0x01-0x41 | Other OSD fields | Variable | ⚠️ Partial | Wind, temperature, other environmental data |

### Camera Control (CAMERA, cmd_set 0x02)

**Total: 126 commands** — Photo, video, ISO, exposure, focus, zoom, format, transfer.

| cmd_id | Name | Payload | Status | Purpose |
|---|---|---|---|---|
| **0x10** | SetPhotoMode | `0x01`/`0x00` | ✅ Confirmed via kprobe | Switch photo/video mode |
| **0x24** | SetFocusMode | `0x02`/`0x00` | ✅ Confirmed via kprobe | Auto-focus / Manual focus |
| **0x68** | AELock | `0x01` | ✅ Confirmed via kprobe | Exposure lock toggle |
| **0x01** | CapturePhoto | - | ✅ Implemented | Trigger single photo |
| **0x02** | StartRecord | - | ✅ Implemented | Begin video recording |
| **0x03** | StopRecord | - | ✅ Implemented | Stop recording |
| 0x04-0x0A | ISO, Shutter, EV, WB | Enum/value | ⚠️ Implemented | Camera exposure settings |
| 0x65-0x6A | Zoom, focus distance | Float/u16 | ⚠️ Implemented | Gimbal/lens control |

**CRITICAL FIX (2026-07-03):** Mode, focus, AE-lock, ISO, shutter all use **cmd_set=0x02**, NOT 0x01. The community dissector and earlier GlassFalcon versions confused module ID (0x01 = camera device) with cmd_set (0x02 = CAMERA command group). This explains prior `0xe0` ("rejected") responses on those commands.

### Gimbal Control (GIMBAL, cmd_set 0x04)

**Total: 35 commands** — Camera mount pitch/roll/yaw, stabilization, speed control.

| cmd_id | Name | Payload | Status | Purpose |
|---|---|---|---|---|
| **0x0C** | SpeedControl | pitch/roll/yaw rate | ✅ Implemented | Move gimbal by rate (preferred) |
| **0x05** | AbsoluteControl | pitch/roll/yaw angle | ⚠️ Ignored on wm240 | Move gimbal to absolute angle |
| 0x07 | ResetMode | Axis mask | ✅ Implemented | Reset to default position |
| 0x08 | SetMode | Mode enum | ✅ Implemented | FREE, FPV, TRACK |

### Remote Control (RC, cmd_set 0x06)

**Total: 70 commands** — RC240/RC210 button mapping, stick calibration, settings.

| cmd_id | Name | Payload | Status | Purpose |
|---|---|---|---|---|
| **0x51** | PushToGlass | 1-byte flags | ✅ Confirmed live | RC button/dial state (7 bits mapped 1:1) |
| 0x02-0x0A | CalibrateStick | - | ✅ Implemented | Joystick centering/scale |
| 0xF7 | PushBuzzer | 1 byte? | ⚠️ Guessed | RC beep on takeoff/land |

**RC240 Button Map (cmd_set=0x06, cmd_id=0x51):**
- `0x40` (bit 6): Left trigger (C1)
- `0x20` (bit 5): Right trigger (C2)
- `0x01` (bit 0): 5-way dial UP
- `0x02` (bit 1): 5-way dial DOWN
- `0x08` (bit 3): 5-way dial LEFT
- `0x10` (bit 4): 5-way dial RIGHT
- `0x04` (bit 2): 5-way dial CENTER/click

All 7 bits distinct, fully mapped.

---

## Telemetry Data Structures

### OSD General Data (0x09/0x43) — Main Flight Telemetry

**Broadcast Frequency:** ~10 Hz  
**Total Size:** 42+ bytes  
**Status:** ✅ **FULLY CAPTURED in GlassFalcon**

Complete byte layout (wm240/Mavic 2, confirmed by GlassFalcon + dji-firmware-tools):

| Offset | Size | Type | Name | Units | Notes |
|--------|------|------|------|-------|-------|
| 0-7 | 8 | f64 LE | Longitude | radians | Convert to degrees via Math.toDegrees() |
| 8-15 | 8 | f64 LE | Latitude | radians | Convert to degrees via Math.toDegrees() |
| 16-17 | 2 | i16 LE | Height (altitude relative to ground) | 0.1m | e.g., 0x0064 = 10.0m |
| 18-19 | 2 | i16 LE | Velocity X (North) | 0.1 m/s | East-North-Down (ENU) frame |
| 20-21 | 2 | i16 LE | Velocity Y (East) | 0.1 m/s | |
| 22-23 | 2 | i16 LE | Velocity Z (Down) | 0.1 m/s | Positive = downward motion |
| 24-25 | 2 | i16 LE | Pitch angle | 0.1° | Nose up = positive |
| 26-27 | 2 | i16 LE | Roll angle | 0.1° | Right wing down = positive |
| 28-29 | 2 | i16 LE | Yaw angle | 0.1° | CW from North = positive |
| 30 | 1 | u8 | Flight State / RC Connection | - | Bits 0-6 = FLYC state (0x7f mask); Bit 7 = no RC (0x80 when disconnected) |
| 31 | 1 | u8 | Latest Command | - | Which device last commanded flight (RC/app/etc) |
| 32-35 | 4 | u32 LE | controller_state (status flags) | bitfield | See flags table below |
| 36 | 1 | u8 | GPS satellite count | count | 0-40; 0 = no fix |
| 37 | 1 | u8 | Reserved / padding | - | |
| 38 | 1 | u8 | Motor start failure reason | enum | Low 7 bits (0x7f); 0 = allow start; non-zero = block reason |
| 39 | 1 | u8 | Reserved / padding | - | |
| 40 | 1 | u8 | Battery percentage | % | 0-100%; 0 on ground until synced from smart battery |
| 41+ | Variable | - | Additional fields | - | Flight time and extended telemetry (not fully mapped) |

**controller_state Flags (offset 32-35, u32 LE):**

| Bit Mask | Flag | Meaning |
|----------|------|---------|
| 0x02 | on_ground | Aircraft is resting on the ground |
| 0x04 | in_air | Aircraft is airborne |
| 0x08 | motor_on | Motors are powered/spinning |
| 0x200 | wind_angle_warn_maybe | Strong wind or excessive attitude (tentative) |
| 0x400 | battery_request_landing | Battery low; landing required |
| 0x8000 | gps_used | GPS is primary velocity sensor (not IMU-only) |
| 0x3C0000 | gps_signal_level | GPS signal quality (~0-15 satellites) |
| 0x20000 | ultrasonic_error | Downward rangefinder malfunction |
| 0x4000000 | barometer_error | Barometer/pressure sensor failed |
| 0x8000000 | esc_stall | ESC reports motor blocked |
| 0x10000000 | esc_insufficient_force | ESC can't produce enough thrust |

**Motor Start Failure Reasons (byte 38, low 7 bits):**

| Value | Reason | Resolution |
|-------|--------|-----------|
| 0x00 | None / allowed | Ready to take off |
| 0x01 | IMU calibration needed | Run IMU calibration |
| 0x02 | Compass error | Recalibrate compass |
| 0x03 | Obstacle/vision error | Clear obstacles, restart vision |
| 0x04 | Battery error | Check battery health |
| 0x05-0x7F | Other FC failure | Restart aircraft |

**GlassFalcon Implementation:**  
✅ Position (lat/lon), altitude  
✅ Velocity (vx/vy/vz)  
✅ Attitude (pitch/roll/yaw)  
✅ Flight state  
✅ All controller_state flags (except 0x1000 IMU preheating, now disabled)  
✅ Battery %  
✅ GPS satellite count  
✅ Motor start failure reason  
**File location:** `/android/sdk/src/main/kotlin/dev/glassfalcon/core/Telemetry.kt:326-369`

---

### OSD Home Point Data (0x09/0x42) — RTH & System Status

**Broadcast Frequency:** ~2 Hz  
**Total Size:** 34 bytes (new fw) or 68 bytes (old fw)  
**Status:** ❌ **NOT CAPTURED**

| Offset | Size | Type | Name | Units | Notes |
|--------|------|------|------|-------|-------|
| 0-7 | 8 | f64 LE | Home Longitude | radians | Home point longitude; convert to degrees |
| 8-15 | 8 | f64 LE | Home Latitude | radians | Home point latitude; convert to degrees |
| 16-19 | 4 | f32 LE | Home Altitude | 0.1m | Height of home point above sea level |
| 20-21 | 2 | u16 LE | Home State (flags) | bitfield | See home state flags below |
| 22-23 | 2 | u16 LE | Go-Home Height | m | Altitude to climb to before returning home |
| 24-25 | 2 | u16 LE | Course Lock Angle | 0.1° | Heading lock for course lock mode |
| 26 | 1 | u8 | Data Recorder Status | flags | Recording status / SD card state |
| 27 | 1 | u8 | Data Recorder Remain Capacity | % | Free space on data recorder |
| 28-29 | 2 | u16 LE | Data Recorder Remain Time | seconds | Recording time remaining |
| 30-31 | 2 | u16 LE | Current Data Recorder File Index | - | Current file being recorded |
| 32-33 | 2 | u16 LE | Masked Flags (new fw) | flags | Simulator mode, navigation mode, etc. |

**High-Priority Missing Fields for HUD:**
- Home location (lat/lon/alt) → enables home marker on map, bearing/distance calculation
- Return-to-home status → show if RTH is armed, in progress, or completed
- Go-home height setting → display target RTH altitude
- Beginner mode flag → indicate if learner mode active

---

### Smart Battery Dynamic Data (0x0d/0x02) — Battery Status

**Broadcast Frequency:** ~2 Hz  
**Total Size:** 21 bytes  
**Status:** ✅ **PARTIAL (voltage, SOC, temp captured; missing current, capacity)**

| Offset | Size | Type | Name | Units | Notes |
|--------|------|------|------|-------|-------|
| 0 | 1 | u8 | Pack Index | - | Which battery in multi-battery system |
| 1-4 | 4 | u32 LE | Pack Voltage | mV | 1-30000; e.g., 13200 = 13.2V |
| 5-8 | 4 | i32 LE | Current | mA | Signed; negative = charging, positive = discharging |
| 9-12 | 4 | u32 LE | Full Capacity | mAh | Total capacity when fully charged |
| 13-16 | 4 | u32 LE | Remaining Capacity | mAh | Current usable capacity |
| 17-18 | 2 | i16 LE | Temperature | 0.1°C | Signed; e.g., 0x0119 = 28.1°C |
| 19 | 1 | u8 | Cell Count | - | Number of cells in pack (typically 6 for Mavic 2) |
| 20 | 1 | u8 | State of Charge | % | Battery percentage (1-100%) |

**GlassFalcon Captures:** ✅ Voltage, SOC, Temperature  
**Missing:** ❌ Current (discharge rate in mA), Full capacity, Remaining capacity, Cell count

**File location:** `/android/sdk/src/main/kotlin/dev/glassfalcon/core/Telemetry.kt:371-385`

---

### Gimbal Status (0x04/0x05) — Camera Mount

**Broadcast Frequency:** ~10 Hz  
**Total Size:** 15 bytes  
**Status:** ✅ **FULLY CAPTURED**

| Offset | Size | Type | Name | Units | Notes |
|--------|------|------|------|-------|-------|
| 0-1 | 2 | i16 LE | Pitch angle | 0.1° | Camera tilt (-90° to +90°) |
| 2-3 | 2 | i16 LE | Roll angle | 0.1° | Camera roll (-45° to +45°) |
| 4-5 | 2 | i16 LE | Yaw angle | 0.1° | Camera pan (-180° to +180°) |
| 6-7 | 2 | u16 LE | Focus distance | cm | Distance to focus point |
| 8-9 | 2 | i16 LE | Pitch speed | °/s | Current pitch rate |
| 10-11 | 2 | i16 LE | Roll speed | °/s | Current roll rate |
| 12-13 | 2 | i16 LE | Yaw speed | °/s | Current yaw rate |
| 14 | 1 | u8 | Status flags | bitfield | Stabilization, lock, error flags |

---

### Vision/Obstacle Distance (0x09/0x6a) — ToF Ranging

**Broadcast Frequency:** ~10 Hz  
**Total Size:** 13 bytes  
**Status:** ✅ **FULLY CAPTURED**

| Offset | Size | Type | Name | Units | Notes |
|--------|------|------|------|-------|-------|
| 0 | 1 | u8 | Header | - | Always 0x01 |
| 1-2 | 2 | u16 LE | Front distance (channel A) | cm | Forward obstacle distance |
| 3-4 | 2 | u16 LE | Unused second beam | - | Permanently open on wm240 |
| 5-6 | 2 | u16 LE | Rear distance (channel B) | cm | Backward obstacle distance |
| 7-8 | 2 | u16 LE | Unused second beam | - | Permanently open on wm240 |
| 9-10 | 2 | u16 LE | Left distance (channel C) | cm | Lateral left distance (low-speed only) |
| 11-12 | 2 | u16 LE | Right distance (channel D) | cm | Lateral right distance (low-speed only) |

Front and rear always populated; left/right only in ActiveTrack/low-speed modes.

---

### Camera State (0x02/0x80) — Camera Settings & Status

**Broadcast Frequency:** ~2 Hz  
**Total Size:** 132+ bytes  
**Status:** ⚠️ **PARTIAL (mode captured; missing focus, zoom, WB, ISO, shutter details)**

Largest OSD/telemetry model in GO4 (157 fields in DataCameraGetPushShotParams). Covers photo mode, video format, frame rate, ISO, exposure time, aperture, white balance, focus mode, zoom level, SD card info.

---

### RC Controller State (0x06/0x51) — Remote Control Input

**Broadcast Frequency:** Event-driven (on button press/dial change)  
**Total Size:** 1 byte (status) + variable  
**Status:** ✅ **Button map confirmed live**

7-bit status byte carries all learnable RC240 controls (see [Remote Control](#remote-control-rc-cmd_set-0x06) section above).

---

### ADS-B / Traffic Detection (0x11/0x02) — Aircraft Detection

**Broadcast Frequency:** ~1 Hz per aircraft  
**Total Size:** 38+ bytes per aircraft  
**Status:** ⚠️ **Data model known; decoder not implemented**

| Field | Type | Units | Notes |
|-------|------|-------|-------|
| ICAO Address | String | 24-bit hex | Unique aircraft identifier |
| Callsign | String | 8 chars | Flight callsign/registration |
| Latitude | double | WGS-84 degrees | Aircraft position |
| Longitude | double | WGS-84 degrees | Aircraft position |
| Altitude | float | meters | Height from pressure or GNSS |
| Heading | float | degrees | Aircraft track (0-359°) |
| Horizontal Speed | float | m/s | Ground speed |
| Vertical Speed | float | m/s | Climb/descent rate |
| NIC | int | 0-11 | Navigation Integrity Category |
| NACP | int | 0-11 | Navigation Accuracy Category |
| Height Source | enum | PRESSURE / GNSS / OTHER | Altitude measurement source |
| Info Source | enum | ES1090 / UAT / OTHER | 1090 MHz ES or 978 MHz UAT |

---

## Error Codes

**Total:** 709 unique error codes across all subsystems.

### General / Success Codes (0x00-0x0F)

| Code | Name | Meaning |
|------|------|---------|
| 0x00 | OK | Operation successful |
| 0x01 | SUCCEED | Successful completion |
| 0x03 | BATTERY_NOT_READY | Battery initialization not complete |
| 0x06 | UPGRADE_SUCCESS_WAIT | Upgrade successful, waiting for status |
| 0x08 | CMD_IS_RUNNING | Command already executing (prevents duplicates) |

### Flight Controller Errors

| Code | Name | Meaning | Resolution |
|------|------|---------|-----------|
| 0x10 | IMU_NEED_CALI | IMU requires calibration | Run calibration routine |
| 0x11 | ERROR_CANNOT_START_MOTOR | Motor startup failed | Check propellers, battery, IMU |
| 0x15 | IMU_WARNING | IMU performance degraded | Restart aircraft |
| 0x80 | NOGPS | No GPS signal | Move outdoors, wait for fix |
| 0xF9 | UPDATE_MOTOR_WORKING | Cannot update while motors running | Land and disarm first |

### Camera / SD Card Errors

| Code | Name | Meaning | Resolution |
|------|------|---------|-----------|
| 0xE8 | SDCARD_NOT_INSERTED | SD card not present | Insert SD card |
| 0xE9 | SDCARD_FULL | SD card storage full | Format or replace SD card |
| 0xEA | SDCARD_ERR | SD card read/write error | Try different card/slot |
| 0xEB | SENSOR_ERR | Camera sensor malfunction | Restart or repair camera |
| 0xEC | CAMERA_CRITICAL_ERR | Fatal camera error | Contact DJI support |
| 0xFE | UPDATE_NOCONNECT_CAMERA | Cannot update without camera | Ensure camera is connected |

### Firmware / Update Errors (0xF0-0xFD)

| Code | Name | Meaning |
|------|------|---------|
| 0xC9 | WRONG_FW_SIZE | Firmware size mismatch |
| 0xF0 | FM_NONSEQUENCE | Update out of sequence |
| 0xF1 | FM_LENGTH_WRONG | Firmware length incorrect |
| 0xF2 | FM_CRC_WRONG | CRC checksum mismatch |
| 0xF3 | FLASH_C_WRONG | Flash checksum error |
| 0xF4 | FLASH_W_WRONG | Flash write error |
| 0xF5 | UPDATE_WRONG | General update error |
| 0xF6 | FIRM_MATCH_WRONG | Firmware model mismatch |
| 0xF7 | UPDATE_WAIT_FINISH | Waiting for update completion |
| 0xFD | FLASH_FLUSHING | Flash memory still flushing |

### Power / Battery Errors

| Code | Name | Meaning |
|------|------|---------|
| 0x03 | BATTERY_NOT_READY | Battery initialization pending |
| 0xFB | DEVICE_LOW_POWER | Critically low battery |

### Remote Control Errors

| Code | Name | Meaning |
|------|------|---------|
| 0xD0 | RC_DATA_ERROR | RC data corrupted/invalid |
| 0xDF | RC_NOT_DATA | RC sending no data |

### Configuration / Parameter Errors

| Code | Name | Meaning |
|------|------|---------|
| 0xCD | CFG_VERSION_MISMATCH | Config version incompatible |
| 0xCE | CFG_INVALID | Config file corrupted |
| 0xCF | CFG_NOT_EXISTED | Config file missing |
| 0xED | PARAM_NOT_AVAILABLE | Parameter not available |

### State Control Errors

| Code | Name | Meaning |
|------|------|---------|
| 0xE4 | NOT_SUPPORT_CURRENT_STATE | Action not valid in current state |
| 0xE5 | TIME_NOT_SYNC | System time not synchronized |
| 0xE6 | SET_PARAM_FAILED | Parameter set operation failed |
| 0xE7 | GET_PARAM_FAILED | Parameter read operation failed |

### General Device Errors

| Code | Name | Meaning |
|------|------|---------|
| 0xD1 | BUSY | Device busy, retry later |
| 0xD2 | NOT_IMPLEMENTATION | Feature not implemented |
| 0xD9 | NOT_SUPPORT_FEATURE | Feature not supported |
| 0xDA | INVALID_CMD | Invalid command format |
| 0xDB | TIMEOUT_REMOTE | Remote command timeout |
| 0xDC | OUT_OF_MEMORY | Memory allocation failed |
| 0xDD | INVALID_PARAM | Parameter out of bounds |
| 0xFA | SELECT_INVALID | Invalid selection |
| 0xFC | SELECT_OVERSIZE | Selection exceeds capacity |
| 0xFF | UNDEFINED | Unknown/undefined error |
| 0xFFF | USER_CANCEL | User cancelled operation |

### Waypoint / Mission Errors (0xC0-0xD5)

| Code | Name | Meaning |
|------|------|---------|
| 0xC0 | ERR_AltitudeTooLow | Waypoint altitude below minimum |
| 0xC1 | ERR_AltitudeTooHigh | Waypoint altitude above maximum |
| 0xC2 | ERR_MissionRadiusInvalid | Patrol radius outside limits |
| 0xC3 | ERR_MissionSpeedTooLarge | Speed exceeds 15 m/s |
| 0xC4 | ERR_MissionEntryPointInvalid | Start point constraints violated |
| 0xC5 | ERR_MissionHeadingModeInvalid | Invalid heading mode |
| 0xC6 | ERR_MissionResumeFailed | Cannot resume interrupted mission |
| 0xC7 | ERR_MissionRadiusOverLimited | Radius boundary exceeded |
| 0xC8 | ERR_INVALID_PRODUCT | Drone model not recognized |
| 0xC9 | ERR_DISTANCE_TOO_LONG | Distance exceeds 65 km |
| 0xCA | ERR_FOR_IN_NOVICE_MODE | Not allowed in beginner mode |
| 0xCD | ERR_BAD_RTK_SIGNAL | RTK signal insufficient |
| 0xCE | ERR_FM_DIST_TOO_LARGE | Flight mission distance limit |
| 0xCF | ERR_FM_UL_DISCONNECT | Uplink lost during mission |
| 0xD0 | ERR_FM_ERROR_GIMBAL_PITCH | Gimbal pitch constraint |
| 0xD2 | ERR_WP_INFO_INVALID | Waypoint metadata invalid |
| 0xD3 | ERR_WP_DATA_INVALID | Waypoint coordinates invalid |

---

## Data Models

**Total:** 964 Java data model classes extracted from decompiled GO4 APK.

### Distribution by Subsystem

| Category | Count | % | Purpose |
|----------|-------|---|---------|
| Flight Controller (FLYC) | 342 | 35.5% | Flight control, parameters, limits, missions |
| Camera | 207 | 21.5% | Photo/video settings, state, transfer codes |
| OSD/Telemetry | 48 | 5.0% | Real-time flight/position data push structures |
| Battery | 31 | 3.2% | Battery health, voltage, current, charge cycles |
| ADS-B / Traffic | 10 | 1.0% | Aircraft detection, collision avoidance |
| Remote Control (RC) | 3 | 0.3% | RC state, stick/button mapping |
| Gimbal | 2 | 0.2% | Camera mount position, stabilization |
| FlySafe / Geofence | 1 | 0.1% | Geographic flight restrictions |
| **Other / Uncategorized** | **346** | **35.9%** | WiFi, Simulator, RTK, HMS, Perception, LiDAR, etc. |

### Top 10 Most Complex Models

| Rank | Name | Fields | Category | Purpose |
|------|------|--------|----------|---------|
| 1 | DataOsdGetPushCommon | 166 | OSD | Main flight telemetry (position, attitude, state) |
| 2 | DataCameraGetPushShotParams | 157 | Camera | Shot settings, metering, focus, zoom, format |
| 3 | DataCameraGetPushStateInfo | 132 | Camera | Current camera mode, recording status, SD info |
| 4 | DataSingleVisualParam | 114 | Vision | Visual sensors, obstacle detection params |
| 5 | DataCameraGetPushShotInfo | 109 | Camera | Photo/video metadata, file info, histogram |
| 6 | DataEyeGetPushException | 105 | Vision | Vision/obstacle detection state, exception codes |
| 7 | DataOsdGetPushHome | 104 | OSD | Home point, RTH status, go-home height, recorder |
| 8 | DataFlycSetVFenceData | 93 | FLYC | Virtual fence configuration, boundary parameters |
| 9 | DataCameraGetPushTauParam | 89 | Camera | Thermal (Tau) camera parameters |
| 10 | DataCameraGetPushRawParams | 81 | Camera | RAW image capture settings, codec, quality |

### Critical Models for GlassFalcon

**MUST STUDY:**

1. **DataFlycFunctionControl** (11 fields)
   - Main flight command execution model
   - Contains FLYC_COMMAND enum for all control operations
   - Linked to takeoff (`0x01`) / landing (`0x0c`) opcodes

2. **DataOsdGetPushCommon** (166 fields)
   - Largest model in GO4 — complete flight telemetry
   - Real-time: position, velocity, attitude, altitude, signals
   - Reference for HUD display fields

3. **DataFlycGetPushForbidStatus** (55 fields)
   - Geographic flight restrictions
   - Dynamic limit areas array
   - Key to understanding 30m cap implementation

4. **DataFlycGetParamInfo** (35 fields)
   - Parameter metadata (name, min, max, default)
   - Matches `0x03/0xf7` probe findings

**SHOULD STUDY:**

5. **DataFlycSetVFenceData** (93 fields) — Virtual fence configuration
6. **DataCameraGetPushShotParams** (157 fields) — Camera telemetry
7. **DataRcGetPushParams** (51 fields) — RC controller state (button mapping)
8. **DataADSBGetPushData** (38 fields) — Traffic avoidance framework

### Model Obfuscation

- **79 models (8.2%)** have obfuscated class names: `a.java`, `b.java`, `c1.java`, `ddf.java`, etc.
- Parent classes and field names remain readable
- Category inference still possible from field/parent analysis

### No Protobuf Usage

- **0 models** use protobuf annotations
- Data structure is purely Java class fields
- JSON serialization likely used instead
- Relevant for packet format reverse-engineering

---

## ADS-B / Remote ID

### Command Set 0x11 (ADS_B)

14 commands for traffic detection, remote identification, collision warnings, and licensing.

| cmd_id | Name | Type | Purpose |
|--------|------|------|---------|
| 0x02 | GetPushData | Push | Receive detected aircraft list |
| 0x08 | GetPushWarning | Push | Collision warning for detected aircraft |
| 0x09 | GetPushOriginal | Push | Raw ADS-B message data |
| 0x10 | SendWhiteList | Command | Configure aircraft whitelist |
| 0x11 | RequestLicense | Command | Request ADS-B license |
| 0x12 | SetLicenseEnabled | Command | Enable/disable ADS-B |
| 0x13 | GetLicenseId | Command | Query license identifier |
| 0x14 | GetPushUnlockInfo | Push | Unlock area IDs |
| 0x15 | SetUserId | Command | Set user ID (base64) |
| 0x16 | GetKeyVersion | Command | Get flight controller key version |
| 0x17 | GetPushAvoidanceAction | Push | Collision avoidance maneuver |
| 0x1C | RidWorkingStatus | Push | Remote ID working status |
| 0x27 | ClearUnlockInfo | Command | Clear unlock information |
| 0x43 | AppUpdatePosEnc | Command | Send encrypted position to drone |

### ADS-B Item (Aircraft Detection)

| Field | Type | Units | Notes |
|-------|------|-------|-------|
| ICAO Address | String | 24-bit hex | Unique aircraft identifier |
| Callsign | String | 8 chars | Flight registration/callsign |
| Latitude | double | WGS-84 degrees | Aircraft position |
| Longitude | double | WGS-84 degrees | Aircraft position |
| Altitude | float | meters | Height (from pressure or GNSS source) |
| Heading | float | degrees | Aircraft track 0-359° |
| Horizontal Speed | float | m/s | Ground speed |
| Vertical Speed | float | m/s | Positive = climbing |
| NIC | int | 0-11 | Navigation Integrity Category |
| NACP | int | 0-11 | Navigation Accuracy Category |
| Height Source | enum | PRESSURE / GNSS / OTHER | Altitude measurement source |
| Info Source | enum | ES1090 / UAT / OTHER | 1090 MHz ES or 978 MHz UAT |

### Collision Warning Structure

| Field | Type | Notes |
|-------|------|-------|
| Aircraft ICAO | String | Which aircraft is a collision threat |
| Distance | float | meters from own aircraft |
| Relative Bearing | float | degrees from nose |
| Time to Collision | int | seconds until closest approach |
| Severity | enum | ADVISORY / CAUTION / WARNING / CRITICAL |

---

## Known Findings & Implementation Status

### Device Authentication Gate (0x11/0x43 — HMS Handshake)

**CRITICAL: This command gates the 30m hard altitude/distance limit.**

Confirmed 2026-07-06 via kprobe: the aircraft enforces a firmware-level physical-device authentication gate via `cmd_set=0x11, cmd_id=0x43` frames. Without valid repeating `0x11/0x43` frames (~1 Hz during flight), the FC refuses to arm beyond 30m, regardless of beginner-mode or any other parameter.

**Frame structure (84 bytes):**

```
[0:4]    0x50000000              frame length (constant: 0x50 = 80 bytes inner)
[4:36]   <random 32 bytes>       nonce (per-frame, app-generated)
[36:52]  <fixed 16 bytes>        device token (static per aircraft/RC combo)
[52:84]  <random 32 bytes>       signature (per-frame, algorithm unknown)
```

**Example device token** (from a wm240 Mavic 2):
```
d3006306bd44fe08200bfd10025716a5
```

**Properties:**
- Device token is **static** across power cycles, app restarts, FC reboots, cable pulls
- Nonce is **unique every frame**, never repeating
- Signature is **unique every frame**, likely HMAC-SHA256(secret_key, nonce || device_token)
- Must repeat continuously; if missing, FC locks to 30m

**Reverse Engineering Status:**
- ✅ Frame structure (fully mapped)
- ✅ Device token (static, captured)
- ❌ Signature algorithm (HMAC-SHA256 hypothesis, needs Frida hook confirmation)
- ❌ Key material (whitebox-protected, needs Frida interception)

See [`docs/2026-07-06-0x11-handshake-discovery.md`](../docs/2026-07-06-0x11-handshake-discovery.md) in the GlassFalcon repo for Frida hook code.

---

### Flight Control Opcodes

| Opcode | Name | Confirmation Status | Notes |
|--------|------|---|-------|
| `0x01` | **Takeoff (AutoTakeoff)** | ✅ **Confirmed via kprobe** | Captured 2026-07-02 on genuine GO4 flight; FC engaged motors; validated end-to-end |
| `0x0C` | **Landing (AutoLand)** | ⚠️ **Unconfirmed** | Possible kprobe match (0xfe, payload 0x00) but insufficient evidence; needs dedicated capture |

---

### Camera Command Set Fix (2026-07-03)

**CRITICAL FIX:** Camera admin functions (mode, focus, AE-lock, ISO, shutter, EV, WB, aperture, system-state, SD-info, format) belong on **`cmd_set=0x02`** (CAMERA), NOT `0x01` (SPECIAL).

**Root Cause:** Confusion between module ID (0x01 = camera device address) and cmd_set field (0x02 = CAMERA command group).

**Confirmed via kprobe:** Genuine GO4 session capturing Photo↔Video toggle, AFC↔MF focus toggle, AE-lock press, all under `cmd_set=0x02`:
- Mode-set: `cmd_id=0x10`, payload `01`→`00`
- Focus-set: `cmd_id=0x24`, payload `02`→`00`→`02`
- AE-lock-set: `cmd_id=0x68`, payload `01`

This explains prior `0xe0` ("rejected") ACKs on those commands in earlier GlassFalcon versions.

---

### Flight Limit Parameters

**Status:** ✅ Pathway confirmed; values NOT yet read from real hardware

Parameter hashes found in GO4:
- `MAX_HEIGHT` = `0x0371238a` (value type: u16 LE meters)
- `MAX_RADIUS` = `0x425c0a94` (value type: u16 LE meters)
- `MIN_HEIGHT` = `0x0438298a`
- `HEIGHT_LIMIT_ENABLED` = `0xae52d19a`
- `RADIUS_LIMIT_ENABLED` = `0x7ece6d19`
- `BEGINNER_MODE_ENABLE` = (related to novice caps)

**IMPORTANT:** DJI GO 4 sends **ZERO** limit-param writes at any point. The 30m envelope is purely a "no mobile GPS yet" state, not a stored parameter. Parameter writes via `0x03/0xf8`/`0xf9` do NOT lift the 30m cap; only the `0x11/0x43` authentication handshake does.

---

### Home Point NOT to be Sent (0x03/0x31)

**⚠️ WARNING: DO NOT SEND `flyc_set_home_point` (`0x03/0x31`) on the wm240.**

Confirmed live 2026-07-05: sending a home point drops the aircraft back into the 30m envelope. The aircraft records its own home point automatically, so there is no legitimate reason to send it in normal operation.

---

### Motor Start Failure Reason (Byte 38, OSD General)

**Importance:** Real pre-flight warnings (IMU calibration, compass error, etc.) are carried in byte 38 of the OSD General telemetry, NOT in separate warning commands. DJI GO 4's own pre-flight warnings are driven by this data.

**Status:** ✅ Now decoded and surfaced in GlassFalcon HUD as `DroneState.startFailText`.

---

### IMU Preheating Flag (0x1000 controller_state bit) — NOW DISABLED

**Status:** ⚠️ **UNRELIABLE / REMOVED FROM WARNINGS (2026-07-05)**

The `0x1000` bit in controller_state (byte 32-35) nominally flags "IMU preheating" but is **unreliable** and **stuck high for 12+ minutes** with no real IMU temperature behind it. This bit has been removed from HUD warnings; only `startFailText` (byte 38) is surfaced now.

---

### Obstacle/Vision Distance (0x09/0x6a) — Community Dissector Was Wrong

**Status:** ✅ Fully mapped and confirmed

Community dissectors describe a 1-byte payload; real captures showed 13-byte payload. Layout confirmed against 647 real frames and live 2026-07-05 by approaching walls:

```
byte 0:      header/type, always 0x01
bytes 1-2:   channel A, FRONT distance, u16 LE
bytes 3-4:   unused second beam (permanently open on wm240)
bytes 5-6:   channel B, REAR distance, u16 LE
bytes 7-8:   unused second beam (permanently open on wm240)
bytes 9-10:  channel C, lateral LEFT distance, u16 LE
bytes 11-12: channel D, lateral RIGHT distance, u16 LE
```

Front and rear are confirmed; left/right only in low-speed ActiveTrack.

---

### Combination Stick Command (CSC) — Physical Emergency Motor Stop

**Status:** ✅ Confirmed physical gesture (not a DUML command)

Push both sticks to their bottom-**outer** corners simultaneously to cut motors mid-flight if the aircraft loses control. This is independent of any software command and worth documenting in UI help text even though it isn't something this SDK sends.

---

### RC Audible Cues (0x06/0xf7 — RC Push Buzzer)

**Status:** ⚠️ **UNVERIFIED / EXPERIMENTAL** — NOT kprobe-confirmed

DJI GO 4 makes the RC240 itself beep on take-off/land/warnings. GlassFalcon implements as `rc_push_buzzer()` with guessed single-byte payload, fired best-effort alongside `autoTakeoff()`/`autoLand()`. Unlike takeoff/land triggers, this is low-stakes to ship without a kprobe capture first; worst case is silence or wrong tone, not a flight-safety issue.

---

### Internal Storage Limit

**Status:** ✅ Discovered live 2026-07-05

A full aircraft internal eMMC (accumulated flight logs) forces a limited flight envelope, independent of GPS stream or parameter settings. Formatting the internal storage via DJI GO 4 clears logs and restores the full envelope.

---

### Firmware Version & DUML Dialect

**Confirmed against:** wm240 on **FC v01.00.0790 / RC v01.00.0770** (read from DJI GO 4 v4.3.64 About screen).

DUML protocol can shift across firmware versions. All findings in this document are tied to this combination until re-verified on other versions.

---

### Implementation Status

#### Fully Implemented (✅)

- Takeoff / Landing / Motor control
- Joystick (throttle, pitch, roll, yaw)
- Flight parameter read/write (via hash)
- Camera photo / video / settings (mode, focus, AE-lock, ISO, shutter, EV, WB)
- Gimbal speed control
- Gimbal pitch/roll/yaw absolute positioning
- OSD general telemetry (position, velocity, attitude, battery, state)
- Battery status (voltage, SOC, temperature)
- Gimbal status (angle, speed)
- Obstacle distance (front, rear, left, right)
- RC button state (all 7 learnable controls)
- Geofence data push (restrictions, limits)
- HMS authentication handshake (0x11/0x43) — for 30m gate unlock

#### Partially Implemented (⚠️)

- Camera telemetry (mode captured; missing focus, zoom, WB details)
- Battery data (voltage, SOC, temp captured; missing current, capacity, health)
- Gimbal calibration (data model known; calibration loop not tested on real aircraft)
- ADS-B / traffic detection (data model known; decoder not implemented)
- Parameter metadata (model known; field access not fully explored)

#### Not Implemented (❌)

- Home point data (0x09/0x42) — RTH status, home location, go-home height
- All 8 OSD telemetry structures beyond general (wind, temperature, etc.)
- Smart battery cell-level health monitoring
- Advanced camera state (RAW capture, thermal [Tau], professional codecs)
- RTK / precise positioning
- Waypoint mission execution (model known; upload/execution loop not tested)
- LiDAR / advanced ranging
- Remote ID / License management

---

## Appendix: Command Extract Sources

All command data extracted from decompiled DJI GO 4 via JADX:
- `/dji/midware/data/config/P3/CmdSet.java` — Command set definitions
- `/dji/midware/data/model/P3/` — 964 data model classes

For detailed command lists by cmd_set, see scratchpad files:
- `go4_cmdsets_final.txt` — Complete command set listing (680 commands, 23 sets)
- `GO4_MODELS_REPORT.txt` — All 964 data models with field counts
- `GO4_ERROR_CODES_COMPLETE.txt` — All 709 error codes by subsystem
- `TELEMETRY_EXTRACTION_FINAL.md` — Complete telemetry structure extraction
- `ADS-B_REMOTE_ID_STRUCTURES.md` — ADS-B and remote ID structures

---

**This document is a living reference.** As new firmware versions are analyzed, new commands discovered via kprobe, and GlassFalcon capabilities expanded, this reference will be updated to reflect the latest findings.

Last verified: 2026-07-06 on wm240 (Mavic 2) with FC v01.00.0790 / RC v01.00.0770
