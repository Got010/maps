#!/usr/bin/env bash
# Download the Noto Sans glyph ranges the map styles use into styles/glyphs.
# The app bundles these as assets so labels render with no network.
# Source: protomaps/basemaps-assets (Noto Sans, SIL Open Font License 1.1).
set -euo pipefail

BASE_URL="https://raw.githubusercontent.com/protomaps/basemaps-assets/main/fonts"
OUT_DIR="$(cd "$(dirname "$0")/../.." && pwd)/styles/glyphs"

# Local names have no spaces so asset:// glyph URLs need no percent-encoding.
declare -A FONTS=(
  ["NotoSans-Regular"]="Noto Sans Regular"
  ["NotoSans-Medium"]="Noto Sans Medium"
  ["NotoSans-Italic"]="Noto Sans Italic"
)
# Latin, Latin Extended, Greek, Cyrillic, general punctuation, letterlike symbols.
RANGES=(0-255 256-511 512-767 768-1023 1024-1279 8192-8447 8448-8703)

for name in "${!FONTS[@]}"; do
  upstream="${FONTS[$name]}"
  mkdir -p "$OUT_DIR/$name"
  for range in "${RANGES[@]}"; do
    dest="$OUT_DIR/$name/$range.pbf"
    [[ -s "$dest" ]] && continue
    curl -fsSL --retry 3 -o "$dest.tmp" "$BASE_URL/${upstream// /%20}/$range.pbf"
    mv "$dest.tmp" "$dest"
  done
done
echo "Glyphs in $OUT_DIR"
