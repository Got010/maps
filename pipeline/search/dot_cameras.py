#!/usr/bin/env python3
"""State DOT traffic cameras for the search pack's landmark table.

The app's "Avoid DOT cameras" route option squares these off like plate
readers (feature/explore PlateReaders), and route options count them. They
are read here, at build time, so the phone needs no request for them and the
option works offline.

Each state's 511 site publishes its camera list; New York's is 511NY's own
map collection (core/traffic FiveElevenNyCars, October 2026), a GeoJSON
FeatureCollection of the whole state: properties id, name, public, route,
cameraOwner, views. search/build.sh downloads SOURCES[state] into tmp/ and
build_search.py loads it with --dot-cameras. A new state is a new entry here.

    dot_cameras.py --check cameras.json   shows how a file will be read
"""

import argparse
import json
import sys

CATEGORY = "dot_camera"

# State -> the camera collection its 511 map reads (whole state, no key).
SOURCES = {
    "NY": "https://api-511x-nysdot.carsprogram.org/cameras/map-features",
}

ATTRIBUTION = "Traffic camera locations: 511NY (NYSDOT)"


def cameras(path, bounds=None):
    """Yields (name, lat, lon) per public camera with a position, inside bounds
    (minlon, minlat, maxlon, maxlat) when given."""
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    features = doc.get("features") if isinstance(doc, dict) else None
    if not isinstance(features, list):
        raise ValueError(f"{path} is not a FeatureCollection")
    for feature in features:
        if not isinstance(feature, dict):
            continue
        props = feature.get("properties") or {}
        if props.get("public") is False:
            continue
        geometry = feature.get("geometry") or {}
        if geometry.get("type") != "Point":
            continue
        try:
            lon, lat = float(geometry["coordinates"][0]), float(geometry["coordinates"][1])
        except (KeyError, IndexError, TypeError, ValueError):
            continue
        if lat == 0 and lon == 0:
            continue
        if bounds and not (bounds[0] <= lon <= bounds[2] and bounds[1] <= lat <= bounds[3]):
            continue
        name = (props.get("name") or props.get("tooltip") or "").strip() or None
        yield (name[:80] if name else None), lat, lon


def load(collector, path, bounds=None):
    """Adds every camera in path as a landmark row; returns how many."""
    count = 0
    for name, lat, lon in cameras(path, bounds):
        collector.landmarks.append((CATEGORY, name, round(lat, 6), round(lon, 6), None))
        collector.landmark_count += 1
        count += 1
    print(f"    {count} DOT cameras from {path}", file=sys.stderr)
    return count


def main():
    parser = argparse.ArgumentParser(description="Show how a DOT camera file will be read.")
    parser.add_argument("--check", required=True, metavar="FILE")
    args = parser.parse_args()
    rows = list(cameras(args.check))
    for row in rows[:5]:
        print(f"  {row}")
    print(f"{len(rows)} cameras")


if __name__ == "__main__":
    main()
