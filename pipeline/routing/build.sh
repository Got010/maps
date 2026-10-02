#!/usr/bin/env bash
# Build the routing pack pipeline/data/out/routing-<region>.tar
#
# Clips the Geofabrik New York extract to the region, then builds Valhalla
# routing tiles in Docker and packs them into one tar. The app opens the tar
# with valhalla-mobile, so the Valhalla version here must match the one that
# library is built on (valhalla-mobile 0.6.3 pins Valhalla 3.6.3).
#
# Usage: build.sh nyc
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
VALHALLA_IMAGE="${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla:3.6.3}"

clip_extract "$REGION"

WORK="tmp/valhalla-$REGION"
OUTPUT="routing-$REGION.tar"
# Fresh tiles each build; DATA_DIR and WORK are always set above.
rm -rf "${DATA_DIR:?}/${WORK:?}"
mkdir -p "$DATA_DIR/$WORK/tiles"

echo "==> Valhalla ($VALHALLA_IMAGE) -> $OUTPUT"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$DATA_DIR:/data" \
  --entrypoint bash \
  "$VALHALLA_IMAGE" -c "
    set -euo pipefail
    cd /data/$WORK
    valhalla_build_config \
      --mjolnir-tile-dir /data/$WORK/tiles \
      --mjolnir-tile-extract /data/out/$OUTPUT \
      --mjolnir-admin /data/$WORK/admin.sqlite \
      --mjolnir-timezone /data/$WORK/timezones.sqlite \
      --mjolnir-concurrency \$(nproc) \
      > valhalla.json
    # Admin areas give each road its country and state rules (driving side,
    # default speeds). Time zones aren't needed for offline routing.
    valhalla_build_admins -c valhalla.json /data/tmp/$REGION.osm.pbf
    valhalla_build_tiles -c valhalla.json /data/tmp/$REGION.osm.pbf
    valhalla_build_extract -c valhalla.json --overwrite
  "

report_pack "$DATA_DIR/out/$OUTPUT"
