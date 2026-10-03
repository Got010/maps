#!/usr/bin/env bash
# Fetches the bundled offline voice: the sherpa-onnx Android library and a
# Piper voice. Both are too big for git, so CI and release builds run this
# first; it's a no-op once the pinned files are in place.
#
#   third-party/m2/...          sherpa-onnx AAR, as a local Maven repo
#   core/voice/build/voice-assets/piper/   the Piper voice, bundled as assets:
#     espeak-ng-data/   pronunciation data, shared by every voice
#     amy/              the bundled voice's model.onnx, tokens.txt and voice.sha256
#
# Every download is checked against a pinned SHA-256.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VERSION="1.13.8"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
SHERPA_SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
# The bundled Piper voice. Other voices are downloadable packs
# (pipeline/voices/build.sh), so they don't weigh on the APK.
BUNDLED_VOICE="amy"
# shellcheck source=pipeline/lib/voices.sh
source "$ROOT/pipeline/lib/voices.sh"

M2_DIR="$ROOT/third-party/m2/com/k2fsa/sherpa-onnx/$SHERPA_VERSION"
AAR="$M2_DIR/sherpa-onnx-$SHERPA_VERSION.aar"
CACHE="$ROOT/third-party/cache"
ASSETS="$ROOT/core/voice/build/voice-assets/piper"

fetch_pinned "$SHERPA_URL" "$AAR" "$SHERPA_SHA256"
cat > "$M2_DIR/sherpa-onnx-$SHERPA_VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.k2fsa</groupId>
  <artifactId>sherpa-onnx</artifactId>
  <version>$SHERPA_VERSION</version>
  <packaging>aar</packaging>
</project>
POM

voice_model "$BUNDLED_VOICE"
TARBALL="$CACHE/$VOICE_MODEL.tar.bz2"
fetch_pinned "$VOICE_URL" "$TARBALL" "$VOICE_SHA256"
STAMP="$ASSETS/$BUNDLED_VOICE/voice.sha256"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$VOICE_SHA256" && ! -e "$ASSETS/norman" ]]; then
  echo "==> Voice assets up to date"
  exit 0
fi
# Earlier layouts: the single-voice folder, and Norman when it was bundled.
rm -rf "$ROOT/core/voice/build/voice-assets/voice"
mkdir -p "$ASSETS"
find "$ASSETS" -mindepth 1 -delete
echo "==> Unpacking $VOICE_MODEL"
work="$(mktemp -d)"
tar -xjf "$TARBALL" -C "$work" --strip-components=1
mkdir -p "$ASSETS/$BUNDLED_VOICE"
mv "$work"/*.onnx "$ASSETS/$BUNDLED_VOICE/model.onnx"
mv "$work/tokens.txt" "$ASSETS/$BUNDLED_VOICE/"
# espeak-ng ships pronunciation dictionaries for ~100 languages; the voices
# speak English only, so keep en_dict and drop the rest (~17 MB). Voice packs
# use this copy too.
mv "$work/espeak-ng-data" "$ASSETS/"
find "$ASSETS/espeak-ng-data" -maxdepth 1 -name '*_dict' ! -name 'en_dict' -delete
echo "$VOICE_SHA256" > "$ASSETS/espeak.sha256"
echo "$VOICE_SHA256" > "$STAMP"
rm -rf "$work"
du -sh "$ASSETS"/*
