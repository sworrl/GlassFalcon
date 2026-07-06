# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
Mission planner, grid survey, orbit, and Claude-assisted planning.

Also provides battery budget estimation: given a wm240 at ~25 min hover endurance
and measured average current draw, computes per-segment battery % and annotates
the waypoint list so the map can colour-code each leg.
"""

from __future__ import annotations
import json
import math
import urllib.request
import urllib.error
from dataclasses import dataclass, field
from typing import Any

# ── wm240 camera constants ─────────────────────────────────────────────────────
# Hasselblad L-Format 20MP, 13.2×8.8mm sensor, 28mm equiv → actual FL ≈10.26mm
HFOV_DEG = 65.0
VFOV_DEG = 44.0
EARTH_R   = 6_371_000.0   # metres

# wm240 battery: 3850 mAh, nominal consumption ≈ 155 mAh/min in normal flight
MAH_TOTAL          = 3850.0
MAH_PER_MIN_HOVER  = 155.0
MAH_PER_MIN_CRUISE = 180.0   # slightly higher with camera
RESERVE_PCT        = 25.0    # never plan below this battery level


# ── Data classes ───────────────────────────────────────────────────────────────

@dataclass
class Waypoint:
    lat:            float
    lon:            float
    alt_m:          float
    capture:        bool  = False
    gimbal_pitch:   float = -90.0
    label:          str   = ""
    batt_pct_est:   float = 100.0   # estimated battery at this point


@dataclass
class MissionPlan:
    name:                   str
    waypoints:              list[Waypoint]
    estimated_photos:       int   = 0
    estimated_minutes:      float = 0.0
    estimated_area_m2:      float = 0.0
    gsd_cm:                 float = 0.0
    min_batt_pct:           float = 100.0
    feasible:               bool  = True
    notes:                  str   = ""


# ── Geometry helpers ───────────────────────────────────────────────────────────

def haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distance in metres."""
    r = EARTH_R
    la1, lo1, la2, lo2 = map(math.radians, [lat1, lon1, lat2, lon2])
    a = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def bearing(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """True bearing in degrees."""
    dLon = math.radians(lon2 - lon1)
    y = math.sin(dLon) * math.cos(math.radians(lat2))
    x = (math.cos(math.radians(lat1)) * math.sin(math.radians(lat2)) -
         math.sin(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.cos(dLon))
    return (math.degrees(math.atan2(y, x)) + 360) % 360


def _m_per_deg_lat() -> float:
    return EARTH_R * math.pi / 180.0


def _m_per_deg_lon(lat_deg: float) -> float:
    return EARTH_R * math.pi / 180.0 * math.cos(math.radians(lat_deg))


# ── Battery model ──────────────────────────────────────────────────────────────

def _annotate_battery(waypoints: list[Waypoint], speed_ms: float,
                      start_pct: float = 95.0) -> float:
    """
    Annotate each waypoint's batt_pct_est in place.
    Returns minimum estimated battery % across the mission.
    """
    pct = start_pct
    for i, wp in enumerate(waypoints):
        wp.batt_pct_est = pct
        if i + 1 < len(waypoints):
            dist = haversine(wp.lat, wp.lon, waypoints[i + 1].lat, waypoints[i + 1].lon)
            minutes = dist / max(speed_ms, 0.5) / 60.0
            pct -= (MAH_PER_MIN_CRUISE / MAH_TOTAL * 100.0) * minutes
    return pct


# ── Grid survey ────────────────────────────────────────────────────────────────

def grid_survey(
    corners: list[tuple[float, float]],
    alt_m:   float = 80.0,
    front_overlap_pct: float = 75.0,
    side_overlap_pct:  float = 70.0,
    speed_ms: float = 5.0,
    start_batt_pct: float = 95.0,
) -> MissionPlan:
    lats = [c[0] for c in corners]
    lons = [c[1] for c in corners]
    min_lat, max_lat = min(lats), max(lats)
    min_lon, max_lon = min(lons), max(lons)
    mid_lat = (min_lat + max_lat) / 2

    fp_w = 2 * alt_m * math.tan(math.radians(HFOV_DEG / 2))
    fp_h = 2 * alt_m * math.tan(math.radians(VFOV_DEG / 2))
    line_spacing_m = fp_w * (1.0 - side_overlap_pct / 100.0)
    photo_spacing_m = fp_h * (1.0 - front_overlap_pct / 100.0)

    width_m  = haversine(min_lat, min_lon, min_lat, max_lon)
    height_m = haversine(min_lat, min_lon, max_lat, min_lon)

    lat_step  = line_spacing_m  / _m_per_deg_lat()
    photo_lat_step = photo_spacing_m / _m_per_deg_lat()

    waypoints: list[Waypoint] = []
    line_idx = 0
    lat = min_lat + lat_step / 2

    while lat < max_lat:
        going_east = line_idx % 2 == 0
        lon = min_lon if going_east else max_lon
        lon_step = photo_spacing_m / _m_per_deg_lon(lat)

        while (lon < max_lon if going_east else lon > min_lon):
            waypoints.append(Waypoint(
                lat=lat, lon=lon, alt_m=alt_m,
                capture=True, gimbal_pitch=-90.0,
                label=f"L{line_idx}",
            ))
            lon += lon_step if going_east else -lon_step

        lat += lat_step
        line_idx += 1

    total_dist = sum(
        haversine(waypoints[i].lat, waypoints[i].lon,
                  waypoints[i+1].lat, waypoints[i+1].lon)
        for i in range(len(waypoints) - 1)
    ) if len(waypoints) > 1 else 0

    min_batt = _annotate_battery(waypoints, speed_ms, start_batt_pct)
    gsd_cm = alt_m / 20.0 * 2.4   # rough: 2.4 cm/px at 20m

    feasible = min_batt >= RESERVE_PCT
    notes = "" if feasible else (
        f"⚠ Battery may drop to {min_batt:.1f}%, below {RESERVE_PCT}% reserve. "
        f"Reduce area, increase speed, or split into multiple flights."
    )

    return MissionPlan(
        name=f"Grid {alt_m:.0f}m",
        waypoints=waypoints,
        estimated_photos=sum(1 for w in waypoints if w.capture),
        estimated_minutes=total_dist / speed_ms / 60.0,
        estimated_area_m2=width_m * height_m,
        gsd_cm=round(gsd_cm, 2),
        min_batt_pct=min_batt,
        feasible=feasible,
        notes=notes,
    )


# ── Orbit ──────────────────────────────────────────────────────────────────────

def orbit(
    center_lat: float, center_lon: float,
    radius_m: float, alt_m: float,
    steps: int = 36, capture_every: int = 3,
    gimbal_pitch: float = -30.0,
    speed_ms: float = 3.0,
    start_batt_pct: float = 95.0,
) -> MissionPlan:
    waypoints = []
    for i in range(steps + 1):
        angle = 2 * math.pi * i / steps
        lat = center_lat + (radius_m * math.cos(angle)) / _m_per_deg_lat()
        lon = center_lon + (radius_m * math.sin(angle)) / _m_per_deg_lon(center_lat)
        waypoints.append(Waypoint(
            lat=lat, lon=lon, alt_m=alt_m,
            capture=(i % capture_every == 0),
            gimbal_pitch=gimbal_pitch,
            label=f"Orb{i}",
        ))

    circumference = 2 * math.pi * radius_m
    min_batt = _annotate_battery(waypoints, speed_ms, start_batt_pct)
    return MissionPlan(
        name=f"Orbit r={radius_m:.0f}m",
        waypoints=waypoints,
        estimated_photos=sum(1 for w in waypoints if w.capture),
        estimated_minutes=circumference / speed_ms / 60.0,
        estimated_area_m2=math.pi * radius_m ** 2,
        min_batt_pct=min_batt,
        feasible=min_batt >= RESERVE_PCT,
    )


# ── Claude integration ─────────────────────────────────────────────────────────

FLIGHT_SYSTEM = """
You are Glass Falcon AI, an expert autonomous drone mission planner for a DJI Mavic 2 Pro (wm240).

Camera: Hasselblad 20MP, HFOV≈65°, VFOV≈44°. Battery: 3850mAh, ~25min flight.
For photogrammetry: minimum 70% overlap. GSD target ≤ 3 cm/px. Never plan below 30m AGL.
Always call create_mission with precise coordinates. Check battery feasibility.
"""

TOOLS = [
    {
        "name": "create_mission",
        "description": "Create a drone mapping mission with waypoints and parameters",
        "input_schema": {
            "type": "object",
            "properties": {
                "name":               {"type": "string"},
                "mission_type":       {"type": "string", "enum": ["grid_survey", "orbit", "waypoints"]},
                "altitude_m":         {"type": "number"},
                "front_overlap_pct":  {"type": "number"},
                "side_overlap_pct":   {"type": "number"},
                "speed_ms":           {"type": "number"},
                "area_corners":       {
                    "type": "array",
                    "items": {"type": "array", "items": {"type": "number"}},
                    "description": "[[lat,lon], ...] polygon corners"
                },
                "orbit_radius_m":     {"type": "number"},
                "orbit_center":       {"type": "array", "items": {"type": "number"}},
                "gimbal_pitch":       {"type": "number"},
                "notes":              {"type": "string"},
            },
            "required": ["name", "mission_type", "altitude_m"],
        },
    }
]


def plan_with_claude(
    user_request: str,
    api_key: str,
    current_lat: float = 0.0,
    current_lon: float = 0.0,
    batt_pct: float = 95.0,
) -> tuple[MissionPlan | None, str]:
    """
    Ask Claude to plan a mission. Returns (plan, notes_text).
    plan is None if Claude didn't call create_mission.
    """
    body = json.dumps({
        "model": "claude-sonnet-4-6",
        "max_tokens": 4096,
        "system": FLIGHT_SYSTEM,
        "tools": TOOLS,
        "tool_choice": {"type": "auto"},
        "messages": [{
            "role": "user",
            "content": (
                f"Current position: lat={current_lat:.6f}, lon={current_lon:.6f}\n"
                f"Battery: {batt_pct:.0f}%\n\n"
                f"{user_request}"
            ),
        }],
    }).encode()

    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages",
        data=body,
        headers={
            "anthropic-version": "2023-06-01",
            "x-api-key": api_key,
            "content-type": "application/json",
        },
    )

    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.loads(resp.read())

    notes = ""
    plan = None
    for block in data.get("content", []):
        if block.get("type") == "text":
            notes += block["text"]
        elif block.get("type") == "tool_use" and block.get("name") == "create_mission":
            inp = block["input"]
            mission_type = inp.get("mission_type", "grid_survey")
            alt = float(inp.get("altitude_m", 80))
            speed = float(inp.get("speed_ms", 5))
            gimbal = float(inp.get("gimbal_pitch", -90))

            if mission_type == "orbit":
                center = inp.get("orbit_center", [current_lat, current_lon])
                plan = orbit(
                    center_lat=center[0], center_lon=center[1],
                    radius_m=float(inp.get("orbit_radius_m", 30)),
                    alt_m=alt, gimbal_pitch=gimbal, speed_ms=speed,
                    start_batt_pct=batt_pct,
                )
            else:
                corners_raw = inp.get("area_corners")
                if corners_raw and len(corners_raw) >= 2:
                    corners = [(c[0], c[1]) for c in corners_raw]
                else:
                    d = 0.001
                    corners = [
                        (current_lat - d, current_lon - d),
                        (current_lat + d, current_lon + d),
                    ]
                plan = grid_survey(
                    corners=corners, alt_m=alt,
                    front_overlap_pct=float(inp.get("front_overlap_pct", 75)),
                    side_overlap_pct=float(inp.get("side_overlap_pct", 70)),
                    speed_ms=speed, start_batt_pct=batt_pct,
                )
            if inp.get("notes"):
                notes = inp["notes"] + "\n" + notes

    return plan, notes
