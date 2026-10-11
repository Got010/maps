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
import re
import signal
import sys
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


SECTIONS = {"nycdot": nycdot, "511ny": fiveeleven, "511ny-new": fiveeleven_new, "511ny-travel": fiveeleven_travel,
            "addresses": addresses, "weather-alerts": weather_alerts, "weather-forecast": weather_forecast,
            "weather-radar": weather_radar, "weather-current": weather_current, "weather-states": weather_states,
            "dot-ct": dot_ct, "dot-nj": dot_nj, "dot-pa": dot_pa, "dot-md": dot_md, "dot-de": dot_de, "dot-dc": dot_dc,
            "ibi-join": ibi_join, "chart-more": chart_more, "deldot-more": deldot_more, "nj-config": nj_config, "hls": hls_frames,
            "dot-midwest-ibi": dot_midwest_ibi, "dot-midwest-other": dot_midwest_other,
            "dot-midwest-cars": dot_midwest_cars, "dot-midwest-2": dot_midwest_2, "dot-midwest-3": dot_midwest_3}


class Timeout(Exception):
    pass


def _alarm(*_):
    raise Timeout("no answer within 45 s")


if __name__ == "__main__":
    signal.signal(signal.SIGALRM, _alarm)
    for name in sys.argv[1:] or SECTIONS:
        SECTIONS[name]()
