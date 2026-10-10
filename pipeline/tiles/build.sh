#!/usr/bin/env bash
# Build the basemap pack pipeline/data/out/basemap-<region>.pmtiles
#
# Downloads the region's Geofabrik extract (New York, or New Jersey for njn;
# verified against its .md5), then runs Planetiler in Docker with the
# OpenMapTiles profile, bounded to the region (see pipeline/regions.json).
# Water polygons and Natural Earth data are fetched by Planetiler's --download
# on the first run and cached in pipeline/data/sources. The njn map holds New
# Jersey only: the New York side of its box is drawn by the nyc and hudson maps.
# A region of several extracts (md: Maryland, Delaware and DC) is drawn from
# them merged (clip_extract), so one map holds all three. Planetiler empties
# its temp folder when it starts, so it gets one of its own: the merged
# extract waits in data/tmp.
#
# Usage: build.sh <region>      e.g. build.sh nyc, build.sh western
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PLANETILER_IMAGE="${PLANETILER_IMAGE:-ghcr.io/onthegomap/planetiler:0.10.2@sha256:cf32202dbc001a9ab4bc11534b642b13de3798179817da8558e567a3d13dd403}"
# A GitHub runner for a private repository has 7 GB of RAM; the node map goes
# to a memory-mapped file so the heap stays well under that for any region.
PLANETILER_HEAP="${PLANETILER_HEAP:-4g}"

region_bounds "$REGION"
if (( $(region_sources "$REGION" | wc -l) > 1 )); then
  clip_extract "$REGION"
  EXTRACT_IN_DOCKER="$CLIPPED_IN_DOCKER"
else
  primary_extract "$REGION"
  EXTRACT_IN_DOCKER="/data/sources/$(basename "$EXTRACT")"
fi

OUTPUT="basemap-$REGION.pmtiles"
echo "==> Planetiler ($PLANETILER_IMAGE) -> $OUTPUT ($BOUNDS)"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e JAVA_TOOL_OPTIONS="-Xmx$PLANETILER_HEAP" \
  -v "$DATA_DIR:/data" \
  "$PLANETILER_IMAGE" \
  --osm_path="$EXTRACT_IN_DOCKER" \
  --download \
  --download_dir=/data/sources \
  --tmpdir="/data/tmp/planetiler-$REGION" \
  --nodemap_storage=mmap \
  --bounds="$BOUNDS" \
  --languages=en \
  --output="/data/out/$OUTPUT" \
  --force

report_pack "$DATA_DIR/out/$OUTPUT"
