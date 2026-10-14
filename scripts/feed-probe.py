#!/usr/bin/env python3
"""Prints what the live-data hosts really send, for fixtures and checks.

The dev container can't reach 511NY, NYC Open Data or gis.ny.gov, but a
GitHub runner can, so .github/workflows/feed-probe.yml runs this and the
answers end up in the job log. No key is used: 511NY is asked with a dummy
key (to see its error document) and for its public API docs. Nothing here
carries a location, search text or route.

    feed-probe.py [section ...]   sections: nycdot 511ny 511ny-new 511ny-travel addresses
                                  weather-alerts weather-forecast weather-radar weather-current weather-states
                                  dot-ct dot-nj dot-pa dot-md dot-de dot-dc ibi-join chart-more deldot-more
                                  nj-config (default: all)
"""

import gzip
import html
import json
import os
import re
import signal
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA = "Southbound feed probe (+https://github.com/StrongHorse44/maps)"


def _text(raw):
    """Body bytes as text; some 511 sites gzip a reply the request didn't ask to be compressed."""
    if raw[:2] == b"\x1f\x8b":
        try:
            raw = gzip.decompress(raw)
        except (OSError, EOFError):
            pass
    return raw.decode("utf-8", "replace")


def get(url, limit=4_000_000, method="GET", headers=None, data=None):
    """(status, final url, headers, body text) without raising on HTTP errors. [data] (bytes) makes it a POST."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*", **(headers or {})}, method=method, data=data)
    print(f"... {method} {url}", flush=True)
    signal.alarm(45)  # a whole request, not just one socket read
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read(limit) if method in ("GET", "POST") else b""
            return r.status, r.geturl(), dict(r.headers), _text(body)
    except urllib.error.HTTPError as e:
        return e.code, url, dict(e.headers), _text(e.read(limit))
    except Exception as e:  # noqa: BLE001 - a probe reports every failure
        return None, url, {}, f"{type(e).__name__}: {e}"
    finally:
        signal.alarm(0)


def text_of(page):
    page = re.sub(r"(?is)<(script|style)\b.*?</\1>", " ", page)
    page = re.sub(r"(?i)<br\s*/?>|</(p|div|tr|li|h\d|pre)>", "\n", page)
    page = re.sub(r"(?i)</t[dh]>", " | ", page)
    page = html.unescape(re.sub(r"<[^>]+>", " ", page))
    lines = (re.sub(r"[ \t]+", " ", line).strip() for line in page.splitlines())
    return "\n".join(line for line in lines if line)


def head(title):
    print(f"\n{'=' * 8} {title} {'=' * 8}", flush=True)


def show(url, chars=1500, label=None):
    status, final, headers, body = get(url)
    print(f"--- {label or url}\nstatus {status}  type {headers.get('Content-Type')}  final {final}")
    print(body[:chars])
    return status, body


def nycdot():
    head("NYC DOT i4gi-tjb9")
    base = "https://data.cityofnewyork.us/resource/i4gi-tjb9.json"
    status, body = show(base + "?%24order=data_as_of%20DESC&%24limit=4", chars=6000, label="4 newest rows, all columns")
    show(base + "?%24select=max(data_as_of),min(data_as_of),count(*)", label="newest/oldest/count")
    # Links seen over the last day of readings (a group-by over the whole table times out).
    _, _, _, b = get(base + "?%24select=link_id,count(*)&%24where=data_as_of%3E%272026-10-06T00:00:00%27&%24group=link_id&%24limit=5000")
    try:
        print("links reporting since Oct 6:", len(json.loads(b)))
    except ValueError:
        print("link count not JSON:", b[:300])
    # The app's own query, to measure what one fetch returns.
    app = (base + "?%24select=id,speed,travel_time,status,data_as_of,link_id,link_points,link_name,borough"
           "&%24order=data_as_of%20DESC&%24limit=1200")
    _, _, headers, b = get(app)
    try:
        rows = json.loads(b)
        links = {r.get("link_id") for r in rows}
        times = sorted({r.get("data_as_of") for r in rows})
        print(f"app query: {len(rows)} rows, {len(b)} bytes, {len(links)} links, data_as_of {times[:1]} .. {times[-1:]}")
        print("value types of first row:", {k: type(v).__name__ for k, v in rows[0].items()} if rows else None)
        print("status values:", sorted({r.get('status') for r in rows}, key=str)[:10])
        print("boroughs:", sorted({r.get('borough') for r in rows}, key=str))
    except (ValueError, IndexError) as e:
        print("app query failed:", e, b[:300])
    show("https://data.cityofnewyork.us/api/views/i4gi-tjb9.json", chars=0, label="dataset metadata")
    _, _, _, meta = get("https://data.cityofnewyork.us/api/views/i4gi-tjb9.json")
    try:
        m = json.loads(meta)
        print("rowsUpdatedAt", m.get("rowsUpdatedAt"), "columns", [(c.get("fieldName"), c.get("dataTypeName")) for c in m.get("columns", [])])
    except ValueError:
        pass
    from datetime import datetime
    from zoneinfo import ZoneInfo
    print("now in New York:", datetime.now(ZoneInfo("America/New_York")).strftime("%Y-%m-%dT%H:%M:%S"))


def cars(path, n=2):
    """511NY's new site (CARS program, October 2026) reads these whole-state collections."""
    url = "https://api-511x-nysdot.carsprogram.org/" + path
    status, final, headers, body = get(url, limit=40_000_000)
    print(f"--- {url}\nstatus {status} type {headers.get('Content-Type')} bytes {len(body)} "
          f"cache {headers.get('Cache-Control')} cors {headers.get('Access-Control-Allow-Origin')}")
    try:
        doc = json.loads(body)
    except ValueError:
        print(body[:1500])
        return
    rows = doc
    if isinstance(doc, dict):
        print("object keys:", list(doc)[:30])
        rows = next((v for v in doc.values() if isinstance(v, list)), [])
    print("rows:", len(rows))
    keys = {}
    for r in rows if isinstance(rows, list) else []:
        if isinstance(r, dict):
            for k, v in r.items():
                keys.setdefault(k, set()).add(type(v).__name__)
    print("fields:", {k: sorted(v) for k, v in keys.items()})
    for r in rows[:n]:
        print(json.dumps(r)[:5000])
    return rows


def fiveeleven_new():
    """511NY's new site reads {api}/map-features?{layer query} per layer (and {api}/hash to skip unchanged
    ones); found in its config (demo.511ny.org/configs/main.json) and main bundle, October 2026."""
    head("511NY new platform: map-features")
    cars_features("https://api-511x-nysdot.carsprogram.org/")


def cars_features(base, limit_keys=80):
    """What a CARS-platform API (api-511x-<agency>.carsprogram.org) answers for the map-features the sites read."""
    from collections import Counter
    base = base.rstrip("/") + "/"
    for path, query in (("events", "eventClassifications=incidents"),
                        ("events", "eventClassifications=closures&maxBeginDateOffset=300000"),
                        ("events", "eventClassifications=construction&maxBeginDateOffset=300000"),
                        ("events", ""), ("signs", ""), ("cameras", "")):
        url = f"{base}{path}/map-features" + (f"?{query}" if query else "")
        status, _, h, body = get(url, limit=60_000_000)
        print(f"--- {url}\nstatus {status} type {h.get('Content-Type')} bytes {len(body)} encoding {h.get('Content-Encoding')}")
        try:
            doc = json.loads(body)
        except ValueError:
            print(body[:800])
            continue
        feats = doc.get("features") if isinstance(doc, dict) else doc
        if not isinstance(feats, list):
            print("top:", list(doc)[:20] if isinstance(doc, dict) else type(doc).__name__, json.dumps(doc)[:1500])
            continue
        print("features:", len(feats), "top keys:", list(doc)[:10] if isinstance(doc, dict) else "list")
        geoms = Counter((f.get("geometry") or {}).get("type") for f in feats if isinstance(f, dict))
        print("geometry types:", geoms)
        keys = Counter(k for f in feats if isinstance(f, dict) for k in (f.get("properties") or {}))
        print("property keys:", keys.most_common(limit_keys))
        for field in ("eventType", "icon", "priority", "eventClassification", "type", "status", "layer"):
            values = Counter(str((f.get("properties") or {}).get(field)) for f in feats if isinstance(f, dict))
            if len(values) > 1 or "None" not in values:
                print(f"{field}:", values.most_common(30))
        for f in feats[:3]:
            print(json.dumps(f)[:6000])
        _, _, _, hb = get(f"{base}{path}/hash" + (f"?{query}" if query else ""))
        print("hash:", hb[:200])


def fiveeleven_travel():
    """The other speed-like layers in 511ny.org's config: bridge & tunnel travel times (its own API) and
    "traffic speeds" (a map function, toggleTrafficSpeeds)."""
    head("511NY travel times")
    from collections import Counter
    _, _, _, cfg = get("https://demo.511ny.org/configs/main.json?v=1.8.5")
    try:
        conf = json.loads(cfg)
    except ValueError:
        conf = {}
    for layer in conf.get("layers", []):
        if layer.get("name") in ("bridge & tunnel travel times", "traffic speeds"):
            print("layer:", json.dumps({k: v for k, v in layer.items() if k != "legend"}))
    print("apis:", json.dumps(conf.get("apis", {}))[:3000])
    base = "https://nysdot-special-layers.carsprogram.org/api/v1/"
    # Like the other layers, the site reads {api}/map-features (the bare path is a 404).
    for path in ("travel-times/map-features", "border-crossings/map-features", "travel-times/hash"):
        status, _, h, body = get(base + path, limit=40_000_000)
        print(f"--- {base}{path}\nstatus {status} type {h.get('Content-Type')} bytes {len(body)} cache {h.get('Cache-Control')}")
        try:
            doc = json.loads(body)
        except ValueError:
            print(body[:800])
            continue
        rows = doc.get("features") if isinstance(doc, dict) and "features" in doc else doc
        if isinstance(doc, dict) and not isinstance(rows, list):
            print("keys:", list(doc)[:30])
            rows = next((v for v in doc.values() if isinstance(v, list)), None)
        if not isinstance(rows, list):
            print(json.dumps(doc)[:3000])
            continue
        print("rows:", len(rows))
        props = [r.get("properties", r) if isinstance(r, dict) else r for r in rows]
        keys = Counter(k for p in props if isinstance(p, dict) for k in p)
        print("fields:", keys.most_common(60))
        for r in rows[:4]:
            print(json.dumps(r)[:4000])
        geoms = Counter((r.get("geometry") or {}).get("type") for r in rows if isinstance(r, dict))
        print("geometry:", geoms)
    # How the site uses them: the script around each name.
    _, _, _, page = get("https://511ny.org/")
    for script in re.findall(r'src="(/assets/index-[^"]+\.js)"', page)[:1]:
        _, _, _, js = get("https://511ny.org" + script, limit=40_000_000)
        for word in ("toggleTrafficSpeeds", "travelTimes", "travel-times", "TrafficLayer"):
            for m in list(re.finditer(re.escape(word), js))[:3]:
                print(f"  [{word}] ...{js[max(0, m.start() - 300):m.end() + 300]!r}")


def fiveeleven():
    head("511NY")
    # 511ny.org answered every path, /api/ included, with its new web app's HTML (October 2026):
    # find where that app gets its data and whether the old API lives on elsewhere.
    status, final, _, page = get("https://511ny.org/")
    print("root", status, final, len(page), "chars")
    print(page[:6000])
    urls = sorted(set(re.findall(r"""https?://[^\s"'<>)]+""", page)) | set(re.findall(r'(?:src|href)="([^"]+)"', page)))
    for u in urls:
        print("url", u)
    status, body = show("https://demo.511ny.org/configs/main.json?v=1.8.5", chars=20000, label="app config")
    for u in sorted(set(re.findall(r"""https?://[^\s"'<>]+""", body))):
        print("config url", u)
    for script in [u for u in urls if u.endswith(".js")][:6]:
        _, _, _, js = get(urllib.parse.urljoin("https://511ny.org/", script))
        found = sorted(set(re.findall(r"""["'`](/?(?:api|Api|API)[^"'`]{0,80})["'`]""", js)) |
                       set(re.findall(r"""https?://[a-z0-9.-]*511[^"'`\s]{0,80}""", js)))
        print(f"js {script}: {len(js)} chars, api-like strings:", found[:80])
    for url in ("https://ny.ibi511.com/api/getevents?key=PROBE&format=json",
                "https://ny.ibi511.com/api/v2/get/event?key=PROBE&format=json",
                "https://www.511ny.org/api/getevents?key=PROBE&format=json",
                "https://api.511ny.org/api/getevents?key=PROBE&format=json",
                "https://old.511ny.org/api/getevents?key=PROBE&format=json",
                "https://legacy.511ny.org/api/getevents?key=PROBE&format=json",
                "https://511ny.org/developers", "https://511ny.org/developers/doc",
                "https://511ny.org/api", "https://511ny.org/api/v2"):
        show(url, chars=400)


def addresses():
    head("NYS Address Points")
    status, final, _, page = get("https://gis.ny.gov/streets-addresses")
    print("page status", status, final)
    links = sorted(set(re.findall(r'href="([^"]+)"', page)))
    picked = [l for l in links if re.search(r"(?i)zip|gdb|shp|download|address|hub|arcgis|fileserver|gisdata", l)]
    for link in picked:
        print("link", html.unescape(link))
    print("page text about points:")
    for line in text_of(page).splitlines():
        if re.search(r"(?i)address point|statewide|download|gdb|shape", line):
            print("  ", line[:300])
    for link in picked:
        link = urllib.parse.urljoin(final, html.unescape(link))
        if re.search(r"(?i)\.zip|fileserver|/download", link):
            s, f, h, _ = get(link, method="HEAD")
            print(f"HEAD {link}\n  -> {s} {f} type {h.get('Content-Type')} length {h.get('Content-Length')} "
                  f"modified {h.get('Last-Modified')} disposition {h.get('Content-Disposition')}")
    svc = "https://nysgeohub.ny.gov/arcgis/rest/services/Address/NYS_Address_Points/FeatureServer/0"
    _, _, _, b = get(svc + "?f=pjson")
    try:
        layer = json.loads(b)
        print("service layer", layer.get("name"), "fields:")
        for f in layer.get("fields", []):
            print("  ", f.get("name"), f.get("type"), f.get("alias"))
    except ValueError:
        print("service not JSON:", b[:300])
    show(svc + "/query?where=1%3D1&outFields=*&resultRecordCount=3&f=json&returnGeometry=true&outSR=4326",
         chars=4000, label="3 sample points")
    show(svc + "/query?where=1%3D1&returnCountOnly=true&f=json", chars=200, label="count")
    show("https://data.gis.ny.gov/api/search/v1/collections/all/items?q=address%20points&limit=10", chars=0, label="hub search")
    _, _, _, b = get("https://data.gis.ny.gov/api/search/v1/collections/all/items?q=address%20points&limit=10")
    try:
        for feat in json.loads(b).get("features", []):
            p = feat.get("properties", {})
            print("hub item", feat.get("id"), "|", p.get("title"), "|", p.get("type"), "|", p.get("url"), "|", p.get("modified"))
    except ValueError:
        print("hub search not JSON", b[:200])

# --- Weather (October 2026): which sources can be pulled whole for New York, so no request says where the
# driver is. The single Albany point below is only for the probe, to see a forecast's shape.

NY_BOX = (-79.8, 40.4, -71.8, 45.1)  # west, south, east, north: the state with a margin


def _json(body):
    try:
        return json.loads(body)
    except ValueError:
        return None


def weather_alerts():
    head("NWS active alerts, all of New York")
    status, final, headers, body = get("https://api.weather.gov/alerts/active?area=NY", limit=20_000_000)
    print(f"status {status}  type {headers.get('Content-Type')}  bytes {len(body)}  cache {headers.get('Cache-Control')}"
          f"  expires {headers.get('Expires')}  last-modified {headers.get('Last-Modified')}")
    doc = _json(body)
    if not isinstance(doc, dict):
        print(body[:800])
        return
    feats = doc.get("features") or []
    print("features", len(feats), "| updated", doc.get("updated"), "| title", doc.get("title"))
    shapes = {}
    for f in feats:
        g = f.get("geometry")
        shapes[g.get("type") if g else None] = shapes.get(g.get("type") if g else None, 0) + 1
    print("geometry types", shapes)
    for f in feats[:8]:
        p = f.get("properties", {})
        print("-", p.get("event"), "|", p.get("severity"), p.get("urgency"), p.get("certainty"), "|", p.get("messageType"),
              "|", (p.get("areaDesc") or "")[:100], "| zones", len(p.get("affectedZones") or []),
              "| onset", p.get("onset"), "| ends", p.get("ends") or p.get("expires"))
        print("  headline:", (p.get("headline") or "")[:160])
        print("  keys:", sorted(p.keys()))
    if feats:
        print("first feature, raw:", json.dumps(feats[0])[:2500])
    # New York may have none today: show the shape of real ones from anywhere, for fixtures (one with a
    # polygon, one with only zones), compacted.
    status, _, headers, body = get("https://api.weather.gov/alerts/active?status=actual&message_type=alert", limit=40_000_000)
    us = (_json(body) or {}).get("features") or []
    print("US active alerts", len(us), "bytes", len(body))
    events = {}
    for f in us:
        e = f.get("properties", {}).get("event")
        events[e] = events.get(e, 0) + 1
    print("events", sorted(events.items(), key=lambda kv: -kv[1])[:25])
    with_shape = [f for f in us if f.get("geometry")]
    zone_only = [f for f in us if not f.get("geometry")]
    for label, pick in (("polygon", with_shape[:2]), ("zones only", zone_only[:2])):
        for f in pick:
            p = dict(f.get("properties", {}))
            p.pop("@id", None)
            p["description"] = (p.get("description") or "")[:400]
            p["instruction"] = (p.get("instruction") or "")[:200]
            p.pop("parameters", None)
            f = {"id": f.get("id"), "type": "Feature", "geometry": f.get("geometry"), "properties": p}
            print(f"SAMPLE {label}:", json.dumps(f, separators=(",", ":"))[:6000])
    # The zones an alert names (affectedZones) need shapes on the phone when an alert has no polygon.
    for url in ("https://api.weather.gov/zones?area=NY&type=forecast",
                "https://api.weather.gov/zones?area=NY&type=county"):
        status, _, headers, body = get(url, limit=20_000_000)
        doc = _json(body) or {}
        zs = doc.get("features") or []
        print(f"--- {url}\nstatus {status} bytes {len(body)} zones {len(zs)}",
              [z.get("properties", {}).get("id") for z in zs[:12]], "| geometry in list:", bool(zs and zs[0].get("geometry")))
    show("https://api.weather.gov/zones/forecast/NYZ052", 800, "one forecast zone with its shape (Albany County)")
    # NWS publishes zone shapefiles too (pipeline could ship them in a pack).
    status, _, headers, body = get("https://www.weather.gov/gis/PublicZones", limit=600_000)
    links = sorted(set(re.findall(r'href="([^"]*z_[^"]*\.zip)"', body)))
    print("PublicZones status", status, "zips:", links[:10])


def weather_forecast():
    head("NWS forecast, the shape of one point and one zone")
    status, body = show("https://api.weather.gov/points/42.6526,-73.7562", 1200, "points (Albany, probe only)")
    props = (_json(body) or {}).get("properties", {})
    print("gridId", props.get("gridId"), props.get("gridX"), props.get("gridY"), "| forecastZone", props.get("forecastZone"),
          "| county", props.get("county"))
    for key in ("forecast", "forecastHourly"):
        url = props.get(key)
        if url:
            status, _, headers, b = get(url, limit=4_000_000)
            d = _json(b) or {}
            periods = d.get("properties", {}).get("periods") or []
            print(f"--- {key}: status {status} bytes {len(b)} periods {len(periods)} cache {headers.get('Cache-Control')}")
            for per in periods[:3]:
                print("  ", json.dumps(per)[:600])
    # A whole-zone forecast, the text the pack would carry: one request per zone, about 60 for New York.
    status, _, headers, b = get("https://api.weather.gov/zones/forecast/NYZ052/forecast")
    d = _json(b) or {}
    periods = d.get("properties", {}).get("periods") or []
    print(f"--- zone forecast NYZ052: status {status} bytes {len(b)} periods {len(periods)} cache {headers.get('Cache-Control')}")
    for per in periods[:4]:
        print("  ", json.dumps(per)[:500])
    show("https://api.weather.gov/products/types/ZFP/locations/ALY", 600, "zone forecast product list (ALY)")

    head("Open-Meteo, many fixed points in one request")
    # A coarse fixed lattice over the state: the same request from every phone.
    lats, lons = [], []
    lat = NY_BOX[1]
    while lat <= NY_BOX[3]:
        lon = NY_BOX[0]
        while lon <= NY_BOX[2]:
            lats.append(f"{lat:.2f}")
            lons.append(f"{lon:.2f}")
            lon += 0.5
        lat += 0.5
    q = urllib.parse.urlencode({
        "latitude": ",".join(lats), "longitude": ",".join(lons),
        "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max",
        "forecast_days": 7, "timezone": "America/New_York", "temperature_unit": "fahrenheit", "wind_speed_unit": "mph",
    })
    url = "https://api.open-meteo.com/v1/forecast?" + q
    print("points", len(lats), "url length", len(url))
    status, _, headers, b = get(url, limit=30_000_000)
    d = _json(b)
    print(f"status {status} bytes {len(b)} type {headers.get('Content-Type')}")
    if isinstance(d, list):
        print("locations", len(d), "first:", json.dumps(d[0])[:900])
    else:
        print(b[:800])
    show("https://open-meteo.com/en/terms", 1500, "Open-Meteo terms")

    head("NDFD grids, northeast sector (what a GitHub Action could decode)")
    base = "https://tgftp.nws.noaa.gov/SL.us008001/ST.opnl/DF.gr2/DC.ndfd/AR.neast"
    for vp in ("VP.001-003", "VP.004-007"):
        for el in ("ds.maxt.bin", "ds.mint.bin", "ds.pop12.bin", "ds.wx.bin", "ds.sky.bin", "ds.wspd.bin"):
            status, _, headers, _ = get(f"{base}/{vp}/{el}", method="HEAD")
            print(f"  {vp}/{el}: {status} {headers.get('Content-Length')} bytes, {headers.get('Last-Modified')}")


def weather_radar():
    head("Radar as one image of the whole state")
    w, s, e, n = NY_BOX
    wms = ("https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?service=WMS&version=1.3.0&request=GetMap"
           f"&layers=conus_bref_qcd&styles=&crs=EPSG:4326&bbox={s},{w},{n},{e}&width=1024&height=600"
           "&format=image/png&transparent=true")
    rest = ("https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer/export"
            f"?bbox={w},{s},{e},{n}&bboxSR=4326&imageSR=4326&size=1024,600&format=png32&transparent=true&f=image")
    # In the map's own projection (Web Mercator), so the image lines up when drawn between its corners.
    import math

    def merc(lon, lat):
        x = lon * 20037508.34 / 180
        y = math.log(math.tan((90 + lat) * math.pi / 360)) * 6378137
        return x, y

    (x0, y0), (x1, y1) = merc(w, s), merc(e, n)
    wms3857 = ("https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?service=WMS&version=1.3.0&request=GetMap"
               f"&layers=conus_bref_qcd&styles=&crs=EPSG:3857&bbox={x0:.0f},{y0:.0f},{x1:.0f},{y1:.0f}&width=1024&height=820"
               "&format=image/png&transparent=true")
    rest3857 = ("https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer/export"
                f"?bbox={w},{s},{e},{n}&bboxSR=4326&imageSR=3857&size=1024,820&format=png32&transparent=true&f=image")
    for label, url in (("NCEP MRMS WMS", wms), ("NOAA mapservices export", rest),
                       ("NCEP MRMS WMS 3857", wms3857), ("NOAA mapservices export 3857", rest3857)):
        status, final, headers, body = get(url, limit=10_000_000)
        print(f"--- {label}: status {status} type {headers.get('Content-Type')} bytes {len(body)} cache {headers.get('Cache-Control')}"
              f" last-modified {headers.get('Last-Modified')} png {body[1:4] == 'PNG'}")
        if not (headers.get("Content-Type") or "").startswith("image/"):
            print(body[:600])
    status, _, headers, body = get(
        "https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?service=WMS&version=1.3.0&request=GetCapabilities",
        limit=3_000_000)
    print("WMS capabilities status", status, "bytes", len(body))
    for m in re.findall(r"<Dimension[^>]*>[^<]{0,300}", body)[:3]:
        print("  ", m[:300])
    status, _, headers, body = get(
        "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity/MapServer?f=json",
        limit=2_000_000)
    d = _json(body) or {}
    print("mapservices info status", status, "| timeInfo", json.dumps(d.get("timeInfo"))[:400], "| copyright", d.get("copyrightText"))


def weather_current():
    head("Current conditions for the whole state in one request")
    # aviationweather.gov (NWS Aviation Weather Center): every New York METAR at once (@NY), no key.
    for url in ("https://aviationweather.gov/api/data/metar?ids=@NY&format=json",
                "https://aviationweather.gov/api/data/stationinfo?ids=@NY&format=json"):
        status, _, headers, body = get(url, limit=8_000_000)
        print(f"--- {url}\nstatus {status} type {headers.get('Content-Type')} bytes {len(body)} cache {headers.get('Cache-Control')}")
        d = _json(body)
        if isinstance(d, list):
            print("items", len(d))
            for item in d[:3]:
                print("  ", json.dumps(item)[:900])
            if d and "temp" in d[0]:
                keys = sorted({k for item in d for k in item})
                print("keys:", keys)
                print("wxString values:", sorted({str(item.get("wxString")) for item in d})[:40])
                print("cover values:", sorted({str(item.get("cover")) for item in d}))
                print("stations:", [(item.get("icaoId"), item.get("name"), item.get("lat"), item.get("lon")) for item in d][:80])
        else:
            print(body[:600])
    # Open-Meteo current conditions for fixed points (the same request from every phone).
    lats, lons = [], []
    lat = NY_BOX[1]
    while lat <= NY_BOX[3]:
        lon = NY_BOX[0]
        while lon <= NY_BOX[2]:
            lats.append(f"{lat:.2f}")
            lons.append(f"{lon:.2f}")
            lon += 0.5
        lat += 0.5
    q = urllib.parse.urlencode({
        "latitude": ",".join(lats), "longitude": ",".join(lons),
        "current": "temperature_2m,weather_code,is_day,wind_speed_10m",
        "timezone": "America/New_York", "temperature_unit": "fahrenheit", "wind_speed_unit": "mph",
    })
    status, _, headers, b = get("https://api.open-meteo.com/v1/forecast?" + q, limit=30_000_000)
    d = _json(b)
    print(f"--- Open-Meteo current, {len(lats)} points: status {status} bytes {len(b)}")
    if isinstance(d, list):
        print("first:", json.dumps(d[0])[:700])
    else:
        print(b[:600])


# The regions beyond New York (owner, October 2026): every state Southbound covers, for the same weather and DOT
# features as New York. A fixed list, the same request for every phone.
STATES = ["NY", "NJ", "CT", "PA", "MD", "DE", "DC"]
ALL_BOX = (-80.6, 37.8, -71.7, 45.1)  # west, south, east, north: every covered state with a margin


def weather_states():
    head("NWS alerts for every covered state in one request")
    url = "https://api.weather.gov/alerts/active?area=" + ",".join(STATES)
    status, _, headers, body = get(url, limit=40_000_000)
    feats = ((_json(body) or {}).get("features")) or []
    print(f"status {status} bytes {len(body)} alerts {len(feats)} type {headers.get('Content-Type')}")
    if status != 200:
        print(body[:800])
    per = {}
    for f in feats:
        for z in f.get("properties", {}).get("geocode", {}).get("UGC", []) or []:
            per[z[:2]] = per.get(z[:2], 0) + 1
    print("zones named, by state code:", per)
    for state in STATES:
        st, _, _, b = get("https://api.weather.gov/alerts/active?area=" + state, limit=20_000_000)
        print(f"  {state}: status {st} alerts {len(((_json(b) or {}).get('features')) or [])} bytes {len(b)}")

    head("METAR for every covered state")
    w, s_, e, n = ALL_BOX

    def metar(url):
        status, _, headers, body = get(url, limit=10_000_000)
        d = _json(body)
        print(f"--- {url}\nstatus {status} bytes {len(body)} type {headers.get('Content-Type')}")
        if not isinstance(d, list):
            print(body[:600])
            return {}
        states = {}
        for item in d:
            name = item.get("name") or ""
            code = name.rsplit(",", 2)[-2].strip() if name.count(",") >= 2 else "?"
            states[code] = states.get(code, 0) + 1
        print("stations", len(d), "by state:", dict(sorted(states.items())))
        return {item.get("icaoId"): item for item in d if isinstance(item, dict)}

    # One group per request is known to work (ids=@NY); several in one list returned New York's alone.
    by_state = {st: metar(f"https://aviationweather.gov/api/data/metar?ids=@{st}&format=json") for st in STATES}
    for sep in ("%20", "+", ";"):
        metar("https://aviationweather.gov/api/data/metar?ids=" + sep.join("@" + st for st in STATES) + "&format=json")
    # A box: which of a state's stations it leaves out, and whether a smaller box or more hours change that.
    for label, (bw, bs, be, bn), extra in (("every state", ALL_BOX, ""), ("every state, 3 hours", ALL_BOX, "&hours=3"),
                                            ("New York's old box", (-79.8, 40.4, -71.8, 45.1), "")):
        print(f"--- box: {label}")
        got = metar(f"https://aviationweather.gov/api/data/metar?bbox={bs},{bw},{bn},{be}&format=json{extra}")
        for st, stations in by_state.items():
            missing = sorted(set(stations) - set(got))
            if missing and (label != "New York's old box" or st == "NY"):
                print(f"   {st}: {len(missing)} of {len(stations)} not in the box:",
                      [(i, stations[i].get("lat"), stations[i].get("lon"), stations[i].get("obsTime")) for i in missing][:20])
    sample = next(iter(by_state.get("NY", {}).values()), None)
    print("   one station:", json.dumps(sample)[:900])

    head("Radar as one picture of every covered state")
    import math

    def merc(lon, lat):
        return lon * 20037508.34 / 180, math.log(math.tan((90 + lat) * math.pi / 360)) * 6378137

    (x0, y0), (x1, y1) = merc(w, s_), merc(e, n)
    for width, height in ((1024, 1100), (1280, 1376)):
        url = ("https://opengeo.ncep.noaa.gov/geoserver/conus/conus_bref_qcd/ows?service=WMS&version=1.3.0&request=GetMap"
               f"&layers=conus_bref_qcd&styles=&crs=EPSG:3857&bbox={x0:.0f},{y0:.0f},{x1:.0f},{y1:.0f}&width={width}&height={height}"
               "&format=image/png&transparent=true")
        status, _, headers, body = get(url, limit=10_000_000)
        print(f"--- {width}x{height}: status {status} type {headers.get('Content-Type')} bytes {len(body)} png {body[1:4] == 'PNG'}")

    head("NWS forecast grid at a point in each state")
    for name, lat, lon in (("Philadelphia", 39.9526, -75.1652), ("Pittsburgh", 40.4406, -79.9959), ("Hartford", 41.7658, -72.6734),
                           ("Trenton", 40.2206, -74.7597), ("Baltimore", 39.2904, -76.6122), ("Dover", 39.1582, -75.5244),
                           ("Washington", 38.8951, -77.0364)):
        status, _, _, body = get(f"https://api.weather.gov/points/{lat},{lon}")
        p = (_json(body) or {}).get("properties", {})
        print(f"  {name}: status {status} grid {p.get('gridId')} {p.get('gridX')},{p.get('gridY')} zone {p.get('forecastZone')}")


def _urls_in(text, limit=120):
    """Absolute URLs and /api-like paths a page or script mentions."""
    found = set(re.findall(r"""https?://[^\s"'<>)\\]+""", text))
    found |= set(re.findall(r"""["'](/(?:api|map|List|configs?|data|json|services?)[^"'\s<>]{1,120})["']""", text))
    return sorted(found)[:limit]


def discover(site, scripts=6):
    """A 511 site's home page: the scripts and APIs it names, and the config files of a CARS site."""
    status, final, headers, page = get(site, limit=4_000_000)
    print(f"--- {site}: status {status} final {final} bytes {len(page)} type {headers.get('Content-Type')}")
    urls = _urls_in(page)
    for u in urls:
        print("   page mentions", u)
    srcs = [urllib.parse.urljoin(final, u) for u in re.findall(r'<script[^>]+src="([^"]+)"', page)]
    print("   scripts:", srcs[:20])
    seen = set(urls)
    for src in srcs[:scripts]:
        _, _, _, js = get(src, limit=30_000_000)
        for u in _urls_in(js, 200):
            if u not in seen and any(k in u for k in ("api", "carsprogram", "json", "map", "camera", "event", "incident", "sign", "List")):
                seen.add(u)
                print("   script mentions", u)
    return final, page


def try_json(url, label=None, sample=900):
    """GET and say what came back: size, top-level shape, a sample item."""
    status, final, headers, body = get(url, limit=40_000_000)
    d = _json(body)
    shape = type(d).__name__
    items = None
    if isinstance(d, dict):
        for key in ("features", "items", "data", "Data", "result", "results", "events", "cameras", "signs"):
            if isinstance(d.get(key), list):
                items = d[key]
                shape = f"dict[{key}]"
                break
        if items is None:
            shape = "dict keys " + ",".join(list(d.keys())[:12])
    elif isinstance(d, list):
        items = d
    print(f"--- {label or url}\nstatus {status} type {headers.get('Content-Type')} bytes {len(body)} final {final} shape {shape}"
          + (f" items {len(items)}" if items is not None else ""))
    if items:
        print("   first:", json.dumps(items[0])[:sample])
    elif d is None:
        print("   body:", text_of(body)[:400] if "<html" in body[:500].lower() else body[:400])
    return status, d


def cars_layers(api):
    """The collections of a CARS (Castle Rock) 511 API, as 511NY's (FiveElevenNyCars)."""
    for path in ("events/map-features?eventClassifications=incidents",
                 "events/map-features?eventClassifications=closures&maxBeginDateOffset=300000",
                 "events/map-features?eventClassifications=construction&maxBeginDateOffset=300000",
                 "cameras/map-features", "signs/map-features"):
        try_json(f"{api}/{path}")


def ibi_layers(site, layers=("Cameras", "Incidents", "Closures", "Construction", "MessageSigns", "SpecialEvents")):
    """The map layers of an IBI-built 511 site (the old 511NY layout): /map/mapIcons/<layer> (every item's
    position, gzipped), /List/GetData/<layer> (the site's list pages, a DataTables query)."""
    for layer in layers:
        status, d = try_json(f"{site}/map/mapIcons/{layer}", sample=700)
        if isinstance(d, dict):
            items = d.get("item2") if isinstance(d.get("item2"), list) else None
            print(f"   mapIcons {layer}: keys {list(d.keys())[:8]} items {len(items) if items is not None else '?'}")
            if items:
                print("   first icon:", json.dumps(items[0])[:400])
    query = urllib.parse.quote(json.dumps({"columns": [], "start": 0, "length": 3}, separators=(",", ":")))
    for layer in ("Cameras", "MessageSigns", "traffic", "Incidents", "Closures", "Construction", "Events", "events/traffic"):
        status, d = try_json(f"{site}/List/GetData/{layer}?query={query}", sample=2500)
        if isinstance(d, dict):
            print(f"   List {layer}: recordsTotal {d.get('recordsTotal')} recordsFiltered {d.get('recordsFiltered')} keys {list(d.keys())[:8]}")
    # All of a layer in one page, if the site allows it.
    big = urllib.parse.quote(json.dumps({"columns": [], "start": 0, "length": 5000}, separators=(",", ":")))
    for layer in ("Cameras", "MessageSigns", "traffic"):
        status, _, _, body = get(f"{site}/List/GetData/{layer}?query={big}", limit=60_000_000)
        d = _json(body)
        rows = d.get("data") if isinstance(d, dict) else None
        print(f"   List {layer} x5000: status {status} bytes {len(body)} rows {len(rows) if isinstance(rows, list) else None}"
              f" total {d.get('recordsTotal') if isinstance(d, dict) else None}")
        if isinstance(rows, list) and rows and layer == "traffic":
            kinds = {}
            for row in rows:
                k = (row.get("type") or row.get("eventType") or row.get("EventType") or "?", row.get("eventSubType") or row.get("subType") or "")
                kinds[k] = kinds.get(k, 0) + 1
            print("   kinds:", sorted(kinds.items(), key=lambda kv: -kv[1])[:25])
    for page in ("/developers/doc", "/developers/help", "/help/endpoint", "/api/v2/get/event?format=json", "/api/getevents?format=json",
                 "/api/v2/get/cameras?format=json"):
        status, _, _, body = get(site + page, limit=2_000_000)
        print(f"   {page}: status {status} bytes {len(body)}:", text_of(body)[:700].replace("\n", " | ") if "<" in body[:200] else body[:300])


def dot_ct():
    head("Connecticut: CTroads (CTDOT's 511, built by IBI like 511PA)")
    ibi_layers("https://ctroads.org")


BROWSER = {"User-Agent": "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
           "Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.9"}


def dot_nj():
    head("New Jersey: 511NJ (TRANSCOM)")
    status, final, _, page = get("https://511nj.org/", limit=4_000_000)
    srcs = [urllib.parse.urljoin(final, u) for u in re.findall(r'<script[^>]+src="([^"]+)"', page)]
    paths = set()
    for src in srcs:
        if "511nj.org" not in src:
            continue
        _, _, _, js = get(src, limit=30_000_000)
        paths |= set(re.findall(r"""["'`]((?:https?://[a-z0-9.-]+)?/?(?:api|client|Content|data|assets/config)[/A-Za-z0-9_.?=&{}-]{2,120})["'`]""", js))
        paths |= set(re.findall(r"""https?://[a-z0-9.-]*(?:511nj|transcom|njdot|iteris|trafficland)[a-z0-9.-]*[^"'`\s]{0,120}""", js))
    print("   script paths:", len(paths))
    for p in sorted(paths)[:300]:
        print("     ", p)
    # The site's API from GitHub's runners answered 403 (a firewall page). The same with a phone's browser headers:
    for url in ("https://511nj.org/api/client/camera/getCameraDataByTourId?tourId=0", "https://511nj.org/api/events",
                "https://511nj.org/assets/config/config.json"):
        status, _, headers, body = get(url, headers={**BROWSER, "Referer": "https://511nj.org/"}, limit=2_000_000)
        print(f"   browser headers {url}: status {status} type {headers.get('Content-Type')} bytes {len(body)}:", text_of(body)[:300].replace("\n", " | "))


def dot_pa():
    head("Pennsylvania: 511PA (built by IBI)")
    ibi_layers("https://www.511pa.com", layers=("Cameras", "Incidents", "Closures", "ActiveRoadwork", "MessageSigns",
                                                "MajorRouteClosure", "MajorRouteIncident", "OtherRouteClosure", "OtherRouteIncident",
                                                "SpecialEvents", "Detour", "WeatherEvents"))


def dot_md():
    head("Maryland: CHART (MDOT SHA), no key")
    base = "https://chartexp1.sha.maryland.gov/CHARTExportClientService"
    for name in ("getEventMapDataJSON.do", "getCameraMapDataJSON.do", "getDMSMapDataJSON.do"):
        status, d = try_json(f"{base}/{name}", sample=300)
        rows = d.get("data") if isinstance(d, dict) else None
        if not isinstance(rows, list):
            continue
        print(f"   {name}: {len(rows)} rows; other keys {[k for k in d if k != 'data']}")
        for row in rows[:2]:
            print("   row:", json.dumps(row)[:2500])
        if name.startswith("getEvent"):
            kinds = {}
            for row in rows:
                k = (row.get("incidentType") or row.get("type") or "?", row.get("closed"), row.get("eventType") or "")
                kinds[k] = kinds.get(k, 0) + 1
            print("   (incidentType, closed, eventType):", sorted(kinds.items(), key=lambda kv: -kv[1]))
            print("   keys seen:", sorted({k for row in rows for k in row})[:80])
        if name.startswith("getCamera"):
            print("   keys seen:", sorted({k for row in rows for k in row}))
            print("   opStatus:", {s: sum(1 for r in rows if r.get("opStatus") == s) for s in {r.get("opStatus") for r in rows}})
            first = rows[0]
            for url in (first.get("publicVideoURL"), f"https://chart.maryland.gov/Video/GetSnapshot/{first.get('id')}",
                        f"https://{first.get('cctvIp')}/rtplive/{first.get('id')}/playlist.m3u8"):
                if url:
                    status, _, headers, body = get(url, limit=3_000_000)
                    print(f"   camera {url}: status {status} type {headers.get('Content-Type')} bytes {len(body)}")
                    print("     mentions:", sorted(set(re.findall(r"""https?://[^\s"'<>]+(?:m3u8|jpg|jpeg|png|mjpg)[^\s"'<>]*""", body)))[:10])
    for name in ("getTSSMapDataJSON.do", "getRWISMapDataJSON.do", "getWebMessagesDataJSON.do", "getSpeedSensorMapDataJSON.do",
                 "getLaneClosureMapDataJSON.do", "getPlannedClosureMapDataJSON.do", "getWZMapDataJSON.do", "getSnowEmergencyMapDataJSON.do"):
        status, _, _, body = get(f"{base}/{name}", limit=20_000_000)
        print(f"   {name}: status {status} bytes {len(body)} json {_json(body) is not None}:", body[:200].replace("\n", " "))
    # The CHART site's own scripts name every export it reads.
    status, final, _, page = get("https://chart.maryland.gov/", limit=4_000_000)
    found = set(re.findall(r"ExportClientService/[A-Za-z]+\.do", page))
    for src in re.findall(r'<script[^>]+src="([^"]+)"', page):
        src = urllib.parse.urljoin(final, src)
        if "chart.maryland.gov" in src and "/lib/" not in src:
            _, _, _, js = get(src, limit=10_000_000)
            found |= set(re.findall(r"ExportClientService/[A-Za-z]+\.do", js))
    for path in ("/TrafficCameras", "/TravInfo/TravelerAlerts", "/Map", "/TravInfo/Index"):
        _, f2, _, p2 = get("https://chart.maryland.gov" + path, limit=4_000_000)
        found |= set(re.findall(r"ExportClientService/[A-Za-z]+\.do", p2))
        for src in re.findall(r'<script[^>]+src="([^"]+)"', p2):
            src = urllib.parse.urljoin(f2, src)
            if "chart.maryland.gov" in src and "/lib/" not in src:
                _, _, _, js = get(src, limit=10_000_000)
                found |= set(re.findall(r"ExportClientService/[A-Za-z]+\.do", js))
    print("   exports the CHART site names:", sorted(found))


def dot_de():
    head("Delaware: DelDOT TMC, no key")
    for url, key in (("https://tmc.deldot.gov/json/advisory.json", "advisories"), ("https://tmc.deldot.gov/json/videocamera.json", "videoCameras")):
        status, d = try_json(url, sample=300)
        rows = d.get(key) if isinstance(d, dict) else None
        if isinstance(rows, list):
            print(f"   {key}: {len(rows)}; keys seen {sorted({k for r in rows for k in r})}")
            for row in rows[:2]:
                print("   row:", json.dumps(row)[:2500])
            if key == "advisories":
                kinds = {}
                for r in rows:
                    t = r.get("type")
                    k = json.dumps(t)[:80] if not isinstance(t, str) else t
                    kinds[k] = kinds.get(k, 0) + 1
                print("   types:", kinds)
    # The travel map's own scripts name the other feeds (signs, closures, speeds).
    status, final, _, page = get("https://deldot.gov/map/index.shtml", limit=4_000_000)
    feeds = set(re.findall(r"""["'](https?://[^"']*?\.json[^"']*|/[A-Za-z0-9_/.-]*?\.json[^"']*)["']""", page))
    for src in re.findall(r'<script[^>]+src="([^"]+)"', page):
        src = urllib.parse.urljoin(final, src)
        if "deldot.gov" in src and "/CLF4/" not in src:
            _, _, _, js = get(src, limit=10_000_000)
            feeds |= set(re.findall(r"""["'](https?://[^"']*?\.json[^"']*|/[A-Za-z0-9_/.-]*?\.json[^"']*)["']""", js))
    print("   feeds named:", sorted(feeds))
    for f in sorted(feeds):
        if any(k in f.lower() for k in ("vms", "sign", "dms", "closure", "restriction", "speed", "flow", "travel", "event")):
            try_json(urllib.parse.urljoin("https://tmc.deldot.gov/", f) if f.startswith("/json") else urllib.parse.urljoin(final, f), sample=900)


def dot_dc():
    head("Washington, DC: DDOT (no 511; open data)")
    layer = "https://maps2.dcgis.dc.gov/dcgis/rest/services/DCGIS_DATA/Transportation_Sensors_WebMercator/MapServer"
    for n in (11, 93):
        try_json(f"{layer}/{n}?f=json", sample=300)
        status, d = try_json(f"{layer}/{n}/query?where=1%3D1&outFields=*&outSR=4326&f=geojson", sample=1200)
    for q in ("traffic incident", "work zone", "street closure", "lane closure", "moratorium"):
        status, d = try_json(f"https://opendata.dc.gov/api/search/v1/collections/all/items?q={urllib.parse.quote(q)}&limit=8", sample=200)
        for feat in (d or {}).get("features", [])[:8] if isinstance(d, dict) else []:
            p = feat.get("properties", {})
            print("   hub item", feat.get("id"), "|", p.get("title"), "|", p.get("type"), "|", p.get("url"))


def _raw(url):
    """(status, content type, body bytes) of a binary answer, such as a camera still."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    print(f"... GET {url}", flush=True)
    signal.alarm(45)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.headers.get("Content-Type"), r.read(5_000_000)
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type"), e.read(2000)
    except Exception as e:  # noqa: BLE001 - a probe reports every failure
        return None, None, f"{type(e).__name__}: {e}".encode()
    finally:
        signal.alarm(0)


def _ibi_rows(site, name, limit=40):
    """Every row of an IBI list, 100 a page (the sites' own cap), at most limit pages."""
    rows, start, total = [], 0, None
    for _ in range(limit):
        q = urllib.parse.quote(json.dumps({"columns": [], "start": start, "length": 100}, separators=(",", ":")))
        status, _, headers, body = get(f"{site}/List/GetData/{name}?query={q}", limit=20_000_000)
        d = _json(body)
        if not isinstance(d, dict) or not isinstance(d.get("data"), list):
            print(f"   {name} start {start}: status {status}, not a page")
            break
        if start == 0:
            print(f"   {name}: cache headers", {k: v for k, v in headers.items() if k.lower() in ("etag", "last-modified", "cache-control", "expires")})
        total = d.get("recordsTotal")
        rows += d["data"]
        start += len(d["data"])
        if not d["data"] or (total is not None and start >= total):
            break
    return rows, total


def ibi_join():
    head("IBI sites (511PA, CTroads): do map icons and list rows share ids, and does a camera still load")
    for site, layers in (("https://www.511pa.com", ("MajorRouteClosure", "OtherRouteClosure", "MajorRouteIncident", "OtherRouteIncident",
                                                     "ActiveRoadwork", "SpecialEvents", "WeatherEvents", "Detour", "TurnpikePlannedRoadwork",
                                                     "MessageSigns", "Cameras")),
                         ("https://ctroads.org", ("Incidents", "Closures", "Construction", "Congestion", "WeatherIncidents", "SpecialEvents",
                                                  "MessageSigns", "Cameras"))):
        print(f"--- {site}")
        icons = {}
        for layer in layers:
            status, _, headers, body = get(f"{site}/map/mapIcons/{layer}", limit=20_000_000)
            d = _json(body)
            items = d.get("item2") if isinstance(d, dict) else None
            icons[layer] = {str(i.get("itemId")): i.get("location") for i in items or [] if isinstance(i, dict)}
            print(f"   icons {layer}: status {status} items {len(icons[layer])} cache",
                  {k: v for k, v in headers.items() if k.lower() in ("etag", "last-modified", "cache-control")})
        for name in ("Incidents", "Closures", "Construction", "traffic", "MessageSigns", "Cameras"):
            rows, total = _ibi_rows(site, name)
            ids = [str(r.get("DT_RowId")) for r in rows if isinstance(r, dict)]
            print(f"   list {name}: total {total} rows {len(rows)} distinct ids {len(set(ids))}")
            if not rows:
                continue
            for layer, found in icons.items():
                both = len(set(ids) & set(found))
                if both:
                    print(f"      in icons {layer}: {both} of {len(set(ids))} (icons {len(found)})")
            layer_names = {}
            for r in rows:
                k = (r.get("layerName"), r.get("type"))
                layer_names[k] = layer_names.get(k, 0) + 1
            if any(k != (None, None) for k in layer_names):
                print("      layerName, type:", sorted(layer_names.items(), key=lambda kv: -kv[1])[:20])
            print("      keys:", sorted({k for r in rows for k in r})[:70])
            if name in ("Incidents", "Closures", "Construction", "traffic"):
                for r in rows[:2]:
                    print("      row:", json.dumps(r)[:1800])
                print("      directions:", sorted({str(r.get("direction")) for r in rows})[:30])
                print("      lanes:", sorted({str(r.get("laneDescription")) for r in rows})[:30])
                print("      roadways:", sorted({str(r.get("roadwayName")) for r in rows})[:25])
                print("      lastUpdated:", [r.get("lastUpdated") for r in rows[:5]])
            if name == "MessageSigns":
                print("      sample messages:", [(r.get("DT_RowId"), r.get("message"), r.get("message2"), r.get("status")) for r in rows[:6]])
            if name == "Cameras":
                first = next((r for r in rows if r.get("images")), None)
                print("      directions:", sorted({str(r.get("direction")) for r in rows})[:20])
                print("      images per camera:", sorted({len(r.get("images") or []) for r in rows}))
                if first:
                    img = first["images"][0]
                    status, ctype, body = _raw(site + img.get("imageUrl", ""))
                    print(f"      still {img.get('imageUrl')}: status {status} type {ctype} bytes {len(body)} jpeg {body[:3] == bytes([0xFF, 0xD8, 0xFF])}")
                    status, ctype, body = _raw(f"{site}/map/Cctv/{first.get('id')}")
                    print(f"      by camera id /map/Cctv/{first.get('id')}: status {status} type {ctype} bytes {len(body)}")


def chart_more():
    head("Maryland CHART: events with lanes, signs with pages, speed sensors")
    base = "https://chartexp1.sha.maryland.gov/CHARTExportClientService"
    status, d = try_json(f"{base}/getEventMapDataJSON.do", sample=100)
    rows = (d or {}).get("data") or [] if isinstance(d, dict) else []
    for r in [r for r in rows if r.get("lanes")][:3] + [r for r in rows if r.get("incidentType") not in ("Other",)][:6] + [r for r in rows if r.get("closed")][:3]:
        print("   event:", json.dumps(r)[:2000])
    print("   types:", sorted({(r.get("type"), r.get("incidentType")) for r in rows}, key=str))
    status, d = try_json(f"{base}/getDMSMapDataJSON.do", sample=100)
    rows = (d or {}).get("data") or [] if isinstance(d, dict) else []
    for r in [r for r in rows if "[np]" in (r.get("msgMulti") or "").lower()][:3]:
        print("   sign with pages:", json.dumps(r)[:1500])
    print("   signs with text:", sum(1 for r in rows if (r.get("msgPlain") or "").strip()), "of", len(rows))
    status, d = try_json(f"{base}/getTSSMapDataJSON.do", sample=1500)


def deldot_more():
    head("Delaware DelDOT: signs, Waze alerts, red light cameras")
    for name in ("vmsg-vms.json", "vmsg-vsl.json", "wazealert.json", "rlecamera.json"):
        status, _, _, body = get(f"https://tmc.deldot.gov/json/{name}", limit=20_000_000)
        print(f"--- {name}: status {status} bytes {len(body)}")
        print("   ", body[:2500])


def nj_config():
    head("511NJ: the app's own config, with a phone browser's headers")
    for url in ("https://511nj.org/assets/configs/application.json", "https://511nj.org/client/get/event",
                "https://511nj.org/api/client/get/event"):
        status, _, headers, body = get(url, headers={**BROWSER, "Referer": "https://511nj.org/"}, limit=2_000_000)
        print(f"   {url}: status {status} type {headers.get('Content-Type')} bytes {len(body)}:", body[:1500].replace("\n", " "))


def hls_frames():
    head("Camera video (CHART, DelDOT): master playlist, media playlist, one segment")
    import urllib.parse as up

    def lines(url):
        status, final, headers, body = get(url, limit=2_000_000)
        print(f"   {url}: status {status} type {headers.get('Content-Type')} bytes {len(body)}")
        print("     ", body[:600].replace("\n", " | "))
        return final, [l.strip() for l in body.splitlines() if l.strip()]

    urls = []
    status, d = try_json("https://chartexp1.sha.maryland.gov/CHARTExportClientService/getCameraMapDataJSON.do", sample=100)
    rows = (d or {}).get("data") or [] if isinstance(d, dict) else []
    for r in [r for r in rows if r.get("opStatus") == "OK"][:2]:
        urls.append(f"https://{r.get('cctvIp')}/rtplive/{r.get('id')}/playlist.m3u8")
    status, d = try_json("https://tmc.deldot.gov/json/videocamera.json", sample=100)
    rows = (d or {}).get("videoCameras") or [] if isinstance(d, dict) else []
    for r in [r for r in rows if r.get("enabled")][:2]:
        urls.append((r.get("urls") or {}).get("m3u8s"))
    for url in [u for u in urls if u]:
        final, ls = lines(url)
        variants = [l for l in ls if not l.startswith("#")]
        if not variants:
            continue
        media = up.urljoin(final, variants[0])
        if any("EXT-X-STREAM-INF" in l for l in ls):
            final, ls = lines(media)
        segs = [l for l in ls if not l.startswith("#")]
        if segs:
            seg = up.urljoin(final, segs[-1])
            status, ctype, body = _raw(seg)
            print(f"   segment {seg}: status {status} type {ctype} bytes {len(body)} mpeg-ts {body[:1] == b'G'} mp4 {body[4:8] in (b'ftyp', b'moof', b'styp')}")


# The Midwest's 511s (owner, October 2026: "the entire midwest states, same features as all other states").
# Most are IBI Group's platform like 511PA and CTroads; the probe tells which, and what their layers are called.
MIDWEST_IBI = {
    "Indiana": "https://511in.org", "Wisconsin": "https://511wi.gov", "Minnesota": "https://511mn.org", "Iowa": "https://511ia.org",
    "North Dakota": "https://travel.dot.nd.gov", "South Dakota": "https://sd511.org", "Nebraska": "https://511.nebraska.gov",
    "Kansas": "https://www.kandrive.gov",
}


def dot_midwest_ibi():
    for state, site in MIDWEST_IBI.items():
        head(f"{state}: {site} (IBI platform?)")
        try:
            status, final, headers, page = get(site + "/", limit=2_000_000)
            print(f"   home: status {status} final {final} type {headers.get('Content-Type')} bytes {len(page)}; ibi marks:",
                  [m for m in ("mapIcons", "List/GetData", "Generated/Content", "ibigroup", "Iteris", "CARS", "castlerock", "one.network") if m.lower() in page.lower()])
            ibi_layers(final.rstrip("/") if status == 200 else site,
                       layers=("Cameras", "Incidents", "Closures", "Construction", "ActiveRoadwork", "MessageSigns", "WinterRoadConditions",
                               "RoadConditions", "Events", "Alerts", "Crashes", "MajorRouteClosure", "OtherRouteIncident"))
        except Exception as e:
            print("   failed:", repr(e)[:200])


MIDWEST_OTHER = {
    "Ohio": ("https://ohgo.com/", ["https://publicapi.ohgo.com/api/v1/incidents", "https://publicapi.ohgo.com/api/v1/cameras",
                                   "https://ohgo.com/api/v1/incidents", "https://ohgo.com/Services/MapDataService.svc/GetIncidents"]),
    "Illinois": ("https://www.travelmidwest.com/lmiga/home.jsp", ["https://www.travelmidwest.com/lmiga/incidentReport.jsp",
                                                                 "https://www.travelmidwest.com/lmiga/cameraReport.jsp",
                                                                 "https://www.travelmidwest.com/lmiga/dmsReport.jsp",
                                                                 "https://www.travelmidwest.com/lmiga/xml/incidentReport.xml",
                                                                 "https://www.gettingaroundillinois.com/"]),
    "Michigan": ("https://mdotjboss.state.mi.us/MiDrive/map", ["https://mdotjboss.state.mi.us/MiDrive/map/getIncidents",
                                                               "https://mdotjboss.state.mi.us/MiDrive/map/getCameras",
                                                               "https://mdotjboss.state.mi.us/MiDrive/map/getDms",
                                                               "https://mdotjboss.state.mi.us/MiDrive/map/getCCTV"]),
    "Missouri": ("https://traveler.modot.org/map/", ["https://traveler.modot.org/map/incidents.json", "https://traveler.modot.org/map/cameras.json",
                                                    "https://traveler.modot.org/map/data/incidents.json"]),
}


def dot_midwest_other():
    for state, (home, tries) in MIDWEST_OTHER.items():
        head(f"{state}: {home}")
        try:
            status, final, headers, page = get(home, headers=BROWSER, limit=4_000_000)
            print(f"   home: status {status} final {final} type {headers.get('Content-Type')} bytes {len(page)}")
            feeds = sorted(set(re.findall(r"""https?://[^\s"'<>]+(?:json|xml|geojson|\.svc/[A-Za-z]+|/api/[A-Za-z0-9/._-]+)""", page)))
            print("   feeds named on the page:", feeds[:40])
            print("   relative paths that look like data:", sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:json|xml|api|data|map/get|Service)[A-Za-z0-9_./?=&-]*)["']""", page)))[:40])
            for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:12]:
                src = urllib.parse.urljoin(final, src)
                if any(x in src for x in ("jquery", "bootstrap", "google", "gtag", "polyfill", "leaflet.js", "mapbox")):
                    continue
                _, _, _, js = get(src, headers=BROWSER, limit=6_000_000)
                hits = sorted(set(re.findall(r"""["'](/?[A-Za-z0-9_./-]*(?:json|api/|data/|map/get|getIncidents|getCameras|GetData|mapIcons)[A-Za-z0-9_./?=&{}-]*)["']""", js)))
                if hits:
                    print(f"   {src[-70:]}: {hits[:30]}")
            for url in tries:
                status, _, headers, body = get(url, headers={**BROWSER, "Accept": "application/json, */*"}, limit=3_000_000)
                print(f"   {url}: status {status} type {headers.get('Content-Type')} bytes {len(body)}:", body[:600].replace("\n", " "))
        except Exception as e:
            print("   failed:", repr(e)[:200])


# Indiana, Minnesota, Iowa, Nebraska and Kansas serve the same JavaScript shell as 511NY's new site (the CARS
# platform, Castle Rock): the API host is in the site's config, as 511NY's was (demo.511ny.org/configs/main.json).
CARS_SITES = {"Indiana": "https://511in.org", "Minnesota": "https://511mn.org", "Iowa": "https://511ia.org",
              "Nebraska": "https://511.nebraska.gov", "Kansas": "https://www.kandrive.gov", "North Dakota": "https://travel.dot.nd.gov"}


def dot_midwest_cars():
    for state, site in CARS_SITES.items():
        head(f"{state}: {site}, CARS platform?")
        api = None
        try:
            for path in ("/configs/main.json", "/config/main.json", "/configs/config.json", "/assets/configs/main.json"):
                status, _, h, body = get(site + path, limit=2_000_000)
                print(f"   {path}: status {status} type {h.get('Content-Type')} bytes {len(body)}")
                if status == 200 and body.lstrip().startswith("{"):
                    print("    ", body[:2000].replace("\n", " "))
                    found = re.findall(r"https://[a-z0-9.-]*carsprogram\.org", body)
                    if found:
                        api = found[0]
                        break
            if not api:
                status, final, _, page = get(site + "/", limit=2_000_000)
                for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:12]:
                    src = urllib.parse.urljoin(final, src)
                    _, _, _, js = get(src, limit=12_000_000)
                    found = sorted(set(re.findall(r"https://[a-z0-9.-]*carsprogram\.org", js)))
                    hosts = sorted(set(re.findall(r"https://api[a-z0-9.-]*\.(?:org|gov|com)[/a-z0-9._-]{0,40}", js)))
                    configs = sorted(set(re.findall(r"""["']([^"' ]*configs?/[^"' ]*\.json)["']""", js)))
                    if found or hosts or configs:
                        print(f"   {src[-70:]}: carsprogram {found[:5]}; api hosts {hosts[:10]}; configs {configs[:5]}")
                    if found and not api:
                        api = found[0]
            if api:
                print(f"   API: {api}")
                cars_features(api, limit_keys=25)
            else:
                print("   no CARS API host found")
        except Exception as e:
            print("   failed:", repr(e)[:200])


def dot_midwest_2():
    """Follow-ups from the first Midwest probe: South Dakota (Iteris), Ohio's keyed API docs, Illinois' JSON paths,
    Michigan's public/api, Missouri's map services."""
    head("South Dakota: sd511.org (Iteris)")
    try:
        status, final, _, page = get("https://www.sd511.org/", headers=BROWSER, limit=4_000_000)
        print("   scripts:", re.findall(r'<script[^>]+src="([^"]+)"', page)[:20])
        print("   data-ish on the page:", sorted(set(re.findall(r"""["'](https?://[^"']+(?:api|json|xml|rest)[^"']*)["']""", page)))[:20],
              sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:api|json|xml|rest|data)[A-Za-z0-9_./?=&-]*)["']""", page)))[:30])
        for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:15]:
            src = urllib.parse.urljoin(final, src)
            if any(x in src for x in ("jquery", "bootstrap", "google", "gtag", "polyfill")):
                continue
            _, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
            hits = sorted(set(re.findall(r"""["'](https?://[^"' ]+(?:api|json|rest|feed)[^"' ]*)["']""", js)))
            rel = sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:api|json|rest|feed|data)[A-Za-z0-9_./?=&{}-]*)["']""", js)))
            if hits or rel:
                print(f"   {src[-70:]}: {hits[:15]} {rel[:25]}")
    except Exception as e:
        print("   failed:", repr(e)[:200])

    head("Ohio: OHGO public API docs (a key, free registration?)")
    for url in ("https://publicapi.ohgo.com/docs/resources", "https://publicapi.ohgo.com/docs", "https://publicapi.ohgo.com/",
                "https://publicapi.ohgo.com/api/v1/cameras?page-size=1"):
        status, _, h, body = get(url, headers=BROWSER, limit=3_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}")
        print("    ", (text_of(body) if "<" in body[:300] else body)[:2500].replace("\n", " | "))

    head("Illinois: Travel Midwest JSON paths from the bundle")
    for base in ("https://travelmidwest.com/lmiga", "https://travelmidwest.com", "https://travelmidwest.com/api", "https://www.travelmidwest.com/lmiga"):
        for path in ("/incidentMap.json?type=lines", "/incidents.json", "/cameraMap.json", "/cameraReport.json", "/dmsMap.json", "/dmsReport.json",
                     "/congestionMap.json", "/constructionMap.json?type=lines", "/construction.json"):
            status, _, h, body = get(base + path, headers={**BROWSER, "Accept": "application/json"}, limit=20_000_000)
            kind = "json" if body.lstrip()[:1] in "[{" else "other"
            print(f"   {base + path}: status {status} type {h.get('Content-Type')} bytes {len(body)} {kind}")
            if status == 200 and kind == "json":
                print("    ", body[:1200].replace("\n", " "))
        print()

    head("Michigan: Mi Drive public/api")
    try:
        _, _, _, js = get("https://mdotjboss.state.mi.us/MiDrive/js/main.js", headers=BROWSER, limit=8_000_000)
        spots = [m.start() for m in re.finditer(r"public/api/", js)][:20]
        for i in spots:
            print("   ...", js[max(0, i - 160):i + 160].replace("\n", " "), "...")
        paths = sorted(set(re.findall(r"""public/api/[A-Za-z0-9_./-]+""", js)))
        print("   paths:", paths[:40])
        _, _, _, js2 = get("https://mdotjboss.state.mi.us/MiDrive/js/map/mapdatabase.js?version=1", headers=BROWSER, limit=8_000_000)
        print("   mapdatabase.js bytes", len(js2), "urls:", sorted(set(re.findall(r"""["'](/?[A-Za-z0-9_./-]*(?:api|json|rest|Server|data)[A-Za-z0-9_./?=&{}-]*)["']""", js2)))[:40])
        for path in paths[:12] + ["public/api/incidents", "public/api/cameras", "public/api/dms", "public/api/events"]:
            url = "https://mdotjboss.state.mi.us/MiDrive/" + path
            status, _, h, body = get(url, headers={**BROWSER, "Accept": "application/json"}, limit=20_000_000)
            print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:500].replace("\n", " "))
    except Exception as e:
        print("   failed:", repr(e)[:200])

    head("Missouri: traveler.modot.org map services")
    try:
        status, final, _, page = get("https://traveler.modot.org/map/", headers=BROWSER, limit=4_000_000)
        srcs = re.findall(r'<script[^>]+src="([^"]+)"', page)
        print("   scripts:", srcs[:30])
        print("   services on the page:", sorted(set(re.findall(r"""https?://[^"' ]+(?:rest/services|FeatureServer|MapServer|\.json)[^"' ]*""", page)))[:20])
        for src in srcs[:30]:
            src = urllib.parse.urljoin(final, src)
            if "modot.org" not in src or "ruxit" in src or "Incapsula" in src:
                continue
            _, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
            hits = sorted(set(re.findall(r"""https?://[^"' ]+(?:rest/services|FeatureServer|MapServer|\.json|/api/)[^"' ]*""", js)))
            rel = sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:api|json|rest|services|data)[A-Za-z0-9_./?=&{}-]*)["']""", js)))
            print(f"   {src[-70:]}: bytes {len(js)}; {hits[:20]} {rel[:20]}")
    except Exception as e:
        print("   failed:", repr(e)[:200])


def dot_midwest_3():
    """Third round: the CARS states' own hosts (<st>tg.carsprogram.org, found in their bundles), South Dakota's Iteris
    aggregator, Ohio's swagger, Illinois' POST endpoints and IDOT's ArcGIS, Michigan's publicApi calls, Missouri's
    ArcGIS, North Dakota's bundle."""
    for state, hosts in (("Indiana", ["intg", "public"]), ("Minnesota", ["mntg", "public"]), ("Iowa", ["iatg", "public"]),
                         ("Nebraska", ["netg", "public"]), ("Kansas", ["kstg", "ks"])):
        head(f"{state}: CARS hosts")
        for host in hosts:
            api = f"https://{host}.carsprogram.org"
            status, _, h, body = get(f"{api}/events/map-features?eventClassifications=incidents", limit=20_000_000)
            print(f"   {api}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:200].replace("\n", " "))
            if status == 200 and body.lstrip().startswith(("{", "[")):
                cars_features(api, limit_keys=25)
                break

    head("South Dakota: Iteris aggregator layers")
    base = "https://aggregator.iteris-atis.com/aggregator/services/layers/group/sddot/current/"
    for layer in ("rest_area", "incident", "incidents", "event", "events", "construction", "road_condition", "road_conditions", "camera", "cameras",
                  "cctv", "dms", "message_sign", "closure", "closures", "winter_road", "weather", ""):
        url = base + (f"?layer_type={layer}" if layer else "")
        status, _, h, body = get(url, headers=BROWSER, limit=20_000_000)
        print(f"   {layer or '(none)'}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:400].replace("\n", " "))
    for url in ("https://sd.cdn.iteris-atis.com/geojson/icons/metadata/", "https://aggregator.iteris-atis.com/aggregator/services/layers/group/sddot/",
                "https://aggregator.iteris-atis.com/aggregator/services/"):
        status, _, h, body = get(url, headers=BROWSER, limit=4_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:600].replace("\n", " "))
    for src in ("https://www.sd511.org/atis-static.iteriscdn.com/WebApps/SD/SafeTravel4/v1.0/js/prefs_base.js?v=1.9.16",
                "https://www.sd511.org/atis-static.iteriscdn.com/WebApps/SD/SafeTravel4/v1.0/js/mapbox-gl-traffic.js?v=1.9.16",
                "https://www.sd511.org/atis-static.iteriscdn.com/WebApps/SD/SafeTravel4/v1.0/js/alert_ticker.js?v=1.9.16",
                "https://www.sd511.org/atis-static.iteriscdn.com/WebApps/SD/SafeTravel4/v1.0/js/bootstrap_utilities.js?v=1.9.16"):
        status, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
        print(f"   {src[-60:]}: status {status} bytes {len(js)}; layer_type values {sorted(set(re.findall(r'layer_type=([A-Za-z_]+)', js)))[:30]};"
              f" urls {sorted(set(re.findall(r'https?://[^\"\' ]+(?:aggregator|iteris)[^\"\' ]*', js)))[:15]}")
    status, final, _, page = get("https://www.sd511.org/", headers=BROWSER, limit=4_000_000)
    print("   inline layer_type values:", sorted(set(re.findall(r"layer_type=([A-Za-z_]+)", page))))
    print("   inline iteris urls:", sorted(set(re.findall(r"""https?://[^"' ]+(?:aggregator|iteris-atis)[^"' ]*""", page)))[:30])

    head("Ohio: OHGO swagger (the schema, without a key)")
    for url in ("https://publicapi.ohgo.com/swagger/v1/swagger.json", "https://publicapi.ohgo.com/swagger/docs/v1", "https://publicapi.ohgo.com/swagger",
                "https://publicapi.ohgo.com/swagger/index.html"):
        status, _, h, body = get(url, headers=BROWSER, limit=4_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}")
        if status == 200 and body.lstrip().startswith("{"):
            try:
                doc = json.loads(body)
                print("   paths:", list(doc.get("paths", {}))[:30])
                for name, schema in list(doc.get("definitions", doc.get("components", {}).get("schemas", {})).items())[:12]:
                    print(f"   {name}: {list((schema or {}).get('properties', {}))[:30]}")
            except ValueError:
                print("   ", body[:800])
        elif status == 200:
            print("   ", re.findall(r"""["'](/swagger/[^"']+)["']""", body)[:5], text_of(body)[:300].replace("\n", " | "))

    head("Illinois: Travel Midwest POST endpoints and IDOT's ArcGIS")
    for path in ("incidentMap.json?type=lines", "incidentMap.json", "cameraMap.json", "dmsMap.json", "congestionMap.json", "constructionMap.json?type=lines",
                 "incidents.json", "construction.json", "dmsReport.json", "chicagoQuickTraffic.json"):
        for body_text, ctype in (("{}", "application/json"), ("", "application/x-www-form-urlencoded")):
            status, _, h, body = get("https://travelmidwest.com/lmiga/" + path, headers={**BROWSER, "Accept": "application/json", "Content-Type": ctype,
                                                                                            "Referer": "https://travelmidwest.com/"},
                                     method="POST", data=body_text.encode(), limit=20_000_000)
            print(f"   POST {path} ({ctype}): status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:700].replace("\n", " "))
            if status == 200:
                break
    status, final, _, page = get("https://www.gettingaroundillinois.com/", headers=BROWSER, limit=4_000_000)
    urls = sorted(set(re.findall(r"""https?://[^"' <>]+(?:rest/services|FeatureServer|MapServer|arcgis)[^"' <>]*""", page)))
    print("   IDOT page arcgis urls:", urls[:30])
    for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:25]:
        src = urllib.parse.urljoin(final, src)
        if any(x in src for x in ("jquery", "bootstrap", "google", "gtag", "datatables", "inspinia", "popper", "font")):
            continue
        _, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
        hits = sorted(set(re.findall(r"""https?://[^"' <>]+(?:rest/services|FeatureServer|MapServer|arcgis|\.json|/api/)[^"' <>]*""", js)))
        if hits:
            print(f"   {src[-70:]}: {hits[:25]}")
    for url in ("https://gis1.dot.illinois.gov/arcgis/rest/services?f=json", "https://gis.dot.illinois.gov/arcgis/rest/services?f=json",
                "https://www.gettingaroundillinois.com/arcgis/rest/services?f=json"):
        status, _, h, body = get(url, headers=BROWSER, limit=2_000_000)
        print(f"   {url}: status {status} bytes {len(body)}:", body[:800].replace("\n", " "))

    head("Michigan: how Mi Drive calls publicApi")
    for src in ("https://mdotjboss.state.mi.us/MiDrive/js/main.js", "https://mdotjboss.state.mi.us/MiDrive/js/map/mapdatabase.js?version=1",
                "https://mdotjboss.state.mi.us/MiDrive/js/map/mapvars.js", "https://mdotjboss.state.mi.us/MiDrive/js/common.js?version=83"):
        status, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
        calls = sorted(set(re.findall(r"""(?:publicApi|secureApi|pageAPI|server)\s*\+\s*["']([^"']+)["']""", js)))
        print(f"   {src[-50:]}: status {status} bytes {len(js)}; api paths {calls[:60]}")
        for m in list(re.finditer(r"\.(?:getJSON|ajax|get)\(", js))[:8]:
            print("     ...", js[m.start():m.start() + 220].replace("\n", " "))
    status, final, _, page = get("https://mdotjboss.state.mi.us/MiDrive/map", headers=BROWSER, limit=4_000_000)
    print("   inline api mentions:", sorted(set(re.findall(r"""(?:publicApi|public/api/|public/page/)[^"' <>;]{0,80}""", page)))[:30])

    head("Missouri: MoDOT ArcGIS services")
    status, _, _, page = get("https://traveler.modot.org/map/", headers=BROWSER, limit=4_000_000)
    print("   inline urls:", sorted(set(u for u in re.findall(r"""https?://[^"' <>]+""", page) if "modot" in u or "arcgis" in u or "esri" in u))[:30])
    print("   inline paths:", sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:rest|services|api|json|Server|data)[A-Za-z0-9_./?=&-]*)["']""", page)))[:30])
    for url in ("https://gis.modot.mo.gov/arcgis/rest/services?f=json", "https://gis.modot.mo.gov/arcgis/rest/services/TravelerInformation?f=json",
                "https://traveler.modot.org/arcgis/rest/services?f=json", "https://traveler.modot.org/map/data/", "https://www.modot.org/traveler-map"):
        status, _, h, body = get(url, headers=BROWSER, limit=2_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:700].replace("\n", " "))

    head("North Dakota: travel.dot.nd.gov bundle")
    for src in ("https://travel.dot.nd.gov/static/js/main.bb4da58d.chunk.js", "https://travel.dot.nd.gov/static/js/2.83af0331.chunk.js"):
        status, _, _, js = get(src, headers=BROWSER, limit=12_000_000)
        urls = sorted(set(u for u in re.findall(r"""https?://[^"' <>\\]+""", js) if not any(x in u for x in ("mapbox", "unpkg", "google", "w3.org", "github", "reactjs", "facebook", "mozilla"))))
        rel = sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:api|json|rest|feed|data|services)[A-Za-z0-9_./?=&{}-]*)["']""", js)))
        print(f"   {src[-40:]}: status {status} bytes {len(js)}; urls {urls[:40]}; paths {rel[:40]}")
    for url in ("https://travel.dot.nd.gov/api/", "https://travel.dot.nd.gov/data/", "https://www.dot.nd.gov/travel-info-v2/"):
        status, _, h, body = get(url, headers=BROWSER, limit=2_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:300].replace("\n", " "))


def dot_midwest_4():
    """Fourth round: the CARS bundles' own request paths, Ohio's resource docs, South Dakota's elements feed, Michigan's
    ArcGIS layers, Missouri's map page, North Dakota's ArcGIS service and text feeds."""
    for state, site in (("Indiana", "https://511in.org"), ("Minnesota", "https://511mn.org"), ("Kansas", "https://www.kandrive.gov")):
        head(f"{state}: what the bundle asks carsprogram.org for")
        try:
            status, final, _, page = get(site + "/", limit=2_000_000)
            for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:12]:
                src = urllib.parse.urljoin(final, src)
                _, _, _, js = get(src, limit=12_000_000)
                spots = [m.start() for m in re.finditer(r"carsprogram\.org", js)][:12]
                for i in spots:
                    print("   ...", js[max(0, i - 200):i + 120].replace("\n", " "), "...")
                paths = sorted(set(re.findall(r"""["'`](/(?:api|v\d|events|cameras|signs|map|graphql|feed)[A-Za-z0-9_./-]{0,60})["'`]""", js)))
                words = sorted(set(re.findall(r"""map-features|/hash\b|eventClassifications|graphql|/api/v[0-9]/[a-z-]+""", js)))
                if paths or words:
                    print(f"   {src[-60:]}: paths {paths[:40]}; words {words[:20]}")
        except Exception as e:
            print("   failed:", repr(e)[:200])

    head("Ohio: OHGO resource docs and the swagger link")
    status, _, _, page = get("https://publicapi.ohgo.com/docs/resources", headers=BROWSER, limit=2_000_000)
    links = sorted(set(re.findall(r'href="([^"]+)"', page)))
    print("   links:", [l for l in links if "swagger" in l.lower() or "docs" in l.lower() or "register" in l.lower()][:30])
    for path in ("/docs/resources/cameras", "/docs/resources/incidents", "/docs/resources/construction", "/docs/resources/digital-signs",
                 "/docs/resources/filters", "/docs/resources/query-parameters", "/docs/resources/api-results", "/docs/registration", "/docs/swagger"):
        status, _, h, body = get("https://publicapi.ohgo.com" + path, headers=BROWSER, limit=2_000_000)
        print(f"   {path}: status {status} bytes {len(body)}:", text_of(body)[:1800].replace("\n", " | ") if status == 200 else "")
    for l in links:
        if "swagger" in l.lower():
            url = urllib.parse.urljoin("https://publicapi.ohgo.com/docs/resources", l)
            status, _, h, body = get(url, headers=BROWSER, limit=4_000_000)
            print(f"   swagger {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:600].replace("\n", " "))
            for spec in re.findall(r"""["']([^"']*swagger[^"']*\.json)["']""", body)[:3] + re.findall(r"""url:\s*["']([^"']+)["']""", body)[:3]:
                spec = urllib.parse.urljoin(url, spec)
                status, _, h, sbody = get(spec, headers=BROWSER, limit=4_000_000)
                print(f"   spec {spec}: status {status} bytes {len(sbody)}")
                try:
                    doc = json.loads(sbody)
                    print("   paths:", list(doc.get("paths", {}))[:30])
                    for name, schema in list((doc.get("definitions") or doc.get("components", {}).get("schemas", {})).items())[:20]:
                        print(f"   {name}: {list((schema or {}).get('properties', {}))[:30]}")
                except ValueError:
                    pass

    head("South Dakota: Iteris elements feed")
    for url in ("https://aggregator.iteris-atis.com/aggregator/services/elements/group/sddot/current/",
                "https://aggregator.iteris-atis.com/aggregator/services/elements/group/sddot/current/?layer_type=incident",
                "https://aggregator.iteris-sd511.net/aggregator/services/news/group/sddot/current/",
                "https://aggregator.iteris-atis.com/aggregator/services/layers/group/sddot/current/?layer_type=Weight%20Stations/Ports%20of%20Entry"):
        status, _, h, body = get(url, headers=BROWSER, limit=30_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}")
        try:
            doc = json.loads(body)
        except ValueError:
            print("    ", body[:500].replace("\n", " "))
            continue
        feats = doc.get("features") if isinstance(doc, dict) else doc
        if isinstance(feats, list):
            from collections import Counter
            print("   features:", len(feats), "content_type:", Counter(str((f.get("properties") or {}).get("content_type")) for f in feats if isinstance(f, dict)).most_common(20))
            print("   icon_type:", Counter(str((f.get("properties") or {}).get("icon_type")) for f in feats if isinstance(f, dict)).most_common(20))
            for f in feats[:3]:
                print("   feature:", json.dumps(f)[:900])
        else:
            print("   top:", json.dumps(doc)[:1200])

    head("Michigan: Mi Drive's map layers (ArcGIS)")
    for src in ("https://mdotjboss.state.mi.us/MiDrive/js/map/mapdatabase.js?version=1", "https://mdotjboss.state.mi.us/MiDrive/js/map/mapvars.js",
                "https://mdotjboss.state.mi.us/MiDrive/map"):
        status, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
        urls = sorted(set(re.findall(r"""https?://[^"' <>]+""", js)))
        print(f"   {src[-40:]}: status {status} bytes {len(js)}; urls {[u for u in urls if 'arcgis.com' not in u][:40]}")
        print("     url strings:", sorted(set(re.findall(r"""url\s*[:=]\s*["']([^"']+)["']""", js)))[:40])
        print("     ArcGIS:", sorted(set(re.findall(r"""[A-Za-z0-9_./:-]*(?:MapServer|FeatureServer)[A-Za-z0-9_./-]*""", js)))[:30])
    for url in ("https://mdotjboss.state.mi.us/arcgis/rest/services?f=json", "https://gis-mdot.state.mi.us/arcgis/rest/services?f=json",
                "https://mdotjboss.state.mi.us/MiDrive/public/api/events/all", "https://mdotjboss.state.mi.us/MiDrive/public/page/map"):
        status, _, h, body = get(url, headers=BROWSER, limit=2_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:500].replace("\n", " "))

    head("Missouri: traveler.modot.org, the map page itself")
    for url in ("https://traveler.modot.org/map/", "https://traveler.modot.org/mapv3/index.html"):
        status, _, h, page = get(url, headers=BROWSER, limit=4_000_000)
        print(f"   {url}: status {status} bytes {len(page)}")
        print("     quoted paths:", sorted(set(re.findall(r"""["'](\.{0,2}/[A-Za-z0-9_./-]{3,80})["']""", page)))[:60])
        print("     words:", sorted(set(re.findall(r"""(?:MapServer|FeatureServer|rest/services|\.json|\.geojson|/api/)[A-Za-z0-9_./-]{0,40}""", page)))[:30])
        for m in list(re.finditer(r"url\s*[:=]\s*[\"'`]", page))[:20]:
            print("     ...", page[m.start():m.start() + 160].replace("\n", " "))

    head("North Dakota: ArcGIS service and text feeds")
    for url in ("https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer?f=json",
                "https://gis.dot.nd.gov/arcgis/rest/services/external?f=json",
                "https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer/10/query?geometry=-110%2C41%2C-91%2C54&inSR=4326&geometryType=esriGeometryEnvelope&spatialRel=esriSpatialRelContains&outFields=*&returnGeometry=true&f=geojson",
                "https://travel.dot.nd.gov/text/roads.txt", "https://travel.dot.nd.gov/text/workzones.txt", "https://www.dot.nd.gov/text/roads.txt",
                "https://travel.dot.nd.gov/georss/loadrestrict-current.xml"):
        status, _, h, body = get(url, headers=BROWSER, limit=20_000_000)
        print(f"   {url[:110]}: status {status} type {h.get('Content-Type')} bytes {len(body)}")
        if status == 200:
            try:
                doc = json.loads(body)
                if "layers" in doc:
                    print("   layers:", [(l.get("id"), l.get("name")) for l in doc["layers"]][:40])
                elif "features" in doc:
                    print("   features:", len(doc["features"]), "first:", json.dumps(doc["features"][0])[:900] if doc["features"] else "")
                else:
                    print("   ", json.dumps(doc)[:800])
            except ValueError:
                print("    ", body[:700].replace("\n", " | "))


def dot_midwest_5():
    """Fifth round: the CARS services by name (events_v1, cameras_v1, signs_v1, the WZDx feed), South Dakota's inline
    layer code, Michigan's and Missouri's inline page code, North Dakota's ArcGIS layers."""
    for state, site, tg, st in (("Indiana", "https://511in.org", "intg", "in"), ("Minnesota", "https://511mn.org", "mntg", "mn"),
                                ("Iowa", "https://511ia.org", "iatg", "ia"), ("Nebraska", "https://511.nebraska.gov", "netg", "ne"),
                                ("Kansas", "https://www.kandrive.gov", "kstg", "ks")):
        head(f"{state}: CARS services")
        try:
            status, final, _, page = get(site + "/", limit=2_000_000)
            for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:12]:
                if "google" in src:
                    continue
                src = urllib.parse.urljoin(final, src)
                _, _, _, js = get(src, limit=12_000_000)
                i = js.find("signs_v1")
                if i >= 0:
                    print("   config ...", js[max(0, i - 900):i + 500].replace("\n", " "), "...")
                for m in list(re.finditer(r"""_v1/api[A-Za-z0-9_/${}.-]{0,60}""", js))[:40]:
                    pass
                print("   service paths:", sorted(set(re.findall(r"""[a-z-]+_v1/api[A-Za-z0-9_/.-]{0,50}""", js)))[:40])
                print("   graphql/rest:", sorted(set(re.findall(r"""/api/(?:graphql|v1)[A-Za-z0-9_/.-]{0,40}""", js)))[:20])
            base = f"https://{tg}.carsprogram.org"
            for path in ("/events_v1/api", "/events_v1/api/events", "/events_v1/api/map-features", "/cameras_v1/api", "/cameras_v1/api/cameras",
                         "/signs_v1/api", "/signs_v1/api/signs", "/rest-areas_v1/api", "/traveltimes_v1/api/travel-times", "/api/graphql"):
                status, _, h, body = get(base + path, headers={"Accept": "application/json, */*", "Referer": site + "/", "Origin": site}, limit=20_000_000)
                print(f"   {base + path}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:400].replace("\n", " "))
            for url in (f"https://{st}.carsprogram.org/carsapi_v1/api/wzdx", f"https://{tg}.carsprogram.org/carsapi_v1/api/wzdx"):
                status, _, h, body = get(url, headers={"Accept": "application/json"}, limit=40_000_000)
                print(f"   WZDx {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:700].replace("\n", " "))
        except Exception as e:
            print("   failed:", repr(e)[:200])

    head("South Dakota: the page's own layer code")
    status, final, _, page = get("https://www.sd511.org/", headers=BROWSER, limit=4_000_000)
    for m in list(re.finditer(r"geojson|cdn\.iteris-atis\.com|aggregator\.iteris", page))[:40]:
        print("   ...", page[max(0, m.start() - 160):m.start() + 160].replace("\n", " "), "...")
    print("   urls:", sorted(set(u for u in re.findall(r"""https?://[^"' <>]+""", page) if "iteris" in u or "geojson" in u or "json" in u))[:40])
    for url in ("https://sd.cdn.iteris-atis.com/geojson/", "https://sd.cdn.iteris-atis.com/geojson/incidents.geojson", "https://sd.cdn.iteris-atis.com/geojson/events.geojson",
                "https://sd.cdn.iteris-atis.com/geojson/cameras.geojson", "https://sd.cdn.iteris-atis.com/geojson/dms.geojson",
                "https://sd.cdn.iteris-atis.com/geojson/icons/metadata/icons.json", "https://sd.cdn.iteris-atis.com/geojson/road_conditions.geojson"):
        status, _, h, body = get(url, headers=BROWSER, limit=20_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:300].replace("\n", " "))

    head("Michigan: Mi Drive's inline page code")
    status, _, _, page = get("https://mdotjboss.state.mi.us/MiDrive/map", headers=BROWSER, limit=4_000_000)
    for block in re.findall(r"<script(?![^>]*src)[^>]*>(.*?)</script>", page, flags=re.S)[:10]:
        lines = [l.strip() for l in block.splitlines() if re.search(r"[Uu]rl|api|server|\.json|Layer\(", l)]
        print("   inline:", lines[:40])
    print("   quoted relative paths:", sorted(set(re.findall(r"""["']([A-Za-z0-9_][A-Za-z0-9_./-]*/[A-Za-z0-9_./?=&-]+)["']""", page)))[:40])
    for path in ("public/api/incident/all", "public/api/incidents/all", "public/api/camera/all", "public/api/cameras/all", "public/api/dms/all",
                 "public/api/construction/all", "public/api/incident", "public/api/camera", "public/api/event/all", "public/page/incidents",
                 "camera/favoriteCameras/", "public/api/truckparking/all", "public/api/speed/all"):
        url = "https://mdotjboss.state.mi.us/MiDrive/" + path
        status, _, h, body = get(url, headers={**BROWSER, "Accept": "application/json", "X-Requested-With": "XMLHttpRequest"}, limit=20_000_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:300].replace("\n", " "))

    head("Missouri: traveler.modot.org inline code")
    for url in ("https://traveler.modot.org/map/", "https://traveler.modot.org/mapv3/index.html"):
        status, _, _, page = get(url, headers=BROWSER, limit=4_000_000)
        blocks = re.findall(r"<script(?![^>]*src)[^>]*>(.*?)</script>", page, flags=re.S)
        print(f"   {url}: {len(blocks)} inline scripts, {sum(len(b) for b in blocks)} bytes")
        for block in blocks[:6]:
            hits = sorted(set(re.findall(r"""["']([A-Za-z0-9_][A-Za-z0-9_./-]*(?:/|\.json|\.js|Server)[A-Za-z0-9_./?=&-]*)["']""", block)))
            print("     strings:", hits[:50])
            for m in list(re.finditer(r"""(?:url|URL|Url)\s*[:=]\s*["'`]([^"'`]+)["'`]""", block))[:20]:
                print("     url:", m.group(1)[:150])
        print("   iframes/links:", re.findall(r'<(?:iframe|link|a)[^>]+(?:src|href)="([^"]+)"', page)[:20])

    head("North Dakota: rcrs_dynamic layers")
    base = "https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer"
    for layer in (5, 37, 7, 41, 21, 22, 31, 32):
        url = f"{base}/{layer}/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geojson"
        status, _, h, body = get(url, headers=BROWSER, limit=30_000_000)
        print(f"   layer {layer}: status {status} type {h.get('Content-Type')} bytes {len(body)}")
        try:
            doc = json.loads(body)
            feats = doc.get("features", [])
            from collections import Counter
            print("   features:", len(feats), "geometry:", Counter((f.get("geometry") or {}).get("type") for f in feats).most_common(3))
            if feats:
                print("   keys:", list((feats[0].get("properties") or {}).keys())[:40])
                for f in feats[:2]:
                    print("   feature:", json.dumps(f)[:1200])
        except ValueError:
            print("    ", body[:400].replace("\n", " "))


def _post_json(url, body, headers=None, limit=20_000_000):
    """POST a JSON body; (status, headers, parsed or None, text)."""
    status, _, h, text = get(url, headers={**BROWSER, "Accept": "application/json", "Content-Type": "application/json", **(headers or {})},
                             method="POST", data=body.encode(), limit=limit)
    return status, h, _json(text), text


def _values(features, key, limit=12):
    from collections import Counter
    return Counter(str((f.get("properties") or {}).get(key)) for f in features).most_common(limit)


def dot_midwest_6():
    """Sixth round: Travel Midwest's whole feature shapes (fixtures for core/traffic TravelMidwest), 511WI's closure
    icons, North Dakota's work zone and alert properties, South Dakota's CDN files by their real names."""
    head("Illinois: Travel Midwest features in full")
    base = "https://travelmidwest.com/lmiga/"
    for path in ("incidentMap.json", "constructionMap.json?type=lines", "cameraMap.json", "dmsMap.json"):
        status, h, doc, text = _post_json(base + path, "{}", headers={"Referer": "https://travelmidwest.com/"})
        feats = doc.get("features", []) if isinstance(doc, dict) else []
        print(f"   {path}: status {status} bytes {len(text)} top keys {list(doc)[:8] if isinstance(doc, dict) else type(doc).__name__} features {len(feats)}")
        if not feats:
            print("    ", text[:600].replace("\n", " "))
            continue
        from collections import Counter
        print("   id prefixes:", Counter((f.get("properties") or {}).get("id", "?")[:8].split("-")[0] for f in feats).most_common(12))
        print("   agencies:", Counter((f.get("properties") or {}).get("id", "?").split("-")[1] if "-" in (f.get("properties") or {}).get("id", "") else "?" for f in feats).most_common(16))
        print("   geometry:", Counter((f.get("geometry") or {}).get("type") for f in feats).most_common(4),
              "inner:", Counter(g.get("type") for f in feats for g in ((f.get("geometry") or {}).get("geometries") or [])).most_common(4))
        for key in ("desc", "stat", "lanes", "src", "closure", "sev", "dur", "ev", "biDir", "locDir", "dis", "video", "extOff", "a", "idotDistrict"):
            if any(key in (f.get("properties") or {}) for f in feats):
                print(f"   {key}:", _values(feats, key))
        # Whole features, the first three and the first three from other states, for fixtures.
        shown = 0
        for f in feats[:3]:
            print("   feature:", json.dumps(f)[:3000])
        for f in feats:
            pid = (f.get("properties") or {}).get("id", "")
            if not pid.startswith("IL-"):
                print("   other-state feature:", json.dumps(f)[:1500])
                shown += 1
                if shown >= 2:
                    break
        if "incident" in path or "construction" in path:
            closures = [f for f in feats if "all lanes" in str((f.get("properties") or {}).get("closure", "")).lower() or (f.get("properties") or {}).get("lanes") == "full"]
            print(f"   closures by wording: {len(closures)}")
            for f in closures[:3]:
                print("   closure:", json.dumps(f.get("properties"))[:800])
    # Without a Content-Type, and a GET, for the reader's request shape; a still and a sign image.
    status, _, h, text = get(base + "incidentMap.json", headers={**BROWSER, "Accept": "application/json"}, method="POST", data=b"", limit=2_000_000)
    print(f"   POST without body or type: status {status} type {h.get('Content-Type')} bytes {len(text)}")
    status, _, h, text = get(base + "incidentMap.json", headers={"User-Agent": UA, "Accept": "application/json", "Content-Type": "application/json"},
                             method="POST", data=b"{}", limit=2_000_000)
    print(f"   POST with the app's own User-Agent: status {status} type {h.get('Content-Type')} bytes {len(text)}")
    status, ctype, body = _raw("https://cctv.travelmidwest.com/snapshots/IL-ISTHA_1_Will_SEB_I-355-CASH_4157660_-8800050_1_S.jpg")
    print(f"   snapshot: status {status} type {ctype} bytes {len(body)} jpeg {body[:3] == b'\xff\xd8\xff'}")
    status, ctype, body = _raw("https://travelmidwest.com/lmiga/messageSign?id=IL-TESTTSC-KE-IRIVINGPK&small=true")
    print(f"   sign image: status {status} type {ctype} bytes {len(body)}")

    head("Wisconsin: which icon layer carries the closures")
    site = "https://511wi.gov"
    for layer in ("Closures", "ConstructionClosures", "Construction", "RoadworkClosures", "Roadwork", "Incidents", "Alerts", "WinterRoadConditions", "SpecialEvents", "RestAreas"):
        status, d = try_json(f"{site}/map/mapIcons/{layer}", sample=300)
        items = d.get("item2") if isinstance(d, dict) and isinstance(d.get("item2"), list) else None
        print(f"   mapIcons {layer}: status {status} items {len(items) if items is not None else '?'}", json.dumps(items[0])[:300] if items else "")
    rows, total = _ibi_rows(site, "Closures", limit=5)
    from collections import Counter
    print(f"   Closures list: {len(rows)} of {total}; layerName {Counter(r.get('layerName') for r in rows).most_common(6)}; type {Counter(r.get('type') for r in rows).most_common(6)}")
    print("   first closure row:", json.dumps(rows[0])[:1200] if rows else None)
    ids = {str(r.get("DT_RowId") or r.get("id")) for r in rows}
    for layer in ("Construction", "ConstructionClosures", "Closures"):
        status, d = try_json(f"{site}/map/mapIcons/{layer}", sample=0)
        items = d.get("item2") if isinstance(d, dict) and isinstance(d.get("item2"), list) else []
        icon_ids = {str(i.get("itemId")) for i in items}
        print(f"   closure rows with an icon in {layer}: {len(ids & icon_ids)} of {len(ids)}")
    rows, total = _ibi_rows(site, "traffic", limit=3)
    print(f"   traffic list: {len(rows)} of {total}; type {Counter(r.get('type') for r in rows).most_common(6)}; layerName {Counter(r.get('layerName') for r in rows).most_common(6)}")
    for r in rows[:2]:
        print("   traffic row:", json.dumps(r)[:1200])

    head("North Dakota: work zones, alerts and cameras in full")
    base = "https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer"
    status, _, _, body = get(base + "?f=json", headers=BROWSER, limit=2_000_000)
    doc = _json(body)
    if isinstance(doc, dict):
        print("   layers:", [(l.get("id"), l.get("name"), l.get("geometryType")) for l in doc.get("layers", [])])
    for layer, label in ((7, "Alerts"), (21, "Work zone points"), (22, "Work zone lines"), (41, "Travel alerts"), (37, "Video cameras"), (10, "Wind")):
        url = f"{base}/{layer}/query?where=1%3D1&outFields=*&returnGeometry=false&f=json"
        status, _, h, body = get(url, headers=BROWSER, limit=30_000_000)
        doc = _json(body)
        feats = doc.get("features", []) if isinstance(doc, dict) else []
        print(f"   layer {layer} {label}: status {status} features {len(feats)} fields {[f.get('name') for f in (doc.get('fields') or [])][:40] if isinstance(doc, dict) else None}")
        for f in feats[:4]:
            print("   attributes:", json.dumps(f.get("attributes"))[:1400])
        if feats:
            for key in ("WorkType", "Active", "LaneReduction", "DelayDesc", "ConditionDesc", "MapIconDesc", "Type", "TravelSpeedsDesc", "DataProvider"):
                if key in (feats[0].get("attributes") or {}):
                    print(f"   {key}:", Counter(str((f.get('attributes') or {}).get(key)) for f in feats).most_common(10))
    status, ctype, body = _raw("https://www.dot.nd.gov/travel-info/cameras/capitalnw00001.jpg")
    print(f"   camera still: status {status} type {ctype} bytes {len(body)} jpeg {body[:3] == b'\xff\xd8\xff'}")
    for layer in (31, 32):
        url = f"{base}/{layer}/query?where=1%3D1&outFields=ConditionDesc,CondDesc,ConditionCategory,TravelSpeeds,Comment,HwyDesc,Direction,EffectiveUntil&returnGeometry=false&f=json"
        status, _, _, body = get(url, headers=BROWSER, limit=30_000_000)
        doc = _json(body)
        feats = doc.get("features", []) if isinstance(doc, dict) else []
        print(f"   layer {layer} road conditions: {len(feats)};", "ConditionDesc", Counter(str((f.get('attributes') or {}).get('ConditionDesc')) for f in feats).most_common(8),
              "category", Counter(str((f.get('attributes') or {}).get('ConditionCategory')) for f in feats).most_common(6))
        for f in feats[:2]:
            print("   attributes:", json.dumps(f.get("attributes"))[:600])

    head("South Dakota: the CDN's GeoJSON by the page's names")
    meta = "https://sd.cdn.iteris-atis.com/geojson/icons/metadata/"
    for name in ("active_jurisdictions.geojson", "icons.road-work.geojson", "icons.incidents-accidents.geojson", "icons.restriction.geojson",
                 "icons.disturbances.geojson", "icons.obstructions.geojson", "icons.scheduled-events.geojson", "icons.cameras.geojson", "icons.rwis.geojson",
                 "icons.road-conditions.geojson", "icons.disasters.geojson"):
        for headers in ({**BROWSER, "Referer": "https://www.sd511.org/", "Origin": "https://www.sd511.org"}, {"User-Agent": UA}):
            status, _, h, body = get(meta + name, headers=headers, limit=20_000_000)
            print(f"   {name} ({'browser' if 'Referer' in headers else 'app'}): status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:500].replace("\n", " "))
            if status == 200:
                doc = _json(body)
                feats = doc.get("features", []) if isinstance(doc, dict) else []
                print(f"   features {len(feats)}; keys {list((feats[0].get('properties') or {}).keys())[:30] if feats else None}")
                for f in feats[:2]:
                    print("   feature:", json.dumps(f)[:1200])
                break
    status, final, _, page = get("https://www.sd511.org/", headers=BROWSER, limit=4_000_000)
    for m in list(re.finditer(r"""(?:cdnLink|cdn_metadata)\s*\+\s*["']([^"']+)["']""", page))[:40]:
        print("   cdn path:", m.group(1))
    for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:30]:
        if any(x in src for x in ("jquery", "bootstrap", "google", "x2js", "leaflet", "openlayers", "ol.js")):
            continue
        src = urllib.parse.urljoin(final, src)
        _, _, _, js = get(src, headers=BROWSER, limit=6_000_000)
        hits = sorted(set(re.findall(r"""(?:cdnLink|cdn_metadata|cdn_geo)\s*\+\s*["']([^"']+)["']""", js)))
        urls = sorted(set(u for u in re.findall(r"""https?://[^"' <>]+""", js) if "iteris" in u))
        if hits or urls:
            print(f"   {src[-60:]}: paths {hits[:30]} urls {urls[:20]}")


def dot_midwest_7():
    """Seventh round: the CARS sites' GraphQL (events), one camera and one sign in full from each; Michigan's and
    Missouri's script files, for the feeds their maps read."""
    for state, site, tg in (("Indiana", "https://511in.org", "intg"), ("Minnesota", "https://511mn.org", "mntg"), ("Iowa", "https://511ia.org", "iatg"),
                            ("Nebraska", "https://511.nebraska.gov", "netg"), ("Kansas", "https://www.kandrive.gov", "kstg")):
        head(f"{state}: GraphQL at the site, cameras and signs in full")
        gql = site + "/api/graphql"
        status, _, h, body = get(gql, headers=BROWSER, limit=2_000_000)
        print(f"   GET {gql}: status {status} type {h.get('Content-Type')}:", body[:300].replace("\n", " "))
        for name, query in (("query type", '{"query":"{ __schema { queryType { fields { name args { name type { name kind ofType { name } } } } } } }"}'),
                            ("MapFeaturesArgs", '{"query":"{ __type(name: \\"MapFeaturesArgs\\") { inputFields { name type { name kind ofType { name kind ofType { name kind ofType { name } } } } } } }"}'),
                            ("LayerSlug", '{"query":"{ __type(name: \\"LayerSlug\\") { enumValues { name } } }"}'),
                            ("features", '{"query":"query ($input: MapFeaturesArgs!) { mapFeaturesQuery(input: $input) { mapFeatures { bbox tooltip uri features { id geometry properties } } } }","variables":{"input":{"north":49.5,"south":36,"east":-84,"west":-104.5,"zoom":7,"layerSlugs":["normalEvents"],"nonClusterableUris":["dashboard"]}}}'),
                            ("features by name", '{"query":"query MapFeatures($input: MapFeaturesArgs!) { mapFeaturesQuery(input: $input) { mapFeatures { bbox tooltip uri features { id geometry properties } __typename } error { message type } } }","variables":{"input":{"north":49.5,"south":36,"east":-84,"west":-104.5,"zoom":7,"layerSlugs":["normalEvents","closures"],"nonClusterableUris":["dashboard"]}}}')):
            status, h, doc, text = _post_json(gql, query, headers={"Referer": site + "/", "Origin": site})
            print(f"   POST {name}: status {status} type {h.get('Content-Type')} bytes {len(text)}:", text[:1500].replace("\n", " "))
        if state == "Indiana":
            # The bundle's own GraphQL documents and layer names, once.
            status, final, _, page = get(site + "/", limit=2_000_000)
            for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:12]:
                if "google" in src:
                    continue
                _, _, _, js = get(urllib.parse.urljoin(final, src), limit=12_000_000)
                for m in list(re.finditer(r"mapFeaturesQuery|MapFeaturesArgs|layerSlugs", js))[:6]:
                    print(f"   {src[-40:]} ...", js[max(0, m.start() - 700):m.start() + 900].replace("\n", " "), "...")
                slugs = sorted(set(re.findall(r"""["']([a-zA-Z]+(?:Events|Closures|Cameras|Signs|Conditions|Plows|Restrictions|Areas|Zones|Alerts|Stations))["']""", js)))
                if slugs:
                    print("   slug-like strings:", slugs[:80])
        base = f"https://{tg}.carsprogram.org"
        for path in ("/cameras_v1/api/cameras", "/signs_v1/api/signs"):
            status, _, h, body = get(base + path, headers={"Accept": "application/json", "Referer": site + "/", "Origin": site}, limit=20_000_000)
            doc = _json(body)
            rows = doc if isinstance(doc, list) else []
            print(f"   {path}: status {status} rows {len(rows)} keys {list(rows[0].keys())[:30] if rows else None}")
            for r in rows[:2]:
                print("   row:", json.dumps(r)[:2500])
            if rows and "signs" in path:
                from collections import Counter
                print("   status:", Counter(r.get("status") for r in rows).most_common(8), "agencies:", Counter(r.get("agencyName") for r in rows).most_common(8))
            if rows and "cameras" in path:
                from collections import Counter
                views = [v for r in rows for v in (r.get("views") or [])]
                print(f"   views {len(views)}; view keys {list(views[0].keys())[:20] if views else None}; owners", Counter((r.get("cameraOwner") or {}).get("name") for r in rows).most_common(8))
                hosts = Counter(urllib.parse.urlsplit(v.get("url") or "").netloc for v in views).most_common(6)
                print("   view url hosts:", hosts)
                for key in ("url", "imageUrl", "stillUrl", "thumbnailUrl", "videoUrl"):
                    vals = [v.get(key) for v in views if v.get(key)]
                    if vals:
                        print(f"   view {key}: {len(vals)} e.g. {vals[0][:160]}")
                for v in views[:3]:
                    url = v.get("url") or ""
                    if url.startswith("https://") and not url.endswith(".m3u8"):
                        status, ctype, body = _raw(url)
                        print(f"   view fetch: status {status} type {ctype} bytes {len(body)} jpeg {body[:3] == b'\xff\xd8\xff'}")

    head("Michigan: Mi Drive's scripts")
    status, final, _, page = get("https://mdotjboss.state.mi.us/MiDrive/map", headers=BROWSER, limit=4_000_000)
    srcs = re.findall(r'<script[^>]+src="([^"]+)"', page)
    print("   scripts:", srcs[:40])
    for src in srcs[:40]:
        if any(x in src for x in ("jquery", "bootstrap", "google", "gtag", "arcgis", "dojo", "esri")):
            continue
        src = urllib.parse.urljoin(final, src)
        status, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
        calls = sorted(set(re.findall(r"""(?:url|URL)\s*[:=]\s*["'`]([^"'`]{3,120})["'`]""", js)))
        gets = [js[m.start():m.start() + 200].replace("\n", " ") for m in list(re.finditer(r"\.(?:getJSON|ajax|get|post|fetch)\(", js))[:10]]
        paths = sorted(set(re.findall(r"""["']((?:/MiDrive/|public/|api/|rest/)[A-Za-z0-9_./?=&{}-]{2,100})["']""", js)))
        print(f"   {src[-70:]}: status {status} bytes {len(js)}; urls {calls[:40]}; paths {paths[:40]}")
        for g in gets[:6]:
            print("     call:", g)

    head("Missouri: traveler.modot.org scripts")
    for url in ("https://traveler.modot.org/map/", "https://traveler.modot.org/mapv3/index.html"):
        status, final, _, page = get(url, headers=BROWSER, limit=4_000_000)
        srcs = re.findall(r'<script[^>]+src="([^"]+)"', page)
        print(f"   {url}: scripts {srcs[:40]}")
        for src in srcs[:40]:
            if any(x in src for x in ("ruxit", "jquery", "bootstrap", "google", "js.arcgis.com", "video-js", "video.min")):
                continue
            src = urllib.parse.urljoin(final, src)
            status, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
            urls = sorted(set(u for u in re.findall(r"""https?://[^"' <>\\]+""", js) if not any(x in u for x in ("w3.org", "arcgis.com/", "esri.com", "googleapis"))))
            rel = sorted(set(re.findall(r"""["']([A-Za-z0-9_][A-Za-z0-9_./-]*(?:rest/services|/api/|\.json|\.ashx|\.aspx|\.php|Server)[A-Za-z0-9_./?=&-]*)["']""", js)))
            print(f"   {src[-70:]}: status {status} bytes {len(js)}; urls {urls[:40]}; paths {rel[:40]}")


def dot_midwest_8():
    """Eighth round: which layer slugs the CARS sites' mapFeaturesQuery answers (one at a time), what an Event
    carries, and the dashboard's collections; Mi Drive's AllForMap feeds."""
    gql_features = ('{"query":"query MapFeatures($input: MapFeaturesArgs!) { mapFeaturesQuery(input: $input) { mapFeatures { bbox tooltip uri '
                    'features { id geometry properties } __typename } error { message type } } }",'
                    '"variables":{"input":{"north":49.5,"south":36,"east":-84,"west":-104.5,"zoom":7,"layerSlugs":[%s],"nonClusterableUris":["dashboard"]}}}')
    slugs = ("incidents", "closures", "construction", "future", "normalEvents", "roadwork", "events", "roadReports", "unplannedEvents",
             "plannedEvents", "incident", "closure", "roadConditions", "WazeAlerts", "weatherWarningsAreaEvents", "electronicSigns", "normalCameras")
    for state, site in (("Indiana", "https://511in.org"), ("Minnesota", "https://511mn.org"), ("Iowa", "https://511ia.org"),
                        ("Nebraska", "https://511.nebraska.gov"), ("Kansas", "https://www.kandrive.gov")):
        head(f"{state}: layer slugs one at a time")
        gql = site + "/api/graphql"
        from collections import Counter
        for slug in slugs:
            status, h, doc, text = _post_json(gql, gql_features % json.dumps(slug), headers={"Referer": site + "/", "Origin": site})
            data = (doc or {}).get("data") if isinstance(doc, dict) else None
            mf = ((data or {}).get("mapFeaturesQuery") or {}).get("mapFeatures") if isinstance(data, dict) else None
            if not isinstance(mf, list):
                print(f"   {slug}: status {status} bytes {len(text)}:", text[:300].replace("\n", " "))
                continue
            icons = Counter(((f.get("properties") or {}).get("icon") or {}).get("url") for m in mf for f in (m.get("features") or []))
            types = Counter(m.get("__typename") for m in mf)
            uris = Counter((m.get("uri") or "").split("/")[0] for m in mf)
            print(f"   {slug}: {len(mf)} map features, {len(text)} bytes; types {types.most_common(4)}; uri kinds {uris.most_common(4)}; icons {icons.most_common(6)}")
            if mf and slug in ("incidents", "closures", "construction", "future", "normalEvents", "roadwork", "events"):
                print("   first:", json.dumps(mf[0])[:1500])
        # What an Event carries, on the map features and in the dashboard's collections.
        for name, query in (
            ("event fields", gql_features.replace("features { id geometry properties } __typename", "features { id geometry properties } __typename ... on Event { title description priority isWazeEvent cityReference color icon agencyAttribution { agencyName } lastUpdated { timestamp timezone } beginTime { timestamp timezone } endTime { timestamp timezone } }") % '"closures","incidents","construction","normalEvents"'),
            ("dashboard collections", '{"query":"query Dashboard($layerSlugs: [String!]!) { dashboardQuery { collections(layerSlugs: $layerSlugs) { uri title cityReference bbox icon color __typename ... on Event { description priority isWazeEvent agencyAttribution { agencyName } lastUpdated { timestamp timezone } beginTime { timestamp timezone } } } } }","variables":{"layerSlugs":["closures","incidents","construction","normalEvents"]}}'),
            ("event query", '{"query":"query Event($eventId: ID!) { eventQuery(eventId: $eventId) { event { uri title description bbox features { id geometry properties } } error { type message } } }","variables":{"eventId":"CARSx-43373"}}'),
        ):
            status, h, doc, text = _post_json(gql, query, headers={"Referer": site + "/", "Origin": site})
            print(f"   {name}: status {status} bytes {len(text)}:", text[:2500].replace("\n", " "))

    head("Michigan: Mi Drive's AllForMap feeds")
    base = "https://mdotjboss.state.mi.us/MiDrive/"
    for path in ("camera/AllForMap", "construction/AllForMap", "dms/AllForMap", "incidents/AllForMap", "incidents/AllForPage", "plows/AllForMap",
                 "tollBridges/allForMap"):
        for headers in ({**BROWSER, "Accept": "application/json, text/javascript, */*; q=0.01", "X-Requested-With": "XMLHttpRequest", "Referer": base + "map"},
                        {"User-Agent": UA, "Accept": "application/json"}):
            status, _, h, body = get(base + path, headers=headers, limit=30_000_000)
            label = "browser" if "Referer" in headers else "app"
            print(f"   {path} ({label}): status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:300].replace("\n", " "))
            doc = _json(body)
            rows = doc if isinstance(doc, list) else (doc.get("data") or doc.get("features") or doc.get("items")) if isinstance(doc, dict) else None
            if isinstance(doc, dict):
                print("   top keys:", list(doc)[:12])
            if isinstance(rows, list):
                print(f"   rows {len(rows)}; keys {list(rows[0].keys())[:40] if rows and isinstance(rows[0], dict) else None}")
                for r in rows[:3]:
                    print("   row:", json.dumps(r)[:1500])
            if status == 200:
                break
    for path in ("camera/getCameraInformation/", "dms/getDMSInfo/", "construction/getConstructionInformation/"):
        status, _, h, body = get(base + path + "1", headers={**BROWSER, "Accept": "application/json", "X-Requested-With": "XMLHttpRequest", "Referer": base + "map"}, limit=2_000_000)
        print(f"   {path}1: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:600].replace("\n", " "))


def dot_midwest_9():
    """Ninth round: the CARS map query at higher zooms (do the clusters open up?), every event slug in one request,
    the dashboard with a priority; Mi Drive's details for real ids."""
    from collections import Counter
    query = ('{"query":"query MapFeatures($input: MapFeaturesArgs!) { mapFeaturesQuery(input: $input) { mapFeatures { bbox tooltip uri '
             'features { id geometry properties } __typename } error { message type } } }",'
             '"variables":{"input":{"north":%s,"south":%s,"east":%s,"west":%s,"zoom":%d,"layerSlugs":["incidents","closures","construction","roadReports"],"nonClusterableUris":["dashboard"]}}}')
    for state, site, box in (("Indiana", "https://511in.org", (41.8, 37.7, -84.7, -88.2)), ("Minnesota", "https://511mn.org", (49.5, 43.4, -89.4, -97.3)),
                             ("Iowa", "https://511ia.org", (43.6, 40.3, -90.0, -96.7)), ("Nebraska", "https://511.nebraska.gov", (43.1, 39.9, -95.2, -104.2)),
                             ("Kansas", "https://www.kandrive.gov", (40.1, 36.9, -94.5, -102.2))):
        head(f"{state}: clusters by zoom")
        gql = site + "/api/graphql"
        n, s_, e, w = box
        for zoom in (7, 10, 12, 14, 16):
            status, h, doc, text = _post_json(gql, query % (n, s_, e, w, zoom), headers={"Referer": site + "/", "Origin": site}, limit=60_000_000)
            data = (doc or {}).get("data") if isinstance(doc, dict) else None
            mf = ((data or {}).get("mapFeaturesQuery") or {}).get("mapFeatures") if isinstance(data, dict) else None
            if not isinstance(mf, list):
                print(f"   zoom {zoom}: status {status} bytes {len(text)}:", text[:300].replace("\n", " "))
                continue
            types = Counter(m.get("__typename") for m in mf)
            points = sum(1 for m in mf for f in (m.get("features") or []) if ((f.get("geometry") or {}).get("type") == "Point"))
            print(f"   zoom {zoom}: {len(mf)} map features, {len(text)} bytes; types {types.most_common(4)}; points {points}")
            if zoom == 14 and mf:
                cl = next((m for m in mf if m.get("__typename") == "Cluster"), None)
                if cl:
                    print("   a cluster:", json.dumps(cl)[:1200])
                icons = Counter(((f.get("properties") or {}).get("icon") or {}).get("url") for m in mf if m.get("__typename") == "Event" for f in (m.get("features") or []))
                print("   event icons:", icons.most_common(20))
                tips = [m.get("tooltip") for m in mf if m.get("__typename") == "Event"][:12]
                print("   tooltips:", json.dumps(tips)[:2500])
        for name, q in (("dashboard priority 10", '{"query":"query Dashboard($layerSlugs: [String!]!, $maxPriority: Int) { dashboardQuery { collections(layerSlugs: $layerSlugs, maxPriority: $maxPriority) { uri title cityReference bbox icon color __typename ... on Event { description priority isWazeEvent lastUpdated { timestamp timezone } beginTime { timestamp timezone } } } } }","variables":{"layerSlugs":["incidents","closures","construction","roadReports"],"maxPriority":10}}'),
                        ("dashboard no filter", '{"query":"{ dashboardQuery { collections(layerSlugs: [\\"incidents\\",\\"closures\\",\\"construction\\",\\"roadReports\\",\\"future\\"]) { uri title bbox icon __typename } } }"}')):
            status, h, doc, text = _post_json(gql, q, headers={"Referer": site + "/", "Origin": site}, limit=20_000_000)
            print(f"   {name}: status {status} bytes {len(text)}:", text[:600].replace("\n", " "))

    head("Michigan: Mi Drive details for real ids")
    base = "https://mdotjboss.state.mi.us/MiDrive/"
    hdr = {**BROWSER, "Accept": "application/json, text/javascript, */*; q=0.01", "X-Requested-With": "XMLHttpRequest", "Referer": base + "map"}
    for path in ("camera/getCameraInformation/1129", "camera/getCameraInformation/2113", "dms/getDMSInfo/3067", "dms/getDMSInfo/17624",
                 "construction/getConstructionInformation/ETX-21385", "construction/getConstructionInformation/ETX-21250", "incidents/AllForMap"):
        status, _, h, body = get(base + path, headers=hdr, limit=4_000_000)
        print(f"   {path}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:2500].replace("\n", " "))
    status, _, h, body = get(base + "camera/AllForMap", headers=hdr, limit=4_000_000)
    doc = _json(body)
    if isinstance(doc, list):
        print("   camera icons:", Counter(r.get("icon") for r in doc).most_common(5), "links:", Counter(bool(r.get("link")) for r in doc), "orientations:", Counter(r.get("orientation") for r in doc).most_common(6))
        for r in doc:
            if r.get("link"):
                print("   camera with link:", json.dumps(r)[:600])
                break
    status, _, h, body = get(base + "construction/AllForMap", headers=hdr, limit=8_000_000)
    doc = _json(body)
    if isinstance(doc, list):
        print("   construction icons:", Counter(r.get("icon") for r in doc).most_common(8), "active:", Counter(r.get("active") for r in doc))
        print("   titles:", Counter(r.get("title", "").split(":")[-1].strip() for r in doc).most_common(15))
    for url in ("https://mdotnetpublic.state.mi.us/drive/images/inc_red_18px.png", "https://mdotjboss.state.mi.us/MiDrive/camera/image/1129", "https://mdotjboss.state.mi.us/MiDrive/camera/1129/image"):
        status, ctype, body = _raw(url)
        print(f"   {url}: status {status} type {ctype} bytes {len(body)}")


def dot_midwest_10():
    """Tenth round: OHGO's swagger definitions in full (Ohio's reader is written against them: the API wants a key
    the owner enters in Settings, so the probe sees no data), and the docs pages' words on the key and the result shape."""
    head("Ohio: OHGO swagger definitions")
    status, _, h, body = get("https://publicapi.ohgo.com/docs/v1/swagger.json", headers=BROWSER, limit=4_000_000)
    print(f"   spec: status {status} bytes {len(body)}")
    doc = _json(body)
    if isinstance(doc, dict):
        print("   host/basePath:", doc.get("host"), doc.get("basePath"), doc.get("servers"), "security:", json.dumps(doc.get("securityDefinitions") or doc.get("components", {}).get("securitySchemes"))[:600])
        defs = doc.get("definitions") or doc.get("components", {}).get("schemas", {})
        for name in ("Camera", "CameraView", "CameraApiResult", "Construction", "ConstructionWorkZone", "ConstructionDetour", "Incident", "IncidentApiResult",
                     "DigitalSign", "DigitalSignApiResult", "Link", "DangerousSlowdown", "TravelDelay", "Location", "Geometry"):
            sc = defs.get(name)
            if sc is None:
                print(f"   {name}: (no definition)")
                continue
            print(f"   {name}:", json.dumps(sc)[:3000])
        for path in ("/api/v1/cameras", "/api/v1/incidents", "/api/v1/construction", "/api/v1/digital-signs"):
            op = (doc.get("paths", {}).get(path) or {}).get("get") or {}
            print(f"   GET {path}: params", json.dumps([{k: p.get(k) for k in ("name", "in", "type", "required", "description", "enum")} for p in op.get("parameters", [])])[:2500])
            print(f"   GET {path}: responses", json.dumps(op.get("responses"))[:600], "| description:", (op.get("description") or op.get("summary") or "")[:600].replace("\n", " "))
        print("   all definitions:", sorted(defs.keys()))
    for page in ("/docs/api-key", "/docs/registration", "/docs/v1/query-params", "/docs/v1/filters", "/docs/v1/api-results", "/docs/cache", "/docs/v1/incidents",
                 "/docs/v1/construction", "/docs/v1/digital-signs", "/docs/v1/cameras", "/docs/terms-of-use"):
        status, _, h, body = get("https://publicapi.ohgo.com" + page, headers=BROWSER, limit=2_000_000)
        print(f"   {page}: status {status} bytes {len(body)}:", text_of(body)[:1800].replace("\n", " | "))
    # Without a key: the error shape and headers (the app shows this when the key is missing or wrong).
    for url in ("https://publicapi.ohgo.com/api/v1/cameras?page-size=1", "https://publicapi.ohgo.com/api/v1/incidents?page-all=true"):
        status, _, h, body = get(url, headers={"User-Agent": UA, "Accept": "application/json"}, limit=200_000)
        print(f"   {url}: status {status} type {h.get('Content-Type')} headers {dict((k, v) for k, v in h.items() if k.lower() in ('www-authenticate', 'cache-control', 'x-ratelimit-limit'))}:", body[:400].replace("\n", " "))


def app_requests():
    """Every Midwest request exactly as the phone makes it (NetworkGate: User-Agent "Southbound", Java's default
    Accept, nothing else; a POST adds Content-Type and Accept JSON), so the shipped readers are known to be answered,
    and the build's DOT camera fetchers (pipeline/search/dot_cameras.py) run against every state."""
    from collections import Counter

    java_accept = "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2"
    app = {"User-Agent": "Southbound", "Accept": java_accept}
    app_post = {"User-Agent": "Southbound", "Content-Type": "application/json", "Accept": "application/json"}

    def show(label, status, h, body):
        ok = "OK " if status == 200 and body[:1] in ("{", "[") else "?? "
        print(f"   {ok}{label}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:160].replace("\n", " "))

    head("The phone's own requests, Midwest")
    q100 = urllib.parse.quote(json.dumps({"columns": [], "start": 0, "length": 100}, separators=(",", ":")))
    gets = [
        ("511WI icons", "https://511wi.gov/map/mapIcons/ConstructionClosures"),
        ("511WI list", f"https://511wi.gov/List/GetData/Cameras?query={q100}"),
        ("NDDOT alerts", "https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer/7/query?where=1%3D1&outFields=*&returnGeometry=false&f=json"),
        ("NDDOT cameras", "https://gis.dot.nd.gov/arcgis/rest/services/external/rcrs_dynamic/MapServer/5/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=json"),
        ("SD511 road work", "https://sd.cdn.iteris-atis.com/geojson/icons/metadata/icons.road-work.geojson"),
        ("SD511 cameras", "https://sd.cdn.iteris-atis.com/geojson/icons/metadata/icons.cameras.geojson"),
        ("Mi Drive incidents", "https://mdotjboss.state.mi.us/MiDrive/incidents/AllForMap"),
        ("Mi Drive construction", "https://mdotjboss.state.mi.us/MiDrive/construction/AllForMap"),
        ("Mi Drive cameras", "https://mdotjboss.state.mi.us/MiDrive/camera/AllForMap"),
        ("Mi Drive camera detail", "https://mdotjboss.state.mi.us/MiDrive/camera/getCameraInformation/1129"),
        ("OHGO without a key", "https://publicapi.ohgo.com/api/v1/cameras?page-all=true&api-key="),
    ]
    for tg in ("intg", "mntg", "iatg", "netg", "kstg"):
        gets.append((f"{tg} cameras", f"https://{tg}.carsprogram.org/cameras_v1/api/cameras"))
        gets.append((f"{tg} signs", f"https://{tg}.carsprogram.org/signs_v1/api/signs"))
    for label, url in gets:
        status, _, h, body = get(url, headers=app, limit=30_000_000)
        show(label, status, h, body)
    for label, url in (("Travel Midwest incidents", "https://travelmidwest.com/lmiga/incidentMap.json"),):
        status, _, h, body = get(url, headers=app_post, method="POST", data=b"{}", limit=30_000_000)
        show(label, status, h, body)
    query = ("query MapFeatures($input: MapFeaturesArgs!) { mapFeaturesQuery(input: $input) { mapFeatures { bbox tooltip uri "
             "features { id geometry properties } __typename } error { message type } } }")
    # The app's layer names per site (CarsStates.EVENT_LAYERS; Iowa's own, CarsStates.IOWA_LAYERS).
    usual = ["incidents", "closures", "construction", "roadReports"]
    for site, (w, so, e, n), slugs in (("https://511in.org", (-88.2, 37.7, -84.7, 41.8), usual), ("https://511mn.org", (-97.3, 43.4, -89.4, 49.5), usual),
                                       ("https://511ia.org", (-96.7, 40.3, -90.0, 43.6), ["iowaAppIncident", "iowaAppRoadwork"]),
                                       ("https://511.nebraska.gov", (-104.2, 39.9, -95.2, 43.1), usual),
                                       ("https://www.kandrive.gov", (-102.2, 36.9, -94.5, 40.1), usual)):
        body = json.dumps({"query": query, "variables": {"input": {"north": n, "south": so, "east": e, "west": w, "zoom": 14,
                           "layerSlugs": slugs, "nonClusterableUris": ["dashboard"]}}},
                          separators=(",", ":")).encode()
        status, _, h, text = get(site + "/api/graphql", headers=app_post, method="POST", data=body, limit=30_000_000)
        doc = _json(text)
        mf = (((doc or {}).get("data") or {}).get("mapFeaturesQuery") or {}).get("mapFeatures") if isinstance(doc, dict) else None
        events = sum(1 for m in mf if m.get("__typename") == "Event") if isinstance(mf, list) else None
        show(f"{site} map query ({events} events)", status, h, text)
        if isinstance(mf, list):
            icons = Counter(((f.get("properties") or {}).get("icon") or {}).get("url") for m in mf if m.get("__typename") == "Event"
                            for f in (m.get("features") or []) if (f.get("geometry") or {}).get("type") == "Point")
            print("      icons:", icons.most_common(8))

    head("The build's DOT camera lists, every state")
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pipeline", "search"))
    import dot_cameras
    for state, fetch in dot_cameras.FETCHERS.items():
        try:
            rows = fetch()
            print(f"   {state}: {len(rows)} cameras; first {rows[0] if rows else None}")
        except Exception as e:  # noqa: BLE001 - a probe reports every failure
            print(f"   {state}: FAILED {type(e).__name__}: {str(e)[:200]}")
    for state, why in dot_cameras.NO_LIST.items():
        print(f"   {state}: no list ({why})")


def iowa_slugs():
    """511IA answers the app's events query (incidents, closures, construction, roadReports) with nothing, while
    Travel Midwest carries Iowa CARS road work: read the layer names Iowa's own bundle defines and try each one."""
    head("Iowa: the layer names its own map uses")
    candidates = set()
    for site in ("https://511ia.org", "https://511in.org"):
        status, final, _, page = get(site + "/", headers=BROWSER, limit=2_000_000)
        for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:15]:
            if "google" in src:
                continue
            _, _, _, js = get(urllib.parse.urljoin(final, src), headers=BROWSER, limit=15_000_000)
            # Object literals (KEY:"value") and compiled TypeScript string enums (e.KEY="value").
            pairs = re.findall(r'([A-Z][A-Z0-9_]{2,40})\s*[:=]\s*"([a-z][A-Za-z0-9-]{2,40})"', js)
            for key in ("ROAD_REPORTS", "CONSTRUCTION", "WAZE_REPORTS", "WINTER_DRIVING", "INCIDENTS", "CLOSURES", "TRUCKERS_REPORTS"):
                for m in list(re.finditer(key + r'\b', js))[:2]:
                    print(f"   {site} {src[-40:]} {key} ...", js[max(0, m.start() - 120):m.start() + 160].replace("\n", " "), "...")
            slugs = sorted(set(v for k, v in pairs if any(w in k for w in ("EVENT", "CLOSURE", "CONSTRUCTION", "INCIDENT", "ROAD", "WORK", "REPORT", "LAYER", "WINTER", "TRAFFIC", "FUTURE", "WEATHER", "TRUCK", "DELAY", "RESTRICT", "SPECIAL", "PLANNED", "ALERT", "CRASH"))))
            for m in re.finditer(r"layerSlugs:\[([^\]]{1,200})\]", js):
                print(f"   {site} {src[-40:]} layerSlugs: {m.group(1)[:200]}")
            if slugs:
                print(f"   {site} {src[-40:]} enum slugs: {slugs[:120]}")
            if site.endswith("511ia.org"):
                candidates.update(slugs)
                candidates.update(re.findall(r'layerSlugs:\["([a-zA-Z]+)"', js))
    query = ("query MapFeatures($input: MapFeaturesArgs!) { mapFeaturesQuery(input: $input) { mapFeatures { bbox tooltip uri "
             "features { id geometry properties } __typename } error { message type } } }")
    from collections import Counter
    for slug in sorted(candidates)[:80]:
        body = json.dumps({"query": query, "variables": {"input": {"north": 43.6, "south": 40.3, "east": -90.0, "west": -96.7, "zoom": 14,
                           "layerSlugs": [slug], "nonClusterableUris": ["dashboard"]}}}, separators=(",", ":")).encode()
        status, _, h, text = get("https://511ia.org/api/graphql", headers={**BROWSER, "Content-Type": "application/json", "Accept": "application/json"},
                                 method="POST", data=body, limit=30_000_000)
        doc = _json(text)
        mf = (((doc or {}).get("data") or {}).get("mapFeaturesQuery") or {}).get("mapFeatures") if isinstance(doc, dict) else None
        if not isinstance(mf, list):
            print(f"   {slug}: status {status}:", text[:160].replace("\n", " "))
            continue
        types = Counter(m.get("__typename") for m in mf)
        icons = Counter(((f.get("properties") or {}).get("icon") or {}).get("url") for m in mf for f in (m.get("features") or []) if m.get("__typename") == "Event")
        print(f"   {slug}: {len(mf)} features; types {types.most_common(3)}; icons {icons.most_common(5)}")
        tips = [m.get("tooltip") for m in mf if m.get("__typename") == "Event"][:3]
        if tips:
            print(f"     tooltips: {json.dumps(tips)[:500]}")


# Virginia, West Virginia and Kentucky (owner, October 2026: "Add in virginia, west virginia, and a surrounding state
# of both. Full features as every other state"). First round: which platform each 511 runs (IBI's like 511PA, CARS
# like Kansas, or its own), the data URLs its pages and scripts name, USDOT's registry of state work-zone feeds,
# and public ArcGIS items (Kentucky's GoKY map is ArcGIS).
SOUTH_SITES = {
    "Virginia": ["https://www.511virginia.org", "https://511.vdot.virginia.gov"],
    "West Virginia": ["https://www.wv511.org", "https://wv511.org"],
    "Kentucky": ["https://goky.ky.gov", "https://511.ky.gov", "https://www.trimarc.org"],
}
SOUTH_STATES = ["VA", "WV", "KY"]


def _site_marks(site):
    """A site's home page: status, final URL, which platform marks it carries, and the data-like URLs it names."""
    status, final, headers, page = get(site + "/", headers=BROWSER, limit=4_000_000)
    print(f"   home: status {status} final {final} type {headers.get('Content-Type')} bytes {len(page)}; marks:",
          [m for m in ("mapIcons", "List/GetData", "Generated/Content", "ibigroup", "iteris", "carsprogram", "castlerock",
                       "arcgis", "esri", "one.network", "waze", "trafficland", "skyvdn", "511") if m.lower() in page.lower()])
    print("   title:", (re.findall(r"(?is)<title>(.*?)</title>", page) or ["?"])[0].strip()[:120])
    print("   data-ish URLs:", sorted(set(re.findall(r"""["'](https?://[^"' ]+(?:api|json|xml|rest|feed|geojson|FeatureServer|MapServer)[^"' ]*)["']""", page)))[:30])
    print("   data-ish paths:", sorted(set(re.findall(r"""["'](/[A-Za-z0-9_./-]*(?:api|json|xml|rest|data|map/|Service|geojson)[A-Za-z0-9_./?=&-]*)["']""", page)))[:40])
    return status, final, page


def _script_urls(final, page, n=14):
    """The data-like URLs and paths the page's own scripts (not the usual libraries) name."""
    for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:n]:
        src = urllib.parse.urljoin(final, src)
        if any(x in src for x in ("jquery", "bootstrap", "google", "gtag", "polyfill", "recaptcha", "analytics")):
            continue
        _, _, _, js = get(src, headers=BROWSER, limit=12_000_000)
        hits = sorted(set(re.findall(r"""["'`](https?://[^"'` ]+(?:api|json|rest|feed|geojson|FeatureServer|MapServer|camera|incident|event)[^"'` ]*)["'`]""", js, re.I)))
        paths = sorted(set(re.findall(r"""["'`](/?[A-Za-z0-9_./-]*(?:api/|json|data/|map/get|getIncidents|getCameras|GetData|mapIcons|geojson|configs?/)[A-Za-z0-9_./?=&{}-]*)["'`]""", js)))
        cars = sorted(set(re.findall(r"https://[a-z0-9.-]*carsprogram\.org", js)))
        if hits or paths or cars:
            print(f"   script {src[-80:]} ({len(js)} bytes):\n      urls {hits[:30]}\n      paths {paths[:30]}\n      cars {cars[:5]}")


def dot_south():
    head("USDOT WZDx feed registry: Virginia, West Virginia, Kentucky")
    status, d = try_json("https://data.transportation.gov/resource/69qe-yiui.json?$limit=500", sample=600)
    if isinstance(d, list):
        for row in d:
            text = json.dumps(row)
            if any(s in text for s in ("Virginia", "Kentucky", '"VA"', '"WV"', '"KY"', "KYTC", "VDOT", "WVDOH")):
                print("   ", text[:700])
    for state, sites in SOUTH_SITES.items():
        for site in sites:
            head(f"{state}: {site}")
            try:
                status, final, page = _site_marks(site)
                if status != 200:
                    continue
                base = final.rstrip("/")
                if "mapicons" in page.lower() or "list/getdata" in page.lower():
                    ibi_layers(base)
                for path in ("/configs/main.json", "/config/main.json"):
                    st_, _, h, body = get(base + path, headers=BROWSER, limit=2_000_000)
                    print(f"   {path}: status {st_} type {h.get('Content-Type')} bytes {len(body)}", body[:600].replace("\n", " ") if st_ == 200 else "")
                _script_urls(final, page)
            except Exception as e:
                print("   failed:", repr(e)[:200])
    head("Iteris' CDN and aggregator, as South Dakota's (sd.cdn.iteris-atis.com): the three states' guesses")
    for prefix in ("va", "vdot", "wv", "wvdot", "ky", "kytc"):
        for path in ("geojson/icons/metadata/icons.json", "geojson/icons/metadata/icons.cameras.geojson", "geojson/events.geojson"):
            try_json(f"https://{prefix}.cdn.iteris-atis.com/{path}", sample=500)
    for group in ("vdot", "virginia", "wvdot", "kytc"):
        try_json(f"https://aggregator.iteris-atis.com/aggregator/services/layers/group/{group}/current/", sample=500)
    head("Public ArcGIS items about the three states' traffic")
    for q in ("goky", "KYTC traffic cameras", "KYTC incidents", "VDOT traffic cameras", "VDOT incidents", "WV511", "WVDOT cameras"):
        st_, d = try_json("https://www.arcgis.com/sharing/rest/search?f=json&num=12&q=" + urllib.parse.quote(q), label=f"ArcGIS search {q!r}", sample=200)
        for item in (d or {}).get("results", []) if isinstance(d, dict) else []:
            print(f"      {item.get('type')} | {item.get('title')} | owner {item.get('owner')} | {item.get('url')} | id {item.get('id')} | access {item.get('access')}")
    head("Weather: the three states")
    for state in SOUTH_STATES:
        st_, _, _, b = get("https://api.weather.gov/alerts/active?area=" + state, limit=20_000_000)
        print(f"  alerts {state}: status {st_} alerts {len(((_json(b) or {}).get('features')) or [])} bytes {len(b)}")
        st_, _, _, b = get(f"https://aviationweather.gov/api/data/metar?ids=@{state}&format=json", limit=10_000_000)
        d = _json(b)
        print(f"  METAR @{state}: status {st_} stations {len(d) if isinstance(d, list) else None} bytes {len(b)}", "" if isinstance(d, list) else b[:300])
    every = ["NY", "NJ", "CT", "PA", "MD", "DE", "DC", "OH", "IN", "IL", "MI", "WI", "MN", "IA", "MO", "ND", "SD", "NE", "KS"] + SOUTH_STATES
    st_, _, _, b = get("https://api.weather.gov/alerts/active?area=" + ",".join(every), limit=40_000_000)
    print(f"  alerts for all {len(every)} states in one request: status {st_} alerts {len(((_json(b) or {}).get('features')) or [])} bytes {len(b)}")


def _strings(js, pattern, limit=80):
    """The quoted strings in a script that match [pattern] (relative paths included), deduplicated, in order."""
    seen = []
    for s in re.findall(r"""["'`]([^"'`\s]{3,200})["'`]""", js):
        if re.search(pattern, s, re.I) and s not in seen:
            seen.append(s)
    return seen[:limit]


def _arcgis_layer(url, where="1=1", n=3, label=None):
    """An ArcGIS layer: its fields, its row count, and a few rows of every field."""
    st_, meta = try_json(url + "?f=json", label=f"{label or url} (layer)", sample=300)
    if isinstance(meta, dict):
        print("   name", meta.get("name"), "| geometry", meta.get("geometryType"), "| maxRecordCount", meta.get("maxRecordCount"))
        print("   fields", [f.get("name") for f in meta.get("fields") or []][:60])
    q = urllib.parse.urlencode({"where": where, "returnCountOnly": "true", "f": "json"})
    try_json(f"{url}/query?{q}", label=f"{label or url} count", sample=200)
    q = urllib.parse.urlencode({"where": where, "outFields": "*", "outSR": "4326", "resultRecordCount": n, "f": "json"})
    st_, d = try_json(f"{url}/query?{q}", label=f"{label or url} rows", sample=2500)
    if isinstance(d, dict):
        for f in (d.get("features") or [])[1:n]:
            print("   row:", json.dumps(f)[:2500])


def dot_south_2():
    """Second round: Virginia's 511 config and Iteris layers, West Virginia's site scripts, Kentucky's ArcGIS services."""
    head("USDOT WZDx feed registry, the three states (compact)")
    st_, _, _, body = get("https://data.transportation.gov/resource/69qe-yiui.json?$limit=500", limit=10_000_000)
    rows = _json(body) if st_ == 200 else None
    print("   registry status", st_, "rows", len(rows) if isinstance(rows, list) else None)
    for row in rows or []:
        text = json.dumps(row)
        if re.search(r"Virginia|Kentucky|KYTC|VDOT|WVDOH|WVDOT|\"(VA|WV|KY)\"", text):
            print("   ", text[:900])

    head("Virginia: 511.vdot.virginia.gov's config and scripts")
    try:
        status, final, page = _site_marks("https://511.vdot.virginia.gov")
        st_, _, h, cfg = get("https://511.vdot.virginia.gov/assets/config/config.json", headers=BROWSER, limit=2_000_000)
        print(f"   assets/config/config.json: status {st_} type {h.get('Content-Type')} bytes {len(cfg)}\n{cfg[:7000]}")
        for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:10]:
            src = urllib.parse.urljoin(final, src)
            if "main-" not in src and "chunk" not in src:
                continue
            _, _, _, js = get(src, headers=BROWSER, limit=12_000_000)
            print(f"   {src[-40:]} ({len(js)} bytes) data strings:",
                  _strings(js, r"https?://|/api|geojson|\.json|iteris|events|camera|sign|dms|incident|layer|feed", 120))
        st_, _, _, home = get("https://www.511virginia.org/", headers=BROWSER, limit=200_000)
        print("   www.511virginia.org status", st_, "bytes", len(home))
    except Exception as e:
        print("   failed:", repr(e)[:300])
    head("Virginia: Iteris aggregator, group vdot")
    agg = "https://aggregator.iteris-atis.com/aggregator/services"
    for path in ("layers/group/vdot/", "elements/group/vdot/current/", "layers/group/vdot/current/?layer_type=incident",
                 "elements/group/vdot/current/?layer_type=incident"):
        st_, d = try_json(f"{agg}/{path}", sample=1500)
        feats = d.get("features") if isinstance(d, dict) else (d if isinstance(d, list) else None)
        if isinstance(feats, list):
            kinds = {}
            for f in feats:
                p = f.get("properties", f) if isinstance(f, dict) else {}
                k = (p.get("content_type") or p.get("layer_type") or p.get("type") or p.get("name") or "?")
                kinds[str(k)] = kinds.get(str(k), 0) + 1
            print("   kinds:", sorted(kinds.items(), key=lambda kv: -kv[1])[:30])

    head("West Virginia: wv511.org's scripts, every service-looking string")
    try:
        status, final, page = _site_marks("https://www.wv511.org")
        print("   inline service-ish strings:", _strings(page, r"asmx|ashx|svc|json|kml|xml|Handler|Service|\.aspx|/api", 80))
        for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:20]:
            src = urllib.parse.urljoin(final, html.unescape(src))
            if any(x in src for x in ("jquery", "jqyery", "google", "gtag", "awstats", "WebResource")):
                continue
            _, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
            print(f"   {src[-60:]} ({len(js)} bytes):", _strings(js, r"asmx|ashx|svc|json|kml|xml|Handler|Service|\.aspx|/api|camera|incident|event|sign|dms|Get[A-Z]", 100))
    except Exception as e:
        print("   failed:", repr(e)[:300])

    head("Kentucky: GoKY's script, every URL")
    try:
        st_, _, _, js = get("https://goky.ky.gov/index.js", headers=BROWSER, limit=8_000_000)
        print("   index.js status", st_, "bytes", len(js))
        print("   urls:", _strings(js, r"https?://", 120))
        print("   paths:", _strings(js, r"^/|FeatureServer|MapServer|\.json|api", 80))
    except Exception as e:
        print("   failed:", repr(e)[:300])
    head("Kentucky: KYTC's ArcGIS services")
    try_json("https://maps.kytc.ky.gov/arcgis/rest/services/RealTime?f=json", sample=3000)
    try_json("https://maps.kytc.ky.gov/arcgis/rest/services?f=json", sample=3000)
    _arcgis_layer("https://services2.arcgis.com/CcI36Pduqd0OR4W9/ArcGIS/rest/services/trafficCamerasCur_Prd/FeatureServer/0", label="KYTC cameras")
    _arcgis_layer("https://services1.arcgis.com/Mg7DLdfYcSWIaDnu/ArcGIS/rest/services/Traffic_Camera_Locations_Public_view/FeatureServer/0", label="Louisville cameras")
    try_json("https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services/kytc_incidents_reports_24hr/FeatureServer?f=json", sample=1500)
    _arcgis_layer("https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services/kytc_incidents_reports_24hr/FeatureServer/0", label="KYTC incidents 24 h")
    try_json("https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services/KYTC_KIR_KYEM_Public_Feed/FeatureServer?f=json", sample=1500)
    _arcgis_layer("https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services/KYTC_KIR_KYEM_Public_Feed/FeatureServer/0", label="KYTC KIR public")
    for q in ("owner:KYTCGIS", "owner:kytc.gis_KYTC", "owner:KYTCBigData", "KYTC work zones", "KYTC message signs", "KYTC road closures", "goky incidents waze"):
        st_, d = try_json("https://www.arcgis.com/sharing/rest/search?f=json&num=40&sortField=modified&sortOrder=desc&q=" + urllib.parse.quote(q), label=f"ArcGIS search {q!r}", sample=100)
        for item in (d or {}).get("results", []) if isinstance(d, dict) else []:
            if item.get("type") in ("Feature Service", "Map Service", "Web Mapping Application", "Web Map"):
                print(f"      {item.get('type')} | {item.get('title')} | {item.get('owner')} | {item.get('url')} | {item.get('id')}")


def _arcgis_all(url, out_fields="*", geometry=True):
    """Every row of an ArcGIS layer, a page (its maxRecordCount, up to 1000) at a time: [{attributes, geometry}]."""
    rows, offset = [], 0
    for _ in range(40):
        q = urllib.parse.urlencode({"where": "1=1", "outFields": out_fields, "returnGeometry": str(geometry).lower(), "outSR": "4326",
                                    "resultOffset": offset, "resultRecordCount": 1000, "f": "json"})
        st_, _, _, body = get(f"{url}/query?{q}", limit=40_000_000)
        d = _json(body)
        page = (d or {}).get("features") if isinstance(d, dict) else None
        if not page:
            if st_ != 200 or not isinstance(d, dict):
                print("   page failed:", st_, body[:300])
            break
        rows += page
        if not d.get("exceededTransferLimit"):
            break
        offset += len(page)
    return rows


def _tally(rows, key, n=25):
    counts = {}
    for r in rows:
        v = (r.get("attributes") or {}).get(key)
        counts[str(v)] = counts.get(str(v), 0) + 1
    return sorted(counts.items(), key=lambda kv: -kv[1])[:n]


def dot_south_3():
    """Third round: Kentucky's layers measured whole, Virginia's lazy scripts, West Virginia's map iframe and lists."""
    import time
    now = time.time() * 1000
    head("Kentucky: KYTC's incidents (24 hours), whole")
    base = "https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services"
    rows = _arcgis_all(f"{base}/kytc_incidents_reports_24hr/FeatureServer/0")
    print("   rows", len(rows))
    for key in ("KYTC_Type", "Source", "CFR_Type", "Source_Type", "Type", "Route"):
        print(f"   {key}:", _tally(rows, key))
    ended = sum(1 for r in rows if ((r.get("attributes") or {}).get("EndDate") or 0) and r["attributes"]["EndDate"] < now)
    print(f"   EndDate before now: {ended}; BeginDate after now: {sum(1 for r in rows if (r.get('attributes') or {}).get('BeginDate', 0) > now)}")
    for kind in ("Crash", "Incident", "Closure", "Road Closed", "Waze"):
        for r in rows:
            a = r.get("attributes") or {}
            if kind.lower() in json.dumps(a).lower():
                print(f"   a {kind!r} row:", json.dumps(a)[:900])
                break
    for svc in ("KYTC_Road_Closures", "dmsSigns_2020"):
        head(f"Kentucky: {svc}")
        st_, d = try_json(f"{base}/{svc}/FeatureServer?f=json", sample=300)
        layers = [l.get("id") for l in (d or {}).get("layers", [])] if isinstance(d, dict) else []
        print("   layers", [(l.get("id"), l.get("name"), l.get("geometryType")) for l in (d or {}).get("layers", [])] if isinstance(d, dict) else None)
        for lid in layers[:4]:
            _arcgis_layer(f"{base}/{svc}/FeatureServer/{lid}", label=f"{svc}/{lid}", n=4)
            every = _arcgis_all(f"{base}/{svc}/FeatureServer/{lid}", geometry=False)
            print(f"   {svc}/{lid} whole: {len(every)} rows, {len(json.dumps(every))} bytes as JSON")
    head("Kentucky: KYTC's cameras, whole")
    cams = _arcgis_all("https://services2.arcgis.com/CcI36Pduqd0OR4W9/ArcGIS/rest/services/trafficCamerasCur_Prd/FeatureServer/0", geometry=False)
    print("   cameras", len(cams))
    hosts = {}
    for c in cams:
        u = (c.get("attributes") or {}).get("snapshot") or ""
        m = re.match(r"(https?)://([^/]+)", u)
        k = f"{m.group(1)}://{m.group(2)}" if m else "(none)"
        hosts[k] = hosts.get(k, 0) + 1
    print("   snapshot hosts:", sorted(hosts.items(), key=lambda kv: -kv[1]))
    for key in ("state", "status", "district", "highway"):
        print(f"   {key}:", _tally(cams, key, 12))
    ky_cams = [c for c in cams if "Indiana" not in ((c.get("attributes") or {}).get("description") or "")]
    print("   not marked Indiana:", len(ky_cams), "sample:", [json.dumps(c["attributes"])[:400] for c in ky_cams[:3]])
    for u in [c["attributes"]["snapshot"] for c in cams if (c.get("attributes") or {}).get("snapshot")][:400:80]:
        st_, _, h, b = get(u.replace("http://", "https://", 1), limit=2_000_000)
        print(f"   https still {u[:90]}: status {st_} type {h.get('Content-Type')} bytes {len(b)}")
    head("Kentucky: KYTC's WZDx feed")
    st_, d = try_json("https://storage.googleapis.com/kytc-its-2020-openrecords/public/feeds/WZDx/kytc_wzdx_v4.1.geojson", sample=1800)
    if isinstance(d, dict):
        print("   feed_info:", json.dumps(d.get("feed_info"))[:600], "features", len(d.get("features") or []))

    head("Virginia: 511.vdot.virginia.gov's lazy chunks")
    try:
        status, final, page = _site_marks("https://511.vdot.virginia.gov")
        mains = [urllib.parse.urljoin(final, s) for s in re.findall(r'<script[^>]+src="([^"]+)"', page) if "main-" in s]
        seen = set()
        queue = list(mains)
        while queue and len(seen) < 60:
            src = queue.pop(0)
            if src in seen:
                continue
            seen.add(src)
            _, _, _, js = get(src, headers=BROWSER, limit=12_000_000)
            for chunk in re.findall(r"""["'`]\.?/?(chunk-[A-Z0-9]+\.js)["'`]""", js):
                queue.append(urllib.parse.urljoin(final, chunk))
            found = _strings(js, r"services/|/511|geojson|ttrip|smarterRoads|iteriscloud|cameras|/events|/layers|dms|signs|NODE_ENDPOINT|\.json", 60)
            if found:
                print(f"   {src[-26:]} ({len(js)} bytes):", found)
            for m in re.finditer(r"NODE_ENDPOINT[^;]{0,400}", js):
                print("   NODE_ENDPOINT use:", m.group(0)[:400])
                break
        print("   scripts read:", len(seen))
    except Exception as e:
        print("   failed:", repr(e)[:300])
    for path in ("/services/511/layers", "/services/511/cameras", "/services/511/events", "/services/511/map/layers", "/services/511/api/layers"):
        st_, _, h, b = get("https://511.vdot.virginia.gov" + path, headers=BROWSER, limit=2_000_000)
        print(f"   {path}: status {st_} type {h.get('Content-Type')} bytes {len(b)}:", b[:300].replace("\n", " "))

    head("West Virginia: the map iframe and the list pages")
    try:
        url = "https://www.wv511.org/webmapi.aspx?VIEWLEVEL=XD&VIEWLEVEL2=&VIEWTYPE=ICDS&VIEWTYPE2=&VIEWPTCONLY=0&LAT=&LNG=&ZOOM=&GV=&WEBMAPKEY="
        st_, final, h, page = get(url, headers=BROWSER, limit=4_000_000)
        print(f"   webmapi: status {st_} bytes {len(page)}")
        print("   service strings:", _strings(page, r"asmx|ashx|svc|json|kml|xml|Handler|Service|\.aspx|/api|camera|incident|event|dms|sign|Get[A-Z]", 120))
        for src in re.findall(r'<script[^>]+src="([^"]+)"', page)[:20]:
            src = urllib.parse.urljoin(final, html.unescape(src))
            if any(x in src for x in ("jquery", "jqyery", "google", "gtag", "awstats")):
                continue
            _, _, _, js = get(src, headers=BROWSER, limit=8_000_000)
            print(f"   {src[-70:]} ({len(js)} bytes):", _strings(js, r"asmx|ashx|svc|json|kml|xml|Handler|\.aspx|/api|Get[A-Z][a-z]", 120))
        for path in ("/CameraListing.aspx", "/dms.aspx", "/current_alerts.aspx", "/districtRoadwork.aspx", "/emergencyClosures.aspx"):
            st_, _, h, b = get("https://www.wv511.org" + path, headers=BROWSER, limit=4_000_000)
            print(f"   {path}: status {st_} bytes {len(b)}; images {len(re.findall(r'<img', b))}; text: {text_of(b)[:1500]!r}")
            print("      links/images:", sorted(set(re.findall(r'(?:src|href)="([^"]*(?:cam|Cam|jpg|dms|DMS|aspx\?)[^"]*)"', b)))[:25])
    except Exception as e:
        print("   failed:", repr(e)[:300])


def _context(js, needle, before=250, after=450, limit=4):
    """Up to [limit] snippets of a script around [needle]."""
    out, start = [], 0
    while len(out) < limit:
        i = js.find(needle, start)
        if i < 0:
            break
        out.append(js[max(0, i - before):i + after].replace("\n", " "))
        start = i + len(needle)
    return out


def dot_south_4():
    """Fourth round: WV511's web services, Virginia's layer URLs from their code, Kentucky's freshness."""
    import time
    now = time.time() * 1000
    head("West Virginia: WV511's gmap.asmx services")
    wv = "https://www.wv511.org/wsvc/gmap.asmx"
    for path in ("buildCamerasJSONjs", "buildEventsKMLc", "buildEventsKMLs", "buildPlannedEventsActiveKML", "buildDMSKML?isActive=1",
                 "buildDMSKML?isActive=0", "buildRoutePolysClosedKML", "GetEventGeometriesGeoJSON?EventClassType=", "buildEventsKMLi_Filtered?CategoryIDs="):
        st_, _, h, b = get(f"{wv}/{path}", headers={**BROWSER, "Referer": "https://www.wv511.org/"}, limit=20_000_000)
        print(f"--- {path}: status {st_} type {h.get('Content-Type')} bytes {len(b)}")
        print("   head:", b[:1800].replace("\n", " "))
        if "<Placemark" in b:
            marks = re.findall(r"(?s)<Placemark.*?</Placemark>", b)
            print(f"   placemarks {len(marks)}; second: {marks[1][:1500] if len(marks) > 1 else ''}".replace("\n", " "))
            styles = {}
            for m in marks:
                s = (re.findall(r"<styleUrl>([^<]*)</styleUrl>", m) or ["?"])[0]
                styles[s] = styles.get(s, 0) + 1
            print("   styles:", sorted(styles.items(), key=lambda kv: -kv[1])[:20])
    st_, _, _, page = get("https://www.wv511.org/CameraListing.aspx", headers=BROWSER, limit=4_000_000)
    _, _, _, js = get("https://www.wv511.org/js/CameraListing.min.js", headers=BROWSER, limit=4_000_000)
    print("   CameraListing.min.js strings:", _strings(js, r"asmx|ashx|json|jpg|camera|Cam|http|\.aspx", 60))

    head("Virginia: how its map builds its layer URLs")
    try:
        status, final, page = _site_marks("https://511.vdot.virginia.gov")
        mains = [urllib.parse.urljoin(final, s) for s in re.findall(r'<script[^>]+src="([^"]+)"', page) if "main-" in s]
        seen, queue = set(), list(mains)
        while queue and len(seen) < 70:
            src = queue.pop(0)
            if src in seen:
                continue
            seen.add(src)
            _, _, _, js = get(src, headers=BROWSER, limit=12_000_000)
            for chunk in re.findall(r"""["'`]\.?/?(chunk-[A-Z0-9]+\.js)["'`]""", js):
                queue.append(urllib.parse.urljoin(final, chunk))
            for needle in ("layers/map", "getCamerasArray", "CAMERA_MAPPING", "/layers/road", "layerMap=", "511_events", "travelSigns:", "/api/incidents"):
                for snip in _context(js, needle, limit=2):
                    print(f"   {src[-22:]} [{needle}]: {snip}")
    except Exception as e:
        print("   failed:", repr(e)[:300])
    va = "https://511.vdot.virginia.gov/services/511/map"
    for path in ("layers/map", "layers/map/", "layers/map/incidents", "layers/map/construction", "layers/map/511_events", "layers/map/dms",
                 "layers/map/cameras", "layers/map/camera", "layers/map/travelSigns", "cameras", "camerasArray", "cameras/array",
                 "layers/road/roadCond", "layers/rwis/", "layers/transit/"):
        st_, _, h, b = get(f"{va}/{path}", headers=BROWSER, limit=30_000_000)
        print(f"--- {path}: status {st_} type {h.get('Content-Type')} bytes {len(b)}:", b[:700].replace("\n", " "))

    head("Kentucky: freshness of the sign layer, closures and incidents in effect")
    base = "https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services"
    signs = _arcgis_all(f"{base}/dmsSigns_2020/FeatureServer/0", geometry=False)
    ts = sorted(((s.get("attributes") or {}).get("updateTS") or 0) for s in signs)
    ex = sorted(((s.get("attributes") or {}).get("kytcDateExistsUTC") or 0) for s in signs)
    print(f"   signs {len(signs)}; updateTS newest {ts[-1] if ts else None} ({(now - ts[-1]) / 3.6e6 if ts else 0:.0f} h ago);"
          f" kytcDateExistsUTC newest {ex[-1] if ex else None}; status {_tally(signs, 'dmsStatus')}")
    closures = _arcgis_all(f"{base}/KYTC_Road_Closures/FeatureServer/0", geometry=False)
    live = [c for c in closures if ((c.get("attributes") or {}).get("endtime") or 0) > now and ((c.get("attributes") or {}).get("starttime") or 0) <= now]
    print(f"   closures {len(closures)}, in effect now {len(live)}; types {_tally(closures, 'closure_type')}; directions {_tally(closures, 'direction')}")
    inc = _arcgis_all(f"{base}/kytc_incidents_reports_24hr/FeatureServer/0", geometry=False)
    ends = sorted(((r.get("attributes") or {}).get("EndDate") or 0) for r in inc)
    newest = ends[-1] if ends else 0
    recent = [r for r in inc if newest - ((r.get("attributes") or {}).get("EndDate") or 0) < 20 * 60_000]
    print(f"   incidents {len(inc)}; newest EndDate {newest} ({(now - newest) / 60000:.0f} min ago); within 20 min of it {len(recent)}: {_tally(recent, 'KYTC_Type')}")


def _fixture(label, text):
    """A raw answer on one line between markers, to copy into a test fixture."""
    print(f"FIXTURE-BEGIN {label}\n{text}\nFIXTURE-END {label}")


def dot_south_5():
    """Fifth round: Virginia's layers listed and measured, WV511's services summed up, and fixtures for all three."""
    head("Virginia: the map's layers")
    va = "https://511.vdot.virginia.gov/services/511/map/layers/map"
    st_, d = try_json(va, sample=100)
    layers = (d or {}).get("data") if isinstance(d, dict) else None
    for l in layers or []:
        print(f"   {l.get('id')} | {l.get('layer_type')} | {l.get('description')} | identifier {l.get('identifier')} | source {l.get('source')}"
              f" | active {l.get('active')} | data_path {l.get('data_path')}")
    idents = []
    for l in layers or []:
        for k in (l.get("identifier"), l.get("source")):
            if k and k not in idents:
                idents.append(k)
    for ident in idents[:40]:
        st_, _, h, b = get(f"{va}/{urllib.parse.quote(ident)}", headers=BROWSER, limit=40_000_000)
        dd = _json(b)
        feats = dd.get("features") if isinstance(dd, dict) else None
        kinds = {}
        for f in feats or []:
            p = f.get("properties") or {}
            k = (p.get("type"), p.get("icon"))
            kinds[str(k)] = kinds.get(str(k), 0) + 1
        print(f"--- {ident}: status {st_} bytes {len(b)} features {len(feats) if isinstance(feats, list) else None}"
              f" kinds {sorted(kinds.items(), key=lambda kv: -kv[1])[:12]}")
        if feats:
            print("   first:", json.dumps(feats[0])[:1200])
    for ident in ("incidents", "dms"):
        st_, _, _, b = get(f"{va}/{ident}", headers=BROWSER, limit=40_000_000)
        dd = _json(b)
        if isinstance(dd, dict) and isinstance(dd.get("features"), list):
            _fixture(f"va-{ident}", json.dumps({"type": "FeatureCollection", "features": dd["features"][:6]}))
    cams = next((l.get("data_path") for l in layers or [] if l.get("identifier") == "cams"), None)
    if cams:
        st_, _, h, b = get(cams, headers=BROWSER, limit=40_000_000)
        dd = _json(b)
        feats = dd.get("features") if isinstance(dd, dict) else None
        print(f"--- cameras {cams}: status {st_} type {h.get('Content-Type')} bytes {len(b)} features {len(feats) if isinstance(feats, list) else None}")
        if feats:
            print("   first:", json.dumps(feats[0])[:1500])
            hosts = {}
            for f in feats:
                for v in json.dumps(f.get("properties") or {}).split('"'):
                    m = re.match(r"(https?)://([^/]+)", v)
                    if m:
                        hosts[m.group(0)] = hosts.get(m.group(0), 0) + 1
            print("   hosts in properties:", sorted(hosts.items(), key=lambda kv: -kv[1])[:10])
            _fixture("va-cameras", json.dumps({"type": "FeatureCollection", "features": feats[:4]}))
            img = re.search(r'"(https://[^"]+\.(?:jpg|jpeg|png)[^"]*)"', json.dumps(feats[:20]))
            if img:
                st_, _, h, b = get(img.group(1), limit=2_000_000)
                print(f"   a still {img.group(1)}: status {st_} type {h.get('Content-Type')} bytes {len(b)}")

    head("West Virginia: WV511's services, summed up")
    wv = "https://www.wv511.org/wsvc/gmap.asmx"
    for path in ("buildEventsKMLc", "buildEventsKMLs", "buildPlannedEventsActiveKML", "buildPlannedEventsFutureKML",
                 "buildDMSKML?isActive=1", "buildDMSKML?isActive=0", "buildRoutePolysClosedKML", "buildRoutePolysRestrictionsKML"):
        st_, _, h, b = get(f"{wv}/{path}", headers={**BROWSER, "Referer": "https://www.wv511.org/"}, limit=20_000_000)
        marks = re.findall(r"(?s)<Placemark.*?</Placemark>", b)
        styles = {}
        for m in marks:
            s = (re.findall(r"<styleUrl>([^<]*)</styleUrl>", m) or ["?"])[0]
            styles[s] = styles.get(s, 0) + 1
        names = {}
        for m in marks:
            n = (re.findall(r"<name><!\[CDATA\[(.*?)\]\]></name>", m, re.S) or re.findall(r"<name>(.*?)</name>", m, re.S) or ["?"])[0]
            names[n[:40]] = names.get(n[:40], 0) + 1
        print(f"--- {path}: status {st_} bytes {len(b)} placemarks {len(marks)} styles {sorted(styles.items(), key=lambda kv: -kv[1])[:10]}")
        print("   names:", sorted(names.items(), key=lambda kv: -kv[1])[:15])
        for m in marks[:2]:
            print("   placemark:", re.sub(r"\s+", " ", m)[:1200])
        if marks and path in ("buildEventsKMLc", "buildEventsKMLs", "buildDMSKML?isActive=0", "buildPlannedEventsActiveKML", "buildRoutePolysClosedKML"):
            start = b.find("<Placemark")
            _fixture(f"wv-{path}", re.sub(r"\s*\n\s*", " ", b[:start] + "".join(marks[:5]) + "</Document></kml>"))
    st_, _, h, b = get(f"{wv}/buildCamerasJSONjs", headers={**BROWSER, "Referer": "https://www.wv511.org/"}, limit=20_000_000)
    print(f"--- buildCamerasJSONjs: status {st_} type {h.get('Content-Type')} bytes {len(b)}\n   head: {b[:2500]}")
    _fixture("wv-cameras", b[:6000])
    stills = re.findall(r"https?://[^\"' ]+\.(?:jpg|jpeg|png)", b)
    hosts = {}
    for u in stills:
        k = re.match(r"https?://[^/]+", u).group(0)
        hosts[k] = hosts.get(k, 0) + 1
    print("   still hosts:", sorted(hosts.items(), key=lambda kv: -kv[1])[:10], "streams:", len(re.findall(r"\.m3u8", b)))
    for u in stills[:2]:
        st_, _, h, bb = get(u.replace("http://", "https://", 1), limit=2_000_000)
        print(f"   still {u}: status {st_} type {h.get('Content-Type')} bytes {len(bb)}")

    head("Kentucky: fixtures")
    base = "https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services"
    for label, url, where in (
        ("ky-incidents", f"{base}/kytc_incidents_reports_24hr/FeatureServer/0", "KYTC_Type IN ('Crash','Hazard','Roadway or Lane Blocking Incident')"),
        ("ky-workzones", f"{base}/kytc_incidents_reports_24hr/FeatureServer/0", "KYTC_Type = 'Work Zone'"),
        ("ky-closures", f"{base}/KYTC_Road_Closures/FeatureServer/0", "1=1"),
        ("ky-cameras", "https://services2.arcgis.com/CcI36Pduqd0OR4W9/ArcGIS/rest/services/trafficCamerasCur_Prd/FeatureServer/0", "1=1"),
    ):
        geometry = "true" if label == "ky-closures" else "false"
        q = urllib.parse.urlencode({"where": where, "outFields": "*", "returnGeometry": geometry, "outSR": "4326", "resultRecordCount": 6, "f": "json"})
        st_, _, _, b = get(f"{url}/query?{q}", limit=10_000_000)
        print(f"   {label}: status {st_} bytes {len(b)}")
        _fixture(label, b)


def dot_south_6():
    """Sixth round: WV511's incident layer (its category ids) and how its cameras' video is played."""
    head("West Virginia: how the map asks for incidents")
    site = "https://www.wv511.org"
    ref = {**BROWSER, "Referer": "https://www.wv511.org/"}
    js_all = {}
    for path in ("/js/gmapi.min.js", "/js/webmap.min.js", "/js/CameraListing.min.js", "/js/cam_stream_timeout.js", "/js/gmapi.polys.min.js"):
        _, _, _, js = get(site + path, headers=ref, limit=4_000_000)
        js_all[path] = js
        for needle in ("buildEventsKMLi_Filtered", "CategoryIDs", "filterEvents", "buildEventsKMLc", "m3u8", "rtplive", "streaming", "STREAMING", "LoadAnotherCam", "playlist"):
            for snip in _context(js, needle, before=200, after=500, limit=2):
                print(f"   {path} [{needle}]: {snip}")
    st_, _, _, page = get(f"{site}/webmapi.aspx?VIEWLEVEL=XD&VIEWLEVEL2=&VIEWTYPE=ICDS&VIEWTYPE2=&VIEWPTCONLY=0&LAT=&LNG=&ZOOM=&GV=&WEBMAPKEY=", headers=ref, limit=4_000_000)
    for needle in ("CategoryIDs", "filterEvents", "Categor", "m3u8", "stream"):
        for snip in _context(page, needle, before=200, after=400, limit=2):
            print(f"   webmapi [{needle}]: {snip}")
    st_, _, _, home = get(f"{site}/", headers=ref, limit=4_000_000)
    print("   home's event filter inputs:", re.findall(r'<input[^>]+(?:Event|event|cat|Cat)[^>]*>', home)[:30])
    for ids in ("1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30", "0", "1"):
        st_, _, h, b = get(f"{site}/wsvc/gmap.asmx/buildEventsKMLi_Filtered?CategoryIDs={ids}", headers=ref, limit=20_000_000)
        marks = re.findall(r"(?s)<Placemark.*?</Placemark>", b)
        print(f"--- buildEventsKMLi_Filtered?CategoryIDs={ids[:20]}: status {st_} bytes {len(b)} placemarks {len(marks)}", b[:300].replace("\n", " ") if st_ != 200 else "")
        for m in marks[:2]:
            print("   placemark:", re.sub(r"\s+", " ", m)[:1000])
    head("West Virginia: a camera's video")
    st_, _, _, cams = get(f"{site}/wsvc/gmap.asmx/buildCamerasJSONjs", headers=ref, limit=20_000_000)
    m = re.search(r"var camera_data = (\{.*?\});?\s*$", cams, re.S)
    data = None
    if m:
        try:
            data = json.loads(m.group(1))
        except ValueError as e:
            print("   camera_data isn't JSON:", e)
    cams_list = (data or {}).get("cams") or []
    print("   cameras", len(cams_list), "icons", _tally([{"attributes": c} for c in cams_list], "icon"), "streaming", sum("STREAMING:1" in (c.get("description") or "") for c in cams_list))
    if cams_list:
        print("   one:", json.dumps(cams_list[0])[:1200])
        cid = cams_list[0].get("md5")
        for path in (f"/CameraListing.aspx?cam={cid}", f"/wsvc/gmap.asmx/getCameraStream?camId={cid}", f"/flowplayer/getCam.aspx?cam={cid}",
                     f"/wsvc/gmap.asmx/GetCameraStreamURL?id={cid}"):
            st_, _, h, b = get(site + path, headers=ref, limit=2_000_000)
            print(f"   {path}: status {st_} type {h.get('Content-Type')} bytes {len(b)}", re.findall(r"https?://[^\"' <]+(?:m3u8|rtplive|stream)[^\"' <]*", b)[:5])


def _function(js, name, limit=3000):
    """The source of a script's `function name(...)` up to [limit] characters."""
    i = js.find(f"function {name}(")
    return js[i:i + limit] if i >= 0 else None


def dot_south_7():
    """Seventh round: WV511's incident filter and camera stream, and the last fixtures."""
    site = "https://www.wv511.org"
    ref = {**BROWSER, "Referer": "https://www.wv511.org/"}
    head("West Virginia: the incident filter, whole")
    _, _, _, webmap = get(site + "/js/webmap.min.js", headers=ref, limit=4_000_000)
    print("   toggleIncidentTypes:", _function(webmap, "toggleIncidentTypes", 4000))
    _, _, _, home = get(site + "/", headers=ref, limit=4_000_000)
    print("   home's incident checkboxes:", re.findall(r'<input[^>]+id="chkIC[^"]*"[^>]*>', home)[:40])
    for cats, sevs, only in (("", "1,2,3,", "0"), ("", "1,2,3,", "1"), ("1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,", "1,2,3,", "0"), ("", "", "0")):
        q = urllib.parse.urlencode({"CategoryIDs": cats, "SeverityIDs": sevs, "is511Only": only})
        st_, _, h, b = get(f"{site}/wsvc/gmap.asmx/buildEventsKMLi_Filtered?{q}", headers=ref, limit=20_000_000)
        marks = re.findall(r"(?s)<Placemark.*?</Placemark>", b)
        names = {}
        for m in marks:
            n = (re.findall(r"<name><!\[CDATA\[(.*?)\]\]></name>", m, re.S) or ["?"])[0]
            names[n[:40]] = names.get(n[:40], 0) + 1
        print(f"--- {q}: status {st_} bytes {len(b)} placemarks {len(marks)} names {sorted(names.items(), key=lambda kv: -kv[1])[:15]}")
        for m in marks[:3]:
            print("   placemark:", re.sub(r"\s+", " ", m)[:1200])
        if marks:
            start = b.find("<Placemark")
            _fixture(f"wv-incidents-{only}", re.sub(r"\s*\n\s*", " ", b[:start] + "".join(marks[:5]) + "</Document></kml>"))
            break
    head("West Virginia: how a camera's stream is played")
    for path in ("/js/gmapi.cams.min.js", "/js/flowplayi.min.js", "/js/cam_stream_timeout.js"):
        _, _, _, js = get(site + path, headers=ref, limit=4_000_000)
        print(f"   {path}: {len(js)} bytes")
        for name in ("LoadStreamingCam", "stopCameraPlayBack", "getStreamUrl", "playStream"):
            src = _function(js, name, 2500)
            if src:
                print(f"   {path} {name}: {src}")
        print("   urls:", _strings(js, r"https?://|m3u8|rtplive|wowza|stream|\.aspx|asmx", 40))

    head("Kentucky: Kentucky's own cameras")
    q = urllib.parse.urlencode({"where": "state='Kentucky'", "outFields": "*", "returnGeometry": "false", "resultRecordCount": 6, "f": "json"})
    st_, _, _, b = get(f"https://services2.arcgis.com/CcI36Pduqd0OR4W9/ArcGIS/rest/services/trafficCamerasCur_Prd/FeatureServer/0/query?{q}", limit=10_000_000)
    _fixture("ky-cameras-ky", b)
    head("Virginia: the other layers' fixtures")
    va = "https://511.vdot.virginia.gov/services/511/map/layers/map"
    for ident, n in (("acon", 5), ("hpIncidents", 5), ("weatherClosures", 5), ("sEvents", 3), ("sActivity", 3)):
        st_, _, _, b = get(f"{va}/{ident}", headers=BROWSER, limit=40_000_000)
        dd = _json(b)
        if isinstance(dd, dict) and isinstance(dd.get("features"), list):
            print(f"   {ident}: {len(dd['features'])} features")
            _fixture(f"va-{ident}", json.dumps({"type": "FeatureCollection", "features": dd["features"][:n]}))
        else:
            print(f"   {ident}: status {st_}", b[:200])


def dot_south_8():
    """Eighth round: every request the three states' readers make, as the phone makes it, WV511's stream page, and
    the build's camera fetchers for the three."""
    java_accept = "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2"
    app = {"User-Agent": "Southbound", "Accept": java_accept}

    def show(label, url, n=160):
        status, _, h, body = get(url, headers=app, limit=40_000_000)
        print(f"   {label}: status {status} type {h.get('Content-Type')} bytes {len(body)}:", body[:n].replace("\n", " "))
        return status, h, body

    head("The phone's own requests: Virginia")
    va = "https://511.vdot.virginia.gov/services/511/map/layers/map"
    for ident in ("incidents", "hpIncidents", "acon", "weatherClosures", "otherWeather", "sEvents", "sActivity", "dms", "cams"):
        status, h, body = show(ident, f"{va}/{ident}", 120)
        d = _json(body)
        feats = d.get("features") if isinstance(d, dict) else None
        if isinstance(feats, list):
            icons = {}
            for f in feats:
                k = str(((f or {}).get("properties") or {}).get("icon"))
                icons[k] = icons.get(k, 0) + 1
            print(f"      {len(feats)} features, icons {sorted(icons.items(), key=lambda kv: -kv[1])[:8]}")
            if ident == "cams":
                active = [f for f in feats if ((f or {}).get("properties") or {}).get("active")]
                hosts = {}
                for f in feats:
                    u = ((f or {}).get("properties") or {}).get("image_url") or ""
                    hh = urllib.parse.urlsplit(u).netloc
                    hosts[hh] = hosts.get(hh, 0) + 1
                print(f"      active {len(active)}; still hosts {hosts}")
                if active:
                    p = active[0]["properties"]
                    show("a still", p.get("image_url"), 8)
                    show("a stream", p.get("https_url"), 300)
            if ident in ("acon", "sEvents"):
                now = time.time()
                live = [f for f in feats if (((f or {}).get("properties") or {}).get("start") or 0) <= now < (((f or {}).get("properties") or {}).get("end") or 1e12)]
                print(f"      in their window now {len(live)} of {len(feats)}")
            if ident == "dms":
                blank = [f for f in feats if not str(((f or {}).get("properties") or {}).get("text") or "").strip()]
                print(f"      blank {len(blank)}; texts with a page mark:",
                      [((f.get("properties") or {}).get("text")) for f in feats if re.search(r"\[|\||/", str((f.get("properties") or {}).get("text")))][:6])

    head("The phone's own requests: West Virginia")
    wv = "https://www.wv511.org/wsvc/gmap.asmx"
    for label, url in (("incidents", f"{wv}/buildEventsKMLi_Filtered?CategoryIDs=&SeverityIDs=1%2C2%2C3%2C4%2C&is511Only=0"),
                       ("roadwork", f"{wv}/buildEventsKMLc"),
                       ("signs", f"{wv}/buildDMSKML?isActive=1"),
                       ("cameras", f"{wv}/buildCamerasJSONjs")):
        status, h, body = show(label, url, 100)
        marks = re.findall(r"(?s)<Placemark.*?</Placemark>", body)
        styles = {}
        for m in marks:
            k = (re.findall(r"<styleUrl>#?([^<]+)</styleUrl>", m) or ["?"])[0]
            styles[k] = styles.get(k, 0) + 1
        if marks:
            print(f"      {len(marks)} placemarks, styles {sorted(styles.items(), key=lambda kv: -kv[1])[:12]}")
        if label == "signs":
            for m in marks[:6]:
                print("      sign:", re.sub(r"\s+", " ", m)[:700])
            if marks:
                start = body.find("<Placemark")
                _fixture("wv-signs-active", re.sub(r"\s*\n\s*", " ", body[:start] + "".join(marks[:5]) + "</Document></kml>"))
        if label == "incidents" and marks:
            start = body.find("<Placemark")
            _fixture("wv-incidents", re.sub(r"\s*\n\s*", " ", body[:start] + "".join(marks[:8]) + "</Document></kml>"))

    head("West Virginia: a camera's stream page")
    ref = {**BROWSER, "Referer": "https://www.wv511.org/"}
    for cam in ("CAM117", "CAM001"):
        st_, _, h, page = get(f"https://www.wv511.org/flowplayeri.aspx?CAMID={cam}", headers=ref, limit=4_000_000)
        print(f"   flowplayeri {cam}: status {st_} bytes {len(page)}")
        found = sorted(set(re.findall(r"""(?:https?:)?//[^\s"'<>]+|[^\s"'<>]*\.m3u8[^\s"'<>]*""", page)))
        print("   urls:", found[:60])
        for needle in ("m3u8", "rtplive", "wowza", "stream", "src", "clip", "playlist"):
            for ctx in _context(page, needle, 200, 300, 3) or []:
                print(f"   [{needle}]", ctx)
        streams = [u for u in found if "m3u8" in u]
        for u in streams[:2]:
            u = "https:" + u if u.startswith("//") else u
            for hdr, label in ((app, "app"), (ref, "browser")):
                status, _, hh, body = get(u, headers=hdr, limit=400_000)
                print(f"   {label} {u}: status {status} type {hh.get('Content-Type')} bytes {len(body)}:", body[:300].replace("\n", " | "))
        if streams:
            break

    head("The phone's own requests: Kentucky")
    base = "https://services2.arcgis.com/CcI36Pduqd0OR4W9/arcgis/rest/services"
    q = "query?where=1%3D1&outFields=*&returnGeometry=false&resultRecordCount=2000&f=json"
    for label, url in (("incidents", f"{base}/kytc_incidents_reports_24hr/FeatureServer/0/{q}"),
                       ("closures", f"{base}/KYTC_Road_Closures/FeatureServer/0/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&resultRecordCount=2000&f=json"),
                       ("cameras", f"{base}/trafficCamerasCur_Prd/FeatureServer/0/{q}")):
        status, h, body = show(label, url, 100)
        d = _json(body)
        if isinstance(d, dict):
            feats = d.get("features") or []
            print(f"      {len(feats)} features, exceededTransferLimit {d.get('exceededTransferLimit')}")
            if label == "cameras":
                hosts = {}
                for f in feats:
                    a = f.get("attributes") or {}
                    if a.get("state") == "Kentucky":
                        hh = urllib.parse.urlsplit(a.get("snapshot") or "").netloc + " " + str(a.get("status"))
                        hosts[hh] = hosts.get(hh, 0) + 1
                print("      Kentucky's stills by host and status:", hosts)
                ky = [f for f in feats if (f.get("attributes") or {}).get("state") == "Kentucky"]
                if ky:
                    show("a still", ky[0]["attributes"]["snapshot"].replace("http://", "https://"), 8)

    head("The build's camera lists, the three states")
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "pipeline", "search"))
    import dot_cameras
    for state in ("VA", "WV", "KY"):
        fetch = dot_cameras.FETCHERS.get(state)
        if fetch is None:
            print(f"   {state}: no fetcher ({dot_cameras.NO_LIST.get(state)})")
            continue
        try:
            rows = fetch()
            print(f"   {state}: {len(rows)} cameras; first {rows[:2]}")
        except Exception as e:  # noqa: BLE001 - a probe reports every failure
            print(f"   {state}: FAILED {type(e).__name__}: {str(e)[:200]}")


SECTIONS = {"nycdot": nycdot, "511ny": fiveeleven, "511ny-new": fiveeleven_new, "511ny-travel": fiveeleven_travel,
            "addresses": addresses, "weather-alerts": weather_alerts, "weather-forecast": weather_forecast,
            "weather-radar": weather_radar, "weather-current": weather_current, "weather-states": weather_states,
            "dot-ct": dot_ct, "dot-nj": dot_nj, "dot-pa": dot_pa, "dot-md": dot_md, "dot-de": dot_de, "dot-dc": dot_dc,
            "ibi-join": ibi_join, "chart-more": chart_more, "deldot-more": deldot_more, "nj-config": nj_config, "hls": hls_frames,
            "dot-midwest-ibi": dot_midwest_ibi, "dot-midwest-other": dot_midwest_other,
            "dot-midwest-cars": dot_midwest_cars, "dot-midwest-2": dot_midwest_2, "dot-midwest-3": dot_midwest_3, "dot-midwest-4": dot_midwest_4, "dot-midwest-5": dot_midwest_5,
            "dot-midwest-6": dot_midwest_6, "dot-midwest-7": dot_midwest_7,
            "dot-midwest-8": dot_midwest_8, "dot-midwest-9": dot_midwest_9,
            "dot-midwest-10": dot_midwest_10,
            "app-requests": app_requests, "iowa-slugs": iowa_slugs, "dot-south": dot_south,
            "dot-south-2": dot_south_2, "dot-south-3": dot_south_3,
            "dot-south-4": dot_south_4, "dot-south-5": dot_south_5,
            "dot-south-6": dot_south_6, "dot-south-7": dot_south_7,
            "dot-south-8": dot_south_8}


class Timeout(Exception):
    pass


def _alarm(*_):
    raise Timeout("no answer within 45 s")


if __name__ == "__main__":
    signal.signal(signal.SIGALRM, _alarm)
    for name in sys.argv[1:] or SECTIONS:
        SECTIONS[name]()
