#!/usr/bin/env bash
# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

# Connect to the rooted Pixel 8 Pro (LineageOS) over WiFi ADB so the USB-C
# port stays free for the drone over OTG.
#
# ADB-over-TCP is persistent on the phone (persist.adb.tcp.port=5555), so adbd
# listens on 5555 after every reboot -- the Wireless-debugging pairing menu is
# never needed.  This just does the host-side `adb connect`.
#
#   ./tools/phone.sh           connect using last-known IP (set PHONE_IP once, see below)
#   ./tools/phone.sh 1.2.3.4   connect to a specific IP
#   PHONE_IP=1.2.3.4 ./tools/phone.sh
set -uo pipefail

PORT=5555
IP="${1:-${PHONE_IP:-}}"
if [ -z "$IP" ]; then
  echo "[!] No IP given and PHONE_IP is unset. Plug the phone in via USB once, "
  echo "    this script will read its current WiFi IP automatically, or pass"
  echo "    an IP directly / export PHONE_IP=<ip>."
fi

# Already connected?  Nothing to do.
if adb devices | grep -q "${IP}:${PORT}[[:space:]]*device"; then
  echo "[+] Already connected: ${IP}:${PORT}"
  exit 0
fi

# If the Pixel happens to be on USB, refresh its current WiFi IP from wlan0 so
# the script keeps working even if DHCP handed out a new address.
USB=$(adb devices | awk '$2=="device"{print $1}' | grep -vE ':|emulator' | head -n1)
if [ -n "${USB:-}" ]; then
  FRESH=$(adb -s "$USB" shell ip -f inet addr show wlan0 2>/dev/null \
            | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
  [ -n "${FRESH:-}" ] && IP="$FRESH"
  echo "[*] USB device $USB present; wlan0 IP = ${IP}"
fi

echo "[*] adb connect ${IP}:${PORT}"
adb connect "${IP}:${PORT}"
if adb -s "${IP}:${PORT}" shell getprop ro.product.model 2>/dev/null; then
  echo "[+] Ready over WiFi. USB-C is free for the drone."
else
  echo "[!] Could not reach ${IP}:${PORT} -- plug the Pixel in over USB once and re-run."
  exit 1
fi
