<p align="center">
  <img src="assets/readme_banner.png" alt="GlassFalcon">
</p>

<h1 align="center">GlassFalcon</h1>

<p align="center"><em>Vendor-independent ground control for DJI drones. No account, no cloud, no DJI SDK.</em></p>

<p align="center">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-3ddc84">
  <img alt="Target" src="https://img.shields.io/badge/target-the%20DJI%20drone%20suite-C8902A">
  <img alt="Tested on" src="https://img.shields.io/badge/tested-Mavic%202%20(wm240)-success">
  <img alt="Protocol" src="https://img.shields.io/badge/protocol-DUML%20(clean--room)-lightgrey">
</p>

GlassFalcon is a clean-room, open-source Android ground-control app and SDK for DJI drones. It speaks the drone's native DUML protocol directly over USB, so an aircraft keeps flying without DJI's app, account, servers, or Mobile SDK. Nothing phones home.

**Why it exists.** DJI's standing in the US market is in doubt: import holds, entity-list actions, and pending legislation have made continued official US support uncertain. Owners of existing DJI hardware risk losing the app their aircraft depend on. GlassFalcon keeps those drones flyable on the owner's terms, independent of the vendor.

**Scope.** The target is DJI's lineup as a whole, not one airframe. DJI aircraft share the DUML protocol, differing per model in opcodes and telemetry layout, so the SDK is built to extend across models.

**What works today.** Only the **Mavic 2 Pro / Zoom (`wm240`)** is implemented and flight-tested, because it is the one aircraft the author owns. Every ✅ below is verified on a wm240; other models are the roadmap and need people who have those airframes (see [Contributing](#contributing)). The [Tested / Not-Tested](#tested--not-tested) section is the exact line between verified and unproven.

---

## Contents

- [Supported models](#supported-models)
- [Features](#features)
- [Tested / Not-Tested](#tested--not-tested)
- [Connecting](#connecting)
- [Install (signed APK)](#install-signed-apk)
- [Build (from source)](#build-from-source)
- [SDK](#sdk)
- [Hardware](#hardware)
- [Architecture and DUML](#architecture-and-duml)
- [DUML Command Tome](#duml-command-tome)
- [Permissions](#permissions)
- [Known Limitations](#known-limitations)
- [Repo Structure](#repo-structure)
- [Contributing](#contributing)
- [Legal](#legal)

---

## Supported models

DJI aircraft share the DUML protocol but differ per model in opcodes, telemetry offsets, and USB IDs. Support is added one airframe at a time by capturing its dialect and filling in the per-model differences in the SDK.

| Model | Code | Status |
|---|---|---|
| Mavic 2 Pro / Zoom | `wm240` | ✅ implemented and flight-tested (the reference target) |
| Rest of the DJI lineup | various | ⬜ planned; each needs an owner of that airframe to capture and verify |

Porting a model means identifying its module IDs and telemetry layout, adding the per-model opcode differences, and verifying against the aircraft. If you own a DJI drone that DJI has walked away from, that is the hardware this project is for. See [Contributing](#contributing).

---

## Features

| Screen | What it does |
|---|---|
| **Flight HUD** | Live H.264 camera feed, emergency stop (tap-arm then confirm), guarded slide-to-arm takeoff/land, RTH, dual GPS readout (drone and phone), speed/altitude PFD tapes, heading compass, aircraft-referenced attitude indicator, obstacle radar, controller-button popups, live wind/gust pill |
| **Map** | Offline-capable MapLibre map, live drone position and heading, speed/altitude-colored flight trail, FAA airspace overlay (restricted/controlled zones plus the UAS Facility Map altitude grid), weather overlays |
| **Camera** | Embedded live viewer, photo capture, record toggle, ISO/shutter/EV/WB/anti-flicker, AF/MF, pinch-to-zoom (Mavic 2 Zoom) |
| **Gimbal** | Artificial-horizon attitude indicator, drag-to-aim plus double-tap recenter (speed control), mode switching (Follow / Lock / YawNoFollow), calibration |
| **Telemetry** | Full attitude, velocity, and GPS table |
| **Mission** | Grid/orbit mission builder with map preview, optional AI natural-language planning, battery-per-leg estimate |
| **Offload / Gallery** | Pull media over WiFi (drone AP) or ADB; on-phone gallery with select, share, filter, and sort |
| **AI Co-pilot** | Voice push-to-talk plus proactive callouts. Runs on on-device **Gemini Nano**, cloud **Gemini**, or a **Hybrid** mode where Nano routes commands and Gemini writes the answers. Fed live telemetry and weather. |
| **Voice** | Per-category mutable spoken callouts, on-demand full status read-out, voice and speed picker |
| **Device** | Ping/version/serial inquiry, FC info, per-module reboot, raw DUML hex console, FC tuning (sport boost, wind resistance), expert flight-limit controls |
| **Firmware** | Aircraft and RC firmware version display |
| **Plugins** | Optional add-on features with per-device enable, including the **encrypted live-stream** plugin |

**Plugins** are an in-app extension point for features not every pilot wants shipped on. The first is an end-to-end-encrypted re-streamer: it AES-256-GCM-encrypts each H.264 frame on the phone and relays it to a blind server that only ever sees ciphertext, viewable from an ephemeral link that burns itself when the stream ends. The server spec and a reference watch page are in [`plugins/encrypted-stream/`](plugins/encrypted-stream/). See [`plugins/`](plugins/) for how plugins are structured.

Settings screens open without a drone connected, the same way DJI GO 4 lets you review camera settings before a drone is linked. Individual controls still need a live link to reach the drone.

### Connection modes

| Mode | How | When to use |
|---|---|---|
| **USB direct (AOA)** | Plug the RC240 into the phone's USB port. The app auto-requests permission and streams DUML over Android Open Accessory. No DJI SDK, no servers, no account. | Primary path |
| **USB direct (CDC-ACM)** | Plug the **aircraft** into the phone/PC over USB. It enumerates as `/dev/ttyACM0` and talks to the flight controller as the DJI-Assistant "PC" identity. | Bench config, FC parameter tuning |
| **TCP** | Enter `IP:port` in the Connect dialog. | LAN bench testing, e.g. a PC-side DUML relay |
| **MSDK** (optional) | DJI Mobile SDK thin layer for USB auth only, if AOA does not enumerate. Needs your own `DJI_APP_KEY` in `local.properties`; the app ships with none and the dependency is inert without one. | Fallback only |

---

## Tested / Not-Tested

Legend: ✅ **Confirmed** (verified against real hardware) · ⚠️ **Unconfirmed** (framing known, effect not verified) · ❌ **Known-bad** (tried live, does not work or is actively harmful).

### DUML commands and behaviors

| Command / behavior | cmd_set/id | Status | Notes |
|---|---|:--:|---|
| Auto-takeoff trigger | `0x03` FunctionControl, val `0x01` | ✅ | Confirmed by real motor engagement plus a kprobe on `acc_write` |
| Auto-land trigger | `0x03/0x2a` | ⚠️ | Command is sent; the payload value that lands is unconfirmed. Manual stick landing works. |
| Return-to-Home | `0x03` | ✅ | |
| Beginner mode off | `0x03/0xf9` hash `0xde9b1b7b`=0 | ✅ | Captured off GO 4. Clears the beginner 30 m cap; the one limit write worth making. |
| 30 m cap: how it lifts | `0x11/0x43` handshake | ✅ | The aircraft has a hard firmware limit that is unlocked by a continuous DUML `0x11` (HMS) authentication handshake. The phone must send repeating `0x11/0x43` frames (~1 Hz) with a device-specific 16-byte token + per-frame signature. Without this handshake, the FC permanently locks to 30m. See [0x11 Handshake Discovery](docs/2026-07-06-0x11-handshake-discovery.md). |
| Set Home Point | `0x03/0x31` | ❌ | **Re-locks the 30 m cap on wm240; never send it** (live-confirmed). The aircraft records its own home. |
| Send mobile GPS to FC | `0x03/0x20` | ✅ | Streams the phone's position for dynamic-home / follow-me. This is **not** what lifts the 30 m cap; the earlier claim that it was is a false-confirm. |
| Get Home Point | `0x03/0x44` | ✅ | Read-only; response is home lon/lat as radian doubles plus the FC serial |
| Max height / radius (read + write) | `0x03/0xf8` + `0xf9` hashes | ✅ | 500 m / 8000 m confirmed; persist across reboot |
| Index/hash config table (643 params) | `0x03/0xe0`-`0xe3` as PC `0x0a` | ✅ | Full FC parameter access (sport tuning, wind resistance, and the rest) |
| Gimbal aim (speed) | `0x04/0x0c` | ✅ | On-screen drag-to-aim plus double-tap recenter |
| Gimbal absolute angle | `0x04/0x0a` | ❌ | Ignored by wm240; use the speed command |
| Camera mode/focus/AE/settings | `0x02` (cmd_set) | ✅ | Belongs on cmd_set `0x02`, not `0x01` |
| Camera capture / record | `0x01` | ✅ | Ground-truth via the `0x80` state push |
| Optical zoom (Mavic 2 Zoom) | `0x01/0xb8` | ⚠️ | Built for the Zoom; a no-op on the Pro, untested on a real Zoom |
| OSD General decode (attitude/GPS/batt/sats) | `0x03/0x43` | ✅ | Full layout confirmed |
| FC attitude/heading push | `0x03/0x6c` (~50 Hz) | ✅ | |
| Obstacle radar, front/rear distance | `0x03/0x6a` | ✅ | bytes 1-2 = front, 5-6 = rear; 3-4 and 7-8 = unused 2nd beam; 9-12 = lateral (ActiveTrack only). Confirmed with a moving obstacle. |
| RC button map | `0x06/0x51` | ✅ | Both triggers plus the 5-way dial, one bit each |
| Low-battery force-land / RTH thresholds | `0x03/0xf9` hashes | ✅ | Driven to minimum so the FC will not fight the pilot home |
| PC-identity (`0x0a`) to lift the cap | n/a | ❌ | Red herring. The cap behaves identically on rooted and non-rooted phones; `0x0a` is only needed for the config-table protocol. |
| Waypoint missions | `0x24` upload / `0x26` enable / `0x27` suspend | ⚠️ | Cataloged, not yet flight-tested |
| RC buzzer | RC push | ⚠️ | Experimental. The RC240 has a beeper, no speaker, so phone TTS is used instead. |

### Non-obvious hardware behaviors

- A full aircraft internal storage ("eMMC full") drops the aircraft into a limited flight envelope on its own. Formatting internal storage in DJI GO 4's settings clears it. Many test flights can fill it.
- **The 30 m hard limit is unlocked by a physical-device authentication handshake** (`cmd_set 0x11`, HMS frame `0x11/0x43`). The app must send repeating 84-byte auth frames containing a device-specific static token + per-frame cryptographic signature. Without this handshake running, the FC refuses to arm beyond 30m, regardless of beginner-mode, GPS, or any parameter write. This is a firmware-level physical-device gate, not a software policy. See [0x11 Handshake Discovery](docs/2026-07-06-0x11-handshake-discovery.md) for the frame structure and reverse-engineering notes.
- The RC240-to-phone link is USB Accessory (AOA), not host-CDC/RNDIS. The aircraft-direct path (CDC-ACM `/dev/ttyACM0`) is a separate bench link.

### Feature test status

✅ verified on real hardware · 🧪 built, not yet flight/use-tested · 🛠 partial. A feature is marked ✅ only after it has been exercised on the aircraft.

| Feature | Status |
|---|:--:|
| Takeoff (one-tap) / RTH / manual stick landing | ✅ |
| Full altitude and distance envelope (past 30 m) | ✅ |
| Live H.264 video and camera controls | ✅ |
| Gimbal drag-to-aim | ✅ |
| Gimbal double-tap recenter (speed-slew) | 🧪 |
| Telemetry / HUD / attitude indicator | ✅ |
| Obstacle radar, data | ✅ |
| Obstacle radar, directionality centering (new) | 🧪 |
| Map and FAA airspace overlays | ✅ |
| AI co-pilot, Nano push-to-talk | ✅ |
| AI co-pilot, cloud Gemini / Hybrid | 🧪 |
| Voice callouts (basic) | ✅ |
| Tunable per-category callouts and status read-out | 🧪 |
| Voice and speed picker | 🧪 |
| Weather-aware co-pilot context | 🧪 |
| Grid / orbit missions | 🛠 partially flown |
| Gallery (browse / select / share) | 🧪 |
| Media offload (WiFi / ADB) | 🧪 |
| Plugin system | 🧪 |
| Encrypted live-stream plugin | 🧪 (phone side built; server relay is user-provided) |

### Firmware seen

| Aircraft | Aircraft FW | RC FW | Status |
|---|---|---|---|
| Mavic 2 Pro + RC240 (primary dev unit) | not recorded | not recorded | ✅ primary test aircraft |
| Mavic 2 Zoom (a friend's) | `01.00.0797` | `01.00.0770` | ⚠️ versions read off a screenshot only, not yet probed for zoom/lockdown deltas |

---

## Connecting

1. Power on the RC240 and wait for it to finish booting (~15 s).
2. Plug the RC240 into the phone's USB-C port. GlassFalcon launches automatically and requests accessory permission.
3. Tap **Allow** (optionally check "always").
4. Power on the drone. Telemetry and video start within a few seconds. Let the aircraft get a solid GPS lock on the ground before takeoff: it lifts its own 30 m altitude cap once it has GPS plus a recorded home (which it self-records), with beginner mode off.

If the AOA permission dialog does not appear, try a different cable or port, or fall back to the MSDK connection mode above.

---

## Install (signed APK)

Grab the latest signed APK from [Releases](https://github.com/sworrl/GlassFalcon/releases). No build toolchain needed.

Verify it before flashing, both integrity and authenticity:

```bash
# 1. integrity: matches the published checksum (SHA256SUMS.txt in the release)
sha256sum GlassFalcon-*.apk

# 2. authenticity: signed by the project key (the same cert on every release)
apksigner verify --print-certs GlassFalcon-*.apk
#    expect  certificate SHA-256:  7d6c56f133e882149009fc0531adcff73d4b764f97fcc0e4dbcbbd39a23f1b4c
```

If the cert fingerprint does not match that value, do not install it. It was not signed by this project.

Flash it (enable USB debugging, connect the phone):

```bash
# one command: fetches the latest release APK (and adb if you don't have it), then installs
./tools/install.sh

# or directly
adb install -r GlassFalcon-*.apk
```

The signed release installs as `dev.glassfalcon`. A locally-built debug APK installs as `dev.glassfalcon.debug`, so the two coexist.

## Build (from source)

```bash
git clone https://github.com/sworrl/GlassFalcon.git
cd GlassFalcon/android

./gradlew assembleDebug
adb install app/build/outputs/apk/debug/*.apk
```

Requirements: Android SDK 34+, the included Gradle wrapper, a device on Android 8.0 (API 26) or newer.

Optional MSDK fallback key:

```bash
echo "DJI_APP_KEY=your_key_here" >> android/local.properties
```

The release build ships one ABI (`arm64-v8a`) and does not run on x86 emulators. Add `"x86_64"` to `abiFilters` in `app/build.gradle.kts` if you want emulator builds. R8/minify is off because this AGP's R8 cannot parse Kotlin 2.2 metadata yet; the release APK is signed but not shrunk.

---

## SDK

The DUML implementation is a reusable library, independent of the app:

| Component | Path | What |
|---|---|---|
| **Python SDK** | [`sdk/python`](sdk/python) | `glassfalcon` package, pip-installable, pure stdlib |
| **Android SDK** | [`android/sdk`](android/sdk) | `:sdk` Gradle module producing `sdk-release.aar` (DUML framing, telemetry decode, mission planning, no UI) |
| **Docs** | [`sdk/docs`](sdk/docs) | [Protocol reference](sdk/docs/protocol.md) · [Getting started](sdk/docs/getting-started.md) |

```bash
pip install -e sdk/python
```

```python
from glassfalcon import DUMLConnection, TelemetryDecoder, duml_cmds as C

conn = DUMLConnection.open_serial("/dev/ttyACM0")
dec = TelemetryDecoder()
conn.add_listener(dec.feed)
conn.send_cmd(C.get_device_state())
print(dec.state.battery_pct, dec.state.gps_sats, dec.state.lat, dec.state.lon)
```

The Android `:sdk` module mirrors the same protocol, telemetry, and mission logic in Kotlin (`Duml.kt`, `DumlCommands.kt`, `Telemetry.kt`, `MissionEngine.kt`).

```bash
cd android && ./gradlew :sdk:assembleRelease   # -> sdk/build/outputs/aar/sdk-release.aar
```

Licensed GPL-3.0-or-later ([`LICENSE`](LICENSE)).

---

## Hardware

The currently supported aircraft:

| Property | Value |
|---|---|
| Drone | DJI Mavic 2 Pro / Zoom |
| Model code | **wm240** |
| USB VID:PID | `2ca3:001f` (drone), `2ca3:0015` (RC240 as USB accessory) |
| Remote | RC240 |

Other DJI models identify with their own model code and USB IDs; adding one starts by capturing its DUML dialect. See [Supported models](#supported-models) and [Contributing](#contributing).

```bash
./tools/find-drone.sh   # read-only USB/serial detection helper, run after plugging in
```

---

## Architecture and DUML

Three layers: Compose UI plus `FlightViewModel`, then the `:sdk` (DUML framing, telemetry, mission), then transport. The SDK carries no Android UI dependency, which is why the same protocol code also ships as a Python package. The full wire-format reference and the running findings/gaps log are in [`sdk/docs/protocol.md`](sdk/docs/protocol.md).

### Transport

| Link | Path | Carries |
|---|---|---|
| RC240 to phone | Android Open Accessory (USB accessory, `2ca3:0015`) | DUML plus H.264, multiplexed |
| Aircraft to phone/PC | CDC-ACM serial `/dev/ttyACM0`, 115200 8N1 | raw DUML |
| PC relay / bench | TCP `IP:port` | raw DUML |

Over AOA the RC wraps everything in an outer `55 cc` frame; an inner two-byte type picks the sub-stream. `49 57` is DUML telemetry, `4a 57` is H.264 video. `Duml.dispatchAoaAcc` demuxes on that pair, routing telemetry to `TelemetryDecoder` and video payloads to `VideoDecoder`.

### DUML frame

```
55 │ LL Lv │ HC │ SRC DST │ SEQ SEQ │ CS CI │ payload… │ C16 C16
│    │       │     │   │      │        │  │              └── CRC-16 (poly 0x3692), whole frame
│    │       │     │   │      seq      │  cmd_id
│    │       │     │   │               cmd_set
│    │       │     src/dst device IDs (below)
│    │       └── CRC-8 (init 0x77) over the first 3 bytes
│    └── 10-bit length plus 6-bit protocol version
└── start byte 0x55
```

Device IDs: FC `0x03`, camera `0x01`, gimbal `0x04`, RC `0x06`, mobile app `0x02`, PC/Assistant `0x0a`. Most commands go out as `0x02`. The FC config-table protocol is only honored from `0x0a` (`Duml.sendAs`).

### Telemetry: OSD General (`0x03/0x43`, ~10 Hz)

Little-endian layout GlassFalcon decodes (`Telemetry.feed`):

| Offset | Field | Type |
|--:|---|---|
| 0 / 8 | longitude / latitude | f64, radians |
| 16 | height above ground | i16, 0.1 m |
| 18-22 | velocity N / E / D | i16, 0.1 m/s |
| 24-28 | pitch / roll / yaw | i16, 0.1° |
| 30 | `flyc_state` (low 7 bits); `0x80` = no RC | u8 |
| 32 | `controller_state` bitfield | u32 |
| 36 | GPS satellites | u8 |
| 38 | motor start-fail reason (low 7 bits) | u8 |
| 40 | battery % | u8 |

`controller_state` bits in use: `0x04` in-air, `0x08` motors on, `0x8000` GPS-used, `(x & 0x3C0000) >> 18` GPS signal level, `0x400` battery-requires-land, plus ESC-stall / ESC-empty / baro / ultrasonic fault bits. Bit `0x1000` ("IMU preheating") exists but reads as stuck garbage, so it is not surfaced (see the [Tested](#tested--not-tested) section).

### FC parameters: two addressing schemes

- **By name-hash.** `0x03/0xf7` info, `0xf8` read, `0xf9` write. Each parameter is addressed by a 32-bit hash of its name (for example `max_height` `0x0371238a`, `max_radius` `0x425c0a94`, beginner-mode `0xde9b1b7b`).
- **By index.** `0x03/0xe0`-`0xe3`, sent as PC `0x0a`. This enumerates all ~643 config-table params by index with name plus min/max/type, then reads and writes by index. It reaches sport-mode tuning, wind resistance, and the rest of the table without needing each hash.

### Video

`4a 57` payloads are Annex-B H.264. `VideoExtractor` strips DJI's proprietary SEI NAL types (`0x55`, `0xf0`, `0xba`, `0xff`) and any embedded DUML frames, groups the clean NALs into whole access units, and `VideoDecoder` feeds them to `MediaCodec` rendering onto a `TextureView`. That same clean access-unit stream is the tap point for the encrypted-stream plugin.

<!-- BEGIN DUML COMMAND TOME (generated by tools/gen_tome.py — do not hand-edit inside markers) -->
## DUML Command Tome

A living dictionary of the DJI **DUML** (DJI Universal Markup Language) wire protocol as
spoken by the wm240 (Mavic 2) and RC240. Command names are sourced from the third-party
[o-gs/dji-firmware-tools](https://github.com/o-gs/dji-firmware-tools) wireshark dissectors
(not ours, credited here); **the bold annotations and decoded payloads are our own findings**
from live captures on this aircraft. Regenerate with `tools/gen_tome.py`; update as we discover more.

### Frame format

```
[0]      0x55                       magic
[1:3]    length (10-bit LE) + version (upper 6 bits of byte 2)
[3]      header CRC8  (seed 0x77, poly table from comm_dat2pcap)
[4]      src device type
[5]      dst device type
[6:8]    sequence number (LE)
[8]      cmd_type   0x40 = request, 0x80 = response/ack
[9]      cmd_set
[10]     cmd_id
[11:-2]  payload
[-2:]    CRC16-MCRF4XX (seed 0x3692)
```

### Device / module types (src & dst byte)

| id | device | | id | device |
|----|--------|-|----|--------|
| 0x00 | Any | | 0x10 | 68013 Ground |
| 0x01 | Camera | | 0x11 | MVO (mono vision) |
| 0x02 | Mobile App | | 0x12 | SVO (stereo vision) |
| 0x03 | Flight Controller | | 0x13 | FPGA Sky |
| 0x04 | Gimbal | | 0x14 | FPGA Ground |
| 0x05 | Center Board | | 0x15 | FPGA Sim |
| 0x06 | Remote Radio (RC) | | 0x16 | Station |
| 0x07 | WiFi | | 0x17 | XU |
| 0x08 | DM3xx Sky | | 0x18 | WTF |
| 0x09 | MCU Sky | | 0x19 | IMU |
| 0x0a | PC / Assistant Tool | | 0x1a | GPS |
| 0x0b | Battery | | 0x1b | WiFi Ground |
| 0x0c | ESC | | 0x1c | Signal Converter |
| 0x0d | DM368 Ground | | 0x1d | PMU |
| 0x0e | OFDM Ground | | 0x1e | Unknown30 |
| 0x0f | 68013 Sky | | 0x1f | WM330/WM220 |


> **Identities that matter on this bird:** the FC answers the **PC/assistant identity `src=0x0a`**
> over `/dev/ttyACM0` (raw DUML, no session) and the **Mobile App identity `src=0x02`** over the
> phone's AOA link. The TCP path (192.168.42.2:10000) speaks the 44bb/55aa session protocol and
> requires the MSDK AES handshake — we do not use it.


### ⭐ The 30 m "kid mode" cap — current understanding (NOT a replayable DUML command)

Field-confirmed behaviour: GlassFalcon alone stays clamped to ~30 m in height **and** radius;
only after DJI GO4 connects once does the FC unlock, and it then persists for that power cycle
so a swapped-in GlassFalcon phone inherits it. Every app-side lever is already healthy while
capped — `flycState=0x06` (GPS_Atti), `gpsUsed=true`, `startFail=0x00` (**FC gives no limiting
reason**), beginner off, max_height=500, max_radius=8000, GPS streaming — yet the cap holds.

**STRONG EVIDENCE (field, 2026-07-06): the gate is a cryptographic app-authentication handshake,
NOT replayable; beginner-off is necessary but not sufficient.** A P10 cold-start (GlassFalcon only,
DJI GO 4 not even installed, fresh power-cycle, beginner-off) stayed capped — but the P10 ran an
older GlassFalcon build, so a same-build P8 cold start (GO 4 never opened that cycle) is still needed
to isolate crypto-gate from build. A large power-cycle handshake sample is being gathered to
characterize the exchange. GO 4's connect runs a high-entropy app-auth exchange: the app streams `0x11/0x43`
(84 B, ~1/s) and `0x11/0x15` (64 B) to the FC, which replies only with short acks (`0x11/0x19`,
`0x11/0x14`, `0x11/0x16`) — the app authenticates itself, it does not answer an FC challenge. The
full frames are now captured whole (2026-07-06 wide 256-byte `acc_write` kprobe, local
`captures/2026-07-06-unlock-session/`; the old 32-byte probe truncated them): `0x11/0x43` = 4 B
length + 32 B per-frame-varying + a **per-device** 16-byte token (`d300…16a5`, identical across
sessions) + 32 B per-frame-varying. Replaying is rejected; reproducing it needs DJI's signing keys.
This matches every symptom: trust-state cap (not a flight condition), per-power-cycle persistence,
and "only DJI GO breaks it" (only DJI can answer the FC challenge with its keys).

**Single-command replays already TESTED on the drone and RULED OUT** (aircraft stayed capped):
`0x03/0xf5` real-name, `0x03/0xda` FlyC-detection, `0x03/0x3f` short NoFly, `0x03/0xcd`. These are
part of GO4's connect but are **not** the gate — do not re-chase them as unlock candidates.

Also independently ruled out as the cause: beginner-mode flag (toggling it is purely
`0x03/0xf9 de9b1b7b 00/01`, nothing rides along), phone GPS (GO4 flies full-envelope on an iPad
with no GPS/data), and home-point — in fact **sending `0x03/0x31 Set Home RE-LOCKS the cap**, so
GlassFalcon must never send it. A full eMMC or a geo-zone can independently limit the envelope.

**Bottom line:** breaking the cap without DJI GO means answering the FC's activation crypto —
an RE effort against DJI's keys/SDK, not a DUML packet we can send. Stop chasing single-command
unlocks. The per-command detail below is kept for reference, tagged with test status.


### cmd_set 0x00 — General  (76 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | Ping |  |
| 0x01 | Version Inquiry | **Version Inquiry.** Send to any dst; plaintext reply carries status + version bytes + ASCII model/serial. Confirmed live on /dev/ttyACM0: FC serial 163DF7X0018B66, Camera WM240, Gimbal GB11, VTx 'DJI P1 HDVT', Battery WM240_TOF_v2. This is how firmware version is read — no crypto needed. |
| 0x02 | Push Param Set |  |
| 0x03 | Push Param Get |  |
| 0x04 | Push Param Start |  |
| 0x05 | Multi Param Set | Multiple prarams set at once |
| 0x06 | Multi Param Get | Multiple prarams get at once |
| 0x07 | Enter Loader | Enter Upgrade Mode / Firmware Upgrade Entry |
| 0x08 | Update Confirm | Upgrade Prepare / Firmware Upgrade Procedure Start |
| 0x09 | Update Transmit | Firmware Upgrade Data Transmission |
| 0x0a | Update Finish | Firmware Upgrade Verify |
| 0x0b | Reboot Chip |  |
| 0x0c | Get Device State | get run status(loader, app) |
| 0x0d | Set Device Version | HardwareId |
| 0x0e | Heartbeat/Log Message | It can transmit text messages from FC, but is usually empty |
| 0x0f | Upgrade Self Request | Upgrade Consistency / Check Upgrade Compatibile |
| 0x10 | Set SDK Std Msgs Frequency |  |
| 0x20 | File List |  |
| 0x21 | File Info |  |
| 0x22 | File Send |  |
| 0x23 | File Receive | See m0101 sys partiton for payload info |
| 0x24 | File Sending |  |
| 0x25 | File Segment Err | File Receive Segment Fail |
| 0x26 | FileTrans App 2 Camera | See m0101 sys partiton for payload info |
| 0x27 | FileTrans Camera 2 App |  |
| 0x28 | FileTrans Delete |  |
| 0x2a | FileTrans General Trans |  |
| 0x30 | Encrypt Config |  |
| 0x32 | Activate Config | Activation Action — **Activate Config / Activation Action.** GO4 sends to FC (0x03) early in connect (payloads `00`, `11`) and to 0x88 (`31`). Part of GO4's connect but not proven to be the 30 m gate (see the crypto-handshake conclusion above). |
| 0x33 | MFi Cert |  |
| 0x34 | Safe Communication |  |
| 0x40 | Fw Update Desc Push |  |
| 0x41 | Fw Update Push Control |  |
| 0x42 | Fw Upgrade Push Status |  |
| 0x43 | Fw Upgrade Finish |  |
| 0x45 | Sleep Control |  |
| 0x46 | Shutdown Notification | aka Disconnect Notifiation |
| 0x47 | Power State | aka Reboot Status |
| 0x48 | LED Control |  |
| 0x4a | Set Date/Time | Set Date/Time. GO4 sends to several devices; payload e.g. `ea0707060a2a33` = packed date-time. |
| 0x4b | Get Date/Time |  |
| 0x4c | Get Module Sys Status | Get Aging Test Status |
| 0x4d | Set RT |  |
| 0x4e | Get RT |  |
| 0x4f | Get Cfg File | Get Cfg File. GO4 polls this (payload `0100000000ffffffff`). |
| 0x50 | Set Serial Number |  |
| 0x51 | Get Serial Number |  |
| 0x52 | Set Gps Push Config |  |
| 0x53 | Push Gps Info |  |
| 0x54 | Get Temperature Info |  |
| 0x55 | Get Alive Time |  |
| 0x56 | Over Temperature | Push Temperature Warning |
| 0x57 | Send Network Info |  |
| 0x58 | Time Sync | Get Ack Of Timestamp |
| 0x59 | Test Mode |  |
| 0x5a | Play Sound |  |
| 0x5c | UAV Fly Info |  |
| 0x60 | Auto Test Info |  |
| 0x61 | Set Product Newest Ver |  |
| 0x62 | Get Product Newest Ver |  |
| 0xef | Send Reserved Key |  |
| 0xf0 | Log Push |  |
| 0xf1 | Component Self Test State | The component is identified by sender field |
| 0xf2 | Log Control Global |  |
| 0xf3 | Log Control Module |  |
| 0xf4 | Test Start |  |
| 0xf5 | Test Stop |  |
| 0xf6 | Test Query Result |  |
| 0xf7 | Push Test Result |  |
| 0xf8 | Get Metadata |  |
| 0xfa | Log Control |  |
| 0xfb | Selftest State |  |
| 0xfc | Selftest State Count |  |
| 0xfd | Dump Frame Buffer | or Autotest Error Inject? |
| 0xfe | Self Define | Pure Transfer From Mc To App |
| 0xff | Query Device Info | Asks a component for identification string / Build Info(Date, Time, Type) — Query Device Info (build date/time/type string). |


### cmd_set 0x01 — Special / App Control  (12 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | Sdk Ctrl Mode Open/Close Nav |  |
| 0x01 | Old Special App Control | Try To Exec V1 Special Function |
| 0x02 | Old Special Remote Control |  |
| 0x03 | New Special App Control | Try To Exec V2 Special Function |
| 0x04 | New Special Remote Control | ie. Ctrl Mode Emergency Brake |
| 0x05 | SDK Ctrl Mode Arm/Disarm |  |
| 0x1a | SDK Ctrl Gimbal Speed Ctrl |  |
| 0x1b | SDK Ctrl Gimbal Angle Ctrl |  |
| 0x20 | SDK Ctrl Camera Shot Ctrl |  |
| 0x21 | SDK Ctrl Camera Start Video Ctrl |  |
| 0x22 | SDK Ctrl Camera Stop Video Ctrl |  |
| 0xff | UAV Loopback |  |


### cmd_set 0x02 — Camera  (206 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x01 | Do Capture Photo |  |
| 0x02 | Do Record |  |
| 0x03 | HeartBeat |  |
| 0x04 | Set Usb Switch | Usb Connect |
| 0x05 | Virtual Key Send |  |
| 0x06 | Get Usb Switch |  |
| 0x10 | Camera Work Mode Set |  |
| 0x11 | Camera Work Mode Get |  |
| 0x12 | Photo Format Set |  |
| 0x13 | Photo Format Get |  |
| 0x14 | Photo Quality Set |  |
| 0x15 | Photo Quality Get |  |
| 0x16 | Photo Storage Fmt Set |  |
| 0x17 | Photo Storage Fmt Get |  |
| 0x18 | Video Format Set |  |
| 0x19 | Video Format Get |  |
| 0x1a | Video Quality Set |  |
| 0x1b | Video Quality Get |  |
| 0x1c | Video Storage Fmt Set |  |
| 0x1d | Video Storage Fmt Get |  |
| 0x1e | Exposure Mode Set |  |
| 0x1f | Exposure Mode Get |  |
| 0x20 | Scene Mode Set |  |
| 0x21 | Scene Mode Get |  |
| 0x22 | AE Meter Set |  |
| 0x23 | AE Meter Get |  |
| 0x24 | Focus Mode Set |  |
| 0x25 | Focus Mode Get |  |
| 0x26 | Aperture Size Set |  |
| 0x27 | Aperture Size Get |  |
| 0x28 | Shutter Speed Set |  |
| 0x29 | Shutter Speed Get |  |
| 0x2a | ISO Set |  |
| 0x2b | ISO Get |  |
| 0x2c | White Balance Env Set |  |
| 0x2d | White Balance Env Get |  |
| 0x2e | Exposition Bias Set | Ev Bias Set |
| 0x2f | Exposition Bias Get |  |
| 0x30 | Focus Region Set |  |
| 0x31 | Focus Region Get |  |
| 0x32 | AE Meter Region Set | Auto Exposition (Ev) Meter Region Set |
| 0x33 | AE Meter Region Get |  |
| 0x34 | Zoom Param Set |  |
| 0x35 | Zoom Param Get |  |
| 0x36 | Flash Mode Set |  |
| 0x37 | Flash Mode Get |  |
| 0x38 | Sharpeness Set |  |
| 0x39 | Sharpeness Get |  |
| 0x3a | Contrast Set |  |
| 0x3b | Contrast Get |  |
| 0x3c | Saturation Set |  |
| 0x3d | Saturation Get |  |
| 0x3e | Hue Set | Color Tonal Set |
| 0x3f | Hue Get |  |
| 0x40 | Face Detect Set |  |
| 0x41 | Face Detect Get |  |
| 0x42 | Digital Effect Set | Digital Filter Set |
| 0x43 | Digital Effect Get |  |
| 0x44 | Digital Denoise Set |  |
| 0x45 | Digital Denoise Get |  |
| 0x46 | Anti Flicker Set |  |
| 0x47 | Anti Flicker Get |  |
| 0x48 | Multi Cap Param Set | Continuous Shoot Set |
| 0x49 | Multi Cap Param Get |  |
| 0x4a | Conti Cap Param Set | Continuous Shoot Time Options Set |
| 0x4b | Conti Cap Param Get |  |
| 0x4c | Hdmi Output Param Set | LCD/HDMI video output (Vout) format set |
| 0x4d | Hdmi Output Param Get |  |
| 0x4e | Quickview Param Set | Quick Playback Options Set |
| 0x4f | Quickview Param Get |  |
| 0x50 | OSD Param Set |  |
| 0x51 | OSD Param Get |  |
| 0x52 | Preview OSD Param Set |  |
| 0x53 | Preview OSD Param Get |  |
| 0x54 | Camera Date/Time Set |  |
| 0x55 | Camera Date/Time Get |  |
| 0x56 | Language Param Set |  |
| 0x57 | Language Param Get |  |
| 0x58 | Camera GPS Set |  |
| 0x59 | Camera GPS Get |  |
| 0x5a | Discon State Set |  |
| 0x5b | Discon State Get |  |
| 0x5c | File Index Mode Set |  |
| 0x5d | File Index Mode Get |  |
| 0x5e | AE bCap Param Set |  |
| 0x5f | AE bCap Param Get |  |
| 0x60 | Histogram Set | Push Chart Set |
| 0x61 | Histogram Get |  |
| 0x62 | Video Subtitles Set | Video Caption Set |
| 0x63 | Video Subtitles Get |  |
| 0x64 | Video Subtitles Log Set |  |
| 0x65 | Mgear Shutter Speed Set | Shutter Speed Limit Set |
| 0x66 | Video Standard Set |  |
| 0x67 | Video Standard Get |  |
| 0x68 | AE Lock Status Set | Auto Exposure Preserve Set |
| 0x69 | AE Lock Status Get |  |
| 0x6a | Photo Capture Type Set | Photo Mode Set |
| 0x6b | Photo Capture Type Get |  |
| 0x6c | Video Record Mode Set |  |
| 0x6d | Video Record Mode Get |  |
| 0x6e | Panorama Mode Set |  |
| 0x6f | Panorama Mode Get |  |
| 0x70 | System State Get |  |
| 0x71 | SDcard Info Get |  |
| 0x72 | SDcard Do Format |  |
| 0x73 | SDcard Format Progress Get |  |
| 0x74 | Fw Upgrade Progress Get |  |
| 0x75 | Photo Sync Progress Get |  |
| 0x76 | Camera Power Info Get |  |
| 0x77 | Settings Save | Save Preferences |
| 0x78 | Settings Load |  |
| 0x79 | File Delete | Photo Erase |
| 0x7a | Video Play Control |  |
| 0x7b | Thumbnail 2 Single Ctrl | Single Play Choice |
| 0x7c | Camera Shutter Cmd | Telectrl Action |
| 0x7d | PB Zoom Ctrl | Scale Gesture |
| 0x7e | PB Pic Drag Ctrl | Drag Gesture |
| 0x80 | Camera State Info | Camera Status Push |
| 0x81 | Camera Shot Params | Cap Params Push |
| 0x82 | Camera PlayBack Params |  |
| 0x83 | Camera Chart Info | Histogram Params Push |
| 0x84 | Camera Recording Name | Video Name Push |
| 0x85 | Camera Raw Params | Raw Camera Status Push |
| 0x86 | Camera Cur Pano Status | Panorama FileName Push |
| 0x87 | Camera Shot Info | Lens Info Push |
| 0x88 | Camera Timelapse Parms | TimeLapse Info Push |
| 0x89 | Camera Tracking Status | Camera Tracking Params Push |
| 0x8a | Camera FOV Param |  |
| 0x8b | Racing Liveview Format Set |  |
| 0x8c | Racing Liveview Format Get |  |
| 0x90 | Sensor Calibrate Test | Check Sensor Test |
| 0x91 | Sensor Calibrate Complete | If Cali |
| 0x92 | Video Clip Info Get | Video Params Get |
| 0x93 | TransCode Control | Xcode Ctrl |
| 0x94 | Focus Range Get |  |
| 0x95 | Focus Stroke Set | VCM Pos Set |
| 0x96 | Focus Stroke Get |  |
| 0x98 | FileSystem Info Get | File Params Get |
| 0x99 | Shot Info Get |  |
| 0x9a | Focus Aid Set |  |
| 0x9b | Video Adaptive Gamma Set | Video Contrast Enhance Set |
| 0x9c | Video Adaptive Gamma Get |  |
| 0x9d | Awb Meter Region Set | White Balance Area Set |
| 0x9e | Awb Meter Region Get |  |
| 0x9f | Audio Param Set |  |
| 0xa0 | Audio Param Get |  |
| 0xa1 | Format Raw SSD |  |
| 0xa2 | Focus Distance Set |  |
| 0xa3 | Calibration Control Set |  |
| 0xa4 | Focus Window Set |  |
| 0xa5 | Tracking Region Get |  |
| 0xa6 | Tracking Region Set |  |
| 0xa7 | Iris Set |  |
| 0xa8 | AE Unlock Mode Set |  |
| 0xa9 | AE Unlock Mode Get |  |
| 0xaa | Pano File Params Get |  |
| 0xab | Video Encode Set |  |
| 0xac | Video Encode Get |  |
| 0xad | MCTF Set |  |
| 0xae | MCTF Get |  |
| 0xaf | SSD Video Format Set |  |
| 0xb0 | SSD Video Format Get |  |
| 0xb1 | Record Fan Set |  |
| 0xb2 | Record Fan Get |  |
| 0xb3 | Request IFrame | Request key frame; useful if some video data was dropped |
| 0xb4 | Camera Prepare Open Fan |  |
| 0xb5 | Camera Sensor Id Get |  |
| 0xb6 | Forearm Lamp Config Set | ForeArm LED Set |
| 0xb7 | Forearm Lamp Config Get |  |
| 0xb8 | Camera Optics Zoom Mode |  |
| 0xb9 | Image Rotation Set | Set Camera Rotation Mode |
| 0xba | Image Rotation Get |  |
| 0xbb | Gimbal Lock Config Set | Lock Gimbal When Shot Set |
| 0xbc | Gimbal Lock Config Get |  |
| 0xbd | Old Cam LCD Format Set | Raw Video Format Set |
| 0xbe | Old Cam LCD Format Get |  |
| 0xbf | File Star Flag Set |  |
| 0xc0 | MFDemarcate |  |
| 0xc1 | Log Mode Set |  |
| 0xc2 | Param Name Set |  |
| 0xc3 | Param Name Get |  |
| 0xc4 | Camera Tap Zoom Set |  |
| 0xc5 | Camera Tap Zoom Get |  |
| 0xc6 | Camera Tap Zoom Target Set |  |
| 0xc7 | Camera Tap Zoom State Info |  |
| 0xc8 | Defog Enabled Set |  |
| 0xc9 | Defog Enabled Get |  |
| 0xca | Raw Equip Info Set |  |
| 0xcc | SSD Raw Video Digital Filter Set |  |
| 0xce | Calibration Control Get |  |
| 0xcf | Mechanical Shutter Set |  |
| 0xd0 | Mechanical Shutter Get |  |
| 0xd1 | Cam DCF Abstract Push | Push DFC Info Get |
| 0xd2 | Dust Reduction State Set |  |
| 0xd3 | Camera UnknownD3 |  |
| 0xdd | ND Filter Set |  |
| 0xde | Raw New Param Set |  |
| 0xdf | Raw New Param Get |  |
| 0xe0 | Capture Sound | Capability Range Get |
| 0xe1 | Capture Config Set |  |
| 0xe2 | Capture Config Get |  |
| 0xf0 | Camera TBD F0 | Supported in P3X |
| 0xf1 | Camera Tau Param |  |
| 0xf2 | Camera Tau Param Get | Push Tau Factor Get |
| 0xf9 | Focus Infinite Get |  |
| 0xfa | Focus Infinite Set |  |


### cmd_set 0x03 — Flight Controller (FLYC)  (191 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | FlyC Scan/Test |  |
| 0x01 | FlyC Status Get |  |
| 0x02 | FlyC Params Get |  |
| 0x03 | Origin GPS Set |  |
| 0x04 | Origin GPS Get |  |
| 0x05 | GPS Coordinate Get | Sim Command |
| 0x06 | Fly Limit Param Set |  |
| 0x07 | Fly Limit Param Get |  |
| 0x08 | Nofly Zone Set | Set Fly Forbid Area |
| 0x09 | Nofly Status Get | FlyC Forbid Status |
| 0x0a | Battery Status Get | Nvt Battary Status |
| 0x0b | Motor Work Status Set |  |
| 0x0c | Motor Work Status Get |  |
| 0x0d | Statistical Info Save | Have Checked Struct Set |
| 0x0e | Emergency Stop |  |
| 0x10 | A2 Push Commom | or FC Config Group Set? |
| 0x11 | Sim Rc | or FC Config Group Get? |
| 0x16 | Sim Status |  |
| 0x1c | Date and Time Set | Date and Time Set (to FC). GO4 payload `0a2a33ea070706`. |
| 0x1d | Initialize Onboard FChannel |  |
| 0x1e | Get Onboard FChannel Output | Get Onboard FChannel Output Value |
| 0x1f | Set Onboard FChannel Output | Set Onboard FChannel Output Value |
| 0x20 | Send GPS To Flyc | **Send GPS To Flyc.** 13-byte payload, all little-endian: `03 \| lat*1e6 int32 \| lon*1e6 int32 \| unix_epoch int32`. GlassFalcon sends this at 2 Hz. Previously believed to be THE 30 m unlock; field test shows it is necessary but NOT sufficient on its own. |
| 0x21 | UAV Status Get |  |
| 0x22 | Upload Air Route |  |
| 0x23 | Download Air Route |  |
| 0x24 | Upload Waypoint |  |
| 0x25 | Download Waypoint |  |
| 0x26 | Enable Waypoint |  |
| 0x27 | Exec Fly |  |
| 0x28 | One Key Back |  |
| 0x29 | Joystick | Virtual Stick / Joystick — direct velocity/attitude control. GlassFalcon uses for scripted flight. |
| 0x2a | Function Control | sets g_real.wm610_app_command.function_command and function_command_state to 1 |
| 0x2b | IOC Mode Type Set | Intelligent Orientation Control Mode |
| 0x2c | IOC Mode Type Get |  |
| 0x2d | Limit Params Set |  |
| 0x2e | Limit Params Get |  |
| 0x2f | Battery Voltage Alarm Set | Set Voltage Warnning |
| 0x30 | Battery Voltage Alarm Get | Get Voltage Warnning |
| 0x31 | UAV Home Point Set | AC/RC/APP — Set Home Point. ⚠️ RE-LOCKS the 30 m cap on wm240 — GlassFalcon never sends it. |
| 0x32 | FlyC Deform Status Get | Push Foot Stool Status |
| 0x33 | UAV User String Set | Set Plane Name |
| 0x34 | UAV User String Get | Get Plane Name |
| 0x35 | Change Param Ping |  |
| 0x36 | Request SN |  |
| 0x37 | Device Info Get |  |
| 0x38 | Device Info Set |  |
| 0x39 | Enter Flight Data Mode | Switches the mode; response contains 1-byte payload - error code, 0 on success |
| 0x3a | Ctrl Fly Data Recorder | ie Format the recorder |
| 0x3b | RC Lost Action Set | Set Fs Action |
| 0x3c | RC Lost Action Get | Get Fs Action |
| 0x3d | Time Zone Set | for Recorder |
| 0x3e | FlyC Request Limit Update |  |
| 0x3f | Set NoFly Zone Data | Set Fly Forbid Area Data — Set NoFly Zone Data (geofence upload). GO4 payload `0001000000`. |
| 0x41 | Upload Unlimit Areas | Set Whitelist Cmd |
| 0x42 | FlyC Unlimit State / UAV Posture | Push Unlimit Areas (WM220) / Push UAV Posture (P3X) |
| 0x43 | OSD General Data Get |  |
| 0x44 | OSD Home Point Get |  |
| 0x45 | FlyC GPS SNR Get |  |
| 0x46 | FlyC GPS SNR Set | Enable GPS SNR |
| 0x47 | Enable Unlimit Areas | Toggle Whitelist |
| 0x49 | Push Encrypted Package |  |
| 0x4a | Push Att IMU Info |  |
| 0x4b | Push RC Stick Value |  |
| 0x4c | Push Fussed Pos Speed Data |  |
| 0x50 | Imu Data Status |  |
| 0x51 | FlyC Battery Status Get | Smart Battery Status |
| 0x52 | Smart Low Battery Actn Set | Set Battery Alarm Action / Low Bat Departure Cnf Cancel |
| 0x53 | FlyC Vis Avoidance Param Get | Push Visual Avoidance Info |
| 0x55 | FlyC Limit State Get |  |
| 0x56 | FlyC LED Status Get |  |
| 0x57 | GPS GLNS Info |  |
| 0x58 | Push Att Stick Speed Pos Data |  |
| 0x59 | Push Sdk Data |  |
| 0x5a | Push FC Data |  |
| 0x60 | SVO API Transfer |  |
| 0x61 | FlyC Activation Info | Sdk Activation Info or Request |
| 0x62 | FlyC Activation Exec | Sdk Activation or Activation Result |
| 0x63 | FlyC On Board Recv |  |
| 0x64 | Send On Board Set | SDK Pure Transfer From App To MC |
| 0x67 | FlyC Power Param Get | Motive Power Info |
| 0x69 | RTK Switch | Handle App To Rtk Pack |
| 0x6a | FlyC Avoid | or Battery Valid State Set? |
| 0x6b | Recorder Data Cfg |  |
| 0x6c | FlyC RTK Location Data Get |  |
| 0x6d | Upload Hotpoint |  |
| 0x6e | Download Hotpoint |  |
| 0x70 | Set Product SN | Some licensing string check |
| 0x71 | Get Product SN | Some licensing string check |
| 0x72 | Reset Product SN |  |
| 0x73 | Set Product Id |  |
| 0x74 | Get Product Id |  |
| 0x75 | Write EEPROM FC0 |  |
| 0x76 | Read EEPROM FC0 |  |
| 0x80 | Navigation Mode Set | Mission On/Off |
| 0x81 | Mission IOC: Set Lock Yaw |  |
| 0x82 | Miss. WP: Upload Mission Info | Set WayLine Mission Length, Upload WayPoint Mission Msg |
| 0x83 | Miss. WP: Download Mission Info | Download WayLine Mission Info, Download WayPoint Mission Msg |
| 0x84 | Upload Waypoint Info By Idx | Upload WayPoint Msg By Index |
| 0x85 | Download Waypoint Info By Idx | Download WayPoint Msg By Index |
| 0x86 | Mission WP: Go/Stop | Start Or Cancel WayLine Mission |
| 0x87 | Mission WP: Pasue/Resume | Pause Or Continue WayLine Mission |
| 0x88 | Push Navigation Status Info | FlyC WayPoint Mission Info |
| 0x89 | Push Navigation Event Info | FlyC WayPoint Mission Current Event |
| 0x8a | Miss. HotPoint: Start With Info | Start HotPoint Mission With Info |
| 0x8b | Miss. HotPoint: Cancel | Stop HotPoint Mission |
| 0x8c | Miss. HotPoint: Pasue/Resume | HotPoint Mission Switch |
| 0x8d | App Set API Sub Mode |  |
| 0x8e | App Joystick Data |  |
| 0x8f | Noe Mission pasue/resume | Noe Mission Pause Or Resume |
| 0x90 | Miss. Follow: Start With Info | Start Follow Me With Info |
| 0x91 | Miss. Follow: Cancel | or Stop Follow Me Mission |
| 0x92 | Miss. Follow: Pasue/Resume | Follow Me Mission Switch |
| 0x93 | Miss. Follow: Get Target Info | Send GPS Info on Target |
| 0x94 | Mission Noe: Start |  |
| 0x95 | Mission Noe: Stop |  |
| 0x96 | Mission HotPoint: Download |  |
| 0x97 | Mission IOC: Start |  |
| 0x98 | Mission IOC: Stop |  |
| 0x99 | Miss. HotPoint: Set Params | Set Default Velocity / Speed and Direction |
| 0x9a | Miss. HotPoint: Set Radius |  |
| 0x9b | Miss. HotPoint: Set Head | HotPoint Reset Camera |
| 0x9c | Miss. WP: Set Idle Veloc | Set WayLine Flight Idle Value / Idle Speed |
| 0x9d | Miss. WP: Get Idle Veloc | Get WayLine Flight Idle Value / Idle Speed |
| 0x9e | App Ctrl Mission Yaw Rate |  |
| 0x9f | Miss. HotPoint: Auto Radius Ctrl |  |
| 0xa0 | Send AGPS Data |  |
| 0xa1 | FlyC AGPS Status Get |  |
| 0xa2 | Race Drone OSD Push |  |
| 0xa3 | Miss. WP: Get BreakPoint Info |  |
| 0xa4 | Miss. WP: Return To Cur Line |  |
| 0xa5 | App Ctrl Fly Sweep Ctrl |  |
| 0xa6 | Set RKT Homepoint |  |
| 0xaa | Sbus Packet |  |
| 0xab | Ctrl Attitude Data Send | Set Attitude |
| 0xac | Ctrl Taillock Data Send | Set Tail Lock |
| 0xad | FlyC Install Error Get |  |
| 0xae | Cmd Handler RC App Chl Handler |  |
| 0xaf | Product Config |  |
| 0xb0 | Get Battery Groups Single Info |  |
| 0xb5 | FlyC Fault Inject Set | FIT Set Parameter / Fdi Input |
| 0xb6 | FlyC Fault Inject Get | Change Dev Colour |
| 0xb7 | Redundancy IMU Index Set and Get | RNS Set Parameter |
| 0xb8 | Redundancy Status | RNS Get State |
| 0xb9 | Push Redundancy Status |  |
| 0xba | Forearm LED Status Set |  |
| 0xbb | Open LED Info Get |  |
| 0xbc | Open Led Action Register |  |
| 0xbd | Open Led Action Logout |  |
| 0xbe | Open Led Action Status Set |  |
| 0xbf | Flight Push | Imu Cali Api Handler |
| 0xc6 | Shell Test |  |
| 0xcd | Update Nofly Area | Update Flyforbid Area |
| 0xce | Push Forbid Data Infos | FMU Api Get Db Info |
| 0xcf | New Nofly Area Get | Get New Flyforbid Area |
| 0xd4 | Additional Info Get | Get Moto Speed |
| 0xd7 | FlyC Flight Record Get | Record Log |
| 0xd9 | Process Sensor Api Data |  |
| 0xda | FlyC Detection | Handler Monitor Cmd Set — FlyC Detection / Handler Monitor. GO4 streams this ~5 s with a rolling 17-byte payload (`0dc7dc4f…`) plus short forms (`09`, `0a01`, `0c`). ⛔ **TESTED on the drone and RULED OUT as the 30 m unlock** — replaying it left the aircraft capped. |
| 0xdf | Assistant Unlock Handler |  |
| 0xe0 | Config Table: Get Tbl Attribute | Get Table Attribs (index protocol): table_no -> param count. |
| 0xe1 | Config Table: Get Item Attribute | returns parameter name and properties post-mavic — Get Param Info By Index: (table_no, idx) -> type/size/min/max/def/name. |
| 0xe2 | Config Table: Get Item Value | Read Param Val By Index: (table_no, 1, idx) -> value. Unlocks all 643 FLYC params without hashes. |
| 0xe3 | Config Table: Set Item Value | Write Param Val By Index: (table_no, 1, idx, value). |
| 0xe4 | Config Table: Reset Def. Item Value |  |
| 0xe5 | Push Config Table: Get Tbl Attribute |  |
| 0xe6 | Push Config Table: Get Item Attribute |  |
| 0xe7 | Push Config Table: Set Item Param |  |
| 0xe8 | Push Config Table: Clear |  |
| 0xe9 | Config Command Table: Get or Exec | Cmd Handler/Get Attribute/Set Flyforbid Data? |
| 0xea | Register Open Motor Error Action |  |
| 0xeb | Logout Open Motor Error Action |  |
| 0xec | Set Open Motor Error Action Status |  |
| 0xed | ESC Echo Set |  |
| 0xee | GoHome CountDown Get | Ost Sats Go Home Port |
| 0xf0 | Config Table: Get Param Info by Index | aka Update Param; returns parameter name and properties pre-mavic |
| 0xf1 | Config Table: Read Params By Index | aka Query Param |
| 0xf2 | Config Table: Write Params By Index |  |
| 0xf3 | Config Table: Reset Default Param Val | Reset Params By Index/Reset Old Config Table Item Value? |
| 0xf4 | Config Table: Set Item By Index |  |
| 0xf5 | Ver Phone Set | Set/Get Real Name Info — Ver Phone Set / Real-Name Info. GO4 payload at t+6.25s `0110db4b6aad…` (embeds a timestamp). ⛔ **TESTED and RULED OUT as the 30 m unlock.** |
| 0xf6 | Push Param PC Log |  |
| 0xf7 | Config Table: Get Param Info By Hash | Get Old Config Table Item Info By Hash Value — Read Param Info By Hash. Returns type/size/min/max/def + name for a param hash. |
| 0xf8 | Config Table: Read Param By Hash | Get Single Param Value By Hash — Read Param By Hash (2015 style). Reply: `status(1) \| hash(4 LE) \| value`. Live-confirmed: beginner_mode(0xde9b1b7b)=0, max_height(0x0371238a)=500, max_radius(0x425c0a94)=8000, level_1_voltage(0x5aae5bcd)=10%, level_2_voltage(0x5ac75bcd)=10%. |
| 0xf9 | Config Table: Write Param By Hash | Set Single Param Value By Hash — Write Param By Hash. Used to set beginner_mode=0 etc. |
| 0xfa | Config Table: Reset Params By Hash | Reset Old Config Table Item Value By Hash Value |
| 0xfb | Config Table: Read Params By Hash |  |
| 0xfc | Config Table: Write Params By Hash | Request Fixed Send Old Config Table Item By Hash Value |
| 0xfd | Product Type Get |  |
| 0xfe | Motor Force Disable Set | Motor Force Disable Flag By App Set — Motor Force Disable Set. GO4 sends payload `00` (motors permitted) ~10 s into connect. GlassFalcon does not send it; not shown to affect the cap. |
| 0xff | Motor Force Disable Get |  |


### cmd_set 0x04 — Gimbal  (45 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | Gimbal Reserved |  |
| 0x01 | Gimbal Control |  |
| 0x02 | Gimbal Get Position |  |
| 0x03 | Gimbal Set Param |  |
| 0x04 | Gimbal Get Param |  |
| 0x05 | Gimbal Params Get | Push Position |
| 0x06 | Gimbal Push AETR |  |
| 0x07 | Gimbal Adjust Roll | Roll Finetune |
| 0x08 | Gimbal Calibration | AutoCalibration |
| 0x09 | Gimbal Reserved2 |  |
| 0x0a | Gimbal Ext Ctrl Degree | Rotate/Angle Set |
| 0x0b | Gimbal Get Ext Ctrl Status | Get State |
| 0x0c | Gimbal Ext Ctrl Accel | Speed Control |
| 0x0d | Gimbal Suspend/Resume | Set On Or Off |
| 0x0e | Gimbal Thirdp Magn |  |
| 0x0f | Gimbal User Params Set |  |
| 0x10 | Gimbal User Params Get |  |
| 0x11 | Gimbal User Params Save |  |
| 0x13 | Gimbal User Params Reset Default | Resume Default Param |
| 0x14 | Gimbal Abs Angle Control |  |
| 0x15 | Gimbal Movement |  |
| 0x1c | Gimbal Type Get |  |
| 0x1e | Gimbal Degree Info Subscription |  |
| 0x20 | Gimbal TBD 20 |  |
| 0x21 | Gimbal TBD 21 |  |
| 0x24 | Gimbal User Params Get |  |
| 0x27 | Gimbal Abnormal Status Get |  |
| 0x2b | Gimbal Tutorial Status Get |  |
| 0x2c | Gimbal Tutorial Step Set |  |
| 0x30 | Gimbal Auto Calibration Status |  |
| 0x31 | Robin Params Set |  |
| 0x32 | Robin Params Get |  |
| 0x33 | Robin Battery Info Push | Gimbal Battery Info |
| 0x34 | Gimbal Handle Params Set |  |
| 0x36 | Gimbal Handle Params Get |  |
| 0x37 | Gimbal Timelapse Params Set |  |
| 0x38 | Gimbal Timelapse Status |  |
| 0x39 | Gimbal Lock |  |
| 0x3a | Gimbal Rotate Camera X Axis |  |
| 0x45 | Gimbal Get Temp |  |
| 0x47 | Gimbal TBD 47 |  |
| 0x4c | Gimbal Reset And Set Mode |  |
| 0x56 | Gimbal NotiFy Camera Id |  |
| 0x57 | Handheld Stick State Get/Push |  |
| 0x58 | Handheld Stick Control Set | Handheld Stick Control Enable |


### cmd_set 0x05 — Center Board  (18 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | Center Open/Close Virtual RC |  |
| 0x01 | Center Virtual RC Data |  |
| 0x02 | Center Push Batt Dynamic Info |  |
| 0x03 | Center Control Uav Status Led |  |
| 0x04 | Center Transform Control |  |
| 0x05 | Center Req Push Bat Normal Data |  |
| 0x06 | Center Battery Common | Center Push Bat Normal Data |
| 0x07 | Center Query Bat Status |  |
| 0x08 | Center Query Bat Hisoty Status |  |
| 0x09 | Center Bat SelfDischarge Days |  |
| 0x0a | Center Bat Storage Info |  |
| 0x21 | Center Req Bat Static Data |  |
| 0x22 | Center Req Bat Dynamic Data |  |
| 0x23 | Center Req Bat Auth Data |  |
| 0x24 | Center Req Bat Auth Result |  |
| 0x31 | Center Req Bat SelfDischarge Time |  |
| 0x32 | Center Set Bat SelfDischarge Time |  |
| 0x33 | Center Req Bat Barcode |  |


### cmd_set 0x06 — Remote Controller  (84 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x01 | RC Channel Params Get | includes Logic Channel Mapping |
| 0x02 | RC Channel Params Set |  |
| 0x03 | RC Calibiration Set |  |
| 0x04 | RC Physical Channel Parameter Get |  |
| 0x05 | RC Parameter Get/Push |  |
| 0x06 | RC Master/Slave Mode Set |  |
| 0x07 | RC Master/Slave Mode Get |  |
| 0x08 | RC Name Set |  |
| 0x09 | RC Name Get |  |
| 0x0a | RC Password Set |  |
| 0x0b | RC Password Get |  |
| 0x0c | RC Connected Master Id Set |  |
| 0x0d | RC Connected Master Id Get |  |
| 0x0e | RC Available Master Id Get |  |
| 0x0f | RC Search Mode Set |  |
| 0x10 | RC Search Mode Get |  |
| 0x11 | RC Master/Slave Switch Set |  |
| 0x12 | RC Master/Slave Switch Conf Get |  |
| 0x13 | RC Request Join By Slave |  |
| 0x14 | RC List Request Join Slave |  |
| 0x15 | RC Delete Slave |  |
| 0x16 | RC Delete Master |  |
| 0x17 | RC Slave Control Right Set |  |
| 0x18 | RC Slave Control Right Get |  |
| 0x19 | RC Control Mode Set |  |
| 0x1a | RC Control Mode Get |  |
| 0x1b | RC GPS Info Get/Push |  |
| 0x1c | RC RTC Info Get/Push |  |
| 0x1d | RC Temperature Info Get/Push |  |
| 0x1e | RC Battery Info Get/Push |  |
| 0x1f | RC Master/Slave Conn Info Get/Push |  |
| 0x20 | RC Power Mode CE/FCC Set |  |
| 0x21 | RC Power Mode CE/FCC Get |  |
| 0x22 | RC Gimbal Ctr Permission Request |  |
| 0x23 | RC Gimbal Ctr Permission Ack |  |
| 0x24 | RC Simulate Flight Mode Set |  |
| 0x25 | RC Simulate Flight Mode Get |  |
| 0x26 | RC AETR Value Push | Get Sim Push Params |
| 0x27 | RC Detection Info Get |  |
| 0x28 | RC Gimbal Control Access Right Get |  |
| 0x29 | RC Slave Control Mode Set |  |
| 0x2a | RC Slave Control Mode Get |  |
| 0x2b | RC Gimbal Control Speed Set |  |
| 0x2c | RC Gimbal Control Speed Get |  |
| 0x2d | RC Self Defined Key Func Set | Custom Fuction Set |
| 0x2e | RC Self Defined Key Func Get |  |
| 0x2f | RC Pairing | Frequency Set |
| 0x30 | RC Test GPS |  |
| 0x31 | RC RTC Clock Set |  |
| 0x32 | RC RTC Clock Get | See m0101 sys partiton for payload info |
| 0x33 | RC Gimbal Control Sensitivity Set | Wheel Gain Set |
| 0x34 | RC Gimbal Control Sensitivity Get |  |
| 0x35 | RC Gimbal Control Mode Set |  |
| 0x36 | RC Gimbal Control Mode Get |  |
| 0x37 | RC Enter App Mode Request |  |
| 0x38 | RC Calibration Value Get |  |
| 0x39 | RC Master Slave Connect Status Push |  |
| 0x3a | RC 2014 Usb Mode Set |  |
| 0x3b | RC Id Set |  |
| 0x3c | RC Coach Mode |  |
| 0x3f | RC Mater/Slave Id |  |
| 0x42 | RC Follow Focus Get/Push |  |
| 0x47 | RC App Special Control |  |
| 0x48 | RC Freq Mode Info Get | RC Param Get |
| 0x4c | RC Pro Custom Buttons Status Get/Push |  |
| 0x50 | RC Push Rmc Key Info | MCU407 Set |
| 0x51 | RC Push To Glass | RC Custom Buttons Status Get/Push |
| 0x52 | RC Push LCD To MCU |  |
| 0x53 | RC Unit Language Get |  |
| 0x54 | RC Unit Language Set |  |
| 0x55 | RC Test Mode Set |  |
| 0x56 | RC Quiry Role | RC Role Get |
| 0x57 | RC Quiry Ms Link Status | FD Push Connect Status Get |
| 0x58 | RC Work Function Set | New Control Function Set |
| 0x59 | RC Work Function Get |  |
| 0x98 | Follow Focus2 Get/Push |  |
| 0x99 | Follow Focus Info Set |  |
| 0xf0 | RC RF Cert Config Set | Set Transciever Pwr Mode |
| 0xf5 | RC Test Stick Value |  |
| 0xf6 | RC Factory Get Board Id |  |
| 0xf7 | RC Push Buzzer To MCU |  |
| 0xf8 | RC Stick Verification Data Get | FD Rc Calibration Statue Get |
| 0xf9 | RC Post Calibiration Set |  |
| 0xfa | RC Stick Middle Value Get |  |


### cmd_set 0x07 — WiFi  (64 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | WiFi Reserved |  |
| 0x01 | WiFi Ap Scan Results Push |  |
| 0x02 | WiFi Ap Channel SNR Get |  |
| 0x03 | WiFi Ap Channel Set |  |
| 0x04 | WiFi Ap Channel Get |  |
| 0x05 | WiFi Ap Tx Pwr Set |  |
| 0x06 | WiFi Ap Tx Pwr Get |  |
| 0x07 | WiFi Ap SSID Get |  |
| 0x08 | WiFi Ap SSID Set |  |
| 0x09 | WiFi Ap RSSI Push |  |
| 0x0a | WiFi Ap Ant RSSI Get |  |
| 0x0b | WiFi Ap Mac Addr Set |  |
| 0x0c | WiFi Ap Mac Addr Get |  |
| 0x0d | WiFi Ap Passphrase Set |  |
| 0x0e | WiFi Ap Passphrase Get | Get PSK/Password |
| 0x0f | WiFi Ap Factory Reset |  |
| 0x10 | WiFi Ap Band Set | Wifi Frequency Set |
| 0x11 | WiFi Ap Sta MAC Push | First App Mac Get/Push |
| 0x12 | WiFi Ap Phy Param Get | Electric Signal Get/Push |
| 0x13 | WiFi Ap Power Mode Set |  |
| 0x14 | WiFi Ap Calibrate |  |
| 0x15 | WiFi Ap Wifi Restart |  |
| 0x16 | WiFi Ap Selection Mode Set |  |
| 0x17 | WiFi Ap Selection Mode Get |  |
| 0x18 | WiFi Ap 18 |  |
| 0x19 | WiFi Ap 19 |  |
| 0x1a | WiFi Ap 1A |  |
| 0x1b | WiFi Ap 1B |  |
| 0x1c | WiFi Ap 1C |  |
| 0x1d | WiFi Ap 1D |  |
| 0x1e | WiFi SSID Get | older variant? |
| 0x1f | WiFi Ap 1F |  |
| 0x20 | WiFi Ap Wifi Frequency Get |  |
| 0x21 | WiFi Ap Set Bw |  |
| 0x22 | WiFi Ap 22 |  |
| 0x23 | WiFi Ap 23 |  |
| 0x24 | WiFi Ap 24 |  |
| 0x25 | WiFi Ap 25 |  |
| 0x26 | WiFi Ap Realtime Acs | Noise Check Adapt Set |
| 0x27 | WiFi Ap Manual Switch SDR |  |
| 0x28 | WiFi Ap Channel List Get/Push |  |
| 0x29 | WiFi Ap Channel Noise/SNR Req |  |
| 0x2a | WiFi Ap Channel Noise/SNR Push | Wifi Sweep Frequency Get |
| 0x2b | WiFi Ap Set Hw Mode | Wifi Mode Channel Set |
| 0x2c | Wifi Ap Code Rate Set |  |
| 0x2d | Wifi Ap Cur Code Rate Get |  |
| 0x2e | WiFi Ap Set Usr Pref | Wifi Freq 5G Mode Set |
| 0x2f | WiFi Ap Get Usr Pref | Wifi Freq Mode Get |
| 0x30 | WiFi Ap Set Country Code | Set Region |
| 0x31 | WiFi Ap Reset Freq |  |
| 0x32 | WiFi Ap Del Country Code |  |
| 0x33 | WiFi Ap Verify Cc | Is Country Code Supported |
| 0x39 | WiFi Get Work Mode |  |
| 0x3a | WiFi Set Work Mode |  |
| 0x3b | WiFi Config By Qrcode |  |
| 0x80 | WiFi Push Mac Stat | Log Get/Push |
| 0x82 | WiFi Master/Slave Status Get/Push |  |
| 0x83 | WiFi Master/Slave AuthCode Set |  |
| 0x84 | WiFi Scan Master List |  |
| 0x85 | WiFi Connect Master With Id AuthCode |  |
| 0x89 | WiFi AuthCode Get |  |
| 0x8b | WiFi MS Error Info Get/Push |  |
| 0x91 | WiFi Rc Info Set |  |
| 0x92 | WiFi Update Sw State |  |


### cmd_set 0x08 — DM36x (link processor)  (15 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x00 | DM36x Reserved |  |
| 0x01 | DM36x Gnd Ctrl Info Send |  |
| 0x02 | DM36x Gnd Ctrl Info Recv |  |
| 0x03 | DM36x UAV Ctrl Info Send |  |
| 0x04 | DM36x UAV Ctrl Info Recv |  |
| 0x05 | DM36x Gnd Stat Info Send |  |
| 0x06 | DM36x UAV Stat Info Send |  |
| 0x07 | DM36x Gnd Stat Info Recv |  |
| 0x0e | DM36x App Connect Stat Get |  |
| 0x0f | DM36x Recycle Vision Frame Info |  |
| 0x20 | DM36x Bitrate Set | Wifi Code Rate Set |
| 0x21 | DM36x Bitrate Get |  |
| 0x30 | DM36x Foresight Showed Set | Status Push |
| 0x31 | DM36x Foresight Showed Get | Send Vmem Fd To Vision |
| 0x60 | Active Track Camera Set |  |


### cmd_set 0x09 — HD Link / OcuSync  (67 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x01 | HDLnk OSD General Data Get/Push |  |
| 0x02 | HDLnk OSD Home Point Get/Push |  |
| 0x03 | HDLnk Baseband State Get/Push |  |
| 0x04 | HDLnk FPGA Write |  |
| 0x05 | HDLnk FPGA Read |  |
| 0x06 | HDLnk TCX Hardware Reg Write | Set register in AD9363 |
| 0x07 | HDLnk TCX Hardware Reg Read | Get register from AD9363 |
| 0x08 | HDLnk VT Signal Quality Push | Video transmission signal strength |
| 0x09 | HDLnk Sweep Frequency Set | Req Freq Energy |
| 0x0a | HDLnk Sweep Frequency Get/Push |  |
| 0x0b | HDLnk Device Status Get/Push |  |
| 0x0c | HDLnk VT Config Info Get/Push | Video transmission config info |
| 0x0d | HDLnk VT Config Info Set |  |
| 0x0e | HDLnk USB Iface Change | Usb Transform Set; See m0101 sys partiton for payload info |
| 0x0f | HDLnk Reset Cy68013 | See m0101 sys partiton for payload info |
| 0x10 | HDLnk Upgrade Tip Set |  |
| 0x11 | HDLnk Wl Env Quality Get/Push |  |
| 0x12 | HDLnk Factory Test Set | Set Transciever Config to test |
| 0x13 | HDLnk Factory Test Get |  |
| 0x14 | HDLnk Max Video Bandwidth Set | Set Max Mcs |
| 0x15 | HDLnk Max Video Bandwidth Get/Push |  |
| 0x16 | HDLnk Debug Info Push |  |
| 0x20 | HDLnk SDR Downward Sweep Frequency | SDR Dl Freq Energy Get/Push |
| 0x21 | HDLnk SDR Vt Config Info Get |  |
| 0x22 | HDLnk SDR Dl Auto Vt Info Get/Push |  |
| 0x23 | HDLnk SDR Rt Status Set |  |
| 0x24 | HDLnk SDR UAV Rt Status Get/Push |  |
| 0x25 | HDLnk SDR Gnd Rt Status Get/Push | SDR Status Ground Info |
| 0x26 | HDLnk SDR Debug/Assitant Read |  |
| 0x27 | HDLnk SDR Debug/Assitant Write |  |
| 0x28 | HDLnk SDR Start Log Set |  |
| 0x29 | HDLnk SDR Upward Sweep Frequency | SDR Ul Freq Energy Push |
| 0x2a | HDLnk SDR Upward Select Channel | SDR Ul Auto Vt Info Push |
| 0x2b | HDLnk SDR Revert Role |  |
| 0x2c | HDLnk SDR Amt Process |  |
| 0x2d | HDLnk SDR LBT Status Get |  |
| 0x2e | HDLnk SDR LBT Status Set |  |
| 0x2f | HDLnk SDR Link Test |  |
| 0x30 | HDLnk SDR Wireless Env State | Wireless Status Get/Push |
| 0x31 | HDLnk SDR Scan Freq Cfg |  |
| 0x32 | HDLnk SDR Factory Mode Set |  |
| 0x33 | HDLnk Tracking State Ind |  |
| 0x34 | HDLnk SDR Liveview Mode Set | SDR Image Transmission Mode Set |
| 0x35 | HDLnk SDR Liveview Mode Get |  |
| 0x36 | HDLnk SDR Liveview Rate Ind | SDR Push Custom Code Rate |
| 0x37 | HDLnk Abnormal Event Ind | aka HDTV Exception |
| 0x38 | HDLnk SDR Set Rate |  |
| 0x39 | HDLnk Liveview Config Set | SDR Config Info Set |
| 0x3a | HDLnk Dl Freq Energy Push | SDR Nf Params |
| 0x3b | HDLnk SDR Tip Interference | SDR Bar Disturb Get/Push |
| 0x3c | HDLnk SDR Upgrade Rf Power | SDR Force Boost Set |
| 0x3e | HDLnk Slave RT Status Push |  |
| 0x3f | HDLnk RC Conn Status Push |  |
| 0x41 | HDLnk Racing Set Modem Info |  |
| 0x42 | HDLnk Racing Get Modem Info |  |
| 0x50 | HDLnk LED Set |  |
| 0x51 | HDLnk Power Set | Robomaster Cnfg Set |
| 0x52 | HDLnk Power Status Get/Push |  |
| 0x53 | HDLnk SDR Cp Status Get |  |
| 0x54 | Osmo Calibration Push |  |
| 0x57 | HDLnk Mic Gain Set |  |
| 0x58 | HDLnk Mic Gain Get |  |
| 0x59 | HDLnk Mic Info Get/Push |  |
| 0x62 | HDLnk Mic Enable Get |  |
| 0x63 | HDLnk Mic Enable Set |  |
| 0x71 | HDLnk Main Camera Bandwidth Percent Set |  |
| 0x72 | HDLnk Main Camera Bandwidth Percent Get |  |


### cmd_set 0x0a — Mono/Stereo Vision (MBINO)  (48 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x01 | Eye Bino Info | log |
| 0x02 | Eye Mono Info |  |
| 0x03 | Eye Ultrasonic Info |  |
| 0x04 | Eye Oa Info |  |
| 0x05 | Eye Relitive Pos |  |
| 0x06 | Eye Avoidance Param | Avoidance Warn Parameters |
| 0x07 | Eye Obstacle Info | Front Obstacle Avoidance |
| 0x08 | Eye TapGo Obst Avo Info | Obstacle Avoidance during Go To Point / Point To Fly |
| 0x0a | Eye Push Vision Debug Info |  |
| 0x0b | Eye Push Control Debug Info |  |
| 0x0d | Eye Track Log |  |
| 0x0e | Eye Point Log |  |
| 0x0f | Eye Push SDK Control Cmd |  |
| 0x10 | Eye Enable Tracking Taptogo |  |
| 0x11 | Eye Push Target Speed Pos Info |  |
| 0x12 | Eye Push Target Pos Info |  |
| 0x13 | Eye Push Trajectory |  |
| 0x14 | Eye Push Expected Speed Angle |  |
| 0x15 | Eye Receive Frame Info |  |
| 0x19 | Eye Flat Check |  |
| 0x1d | Eye Fixed Wing Ctrl |  |
| 0x1e | Eye Fixed Wing Status Push |  |
| 0x20 | Eye Marquee Push |  |
| 0x21 | Eye Tracking Cnf Cancel |  |
| 0x22 | Eye Move Marquee Push |  |
| 0x23 | Eye Tracking Status Push |  |
| 0x24 | Eye Position Push |  |
| 0x25 | Eye Fly Ctl Push |  |
| 0x26 | Eye TapGo Status Push | Status of Go To Point / Point To Fly |
| 0x27 | Eye Common Ctl Cmd |  |
| 0x28 | Eye Get Para Cmd |  |
| 0x29 | Eye Set Para Cmd |  |
| 0x2a | Eye Com Status Update | The update basically means Exception |
| 0x2c | Eye Ta Lock Update |  |
| 0x2d | Eye Smart Landing |  |
| 0x2e | Eye Function List Push |  |
| 0x2f | Eye Sensor Status Push | Informs of Sensor Exceptions |
| 0x30 | Eye Self Calibration |  |
| 0x32 | Eye Easy Self Calib State |  |
| 0x37 | Eye QRCode Mode |  |
| 0x39 | Eye Vision Tip |  |
| 0x3a | Eye Precise Landing Energy |  |
| 0x46 | Eye RC Packet |  |
| 0x47 | Eye Set Buffer Config |  |
| 0x48 | Eye Get Buffer Config |  |
| 0xa3 | Eye Enable SDK Func |  |
| 0xa4 | Eye Detection Msg Push |  |
| 0xa5 | Eye Get SDK Func |  |


### cmd_set 0x0b — Simulator  (21 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x01 | Simu Connect Heart Packet |  |
| 0x02 | Simu IMU Status Push | Main Controller Params Request |
| 0x03 | Simu SDR Status Push | Main Controller Return Params Get/Push |
| 0x04 | Simu Get Headbelt SN | Simulate Flight Commend |
| 0x06 | Simu Flight Status Params |  |
| 0x07 | Simu GetWind Set |  |
| 0x08 | Simu GetArea Set |  |
| 0x09 | Simu GetAirParams Set |  |
| 0x0a | Simu Force Moment |  |
| 0x0b | Simu GetTemperature Set |  |
| 0x0c | Simu GetGravity Set |  |
| 0x0d | Simu Crash ShutDown |  |
| 0x0e | Simu Ctrl Motor |  |
| 0x0f | Simu Momentum |  |
| 0x10 | Simu GetArmLength Set |  |
| 0x11 | Simu GetMassInertia Set |  |
| 0x12 | Simu GetMotorSetting Set |  |
| 0x13 | Simu GetBatterySetting Set |  |
| 0x14 | Simu Frequency Get |  |
| 0x1a | Simu Set Sim Vision Mode |  |
| 0x1b | Simu Get Sim Vision Mode |  |


### cmd_set 0x0c — ESC  (0 commands)

_(no command table found in dissectors)_


### cmd_set 0x0d — Battery / Smart Battery  (16 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x01 | Battery Static Data Get |  |
| 0x02 | Battery Dynamic Data Get/Push |  |
| 0x03 | Battery Cell Voltage Get/Push | Get Single Core Volt |
| 0x04 | Battery BarCode Data Get |  |
| 0x05 | Battery History Get |  |
| 0x06 | Battery Push Common Info |  |
| 0x11 | Battery SetSelfDischargeDays Get |  |
| 0x12 | Battery ShutDown |  |
| 0x13 | Battery Force ShutDown |  |
| 0x14 | Battery StartUp |  |
| 0x15 | Battery Pair Get |  |
| 0x16 | Battery Pair Set |  |
| 0x22 | Battery Data Record Control |  |
| 0x23 | Battery Authentication |  |
| 0x31 | Battery Re-Arrangement Get/Push |  |
| 0x32 | Battery Mult Battery Info Get |  |


### cmd_set 0x0e — Data Log / Recorder  (2 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x22 | DLog Battery Data |  |
| 0x23 | DLog Battery Message |  |


### cmd_set 0x0f — RTK  (1 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x09 | Rtk Status |  |


### cmd_set 0x10 — Auto / Intelligent Flight  (0 commands)

_(no command table found in dissectors)_


### cmd_set 0x11 — ADS-B  (11 commands)

| cmd_id | name | dissector note / **our findings** |
|--------|------|------------------------------------|
| 0x02 | Push Data Get |  |
| 0x08 | Push Warning Get |  |
| 0x09 | Push Original Get |  |
| 0x10 | Send Whitelist |  |
| 0x11 | Request License |  |
| 0x12 | License Enabled Set |  |
| 0x13 | License Id Get |  |
| 0x14 | Push Unlock Info Get |  |
| 0x15 | User Id Set |  |
| 0x16 | Key Version Get |  |
| 0x17 | Push Avoidance Action Get  |  |


<!-- END DUML COMMAND TOME (877 commands across 18 cmd_sets) -->

---

## Permissions

| Permission | Why | Optional? |
|---|---|:--:|
| USB accessory (runtime grant, no manifest permission) | the RC240 AOA link | required |
| `ACCESS_FINE_LOCATION` | phone GPS for the map "you are here", the dynamic-home stream, and nearby-airspace lookups | yes; flight control works without it |
| `INTERNET` | weather (Open-Meteo, keyless), FAA airspace tiles, optional cloud AI, optional encrypted stream | yes; core flight, camera, and telemetry are offline |

No analytics, no crash reporting, no phone-home. Every outbound connection maps to a feature you can leave off.

---

## Known Limitations

- The auto-land button sends a real DUML command (`0x03/0x2a`), but the payload value that triggers landing on wm240 is unconfirmed (auto-takeoff's value `0x01` is confirmed). Manual stick landing works today.
- The lateral obstacle channels (C/D) only populate in low-speed ActiveTrack, so their left/right assignment is not independently confirmed. Front/rear distance is confirmed. The wm240's front/rear sensors have no left/right sub-resolution within an arc; the second beam per direction is unused hardware.
- Waypoint missions are cataloged but not yet flight-tested.
- OpenAIP (the global airspace baseline) is not yet integrated. Only FAA US airspace layers are live.

Contributions on any of these are welcome.

---

## Repo Structure

```
GlassFalcon/
├── android/
│   ├── sdk/                      # :sdk library module -> sdk-release.aar
│   │   └── src/main/kotlin/dev/glassfalcon/core/
│   │       ├── Duml.kt            # DUML framing + USB accessory/CDC-ACM transport
│   │       ├── DumlCommands.kt    # Camera/FC/Gimbal command builders
│   │       ├── Telemetry.kt       # FC broadcast decoder + DroneState
│   │       ├── VideoExtractor.kt  # H.264 NAL extraction from the multiplexed downlink
│   │       ├── MissionPlanner.kt  # Grid survey, orbit, battery model
│   │       └── MissionEngine.kt   # Coroutine mission state machine
│   └── app/src/main/kotlin/dev/glassfalcon/
│       ├── core/                  # FlightViewModel, VideoDecoder, Weather, RcButtons
│       │   ├── NanoCopilot / GeminiCoPilot / VoiceAnnouncer   # on-device + cloud AI, TTS
│       │   └── plugin/            # plugin registry + stream/ (encrypted re-streamer)
│       └── ui/                    # screens/, GlassFalconRoot.kt, Theme.kt
├── sdk/
│   ├── python/                    # `glassfalcon` pip package (pure stdlib)
│   └── docs/                      # protocol.md, getting-started.md
├── plugins/                       # plugin server/companion halves (encrypted-stream/) + plugins/local/ (gitignored)
├── tools/                         # install.sh / install.ps1, find-drone.sh, phone.sh
├── assets/                        # icons, banner
└── LICENSE                        # GPL-3.0-or-later
```

---

## Contributing

Issues and PRs welcome. Two kinds of contribution move this forward most:

- **A new airframe.** If you own a DJI drone other than the wm240, capturing its DUML dialect (module IDs, telemetry offsets, opcode differences) is the work that extends support to it. Open an issue with the model and what you can capture.
- **Filling the wm240 gaps.** Progress on the auto-land opcode, waypoint missions, or the lateral obstacle channels is welcome; see [Known Limitations](#known-limitations) and the [Tested / Not-Tested](#tested--not-tested) section for exactly what is open.

---

## Legal

This project is a clean-room implementation of the DUML protocol for interoperability with hardware you own, built from empirical protocol observation (captured bytes, documented behavior), not from DJI's copyrighted source or decompiled binaries. It does not distribute DJI firmware, proprietary keys, or DJI SDK components.

GlassFalcon is free, open-source software with no company, server, account system, or monetization behind it. Nothing in it, and no one, can verify what certifications, waivers, or local authorizations you hold to operate an aircraft in any given airspace, at any given height, in any given country.

Because of that, GlassFalcon does not try to enforce compliance. Any limit it shows you (your aircraft's own configured height/radius limits, nearby FAA airspace ceilings, anything else) is informational, pulled from the aircraft's own reported settings or public data. None of it is a guarantee, and none of it is a lock GlassFalcon puts between you and your own aircraft.

**You are solely responsible for complying with all laws and regulations that apply wherever you operate your aircraft** (FAA Part 107, EU UAS regulations, and so on). You, not GlassFalcon and not its authors, are accountable if you do not. The app shows this same disclaimer once on first launch.

### Fonts

GlassFalcon bundles [Space Grotesk](https://github.com/floriankarsten/space-grotesk) (UI labels) and [IBM Plex Mono](https://github.com/IBM/plex) (numeric HUD readouts), both under the [SIL Open Font License 1.1](https://openfontlicense.org/).

GlassFalcon is licensed GPL-3.0-or-later; see [`LICENSE`](LICENSE).
