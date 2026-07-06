# GlassFalcon, authored and owned by FalconTechnix.
# Copyright (C) 2026 FalconTechnix.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Free software released by FalconTechnix under the GNU GPL v3 or later.
# See LICENSE at the repository root or <https://www.gnu.org/licenses/>.
#
# One-command offline installer for Windows, no separate ADB download, no
# Android Studio, no build toolchain. If `adb` isn't already on your PATH,
# this fetches Google's own official platform-tools zip straight from
# dl.google.com (the same artifact Android Studio installs), unpacks it into
# .\tools\.platform-tools\, and uses that copy, nothing is installed
# system-wide, nothing touches your PATH permanently.
#
#   .\tools\install.ps1                  installs the latest GitHub release APK
#   .\tools\install.ps1 -Apk foo.apk      installs a specific local APK instead
#
# Requires: a phone with USB debugging enabled (Settings -> About phone ->
# tap "Build number" 7 times -> Developer options -> USB debugging), connected
# over USB, with the "Allow USB debugging" prompt accepted on the phone screen.

param([string]$Apk = "")

$ErrorActionPreference = "Stop"
$Repo = "sworrl/GlassFalcon"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$PtDir = Join-Path $Here ".platform-tools"

Write-Host "=== GlassFalcon offline installer ==="

# -- Locate or fetch adb ------------------------------------------------------
$Adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $Adb -and (Test-Path (Join-Path $PtDir "adb.exe"))) {
    $Adb = Join-Path $PtDir "adb.exe"
}

if (-not $Adb) {
    Write-Host "[i] adb not found on PATH, fetching Google's official platform-tools"
    Write-Host "    (one-time, ~10MB, from dl.google.com, the same file Android Studio uses)"
    $ZipUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
    $TmpZip = Join-Path $env:TEMP "platform-tools-latest-windows.zip"
    Invoke-WebRequest -Uri $ZipUrl -OutFile $TmpZip
    if (Test-Path $PtDir) { Remove-Item $PtDir -Recurse -Force }
    Expand-Archive -Path $TmpZip -DestinationPath $Here -Force
    Rename-Item -Path (Join-Path $Here "platform-tools") -NewName ".platform-tools"
    Remove-Item $TmpZip -Force
    $Adb = Join-Path $PtDir "adb.exe"
    Write-Host "[+] adb ready at $Adb"
} else {
    Write-Host "[+] Using adb: $Adb"
}

# -- Locate or fetch the APK ---------------------------------------------------
if (-not $Apk) {
    Write-Host "[i] No APK path given, fetching the latest GitHub release"
    $ApiUrl = "https://api.github.com/repos/$Repo/releases/latest"
    $Release = Invoke-RestMethod -Uri $ApiUrl -Headers @{ "User-Agent" = "GlassFalcon-installer" }
    $Asset = $Release.assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1
    if (-not $Asset) {
        Write-Host "[!] Couldn't find a released APK at $ApiUrl"
        Write-Host "    Build one yourself instead: cd android; .\gradlew.bat assembleDebug"
        Write-Host "    then re-run: .\tools\install.ps1 -Apk android\app\build\outputs\apk\debug\<file>.apk"
        exit 1
    }
    $Apk = Join-Path $Here "GlassFalcon-latest.apk"
    Write-Host "[i] Downloading $($Asset.browser_download_url)"
    Invoke-WebRequest -Uri $Asset.browser_download_url -OutFile $Apk
}

if (-not (Test-Path $Apk)) {
    Write-Host "[!] APK not found: $Apk"
    exit 1
}

# -- Install --------------------------------------------------------------------
Write-Host "[i] Waiting for a device (plug in your phone now if you haven't) ..."
& $Adb wait-for-device
Write-Host "[i] Installing $Apk"
& $Adb install -r $Apk
Write-Host "[+] Done, GlassFalcon should now be on your phone's app list."
