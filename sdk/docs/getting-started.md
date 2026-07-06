# Getting Started

## Install

```bash
pip install -e sdk/python              # from this repo
pip install "glassfalcon[serial]"      # add pyserial for /dev/ttyACM
```

Or just `./djilab sdk` for a REPL with `glassfalcon` preloaded inside the
container, with the drone's USB/serial nodes passed through.

## Connect

The Mavic 2 Pro enumerates as a USB CDC-ACM serial device (`/dev/ttyACM0`) when
plugged into the remote or directly. Three transports:

```python
from glassfalcon import DUMLConnection

conn = DUMLConnection.open_serial("/dev/ttyACM0")   # USB / RC, needs [serial]
conn = DUMLConnection.open_tcp("192.168.42.2")      # RNDIS / network bridge
conn = DUMLConnection()                              # construct, connect later
```

## Read telemetry

Telemetry is **push-based**: register a listener and the threaded receiver feeds
every decoded frame to it. `TelemetryDecoder` accumulates frames into a single
mutable `DroneState`.

```python
from glassfalcon import DUMLConnection, TelemetryDecoder, duml_cmds as C

conn = DUMLConnection.open_serial("/dev/ttyACM0")
dec  = TelemetryDecoder()
conn.add_listener(dec.feed)
conn.send_cmd(C.get_device_state())     # nudge the FC to start the OSD push

import time
for _ in range(10):
    time.sleep(1)
    s = dec.state
    print(f"{s.lat:.6f},{s.lon:.6f}  {s.alt_rel:.1f} m  "
          f"{s.gps_sats} sats  {s.battery_pct}%")
```

## Send commands

```python
conn.send_cmd(C.camera_start_record(), dst=0x01)     # CAM module
conn.send_cmd(C.gimbal_speed(pitch_rate=-90), dst=0x04)  # tilt down, wm240 aims
                                                        # via the speed cmd
                                                        # (0x04/0x0c) and IGNORES
                                                        # gimbal_abs_angle
```

> **Do not send `C.flyc_set_home_point()` on the wm240.** It re-locks the 30 m
> altitude/distance cap (confirmed live 2026-07-05). The aircraft records its own
> home point automatically, so there is never a reason to send it in normal
> operation.

Read back a response by parsing it in your listener:

```python
def on_frame(f):
    if (f["cmd_set"], f["cmd_id"]) == (0x00, 0x01):     # version inquiry ACK
        print(C.parse_version_inquiry(f["payload"]))
conn.add_listener(on_frame)
conn.send_cmd(C.version_inquiry())
```

## Plan a mission

```python
from glassfalcon import grid_survey, orbit, MissionEngine

# grid_survey takes the survey-area corners; spacing is derived from the
# camera FOV at alt_m and the requested photo overlap.
plan = grid_survey(
    corners=[(37.000, -122.000), (37.000, -121.998),
             (37.0015, -121.998), (37.0015, -122.000)],
    alt_m=50, front_overlap_pct=75, side_overlap_pct=70)
print(plan.name, ", ", len(plan.waypoints), "waypoints")

orbit_plan = orbit(center_lat=37.0, center_lon=-122.0, radius_m=40, alt_m=30)

eng = MissionEngine(conn)
eng.start(plan)    # streams waypoints to the FC on a worker thread
```

## Next

- [`protocol.md`](protocol.md), the DUML wire format and module map
- [`../firmware/README.md`](../firmware/README.md), firmware fetch/unpack/patch
- [`../python/README.md`](../python/README.md), full module table
