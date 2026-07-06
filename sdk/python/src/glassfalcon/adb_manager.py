# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
ADB manager, bundles platform-specific adb binary and exposes a clean API.

Handles:
  - Android phone detection
  - APK installation (flashing the Glass Falcon Android app)
  - Shell commands / file push
  - Cross-platform: Linux, Windows, macOS

ADB binary search order:
  1. <project_root>/bin/adb[.exe]   (bundled, recommended for distribution)
  2. PATH
"""

import os
import platform
import shutil
import subprocess
import threading
from pathlib import Path
from typing import Callable, Optional


_BIN_DIR = Path(__file__).parent.parent.parent / "bin"
_SYSTEM = platform.system().lower()  # 'linux', 'windows', 'darwin'


def _adb_name() -> str:
    return "adb.exe" if _SYSTEM == "windows" else "adb"


def find_adb() -> Optional[str]:
    """Return path to adb binary, preferring bundled copy."""
    bundled = _BIN_DIR / _adb_name()
    if bundled.exists():
        return str(bundled)
    return shutil.which("adb")


def _run(args: list, timeout: int = 30, **kwargs) -> subprocess.CompletedProcess:
    adb = find_adb()
    if not adb:
        raise FileNotFoundError(
            "adb not found. Place adb binary in bin/ or install Android Platform Tools."
        )
    return subprocess.run([adb] + args, capture_output=True, text=True,
                          timeout=timeout, **kwargs)


class ADBManager:
    """
    High-level ADB wrapper for Glass Falcon.

    All long-running operations accept an optional `progress_cb(str)` that is
    called with status lines from adb stdout (runs in a thread).
    """

    def __init__(self):
        self._lock = threading.Lock()

    # ── Device discovery ──────────────────────────────────────────────────────

    def adb_path(self) -> Optional[str]:
        return find_adb()

    def devices(self) -> list[dict]:
        """Return list of connected devices: [{serial, state, model}]"""
        try:
            r = _run(["devices", "-l"])
        except FileNotFoundError:
            return []
        devices = []
        for line in r.stdout.splitlines()[1:]:
            parts = line.split()
            if len(parts) < 2 or parts[1] in ("offline", "unauthorized", ""):
                continue
            serial = parts[0]
            state  = parts[1]
            model  = ""
            for p in parts[2:]:
                if p.startswith("model:"):
                    model = p.split(":", 1)[1]
            devices.append({"serial": serial, "state": state, "model": model})
        return devices

    def wait_for_device(self, serial: Optional[str] = None, timeout: int = 30):
        args = ["wait-for-device"]
        if serial:
            args = ["-s", serial] + args
        _run(args, timeout=timeout)

    # ── APK flash ─────────────────────────────────────────────────────────────

    def install_apk(self, apk_path: str, serial: Optional[str] = None,
                    progress_cb: Optional[Callable] = None,
                    replace: bool = True) -> bool:
        """
        Install an APK on the connected device.
        Returns True on success.
        progress_cb is called with each output line.
        """
        adb = find_adb()
        if not adb:
            if progress_cb:
                progress_cb("ERROR: adb not found")
            return False

        args = [adb]
        if serial:
            args += ["-s", serial]
        args += ["install"]
        if replace:
            args += ["-r"]
        args += [str(apk_path)]

        if progress_cb:
            progress_cb(f"Installing {Path(apk_path).name} ...")

        def _stream():
            proc = subprocess.Popen(args, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True)
            for line in proc.stdout:
                line = line.rstrip()
                if line and progress_cb:
                    progress_cb(line)
            proc.wait()
            if progress_cb:
                ok = proc.returncode == 0
                progress_cb("SUCCESS" if ok else f"FAILED (exit {proc.returncode})")

        t = threading.Thread(target=_stream, daemon=True)
        t.start()
        return True  # async; caller monitors progress_cb

    # ── Shell / file ops ──────────────────────────────────────────────────────

    def shell(self, cmd: str, serial: Optional[str] = None) -> str:
        args = []
        if serial:
            args += ["-s", serial]
        args += ["shell", cmd]
        r = _run(args, timeout=10)
        return r.stdout.strip()

    def push(self, local: str, remote: str, serial: Optional[str] = None) -> bool:
        args = []
        if serial:
            args += ["-s", serial]
        args += ["push", local, remote]
        r = _run(args, timeout=120)
        return r.returncode == 0

    def pull(self, remote: str, local: str, serial: Optional[str] = None) -> bool:
        args = []
        if serial:
            args += ["-s", serial]
        args += ["pull", remote, local]
        r = _run(args, timeout=120)
        return r.returncode == 0

    # ── Convenience ───────────────────────────────────────────────────────────

    def device_info(self, serial: Optional[str] = None) -> dict:
        def prop(name):
            args = []
            if serial:
                args += ["-s", serial]
            args += ["shell", "getprop", name]
            try:
                return _run(args, timeout=5).stdout.strip()
            except Exception:
                return ""
        return {
            "model":        prop("ro.product.model"),
            "manufacturer": prop("ro.product.manufacturer"),
            "android":      prop("ro.build.version.release"),
            "sdk":          prop("ro.build.version.sdk"),
            "serial":       prop("ro.serialno"),
        }
