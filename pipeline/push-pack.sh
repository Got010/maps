#!/usr/bin/env bash
# Copy a built pack into the app's private files storage over adb, for testing.
# Needs a debug build of the app installed (run-as only works for debuggable
# apps). Streams the file straight in, so nothing lands on shared storage.
#
# Usage: push-pack.sh [pack-file]   (default: data/out/basemap-nyc.pmtiles)
set -euo pipefail

APP_ID="${APP_ID:-org.nysmaps}"
PIPELINE_DIR="$(cd "$(dirname "$0")" && pwd)"
PACK="${1:-$PIPELINE_DIR/data/out/basemap-nyc.pmtiles}"
NAME="$(basename "$PACK")"

[[ -f "$PACK" ]] || { echo "No pack at $PACK. Run: make -C pipeline nyc" >&2; exit 1; }

adb shell run-as "$APP_ID" mkdir -p files/packs
# Write to a temp name, then rename, so the app never sees a half-copied file.
# adb joins arguments before the phone's shell parses them, so pass one string.
adb exec-in "run-as $APP_ID sh -c 'cat > files/packs/$NAME.part'" < "$PACK"
adb shell run-as "$APP_ID" mv "files/packs/$NAME.part" "files/packs/$NAME"

local_size="$(wc -c < "$PACK" | tr -d ' ')"
remote_size="$(adb shell run-as "$APP_ID" stat -c %s "files/packs/$NAME" | tr -d '\r')"
if [[ "$local_size" != "$remote_size" ]]; then
  echo "Size mismatch: local $local_size, phone $remote_size" >&2
  exit 1
fi
echo "Pushed $NAME ($local_size bytes) to /data/data/$APP_ID/files/packs/"
