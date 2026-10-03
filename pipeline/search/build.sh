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
# Usage: build.sh <region>      e.g. build.sh nys
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
  -v "$PIPELINE_DIR/regions.json:/regions.json:ro" \
  "$PYTHON_IMAGE" bash -c "
    set -e
    # pyosmium's wheel links against libexpat, which the slim image lacks.
    apt-get update -qq >/dev/null && apt-get install -y -qq libexpat1 >/dev/null
    pip install -q --root-user-action=ignore osmium==$PYOSMIUM_VERSION
    python /search/build_search.py $CLIPPED_IN_DOCKER /data/out/$OUTPUT $REGION
    chown $(id -u):$(id -g) /data/out/$OUTPUT
  "

report_pack "$DATA_DIR/out/$OUTPUT"
