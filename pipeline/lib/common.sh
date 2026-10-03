# shellcheck shell=bash
# Shared helpers for the pack builders. Source it; don't run it.
#
#   source "$(dirname "$0")/../lib/common.sh"
#   region_bounds nyc        -> sets BOUNDS (minlon,minlat,maxlon,maxlat)
#   fetch_source new-jersey  -> sets SOURCE_FILE, a verified Geofabrik extract
#   primary_extract njn      -> sets EXTRACT to the region's first source (what
#                               the basemap builder reads)
#   clip_extract nyc         -> sets CLIPPED (host path) and CLIPPED_IN_DOCKER
#                               (the same file under the /data mount)
#   report_pack <file>       -> prints size and SHA-256
#
# Regions come from pipeline/regions.json: the basemap regions and "nys", the
# routing and search region (New York plus northern New Jersey). A region says
# which Geofabrik extracts it is built from in its optional "sources" map
# (see region_sources); the default is the New York extract cut to its bounds.

PIPELINE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DATA_DIR="${DATA_DIR:-$PIPELINE_DIR/data}"
GEOFABRIK_URL="https://download.geofabrik.de/north-america/us"
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

# Downloads a Geofabrik extract by source name (new-york, new-jersey) into
# sources/<name>-latest.osm.pbf and verifies it against its .md5. Sets
# SOURCE_FILE.
fetch_source() {
  local name="$1" url
  case "$name" in
    new-york | new-jersey) url="$GEOFABRIK_URL/$name-latest.osm.pbf" ;;
    *) echo "Unknown source '$name'. Known: new-york, new-jersey." >&2; exit 2 ;;
  esac
  SOURCE_FILE="$DATA_DIR/sources/$name-latest.osm.pbf"
  echo "==> Fetching $url"
  # -z only re-downloads when Geofabrik has a newer extract.
  curl -fL --retry 3 -z "$SOURCE_FILE" -o "$SOURCE_FILE" "$url"
  curl -fsSL --retry 3 -o "$SOURCE_FILE.md5" "$url.md5"
  local expected actual
  expected="$(cut -d' ' -f1 "$SOURCE_FILE.md5")"
  actual="$(md5_of "$SOURCE_FILE")"
  if [[ "$expected" != "$actual" ]]; then
    echo "Checksum mismatch for $SOURCE_FILE (expected $expected, got $actual)." >&2
    echo "Delete it and rerun; Geofabrik may have published mid-download." >&2
    exit 1
  fi
  echo "    md5 ok"
}

# What a region is built from: one "<source> <clip>" line per Geofabrik extract.
# <clip> is the region whose bounds the extract is cut to first, or "-" for the
# whole extract. In regions.json, "sources" maps a source name to null (whole),
# to "self" (this region's bounds) or to another region's id; without it a
# region is the New York extract cut to its own bounds. The statewide region
# (nys) lists New York whole plus New Jersey cut to njn, so routing and search
# cover the same ground as the maps.
region_sources() {
  python3 - "$REGIONS_JSON" "$1" <<'PY'
import json, sys
regions = json.load(open(sys.argv[1]))["regions"]
name = sys.argv[2]
region = regions.get(name)
if region is None:
    sys.exit(2)
for source, clip in region.get("sources", {"new-york": "self"}).items():
    print(source, name if clip == "self" else (clip or "-"))
PY
}

# Sets EXTRACT to the first extract a region is built from, downloaded and
# verified. The basemap builder gives it to Planetiler with --bounds.
primary_extract() {
  local lines source clip
  lines="$(region_sources "$1")" || { echo "Unknown region '$1'. See pipeline/regions.json." >&2; exit 2; }
  read -r source clip <<< "$(head -1 <<< "$lines")"
  fetch_source "$source"
  # shellcheck disable=SC2034  # read by the build scripts that source this file
  EXTRACT="$SOURCE_FILE"
}

# Runs osmium in Docker so no local install is needed. $1 is a shell script
# using /data paths; the rest are the files it writes, handed back to the
# calling user.
osmium_run() {
  local script="$1"
  shift
  docker run --rm -v "$DATA_DIR:/data" "$OSMIUM_IMAGE" bash -c "
    set -e
    apt-get update -qq >/dev/null && apt-get install -y -qq osmium-tool >/dev/null
    $script
    chown $(id -u):$(id -g) $*
  "
}

# True when $1 exists and is newer than every other argument.
fresh_against() {
  local out="$1" input
  shift
  [[ -f "$out" ]] || return 1
  for input in "$@"; do
    [[ "$out" -nt "$input" ]] || return 1
  done
}

# Builds the region's extract and sets CLIPPED (host path) and
# CLIPPED_IN_DOCKER (the same file under the /data mount).
#
# Each source is fetched and, unless the region takes it whole, cut to a
# bounding box with osmium. Ways crossing the edge are kept whole
# (complete_ways), so roads at the border still route. A region with several
# sources (nys) then has them joined with `osmium merge`:
#   - Memory: merge streams its sorted inputs, so it needs little RAM (tens of
#     MB); it is not the step that limits a 7 GB runner.
#   - Disk: the merged file is about the sum of its inputs (New York whole
#     plus northern New Jersey is roughly 0.7 to 1 GB) and sits next to
#     them. Geofabrik extracts are sorted, which merge requires. An object
#     that both extracts carry (a road crossing the state line) is written
#     once when the versions match.
#   - FREE_SOURCES=1 (release.yml sets it) deletes the inputs once the merge
#     is done, since a pack job builds only this region.
# The result is reused until a source changes.
clip_extract() {
  local region="$1" lines source clip i bbox host
  local -a sources=() clips=() inputs=() parts=() outputs=()
  lines="$(region_sources "$region")" || { echo "Unknown region '$region'. See pipeline/regions.json." >&2; exit 2; }
  while read -r source clip; do
    sources+=("$source")
    clips+=("$clip")
  done <<< "$lines"

  for source in "${sources[@]}"; do
    fetch_source "$source"
    inputs+=("$SOURCE_FILE")
  done

  # One source: the result is that extract, whole or cut to a box.
  if (( ${#sources[@]} == 1 )); then
    if [[ "${clips[0]}" == - ]]; then
      CLIPPED="${inputs[0]}"
      CLIPPED_IN_DOCKER="/data/sources/$(basename "$CLIPPED")"
      echo "==> Using the whole ${sources[0]} extract"
      return
    fi
    CLIPPED="$DATA_DIR/tmp/$region.osm.pbf"
    # shellcheck disable=SC2034  # read by the build scripts that source this file
    CLIPPED_IN_DOCKER="/data/tmp/$region.osm.pbf"
    if fresh_against "$CLIPPED" "${inputs[0]}"; then
      echo "==> Reusing $CLIPPED"
      return
    fi
    region_bounds "${clips[0]}"
    echo "==> Clipping to $BOUNDS -> $(basename "$CLIPPED")"
    osmium_run "osmium extract --strategy=complete_ways --bbox=$BOUNDS --overwrite \
      -o $CLIPPED_IN_DOCKER /data/sources/$(basename "${inputs[0]}")" "$CLIPPED_IN_DOCKER"
    return
  fi

  # Several sources: cut each, then merge.
  CLIPPED="$DATA_DIR/tmp/$region.osm.pbf"
  CLIPPED_IN_DOCKER="/data/tmp/$region.osm.pbf"
  if fresh_against "$CLIPPED" "${inputs[@]}"; then
    echo "==> Reusing $CLIPPED"
    return
  fi
  local script="" merge_inputs=""
  for i in "${!sources[@]}"; do
    if [[ "${clips[$i]}" == - ]]; then
      merge_inputs+=" /data/sources/$(basename "${inputs[$i]}")"
      continue
    fi
    region_bounds "${clips[$i]}"
    bbox="$BOUNDS"
    host="tmp/$region.${sources[$i]}.osm.pbf"
    echo "==> Clipping ${sources[$i]} to $bbox -> $(basename "$host")"
    script+="osmium extract --strategy=complete_ways --bbox=$bbox --overwrite -o /data/$host /data/sources/$(basename "${inputs[$i]}")
"
    parts+=("$DATA_DIR/$host")
    outputs+=("/data/$host")
    merge_inputs+=" /data/$host"
  done
  echo "==> Merging ${sources[*]} -> $(basename "$CLIPPED")"
  osmium_run "${script}osmium merge --overwrite -o $CLIPPED_IN_DOCKER$merge_inputs" "$CLIPPED_IN_DOCKER" "${outputs[@]}"
  # The cut copies were only inputs to the merge.
  rm -f "${parts[@]}"
  if [[ "${FREE_SOURCES:-}" == 1 ]]; then
    echo "==> Freeing the source extracts"
    rm -f "${inputs[@]}"
  fi
}

report_pack() {
  echo "==> Done: $1"
  echo "    size   $(du -h "$1" | cut -f1)"
  echo "    sha256 $(sha256_of "$1")"
}
