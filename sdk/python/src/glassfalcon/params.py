# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
FC parameter read/write over DUML for wm240.

Wraps comm_og_service_tool.py in a subprocess (authoritative implementation)
and provides a clean Qt-friendly async interface + local CSV cache.
"""

import csv
import io
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List, Optional


TOOL = Path(__file__).parent.parent.parent / "research" / "dji-firmware-tools" / "comm_og_service_tool.py"
CAPTURES = Path(__file__).parent.parent.parent / "captures"

PYTHON = sys.executable  # use current venv python


@dataclass
class FCParam:
    idx:     int
    tbl_idx: str
    name:    str
    type_id: int
    size:    int
    min:     float
    max:     float
    default: float
    current: Optional[float] = None


def load_csv(path: Path) -> List[FCParam]:
    params = []
    with open(path, newline="") as f:
        reader = csv.DictReader(f, delimiter=";")
        for row in reader:
            try:
                params.append(FCParam(
                    idx     = int(row["idx"]),
                    tbl_idx = row["tbl:idx"],
                    name    = row["name"],
                    type_id = int(row["typeId"]),
                    size    = int(row["size"]),
                    min     = float(row["min"]),
                    max     = float(row["max"]),
                    default = float(row["deflt"]),
                ))
            except (ValueError, KeyError):
                continue
    return params


class ParamManager:
    def __init__(self, port: str = "/dev/ttyACM0", baud: int = 115200):
        self.port  = port
        self.baud  = baud
        self.params: List[FCParam] = []
        self._cbs:  List[Callable] = []

        # Load cached CSV immediately
        cached = CAPTURES / "wm240_flyc_params_complete.csv"
        if cached.exists():
            self.params = load_csv(cached)

    def on_update(self, fn: Callable):
        self._cbs.append(fn)

    def refresh_all(self, start: int = 0, count: int = 1400):
        """Spawn comm_og_service_tool in a thread, emit on_update per chunk."""
        t = threading.Thread(target=self._fetch, args=(start, count), daemon=True)
        t.start()

    def _fetch(self, start: int, count: int):
        cmd = [
            PYTHON, str(TOOL),
            "--port", self.port, "-b", str(self.baud),
            "-w", "2000",
            "WM240", "FlycParam", "list",
            "-f", "csv", "-s", str(start), "-c", str(count),
        ]
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
            lines = [l for l in result.stdout.splitlines() if ";" in l]
            reader = csv.DictReader(io.StringIO("\n".join(lines)), delimiter=";")
            new_params = []
            for row in reader:
                try:
                    new_params.append(FCParam(
                        idx     = int(row["idx"]),
                        tbl_idx = row["tbl:idx"],
                        name    = row["name"],
                        type_id = int(row["typeId"]),
                        size    = int(row["size"]),
                        min     = float(row["min"]),
                        max     = float(row["max"]),
                        default = float(row["deflt"]),
                    ))
                except (ValueError, KeyError):
                    continue
            if new_params:
                # Merge into self.params by idx
                idx_map = {p.idx: p for p in self.params}
                for p in new_params:
                    idx_map[p.idx] = p
                self.params = sorted(idx_map.values(), key=lambda x: x.idx)
                for fn in self._cbs:
                    fn(self.params)
        except subprocess.TimeoutExpired:
            pass

    def set_param(self, name: str, value: float, done_cb: Optional[Callable] = None):
        def _run():
            cmd = [
                PYTHON, str(TOOL),
                "--port", self.port, "-b", str(self.baud),
                "WM240", "FlycParam", "set", name, str(value),
            ]
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            if done_cb:
                done_cb(result.returncode == 0, result.stdout + result.stderr)
        threading.Thread(target=_run, daemon=True).start()
