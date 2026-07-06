#!/usr/bin/env bash
# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.

# One-command offline installer for macOS/Linux, no separate ADB download,
# no Android Studio, no build toolchain. If `adb` isn't already on your PATH,
# this fetches Google's own official platform-tools zip for your OS straight
# from dl.google.com (the same artifact `sdkmanager`/Android Studio install),
# unpacks it into ./tools/.platform-tools/, and uses that copy, nothing is
# installed system-wide, nothing modifies your shell profile.
#
#   ./tools/install.sh                 install the latest GitHub release APK
#   ./tools/install.sh path/to/foo.apk  install a specific local APK instead
#
# Requires: a phone with USB debugging enabled (Settings → About phone → tap
# "Build number" 7 times → Developer options → USB debugging), connected over
# USB, with the "Allow USB debugging" prompt accepted on the phone screen.
set -uo pipefail

REPO="sworrl/GlassFalcon"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PT_DIR="$HERE/.platform-tools"

echo "=== GlassFalcon offline installer ==="

# ── Locate or fetch adb ─────────────────────────────────────────────────────
ADB="$(command -v adb || true)"
if [ -z "$ADB" ] && [ -x "$PT_DIR/adb" ]; then
  ADB="$PT_DIR/adb"
fi

if [ -z "$ADB" ]; then
  echo "[i] adb not found on PATH, fetching Google's official platform-tools"
  echo "    (one-time, ~10MB, from dl.google.com, the same file Android Studio uses)"
  case "$(uname -s)" in
    Darwin) PLATFORM="darwin" ;;
    Linux)  PLATFORM="linux" ;;
    *) echo "[!] Unsupported OS for auto-download, install Android platform-tools"
       echo "    manually and re-run, or use tools/install.ps1 on Windows."; exit 1 ;;
  esac
  ZIP_URL="https://dl.google.com/android/repository/platform-tools-latest-${PLATFORM}.zip"
  TMP_ZIP="$(mktemp)"
  if ! curl -fsSL "$ZIP_URL" -o "$TMP_ZIP"; then
    echo "[!] Download failed: $ZIP_URL"; rm -f "$TMP_ZIP"; exit 1
  fi
  mkdir -p "$PT_DIR"
  unzip -qo "$TMP_ZIP" -d "$HERE"
  rm -f "$TMP_ZIP"
  # The zip extracts to $HERE/platform-tools/, move its contents into our
  # own .platform-tools/ so re-running this script doesn't re-nest it.
  rm -rf "$PT_DIR"
  mv "$HERE/platform-tools" "$PT_DIR"
  ADB="$PT_DIR/adb"
  chmod +x "$ADB"
  echo "[+] adb ready at $ADB"
else
  echo "[+] Using adb: $ADB"
fi

# ── Locate or fetch the APK ─────────────────────────────────────────────────
APK="${1:-}"
if [ -z "$APK" ]; then
  echo "[i] No APK path given, fetching the latest GitHub release"
  API_URL="https://api.github.com/repos/${REPO}/releases/latest"
  ASSET_URL="$(curl -fsSL "$API_URL" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -n1 | sed 's/.*"\(https[^"]*\)"/\1/')"
  if [ -z "$ASSET_URL" ]; then
    echo "[!] Couldn't find a released APK at $API_URL"
    echo "    Build one yourself instead: cd android && ./gradlew assembleDebug"
    echo "    then re-run: ./tools/install.sh android/app/build/outputs/apk/debug/*.apk"
    exit 1
  fi
  APK="$HERE/GlassFalcon-latest.apk"
  echo "[i] Downloading $ASSET_URL"
  curl -fsSL "$ASSET_URL" -o "$APK"
fi

if [ ! -f "$APK" ]; then
  echo "[!] APK not found: $APK"; exit 1
fi

# ── Install ──────────────────────────────────────────────────────────────────
echo "[i] Waiting for a device (plug in your phone now if you haven't) …"
"$ADB" wait-for-device
echo "[i] Installing $APK"
"$ADB" install -r "$APK"
echo "[+] Done, GlassFalcon should now be on your phone's app list."
