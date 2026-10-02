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
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PLANETILER_IMAGE="${PLANETILER_IMAGE:-ghcr.io/onthegomap/planetiler:0.10.2}"
PLANETILER_HEAP="${PLANETILER_HEAP:-4g}"

region_bounds "$REGION"
fetch_extract

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

report_pack "$DATA_DIR/out/$OUTPUT"
