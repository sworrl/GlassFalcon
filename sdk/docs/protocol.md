# DUML Protocol Reference

DUML (DJI Universal Markup Language) is the framed command/response protocol the
aircraft, camera, gimbal, battery, and app all speak over every transport (USB
CDC-ACM, the OcuSync downlink, UART). The Glass Falcon SDK implements it in
[`glassfalcon/duml.py`](../python/src/glassfalcon/duml.py).

## Frame layout

```
 0      1      2      3      4    5     6   7    8         9        10      11..n-2   n-2..n
+------+------+------+------+----+----+--------+---------+--------+--------+--------+--------+
| 0x55 | len  | ver/ | CRC8 |src |dst |  seq   | cmd_type| cmd_set| cmd_id | payload| CRC16  |
|      | [7:0]| len  |      |    |    | (LE16) |         |        |        |        | (LE16) |
+------+------+------+------+----+----+--------+---------+--------+--------+--------+--------+
```

| Field | Bytes | Notes |
|---|---|---|
| magic | 1 | always `0x55` |
| length | 1 + 2 low bits of byte 2 | total frame length, 10 bits |
| version | high 6 bits of byte 2 | `1` for wm240 |
| CRC8 | 1 | over bytes `[0:3]`, **seed `0x77`** |
| src / dst | 1 + 1 | module IDs (table below) |
| seq | 2 (LE) | wraps at `0xFFFF` |
| cmd_type | 1 | `0x40` = request (REQ), `0x80` = ack/response (ACK) |
| cmd_set | 1 | command group (e.g. `0x03` flight controller) |
| cmd_id | 1 | command within the group |
| payload | 0..n | command-specific |
| CRC16 | 2 (LE) | over the whole frame minus the CRC, **seed `0x3692`** |

Build and parse with `build_packet(...)` / `parse_frame(buf) -> (frame|None, consumed)`.
`DUMLConnection` does the framing, sequencing, and threaded receive for you.

## Module (src/dst) map

| ID | Module | Identifier string |
|---|---|---|
| `0x01` | Camera | WM240 (Ambarella H2) |
| `0x03` | Flight Controller | 163DF7 (fw 24a0714) |
| `0x04` | Gimbal | GB11 |
| `0x09` | HD Video Transmission | DJI P1 HDVT (OcuSync 2.0) |
| `0x02` | Mobile App | **us** |
| `0x0a` | Ground/desktop assistant tool (DJI Assistant 2) | not us |
| `0x0b` | Battery | BA01WM240BAT |
| `0x0c` | Motor ESCs | WM240_ESC_V7 (4-in-1) |
| `0x12` | Obstacle-avoidance ToF | WM240_TOF_v2 |
| `0x28` | Aircraft Computer | WM240 AC Ver.A |

A request from the app to the flight controller is therefore
`src=0x02, dst=0x03, cmd_type=0x40`. `DUMLConnection.send_cmd()` defaults to
exactly this; override `dst=` for camera (`0x01`) or gimbal (`0x04`). (Prior to
2026-07-01 this defaulted to `src=0x0a`, the ground/desktop-assistant-tool
identity rather than the phone-app identity, see the module map above.)

## OSD General Data, telemetry push

The FC broadcasts **OSD General Data** (`cmd_set=0x03, cmd_id=0x43`) at ~1 Hz
once any app command has been received. `TelemetryDecoder.feed(frame)` parses it:

| Offset | Type | Field | Scale |
|---|---|---|---|
| 0 | f64 LE | longitude | radians → degrees |
| 8 | f64 LE | latitude | radians → degrees |
| 16 | i16 | relative height | ×0.1 m |
| 18 | i16 ×3 | velocity vgx/vgy/vgz | ×0.1 m/s |
| 24 | i16 ×3 | pitch/roll/yaw | ×0.1 ° |
| 32 | u32 | controller_state | bitfield (bit `0x400` = battery-requires-land; bit `0x1000` nominally "IMU preheating" but is **unreliable/stuck**, no real IMU temperature sits behind it, so it is not surfaced) |
| 36 | u8 | GPS satellite count |, |
| 38 | u8 | motor start-fail reason | low 7 bits, real firmware enum, 0 = none/allow start; see `START_FAIL_REASONS` in `Telemetry.kt` |
| 40 | u8 | battery remaining | % (0 until the battery module syncs) |
| 42 | u16 | flight time | s |

**2026-07-02, first real flight, no pre-flight gyro/IMU warning was shown.** Root cause: the
byte-38 field was never decoded at all, DJI GO 4's own pre-flight warnings are driven by exactly
this data, so GlassFalcon simply had nothing to show even when the FC itself had a reason queued
up. The byte-38 motor start-fail reason is now decoded and surfaced through the normal warnings
banner (`DroneState.startFailText`). Confidence is high: the enum strings come directly from
`dji-dumlv1-flyc.lua`'s `FLYC_OSD_GENERAL_START_FAIL_REASON_ENUM` (real firmware strings), and the
byte offsets were cross-checked against this file's own already-confirmed-working offsets
(32/36/40) rather than assumed fresh.

**Update 2026-07-05:** the companion "IMU preheating" warning (from the `0x1000` controller_state
bit, previously surfaced as `DroneState.imuPreheating`) was **removed**, that bit is
unreliable/stuck with no real IMU temperature behind it. Only `startFailText` is surfaced now.

## Known findings, gaps, and firmware version

Confirmed live against a real wm240 unit on **FC v01.00.0790 / RC v01.00.0770**
(read from DJI GO 4 v4.3.64's About screen). DUML dialect can shift across
firmware, treat findings below as tied to this combination until re-verified
elsewhere.

- **Camera admin functions (mode/focus/AE-lock/ISO/shutter/EV/WB/aperture/
  system-state/SD-info/format) belong on `cmd_set=0x02`, not `0x01`.**
  Recovered 2026-07-03 via the same `acc_write()` kprobe technique (below),
  capturing a genuine DJI GO 4 session doing a Photo↔Video toggle, an AFC↔MF
  focus toggle, and an AE-lock press. All three came back with `cmd_set=0x02`:
  mode-set (`cmd_id=0x10`, payload `01`→`00`), focus-set (`cmd_id=0x24`,
  payload `02`→`00`→`02`), AE-lock-set (`cmd_id=0x68`, payload `01`), each
  matching the exact action taken, isolated among the periodic `0x02/0x4d`,
  `0x02/0xba`, `0x02/0xe8` polling frames (empty-payload GETs, purpose not
  decoded). `capturePhoto`/`startRecord`/`stopRecord` (`cmd_set=0x01`) are
  unaffected, those got real `0x00` success ACKs under `0x01` in the
  2026-07-03 testing session referenced below, and stay there.
  This directly explains why `setMode()`/`systemState()`/`sdcardInfo()` all
  previously got `0xe0` ("rejected") ACKs: wrong cmd_set, not a dead command
  or missing feature. Community dissector cross-reference (`dji-dumlv1-proto.lua`'s
  own cmd_set table) independently calls `0x01`="SPECIAL" and `0x02`="CAMERA", 
  this project had conflated the module's *dst* routing address (`0x01`,
  unchanged, still correct) with the embedded cmd_set *field* (a separate
  byte in the frame) ever since the very first commit. Fixed in both SDKs.
  The corresponding `cmd_id=0x80` "Camera State Info" PUSH's cmd_set is still
  unconfirmed (no push captured in this session), `TelemetryDecoder`/
  `parse_camera_state` now accept it on either `0x01` or `0x02` defensively.

- **`cmd_set=0x03, cmd_id=0x2a` (FLYC Function Control) auto-takeoff trigger is
  now CONFIRMED: payload `0x01`.** Recovered 2026-07-02 via a kernel kprobe on
  the AOA accessory driver's `acc_write()` (bypasses the app-ownership/SELinux
  wall that blocks ptrace-based approaches, see below), capturing a genuine
  DJI GO 4 v4.3.64 takeoff over the same link. Verified end-to-end: FC accepted
  the frame and engaged the motors (caught by the aircraft's own no-propeller
  safety check, in a deliberate props-off test). The earlier `0x0b` guess
  (pattern-matched from unrelated OSD telemetry-readback enums) is confirmed
  wrong, it's a real no-op, not a framing artifact. **Landing (`0x0c`) is
  still unverified**, the same capture also caught one `cmd_id=0xfe`
  (payload `0x00`) ~11.6s after the takeoff trigger, possibly a stop/cancel
  sent after the no-prop warning, but that's not enough evidence to claim it's
  the land command. Needs its own kprobe capture of a genuine landing.

  **Capture method, for reproducing this or extending it to landing:** DUML
  frames written to `/dev/usb_accessory` are visible to a kprobe on the AOA
  gadget driver's character-device write handler (`acc_write` in the kernel's
  `f_accessory` driver, confirmed present via kernel BTF introspection at
  `/sys/kernel/btf/vmlinux`, no kernel source needed). This sees the raw
  userspace buffer regardless of which app owns the accessory, so it isn't
  blocked by the same-fd-exclusivity or ptrace/SELinux restrictions that block
  other capture approaches. Rough recipe (root required):
  ```
  mount -t debugfs debugfs /sys/kernel/debug   # if not already mounted
  echo 'p:acc_probe acc_write count=%x2:u64 b0=+0(%x1):x64 b1=+8(%x1):x64 b2=+16(%x1):x64 b3=+24(%x1):x64' \
      > /sys/kernel/debug/tracing/kprobe_events
  echo 1 > /sys/kernel/debug/tracing/events/kprobes/acc_probe/enable
  echo 1 > /sys/kernel/debug/tracing/tracing_on
  # ... trigger the action in DJI GO 4, then:
  cat /sys/kernel/debug/tracing/trace
  ```
  Each hit gives up to 32 captured bytes (4×8-byte hex reads via `%x1`) of the
  write buffer, little-endian, reconstruct and parse as a normal DUML stream.
  Kprobes do not survive reboot and must be redefined each session; `adb root`
  also resets on reboot and must be re-run before any of this (a real failure
  mode hit while capturing this, `mount`/kprobe writes fail with confusing
  errors like "bad /etc/fstab" if you're actually running as unprivileged
  `shell`, not `root`, so check `id` first if debugfs setup misbehaves).
- **`cmd_set=0x03, cmd_id=0x6a` carries obstacle/vision distance data, and
  community dissectors are wrong about it.** They describe a 1-byte payload;
  a live capture showed a real 13-byte payload instead. Byte layout confirmed
  against 647 real frames from `captures/gf_wm240_bench_session_20260701_full.bin`
  and confirmed live 2026-07-05 by approaching walls. Layout:
  ```
  byte 0:      header/type, always 0x01
  bytes 1-2:   channel A, FRONT distance, u16 LE
  bytes 3-4:   unused second beam (permanently open on wm240)
  bytes 5-6:   channel B, REAR distance, u16 LE
  bytes 7-8:   unused second beam (permanently open on wm240)
  bytes 9-10:  channel C, lateral LEFT distance, u16 LE
  bytes 11-12: channel D, lateral RIGHT distance, u16 LE
  ```
  **A = front and B = rear are confirmed** (approaching a wall moved the
  matching channel). Each arc has NO left/right sub-resolution, the second
  u16 in each 4-byte field is an unused second beam that reads permanently
  "open" on the wm240. The lateral channels C (left) and D (right) are only
  populated in low-speed ActiveTrack and read zero otherwise; this matches
  DJI GO 4's own "Obstacle Avoidance Status" screen (forward+backward always
  rich, left+right only in ActiveTrack). Implemented as `ObstacleState` in
  `Telemetry.kt`, surfaced in `MainScreen.kt`.
- **Combination Stick Command (CSC) emergency motor stop is a physical RC
  gesture, not a DUML command**: push both sticks to their bottom-**outer**
  corners simultaneously to cut motors mid-flight if the aircraft loses
  control. This is independent of any software command and worth surfacing in
  UI documentation/help text even though it isn't something this SDK sends.
- **The 30 m cap is lifted by streaming mobile GPS, not by any parameter write.**
  Confirmed live 2026-07-05: the FC clamps altitude/distance to 30 m until the app
  streams the phone's GPS to the flight controller via `cmd_set=0x03, cmd_id=0x20`
  (Send-GPS-To-Flyc). DJI GO 4 sends **zero** limit-param writes at any point, the
  30 m envelope is purely a "no mobile GPS yet" state, not a stored parameter. Two
  things that do **NOT** lift it: (a) FC-parameter writes (below), GO 4 never
  touches them; (b) device identity / root / PC-identity (`0x0a`), the cap behaves
  identically on rooted and non-rooted phones.
- **`flyc_set_home_point` (`0x03/0x31`) must NEVER be sent on the wm240, it
  RE-LOCKS the 30 m cap.** Confirmed live 2026-07-05: sending a home point drops the
  aircraft back into the 30 m envelope. The aircraft records its own home point
  automatically, so there is no legitimate reason to send it in normal operation.
- **A full aircraft internal storage independently forces a limited flight
  envelope.** When the aircraft's internal eMMC fills up with accumulated flight
  logs ("eMMC full"), the FC drops into a limited envelope regardless of the GPS
  stream. Formatting the internal storage via DJI GO 4 clears the logs and restores
  the full envelope.
- **Flight-limit config parameters (max height/radius), pathway CONFIRMED, values
  NOT yet read back from real hardware.** The first real flight test capped out at
  30m, but per the finding above that was the missing mobile-GPS stream, not a
  stored FC-level parameter. GlassFalcon itself has never sent any distance/height/
  speed cap of its own; that's a deliberate design choice, see
  [Known Limitations](../../README.md). DJI's generic FC
  parameter-tuning pathway (the same one DJI Assistant 2 uses) is fully byte-level
  dissected upstream: `cmd_set=0x03, cmd_id=0xf8` reads a parameter by a 32-bit name
  hash (`request: hash u32 LE` → `response: status u8, hash u32 LE, value bytes`),
  `cmd_id=0xf9` writes one the same way. Framing confidence is HIGH (fully dissected,
  not a guess). Named hash constants found and added as `FlyC.ParamHash` /
  `PARAM_HASH_*`: `MAX_HEIGHT` (`0x0371238a`), `MAX_RADIUS` (`0x425c0a94`),
  `MIN_HEIGHT` (`0x0438298a`), `HEIGHT_LIMIT_ENABLED` (`0xae52d19a`),
  `RADIUS_LIMIT_ENABLED` (`0x7ece6d19`), and the separate novice-mode caps
  `NOVICE_MAX_HEIGHT` (`0xd9ab9f79`) / `NOVICE_MAX_RADIUS` (`0x18968688`). **What's
  NOT confirmed:** the value size/type for any given hash, the dissector says "size
  and type depends on parameter" without specifying it per-hash, and no unit
  (meters? decimeters?) is documented. Read a hash back first via the app's raw DUML
  console (Device tab: `cmd_set=3, cmd_id=248, payload=<4-byte LE hash>`) and inspect
  the actual byte count/value before writing anything, don't blind-write a guessed
  "unlimited" value. No max-*speed* hash was found in this pass, only height/radius.
- **RC audible take-off/land cue, UNVERIFIED / EXPERIMENTAL, not kprobe-confirmed.**
  DJI GO 4 makes the RC240 itself beep on take-off/land/warnings; GlassFalcon didn't. A
  community dissector (`dji-dumlv1-proto.lua`'s `RC_UART_CMD_TEXT` table) names
  `cmd_set=0x06, cmd_id=0xf7` as "RC Push Buzzer To MCU", `cmd_set=0x06` matches this
  project's established convention that a module's cmd_set number equals its dst address
  (compare FC/Camera/Gimbal), so the RC module's own dst is presumed `0x06`, but no payload
  layout is documented anywhere. Implemented as `Rc.pushBuzzer()` / `rc_push_buzzer()` with a
  guessed single-byte payload, fired best-effort alongside `autoTakeoff()`/`autoLand()`. Unlike
  the takeoff/land trigger values, this is low-stakes to ship without a kprobe capture first, 
  worst case is silence or the wrong tone, not a flight-safety issue.
- **RC240 custom-button/dial byte map, CONFIRMED 2026-07-03, live device (Pixel 8 Pro, RC240
  connected, no drone link required).** `cmd_set=0x06, cmd_id=0x51` ("RC Push To Glass") carries
  a status byte whose low 7 bits map one-to-one, no overlap, to the RC240's seven learnable
  physical controls, confirmed via GlassFalcon's own in-app Learn/guided-calibration flow
  (press one control, diff the byte against baseline), not a dissector guess:
  - `0x40` (bit 6), left trigger (front-left shoulder button, "C1")
  - `0x20` (bit 5), right trigger (front-right shoulder button, "C2")
  - `0x01` (bit 0), 5-way dial (near the screen) pressed UP
  - `0x02` (bit 1), 5-way dial pressed DOWN
  - `0x08` (bit 3), 5-way dial pressed LEFT
  - `0x10` (bit 4), 5-way dial pressed RIGHT
  - `0x04` (bit 2), 5-way dial pressed IN (click)

  All seven bits are distinct and cover the full 0x00-0x7f range's active bits exactly once, 
  high confidence this is the complete map for these controls, not a partial/coincidental
  match. Payload length for this frame and the meaning of any other bits are still unconfirmed.

## Command builders

[`duml_cmds.py`](../python/src/glassfalcon/duml_cmds.py) returns
`(cmd_set, cmd_id, payload)` tuples ready for `send_cmd()`. Groups:

- **General** (`0x00`): `ping`, `version_inquiry`, `reboot_chip`, `get_device_state`, `get_serial_number`
- **Camera** (`0x01`): `camera_capture_photo`, `camera_start_record`, `camera_set_iso`, `camera_set_shutter`, …
- **Flight** (`0x03`): `flyc_emergency_stop`, `flyc_motor_ctrl`, `flyc_set_home_point` (⚠️ do NOT send on the wm240, re-locks the 30 m cap; the aircraft self-records home), `flyc_joystick`
- **Gimbal** (`0x04`): `gimbal_abs_angle` (⚠️ ignored by the wm240, use `gimbal_speed`, `0x04/0x0c`, instead), `gimbal_speed`, `gimbal_set_mode`, `gimbal_calibrate`

Response parsers (`parse_version_inquiry`, `parse_serial_number`,
`parse_camera_state`) decode ACK payloads back into dicts.
