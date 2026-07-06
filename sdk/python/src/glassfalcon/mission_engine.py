# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
Desktop mission engine, executes MissionPlan waypoints via DUML virtual RC sticks.

P-controller navigation: yaw toward bearing first, pitch forward when aligned,
throttle for altitude hold. Same approach as Android MissionEngine.kt.
Runs in a daemon thread so Qt UI is never blocked.
"""

from __future__ import annotations

import math
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Optional

from .mission_planner import MissionPlan, Waypoint, haversine, bearing
from .duml_cmds import (
    flyc_joystick, flyc_motor_ctrl, flyc_emergency_stop,
    camera_capture_photo, gimbal_abs_angle,
    FC, CAM, GIMB, PC, REQ,
)

# ── P-controller constants ─────────────────────────────────────────────────────
YAW_KP       = 0.8    # proportional gain yaw
ALT_KP       = 0.4    # proportional gain altitude
POS_KP       = 0.6    # proportional gain forward pitch
WP_RADIUS_M  = 4.0    # waypoint capture radius
YAW_THRESH   = 12.0   # degrees, must be aligned before pitching forward
MAX_PITCH    = 0.6    # ±1.0 stick scale
MAX_THROTTLE = 0.5
LOOP_HZ      = 10     # navigation loop rate


class MissionState(Enum):
    IDLE      = "IDLE"
    ARMING    = "ARMING"
    TAKEOFF   = "TAKEOFF"
    FLYING    = "FLYING"
    CAPTURING = "CAPTURING"
    RTH       = "RTH"
    LANDING   = "LANDING"
    COMPLETE  = "COMPLETE"
    ABORTED   = "ABORTED"


@dataclass
class MissionStatus:
    state:            MissionState = MissionState.IDLE
    wp_idx:           int = 0
    total_wps:        int = 0
    photos_taken:     int = 0
    distance_m:       float = 0.0
    log:              list[str] = field(default_factory=list)


class MissionEngine:
    """Thread-based mission executor. Call start(plan) to begin."""

    def __init__(self, duml):
        self._duml = duml
        self._state = MissionState.IDLE
        self._status = MissionStatus()
        self._plan: Optional[MissionPlan] = None
        self._abort = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._on_status: list[Callable[[MissionStatus], None]] = []
        # Latest drone state injected externally
        self._drone_lat  = 0.0
        self._drone_lon  = 0.0
        self._drone_alt  = 0.0
        self._drone_yaw  = 0.0
        self._drone_batt = 100

    # ── Public API ─────────────────────────────────────────────────────────────

    def on_status(self, cb: Callable[[MissionStatus], None]):
        self._on_status.append(cb)

    def update_drone_state(self, lat: float, lon: float, alt: float,
                           yaw: float, batt: int):
        self._drone_lat  = lat
        self._drone_lon  = lon
        self._drone_alt  = alt
        self._drone_yaw  = yaw
        self._drone_batt = batt

    def start(self, plan: MissionPlan):
        if self._thread and self._thread.is_alive():
            self.abort()
        self._plan = plan
        self._abort.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def abort(self):
        self._abort.set()
        self._send_joystick(0, 0, 0, 0)
        self._set_state(MissionState.ABORTED)
        if self._thread:
            self._thread.join(timeout=3)

    # ── Navigation loop ────────────────────────────────────────────────────────

    def _run(self):
        plan = self._plan
        self._status = MissionStatus(total_wps=len(plan.waypoints))
        self._log(f"Mission '{plan.name}' started, {len(plan.waypoints)} waypoints")

        self._set_state(MissionState.ARMING)
        self._send(FC, *flyc_motor_ctrl(True))
        time.sleep(3.0)
        if self._abort.is_set():
            return

        self._set_state(MissionState.TAKEOFF)
        target_alt = plan.waypoints[0].alt_m if plan.waypoints else 30.0
        self._climb_to_alt(target_alt)

        for i, wp in enumerate(plan.waypoints):
            if self._abort.is_set():
                break
            self._status.wp_idx = i
            self._set_state(MissionState.FLYING)
            self._log(f"→ WP {i + 1}/{len(plan.waypoints)}, {wp.label}")
            self._fly_to_wp(wp)

            if self._abort.is_set():
                break

            if wp.capture:
                self._set_state(MissionState.CAPTURING)
                # Point gimbal
                self._send(GIMB, *gimbal_abs_angle(wp.gimbal_pitch, 0.0, 0.0))
                time.sleep(0.6)
                self._send(CAM, *camera_capture_photo())
                self._status.photos_taken += 1
                self._log(f"  Photo {self._status.photos_taken} captured")
                time.sleep(1.5)

        if not self._abort.is_set():
            self._set_state(MissionState.RTH)
            self._log("Mission complete, return to home")
            self._send(FC, 0x03, 0x24, bytes([0x01]))  # RTH cmd_id=0x24
            time.sleep(30)  # wait for RTH to complete (drone handles it)
            self._set_state(MissionState.COMPLETE)
            self._log("Done.")
        else:
            self._send_joystick(0, 0, 0, 0)
            self._log("Mission aborted")

    def _fly_to_wp(self, wp: Waypoint):
        while not self._abort.is_set():
            dist = haversine(self._drone_lat, self._drone_lon, wp.lat, wp.lon)
            if dist < WP_RADIUS_M:
                self._status.distance_m += dist
                return

            # Altitude control
            alt_err = wp.alt_m - self._drone_alt
            throttle = max(-MAX_THROTTLE, min(MAX_THROTTLE, alt_err * ALT_KP))

            # Bearing control
            target_bear = bearing(self._drone_lat, self._drone_lon, wp.lat, wp.lon)
            yaw_err = self._normalize_angle(target_bear - self._drone_yaw)
            yaw = max(-1.0, min(1.0, yaw_err * YAW_KP / 180.0))

            # Only pitch forward once yaw is roughly aligned
            if abs(yaw_err) < YAW_THRESH:
                pitch = min(MAX_PITCH, dist * POS_KP / 100.0)
            else:
                pitch = 0.0

            self._send_joystick(0.0, pitch, throttle, yaw)
            self._notify()
            time.sleep(1.0 / LOOP_HZ)

        self._send_joystick(0, 0, 0, 0)

    def _climb_to_alt(self, target_m: float):
        for _ in range(int(target_m / 0.5 / (1.0 / LOOP_HZ))):
            if self._abort.is_set():
                return
            alt_err = target_m - self._drone_alt
            if abs(alt_err) < 1.0:
                break
            throttle = max(0.05, min(MAX_THROTTLE, alt_err * ALT_KP))
            self._send_joystick(0, 0, throttle, 0)
            time.sleep(1.0 / LOOP_HZ)
        self._send_joystick(0, 0, 0, 0)

    # ── Helpers ────────────────────────────────────────────────────────────────

    @staticmethod
    def _normalize_angle(deg: float) -> float:
        while deg >  180: deg -= 360
        while deg < -180: deg += 360
        return deg

    def _send(self, dst: int, cmd_set: int, cmd_id: int, payload: bytes = b""):
        try:
            self._duml.send(dst=dst, src=PC, cmd_type=REQ,
                            cmd_set=cmd_set, cmd_id=cmd_id, payload=payload)
        except Exception:
            pass

    def _send_joystick(self, roll: float, pitch: float,
                       throttle: float, yaw: float):
        self._send(FC, *flyc_joystick(roll, pitch, throttle, yaw))

    def _set_state(self, state: MissionState):
        self._state = state
        self._status.state = state
        self._notify()

    def _log(self, msg: str):
        ts = time.strftime("%H:%M:%S")
        self._status.log.append(f"{ts}  {msg}")
        self._status.log = self._status.log[-100:]
        self._notify()

    def _notify(self):
        import copy
        st = copy.copy(self._status)
        for cb in self._on_status:
            try:
                cb(st)
            except Exception:
                pass
