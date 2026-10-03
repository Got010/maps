#!/usr/bin/env bash
# Build a voice pack, pipeline/data/out/voice-<voice>.zip
#
# A downloadable Piper voice for the app's built-in engine: the voice's
# model.onnx, tokens.txt and MODEL_CARD from the pinned sherpa-onnx
# tts-models release, plus voice.sha256 (the source's checksum, which the
# app uses to tell versions apart). The espeak-ng pronunciation data is
# already in the APK with the bundled voice, so it isn't repeated here.
# A ZIP rather than a tar, so the app tells it from the routing pack by its
# first bytes.
#
# Usage: build.sh <voice>      e.g. build.sh norman
set -euo pipefail

VOICE="${1:?usage: build.sh <voice>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
# shellcheck source=pipeline/lib/voices.sh
source "$(dirname "$0")/../lib/voices.sh"

voice_model "$VOICE"
TARBALL="$DATA_DIR/sources/$VOICE_MODEL.tar.bz2"
fetch_pinned "$VOICE_URL" "$TARBALL" "$VOICE_SHA256"

WORK="$DATA_DIR/tmp/voice-$VOICE"
rm -rf "$WORK"
mkdir -p "$WORK/src" "$WORK/pack"
tar -xjf "$TARBALL" -C "$WORK/src" --strip-components=1
mv "$WORK/src"/*.onnx "$WORK/pack/model.onnx"
mv "$WORK/src/tokens.txt" "$WORK/pack/"
if [[ -f "$WORK/src/MODEL_CARD" ]]; then mv "$WORK/src/MODEL_CARD" "$WORK/pack/"; fi
echo "$VOICE_SHA256" > "$WORK/pack/voice.sha256"

OUTPUT="$DATA_DIR/out/voice-$VOICE.zip"
echo "==> Packing $OUTPUT"
rm -f "$OUTPUT"
# Fixed timestamps, so the same voice always makes the same file (and the
# app doesn't download it again after a data rebuild).
python3 - "$WORK/pack" "$OUTPUT" <<'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for name in sorted(os.listdir(src)):
        info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        with open(os.path.join(src, name), "rb") as f:
            z.writestr(info, f.read())
PY
rm -rf "$WORK"
report_pack "$OUTPUT"
