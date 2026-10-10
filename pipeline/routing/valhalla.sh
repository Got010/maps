# shellcheck shell=bash
# Valhalla's tile build, shared by build.sh (one tile extract) and split.sh
# (loose tiles, cut into packs). Source it after lib/common.sh.
#
#   valhalla_tiles <work> [<extract>]
#
# Builds tiles from $CLIPPED_IN_DOCKER (clip_extract) into
# $DATA_DIR/<work>/tiles; with <extract> (a file name under data/out) also packs
# them into that one tar. The Valhalla version must match the one
# valhalla-mobile is built on (valhalla-mobile 0.6.3 pins Valhalla 3.6.3).

VALHALLA_IMAGE="${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla:3.6.3@sha256:2b19ea46551a9687b245022551183829d817fdee9b58c5e7b2adb6e422749c43}"

valhalla_tiles() {
  local work="$1" extract="${2:-}" extract_args="" extract_step=""
  # Fresh tiles each build; DATA_DIR and work are always set.
  rm -rf "${DATA_DIR:?}/${work:?}"
  mkdir -p "$DATA_DIR/$work/tiles"
  if [[ -n "$extract" ]]; then
    extract_args="--mjolnir-tile-extract /data/out/$extract"
    extract_step="valhalla_build_extract -c valhalla.json --overwrite"
  else
    # No extract: a missing file makes Valhalla read the tile directory.
    extract_args="--mjolnir-tile-extract /data/$work/none.tar"
  fi
  echo "==> Valhalla ($VALHALLA_IMAGE) -> ${extract:-$work/tiles}"
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$DATA_DIR:/data" \
    --entrypoint bash \
    "$VALHALLA_IMAGE" -c "
      set -euo pipefail
      cd /data/$work
      valhalla_build_config \
        --mjolnir-tile-dir /data/$work/tiles \
        $extract_args \
        --mjolnir-admin /data/$work/admin.sqlite \
        --mjolnir-timezone /data/$work/timezones.sqlite \
        --mjolnir-concurrency \$(nproc) \
        > valhalla.json
      # Admin areas give each road its country and state rules (driving side,
      # default speeds). Time zones aren't needed for offline routing.
      valhalla_build_admins -c valhalla.json $CLIPPED_IN_DOCKER
      valhalla_build_tiles -c valhalla.json $CLIPPED_IN_DOCKER
      $extract_step
    "
}
