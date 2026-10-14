#!/usr/bin/env python3
"""What a phone would download for routing under different ways of cutting the graph, from split_tiles.py's
tile index (one JSON line per tile: path, level, bytes, regions it covers).

    pack_sizes.py <tiles.jsonl>

For a few choices of regions it prints the download in MB under:

  per region      levels 0 and 1 shared, plus one pack of local roads per region, as the first test build cut
                  them: a tile on a boundary comes in every region's pack.
  one copy        levels 0 and 1 shared, plus tiles grouped by the regions they cover, so each comes once.
  one copy, L0    what split_tiles.py writes: only level 0 (motorways, trunk and primary roads) shared, by the
                  "main" group of the regions (the east's level 0 for a New York phone, the Midwest's too for one
                  with a Midwest region), and level 1 (secondary and tertiary) going by region like local roads,
                  each tile once.

Run by the states test build; nothing reads its output but people.
"""

import json
import os
import sys

CHOICES = [
    ("Hudson Valley", ["hudson"]),
    ("Hudson Valley + Capital", ["hudson", "capital"]),
    ("NYC + Hudson + Capital + North NJ", ["nyc", "hudson", "capital", "njn"]),
    ("All of New York + North NJ", ["nyc", "li", "hudson", "capital", "western", "njn"]),
    ("Pennsylvania", ["pae", "paw"]),
    ("Maryland, Delaware, DC", ["md"]),
    ("Everything east", ["nyc", "li", "hudson", "capital", "western", "njn", "njs", "ct", "pae", "paw", "md"]),
    ("Chicago and Northern Illinois", ["iln"]),
    ("Northern Ohio", ["ohn"]),
    ("Ohio", ["ohn", "ohs"]),
    ("Southern Michigan + Ohio + Indiana", ["mis", "ohn", "ohs", "in"]),
    ("NYC + Chicago (a trip there)", ["nyc", "iln"]),
    ("The Midwest", ["ohn", "ohs", "in", "iln", "ils", "mis", "miu", "wi", "mn", "ia", "mo", "nd", "sd", "ne", "ks"]),
    ("Northern Virginia, Richmond and Hampton Roads", ["vae"]),
    ("Virginia", ["vae", "vaw"]),
    ("West Virginia", ["wv"]),
    ("Kentucky", ["ky"]),
    ("Maryland + Northern Virginia (a DC phone)", ["md", "vae"]),
    ("Virginia, West Virginia and Kentucky", ["vae", "vaw", "wv", "ky"]),
]
SCHEMES = ["per region", "one copy", "one copy, L0"]


HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import split_tiles as st  # noqa: E402

REGIONS = st.load_regions()


def read_index(lines):
    """[(path, level, bytes, regions)] from the index's lines."""
    return [tuple(json.loads(line)) for line in lines if line.strip()]


def sizes(tiles, choice):
    """{scheme: MB} for one choice of regions."""
    chosen = set(choice)
    by_level = {0: 0, 1: 0}
    for _, level, size, _ in tiles:
        if level in by_level:
            by_level[level] += size
    local = [(level, size, set(covers)) for _, level, size, covers in tiles if level == 2]
    groups = {st.group_of(REGIONS[name]) for name in chosen if name in REGIONS}
    main = 0
    for path, level, size, covers in tiles:
        if level == 0 and groups & set(st.main_groups_for_tile(REGIONS, 0, st.tile_from_path(path)[1], covers)):
            main += size
    out = {
        "per region": by_level[0] + by_level[1] + sum(size * len(chosen & covers) for _, size, covers in local),
        "one copy": by_level[0] + by_level[1] + sum(size for _, size, covers in local if chosen & covers),
        "one copy, L0": main + sum(
            size for _, level, size, covers in tiles if level in (1, 2) and chosen & set(covers)
        ),
    }
    return {k: v / 1e6 for k, v in out.items()}


def report(tiles, log=sys.stdout):
    print(f"{'MB to download':<38}" + "".join(f"{s:>15}" for s in SCHEMES), file=log)
    for name, choice in CHOICES:
        row = sizes(tiles, choice)
        print(f"{name:<38}" + "".join(f"{row[s]:15.0f}" for s in SCHEMES), file=log)


if __name__ == "__main__":
    with open(sys.argv[1]) as f:
        report(read_index(f))
