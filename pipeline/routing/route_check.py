#!/usr/bin/env python3
"""Routes real trips on the split packs the way a phone would hold them, and
compares each with the same trip on the whole graph.

    route_check.py <work_dir> <out_dir>

<work_dir> is split.sh's Valhalla folder (data/tmp/valhalla-split-all, built
with KEEP_TILES=1: tiles/, tiles.jsonl and valhalla.json); <out_dir> holds the
packs and routing-packs.json. For each trip, a phone with the trip's regions is
made twice, and Valhalla (the pipeline's pinned image, valhalla_service's
one-shot mode) routes the trip on each and on the whole graph:

  packs    routing-main.tar (level 0) and every pack naming one of the
           regions, unpacked into one tile folder, as the app will.
  L0+L1    levels 0 and 1 shared and the regions' local roads, as the first
           test build cut them, linked from the tile index for comparison.

A trip marked "same" must come out within 5% of the whole graph's length and
time on the packs; the others only print what happens (a destination whose
local roads aren't there). Exits 1 when a "same" trip fails or strays on the
packs; the L0+L1 column only informs.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tarfile

TOLERANCE = 0.05

PLACES = {
    "Times Square": (40.7580, -73.9855),
    "Baltimore Inner Harbor": (39.2860, -76.6094),
    "Philadelphia City Hall": (39.9526, -75.1636),
    "Buffalo": (42.8864, -78.8784),
    "Montauk": (41.0359, -71.9545),
    "Pittsburgh": (40.4406, -79.9959),
    "Hartford": (41.7658, -72.6734),
    "Newark Penn Station": (40.7347, -74.1644),
    "Hoboken Terminal": (40.7359, -74.0275),
    "Scranton": (41.4090, -75.6624),
    "Wilmington, DE": (39.7447, -75.5466),
    "Washington, DC": (38.8977, -77.0365),
    "Chicago": (41.8781, -87.6298),
    "Detroit": (42.3314, -83.0458),
    "Cleveland": (41.4993, -81.6944),
    "Columbus": (39.9612, -82.9988),
    "Cincinnati": (39.1031, -84.5120),
    "Indianapolis": (39.7684, -86.1581),
    "St. Louis": (38.6386, -90.2847),
    "Minneapolis": (44.9778, -93.2650),
    "Kansas City": (39.0997, -94.5786),
    "Fargo": (46.8772, -96.7898),
    "Omaha": (41.2565, -95.9345),
}

# (from, to, the phone's regions, must it match the whole graph)
TRIPS = [
    ("Times Square", "Baltimore Inner Harbor", ["nyc", "md"], True),  # through New Jersey and Delaware on main roads
    ("Buffalo", "Montauk", ["western", "li"], True),
    ("Pittsburgh", "Hartford", ["paw", "ct"], True),
    ("Newark Penn Station", "Hoboken Terminal", ["njn"], True),
    ("Philadelphia City Hall", "Baltimore Inner Harbor", ["pae", "md"], True),
    ("Scranton", "Wilmington, DE", ["pae", "md"], True),
    ("Times Square", "Washington, DC", ["nyc", "md"], True),
    # The Midwest: across the seam between the east's and the Midwest's main-road packs, and within it.
    ("Times Square", "Chicago", ["nyc", "iln"], True),  # through Pennsylvania, Ohio and Indiana on main roads
    ("Cleveland", "Pittsburgh", ["ohn", "paw"], True),
    ("Chicago", "Detroit", ["iln", "mis"], True),  # through Indiana
    ("Columbus", "Cincinnati", ["ohs"], True),
    ("Indianapolis", "St. Louis", ["in", "mo"], True),  # through Illinois
    ("Minneapolis", "Kansas City", ["mn", "mo"], True),  # through Iowa
    ("Fargo", "Omaha", ["nd", "ne"], True),  # through South Dakota
    # Without Pennsylvania's local roads: where does the trip end?
    ("Times Square", "Philadelphia City Hall", ["nyc"], False),
]


def docker_route(data_dir, config_rel, start, end):
    """Valhalla's answer for a car trip, as a dict; {"error": ...} when it can't route."""
    request = json.dumps({
        "locations": [{"lat": start[0], "lon": start[1]}, {"lat": end[0], "lon": end[1]}],
        "costing": "auto",
        "units": "kilometers",
    })
    proc = subprocess.run(
        ["docker", "run", "--rm", "-v", f"{data_dir}:/data", "--entrypoint", "valhalla_service", valhalla_image(),
         f"/data/{config_rel}", "route", request],
        capture_output=True, text=True, timeout=600,
    )
    out = proc.stdout.strip()
    try:
        answer = json.loads(out[out.index("{"):]) if "{" in out else {}
    except ValueError:
        answer = {}
    if "trip" not in answer:
        message = answer.get("error") if isinstance(answer.get("error"), str) else None
        return {"error": message or (proc.stderr.strip().splitlines() or ["no answer"])[-1]}
    return answer


def valhalla_image():
    """The image valhalla.sh pins, unless VALHALLA_IMAGE says otherwise."""
    if os.environ.get("VALHALLA_IMAGE"):
        return os.environ["VALHALLA_IMAGE"]
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "valhalla.sh")) as f:
        match = re.search(r'VALHALLA_IMAGE="\$\{VALHALLA_IMAGE:-([^}]+)\}"', f.read())
    return match.group(1)


def summary(answer):
    s = answer["trip"]["summary"]
    return s["length"], s["time"] / 60


def end_point(answer):
    """Where the route really ends: the last point of the last leg's shape (polyline, 6 decimals)."""
    shape = answer["trip"]["legs"][-1]["shape"]
    lat = lon = index = 0
    while index < len(shape):
        for axis in (0, 1):
            shift = result = 0
            while True:
                byte = ord(shape[index]) - 63
                index += 1
                result |= (byte & 0x1F) << shift
                shift += 5
                if byte < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if axis == 0:
                lat += delta
            else:
                lon += delta
    return lat / 1e6, lon / 1e6


def unpack(out_dir, regions, folder):
    """The phone's tile folder from the real packs: routing-main.tar and every pack naming one of the regions."""
    shutil.rmtree(folder, ignore_errors=True)
    os.makedirs(folder)
    with open(os.path.join(out_dir, "routing-packs.json")) as f:
        listing = json.load(f)
    for name, serves in listing.items():
        if not serves or set(serves) & set(regions):
            with tarfile.open(os.path.join(out_dir, name)) as tar:
                tar.extractall(folder, filter="data")


def link(work_dir, regions, folder, main_levels):
    """The phone's tile folder as packs cut with main_levels would make it, linked from the whole tile folder."""
    shutil.rmtree(folder, ignore_errors=True)
    with open(os.path.join(work_dir, "tiles.jsonl")) as f:
        for line in f:
            path, level, _size, covers = json.loads(line)
            if level in main_levels or set(covers) & set(regions):
                os.makedirs(os.path.dirname(os.path.join(folder, path)), exist_ok=True)
                os.link(os.path.join(work_dir, "tiles", path), os.path.join(folder, path))


def config_for(work_dir, tile_dir, name, partial=False):
    """A config for the tiles in tile_dir. A phone's partial set (partial) turns Loki's connectivity check off,
    as the app must: the check colours connected areas by the local-road tiles present, so with the regions
    between two places missing it calls them "unconnected regions" though the main roads join them (and
    valhalla-mobile turns it off for any set of tiles that isn't whole)."""
    with open(os.path.join(work_dir, "valhalla.json")) as f:
        config = json.load(f)
    config["mjolnir"]["tile_dir"] = tile_dir
    config["mjolnir"]["tile_extract"] = tile_dir + "/none.tar"
    if partial:
        config["loki"]["use_connectivity"] = False
    path = os.path.join(work_dir, f"{name}.json")
    with open(path, "w") as f:
        json.dump(config, f)
    return path


def compare(whole, phone, same):
    """(column text, strayed) for one phone's answer against the whole graph's."""
    if "error" in phone:
        return f"failed: {phone['error']}", same
    wl, wt = summary(whole)
    pl, pt = summary(phone)
    off = max(abs(pl - wl) / wl, abs(pt - wt) / wt)
    text = f"{pl:7.1f}/{pt:5.0f} " + ("same" if off <= TOLERANCE else f"{off:.0%} off")
    if not same:
        lat, lon = end_point(phone)
        text += f", ends at {lat:.4f},{lon:.4f}"
    return text, same and off > TOLERANCE


def main(argv):
    work_dir, out_dir = (os.path.abspath(a) for a in argv[1:3])
    data_dir = os.path.dirname(os.path.dirname(work_dir))  # .../data (work_dir is data/tmp/<work>)
    work_rel = os.path.relpath(work_dir, data_dir)
    config_for(work_dir, f"/data/{work_rel}/tiles", "whole")
    config_for(work_dir, f"/data/{work_rel}/phone", "phone", partial=True)
    folder = os.path.join(work_dir, "phone")
    failed = []
    print(f"{'trip':<50} {'regions':<12} {'whole km/min':>13}   {'packs':<22} {'L0+L1 shared':<22}")
    for start, end, regions, same in TRIPS:
        trip = f"{start} -> {end}"
        whole = docker_route(data_dir, f"{work_rel}/whole.json", PLACES[start], PLACES[end])
        if "error" in whole:
            print(f"{trip:<50} whole graph failed: {whole['error']}")
            failed.append(trip)
            continue
        wl, wt = summary(whole)
        unpack(out_dir, regions, folder)
        packs, strayed = compare(whole, docker_route(data_dir, f"{work_rel}/phone.json", PLACES[start], PLACES[end]), same)
        link(work_dir, regions, folder, (0, 1))
        both, _ = compare(whole, docker_route(data_dir, f"{work_rel}/phone.json", PLACES[start], PLACES[end]), same)
        print(f"{trip:<50} {'+'.join(regions):<12} {wl:7.1f}/{wt:5.0f}   {packs:<22} {both:<22}")
        if strayed:
            failed.append(trip)
    shutil.rmtree(folder, ignore_errors=True)
    if failed:
        print("Failed: " + "; ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
