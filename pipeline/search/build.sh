#!/usr/bin/env bash
# Build the search pack pipeline/data/out/search-<region>.sqlite
#
# Builds the region's extract (New York cut to the region, or for nys the whole
# New York extract merged with northern New Jersey), then indexes its places,
# streets and addresses with build_search.py, in Docker so the only local
# requirement is Docker. Releases ship search-nys.sqlite: New York and northern
# New Jersey in one index. search/states.py tells which state a point is in
# (the merged extract doesn't say); regions.json is mounted at /regions.json,
# next to /search, for it.
#
# Optional settings (environment variables; in GitHub Actions, repository
# variables of the same names, see release.yml):
#   ADDRESS_POINTS_URL  NYS Address Points to add to OSM's addresses: a .zip
#                       holding a file geodatabase or shapefile (as published at
#                       https://gis.ny.gov/streets-addresses), a .csv/.geojson,
#                       or the NYS GIS Resource Hub's download link for the
#                       dataset (data.gis.ny.gov/api/download/v1/items/<id>/csv,
#                       shapefile, filegdb or geojson). Unset: OSM addresses
#                       only (the NYC PR check runs so).
#   ADDRESS_POINTS_LAYER  the layer to read when the download holds several
#   SEARCH_STATE        force one state (e.g. NJ) on addresses that don't say;
#                       default: by location (states.py)
#   SEARCH_MAX_MB       fail if the pack is bigger (default 1900: GitHub release
#                       files must stay under 2 GiB)
#   OVERTURE            1: add Overture Places (overture.py) in their own tables,
#                       fetched from Overture's public S3 bucket with DuckDB.
#                       Default off; release.yml and data.yml turn it on.
#   OVERTURE_RELEASE    an Overture release id (e.g. 2026-09-23.1) or "latest";
#                       default: overture.py's DEFAULT_RELEASE while published,
#                       else the newest
#   OVERTURE_MIN_CONFIDENCE  keep places Overture is at least this sure of
#                       (0 to 1, default 0.5); higher makes a smaller pack
#   OVERTURE_MAX_MB     fail if the Overture tables alone are bigger (default
#                       700; New York plus northern New Jersey measured 483 MB
#                       with release 2026-09-23.1)
#
# Usage: build.sh <region>      e.g. build.sh nys
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12-slim@sha256:02108f5d322dd89f1c9e552442c25acb0543dfdbc455693a5599624f20d9155d}"
GDAL_IMAGE="${GDAL_IMAGE:-ghcr.io/osgeo/gdal:ubuntu-small-3.9.2@sha256:6af57cbe64534fd8c3803ac4158e9bf812dfb1968cf13e8922b80f665d76d063}"
SEARCH_MAX_MB="${SEARCH_MAX_MB:-1900}"
# pyosmium, DuckDB and what they need, locked by hash in the file ci.yml installs for the unit tests (its
# header says how to regenerate it). Mounted into the container; pip refuses anything not in it.
PIP_LOCK="$(cd "$PIPELINE_DIR/.." && pwd)/.github/pipeline-requirements.txt"
[[ -f "$PIP_LOCK" ]] || { echo "Missing $PIP_LOCK (the pinned pip requirements)." >&2; exit 1; }
OVERTURE_MAX_MB="${OVERTURE_MAX_MB:-700}"
OVERTURE_MIN_CONFIDENCE="${OVERTURE_MIN_CONFIDENCE:-0.5}"

clip_extract "$REGION"
region_bounds "$REGION"

# Downloads $ADDRESS_POINTS_URL and leaves the points as CSV in WGS84, cut to
# the region, at $DATA_DIR/tmp/address-points-<region>.csv. Converting with
# ogr2ogr (GDAL in Docker) reads whichever layer the geodatabase or shapefile
# holds; addresses.py then picks columns by name.
# The file name for an address points URL: the URL's own file name, or for an
# ArcGIS Hub download link (…/api/download/v1/items/<id>/<format>?…) a name
# built from the format, since that path has no extension.
address_points_filename() {
  local url="$1" path format
  path="${url%%\?*}"
  if [[ "$path" == */api/download/v1/items/*/* ]]; then
    format="${path##*/}"
    case "$format" in
      csv) echo "address-points.csv" ;;
      geojson) echo "address-points.geojson" ;;
      shapefile|filegdb|fgdb|kml|kmz|gpkg|geopackage) echo "address-points-$format.zip" ;;
      *) echo "Unknown Hub download format '$format' in $url" >&2; return 1 ;;
    esac
  else
    basename "$path"
  fi
}

# Downloads $url to $file. An ArcGIS Hub download link answers with JSON
# ({"status":"Pending"…}) while it prepares the file, then with the file or a
# redirect to it; keep asking until it is ready (about an hour at most).
download_address_points() {
  local url="$1" file="$2" tries=0 type
  while true; do
    curl -sSfL --retry 3 -o "$file" -w '%{content_type}\n' "$url" > "$file.type"
    type="$(tr '[:upper:]' '[:lower:]' < "$file.type")"
    rm -f "$file.type"
    if [[ "$type" != application/json* ]] || [[ "$file" == *.geojson ]]; then
      return 0
    fi
    # A JSON answer to a non-GeoJSON download is the Hub saying "not yet".
    if grep -q '"resultUrl"' "$file"; then
      url="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["resultUrl"])' "$file")"
      echo "==> Address points ready at $url"
      continue
    fi
    tries=$((tries + 1))
    if (( tries > 120 )); then
      echo "Address points download never became ready: $(head -c 300 "$file")" >&2
      return 1
    fi
    echo "==> Address points not ready yet ($(head -c 120 "$file" | tr -d '\n')); waiting"
    sleep 30
  done
}

fetch_address_points() {
  local url="$1" file csv input
  file="$DATA_DIR/sources/$(address_points_filename "$url")"
  csv="$DATA_DIR/tmp/address-points-$REGION.csv"
  echo "==> Fetching address points $url"
  if [[ -f "$file" ]]; then
    echo "==> Reusing $(basename "$file")"
  else
    download_address_points "$url" "$file"
  fi
  case "$file" in
    *.csv|*.geojson|*.geojsonl|*.json)
      ADDRESS_POINTS_IN_DOCKER="/data/sources/$(basename "$file")"
      return ;;
  esac
  if [[ -f "$csv" && "$csv" -nt "$file" ]]; then
    echo "==> Reusing $(basename "$csv")"
  else
    rm -rf "$DATA_DIR/tmp/address-points"
    mkdir -p "$DATA_DIR/tmp/address-points"
    if [[ "$file" == *.zip ]]; then
      python3 -m zipfile -e "$file" "$DATA_DIR/tmp/address-points"
    else
      cp "$file" "$DATA_DIR/tmp/address-points/"
    fi
    input="$(cd "$DATA_DIR/tmp/address-points" && find . -maxdepth 3 \( -name '*.gdb' -o -name '*.shp' -o -name '*.gpkg' \) | head -1)"
    if [[ -z "$input" ]]; then
      echo "No .gdb, .shp or .gpkg in $file" >&2
      exit 1
    fi
    echo "==> Converting ${input#./} to CSV, cut to $BOUNDS"
    # shellcheck disable=SC2086  # BOUNDS splits into four numbers, the layer may be empty
    docker run --rm -v "$DATA_DIR:/data" "$GDAL_IMAGE" \
      ogr2ogr -f CSV -overwrite -lco GEOMETRY=AS_XY -t_srs EPSG:4326 \
        -spat ${BOUNDS//,/ } -spat_srs EPSG:4326 \
        "/data/tmp/$(basename "$csv")" "/data/tmp/address-points/${input#./}" ${ADDRESS_POINTS_LAYER:-}
    rm -rf "$DATA_DIR/tmp/address-points"
  fi
  ADDRESS_POINTS_IN_DOCKER="/data/tmp/$(basename "$csv")"
}

ARGS="--bounds=$BOUNDS --max-mb=$SEARCH_MAX_MB"
if [[ -n "${SEARCH_STATE:-}" ]]; then
  ARGS="$ARGS --state=$SEARCH_STATE"
fi
if [[ "${ADDRESS_POINTS_URL:-}" == none ]]; then
  echo "==> ADDRESS_POINTS_URL is none: addresses from OSM only"
elif [[ -n "${ADDRESS_POINTS_URL:-}" ]]; then
  fetch_address_points "$ADDRESS_POINTS_URL"
  ARGS="$ARGS --address-points=$ADDRESS_POINTS_IN_DOCKER"
else
  echo "==> ADDRESS_POINTS_URL is not set: addresses from OSM only"
fi

# Overture Places: overture.py fetch reads the region's rows from Overture's
# bucket into a staging CSV (kept in tmp/ and reused while the release and
# bounds stay the same), then build_search.py loads it into its own tables.
OVERTURE_STEP=""
if [[ "${OVERTURE:-0}" == 1 ]]; then
  OVERTURE_CSV="/data/tmp/overture-$REGION.csv"
  OVERTURE_STEP="python /search/overture.py fetch --bounds=$BOUNDS --out=$OVERTURE_CSV --min-confidence=$OVERTURE_MIN_CONFIDENCE
    chown $(id -u):$(id -g) $OVERTURE_CSV $OVERTURE_CSV.json"
  ARGS="$ARGS --overture=$OVERTURE_CSV --overture-max-mb=$OVERTURE_MAX_MB"
  echo "==> Adding Overture Places (release: ${OVERTURE_RELEASE:-default})"
else
  echo "==> OVERTURE is not 1: OSM places only"
fi

OUTPUT="search-$REGION.sqlite"
echo "==> Indexing places, streets and addresses -> $OUTPUT"
docker run --rm \
  -v "$DATA_DIR:/data" \
  -v "$PIPELINE_DIR/search:/search:ro" \
  -v "$PIPELINE_DIR/regions.json:/regions.json:ro" \
  -v "$PIP_LOCK:/requirements.txt:ro" \
  -e OVERTURE_RELEASE="${OVERTURE_RELEASE:-}" \
  "$PYTHON_IMAGE" bash -c "
    set -e
    # pyosmium's wheel links against libexpat, which the slim image lacks.
    apt-get update -qq >/dev/null && apt-get install -y -qq libexpat1 >/dev/null
    pip install -q --root-user-action=ignore --require-hashes --only-binary :all: -r /requirements.txt
    $OVERTURE_STEP
    python /search/build_search.py $CLIPPED_IN_DOCKER /data/out/$OUTPUT $REGION $ARGS
    chown $(id -u):$(id -g) /data/out/$OUTPUT
  "

report_pack "$DATA_DIR/out/$OUTPUT"
