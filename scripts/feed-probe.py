#!/usr/bin/env python3
"""Prints what the live-data hosts really send, for fixtures and checks.

The dev container can't reach 511NY, NYC Open Data or gis.ny.gov, but a
GitHub runner can, so .github/workflows/feed-probe.yml runs this and the
answers end up in the job log. No key is used: 511NY is asked with a dummy
key (to see its error document) and for its public API docs. Nothing here
carries a location, search text or route.

    feed-probe.py [section ...]   sections: nycdot 511ny 511ny-new addresses tomtom (default: all)
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
    head("511NY new platform")
    events = cars("events", n=3) or []
    if isinstance(events, list):
        from collections import Counter
        for field in ("type", "eventType", "category", "subType", "severity", "status", "priority", "source"):
            values = Counter(str(e.get(field)) for e in events if isinstance(e, dict) and field in e)
            if values:
                print(f"{field}:", values.most_common(25))
        closures = [e for e in events if isinstance(e, dict) and "clos" in json.dumps(e).lower()][:2]
        for e in closures:
            print("closure-like:", json.dumps(e)[:5000])
    cars("signs", n=3)
    cars("cameras", n=3)
    for path in ("events?limit=1", "api/events", "events/", "signs/", "cameras/"):
        status, _, h, b = get("https://api-511x-nysdot.carsprogram.org/" + path)
        print(f"--- {path} {status} {h.get('Content-Type')} {b[:200]!r}")


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


def tomtom():
    head("TomTom terms")
    # The developer terms page is rendered by script: find the document it loads.
    _, _, _, page = get("https://docs.tomtom.com/legal/terms-and-conditions")
    print(page[:3000])
    docs = sorted(set(re.findall(r"""["'(]((?:https?://[^"' )]+|/)[^"' )]*(?:legal|terms)[^"' )]*)["')]""", page)))
    for u in docs[:40]:
        print("terms url", u)
    for u in docs[:15]:
        u = urllib.parse.urljoin("https://docs.tomtom.com/", u)
        status, final, _, body = get(u)
        text = text_of(body)
        print(f"--- {u} status {status} ({len(text)} chars)")
        for para in text.splitlines():
            if re.search(r"(?i)cach|stor(e|age)|retain|routing|route|combin|non-tomtom|third.part|offline|prefetch|open source", para):
                print("  >", para[:900])
    for url in ("https://developer.tomtom.com/terms-and-conditions", "https://developer.tomtom.com/legal",
                "https://www.tomtom.com/legal/terms-and-conditions/", "https://developer.tomtom.com/legal/terms-of-use",
                "https://www.tomtom.com/en_gb/legal/eula-api/"):
        status, final, _, body = get(url)
        text = text_of(body)
        print(f"--- {url} status {status} final {final} ({len(text)} chars)")
        for para in re.split(r"\n", text):
            if re.search(r"(?i)cach|stor(e|age)|retain|routing|route|combin|third.part|navigation|offline|prefetch|mirror", para):
                print("  >", para[:700])


SECTIONS = {"nycdot": nycdot, "511ny": fiveeleven, "511ny-new": fiveeleven_new, "addresses": addresses, "tomtom": tomtom}

class Timeout(Exception):
    pass


def _alarm(*_):
    raise Timeout("no answer within 45 s")


if __name__ == "__main__":
    signal.signal(signal.SIGALRM, _alarm)
    for name in sys.argv[1:] or SECTIONS:
        SECTIONS[name]()
