#!/usr/bin/env bash
# Build the search pack pipeline/data/out/search-<region>.sqlite
#
# Clips the Geofabrik New York extract to the region, then indexes its
# places, streets and addresses with build_search.py, in Docker so the only
# local requirement is Docker.
#
# Usage: build.sh nyc
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12-slim}"
PYOSMIUM_VERSION="4.3.1"

clip_extract "$REGION"

OUTPUT="search-$REGION.sqlite"
echo "==> Indexing places, streets and addresses -> $OUTPUT"
docker run --rm \
  -v "$DATA_DIR:/data" \
  -v "$PIPELINE_DIR/search:/search:ro" \
  "$PYTHON_IMAGE" bash -c "
    set -e
    # pyosmium's wheel links against libexpat, which the slim image lacks.
    apt-get update -qq >/dev/null && apt-get install -y -qq libexpat1 >/dev/null
    pip install -q --root-user-action=ignore osmium==$PYOSMIUM_VERSION
    python /search/build_search.py /data/tmp/$REGION.osm.pbf /data/out/$OUTPUT $REGION
    chown $(id -u):$(id -g) /data/out/$OUTPUT
  "

report_pack "$DATA_DIR/out/$OUTPUT"
