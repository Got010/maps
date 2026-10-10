#!/usr/bin/env python3
"""Cuts a Valhalla tile directory into routing packs, so a phone downloads the
main roads everywhere and local roads only where it has the map, each tile once.

    split_tiles.py <tiles_dir> <out_dir> [--regions=regions.json] [--main-levels=0] [--max-mb=1900]

Valhalla keeps its graph in three levels of square tiles, each file named by
level and tile id (2/000/756/425.gph):

  level 0, 4-degree tiles: motorways, trunk and primary roads
  level 1, 1-degree tiles: secondary and tertiary roads
  level 2, 0.25-degree tiles: everything else (residential, service, ...)

routing-main.tar gets every tile of the main levels (level 0 by default: the
roads a long trip runs on), which every phone has. Every other tile, secondary
and tertiary roads and local streets alike, goes by the basemap regions of regions.json
(planned ones too) it covers: it is sampled on a 9 by 9 grid of points, and each
point goes to the regions the app would give it (Regions.basemapsAt in
core/packs: a region whose area holds the point owns it, else every region whose
box holds it), so Pennsylvania's local roads stay out of the Hudson Valley's
though its box reaches over the river. Tiles covering the same regions share a
pack named by them: routing-hudson.tar holds the tiles only the Hudson Valley
covers, routing-hudson-njn-nyc.tar those on the edge of three regions. A phone
takes every pack naming one of its regions, so a tile on a boundary is
downloaded once, however many of the phone's regions it touches. Tiles no
region covers (Ohio, Virginia: the extracts reach a little past the state
lines) are left out and counted.

routing-packs.json, next to the packs, lists each pack with the regions it
serves (an empty list for routing-main.tar: every phone); the release manifest
copies it. The packs are plain tars of the tile files, ordered and timestamped
the same on every run.
"""

import argparse
import json
import os
import sys
import tarfile

LEVEL_SIZES = {0: 4.0, 1: 1.0, 2: 0.25}
# Level 0 only (owner, October 2026: the leanest packs). The first test build shared levels 0 and 1 (345 MB
# for every phone); with level 1 going by region, a phone with the Hudson Valley alone saves about 150 MB, and the
# states test build routes every trip on this layout against the whole graph (route_check.py).
MAIN_LEVELS = (0,)
MAIN_PACK = "main"
PACKS_JSON = "routing-packs.json"
SAMPLES = 9

HERE = os.path.dirname(os.path.abspath(__file__))
REGIONS_JSON = os.path.join(HERE, "..", "regions.json")


def columns(level):
    return int(round(360 / LEVEL_SIZES[level]))


def rows(level):
    return int(round(180 / LEVEL_SIZES[level]))


def digits(level):
    """Valhalla's FileSuffix: the digits of the level's largest tile id, padded up to a multiple of 3."""
    n = len(str(columns(level) * rows(level) - 1))
    return n + (-n) % 3


def tile_path(level, tile_id):
    """(2, 756425) -> "2/000/756/425.gph", as Valhalla names the file."""
    text = str(tile_id).zfill(digits(level))
    return f"{level}/" + "/".join(text[i:i + 3] for i in range(0, len(text), 3)) + ".gph"


def tile_from_path(path):
    """"2/000/756/425.gph" -> (2, 756425); None for anything that isn't a tile."""
    parts = path.replace(os.sep, "/").split("/")
    if len(parts) < 2 or not parts[-1].endswith(".gph"):
        return None
    try:
        level = int(parts[0])
        tile_id = int("".join(parts[1:-1]) + parts[-1][:-len(".gph")])
    except ValueError:
        return None
    if level not in LEVEL_SIZES or tile_path(level, tile_id) != "/".join(parts):
        return None
    return level, tile_id


def tile_id_at(level, lat, lon):
    size = LEVEL_SIZES[level]
    row = int((lat + 90) // size)
    col = int((lon + 180) // size)
    return row * columns(level) + col


def tile_bounds(level, tile_id):
    """(minlon, minlat, maxlon, maxlat) of a tile."""
    size = LEVEL_SIZES[level]
    row, col = divmod(tile_id, columns(level))
    minlon = -180 + col * size
    minlat = -90 + row * size
    return minlon, minlat, minlon + size, minlat + size


def in_box(bounds, lon, lat):
    return bounds[0] <= lon <= bounds[2] and bounds[1] <= lat <= bounds[3]


def in_area(area, lon, lat):
    """Ray casting; the same as Region.insideArea in core/packs Regions.kt."""
    inside = False
    previous = area[-1]
    for point in area:
        if (point[1] > lat) != (previous[1] > lat) and (
            lon < (previous[0] - point[0]) * (lat - point[1]) / (previous[1] - point[1]) + point[0]
        ):
            inside = not inside
        previous = point
    return inside


def load_regions(path=REGIONS_JSON):
    """The basemap regions (planned ones too), in file order: id -> region."""
    with open(path) as f:
        regions = json.load(f)["regions"]
    return {name: r for name, r in regions.items() if r["basemap"]}


def owners_at(regions, lon, lat):
    """The regions a point belongs to, like Regions.basemapsAt in Regions.kt."""
    found = [
        name for name, r in regions.items()
        if in_box(r["bounds"], lon, lat) and ("area" not in r or in_area(r["area"], lon, lat))
    ]
    return [n for n in found if "area" in regions[n]] or found


def regions_for_tile(regions, level, tile_id):
    """The regions a tile covers, in regions.json's order ([] for none)."""
    minlon, minlat, maxlon, maxlat = tile_bounds(level, tile_id)
    found = []
    for i in range(SAMPLES):
        lat = minlat + (maxlat - minlat) * i / (SAMPLES - 1)
        for j in range(SAMPLES):
            lon = minlon + (maxlon - minlon) * j / (SAMPLES - 1)
            for name in owners_at(regions, lon, lat):
                if name not in found:
                    found.append(name)
    order = list(regions)
    return sorted(found, key=order.index)


def pack_for(level, covers, main_levels=MAIN_LEVELS):
    """The pack a tile goes into: MAIN_PACK for the main levels, else the regions it covers joined in alphabetical
    order ("hudson-njn-nyc"); None when it covers none."""
    if level in main_levels:
        return MAIN_PACK
    return "-".join(sorted(covers)) or None


def tiles_in(tiles_dir):
    """[(relative path, level, tile id)] of every tile file, sorted by path."""
    found = []
    for root, _dirs, files in os.walk(tiles_dir):
        for name in files:
            rel = os.path.relpath(os.path.join(root, name), tiles_dir).replace(os.sep, "/")
            tile = tile_from_path(rel)
            if tile is not None:
                found.append((rel, *tile))
    return sorted(found)


def write_tar(path, tiles_dir, paths):
    """A ustar archive of the tiles, in path order, with fixed metadata so a rebuild of the same tiles is the same file."""
    tmp = path + ".part"
    with tarfile.open(tmp, "w", format=tarfile.USTAR_FORMAT) as tar:
        for rel in sorted(paths):
            info = tarfile.TarInfo(rel)
            info.size = os.path.getsize(os.path.join(tiles_dir, rel))
            info.mtime = 0
            info.mode = 0o644
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            with open(os.path.join(tiles_dir, rel), "rb") as f:
                tar.addfile(info, f)
    os.replace(tmp, path)


def split(tiles_dir, out_dir, regions, max_mb=None, log=sys.stdout, index=None, main_levels=MAIN_LEVELS):
    """Writes the packs and routing-packs.json and returns {pack: (tiles, bytes)} plus the left-out tiles under
    None. With index, also writes one JSON line per tile there ([path, level, bytes, regions it covers]) for
    pack_sizes.py and route_check.py."""
    packs = {MAIN_PACK: []}
    covering = {MAIN_PACK: []}
    dropped = []
    level_bytes = {level: 0 for level in LEVEL_SIZES}
    lines = []
    for rel, level, tile_id in tiles_in(tiles_dir):
        size = os.path.getsize(os.path.join(tiles_dir, rel))
        level_bytes[level] += size
        covers = regions_for_tile(regions, level, tile_id)
        name = pack_for(level, covers, main_levels)
        if name is None:
            dropped.append(rel)
        else:
            packs.setdefault(name, []).append(rel)
            covering.setdefault(name, sorted(covers))
        lines.append(json.dumps([rel, level, size, covers]))
    if index:
        with open(index, "w") as f:
            f.write("\n".join(lines) + "\n")

    os.makedirs(out_dir, exist_ok=True)
    result = {}
    listing = {}
    for name, paths in sorted(packs.items()):
        if not paths:
            continue
        size = sum(os.path.getsize(os.path.join(tiles_dir, rel)) for rel in paths)
        if max_mb is not None and size > max_mb * 1e6:
            raise SystemExit(f"routing-{name}.tar would be {size / 1e6:.0f} MB, over {max_mb:.0f} MB")
        write_tar(os.path.join(out_dir, f"routing-{name}.tar"), tiles_dir, paths)
        result[name] = (len(paths), size)
        listing[f"routing-{name}.tar"] = covering[name]
    with open(os.path.join(out_dir, PACKS_JSON), "w") as f:
        json.dump(listing, f, indent=1, sort_keys=True)
        f.write("\n")
    result[None] = (len(dropped), sum(os.path.getsize(os.path.join(tiles_dir, rel)) for rel in dropped))

    print("Level  MB", file=log)
    for level, size in level_bytes.items():
        print(f"{level:5d}  {size / 1e6:8.1f}", file=log)
    width = max(len(f"routing-{n}.tar") for n in result if n) if len(result) > 1 else 22
    print(f"{'pack':<{width}} {'tiles':>6} {'MB':>8}", file=log)
    for name, (count, size) in result.items():
        label = f"routing-{name}.tar" if name else "(no region, left out)"
        print(f"{label:<{width}} {count:6d} {size / 1e6:8.1f}", file=log)
    print(f"{len(result) - 1} packs, longest name {width} characters", file=log)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("tiles_dir")
    parser.add_argument("out_dir")
    parser.add_argument("--regions", default=REGIONS_JSON)
    parser.add_argument("--max-mb", type=float, default=1900, help="fail if a pack would be larger (GitHub's limit is 2 GiB)")
    parser.add_argument("--index", help="write a JSON line per tile here (see pack_sizes.py)")
    parser.add_argument("--main-levels", default=",".join(str(lv) for lv in MAIN_LEVELS),
                        help="levels every phone gets in routing-main.tar (default 0)")
    args = parser.parse_args(argv)
    main_levels = tuple(int(lv) for lv in args.main_levels.split(",") if lv)
    split(args.tiles_dir, args.out_dir, load_regions(args.regions), args.max_mb, index=args.index,
          main_levels=main_levels)


if __name__ == "__main__":
    main()
