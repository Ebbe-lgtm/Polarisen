#!/bin/sh
# Installs Polarisen on an Android TV over the network (macOS / Linux).
# Usage: ./install.sh 192.168.0.50 [path/to/polarisen.apk]
# Needs adb in PATH and network debugging switched on in the TV's developer options.
set -e
IP="${1:?usage: ./install.sh <tv-ip> [apk]}"
APK="${2:-$(dirname "$0")/polarisen.apk}"
command -v adb >/dev/null || { echo "adb not found (install Android platform-tools)"; exit 1; }
[ -f "$APK" ] || { echo "APK not found: $APK (download polarisen.apk from the Releases page)"; exit 1; }
DEV="$IP:5555"
adb connect "$DEV"
[ "$(adb -s "$DEV" get-state)" = device ] || { echo "Could not reach $DEV. Accept the prompt on the TV and retry."; exit 1; }
adb -s "$DEV" install -r "$APK"
adb -s "$DEV" shell pm grant se.isbjorn.home android.permission.DUMP
adb -s "$DEV" shell pm grant se.isbjorn.home android.permission.WRITE_SECURE_SETTINGS
adb -s "$DEV" shell appops set se.isbjorn.home WRITE_SETTINGS allow
echo "Polarisen is installed. See the README for making it your home screen."