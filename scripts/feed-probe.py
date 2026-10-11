#!/usr/bin/env python3
"""Prints what the live-data hosts really send, for fixtures and checks.

The dev container can't reach 511NY, NYC Open Data or gis.ny.gov, but a
GitHub runner can, so .github/workflows/feed-probe.yml runs this and the
answers end up in the job log. No key is used: 511NY is asked with a dummy
key (to see its error document) and for its public API docs. Nothing here
carries a location, search text or route.

    feed-probe.py [section ...]   sections: nycdot 511ny 511ny-new 511ny-travel addresses
                                  weather-alerts weather-forecast weather-radar weather-current (default: all)
"""

import html
import json
import re
import signal
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = "Southbound feed probe (+https://github.com/StrongHorse44/maps)"


def get(url, limit=4_000_000, method="GET"):
    """(status, final url, headers, body text) without raising on HTTP errors."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"}, method=method)
    print(f"... {method} {url}", flush=True)
    signal.alarm(45)  # a whole request, not just one socket read
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read(limit) if method == "GET" else b""
            return r.status, r.geturl(), dict(r.headers), body.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, url, dict(e.headers), e.read(limit).decode("utf-8", "replace")
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
    from collections import Counter
    base = "https://api-511x-nysdot.carsprogram.org/"
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
        print("property keys:", keys.most_common(80))
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
    groups = ",".join("@" + st for st in STATES)
    w, s_, e, n = ALL_BOX
    for url in (f"https://aviationweather.gov/api/data/metar?ids={groups}&format=json",
                f"https://aviationweather.gov/api/data/metar?bbox={s_},{w},{n},{e}&format=json"):
        status, _, headers, body = get(url, limit=10_000_000)
        d = _json(body)
        print(f"--- {url}\nstatus {status} bytes {len(body)} type {headers.get('Content-Type')}")
        if isinstance(d, list):
            states = {}
            for item in d:
                name = item.get("name") or ""
                code = name.rsplit(",", 2)[-2].strip() if name.count(",") >= 2 else "?"
                states[code] = states.get(code, 0) + 1
            print("stations", len(d), "by state:", dict(sorted(states.items())))
        else:
            print(body[:600])

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


def ibi_layers(site):
    """The map layers of an IBI-built 511 site (the old 511NY layout): /map/mapIcons/<layer>, /List/GetData/<layer>."""
    for layer in ("Cameras", "Incidents", "Closures", "Construction", "MessageSigns", "SpecialEvents"):
        try_json(f"{site}/map/mapIcons/{layer}", sample=500)
    try_json(f"{site}/List/GetData/Cameras?query=%7B%22columns%22%3A%5B%5D%2C%22start%22%3A0%2C%22length%22%3A5%7D", sample=900)


def dot_ct():
    head("Connecticut: CTroads (CTDOT's 511)")
    final, page = discover("https://ctroads.org/")
    for cfg in ("https://ctroads.org/configs/main.json", "https://www.ctroads.org/configs/main.json"):
        status, _, _, body = get(cfg, limit=3_000_000)
        print(f"--- {cfg}: status {status} bytes {len(body)}")
        apis = sorted(set(re.findall(r"https://api[^\"'\s]*carsprogram\.org", body)))
        print("   CARS APIs:", apis, "| layers:", sorted(set(re.findall(r'"api"\s*:\s*"([^"]+)"', body)))[:20])
        print("   ", body[:1500])
        for api in apis[:2]:
            cars_layers(api.rstrip("/"))
    for api in ("https://api-ctdot.carsprogram.org", "https://api-511x-ctdot.carsprogram.org", "https://api-ct.carsprogram.org"):
        try_json(api + "/cameras/map-features", sample=500)


def dot_nj():
    head("New Jersey: 511NJ")
    discover("https://511nj.org/")
    ibi_layers("https://511nj.org")
    for url in ("https://511nj.org/api/client/camera/getCameraDataByTourId?tourId=0",
                "https://511nj.org/client/get/getAllEvents", "https://511nj.org/api/events",
                "https://511nj.org/developers", "https://511nj.org/developer"):
        try_json(url, sample=500)


def dot_pa():
    head("Pennsylvania: 511PA")
    discover("https://www.511pa.com/")
    ibi_layers("https://www.511pa.com")
    for url in ("https://www.511pa.com/developers/doc", "https://www.511pa.com/api/v2/get/event?format=json",
                "https://www.511pa.com/map/data/events"):
        try_json(url, sample=500)


def dot_md():
    head("Maryland: CHART and MD 511")
    base = "https://chartexp1.sha.maryland.gov/CHARTExportClientService"
    for name in ("getEventMapDataJSON.do", "getCameraMapDataJSON.do", "getDMSMapDataJSON.do", "getRTMSMapDataJSON.do",
                 "getActiveIncidentDataJSON.do", "getClosureMapDataJSON.do"):
        try_json(f"{base}/{name}", sample=900)
    discover("https://chart.maryland.gov/")
    discover("https://md511.maryland.gov/")
    for cfg in ("https://md511.maryland.gov/configs/main.json",):
        status, _, _, body = get(cfg, limit=3_000_000)
        print(f"--- {cfg}: status {status} bytes {len(body)}")
        apis = sorted(set(re.findall(r"https://api[^\"'\s]*carsprogram\.org", body)))
        print("   CARS APIs:", apis)
        for api in apis[:2]:
            cars_layers(api.rstrip("/"))


def dot_de():
    head("Delaware: DelDOT")
    for url in ("https://tmc.deldot.gov/json/advisory.json", "https://tmc.deldot.gov/json/videocamera.json",
                "https://tmc.deldot.gov/json/dms.json", "https://tmc.deldot.gov/json/roadClosure.json",
                "https://deldot.gov/json/advisory.json"):
        try_json(url, sample=900)
    discover("https://deldot.gov/map/index.shtml")


def dot_dc():
    head("Washington, DC: DDOT")
    discover("https://ddot.dc.gov/")
    for url in ("https://opendata.dc.gov/api/search/v1/collections/all/items?q=traffic%20camera&limit=10",
                "https://opendata.dc.gov/api/search/v1/collections/all/items?q=road%20closure&limit=10"):
        status, d = try_json(url, sample=400)
        for feat in (d or {}).get("features", [])[:10] if isinstance(d, dict) else []:
            p = feat.get("properties", {})
            print("   hub item", feat.get("id"), "|", p.get("title"), "|", p.get("type"), "|", p.get("url"))


SECTIONS = {"nycdot": nycdot, "511ny": fiveeleven, "511ny-new": fiveeleven_new, "511ny-travel": fiveeleven_travel,
            "addresses": addresses, "weather-alerts": weather_alerts, "weather-forecast": weather_forecast,
            "weather-radar": weather_radar, "weather-current": weather_current, "weather-states": weather_states,
            "dot-ct": dot_ct, "dot-nj": dot_nj, "dot-pa": dot_pa, "dot-md": dot_md, "dot-de": dot_de, "dot-dc": dot_dc}


class Timeout(Exception):
    pass


def _alarm(*_):
    raise Timeout("no answer within 45 s")


if __name__ == "__main__":
    signal.signal(signal.SIGALRM, _alarm)
    for name in sys.argv[1:] or SECTIONS:
        SECTIONS[name]()
