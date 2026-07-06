# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
Decode live DUML telemetry frames from the wm240 flight controller.

The FC broadcasts "OSD General Data", cmd_set=0x03, cmd_id=0x43, at ~1 Hz.
Layout below is the authoritative one from o-gs/dji-firmware-tools
(comm_dissector/wireshark/dji-dumlv1-flyc.lua), all little-endian. Note that
position is double-precision **radians** and velocity/attitude are int16 tenths,
and the battery percentage and GPS satellite count live in this same packet:

  [0:8]   double  longitude (radians)
  [8:16]  double  latitude  (radians)
  [16:18] int16   relative_height * 0.1 (m, to ground)
  [18:20] int16   vgx  * 0.1 (m/s, to ground)
  [20:22] int16   vgy  * 0.1
  [22:24] int16   vgz  * 0.1
  [24:26] int16   pitch * 0.1 (deg)
  [26:28] int16   roll  * 0.1
  [28:30] int16   yaw   * 0.1
  [30]    uint8   ctrl_info (flyc_state=0x7F, no_rc=0x80)
  [31]    uint8   latest_cmd
  [32:36] uint32  controller_state flags (0x04=in_air, 0x8000=gps_used, …)
  [36]    uint8   gps satellite count
  [40]    uint8   battery_remaining (%)
  [42:44] uint16  motor_startup_time (fly time, seconds)
  ...     packet is 50 bytes (P3X) or 55 (WM6xx/WM240 with extension)
"""

import math
import struct
import time
from dataclasses import dataclass, field
from typing import Optional, List

RAD2DEG = 180.0 / math.pi


@dataclass
class DroneState:
    # Flight controller
    uptime_ms:       int   = 0
    vx:              float = 0.0   # m/s N
    vy:              float = 0.0   # m/s E
    vz:              float = 0.0   # m/s D
    lat:             float = 0.0   # degrees
    lon:             float = 0.0   # degrees
    alt_rel:         float = 0.0   # m, relative to home
    flags:           int   = 0
    home_dist_m:     float = 0.0
    # Attitude (from separate packet)
    roll:            float = 0.0   # degrees
    pitch:           float = 0.0
    yaw:             float = 0.0
    # Battery
    battery_pct:     int   = 0
    battery_mv:      int   = 0
    batt_low:        bool  = False  # 0x51 low_warning flag
    batt_rth:        bool  = False  # 0x51 low_warning_go_home flag (return-to-home)
    batt_req_land:   bool  = False  # OSD controller_state bit 0x400
    # RC signal
    rc_signal:       int   = 0
    # GPS / flight time (from OSD General Data)
    gps_sats:        int   = 0
    fly_time_s:      int   = 0
    # Computed
    speed:           float = 0.0
    last_update:     float = field(default_factory=time.time)
    connected:       bool  = False


# History ring buffer for WebGL charts (last N seconds)
MAX_HISTORY = 300

class TelemetryHistory:
    def __init__(self):
        self.ts:      List[float] = []
        self.alt:     List[float] = []
        self.speed:   List[float] = []
        self.vx:      List[float] = []
        self.vy:      List[float] = []
        self.vz:      List[float] = []
        self.roll:    List[float] = []
        self.pitch:   List[float] = []
        self.yaw:     List[float] = []
        self.battery: List[int]   = []
        self.lat:     List[float] = []
        self.lon:     List[float] = []

    def push(self, state: DroneState):
        now = time.time()
        for lst in (self.ts, self.alt, self.speed, self.vx, self.vy, self.vz,
                    self.roll, self.pitch, self.yaw, self.battery, self.lat, self.lon):
            if len(lst) >= MAX_HISTORY:
                lst.pop(0)
        self.ts.append(now)
        self.alt.append(state.alt_rel)
        self.speed.append(state.speed)
        self.vx.append(state.vx)
        self.vy.append(state.vy)
        self.vz.append(state.vz)
        self.roll.append(state.roll)
        self.pitch.append(state.pitch)
        self.yaw.append(state.yaw)
        self.battery.append(state.battery_pct)
        self.lat.append(state.lat)
        self.lon.append(state.lon)

    def as_json(self) -> dict:
        return {
            "ts": self.ts, "alt": self.alt, "speed": self.speed,
            "vx": self.vx, "vy": self.vy, "vz": self.vz,
            "roll": self.roll, "pitch": self.pitch, "yaw": self.yaw,
            "battery": self.battery, "lat": self.lat, "lon": self.lon,
        }


class TelemetryDecoder:
    FC_CMD_SET      = 0x03
    FC_OSD_GENERAL  = 0x43   # OSD General Data: position, velocity, attitude
    FC_BATT_STATUS  = 0x51   # FlyC Battery Status: voltage, percent

    def __init__(self):
        self.state   = DroneState()
        self.history = TelemetryHistory()
        self._cbs      = []
        self._raw_cbs  = []
        self._batt_cbs = []  # called with battery % (int) from 0x51 push

    def on_update(self, fn):
        self._cbs.append(fn)

    def on_raw_frame(self, fn):
        self._raw_cbs.append(fn)

    def on_battery(self, fn):
        """Called with int battery % when a 0x03/0x51 battery push arrives."""
        self._batt_cbs.append(fn)

    def feed(self, frame: dict):
        cs = frame["cmd_set"]
        ci = frame["cmd_id"]
        p  = frame["payload"]
        changed = False

        for fn in self._raw_cbs:
            fn(frame)

        # OSD General Data, ~1 Hz position/velocity/attitude push from FC.
        if cs == self.FC_CMD_SET and ci == self.FC_OSD_GENERAL and len(p) >= 50:
            lon = struct.unpack_from("<d", p, 0)[0]
            lat = struct.unpack_from("<d", p, 8)[0]
            rel_h, vgx, vgy, vgz, pitch, roll, yaw = struct.unpack_from("<7h", p, 16)
            ctrl_state = struct.unpack_from("<I", p, 32)[0]
            self.state.lon         = lon * RAD2DEG
            self.state.lat         = lat * RAD2DEG
            self.state.alt_rel     = rel_h * 0.1
            self.state.vx          = vgx * 0.1
            self.state.vy          = vgy * 0.1
            self.state.vz          = vgz * 0.1
            self.state.pitch       = pitch * 0.1
            self.state.roll        = roll * 0.1
            self.state.yaw         = yaw * 0.1
            self.state.flags        = ctrl_state
            self.state.batt_req_land = bool(ctrl_state & 0x400)
            self.state.gps_sats     = p[36]
            # p[40] is battery_remain per Lua dissector; 0 until battery module syncs.
            # We keep the OSD value and let the 0x51 push override it.
            osd_batt = p[40]
            if osd_batt > 0:
                self.state.battery_pct = osd_batt
            self.state.fly_time_s  = struct.unpack_from("<H", p, 42)[0]
            self.state.uptime_ms   = self.state.fly_time_s * 1000
            self.state.speed = (self.state.vx ** 2 + self.state.vy ** 2
                                + self.state.vz ** 2) ** 0.5
            self.state.last_update = time.time()
            self.state.connected   = True
            changed = True

        # Battery Status push (cmd_id=0x51), has accurate battery % and voltage.
        # Layout per dji-dumlv1-flyc.lua flyc_flyc_battery_status_dissector:
        #   [0:2]   useful_time (uint16), [2:4] go_home_time, [4:6] land_time
        #   [6:8]   go_home_battery, [8:10] land_battery
        #   [10:14] safe_fly_radius (float), [14:18] volume_consume (float)
        #   [18:22] status (uint32), [22] go_home_status, [23] go_home_countdown
        #   [24:26] voltage (uint16), [26] battery_percent (uint8)
        #   [27] masked1b, [28] low_warning, [29] low_warning_go_home
        elif cs == self.FC_CMD_SET and ci == self.FC_BATT_STATUS and len(p) >= 27:
            pct = p[26]
            if 0 < pct <= 100:
                self.state.battery_pct = pct
                self.state.battery_mv  = struct.unpack_from("<H", p, 24)[0]
                if len(p) >= 30:
                    self.state.batt_low = bool(p[28])
                    self.state.batt_rth = bool(p[29])
                for fn in self._batt_cbs:
                    try:
                        fn(pct)
                    except Exception:
                        pass
                changed = True

        if changed:
            self.history.push(self.state)
            for fn in self._cbs:
                fn(self.state)
