#!/usr/bin/env python3
"""What a phone would download for routing under different ways of cutting the graph, from split_tiles.py's
tile index (one JSON line per tile: path, level, bytes, packs).

    pack_sizes.py <tiles.jsonl>

For a few choices of regions it prints the download in MB under:

  regions   the packs split_tiles.py writes: routing-main (levels 0 and 1) plus each chosen region's
            routing-<region>.tar. Regions overlap, so a tile on a boundary comes in several packs; the phone
            keeps it once ("kept").
  states    one local-road pack per state (New York's five regions in one, both New Jersey regions, both
            Pennsylvania halves), plus routing-main.
  blocks    local roads in squares that don't overlap (half a degree, or one), plus routing-main: a chosen
            region takes every square holding one of its tiles.
  blocks+1  as blocks of one degree, with each square's level 1 tile in it too, and only level 0 shared.

Run by the states test build; nothing reads its output but people.
"""

import json
import sys

STATES = {"ny": ["nyc", "li", "hudson", "capital", "western"], "nj": ["njn", "njs"], "ct": ["ct"],
          "pa": ["pae", "paw"], "md": ["md"]}

CHOICES = [
    ("Hudson Valley", ["hudson"]),
    ("Hudson Valley + Capital", ["hudson", "capital"]),
    ("NYC + Hudson + Capital + North NJ", ["nyc", "hudson", "capital", "njn"]),
    ("All of New York + North NJ", ["nyc", "li", "hudson", "capital", "western", "njn"]),
    ("Pennsylvania", ["pae", "paw"]),
    ("Maryland, Delaware, DC", ["md"]),
    ("Everything", [r for rs in STATES.values() for r in rs]),
]


def read_index(lines):
    """[(path, level, bytes, packs)] from the index's lines."""
    return [tuple(json.loads(line)) for line in lines if line.strip()]


def corner(path, level):
    """The south-west corner (lat, lon) of a tile, from its path (split_tiles naming)."""
    sizes = {0: 4.0, 1: 1.0, 2: 0.25}
    tile_id = int("".join(path.split("/")[1:])[:-len(".gph")])
    row, col = divmod(tile_id, int(round(360 / sizes[level])))
    return -90 + row * sizes[level], -180 + col * sizes[level]


def block_of(path, level, degrees):
    lat, lon = corner(path, level)
    return int((lat + 90) // degrees), int((lon + 180) // degrees)


def sizes(tiles, choice):
    """{scheme: MB} for one choice of regions."""
    chosen = set(choice)
    level = {0: 0, 1: 0}
    local = [(p, lv, b, packs) for p, lv, b, packs in tiles if lv == 2 and packs]
    for p, lv, b, packs in tiles:
        if lv in level:
            level[lv] += b
    main = level[0] + level[1]

    out = {}
    out["regions"] = main + sum(b * len(chosen & set(packs)) for _, _, b, packs in local)
    out["kept"] = main + sum(b for _, _, b, packs in local if chosen & set(packs))
    states = {s for s, rs in STATES.items() if chosen & set(rs)}
    out["states"] = main + sum(
        b * sum(1 for s in states if set(STATES[s]) & set(packs)) for _, _, b, packs in local
    )
    for degrees in (0.5, 1.0):
        blocks = {block_of(p, lv, degrees) for p, lv, _, packs in local if chosen & set(packs)}
        out[f"blocks {degrees:g}"] = main + sum(b for p, lv, b, _ in local if block_of(p, lv, degrees) in blocks)
    blocks = {block_of(p, lv, 1.0) for p, lv, _, packs in local if chosen & set(packs)}
    out["blocks+1"] = level[0] + sum(
        b for p, lv, b, packs in tiles if lv in (1, 2) and block_of(p, lv, 1.0) in blocks and (lv == 1 or packs)
    )
    return {k: v / 1e6 for k, v in out.items()}


def report(tiles, log=sys.stdout):
    schemes = ["regions", "kept", "states", "blocks 0.5", "blocks 1", "blocks+1"]
    print(f"{'MB to download':<38}" + "".join(f"{s:>11}" for s in schemes), file=log)
    for name, choice in CHOICES:
        row = sizes(tiles, choice)
        print(f"{name:<38}" + "".join(f"{row[s]:11.0f}" for s in schemes), file=log)


if __name__ == "__main__":
    with open(sys.argv[1]) as f:
        report(read_index(f))
