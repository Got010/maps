#!/usr/bin/env bash
# Fetches the bundled offline voice: the sherpa-onnx Android library and a
# Piper voice. Both are too big for git, so CI and release builds run this
# first; it's a no-op once the pinned files are in place.
#
#   third-party/m2/...          sherpa-onnx AAR, as a local Maven repo
#   core/voice/build/voice-assets/piper/   the Piper voices, bundled as assets:
#     espeak-ng-data/   pronunciation data, shared by every voice
#     <voice>/          one voice's model.onnx, tokens.txt and voice.sha256
#
# Every download is checked against a pinned SHA-256.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHERPA_VERSION="1.13.8"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
SHERPA_SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
# The bundled Piper voices: <asset dir>|<sherpa-onnx tts-models name>|<SHA-256>.
# Each adds about 58 MB to the APK (the .onnx barely compresses). Licences:
# amy: see the Piper model card; norman: public domain (LibriVox recordings).
VOICES=(
  "amy|vits-piper-en_US-amy-low|c70f5284a09a7fd4ed203b39b2ff51cac1432b422b852eb647b481dade3cf639"
  "norman|vits-piper-en_US-norman-medium|1f32065d480abe9abc7c7f91442125d0b34c1cc065d1e600466cac408eabf3b8"
)
VOICE_BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

M2_DIR="$ROOT/third-party/m2/com/k2fsa/sherpa-onnx/$SHERPA_VERSION"
AAR="$M2_DIR/sherpa-onnx-$SHERPA_VERSION.aar"
CACHE="$ROOT/third-party/cache"
ASSETS="$ROOT/core/voice/build/voice-assets/piper"

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

# One stamp for the whole set, so changing the list re-unpacks everything.
WANTED="$(printf '%s\n' "${VOICES[@]}" | sha256sum | cut -d' ' -f1)"
STAMP="$ASSETS/set.sha256"
for entry in "${VOICES[@]}"; do
  IFS='|' read -r _ name sha <<<"$entry"
  fetch "$VOICE_BASE_URL/$name.tar.bz2" "$CACHE/$name.tar.bz2" "$sha"
done
if [[ -f "$STAMP" && "$(cat "$STAMP")" == "$WANTED" ]]; then
  echo "==> Voice assets up to date"
  exit 0
fi
# The single-voice layout of earlier builds.
rm -rf "$ROOT/core/voice/build/voice-assets/voice"
mkdir -p "$ASSETS"
find "$ASSETS" -mindepth 1 -delete
for entry in "${VOICES[@]}"; do
  IFS='|' read -r dir name sha <<<"$entry"
  echo "==> Unpacking $name"
  work="$(mktemp -d)"
  tar -xjf "$CACHE/$name.tar.bz2" -C "$work" --strip-components=1
  mkdir -p "$ASSETS/$dir"
  mv "$work"/*.onnx "$ASSETS/$dir/model.onnx"
  mv "$work/tokens.txt" "$ASSETS/$dir/"
  echo "$sha" > "$ASSETS/$dir/voice.sha256"
  if [[ ! -d "$ASSETS/espeak-ng-data" ]]; then
    # espeak-ng ships pronunciation dictionaries for ~100 languages; the
    # voices speak English only, so keep en_dict and drop the rest (~17 MB).
    mv "$work/espeak-ng-data" "$ASSETS/"
    find "$ASSETS/espeak-ng-data" -maxdepth 1 -name '*_dict' ! -name 'en_dict' -delete
    echo "$sha" > "$ASSETS/espeak.sha256"
  fi
  rm -rf "$work"
done
echo "$WANTED" > "$STAMP"
du -sh "$ASSETS"/*
