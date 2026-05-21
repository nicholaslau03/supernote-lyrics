#!/usr/bin/env bash
# install-apk.sh — push the latest debug APK to the connected Supernote (or any adb device)
#
# Usage:
#   ./install-apk.sh                      # uses ~/Downloads/SupernoteLyrics-debug.apk
#   ./install-apk.sh path/to/file.apk     # uses the path you give
set -e

APK="${1:-$HOME/Downloads/SupernoteLyrics-debug.apk}"

if [ ! -f "$APK" ]; then
  echo "APK not found: $APK"
  exit 1
fi

DEVICES=$(adb devices | tail -n +2 | awk '$2=="device"{print $1}')
COUNT=$(echo "$DEVICES" | grep -c . || true)

if [ "$COUNT" -eq 0 ]; then
  echo "No adb device connected."
  echo "Plug the Manta in (or run 'adb connect <ip>' for wireless) and ensure USB debugging is on."
  exit 1
fi

echo "Installing $(basename "$APK") to:"
echo "$DEVICES" | sed 's/^/  /'
adb install -r "$APK"
echo "Done."
