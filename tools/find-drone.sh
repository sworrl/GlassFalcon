#!/usr/bin/env bash
# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

# Detect a connected DJI aircraft / remote controller and report how it
# enumerated, so we know which passthrough the container needs.
# Run on the HOST after plugging in.  Safe / read-only.
set -uo pipefail

DJI_VID="2ca3"   # DJI Technology Co., Ltd

echo "=== USB: DJI vendor devices ==="
found=0
while read -r line; do
  echo "  $line"
  found=1
done < <(lsusb | grep -i "$DJI_VID" )
if [ "$found" = 0 ]; then
  echo "  (none with VID $DJI_VID, also showing anything new/unknown:)"
  lsusb | grep -viE "1d6b|0b05|2109|05e3|25a7|8087|258a|2dc8" | sed 's/^/  /'
fi

echo
echo "=== /dev/bus/usb node + permissions for any DJI device ==="
for d in /sys/bus/usb/devices/*; do
  vid=$(cat "$d/idVendor" 2>/dev/null)
  if [ "$vid" = "$DJI_VID" ]; then
    busnum=$(cat "$d/busnum" 2>/dev/null)
    devnum=$(cat "$d/devnum" 2>/dev/null)
    node=$(printf "/dev/bus/usb/%03d/%03d" "$busnum" "$devnum")
    echo "  product : $(cat "$d/product" 2>/dev/null)"
    echo "  node    : $node"
    ls -l "$node" 2>&1 | sed 's/^/  perms   : /'
    getfacl -p "$node" 2>/dev/null | grep -E "^user:" | sed 's/^/  acl     : /'
  fi
done

echo
echo "=== Serial ports (DUML-over-serial path) ==="
ls -l /dev/ttyACM* /dev/ttyUSB* 2>/dev/null | sed 's/^/  /' || echo "  (no ttyACM/ttyUSB)"

echo
echo "=== New network interfaces (DJI USB-RNDIS path) ==="
ip -o link show 2>/dev/null | grep -iE "usb|enx|rndis" | sed 's/^/  /' || echo "  (none obvious)"

echo
echo "Done. Paste this output back and I'll wire the exact passthrough."
