# shellcheck shell=bash
# Shared helpers for the pack builders. Source it; don't run it.
#
#   source "$(dirname "$0")/../lib/common.sh"
#   region_bounds nyc        -> sets BOUNDS (minlon,minlat,maxlon,maxlat)
#   fetch_extract            -> sets EXTRACT, the verified Geofabrik NY extract
#   clip_extract nyc         -> sets CLIPPED (host path) and CLIPPED_IN_DOCKER
#                               (the same file under the /data mount)
#   report_pack <file>       -> prints size and SHA-256
#
# Regions come from pipeline/regions.json: five basemap regions and "nys",
# the whole state, used for the routing and search packs.

PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${DATA_DIR:-$PIPELINE_DIR/data}"
EXTRACT_URL="https://download.geofabrik.de/north-america/us/new-york-latest.osm.pbf"
OSMIUM_IMAGE="${OSMIUM_IMAGE:-debian:bookworm-slim}"
REGIONS_JSON="$PIPELINE_DIR/regions.json"

mkdir -p "$DATA_DIR/sources" "$DATA_DIR/out" "$DATA_DIR/tmp"

# The region ids in regions.json, one per line; basemap regions only with -b.
list_regions() {
  python3 - "$REGIONS_JSON" "${1:-}" <<'PY'
import json, sys
regions = json.load(open(sys.argv[1]))["regions"]
for name, region in regions.items():
    if sys.argv[2] == "-b" and not region["basemap"]:
        continue
    print(name)
PY
}

region_bounds() {
  BOUNDS="$(python3 - "$REGIONS_JSON" "$1" <<'PY'
import json, sys
regions = json.load(open(sys.argv[1]))["regions"]
region = regions.get(sys.argv[2])
if region is None:
    sys.exit(2)
print(",".join(str(v) for v in region["bounds"]))
PY
  )" || { echo "Unknown region '$1'. See pipeline/regions.json." >&2; exit 2; }
}

md5_of() {
  if command -v md5sum >/dev/null; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi
}

sha256_of() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

fetch_extract() {
  EXTRACT="$DATA_DIR/sources/new-york-latest.osm.pbf"
  echo "==> Fetching $EXTRACT_URL"
  # -z only re-downloads when Geofabrik has a newer extract.
  curl -fL --retry 3 -z "$EXTRACT" -o "$EXTRACT" "$EXTRACT_URL"
  curl -fsSL --retry 3 -o "$EXTRACT.md5" "$EXTRACT_URL.md5"
  local expected actual
  expected="$(cut -d' ' -f1 "$EXTRACT.md5")"
  actual="$(md5_of "$EXTRACT")"
  if [[ "$expected" != "$actual" ]]; then
    echo "Checksum mismatch for $EXTRACT (expected $expected, got $actual)." >&2
    echo "Delete it and rerun; Geofabrik may have published mid-download." >&2
    exit 1
  fi
  echo "    md5 ok"
}

# Cuts the extract to the region's bounding box with osmium, in Docker so no
# local install is needed. Ways crossing the edge are kept whole
# (complete_ways), so roads at the border still route. Reused until the
# extract changes. The statewide region (nys) is the extract itself.
clip_extract() {
  local region="$1"
  region_bounds "$region"
  fetch_extract
  if [[ "$region" == nys ]]; then
    CLIPPED="$EXTRACT"
    CLIPPED_IN_DOCKER="/data/sources/$(basename "$EXTRACT")"
    echo "==> Statewide: using the whole extract"
    return
  fi
  CLIPPED="$DATA_DIR/tmp/$region.osm.pbf"
  # shellcheck disable=SC2034  # read by the build scripts that source this file
  CLIPPED_IN_DOCKER="/data/tmp/$region.osm.pbf"
  if [[ -f "$CLIPPED" && "$CLIPPED" -nt "$EXTRACT" ]]; then
    echo "==> Reusing $CLIPPED"
    return
  fi
  echo "==> Clipping to $BOUNDS -> $(basename "$CLIPPED")"
  docker run --rm -v "$DATA_DIR:/data" "$OSMIUM_IMAGE" bash -c "
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq osmium-tool >/dev/null
    osmium extract --strategy=complete_ways --bbox=$BOUNDS --overwrite \
      -o /data/tmp/$region.osm.pbf /data/sources/$(basename "$EXTRACT")
    chown $(id -u):$(id -g) /data/tmp/$region.osm.pbf
  "
}

report_pack() {
  echo "==> Done: $1"
  echo "    size   $(du -h "$1" | cut -f1)"
  echo "    sha256 $(sha256_of "$1")"
}
