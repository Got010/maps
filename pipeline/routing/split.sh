#!/usr/bin/env bash
# Build the split routing packs: pipeline/data/out/routing-main.tar and one
# routing-<region>.tar per basemap region.
#
# One Valhalla graph for every state at once (region "all" in regions.json:
# New York, New Jersey, Connecticut, Pennsylvania, Maryland, Delaware and DC,
# each extract whole, merged), so roads join across every state line; then
# split_tiles.py cuts its tiles into the main roads everywhere (levels 0 and 1)
# and each region's local roads (level 2). A phone unpacks the main pack and
# the packs of its regions into one tile folder: a long trip runs on the main
# roads, local streets exist where the driver has the map.
#
# Memory: the graph for all seven extracts (about 1.3 GB of OSM) is the
# largest build in the pipeline; it wants a runner with more than 7 GB.
#
# Usage: split.sh [region]      default: all
set -euo pipefail

REGION="${1:-all}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
# shellcheck source=pipeline/routing/valhalla.sh
source "$(dirname "$0")/valhalla.sh"

clip_extract "$REGION"

WORK="tmp/valhalla-split-$REGION"
valhalla_tiles "$WORK"
# The merged extract is only the graph's input.
if [[ "${FREE_SOURCES:-}" == 1 && "$CLIPPED" == "$DATA_DIR/tmp/"* ]]; then
  rm -f "$CLIPPED"
fi

echo "==> Splitting into main roads and each region's local roads"
python3 "$PIPELINE_DIR/routing/split_tiles.py" "$DATA_DIR/$WORK/tiles" "$DATA_DIR/out" --regions="$REGIONS_JSON" \
  --index="$DATA_DIR/$WORK/tiles.jsonl"
if [[ "${KEEP_TILES:-}" != 1 ]]; then
  rm -rf "${DATA_DIR:?}/${WORK:?}/tiles"
fi

for pack in "$DATA_DIR"/out/routing-*.tar; do
  case "$(basename "$pack")" in
    routing-nys.tar | routing-nyc.tar) ;;  # one-graph packs from build.sh
    *) report_pack "$pack" ;;
  esac
done
