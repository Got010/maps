#!/usr/bin/env python3
"""Writes the manifests the app reads to update itself: southbound.json and southbound-v2.json.

    release-manifest.py <dir> <version> [--only NAME ...]           (prints southbound.json)
    release-manifest.py <dir> <version> --v2 --regions regions.json  (prints southbound-v2.json)

<dir> holds a release's files: southbound-<version>.apk and the data packs.
The app downloads a manifest first, then only the APK or packs whose SHA-256
differs from what it has, and checks each download against it.

southbound.json (schema 1) is what every app before the four-state packs reads,
and they take every routing and search pack it lists, so release.yml lists
only the packs they know (--only: the six New York basemaps, routing-nys,
search-nys, transit, voice). Parsed by core/packs ReleaseManifest; keep the two
in step.

southbound-v2.json (schema 2) lists every pack except the ones built whole
for older apps: routing-nys.tar (a phone routing from a tile folder can't mix
it in) and search-nys.sqlite (New York's regions have their own, cut from it
by search/split_search.py). It says which basemap regions need each routing
and search pack in "regions": a routing pack's from routing-packs.json
(split_tiles.py; an empty list, routing-main.tar, is every phone's), a search
pack's is its own region, when regions.json has that basemap region. A pack
without "regions" goes by its kind as before: a basemap for its own region,
voices when picked, transit for every phone.
"""

import argparse
import hashlib
import json
import os
import re
import sys

PACK = re.compile(r"^(basemap|routing|search|transit|voice)-([a-z0-9-]+)\.(pmtiles|tar|sqlite|zip)$")
ROUTING_PACKS = "routing-packs.json"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def version_code(version):
    """1.2.3 -> 10203. Each part 0 to 99: 0.1.100 would make 200, the code of 0.2.0."""
    major, minor, patch = (int(p) for p in version.split("."))
    if not all(0 <= part <= 99 for part in (major, minor, patch)):
        raise SystemExit(f"Version {version}: each part must be 0 to 99")
    return major * 10_000 + minor * 100 + patch


def entry(directory, name):
    path = os.path.join(directory, name)
    return {"file": name, "size": os.path.getsize(path), "sha256": sha256(path)}


def search_regions(regions_json):
    """Search pack id -> the basemap regions that use it: each basemap region its own."""
    with open(regions_json) as f:
        regions = json.load(f)["regions"]
    return {name: [name] for name, region in regions.items() if region["basemap"]}


def manifest(directory, version, only=None, v2=False, regions_json=None):
    names = sorted(os.listdir(directory))
    apk = f"southbound-{version}.apk"
    routing = {}
    if v2:
        # Every v2 release routes from tiles: no routing-packs.json, or one listing nothing, is a packaging slip, and
        # a manifest without routing packs would make the phones drop their offline routing.
        if ROUTING_PACKS not in names:
            raise SystemExit(f"{ROUTING_PACKS} is missing: southbound-v2.json would list no routing packs")
        with open(os.path.join(directory, ROUTING_PACKS)) as f:
            routing = json.load(f)
        if not routing:
            raise SystemExit(f"{ROUTING_PACKS} lists no routing packs")
    searches = search_regions(regions_json) if v2 else {}
    packs = []
    for name in names:
        m = PACK.match(name)
        if not m or (only is not None and name not in only):
            continue
        pack = {"kind": m.group(1), "region": m.group(2), **entry(directory, name)}
        if v2 and pack["kind"] == "routing":
            if name not in routing:
                continue  # a one-graph pack (routing-nys.tar)
            pack["regions"] = routing[name]
        elif v2 and pack["kind"] == "search":
            if pack["region"] not in searches:
                continue  # no basemap region's own (search-nys.sqlite, for older apps)
            pack["regions"] = searches[pack["region"]]
        packs.append(pack)
    return {
        "schema": 2 if v2 else 1,
        "version": version,
        "versionCode": version_code(version),
        "app": entry(directory, apk) if apk in names else None,
        "packs": packs,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dir")
    parser.add_argument("version")
    parser.add_argument("--only", nargs="+", help="list only these files (southbound.json for older apps)")
    parser.add_argument("--v2", action="store_true", help="write southbound-v2.json instead")
    parser.add_argument("--regions", help="pipeline/regions.json, for --v2")
    args = parser.parse_args()
    if args.v2 and not args.regions:
        parser.error("--v2 needs --regions")
    json.dump(manifest(args.dir, args.version, set(args.only) if args.only else None, args.v2, args.regions),
              sys.stdout, indent=2)
    print()
