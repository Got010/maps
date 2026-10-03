#!/usr/bin/env bash
# Fetches the bundled offline voice: the sherpa-onnx Android library and a
# Piper voice. Both are too big for git, so CI and release builds run this
# first; it's a no-op once the pinned files are in place.
#
#   third-party/m2/...          sherpa-onnx AAR, as a local Maven repo
#   core/voice/build/voice-assets/voice/   the Piper voice, bundled as assets
#
# Every download is checked against a pinned SHA-256.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VERSION="1.13.8"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
SHERPA_SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
VOICE="vits-piper-en_US-amy-low"
VOICE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$VOICE.tar.bz2"
VOICE_SHA256="c70f5284a09a7fd4ed203b39b2ff51cac1432b422b852eb647b481dade3cf639"

M2_DIR="$ROOT/third-party/m2/com/k2fsa/sherpa-onnx/$SHERPA_VERSION"
AAR="$M2_DIR/sherpa-onnx-$SHERPA_VERSION.aar"
CACHE="$ROOT/third-party/cache"
ASSETS="$ROOT/core/voice/build/voice-assets/voice"

sha256_of() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Downloads $1 to $2 unless a file with SHA-256 $3 is already there.
fetch() {
  local url="$1" out="$2" sha="$3"
  if [[ -f "$out" && "$(sha256_of "$out")" == "$sha" ]]; then return; fi
  echo "==> Downloading ${url##*/}"
  mkdir -p "$(dirname "$out")"
  curl -fL --retry 3 -o "$out.part" "$url"
  local actual
  actual="$(sha256_of "$out.part")"
  if [[ "$actual" != "$sha" ]]; then
    echo "Checksum mismatch for ${url##*/}: expected $sha, got $actual" >&2
    exit 1
  fi
  mv "$out.part" "$out"
}

fetch "$SHERPA_URL" "$AAR" "$SHERPA_SHA256"
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

TARBALL="$CACHE/$VOICE.tar.bz2"
fetch "$VOICE_URL" "$TARBALL" "$VOICE_SHA256"
STAMP="$ASSETS/voice.sha256"
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$VOICE_SHA256" ]]; then
  echo "==> Voice assets up to date"
  exit 0
fi
echo "==> Unpacking $VOICE"
mkdir -p "$ASSETS"
find "$ASSETS" -mindepth 1 -delete
tar -xjf "$TARBALL" -C "$ASSETS" --strip-components=1
# espeak-ng ships pronunciation dictionaries for ~100 languages; the voice
# speaks English only, so keep en_dict and drop the rest (about 17 MB).
find "$ASSETS/espeak-ng-data" -maxdepth 1 -name '*_dict' ! -name 'en_dict' -delete
echo "$VOICE_SHA256" > "$STAMP"
du -sh "$ASSETS"
