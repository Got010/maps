#!/usr/bin/env bash
# Build the routing pack pipeline/data/out/routing-<region>.tar
#
# Builds the region's extract (see clip_extract in lib/common.sh: New York cut
# to the region, or for nys the whole New York extract merged with northern
# New Jersey), then builds Valhalla routing tiles in Docker and packs them into
# one tar (valhalla.sh). The app opens the tar with valhalla-mobile.
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
# shellcheck source=pipeline/routing/valhalla.sh
source "$(dirname "$0")/valhalla.sh"

clip_extract "$REGION"

WORK="tmp/valhalla-$REGION"
OUTPUT="routing-$REGION.tar"
valhalla_tiles "$WORK" "$OUTPUT"
# The loose tiles are in the tar now; free the disk for the next pack.
rm -rf "${DATA_DIR:?}/${WORK:?}/tiles"

report_pack "$DATA_DIR/out/$OUTPUT"
