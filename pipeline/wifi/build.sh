#!/usr/bin/env bash
# Build the Free Wi-Fi pack pipeline/data/out/wifi-us.sqlite
#
# One pack for every state the maps cover: the Geofabrik extracts named in the "sources" of pipeline/regions.json
# (the "all" region's list is the union), so a state added to the maps brings its Wi-Fi spots with it. Each
# extract is downloaded and verified (fetch_source), cut by `osmium tags-filter` to the few objects that can be
# Wi-Fi spots, and, with FREE_SOURCES=1, deleted: the extracts together are several GB, too much for a runner
# next to its Docker images, the filtered files are small. The filtered files are then merged, reduced to one
# version of each object (`osmium time-filter`, as clip_extract does), and build_wifi.py classifies them into
# the pack in the same pinned Python container and from the same hash-locked pip requirements as the search
# pack. What counts as a spot, the tiers and the chains are in build_wifi.py and chains.json (see README.md).
#
# What the filter keeps (ways and relations bring their nodes, so centroids can be computed; osmium's
# --omit-referenced is NOT used):
#   internet_access=wlan,yes,wifi      tiers 1 and 2: Wi-Fi tagged on anything
#   brand:wikidata=<chains.json QIDs>  tier 3: the chains in the config, by QID (a new QID in chains.json
#                                      changes the expression and so rebuilds the filtered files)
#   amenity=library, tourism=hotel,motel, highway=rest_area       tier 4
#   information=office,visitor_centre  tier 4: welcome centers and visitor centers (build_wifi.py then keeps
#                                      the offices, and the visitor centers of the National Park Service)
#
# osmium runs in one container for the whole build (the pinned OSMIUM_IMAGE, osmium-tool installed once, a
# `docker exec` per step), stopped when the script ends.
#
# Optional settings (environment variables):
#   WIFI_MAX_MB    fail if the pack is bigger (default 60)
#   FREE_SOURCES   1: delete each extract once it is filtered (release.yml and wifi.yml set it), so at most
#                  one extract is on disk at a time
#   WIFI_SOURCES   space-separated Geofabrik names (district-of-columbia): build from these extracts only, not
#                  from regions.json's list. For a quick smoke build (data.yml builds DC so); such a pack covers
#                  only those states and must not be shipped.
#   WIFI_ALLOW_NO_RTREE  1: accept a pack without the wifi_rtree table (build_wifi.py warns when this SQLite has
#                  no R-tree module). Default: fail, since every published pack should carry it.
#
# Usage: build.sh us      (or: make -C pipeline wifi-us)
set -euo pipefail

REGION="${1:?usage: build.sh us}"
[[ "$REGION" == us ]] || { echo "Unknown Wi-Fi region '$REGION'. There is one pack, wifi-us (every state in pipeline/regions.json)." >&2; exit 2; }
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12-slim@sha256:02108f5d322dd89f1c9e552442c25acb0543dfdbc455693a5599624f20d9155d}"
WIFI_MAX_MB="${WIFI_MAX_MB:-60}"
[[ "$WIFI_MAX_MB" =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "WIFI_MAX_MB must be a number of megabytes, not '$WIFI_MAX_MB'." >&2; exit 2; }
# pyosmium and what it needs, locked by hash in the file ci.yml installs for the unit tests (see search/build.sh).
PIP_LOCK="$(cd "$PIPELINE_DIR/.." && pwd)/.github/pipeline-requirements.txt"
[[ -f "$PIP_LOCK" ]] || { echo "Missing $PIP_LOCK (the pinned pip requirements)." >&2; exit 1; }
CHAINS="$PIPELINE_DIR/wifi/chains.json"
THIS_SCRIPT="${BASH_SOURCE[0]}"
OUTPUT="wifi-$REGION.sqlite"

# The chains' QIDs, from the config (build_wifi.py checks the file and every QID's shape; it stops the build
# on a malformed one).
QIDS="$(python3 "$PIPELINE_DIR/wifi/build_wifi.py" --chains "$CHAINS" --print-qids)"
FILTERS="nwr/internet_access=wlan,yes,wifi nwr/brand:wikidata=$QIDS nwr/amenity=library nwr/tourism=hotel,motel nwr/highway=rest_area nwr/information=office,visitor_centre"

SOURCES=()
if [[ -n "${WIFI_SOURCES:-}" ]]; then
  read -r -a SOURCES <<< "$WIFI_SOURCES"
  for source in "${SOURCES[@]}"; do
    is_known_source "$source" || { echo "Unknown source '$source' in WIFI_SOURCES. Known: Geofabrik's US state names (district-of-columbia, new-york, ...)." >&2; exit 2; }
  done
  echo "==> WIFI_SOURCES is set: the pack covers only ${SOURCES[*]} and is for testing"
else
  while IFS= read -r source; do
    SOURCES+=("$source")
  done < <(all_sources)
fi
if (( ${#SOURCES[@]} == 0 )); then
  echo "No extracts to build from: pipeline/regions.json names no sources (or WIFI_SOURCES is blank)." >&2
  exit 2
fi
echo "==> ${#SOURCES[@]} extracts: ${SOURCES[*]}"

# One osmium container for every step, started when the first one needs it (a rerun that reuses everything
# starts none) and stopped on exit. Its image is the same pinned OSMIUM_IMAGE osmium_run uses.
OSMIUM_CONTAINER=""
stop_osmium() {
  if [[ -n "$OSMIUM_CONTAINER" ]]; then
    docker stop "$OSMIUM_CONTAINER" >/dev/null 2>&1 || true
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
  filtered="$DATA_DIR/tmp/wifi-$source.osm.pbf"
  stamp="$DATA_DIR/tmp/wifi-$source.timestamp"
  FILTERED+=("$filtered")
  # Reused while the extract, the config and this script are older than it (a rerun on a machine that keeps data/tmp).
  if fresh_against "$filtered" "$extract" "$CHAINS" "$THIS_SCRIPT" && fresh_against "$stamp" "$extract"; then
    echo "==> Reusing $(basename "$filtered")"
  else
    echo "==> Filtering $base"
    # The replication timestamp is read from the extract itself: it is the date of the data in the pack.
    osmium_step "osmium fileinfo -g header.option.osmosis_replication_timestamp /data/sources/$base > /data/tmp/wifi-$source.timestamp
      osmium tags-filter --overwrite -o /data/tmp/wifi-$source.part.osm.pbf /data/sources/$base $FILTERS
      mv /data/tmp/wifi-$source.part.osm.pbf /data/tmp/wifi-$source.osm.pbf" \
      "/data/tmp/wifi-$source.timestamp" "/data/tmp/wifi-$source.osm.pbf"
  fi
  if [[ ! -s "$stamp" ]]; then
    echo "$base has no osmosis_replication_timestamp in its header; the pack's extract date would be a guess." >&2
    exit 1
  fi
  if [[ "${FREE_SOURCES:-}" == 1 ]]; then
    rm -f "$extract" "$extract.md5"
  fi
done

MERGED="$DATA_DIR/tmp/wifi-$REGION.osm.pbf"
# The states the merged file holds, so a file merged from another set (WIFI_SOURCES) is never reused.
SOURCES_STAMP="$DATA_DIR/tmp/wifi-$REGION.sources"
if fresh_against "$MERGED" "${FILTERED[@]}" "$CHAINS" "$THIS_SCRIPT" "$REGIONS_JSON" \
  && [[ "$(cat "$SOURCES_STAMP" 2>/dev/null)" == "${SOURCES[*]}" ]]; then
  echo "==> Reusing $(basename "$MERGED")"
else
  echo "==> Merging ${#FILTERED[@]} filtered extracts -> $(basename "$MERGED")"
  merge_inputs=""
  for filtered in "${FILTERED[@]}"; do
    merge_inputs+=" /data/tmp/$(basename "$filtered")"
  done
  # merge keeps both versions of an object two extracts carry at different times; time-filter keeps the newest.
  osmium_step "osmium merge --overwrite -o /data/tmp/wifi-$REGION.merged.osm.pbf$merge_inputs
    osmium time-filter --overwrite -o /data/tmp/wifi-$REGION.part.osm.pbf /data/tmp/wifi-$REGION.merged.osm.pbf
    mv /data/tmp/wifi-$REGION.part.osm.pbf /data/tmp/wifi-$REGION.osm.pbf
    rm -f /data/tmp/wifi-$REGION.merged.osm.pbf" "/data/tmp/wifi-$REGION.osm.pbf"
  echo "${SOURCES[*]}" > "$SOURCES_STAMP"
fi

# The extract dates, read from the headers above, as build_wifi.py's --sources.
python3 - "$DATA_DIR/tmp" "${SOURCES[@]}" > "$DATA_DIR/tmp/wifi-sources.json" <<'PY'
import json, sys
tmp = sys.argv[1]
print(json.dumps([{"name": n, "timestamp": open(f"{tmp}/wifi-{n}.timestamp").read().strip()} for n in sys.argv[2:]]))
PY

LOG="$DATA_DIR/tmp/wifi-$REGION.log"
echo "==> Classifying -> $OUTPUT"
docker run --rm \
  -v "$DATA_DIR:/data" \
  -v "$PIPELINE_DIR/wifi:/wifi:ro" \
  -v "$PIP_LOCK:/requirements.txt:ro" \
  "$PYTHON_IMAGE" bash -c "
    set -e
    # pyosmium's wheel links against libexpat, which the slim image lacks.
    apt-get update -qq >/dev/null && apt-get install -y -qq libexpat1 >/dev/null
    pip install -q --root-user-action=ignore --require-hashes --only-binary :all: -r /requirements.txt
    python -B /wifi/build_wifi.py /data/tmp/wifi-$REGION.osm.pbf /data/out/$OUTPUT --chains /wifi/chains.json --max-mb $WIFI_MAX_MB --sources /data/tmp/wifi-sources.json
    chown $(id -u):$(id -g) /data/out/$OUTPUT
  " 2>&1 | tee "$LOG"

# Every published pack carries the R-tree; build_wifi.py only warns when this SQLite lacks the module.
if ! python3 - "$DATA_DIR/out/$OUTPUT" <<'PY'
import sqlite3, sys
db = sqlite3.connect(sys.argv[1])
sys.exit(0 if db.execute("SELECT count(*) FROM sqlite_master WHERE name = 'wifi_rtree'").fetchone()[0] else 1)
PY
then
  if [[ "${WIFI_ALLOW_NO_RTREE:-}" == 1 ]]; then
    echo "::warning::$OUTPUT has no wifi_rtree table (WIFI_ALLOW_NO_RTREE=1)"
  else
    echo "$OUTPUT has no wifi_rtree table: the Python in the container has no R-tree module. Set WIFI_ALLOW_NO_RTREE=1 to accept that." >&2
    exit 1
  fi
fi

report_pack "$DATA_DIR/out/$OUTPUT"
