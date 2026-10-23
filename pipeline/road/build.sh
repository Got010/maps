#!/usr/bin/env bash
# Build the Road pack pipeline/data/out/road-us.sqlite
#
# One pack for every state the maps cover: the Geofabrik extracts named in the "sources" of pipeline/regions.json
# (the "all" region's list is the union), so a state added to the maps brings its road spots with it. Built the
# way the Free Wi-Fi pack is (pipeline/wifi/build.sh, which this copies; nothing is shared with it at run time).
# Each extract is downloaded and verified (fetch_source), cut by `osmium tags-filter` to the few objects that can
# be road spots, and, with FREE_SOURCES=1, deleted: the extracts together are several GB, too much for a runner
# next to its Docker images, the filtered files are smaller. The filtered files are then merged, reduced to one
# version of each object (`osmium time-filter`), and build_road.py classifies them into the pack in the same
# pinned Python container and from the same hash-locked pip requirements as the search pack. What counts as a
# spot and the categories are in build_road.py and README.md; the truck stop brands are in chains.json.
#
# What the filter keeps (ways and relations bring their nodes, so centroids can be computed; osmium's
# --omit-referenced is NOT used). It can only name tag values, never combine tags, so it is wider than the
# classifier: build_road.py then applies the rest (amenity=doctors only with healthcare=clinic; boundary=
# protected_area only with protect_class 2 to 6; leisure=park only for state parks; hgv=yes and the brands):
#   tourism=camp_site,caravan_site                                         camp, rv
#   highway=rest_area,services                                             rest (and truck)
#   amenity=sanitary_dump_station,drinking_water,toilets,shower            dump, water, toilet, shower
#   amenity=fuel                                                           fuel (and truck)
#   amenity=library,pharmacy,hospital,clinic,doctors                       library, pharmacy, hospital, clinic
#   shop=laundry,supermarket                                               laundry, grocery
#   boundary=national_park,protected_area and leisure=park                 park
#
# osmium runs in one container for the whole build (the pinned OSMIUM_IMAGE, osmium-tool installed once, a
# `docker exec` per step), stopped when the script ends.
#
# Optional settings (environment variables):
#   ROAD_MAX_MB    fail if the pack is bigger (default 64)
#   FREE_SOURCES   1: delete each extract once it is filtered (release.yml and road.yml set it), so at most
#                  one extract is on disk at a time
#   ROAD_SOURCES   space-separated Geofabrik names (district-of-columbia): build from these extracts only, not
#                  from regions.json's list (blank is the same as unset). For a quick smoke build (data.yml builds DC so); such a pack covers
#                  only those states and must not be shipped.
#   ROAD_ALLOW_NO_RTREE  1: accept a pack without the road_rtree table (build_road.py warns when this SQLite has
#                  no R-tree module). Default: fail, since every published pack should carry it.
#
# Usage: build.sh us      (or: make -C pipeline road-us)
set -euo pipefail

REGION="${1:?usage: build.sh us}"
[[ "$REGION" == us ]] || { echo "Unknown Road region '$REGION'. There is one pack, road-us (every state in pipeline/regions.json)." >&2; exit 2; }
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12-slim@sha256:02108f5d322dd89f1c9e552442c25acb0543dfdbc455693a5599624f20d9155d}"
ROAD_MAX_MB="${ROAD_MAX_MB:-64}"
[[ "$ROAD_MAX_MB" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "ROAD_MAX_MB must be a number of megabytes, not '$ROAD_MAX_MB'." >&2; exit 2; }
# pyosmium and what it needs, locked by hash in the file ci.yml installs for the unit tests (see search/build.sh).
PIP_LOCK="$(cd "$PIPELINE_DIR/.." && pwd)/.github/pipeline-requirements.txt"
[[ -f "$PIP_LOCK" ]] || { echo "Missing $PIP_LOCK (the pinned pip requirements)." >&2; exit 1; }
CHAINS="$PIPELINE_DIR/road/chains.json"
THIS_SCRIPT="${BASH_SOURCE[0]}"
OUTPUT="road-$REGION.sqlite"

# The truck stop brands, from the config (build_road.py checks the file; it stops the build on a malformed one,
# before anything is downloaded).
python3 "$PIPELINE_DIR/road/build_road.py" --chains "$CHAINS" --check-chains
FILTERS="nwr/tourism=camp_site,caravan_site nwr/highway=rest_area,services nwr/amenity=sanitary_dump_station,drinking_water,toilets,shower,fuel,library,pharmacy,hospital,clinic,doctors nwr/shop=laundry,supermarket nwr/boundary=national_park,protected_area nwr/leisure=park"

SOURCES=()
# A blank ROAD_SOURCES is the same as unset (release.yml sets it to "" so nothing can narrow a published pack).
read -r -a REQUESTED <<< "${ROAD_SOURCES:-}"
if (( ${#REQUESTED[@]} > 0 )); then
  SOURCES=("${REQUESTED[@]}")
  for source in "${SOURCES[@]}"; do
    is_known_source "$source" || { echo "Unknown source '$source' in ROAD_SOURCES. Known: Geofabrik's US state names (district-of-columbia, new-york, ...)." >&2; exit 2; }
  done
  echo "==> ROAD_SOURCES is set: the pack covers only ${SOURCES[*]} and is for testing"
else
  while IFS= read -r source; do
    SOURCES+=("$source")
  done < <(all_sources)
fi
if (( ${#SOURCES[@]} == 0 )); then
  echo "No extracts to build from: pipeline/regions.json names no sources." >&2
  exit 2
fi
echo "==> ${#SOURCES[@]} extracts: ${SOURCES[*]}"

# One osmium container for every step, started when the first one needs it (a rerun that reuses everything
# starts none) and stopped on exit. Its image is the same pinned OSMIUM_IMAGE osmium_run uses.
OSMIUM_CONTAINER=""
stop_osmium() {
  if [[ -n "$OSMIUM_CONTAINER" ]]; then
    docker stop -t 0 "$OSMIUM_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap stop_osmium EXIT
# Like osmium_run: $1 is a script using /data paths, the rest are the files it writes, handed back to the caller.
osmium_step() {
  local script="$1"
  shift
  if [[ -z "$OSMIUM_CONTAINER" ]]; then
    OSMIUM_CONTAINER="$(docker run -d --rm -v "$DATA_DIR:/data" "$OSMIUM_IMAGE" sleep infinity)"
    docker exec "$OSMIUM_CONTAINER" bash -c "apt-get update -qq >/dev/null && apt-get install -y -qq osmium-tool >/dev/null"
  fi
  docker exec "$OSMIUM_CONTAINER" bash -c "
    set -e
    $script
    chown $(id -u):$(id -g) $*
  "
}

FILTERED=()
for source in "${SOURCES[@]}"; do
  fetch_source "$source"
  extract="$SOURCE_FILE"
  base="$(basename "$extract")"
  filtered="$DATA_DIR/tmp/road-$source.osm.pbf"
  stamp="$DATA_DIR/tmp/road-$source.timestamp"
  FILTERED+=("$filtered")
  # Reused while the extract and this script are older than it (a rerun on a machine that keeps data/tmp).
  if fresh_against "$filtered" "$extract" "$THIS_SCRIPT" && fresh_against "$stamp" "$extract"; then
    echo "==> Reusing $(basename "$filtered")"
  else
    echo "==> Filtering $base"
    # The replication timestamp is read from the extract itself: it is the date of the data in the pack.
    osmium_step "osmium fileinfo -g header.option.osmosis_replication_timestamp /data/sources/$base > /data/tmp/road-$source.timestamp
      osmium tags-filter --overwrite -o /data/tmp/road-$source.part.osm.pbf /data/sources/$base $FILTERS
      mv /data/tmp/road-$source.part.osm.pbf /data/tmp/road-$source.osm.pbf" \
      "/data/tmp/road-$source.timestamp" "/data/tmp/road-$source.osm.pbf"
  fi
  if [[ ! -s "$stamp" ]]; then
    echo "$base has no osmosis_replication_timestamp in its header; the pack's extract date would be a guess." >&2
    exit 1
  fi
  if [[ "${FREE_SOURCES:-}" == 1 ]]; then
    rm -f "$extract" "$extract.md5"
  fi
done

MERGED="$DATA_DIR/tmp/road-$REGION.osm.pbf"
# The states the merged file holds, so a file merged from another set (ROAD_SOURCES) is never reused.
SOURCES_STAMP="$DATA_DIR/tmp/road-$REGION.sources"
if fresh_against "$MERGED" "${FILTERED[@]}" "$THIS_SCRIPT" "$REGIONS_JSON" \
  && [[ "$(cat "$SOURCES_STAMP" 2>/dev/null)" == "${SOURCES[*]}" ]]; then
  echo "==> Reusing $(basename "$MERGED")"
else
  echo "==> Merging ${#FILTERED[@]} filtered extracts -> $(basename "$MERGED")"
  merge_inputs=""
  for filtered in "${FILTERED[@]}"; do
    merge_inputs+=" /data/tmp/$(basename "$filtered")"
  done
  # merge keeps both versions of an object two extracts carry at different times; time-filter keeps the newest.
  osmium_step "osmium merge --overwrite -o /data/tmp/road-$REGION.merged.osm.pbf$merge_inputs
    osmium time-filter --overwrite -o /data/tmp/road-$REGION.part.osm.pbf /data/tmp/road-$REGION.merged.osm.pbf
    mv /data/tmp/road-$REGION.part.osm.pbf /data/tmp/road-$REGION.osm.pbf
    rm -f /data/tmp/road-$REGION.merged.osm.pbf" "/data/tmp/road-$REGION.osm.pbf"
  echo "${SOURCES[*]}" > "$SOURCES_STAMP"
fi

# The extract dates, read from the headers above, as build_road.py's --sources.
python3 - "$DATA_DIR/tmp" "${SOURCES[@]}" > "$DATA_DIR/tmp/road-sources.json" <<'PY'
import json, sys
tmp = sys.argv[1]
print(json.dumps([{"name": n, "timestamp": open(f"{tmp}/road-{n}.timestamp").read().strip()} for n in sys.argv[2:]]))
PY

LOG="$DATA_DIR/tmp/road-$REGION.log"
echo "==> Classifying -> $OUTPUT"
docker run --rm \
  -v "$DATA_DIR:/data" \
  -v "$PIPELINE_DIR/road:/road:ro" \
  -v "$PIP_LOCK:/requirements.txt:ro" \
  "$PYTHON_IMAGE" bash -c "
    set -e
    # pyosmium's wheel links against libexpat, which the slim image lacks.
    apt-get update -qq >/dev/null && apt-get install -y -qq libexpat1 >/dev/null
    pip install -q --root-user-action=ignore --require-hashes --only-binary :all: -r /requirements.txt
    python -B /road/build_road.py /data/tmp/road-$REGION.osm.pbf /data/out/$OUTPUT --chains /road/chains.json --max-mb $ROAD_MAX_MB --sources /data/tmp/road-sources.json
    chown $(id -u):$(id -g) /data/out/$OUTPUT
  " 2>&1 | tee "$LOG"

# Every published pack carries the R-tree; build_road.py only warns when this SQLite lacks the module.
if ! python3 - "$DATA_DIR/out/$OUTPUT" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
sys.exit(0 if db.execute("SELECT count(*) FROM sqlite_master WHERE name = 'road_rtree'").fetchone()[0] else 1)
PY
then
  if [[ "${ROAD_ALLOW_NO_RTREE:-}" == 1 ]]; then
    echo "::warning::$OUTPUT has no road_rtree table (ROAD_ALLOW_NO_RTREE=1)"
  else
    echo "$OUTPUT has no road_rtree table: the Python in the container has no R-tree module. Set ROAD_ALLOW_NO_RTREE=1 to accept that." >&2
    rm -f "$DATA_DIR/out/$OUTPUT"
    exit 1
  fi
fi

report_pack "$DATA_DIR/out/$OUTPUT"
