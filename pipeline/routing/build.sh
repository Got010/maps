#!/usr/bin/env bash
# Build the routing pack pipeline/data/out/routing-<region>.tar
#
# Builds the region's extract (see clip_extract in lib/common.sh: New York cut
# to the region, or for nys the whole New York extract merged with northern
# New Jersey), then builds Valhalla routing tiles in Docker and packs them into
# one tar. The app opens the tar with valhalla-mobile, so the
# Valhalla version here must match the one that library is built on
# (valhalla-mobile 0.6.3 pins Valhalla 3.6.3).
#
# Releases ship routing-nys.tar, one graph for New York and northern New
# Jersey, so a trip from Buffalo to Montauk, or from Newark to Albany, routes
# offline. (The id stays nys; renaming it is a pack migration.) routing-nyc.tar still builds for
# quick tests.
#
# Usage: build.sh <region>      e.g. build.sh nys
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
VALHALLA_IMAGE="${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla:3.6.3@sha256:2b19ea46551a9687b245022551183829d817fdee9b58c5e7b2adb6e422749c43}"

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
    valhalla_build_admins -c valhalla.json $CLIPPED_IN_DOCKER
    valhalla_build_tiles -c valhalla.json $CLIPPED_IN_DOCKER
    valhalla_build_extract -c valhalla.json --overwrite
  "
# The loose tiles are in the tar now; free the disk for the next pack.
rm -rf "${DATA_DIR:?}/${WORK:?}/tiles"

report_pack "$DATA_DIR/out/$OUTPUT"
