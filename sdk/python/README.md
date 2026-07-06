# Glass Falcon SDK (Python)

A FOSS toolkit for building software and firmware for DJI drones, independent of
DJI's proprietary Mobile SDK. It speaks the drone's native **DUML** protocol
directly, so any tool built on it talks to the aircraft the same way the official
app does, no activation gate, no cloud, no closed-source blobs.

Targets the **wm240** (Mavic 2 Pro / Zoom); the protocol layer is generic and
works with other DUML-based DJI products.

## Install

```bash
pip install glassfalcon            # core, pure stdlib, zero deps
pip install "glassfalcon[serial]"  # + pyserial for /dev/ttyACM (CDC-ACM)
pip install "glassfalcon[usb]"     # + pyusb for raw USB transport
```

From this repo:

```bash
pip install -e sdk/python
```

The container already puts it on `PYTHONPATH`; `./djilab sdk` drops you into a
REPL with it loaded.

## Quick start

```python
from glassfalcon import DUMLConnection, TelemetryDecoder, duml_cmds as C

conn = DUMLConnection.open_serial("/dev/ttyACM0")   # needs the `serial` extra
dec  = TelemetryDecoder()
conn.add_listener(dec.feed)            # decode every frame into dec.state
conn.send_cmd(C.get_device_state())    # ask the FC to start pushing OSD data

import time; time.sleep(1)
print(dec.state.battery_pct, dec.state.gps_sats, dec.state.lat, dec.state.lon)
```

Sending camera / gimbal commands (route to a different destination module):

```python
conn.send_cmd(C.camera_start_record(), dst=0x01)   # CAM
conn.send_cmd(C.gimbal_set_mode(1),    dst=0x04)    # GIMB
```

## Modules

| Module | What it does |
|---|---|
| `duml` | DUML packet framing (CRC-8/16, build/parse) + `DUMLConnection` (serial/TCP, threaded RX) |
| `duml_cmds` | Ready-made command builders → `(cmd_set, cmd_id, payload)` tuples |
| `telemetry` | `TelemetryDecoder` turns OSD frames into a `DroneState`; `TelemetryHistory` logs them |
| `params` | Flight-controller parameter get/set + CSV catalogue (`load_csv`, `ParamManager`) |
| `mission_planner` | Waypoint missions, `grid_survey`, `orbit`, `haversine`, `bearing` |
| `mission_engine` | Mission execution state machine (`MissionEngine`, `MissionStatus`) |
| `adb_manager` | USB/ADB transport discovery + bridge (`find_adb`, `ADBManager`) |
| `video_decrypt` | DJI encrypted video-stream tooling |

## Protocol reference

See [`../docs/protocol.md`](../docs/protocol.md) for the DUML wire format,
CRC seeds, the source/destination module map, and the OSD telemetry layout.

## License

GPL-3.0-or-later. See [`../../LICENSE`](../../LICENSE).
