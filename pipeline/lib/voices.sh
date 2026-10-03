# shellcheck shell=bash
# shellcheck disable=SC2034  # the variables set here are read by the scripts that source it
# The Piper voices Southbound can speak with, pinned by SHA-256. Source it.
#
#   voice_model norman   -> sets VOICE_MODEL (sherpa-onnx tts-models name),
#                           VOICE_URL and VOICE_SHA256
#   fetch_pinned <url> <out> <sha256>
#                        -> downloads unless <out> already has that SHA-256
#
# amy is bundled in the APK (scripts/fetch-voice.sh) as the voice that always
# works; the others ship as voice-<id>.zip packs (pipeline/voices/build.sh)
# that the app downloads only for whoever picks them.

VOICE_BASE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

voice_model() {
  case "$1" in
    # US English, female, low quality: small and clear. Bundled.
    amy)
      VOICE_MODEL="vits-piper-en_US-amy-low"
      VOICE_SHA256="c70f5284a09a7fd4ed203b39b2ff51cac1432b422b852eb647b481dade3cf639" ;;
    # US English, male, medium quality, trained from public domain LibriVox recordings.
    norman)
      VOICE_MODEL="vits-piper-en_US-norman-medium"
      VOICE_SHA256="1f32065d480abe9abc7c7f91442125d0b34c1cc065d1e600466cac408eabf3b8" ;;
    *)
      echo "Unknown voice '$1'." >&2
      return 2 ;;
  esac
  VOICE_URL="$VOICE_BASE_URL/$VOICE_MODEL.tar.bz2"
}

pinned_sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

fetch_pinned() {
  local url="$1" out="$2" sha="$3"
  if [[ -f "$out" && "$(pinned_sha256 "$out")" == "$sha" ]]; then return; fi
  echo "==> Downloading ${url##*/}"
  mkdir -p "$(dirname "$out")"
  curl -fL --retry 3 -o "$out.part" "$url"
  local actual
  actual="$(pinned_sha256 "$out.part")"
  if [[ "$actual" != "$sha" ]]; then
    echo "Checksum mismatch for ${url##*/}: expected $sha, got $actual" >&2
    exit 1
  fi
  mv "$out.part" "$out"
}
