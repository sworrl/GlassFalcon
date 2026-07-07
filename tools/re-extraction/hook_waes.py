#!/usr/bin/env python3
# GlassFalcon — authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

"""
hook_waes.py — Host-side Frida runner for white-box AES key extraction.

Usage:
  python3 hook_waes.py [--spawn] [--host <phone-ip>]

  --spawn   : spawn the app fresh (use if it's not running)
  --host IP : connect to frida-server via network (ADB-over-WiFi setup)
              default: use USB ADB

Prerequisites on the phone (rooted Pixel 8 Pro / LineageOS):
  adb push /tmp/frida-server /data/local/tmp/frida-server
  adb shell chmod 755 /data/local/tmp/frida-server
  adb shell su -c /data/local/tmp/frida-server &
"""

import frida
import sys
import json
import argparse
import time

PACKAGE = "com.glassfalcon.gcs"
SCRIPT  = open("hook_waes.js").read()

def on_message(message, data):
    if message["type"] == "send":
        payload = message["payload"]
        evt = payload.get("event", "")
        if "key_captured" in evt or "java_key_captured" in evt:
            inp = payload.get("input", "")
            out = payload.get("output", "")
            print("\n" + "="*60)
            print("  WHITE-BOX KEY CAPTURED!")
            print(f"  Input  (encrypted blob): {inp}")
            print(f"  Output (AES video key):  {out}")
            print("="*60 + "\n")

            # Write to file for post-processing
            with open("captured_key.json", "w") as f:
                json.dump({"input": inp, "output": out}, f, indent=2)
            print("  Saved to captured_key.json")

            # Show Python snippet
            key_bytes = bytes.fromhex(out.replace(" ", ""))
            print(f"\n  Python: key = bytes.fromhex('{out.replace(' ', '')}')")
            print(f"  Key length: {len(key_bytes)} bytes (AES-{len(key_bytes)*8})\n")
    elif message["type"] == "error":
        print("[ERROR]", message.get("stack", message))
    else:
        print("[MSG]", message)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--spawn",    action="store_true", help="Spawn the app")
    parser.add_argument("--emulator", action="store_true", help="Target the running AVD emulator")
    parser.add_argument("--host",     default=None,        help="Phone IP for ADB-WiFi / network Frida")
    parser.add_argument("--port",     type=int, default=27042)
    args = parser.parse_args()

    print(f"[*] Frida {frida.__version__} — hooking {PACKAGE}")

    if args.host:
        print(f"[*] Connecting to frida-server at {args.host}:{args.port}")
        device = frida.get_device_manager().add_remote_device(f"{args.host}:{args.port}")
    elif args.emulator:
        print("[*] Targeting Android emulator")
        # find the emulator in device list
        mgr = frida.get_device_manager()
        device = next((d for d in mgr.enumerate_devices() if d.type == "emulator"), None)
        if device is None:
            print("[!] No emulator found. Is the AVD running?")
            sys.exit(1)
    else:
        print("[*] Using USB device (phone via ADB)")
        device = frida.get_usb_device(timeout=10)

    print(f"[*] Device: {device.name}")

    if args.spawn:
        print(f"[*] Spawning {PACKAGE}...")
        pid = device.spawn([PACKAGE])
        session = device.attach(pid)
        script = session.create_script(SCRIPT)
        script.on("message", on_message)
        script.load()
        device.resume(pid)
        print(f"[*] App spawned (PID {pid}) and hooked. Connect drone now.")
    else:
        print(f"[*] Attaching to running {PACKAGE}...")
        try:
            session = device.attach(PACKAGE)
        except frida.ProcessNotFoundError:
            print(f"[!] {PACKAGE} not running. Launch it on the phone or use --spawn")
            sys.exit(1)
        script = session.create_script(SCRIPT)
        script.on("message", on_message)
        script.load()
        print("[*] Hooked. Connect drone to phone now and wait for key capture.")

    print("[*] Waiting for decryptFromWhiteBox call... (Ctrl+C to exit)")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\n[*] Detaching...")
        session.detach()

if __name__ == "__main__":
    main()
