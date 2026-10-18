"""Builds the Free Wi-Fi pack (wifi-us.sqlite) from a filtered, merged OSM extract.

Usage: build_wifi.py <merged.osm.pbf> <out.sqlite> --chains chains.json --max-mb 60 --sources sources.json

The input is what wifi/build.sh prepares: the Geofabrik extracts of every covered state, cut by `osmium
tags-filter` to the objects that can matter here, merged, one version per object. Every object is classified
by its tags into one of four tiers (the rules are in classify(); the app shows the tier as a confidence label,
and never presents tiers 2 to 4 as confirmed):

  1  Tagged free              internet_access=wlan|yes|wifi and internet_access:fee=no (or no fee tag and
                              fee=no); internet_access:fee=customers is kept as fee "customers" (the app's
                              "Free for customers" badge)
  2  Tagged Wi-Fi (fee not    internet_access=wlan|yes|wifi with no internet_access:fee and no fee=no
     stated)
  3  Chain policy            brand:wikidata is one of the chains in chains.json
  4  Likely                   libraries, hotels and motels, rest areas, welcome centers, National Park
                              visitor centers

A spot takes the lowest tier it matches. internet_access:fee=yes, internet_access=no and
internet_access=terminal exclude an object from every tier, even a chain or a library; so do access=private and
access=no (a staff-only room, a school's library) and tagging that only says the place is gone (disused:,
abandoned:, was:, demolished:, removed:, razed:, destroyed: with no live feature of its own). Objects with no
name and no brand are kept: a chain's (any tier) takes the chain's name from chains.json, the others have a NULL name (the
app shows a fallback from the category).

Tier 3 spots of one chain within 60 m of each other are one store: a Walmart's pharmacy and garden centre, a
McDonald's PlayPlace or a charging station carry the chain's brand:wikidata too. One row is kept per store (the
chain's "categories" first, then the row named like the chain, then the lowest OSM id); the build log says how
many were folded. Tiers 1, 2 and 4 are never folded.

chains.json (edited by hand; the build fails loudly when it is malformed):

  {"chains": [
    {"name": "Starbucks", "wikidata": ["Q37158"], "fee": "customers"},
    {"name": "Pilot", "wikidata": ["Q64128179"], "fee": "customers", "note": "free text, not read"}
  ]}

  name      the chain's name: for the build log, and the name of a spot of the chain that has none of its own
  wikidata  one or more brand:wikidata QIDs (the chain is matched by these, never by name)
  fee       "no", "customers" (the default) or "unknown": the fee column of the chain's spots
  categories optional: the chain's main OSM categories (the amenity/shop value of its stores, e.g. "supermarket"
            for Walmart, "fuel" for a truck stop). Used only to choose which of several rows one store is made of
            (see fold_sub_objects)
  note      optional, ignored

The output is SQLite: meta, spot, cluster (a Web Mercator tile grid for zoom 3 to 11, for the map's low
zooms), the B-tree indexes spot_lat_lon and spot_tier_lat_lon, and the R-tree table wifi_rtree when this
SQLite has the module. application_id 0x53425731 ("SBW1"), user_version 1. The build prints the rows per
tier and category and, for every QID in chains.json, how many objects carry it and its three commonest
names, so a wrong QID shows at once.
"""

import argparse
import collections
import hashlib
import json
import math
import os
import re
import sqlite3
import sys
import time

APPLICATION_ID = 0x53425731  # "SBW1"
SCHEMA_VERSION = 1
CLUSTER_ZOOMS = range(3, 12)  # 3..11 inclusive (the app reads MAX(zoom) from the table, so either range works)
MAX_LATITUDE = 85.05112878  # Web Mercator's edge

WLAN = ("wlan", "yes", "wifi")
FEES = ("no", "customers", "unknown")
TIER_LABELS = {1: "Tagged free", 2: "Tagged Wi-Fi (fee not stated)", 3: "Chain policy", 4: "Likely"}
NPS_QID = "Q308439"  # National Park Service
QID = re.compile(r"^Q[0-9]+$")
# A feature key that is live on an object (so that a leftover disused: key beside it is noise), and the keys a
# gone-prefix counts against: disused:amenity is a death marker, was:operator or was:iata is not.
FEATURE_KEYS = ("amenity", "shop", "tourism", "highway", "office", "leisure", "craft", "healthcare", "club",
                "railway", "public_transport", "aeroway", "historic")
GONE_PREFIXES = ("disused:", "abandoned:", "was:", "demolished:", "removed:", "razed:", "destroyed:")
# What makes an object a place, in search/build_search.py's order (PLACE_KEYS, SKIP_VALUES, STATION_TAGS), then
# office and highway. The literal "yes" is never a category.
PLACE_KEYS = ("amenity", "shop", "tourism", "leisure", "craft", "healthcare", "historic")
SKIP_VALUES = ("yes", "no", "parking_space", "bench", "waste_basket", "vending_machine", "bicycle_parking")
STATION_TAGS = (("railway", "station"), ("railway", "halt"), ("public_transport", "station"), ("aeroway", "aerodrome"))

SCHEMA = f"""
PRAGMA application_id = {APPLICATION_ID};
PRAGMA user_version = {SCHEMA_VERSION};
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE spot (
  id INTEGER PRIMARY KEY,
  osm_type TEXT NOT NULL,
  osm_id INTEGER NOT NULL,
  name TEXT,
  brand_wikidata TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  category TEXT NOT NULL,
  tier INTEGER NOT NULL,
  fee TEXT NOT NULL,
  opening_hours TEXT,
  website TEXT
);
CREATE TABLE cluster (
  zoom INTEGER,
  x INTEGER,
  y INTEGER,
  count INTEGER,
  lat REAL,
  lon REAL,
  tier_min INTEGER
);
"""
INDEXES = """
CREATE INDEX spot_lat_lon ON spot(lat, lon);
CREATE INDEX spot_tier_lat_lon ON spot(tier, lat, lon);
CREATE INDEX cluster_zoom_x_y ON cluster(zoom, x, y);
"""
RTREE = "CREATE VIRTUAL TABLE wifi_rtree USING rtree(id, minlat, maxlat, minlon, maxlon)"
ATTRIBUTION = "© OpenStreetMap contributors, ODbL"


class ChainsError(ValueError):
    """chains.json is malformed."""


class Chains:
    """The chains of chains.json, looked up by QID."""

    def __init__(self, chains, sha256):
        self.chains = chains  # [{"name", "wikidata": [...], "fee"}]
        self.sha256 = sha256
        self.by_qid = {}
        for chain in chains:
            for qid in chain["wikidata"]:
                self.by_qid[qid] = chain

    def qids(self):
        return sorted(self.by_qid)


def parse_chains(text, label="chains.json"):
    """The chains in `text` (chains.json's content); raises ChainsError on anything off."""
    try:
        data = json.loads(text)
    except ValueError as e:
        raise ChainsError(f"{label} is not valid JSON: {e}") from None
    if not isinstance(data, dict) or set(data) != {"chains"}:
        raise ChainsError(f'{label} must be an object with one key, "chains"')
    if not isinstance(data["chains"], list) or not data["chains"]:
        raise ChainsError(f'{label}: "chains" must be a non-empty list')
    chains = []
    seen = {}
    for i, entry in enumerate(data["chains"]):
        where = f"{label}: chain {i + 1}"
        if not isinstance(entry, dict):
            raise ChainsError(f"{where} must be an object")
        extra = set(entry) - {"name", "wikidata", "fee", "categories", "note"}
        if extra:
            raise ChainsError(f"{where} has unknown keys {sorted(extra)} (allowed: name, wikidata, fee, categories, note)")
        name = entry.get("name")
        if not isinstance(name, str) or not name.strip():
            raise ChainsError(f'{where} needs a "name"')
        where = f"{label}: {name}"
        qids = entry.get("wikidata")
        if not isinstance(qids, list) or not qids:
            raise ChainsError(f'{where}: "wikidata" must be a non-empty list of QIDs')
        for qid in qids:
            if not isinstance(qid, str) or not QID.match(qid):
                raise ChainsError(f"{where}: {qid!r} is not a Wikidata QID (like Q37158)")
            if qid in seen:
                raise ChainsError(f"{where}: {qid} is already {seen[qid]}'s")
            seen[qid] = name
        fee = entry.get("fee", "customers")
        if fee not in FEES:
            raise ChainsError(f'{where}: "fee" must be one of {", ".join(FEES)}, not {fee!r}')
        if "note" in entry and not isinstance(entry["note"], str):
            raise ChainsError(f'{where}: "note" must be text')
        categories = entry.get("categories", [])
        if not isinstance(categories, list) or not all(isinstance(c, str) and c.strip() for c in categories):
            raise ChainsError(f'{where}: "categories" must be a list of OSM values like "supermarket"')
        chains.append({"name": name.strip(), "wikidata": list(qids), "fee": fee,
                       "categories": [c.strip().lower() for c in categories]})
    return Chains(chains, hashlib.sha256(text.encode("utf-8")).hexdigest())


def load_chains(path):
    with open(path, encoding="utf-8") as f:
        return parse_chains(f.read(), os.path.basename(path))


# ---- Classification (pure functions of an object's tags) -------------------------------------------------


def value(tags, key):
    """A tag's value, trimmed and lower-cased; None when absent or empty."""
    v = tags.get(key)
    if v is None:
        return None
    v = v.strip().lower()
    return v or None


def text(tags, key):
    """A tag's value as written, trimmed; None when absent or empty."""
    v = tags.get(key)
    if v is None:
        return None
    return v.strip() or None


def is_lifecycle_dead(tags):
    """True when the object says only that the place is gone: disused:amenity=cafe, abandoned:shop=... (also
    was:, demolished:, removed:, razed:, destroyed:) with no live amenity, shop, tourism... of its own, or a
    vacant shop (shop=vacant) or disused=yes. Only a prefixed feature key counts (was:operator and was:iata
    on a live station or aerodrome are ordinary history)."""
    if value(tags, "shop") == "vacant" or value(tags, "disused") == "yes":
        return True
    for key in tags:
        for prefix in GONE_PREFIXES:
            base = key[len(prefix):] if key.startswith(prefix) else None
            if base in FEATURE_KEYS and base not in tags:
                return not any(tags.get(k) for k in FEATURE_KEYS)
    return False


def likely_category(tags):
    """Tier 4's category when the tags describe a place that most likely has Wi-Fi, else None."""
    if value(tags, "amenity") == "library":
        return "library"
    tourism = value(tags, "tourism")
    if tourism in ("hotel", "motel"):
        return tourism
    if value(tags, "highway") == "rest_area":
        return "rest_area"
    if tourism == "information":
        information = value(tags, "information")
        if information == "office":
            return "welcome_center"
        if information == "visitor_centre":
            operator = (tags.get("operator") or "").lower()
            if (tags.get("operator:wikidata") or "").strip() == NPS_QID or "national park" in operator:
                return "visitor_centre"
    return None


def osm_category(tags):
    """The OSM value that names the kind of place, in the search pack's place_category order (PLACE_KEYS:
    amenity, shop, tourism, leisure, craft, healthcare, historic; then a station or aerodrome), then office and
    highway, which come later here than office does in the search pack (it has office before craft). Values the
    search pack skips ("yes", "no", benches... in any case) never count; "place" when nothing is left."""
    for key in PLACE_KEYS:
        v = text(tags, key)
        if v and v.lower() not in SKIP_VALUES:
            return v
    for key, wanted in STATION_TAGS:
        if value(tags, key) == wanted:
            return "station" if key in ("railway", "public_transport") and wanted == "station" else wanted
    for key in ("office", "highway"):
        v = text(tags, key)
        if v and v.lower() not in SKIP_VALUES:
            return v
    return "place"


Verdict = collections.namedtuple(
    "Verdict", "tier fee category name brand_wikidata opening_hours website"
)


def classify(tags, chains):
    """The Verdict for an object's tags (a dict), or None when it is not a Wi-Fi spot.

    Order: exclusions first (they beat every tier), then the lowest tier that matches. A spot of a configured
    chain (any tier) with no name or brand of its own is called by the chain's name; the others keep a NULL name."""
    internet = value(tags, "internet_access")
    internet_fee = value(tags, "internet_access:fee")
    if internet_fee == "yes" or internet in ("no", "terminal") or value(tags, "access") in ("private", "no"):
        return None
    if is_lifecycle_dead(tags):
        return None

    tier = fee = None
    if internet in WLAN:
        if internet_fee == "no":
            tier, fee = 1, "no"
        elif internet_fee == "customers":
            tier, fee = 1, "customers"
        elif internet_fee is None:
            if value(tags, "fee") == "no":
                tier, fee = 1, "no"
            else:
                tier, fee = 2, "unknown"
        # Any other internet_access:fee value is not understood: neither tier 1 nor 2 claims the object.
    brand = text(tags, "brand:wikidata")
    chain = chains.by_qid.get(brand) if brand else None
    if tier is None and chain is not None:
        tier, fee = 3, chain["fee"]
    category = None
    if tier is None:
        category = likely_category(tags)
        if category:
            tier, fee = 4, "unknown"
    if tier is None:
        return None
    name = text(tags, "name") or text(tags, "brand")
    if name is None and chain is not None:
        name = chain["name"]
    if category is None:
        category = osm_category(tags)
    return Verdict(
        tier, fee, category, name, brand,
        text(tags, "opening_hours"), text(tags, "website") or text(tags, "contact:website"),
    )


# ---- Clusters ------------------------------------------------------------------------------------------


def tile_of(lat, lon, zoom):
    """The Web Mercator (slippy map) tile x, y of a point at a zoom."""
    n = 1 << zoom
    lat = max(min(lat, MAX_LATITUDE), -MAX_LATITUDE)
    x = math.floor((lon + 180.0) / 360.0 * n)
    y = math.floor((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


class Clusters:
    """Per zoom and tile: how many spots, their mean position and the best tier among them."""

    def __init__(self, zooms=CLUSTER_ZOOMS):
        self.zooms = list(zooms)
        self.cells = {}  # (zoom, x, y) -> [count, lat sum, lon sum, tier_min]

    def add(self, lat, lon, tier):
        for zoom in self.zooms:
            x, y = tile_of(lat, lon, zoom)
            cell = self.cells.get((zoom, x, y))
            if cell is None:
                self.cells[(zoom, x, y)] = [1, lat, lon, tier]
            else:
                cell[0] += 1
                cell[1] += lat
                cell[2] += lon
                if tier < cell[3]:
                    cell[3] = tier

    def rows(self):
        return [
            (zoom, x, y, count, round(lat_sum / count, 6), round(lon_sum / count, 6), tier_min)
            for (zoom, x, y), (count, lat_sum, lon_sum, tier_min) in sorted(self.cells.items())
        ]


# ---- Geometry ------------------------------------------------------------------------------------------


def points_centre(points, closed):
    """A point for a way's (lat, lon) nodes: the mean of a closed ring's corners, else the middle node."""
    if not points:
        return None
    if closed:
        corners = points[:-1] if len(points) > 1 else points
        return sum(p[0] for p in corners) / len(corners), sum(p[1] for p in corners) / len(corners)
    return points[len(points) // 2]


def read_objects(pbf):
    """Yields (osm_type, osm_id, tags, lat, lon) for every tagged node, way and multipolygon relation in the
    file: a node at itself, a way at its middle node (a closed way at the mean of its corners), a relation
    at the mean of its first outer ring's corners. Objects with no usable position are skipped."""
    import osmium  # only needed to read a file

    processor = osmium.FileProcessor(pbf).with_locations().with_areas().with_filter(osmium.filter.EmptyTagFilter())
    for obj in processor:
        if obj.is_node():
            if obj.location.valid():
                yield "n", obj.id, {t.k: t.v for t in obj.tags}, obj.location.lat, obj.location.lon
        elif obj.is_way():
            points = [(n.lat, n.lon) for n in obj.nodes if n.location.valid()]
            centre = points_centre(points, obj.is_closed())
            if centre:
                yield "w", obj.id, {t.k: t.v for t in obj.tags}, centre[0], centre[1]
        elif obj.is_area() and not obj.from_way():
            for ring in obj.outer_rings():
                points = [(n.lat, n.lon) for n in ring if n.location.valid()]
                centre = points_centre(points, True)
                if centre:
                    yield "r", obj.orig_id(), {t.k: t.v for t in obj.tags}, centre[0], centre[1]
                break


# ---- Chain sub-objects ----------------------------------------------------------------------------------

FOLD_METRES = 60.0
FOLD_CELL = 0.001  # degrees: a grid cell is about 110 m high, so a neighbour search finds everything within 60 m

Spot = collections.namedtuple("Spot", "osm_type osm_id lat lon verdict")


def metres_between(a, b):
    """Distance in metres between two spots (flat-earth, good to well under a metre at 60 m)."""
    dlat = (a.lat - b.lat) * 110540.0
    dlon = (a.lon - b.lon) * 111320.0 * math.cos(math.radians((a.lat + b.lat) / 2))
    return math.hypot(dlat, dlon)


def fold_sub_objects(spots, chains):
    """Folds in-store sub-objects into their stores: Walmart's pharmacy, a McDonald's PlayPlace, a garden centre
    or a charging station all carry the chain's brand:wikidata, and would show as chain spots of their own.

    Among tier 3 spots with the same brand_wikidata, any two within 60 m are one store (a chain of such pairs is
    one store too); one row is kept per store: the one whose category is in the chain's "categories" (when
    chains.json lists them), else the one named like the chain, else the lowest OSM id. Only tier 3 is touched:
    a sub-object tagged with Wi-Fi of its own is tier 1 or 2 and stays. Returns (the spots that are left, a
    Counter of the rows folded away per QID). A grid of FOLD_CELL degrees keeps it linear."""
    by_qid = collections.defaultdict(list)
    for i, spot in enumerate(spots):
        qid = spot.verdict.brand_wikidata
        if spot.verdict.tier == 3 and qid in chains.by_qid:
            by_qid[qid].append(i)
    dropped = set()
    folded = collections.Counter()
    for qid, indexes in by_qid.items():
        if len(indexes) < 2:
            continue
        parent = {i: i for i in indexes}

        def find(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i

        grid = collections.defaultdict(list)
        for i in indexes:
            grid[(math.floor(spots[i].lat / FOLD_CELL), math.floor(spots[i].lon / FOLD_CELL))].append(i)
        for i in indexes:
            here = spots[i]
            row, col = math.floor(here.lat / FOLD_CELL), math.floor(here.lon / FOLD_CELL)
            # Cells are narrower than 60 m in longitude far to the north: look as many cells aside as it takes.
            reach = max(1, math.ceil(FOLD_METRES / (111320.0 * max(math.cos(math.radians(here.lat)), 0.05) * FOLD_CELL)))
            for dr in (-1, 0, 1):
                for dc in range(-reach, reach + 1):
                    for j in grid.get((row + dr, col + dc), ()):
                        if j > i and metres_between(here, spots[j]) <= FOLD_METRES:
                            parent[find(j)] = find(i)
        stores = collections.defaultdict(list)
        for i in indexes:
            stores[find(i)].append(i)
        chain = chains.by_qid[qid]
        main = set(chain.get("categories", []))
        chain_name = chain["name"].strip().lower()

        def preference(i):
            spot = spots[i]
            return (
                0 if spot.verdict.category.lower() in main else 1,
                0 if (spot.verdict.name or "").strip().lower() == chain_name else 1,
                spot.osm_id,
                spot.osm_type,
            )

        for members in stores.values():
            if len(members) > 1:
                keep = min(members, key=preference)
                for i in members:
                    if i != keep:
                        dropped.add(i)
                        folded[qid] += 1
    return [spot for i, spot in enumerate(spots) if i not in dropped], folded


# ---- The pack --------------------------------------------------------------------------------------------


def parse_sources(arg):
    """--sources: a JSON list of {"name", "timestamp"}, inline or in a file. Returns the list."""
    raw = arg.strip()
    if not raw.startswith("["):
        with open(arg, encoding="utf-8") as f:
            raw = f.read()
    sources = json.loads(raw)
    if not isinstance(sources, list) or not sources:
        raise SystemExit("--sources must be a non-empty JSON list of {name, timestamp}")
    for source in sources:
        if not isinstance(source, dict) or not source.get("name") or not source.get("timestamp"):
            raise SystemExit(f"--sources entry without a name and a timestamp: {source!r}")
    return sources


def date_of(timestamp):
    """YYYY-MM-DD from an ISO timestamp (2026-10-17T20:21:15Z) or a date."""
    match = re.match(r"^(\d{4}-\d{2}-\d{2})", timestamp.strip())
    if not match:
        raise SystemExit(f"Not a date or timestamp: {timestamp!r}")
    return match.group(1)


def top_names(counter, n=3):
    return ", ".join(f"{name} ({count})" for name, count in counter.most_common(n)) or "none"


def build(objects, output, chains, sources, max_mb=None, built_at=None, log=None):
    """Writes the pack from `objects` (read_objects' tuples); returns the row counts per tier.

    Fails (and leaves no pack) when the file is larger than max_mb."""
    log = log or (lambda line: print(line, flush=True))
    built_at = built_at or time.strftime("%Y-%m-%d", time.gmtime())
    started = time.time()
    dates = {s["name"]: date_of(s["timestamp"]) for s in sources}
    if os.path.exists(output):
        os.remove(output)
    tmp = output + ".part"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;" + SCHEMA)

    clusters = Clusters()
    tiers = collections.Counter()
    categories = collections.Counter()
    fees = collections.Counter()
    nameless = collections.Counter()
    rows = []
    spots = []
    qid_seen = collections.Counter()
    qid_kept = collections.Counter()
    qid_names = collections.defaultdict(collections.Counter)
    seen = skipped = 0

    for osm_type, osm_id, tags, lat, lon in objects:
        seen += 1
        qid = text(tags, "brand:wikidata")
        if qid in chains.by_qid:
            qid_seen[qid] += 1
            qid_names[qid][text(tags, "name") or text(tags, "brand") or "(no name)"] += 1
        verdict = classify(tags, chains)
        if verdict is None:
            skipped += 1
            continue
        if qid in chains.by_qid:
            qid_kept[qid] += 1
        spots.append(Spot(osm_type, osm_id, round(lat, 6), round(lon, 6), verdict))

    spots, folded = fold_sub_objects(spots, chains)
    for qid, count in folded.items():
        qid_kept[qid] -= count
    for spot in spots:
        verdict = spot.verdict
        rows.append((
            spot.osm_type, spot.osm_id, verdict.name, verdict.brand_wikidata, spot.lat, spot.lon, verdict.category,
            verdict.tier, verdict.fee, verdict.opening_hours, verdict.website,
        ))
        clusters.add(spot.lat, spot.lon, verdict.tier)
        tiers[verdict.tier] += 1
        categories[(verdict.tier, verdict.category)] += 1
        if verdict.name is None:
            nameless[verdict.tier] += 1
        fees[verdict.fee] += 1
        if len(rows) >= 50_000:
            flush(db, rows)
    flush(db, rows)

    cluster_rows = clusters.rows()
    db.executemany("INSERT INTO cluster VALUES (?,?,?,?,?,?,?)", cluster_rows)
    rtree = True
    try:
        db.execute(RTREE)
        db.execute("INSERT INTO wifi_rtree SELECT id, lat, lat, lon, lon FROM spot")
    except sqlite3.OperationalError as e:
        rtree = False
        print(f"::warning::This SQLite has no R-tree module ({e}); the pack is written without wifi_rtree",
              file=sys.stderr)
    newest = max(dates.values())
    db.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("extract_date", newest),
            ("extract_dates", json.dumps(dates, sort_keys=True)),
            ("built_at", built_at),
            ("chains_sha256", chains.sha256),
            ("chains_version", chains.sha256),  # the name the pack's design note gave it; same value
            ("schema", str(SCHEMA_VERSION)),
            ("sources", json.dumps([s["name"] for s in sources])),
            ("attribution", ATTRIBUTION),
        ],
    )
    db.commit()
    db.executescript(INDEXES)
    db.commit()
    db.execute("VACUUM")
    db.close()

    size_mb = os.path.getsize(tmp) / 1e6
    log("== Wi-Fi pack summary ==")
    log(f"{seen} objects read, {sum(tiers.values())} spots kept, {skipped} not Wi-Fi spots or excluded"
        f" ({'with' if rtree else 'WITHOUT'} R-tree), in {time.time() - started:.0f}s")
    for tier in sorted(TIER_LABELS):
        log(f"tier {tier} {TIER_LABELS[tier]}: {tiers[tier]} ({nameless[tier]} without a name)")
    log("fee: " + ", ".join(f"{fee} {fees[fee]}" for fee in FEES))
    for tier in sorted(TIER_LABELS):
        by_category = sorted(((c, n) for (t, c), n in categories.items() if t == tier), key=lambda cn: -cn[1])
        if by_category:
            log(f"  tier {tier} categories: " + ", ".join(f"{c} {n}" for c, n in by_category[:30]))
    log(f"{len(cluster_rows)} cluster rows for zooms {CLUSTER_ZOOMS[0]} to {CLUSTER_ZOOMS[-1]}")
    log("chains (QID: objects with the tag, kept, folded into their store; the three commonest names):")
    for chain in chains.chains:
        for qid in chain["wikidata"]:
            flag = "" if qid_seen[qid] else "  <-- NONE SEEN: wrong QID, or a build without the whole country?"
            log(f"  {qid} {chain['name']}: {qid_seen[qid]} seen, {qid_kept[qid]} kept, {folded[qid]} folded; "
                f"{top_names(qid_names[qid])}{flag}")
    log(f"{sum(folded.values())} chain sub-objects folded into their stores (tier 3 spots of one chain within"
        f" {FOLD_METRES:g} m of each other are one store):")
    for chain in chains.chains:
        count = sum(folded[qid] for qid in chain["wikidata"])
        if count:
            log(f"  {chain['name']}: {count} chain sub-objects folded into their stores")
    log("extracts: " + ", ".join(f"{name} {date}" for name, date in sorted(dates.items())))
    log(f"extract_date {newest}, built_at {built_at}")
    log(f"{os.path.basename(output)}: {size_mb:.1f} MB")
    if max_mb is not None and size_mb > max_mb:
        os.remove(tmp)
        raise SystemExit(f"{output} would be {size_mb:.0f} MB, over the {max_mb:g} MB limit (--max-mb, WIFI_MAX_MB)")
    os.replace(tmp, output)
    return dict(tiers)


def flush(db, rows):
    if rows:
        db.executemany(
            "INSERT INTO spot (osm_type, osm_id, name, brand_wikidata, lat, lon, category, tier, fee,"
            " opening_hours, website) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            rows,
        )
        rows.clear()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pbf", nargs="?", help="the merged, filtered extract")
    parser.add_argument("output", nargs="?", help="the pack to write")
    parser.add_argument("--chains", required=True, help="chains.json")
    parser.add_argument("--max-mb", type=float, help="fail if the pack is larger than this")
    parser.add_argument("--sources", help="JSON list of {name, timestamp} (inline, or the path of a file)")
    parser.add_argument("--print-qids", action="store_true",
                        help="print the QIDs of chains.json, comma-separated, and exit (for osmium's filter)")
    args = parser.parse_args()
    try:
        chains = load_chains(args.chains)
    except ChainsError as e:
        raise SystemExit(f"ERROR: {e}") from None
    if args.print_qids:
        print(",".join(chains.qids()))
        return
    if not (args.pbf and args.output and args.sources):
        parser.error("pbf, output and --sources are required")
    build(read_objects(args.pbf), args.output, chains, parse_sources(args.sources), max_mb=args.max_mb)


if __name__ == "__main__":
    main()
