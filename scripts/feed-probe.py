#!/usr/bin/env python3
"""Prints what the live-data hosts really send, for fixtures and checks.

The dev container can't reach 511NY, NYC Open Data or gis.ny.gov, but a
GitHub runner can, so .github/workflows/feed-probe.yml runs this and the
answers end up in the job log. No key is used: 511NY is asked with a dummy
key (to see its error document) and for its public API docs. Nothing here
carries a location, search text or route.

    feed-probe.py [section ...]   sections: nycdot 511ny addresses tomtom (default: all)
"""

import html
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = "Southbound feed probe (+https://github.com/StrongHorse44/maps)"


def get(url, limit=4_000_000, method="GET"):
    """(status, final url, headers, body text) without raising on HTTP errors."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read(limit) if method == "GET" else b""
            return r.status, r.geturl(), dict(r.headers), body.decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, url, dict(e.headers), e.read(limit).decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 - a probe reports every failure
        return None, url, {}, f"{type(e).__name__}: {e}"


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
    show(base + "?%24select=link_id,max(data_as_of)%20as%20newest&%24group=link_id&%24order=newest%20DESC&%24limit=400",
         chars=200, label="links by newest reading (count below)")
    _, _, _, b = get(base + "?%24select=link_id,max(data_as_of)%20as%20newest&%24group=link_id&%24limit=2000")
    try:
        rows = json.loads(b)
        print(f"distinct links: {len(rows)}; newest per link, top 5:",
              sorted((r.get("newest", "") for r in rows), reverse=True)[:5],
              "oldest 5:", sorted(r.get("newest", "") for r in rows)[:5])
    except ValueError:
        print("group query not JSON:", b[:300])
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
    show("http://207.251.86.229/nyc-links-cams/LinkSpeedQuery.txt", chars=800, label="DOT's own realtime text feed")


def fiveeleven():
    head("511NY")
    for path in ("/api/getevents?format=json", "/api/getevents?key=PROBE&format=json",
                 "/api/getcameras?key=PROBE&format=json", "/api/getmessagesigns?key=PROBE&format=json",
                 "/api/v2/get/event?key=PROBE&format=json", "/api/v2/get/cameras?key=PROBE&format=json",
                 "/api/v2/get/messagesigns?key=PROBE&format=json"):
        show("https://511ny.org" + path, chars=600)
    for path in ("/developers/help", "/developers/help/api/get-api-getevents_key_format",
                 "/developers/help/api/get-api-getcameras_key_format",
                 "/developers/help/api/get-api-getmessagesigns_key_format",
                 "/help/endpoint/event", "/help/endpoint/cameras", "/help/endpoint/messagesigns"):
        status, _, _, body = get("https://511ny.org" + path)
        print(f"--- doc {path}  status {status}")
        print(text_of(body)[:9000])


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
    for url in ("https://developer.tomtom.com/terms-and-conditions", "https://developer.tomtom.com/legal",
                "https://www.tomtom.com/legal/terms-and-conditions/", "https://developer.tomtom.com/legal/terms-of-use",
                "https://www.tomtom.com/en_gb/legal/eula-api/"):
        status, final, _, body = get(url)
        text = text_of(body)
        print(f"--- {url} status {status} final {final} ({len(text)} chars)")
        for para in re.split(r"\n", text):
            if re.search(r"(?i)cach|stor(e|age)|retain|routing|route|combin|third.part|navigation|offline|prefetch|mirror", para):
                print("  >", para[:700])


SECTIONS = {"nycdot": nycdot, "511ny": fiveeleven, "addresses": addresses, "tomtom": tomtom}

if __name__ == "__main__":
    for name in sys.argv[1:] or SECTIONS:
        SECTIONS[name]()
