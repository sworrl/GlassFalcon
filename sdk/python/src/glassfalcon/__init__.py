# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
Glass Falcon SDK, a FOSS toolkit for building software and firmware for DJI
drones, independent of DJI's proprietary Mobile SDK.

It speaks the drone's native DUML protocol directly, so any tool built on it
talks to the aircraft the same way the official app does, no activation gate,
no cloud, no closed-source blobs.

Quick start
-----------
    from glassfalcon import DUMLConnection, TelemetryDecoder, duml_cmds as C

    conn = DUMLConnection.open_serial("/dev/ttyACM0")   # needs the `serial` extra
    dec  = TelemetryDecoder()
    conn.add_listener(dec.feed)            # decode every frame into dec.state
    conn.send_cmd(C.get_device_state())    # ask the FC to start pushing OSD data

    import time; time.sleep(1)
    print(dec.state.battery_pct, dec.state.gps_sats, dec.state.lat, dec.state.lon)

Submodules
----------
    duml            DUML packet framing (CRC-8/16, build/parse) + connection
    duml_cmds       Ready-made command builders (camera, gimbal, flight ctrl)
    telemetry       OSD/telemetry decoder -> DroneState
    params          Flight-controller parameter get/set + CSV catalogue
    mission_planner Waypoint missions (grid survey, orbit, Claude-assisted)
    mission_engine  Mission execution state machine
    adb_manager     USB/ADB transport discovery + bridge
    video_decrypt   DJI encrypted video stream tooling
"""

from . import (
    duml,
    duml_cmds,
    telemetry,
    params,
    mission_engine,
    mission_planner,
    adb_manager,
    video_decrypt,
)

from .duml import DUMLConnection, build_packet, parse_frame, crc8, crc16
from .telemetry import DroneState, TelemetryDecoder, TelemetryHistory
from .params import FCParam, ParamManager, load_csv
from .adb_manager import ADBManager, find_adb
from .mission_planner import (
    Waypoint, MissionPlan, grid_survey, orbit, haversine, bearing,
)
from .mission_engine import MissionEngine, MissionState, MissionStatus

__version__ = "0.1.0"

__all__ = [
    # submodules
    "duml", "duml_cmds", "telemetry", "params",
    "mission_engine", "mission_planner", "adb_manager", "video_decrypt",
    # protocol
    "DUMLConnection", "build_packet", "parse_frame", "crc8", "crc16",
    # telemetry
    "DroneState", "TelemetryDecoder", "TelemetryHistory",
    # params
    "FCParam", "ParamManager", "load_csv",
    # transport
    "ADBManager", "find_adb",
    # missions
    "Waypoint", "MissionPlan", "grid_survey", "orbit", "haversine", "bearing",
    "MissionEngine", "MissionState", "MissionStatus",
]
