#!/usr/bin/env bash
# Build the basemap pack pipeline/data/out/basemap-<region>.pmtiles
#
# Downloads the Geofabrik New York extract (verified against its .md5), then
# runs Planetiler in Docker with the OpenMapTiles profile. Water polygons and
# Natural Earth data are fetched by Planetiler's --download on the first run
# and cached in pipeline/data/sources.
#
# Usage: build.sh nyc
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
PIPELINE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${DATA_DIR:-$PIPELINE_DIR/data}"
PLANETILER_IMAGE="${PLANETILER_IMAGE:-ghcr.io/onthegomap/planetiler:0.10.2}"
PLANETILER_HEAP="${PLANETILER_HEAP:-4g}"
EXTRACT_URL="https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf"
EXTRACT="$DATA_DIR/sources/new-york-latest.osm.pbf"

case "$REGION" in
  # Bounding box of the five boroughs. The extract holds only NY State data,
  # so the box adds no New Jersey features, just a sliver of Nassau County.
  nyc) BOUNDS="-74.2591,40.4774,-73.7004,40.9176" ;;
  *) echo "Unknown region '$REGION'. Statewide regions arrive in Phase 3." >&2; exit 2 ;;
esac

md5_of() {
  if command -v md5sum >/dev/null; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi
}

mkdir -p "$DATA_DIR/sources" "$DATA_DIR/out" "$DATA_DIR/tmp"

echo "==> Fetching $EXTRACT_URL"
# -z only re-downloads when Geofabrik has a newer extract.
curl -fL --retry 3 -z "$EXTRACT" -o "$EXTRACT" "$EXTRACT_URL"
curl -fsSL --retry 3 -o "$EXTRACT.md5" "$EXTRACT_URL.md5"
expected="$(cut -d' ' -f1 "$EXTRACT.md5")"
actual="$(md5_of "$EXTRACT")"
if [[ "$expected" != "$actual" ]]; then
  echo "Checksum mismatch for $EXTRACT (expected $expected, got $actual)." >&2
  echo "Delete it and rerun; Geofabrik may have published mid-download." >&2
  exit 1
fi
echo "    md5 ok"

OUTPUT="basemap-$REGION.pmtiles"
echo "==> Planetiler ($PLANETILER_IMAGE) -> $OUTPUT"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e JAVA_TOOL_OPTIONS="-Xmx$PLANETILER_HEAP" \
  -v "$DATA_DIR:/data" \
  "$PLANETILER_IMAGE" \
  --osm_path="/data/sources/$(basename "$EXTRACT")" \
  --download \
  --download_dir=/data/sources \
  --tmpdir=/data/tmp \
  --bounds="$BOUNDS" \
  --languages=en \
  --output="/data/out/$OUTPUT" \
  --force

out="$DATA_DIR/out/$OUTPUT"
if command -v sha256sum >/dev/null; then sha="$(sha256sum "$out" | cut -d' ' -f1)"
else sha="$(shasum -a 256 "$out" | cut -d' ' -f1)"; fi
echo "==> Done: $out"
echo "    size   $(du -h "$out" | cut -f1)"
echo "    sha256 $sha"
