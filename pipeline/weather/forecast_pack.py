#!/usr/bin/env python3
"""Builds the forecast pack: the National Weather Service 7-day forecast for a fixed grid of points.

The phone never asks a weather service about a place. A GitHub Action
(.github/workflows/weather.yml) runs this every few hours and publishes one
small file, forecast-ny.json.gz, on the prerelease tagged "weather"; every phone
downloads the same file and looks a town up in it on the phone.

How: a lattice over the box of the regions pipeline/regions.json offers (every
basemap region not marked planned: New York, New Jersey, Connecticut,
Pennsylvania, Maryland, Delaware and DC, and since October 2026 the twelve
Midwest states; 3,097 points), STEP degrees apart, keeping the points inside one of those regions (its
box, and its area where it has one). The lattice starts at that box's south-west
corner, so the points stay put while the box does. The file keeps its old name,
forecast-ny.json.gz, so apps from before the other states read it too. Each
point is looked up once with api.weather.gov/points/{lat},{lon} (its forecast
grid cell; kept in a cache file between runs); points the NWS has no forecast for
(the ocean, the lakes, Canada) drop out. Each distinct grid cell's
/gridpoints/{office}/{x},{y}/forecast gives 14 periods (day and night, 7 days).

    forecast_pack.py --out forecast-ny.json.gz [--cache points.json] [--limit N]

Output (gzip'd JSON, one line):
    {"v":1, "generated":<epoch s>, "source":"api.weather.gov", "step":0.3,
     "points":[{"lat":..,"lon":..,"periods":[[start, end, isDay, tempF, pop, icon, name, short], ...]}]}
where start/end are epoch seconds, isDay 0/1, pop a percentage or null, icon the
NWS icon code ("rain_showers", "sct"), name "Tonight", short "Chance Rain Showers".
"""

import argparse
import concurrent.futures
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://api.weather.gov"
UA = "Southbound forecast pack (+https://github.com/StrongHorse44/maps)"
STEP = 0.3
WORKERS = 6
VERSION = 1


def lattice(bounds, step=STEP):
    """Points from the south-west corner, step degrees apart, inside bounds (west, south, east, north)."""
    west, south, east, north = bounds
    points = []
    rows = int(round((north - south) / step)) + 1
    cols = int(round((east - west) / step)) + 1
    for r in range(rows):
        lat = round(south + r * step, 4)
        if lat > north + 1e-9:
            continue
        for c in range(cols):
            lon = round(west + c * step, 4)
            if lon > east + 1e-9:
                continue
            points.append((lat, lon))
    return points


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


def offered_box(regions):
    """One box (west, south, east, north) around every basemap region not marked planned."""
    boxes = [r["bounds"] for r in regions.values() if r.get("basemap") and not r.get("planned")]
    return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))


def covered(points, regions):
    """
    The points inside some basemap region of regions.json ({id: region}): its box, and its area if it has one. A
    planned region (built but not offered yet; the Midwest's were until v0.1.38) doesn't count: the pack grows with it when it ships.
    """
    basemaps = [r for r in regions.values() if r.get("basemap") and not r.get("planned")]
    return [
        (lat, lon) for lat, lon in points
        if any(in_box(r["bounds"], lon, lat) and ("area" not in r or in_area(r["area"], lon, lat)) for r in basemaps)
    ]


# How long a point /points answered 404 for is left alone before it is asked again.
MISS_DAYS = 7


def get_json(url, tries=3):
    """The parsed JSON, or None for a 404 (no forecast there). Retries other errors."""
    for attempt in range(tries):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/geo+json"})
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if attempt == tries - 1:
                raise
        except (urllib.error.URLError, TimeoutError, ValueError):
            if attempt == tries - 1:
                raise
        time.sleep(2 * (attempt + 1))
    return None


def grid_of(points_json):
    """("ALY", 72, 63) from a /points answer, or None."""
    p = (points_json or {}).get("properties") or {}
    office, x, y = p.get("gridId"), p.get("gridX"), p.get("gridY")
    if not office or x is None or y is None:
        return None
    return office, int(x), int(y)


def epoch(text):
    """ISO 8601 with offset to epoch seconds; None if absent."""
    if not text:
        return None
    return int(datetime.fromisoformat(text).timestamp())


def icon_code(url):
    """".../icons/land/day/rain_showers,20/sct?size=medium" -> "rain_showers" (the first, the period's start)."""
    if not url:
        return None
    path = url.split("?")[0]
    parts = path.split("/")
    for i, part in enumerate(parts):
        if part in ("day", "night") and i + 1 < len(parts):
            return parts[i + 1].split(",")[0] or None
    return None


def periods_of(forecast_json):
    """The compact periods of a /gridpoints/.../forecast answer."""
    out = []
    for p in ((forecast_json or {}).get("properties") or {}).get("periods") or []:
        start, end = epoch(p.get("startTime")), epoch(p.get("endTime"))
        if start is None or end is None:
            continue
        temp = p.get("temperature")
        if isinstance(temp, (int, float)) and p.get("temperatureUnit") == "C":
            temp = round(temp * 9 / 5 + 32)
        pop = (p.get("probabilityOfPrecipitation") or {}).get("value")
        out.append([
            start,
            end,
            1 if p.get("isDaytime") else 0,
            int(round(temp)) if isinstance(temp, (int, float)) else None,
            int(pop) if isinstance(pop, (int, float)) else None,
            icon_code(p.get("icon")),
            (p.get("name") or "")[:24],
            (p.get("shortForecast") or "")[:60],
        ])
    return out


def build(points, cache, fetch=get_json, workers=WORKERS, now=None):
    """{"points": [...]} for the lattice, using and filling cache.

    The cache maps "lat,lon" to the point's grid cell, [office, x, y], or to {"miss": epoch seconds} when /points
    answered 404 (over a lake, say). A miss is asked again after MISS_DAYS: a one-off 404 used to be kept for good,
    and its points never had a forecast again. The old form of a miss, null, is asked again at once.
    """
    now = time.time() if now is None else now

    def look_up(pt):
        key = f"{pt[0]},{pt[1]}"
        known = cache.get(key)
        if isinstance(known, list):
            return pt, known, False
        if isinstance(known, dict) and now - known.get("miss", 0) < MISS_DAYS * 86_400:
            return pt, None, False
        try:
            grid = grid_of(fetch(f"{API}/points/{pt[0]},{pt[1]}"))
        except Exception as e:  # noqa: BLE001 - one point's failure must not stop the pack
            print(f"points {key}: {type(e).__name__}: {e}", file=sys.stderr)
            return pt, "error", True
        return pt, list(grid) if grid else None, True

    with concurrent.futures.ThreadPoolExecutor(workers) as pool:
        looked = list(pool.map(look_up, points))
    cells = {}
    for pt, grid, asked in looked:
        if grid == "error":
            continue
        key = f"{pt[0]},{pt[1]}"
        if grid:
            cache[key] = grid
            cells.setdefault(tuple(grid), []).append(pt)
        elif asked:
            cache[key] = {"miss": int(now)}

    def forecast(cell):
        office, x, y = cell
        try:
            return cell, periods_of(fetch(f"{API}/gridpoints/{office}/{x},{y}/forecast"))
        except Exception as e:  # noqa: BLE001
            print(f"forecast {office}/{x},{y}: {type(e).__name__}: {e}", file=sys.stderr)
            return cell, []

    with concurrent.futures.ThreadPoolExecutor(workers) as pool:
        forecasts = dict(pool.map(forecast, list(cells)))
    # A cell whose forecast failed every try leaves its points out of this pack: said, not silent.
    failed = sum(1 for cell in cells if not forecasts.get(cell))
    if failed:
        print(f"::warning::{failed} of {len(cells)} grid cells had no forecast this run; their points are left out",
              file=sys.stderr)
    out = []
    for cell, pts in cells.items():
        periods = forecasts.get(cell) or []
        if not periods:
            continue
        for lat, lon in pts:
            out.append({"lat": lat, "lon": lon, "periods": periods})
    out.sort(key=lambda p: (p["lat"], p["lon"]))
    return {"points": out, "cells": len(cells), "looked_up": len(points), "failed_cells": failed}


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--cache", help="the points -> grid cell cache, read and rewritten")
    parser.add_argument("--region", help="a regions.json box to cover instead of the offered regions' (points outside every offered region drop out)")
    parser.add_argument("--step", type=float, default=STEP)
    parser.add_argument("--limit", type=int, help="only N points from the middle of the lattice (a quick check)")
    parser.add_argument("--print-raw", action="store_true", help="print one cell's raw forecast answer (for fixtures)")
    args = parser.parse_args()

    with open(os.path.join(HERE, "..", "regions.json")) as f:
        regions = json.load(f)["regions"]
    box = tuple(regions[args.region]["bounds"]) if args.region else offered_box(regions)
    points = covered(lattice(box, args.step), regions)
    if args.limit:
        points = points[len(points) // 2:][: args.limit]
    cache = {}
    if args.cache and os.path.exists(args.cache):
        with open(args.cache) as f:
            cache = json.load(f)
    started = time.time()
    result = build(points, cache)
    if args.cache:
        with open(args.cache, "w") as f:
            json.dump(cache, f)
    pack = {"v": VERSION, "generated": int(time.time()), "source": "api.weather.gov", "step": args.step,
            "points": result["points"]}
    raw = json.dumps(pack, separators=(",", ":")).encode("utf-8")
    with gzip.open(args.out, "wb", compresslevel=9) as f:
        f.write(raw)
    print(f"{len(points)} lattice points, {result['cells']} grid cells, {len(pack['points'])} points with a forecast; "
          f"{len(raw)} bytes, {os.path.getsize(args.out)} gzipped, {time.time() - started:.0f} s")
    if not pack["points"]:
        raise SystemExit("no forecast at all: not publishing an empty pack")
    sample = pack["points"][len(pack["points"]) // 2]
    print("sample point:", json.dumps(sample)[:1500])
    if args.print_raw:
        lat, lon = sample["lat"], sample["lon"]
        grid = cache.get(f"{lat},{lon}")
        if grid:
            raw_answer = get_json(f"{API}/gridpoints/{grid[0]}/{grid[1]},{grid[2]}/forecast")
            print(f"RAW {grid}:", json.dumps(raw_answer, separators=(",", ":")))


if __name__ == "__main__":
    main()
