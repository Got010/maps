#!/usr/bin/env python3
"""State DOT traffic cameras for the search pack's landmark table.

The app's "Avoid DOT cameras" route option routes around these as it does around
plate readers (feature/explore PlateReaders), and route options count them. They
are read here, at build time, so the phone needs no request for them and the
option works offline.

Each state's 511 publishes its camera list the way its own map reads it; the
readers here are the same ones the app's traffic layer uses (core/traffic), cut
to name and position. `dot_cameras.py fetch --region R` asks every state the
region covers (its Geofabrik sources in regions.json) and writes one GeoJSON
FeatureCollection; search/build.sh hands it to build_search.py with
--dot-cameras. A state that fails is left out with a warning, never the pack.

States without a list, and why (core/packs Coverage.kt says the same on the
phone): New Jersey (511NJ refuses GitHub's servers, where packs are built),
Ohio (OHGO's API wants a key), Missouri (MoDOT's map publishes none we can
read), DC (no 511 and no camera list in its open data).

    dot_cameras.py fetch --region md --out cameras.json   fetches a region's lists
    dot_cameras.py --check cameras.json                     shows how a file will be read
"""

import argparse
import gzip
import json
import os
import re
import sys
import urllib.parse
import urllib.request

CATEGORY = "dot_camera"

ATTRIBUTION = "Traffic camera locations: the states' 511 services"

UA = "Southbound data build (+https://github.com/StrongHorse44/maps)"

# Geofabrik extract (regions.json "sources") -> state code.
STATE_OF_SOURCE = {
    "new-york": "NY", "new-jersey": "NJ", "connecticut": "CT", "pennsylvania": "PA", "maryland": "MD",
    "delaware": "DE", "district-of-columbia": "DC", "ohio": "OH", "indiana": "IN", "illinois": "IL",
    "michigan": "MI", "wisconsin": "WI", "minnesota": "MN", "iowa": "IA", "missouri": "MO",
    "north-dakota": "ND", "south-dakota": "SD", "nebraska": "NE", "kansas": "KS",
}


def _get(url, data=None, headers=None, timeout=90):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept": "application/json", **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
    # The IBI sites gzip their answers even unasked.
    return gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw


def _json(raw):
    return json.loads(raw.decode("utf-8-sig") if isinstance(raw, bytes) else raw)


def _ok(lat, lon):
    try:
        lat, lon = float(lat), float(lon)
    except (TypeError, ValueError):
        return None
    if (lat == 0 and lon == 0) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return lat, lon


def _named(name):
    name = (name or "").strip() if isinstance(name, str) else ""
    return name[:80] or None


# Each reader takes one answer as the state's service sends it and returns [(name, lat, lon)].

def read_geojson(doc, keep=None):
    """A FeatureCollection of Points (511NY's CARS map collection, Travel Midwest, NDDOT's ArcGIS, SD511's CDN)."""
    out = []
    for f in (doc or {}).get("features") or []:
        if not isinstance(f, dict):
            continue
        props = f.get("properties") or {}
        if props.get("public") is False or (keep and not keep(props)):
            continue
        g = f.get("geometry") or {}
        if g.get("type") != "Point":
            continue
        c = g.get("coordinates") or []
        at = _ok(c[1], c[0]) if len(c) >= 2 else None
        if at:
            out.append((_named(props.get("name") or props.get("tooltip") or props.get("locDesc") or props.get("Description")), *at))
    return out


WKT_POINT = re.compile(r"POINT\s*\(\s*(-?[\d.]+)\s+(-?[\d.]+)\s*\)")


def read_ibi(doc):
    """One page of an IBI 511's camera list (511PA, CTroads, 511WI): rows with latLng.geography.wellKnownText."""
    out = []
    for row in (doc or {}).get("data") or []:
        wkt = (((row.get("latLng") or {}).get("geography")) or {}).get("wellKnownText") or ""
        m = WKT_POINT.search(wkt)
        at = _ok(m.group(2), m.group(1)) if m else None
        if at:
            out.append((_named(row.get("location") or row.get("nickname")), *at))
    return out


def read_chart(doc):
    """Maryland's CHART camera export: {"data":[{lat, lon, name, description}]}."""
    out = []
    for row in (doc or {}).get("data") or []:
        at = _ok(row.get("lat"), row.get("lon"))
        if at:
            out.append((_named(row.get("description") or row.get("name")), *at))
    return out


def read_deldot(doc):
    """DelDOT's camera file: {"videoCameras":[{lat, lon, title}]}."""
    out = []
    for row in (doc or {}).get("videoCameras") or []:
        at = _ok(row.get("lat"), row.get("lon"))
        if at:
            out.append((_named(row.get("title")), *at))
    return out


def read_cars(doc):
    """A CARS state's camera list (Indiana, Minnesota, Iowa, Nebraska, Kansas): [{name, public, location{latitude, longitude}}]."""
    out = []
    for row in doc if isinstance(doc, list) else []:
        if not isinstance(row, dict) or row.get("public") is False:
            continue
        loc = row.get("location") or {}
        at = _ok(loc.get("latitude"), loc.get("longitude"))
        if at:
            out.append((_named(row.get("name")), *at))
    return out


def read_midrive(doc):
    """Mi Drive's camera list: [{latitude, longitude, title}]."""
    out = []
    for row in doc if isinstance(doc, list) else []:
        if isinstance(row, dict):
            at = _ok(row.get("latitude"), row.get("longitude"))
            if at:
                out.append((_named(row.get("title")), *at))
    return out


def read_arcgis(doc):
    """An ArcGIS layer query as f=json (NDDOT's still cameras): features[{attributes, geometry{x, y}}]."""
    out = []
    for f in (doc or {}).get("features") or []:
        g = f.get("geometry") or {}
        at = _ok(g.get("y"), g.get("x"))
        if at:
            name = (f.get("attributes") or {}).get("Description") or ""
            out.append((_named(name.replace(" - NDDOT", "")), *at))
    return out


# Fetchers: state code -> function returning [(name, lat, lon)] for the whole state.

def _ibi(base):
    def fetch():
        rows, start = [], 0
        for _ in range(60):  # 100 rows a page; 511PA had 1,565 cameras (16 pages)
            q = urllib.parse.quote(json.dumps({"columns": [], "start": start, "length": 100}, separators=(",", ":")))
            doc = _json(_get(f"{base}/List/GetData/Cameras?query={q}"))
            page = (doc or {}).get("data") or []
            rows += read_ibi(doc)
            start += len(page)
            total = (doc or {}).get("recordsTotal")
            if not page or (isinstance(total, int) and start >= total):
                break
        return rows
    return fetch


def _cars(tg):
    return lambda: read_cars(_json(_get(f"https://{tg}.carsprogram.org/cameras_v1/api/cameras")))


ND_CAMERAS = ("https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer/5/query"
              "?where=1%3D1&outFields=Description&returnGeometry=true&outSR=4326&f=json")
SD_CAMERAS = "https://sd.cdn.iteris-atis.com/geojson/icons/metadata/icons.cameras.geojson"
SD_WEATHER_CAMERAS = "https://sd.cdn.iteris-atis.com/geojson/icons/metadata/icons.rwis.geojson"

FETCHERS = {
    "NY": lambda: read_geojson(_json(_get("https://api-511x-nysdot.carsprogram.org/cameras/map-features"))),
    "CT": _ibi("https://ctroads.org"),
    "PA": _ibi("https://www.511pa.com"),
    "MD": lambda: read_chart(_json(_get("https://chartexp1.sha.maryland.gov/CHARTExportClientService/getCameraMapDataJSON.do"))),
    "DE": lambda: read_deldot(_json(_get("https://tmc.deldot.gov/json/videocamera.json"))),
    "WI": _ibi("https://511wi.gov"),
    "IL": lambda: read_geojson(
        _json(_get("https://travelmidwest.com/lmiga/cameraMap.json", data=b"{}", headers={"Content-Type": "application/json"})),
        keep=lambda p: str(p.get("id", "")).startswith("IL-"),
    ),
    "IN": _cars("intg"),
    "MN": _cars("mntg"),
    "IA": _cars("iatg"),
    "NE": _cars("netg"),
    "KS": _cars("kstg"),
    "MI": lambda: read_midrive(_json(_get("https://mdotjboss.state.mi.us/MiDrive/camera/AllForMap"))),
    "ND": lambda: read_arcgis(_json(_get(ND_CAMERAS))),
    "SD": lambda: read_geojson(_json(_get(SD_CAMERAS))) + read_geojson(_json(_get(SD_WEATHER_CAMERAS))),
}

# The covered states with no list, and why (the phone's Coverage.kt says the same).
NO_LIST = {
    "NJ": "511NJ refuses GitHub's servers, where the packs are built",
    "OH": "OHGO's API wants a key",
    "MO": "MoDOT's traveler map publishes no camera list the build can read",
    "DC": "DC runs no 511 and publishes no camera list",
}


def states_of(region, regions):
    """The state codes a region covers, from its Geofabrik sources (else its state or default_state)."""
    r = regions[region]
    states = [STATE_OF_SOURCE[s] for s in (r.get("sources") or {}) if s in STATE_OF_SOURCE]
    if not states:
        states = [x for x in (r.get("state"), r.get("default_state")) if x]
    return states or ["NY"]


def fetch_region(region, regions, out_path, fetchers=None):
    """Writes every camera of the region's states with a list to out_path as GeoJSON; returns {state: count}."""
    fetchers = fetchers or FETCHERS
    features, counts = [], {}
    for state in states_of(region, regions):
        fetch = fetchers.get(state)
        if fetch is None:
            print(f"    {state}: no camera list ({NO_LIST.get(state, 'none known')})", file=sys.stderr)
            continue
        try:
            rows = fetch()
        except Exception as e:  # noqa: BLE001 - one state's failure leaves its cameras out, never the pack
            print(f"::warning::{state} DOT cameras could not be fetched: {type(e).__name__}: {e}", file=sys.stderr)
            continue
        counts[state] = len(rows)
        print(f"    {state}: {len(rows)} cameras", file=sys.stderr)
        for name, lat, lon in rows:
            features.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [lon, lat]},
                             "properties": {"name": name, "state": state}})
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": features}, f)
    return counts


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
    parser = argparse.ArgumentParser(description="Fetch a region's DOT camera lists, or show how a file will be read.")
    sub = parser.add_subparsers(dest="cmd")
    f = sub.add_parser("fetch", help="fetch every list the region's states publish into one GeoJSON file")
    f.add_argument("--region", required=True)
    f.add_argument("--regions", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "regions.json"))
    f.add_argument("--out", required=True)
    parser.add_argument("--check", metavar="FILE")
    args = parser.parse_args()
    if args.cmd == "fetch":
        with open(args.regions, encoding="utf-8") as fh:
            regions = json.load(fh)["regions"]
        counts = fetch_region(args.region, regions, args.out)
        print(f"{sum(counts.values())} cameras from {', '.join(counts) or 'no state'}")
        return 0 if counts else 1
    if not args.check:
        parser.error("fetch or --check FILE")
    rows = list(cameras(args.check))
    for row in rows[:5]:
        print(f"  {row}")
    print(f"{len(rows)} cameras")
    return 0


if __name__ == "__main__":
    sys.exit(main())
