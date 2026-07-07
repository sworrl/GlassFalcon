#!/usr/bin/env python3
# GlassFalcon — authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
hook_dex.py — Dump bangcle-decrypted DEX from DJI MSDK at class-load time.

Usage:
  python3 hook_dex.py [--spawn] [--host <phone-ip>]

After a successful run:
  adb pull /data/local/tmp/dex_dump_0.dex
  jadx dex_dump_0.dex -d jadx_out/

Prerequisites:
  - frida-server running on the phone (root):
      adb push /tmp/frida-server /data/local/tmp/frida-server
      adb shell su -c '/data/local/tmp/frida-server -l 0.0.0.0 &'
  - GlassFalconGCS APK installed (or any app embedding DJI MSDK)
"""

import frida
import sys
import json
import argparse
import time
import pathlib

PACKAGE    = "com.glassfalcon.gcs"
SCRIPT_SRC = (pathlib.Path(__file__).parent / "hook_dex.js").read_text()


def on_message(message, data):
    if message["type"] == "send":
        evt = message["payload"].get("event", "")
        if evt == "dex_dumped":
            p = message["payload"]
            print(f"\n[+] DEX #{p['n']} saved on phone: {p['path']}  ({p['size']} B)")
            print(f"    Pull with:  adb pull {p['path']}")
            print(f"    Decompile:  jadx {pathlib.Path(p['path']).name} -d jadx_out/\n")
        elif evt == "dex_bytes":
            # Fallback: bytes delivered over Frida pipe
            p = message["payload"]
            out = pathlib.Path(f"dex_dump_{p['n']}.dex")
            out.write_bytes(bytes(data))
            print(f"\n[+] DEX #{p['n']} received over Frida pipe → {out}  ({p['size']} B)")
            print(f"    Decompile:  jadx {out} -d jadx_out/\n")
        elif evt == "dex_file_load":
            print(f"[~] DexClassLoader: {message['payload']['path']}")
        else:
            print(f"[MSG] {message['payload']}")
    elif message["type"] == "error":
        print(f"[ERROR] {message.get('stack', message)}")
    else:
        print(f"[MSG] {message}")


def get_device(args):
    if args.host:
        print(f"[*] Connecting to frida-server at {args.host}:27042")
        return frida.get_device_manager().add_remote_device(f"{args.host}:27042")
    return frida.get_usb_device(timeout=10)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spawn",  action="store_true", help="Spawn the app fresh")
    parser.add_argument("--host",   default=None,        help="Phone IP (ADB-WiFi / network Frida)")
    args = parser.parse_args()

    device = get_device(args)
    print(f"[*] Device: {device.name}")

    if args.spawn:
        print(f"[*] Spawning {PACKAGE}...")
        pid     = device.spawn([PACKAGE])
        session = device.attach(pid)
        script  = session.create_script(SCRIPT_SRC)
        script.on("message", on_message)
        script.load()
        device.resume(pid)
        print(f"[*] App spawned (PID {pid}) — waiting for MSDK DEX load...")
    else:
        print(f"[*] Attaching to running {PACKAGE}...")
        try:
            session = device.attach(PACKAGE)
        except frida.ProcessNotFoundError:
            print(f"[!] {PACKAGE} not running. Launch it on the phone or use --spawn")
            sys.exit(1)
        script  = session.create_script(SCRIPT_SRC)
        script.on("message", on_message)
        script.load()
        print("[*] Hook loaded — if the app is already past class-load, restart it with --spawn")

    print("[*] Watching... Ctrl+C to stop\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Detaching...")
        session.detach()


if __name__ == "__main__":
    main()
