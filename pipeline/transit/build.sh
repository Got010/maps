#!/usr/bin/env bash
# Build the transit pack pipeline/data/out/transit-<region>.sqlite
#
# Downloads MTA's static GTFS feeds (subway with its supplement of upcoming
# service changes, the six bus files, LIRR and Metro-North) and compiles them
# with build_transit.py, in Docker so the only local requirement is Docker.
#
# Usage: build.sh nyc
set -euo pipefail

REGION="${1:?usage: build.sh <region>}"
# shellcheck source=pipeline/lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12-slim@sha256:02108f5d322dd89f1c9e552442c25acb0543dfdbc455693a5599624f20d9155d}"
GTFS_BASE="https://rrgtfsfeeds.s3.amazonaws.com"

case "$REGION" in
  nyc) ;;
  *) echo "Transit is NYC only until Phase 4." >&2; exit 2 ;;
esac

# name=file pairs; the bus files share one namespace, since MTA reuses stop
# and route ids across them.
FEEDS=(
  subway=gtfs_supplemented.zip
  bus=gtfs_b.zip bus=gtfs_bx.zip bus=gtfs_m.zip bus=gtfs_q.zip bus=gtfs_si.zip bus=gtfs_busco.zip
  lirr=gtfslirr.zip
  mnr=gtfsmnr.zip
)

GTFS_DIR="$DATA_DIR/sources/gtfs"
mkdir -p "$GTFS_DIR"
ARGS=()
for feed in "${FEEDS[@]}"; do
  file="${feed#*=}"
  echo "==> Fetching $GTFS_BASE/$file"
  # -z only re-downloads when MTA has published a newer file.
  curl -fL --retry 3 -sS -z "$GTFS_DIR/$file" -o "$GTFS_DIR/$file" "$GTFS_BASE/$file"
  ARGS+=("${feed%%=*}=/data/sources/gtfs/$file")
done

OUTPUT="transit-$REGION.sqlite"
echo "==> Compiling schedules -> $OUTPUT"
docker run --rm \
  -v "$DATA_DIR:/data" \
  -v "$PIPELINE_DIR/transit:/transit:ro" \
  "$PYTHON_IMAGE" bash -c "
    set -e
    python /transit/build_transit.py /data/out/$OUTPUT $REGION ${ARGS[*]}
    chown $(id -u):$(id -g) /data/out/$OUTPUT
  "

report_pack "$DATA_DIR/out/$OUTPUT"
