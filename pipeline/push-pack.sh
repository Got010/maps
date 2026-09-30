#!/usr/bin/env bash
# Copy a built pack to a USB-connected phone for the app to load.
# Works with the release APK from GitHub: packs go to the app's import folder,
# Android/data/<app id>/files/packs/, which adb can write and other apps can't read.
# Open Southbound once first so Android creates that folder.
#
# Usage: push-pack.sh [pack-file]   (default: data/out/basemap-nyc.pmtiles)
set -euo pipefail

APP_ID="${APP_ID:-app.southbound}"
PIPELINE_DIR="$(cd "$(dirname "$0")" && pwd)"
PACK="${1:-$PIPELINE_DIR/data/out/basemap-nyc.pmtiles}"
NAME="$(basename "$PACK")"
DEST="/sdcard/Android/data/$APP_ID/files/packs"

[[ -f "$PACK" ]] || { echo "No pack at $PACK. Run: make -C pipeline nyc" >&2; exit 1; }

adb shell mkdir -p "$DEST"
# Push under a temp name, then rename, so the app never sees a half-copied file.
adb push "$PACK" "$DEST/$NAME.part"
adb shell mv "$DEST/$NAME.part" "$DEST/$NAME"

local_size="$(wc -c < "$PACK" | tr -d ' ')"
remote_size="$(adb shell stat -c %s "$DEST/$NAME" | tr -d '\r')"
if [[ "$local_size" != "$remote_size" ]]; then
  echo "Size mismatch: local $local_size, phone $remote_size" >&2
  exit 1
fi
echo "Pushed $NAME ($local_size bytes) to $DEST"
