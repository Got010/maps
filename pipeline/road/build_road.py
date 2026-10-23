"""Builds the Road pack (road-us.sqlite) from a filtered, merged OSM extract.

Usage: build_road.py <merged.osm.pbf> <out.sqlite> --chains chains.json --max-mb 64 --sources sources.json

The input is what road/build.sh prepares: the Geofabrik extracts of every covered state, cut by `osmium
tags-filter` to the objects that can matter here, merged, one version per object. Every object is classified by
its tags into zero or more categories (the rules are in classify()); each category an object belongs to is one
row of the pack, so a diesel pump at a truck stop is both `fuel` and `truck`:

  camp      tourism=camp_site                      rv       tourism=caravan_site
  rest      highway=rest_area, highway=services    dump     amenity=sanitary_dump_station
  water     amenity=drinking_water                 toilet   amenity=toilets
  shower    amenity=shower                         fuel     amenity=fuel
  truck     amenity=fuel or highway=services with hgv=yes or a brand of chains.json
  laundry   shop=laundry                           library  amenity=library
  pharmacy  amenity=pharmacy                       hospital amenity=hospital
  clinic    amenity=clinic, or amenity=doctors with healthcare=clinic
  grocery   shop=supermarket
  park      boundary=national_park; boundary=protected_area with protect_class 2 to 6; leisure=park only when
            park:type=state_park or the operator's name has "State Park" in it

Every spot is a point: a node as it is, a way at its middle node (a closed way at the mean of its corners), a
relation at the mean of its first outer ring's corners. Only the name and the brand are kept as columns, and a
few fields per category as the JSON object `extra` (the table in README.md); addresses, phone numbers,
websites and every other tag are dropped. A field that is absent is unknown, never false: `extra` has no key for
it. An object tagged access=private or access=no is left out of every category (the Wi-Fi pack does the same);
any other access value (customers, permit, yes...) stays, and so does a place tagged as closed or disused.

chains.json (edited by hand; the build fails loudly when it is malformed) lists the truck stop brands:

  {"chains": [
    {"name": "Pilot", "brands": ["Pilot", "Pilot Flying J"], "wikidata": ["Q64128179"], "note": "not read"}
  ]}

  name      the chain's name, for the build log
  brands    names matched (whole, ignoring case) against an object's `brand` tag
  wikidata  QIDs matched against its `brand:wikidata` tag
  note      optional, ignored
  (a chain needs at least one brand or QID; a match by either makes a fuel station or a services area a truck stop)

The output is SQLite: meta, spot, cluster (a Web Mercator tile grid for zoom 3 to 11 by category, for the map's
low zooms), the B-tree indexes spot_lat_lon, spot_cat_lat_lon and cluster_zoom_x_y, and the R-tree table
road_rtree when this SQLite has the module. application_id 0x53425231 ("SBR1"), user_version 1. The build
prints the rows per category and, per truck stop chain, how many objects matched by name and by QID, so a wrong
QID shows at once.
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

APPLICATION_ID = 0x53425231  # "SBR1"
SCHEMA_VERSION = 1
CLUSTER_ZOOMS = range(3, 12)  # 3..11 inclusive, as the Wi-Fi pack (the app reads MAX(zoom) from the table)
MAX_LATITUDE = 85.05112878  # Web Mercator's edge
QID = re.compile(r"^Q[0-9]+$")

CATEGORIES = ("camp", "rv", "rest", "dump", "water", "toilet", "shower", "fuel", "truck", "laundry", "library",
              "pharmacy", "hospital", "clinic", "grocery", "park")

# The fields kept per category, as OSM tags, in `extra`. `diesel` is not a tag: it is true when fuel:diesel=yes.
CAMP_FIELDS = ("fee", "drinking_water", "toilets", "shower", "reservation", "opening_hours", "operator", "tents",
               "caravans")
FIELDS = {
    "camp": CAMP_FIELDS,
    "rv": CAMP_FIELDS + ("sanitary_dump_station",),
    "rest": ("toilets", "drinking_water", "opening_hours", "operator"),
    "dump": ("fee", "opening_hours"),
    "water": ("fee", "seasonal"),
    "toilet": ("fee", "opening_hours", "wheelchair"),
    "shower": ("fee", "opening_hours"),
    "fuel": ("diesel", "hgv", "opening_hours"),
    "truck": ("shower", "opening_hours"),
    "laundry": ("opening_hours",),
    "library": ("opening_hours",),
    "pharmacy": ("opening_hours",),
    "hospital": ("emergency",),
    "clinic": ("opening_hours",),
    "grocery": ("opening_hours",),
    "park": ("operator", "fee"),
}

SCHEMA = f"""
PRAGMA application_id = {APPLICATION_ID};
PRAGMA user_version = {SCHEMA_VERSION};
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE spot (
  id INTEGER PRIMARY KEY,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  category TEXT NOT NULL,
  name TEXT,
  brand TEXT,
  extra TEXT NOT NULL DEFAULT '{{}}'
);
CREATE TABLE cluster (
  zoom INTEGER,
  x INTEGER,
  y INTEGER,
  category TEXT,
  count INTEGER,
  lat REAL,
  lon REAL
);
"""
INDEXES = """
CREATE INDEX spot_lat_lon ON spot(lat, lon);
CREATE INDEX spot_cat_lat_lon ON spot(category, lat, lon);
CREATE INDEX cluster_zoom_x_y ON cluster(zoom, x, y);
"""
RTREE = "CREATE VIRTUAL TABLE road_rtree USING rtree(id, minlat, maxlat, minlon, maxlon)"
ATTRIBUTION = "© OpenStreetMap contributors, ODbL"


class ChainsError(ValueError):
    """chains.json is malformed."""


def normal_brand(brand):
    """A brand name for comparison: trimmed, lower-cased, curly apostrophes made straight, spaces collapsed."""
    return " ".join(brand.replace("’", "'").replace("‘", "'").lower().split())


class Chains:
    """The truck stop chains of chains.json, looked up by brand name or by QID."""

    def __init__(self, chains, sha256):
        self.chains = chains  # [{"name", "brands": [...], "wikidata": [...]}]
        self.sha256 = sha256
        self.by_qid = {}
        self.by_brand = {}
        for chain in chains:
            for qid in chain["wikidata"]:
                self.by_qid[qid] = chain
            for brand in chain["brands"]:
                self.by_brand[normal_brand(brand)] = chain

    def match(self, tags):
        """(the chain, matched by QID?, matched by name?) for an object's tags; (None, False, False) when none."""
        by_qid = self.by_qid.get(text(tags, "brand:wikidata") or "")
        brand = text(tags, "brand")
        by_name = self.by_brand.get(normal_brand(brand)) if brand else None
        return (by_qid or by_name), by_qid is not None, by_name is not None


def parse_chains(text_, label="chains.json"):
    """The chains in `text_` (chains.json's content); raises ChainsError on anything off."""
    try:
        data = json.loads(text_)
    except ValueError as e:
        raise ChainsError(f"{label} is not valid JSON: {e}") from None
    if not isinstance(data, dict) or set(data) != {"chains"}:
        raise ChainsError(f'{label} must be an object with one key, "chains"')
    if not isinstance(data["chains"], list) or not data["chains"]:
        raise ChainsError(f'{label}: "chains" must be a non-empty list')
    chains = []
    seen_qid = {}
    seen_brand = {}
    for i, entry in enumerate(data["chains"]):
        where = f"{label}: chain {i + 1}"
        if not isinstance(entry, dict):
            raise ChainsError(f"{where} must be an object")
        extra = set(entry) - {"name", "brands", "wikidata", "note"}
        if extra:
            raise ChainsError(f"{where} has unknown keys {sorted(extra)} (allowed: name, brands, wikidata, note)")
        name = entry.get("name")
        if not isinstance(name, str) or not name.strip():
            raise ChainsError(f'{where} needs a "name"')
        where = f"{label}: {name}"
        brands = entry.get("brands", [])
        if not isinstance(brands, list) or not all(isinstance(b, str) and b.strip() for b in brands):
            raise ChainsError(f'{where}: "brands" must be a list of brand names')
        qids = entry.get("wikidata", [])
        if not isinstance(qids, list):
            raise ChainsError(f'{where}: "wikidata" must be a list of QIDs')
        if not brands and not qids:
            raise ChainsError(f'{where} needs at least one of "brands" and "wikidata"')
        for qid in qids:
            if not isinstance(qid, str) or not QID.match(qid):
                raise ChainsError(f"{where}: {qid!r} is not a Wikidata QID (like Q64128179)")
            if qid in seen_qid:
                raise ChainsError(f"{where}: {qid} is already {seen_qid[qid]}'s")
            seen_qid[qid] = name
        for brand in brands:
            key = normal_brand(brand)
            if key in seen_brand:
                raise ChainsError(f"{where}: {brand!r} is already {seen_brand[key]}'s")
            seen_brand[key] = name
        if "note" in entry and not isinstance(entry["note"], str):
            raise ChainsError(f'{where}: "note" must be text')
        chains.append({"name": name.strip(), "brands": [b.strip() for b in brands], "wikidata": list(qids)})
    return Chains(chains, hashlib.sha256(text_.encode("utf-8")).hexdigest())


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


def protect_class_in_range(tags):
    """True when protect_class (a number, or several split by ";") has a class from 2 to 6: national park (2),
    natural monument (3), habitat or species management area (4), protected landscape (5), resource reserve (6)."""
    for part in (tags.get("protect_class") or "").split(";"):
        part = part.strip()
        if part.isdigit() and 2 <= int(part) <= 6:
            return True
    return False


def is_state_park(tags):
    """A leisure=park that is a state park: park:type=state_park, or "State Park" in the operator's name."""
    return value(tags, "park:type") == "state_park" or "state park" in (tags.get("operator") or "").lower()


def categories_of(tags, chains):
    """The categories an object belongs to, in CATEGORIES' order, each once. An empty list when it is private or off limits."""
    if value(tags, "access") in ("private", "no"):
        return []
    amenity, highway, tourism = value(tags, "amenity"), value(tags, "highway"), value(tags, "tourism")
    shop, boundary, leisure = value(tags, "shop"), value(tags, "boundary"), value(tags, "leisure")
    found = set()
    if tourism == "camp_site":
        found.add("camp")
    if tourism == "caravan_site":
        found.add("rv")
    if highway in ("rest_area", "services"):
        found.add("rest")
    found.update({
        "sanitary_dump_station": {"dump"}, "drinking_water": {"water"}, "toilets": {"toilet"},
        "shower": {"shower"}, "fuel": {"fuel"}, "library": {"library"}, "pharmacy": {"pharmacy"},
        "hospital": {"hospital"}, "clinic": {"clinic"},
    }.get(amenity, set()))
    if amenity == "doctors" and value(tags, "healthcare") == "clinic":
        found.add("clinic")
    if amenity == "fuel" or highway == "services":
        if value(tags, "hgv") == "yes" or chains.match(tags)[0] is not None:
            found.add("truck")
    if shop == "laundry":
        found.add("laundry")
    if shop == "supermarket":
        found.add("grocery")
    if (boundary == "national_park" or (boundary == "protected_area" and protect_class_in_range(tags))
            or (leisure == "park" and is_state_park(tags))):
        found.add("park")
    return [c for c in CATEGORIES if c in found]


def extra_of(category, tags):
    """The category's fields that the object has, as a dict; a missing field is left out, never false."""
    extra = {}
    for field in FIELDS[category]:
        if field == "diesel":
            if value(tags, "fuel:diesel") == "yes":
                extra["diesel"] = True
            continue
        v = text(tags, field)
        if v is not None:
            extra[field] = v
    return extra


Verdict = collections.namedtuple("Verdict", "category name brand extra")


def classify(tags, chains):
    """The Verdicts for an object's tags (a dict): one per category it belongs to, [] when it is not a spot."""
    name, brand = text(tags, "name"), text(tags, "brand")
    return [Verdict(c, name, brand, extra_of(c, tags)) for c in categories_of(tags, chains)]


def extra_json(extra):
    return json.dumps(extra, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


# ---- Clusters ------------------------------------------------------------------------------------------


def tile_of(lat, lon, zoom):
    """The Web Mercator (slippy map) tile x, y of a point at a zoom."""
    n = 1 << zoom
    lat = max(min(lat, MAX_LATITUDE), -MAX_LATITUDE)
    x = math.floor((lon + 180.0) / 360.0 * n)
    y = math.floor((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


class Clusters:
    """Per zoom, tile and category: how many spots and their mean position."""

    def __init__(self, zooms=CLUSTER_ZOOMS):
        self.zooms = list(zooms)
        self.cells = {}  # (zoom, x, y, category) -> [count, lat sum, lon sum]

    def add(self, lat, lon, category):
        for zoom in self.zooms:
            x, y = tile_of(lat, lon, zoom)
            cell = self.cells.get((zoom, x, y, category))
            if cell is None:
                self.cells[(zoom, x, y, category)] = [1, lat, lon]
            else:
                cell[0] += 1
                cell[1] += lat
                cell[2] += lon

    def rows(self):
        return [
            (zoom, x, y, category, count, round(lat_sum / count, 6), round(lon_sum / count, 6))
            for (zoom, x, y, category), (count, lat_sum, lon_sum) in sorted(self.cells.items())
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
    """Yields (osm_type, osm_id, tags, lat, lon) for every tagged node, way and area relation (multipolygon or
    boundary) in the file: a node at itself, a way at its middle node (a closed way at the mean of its corners),
    a relation at the mean of its first outer ring's corners. Objects with no usable position are skipped."""
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


def build(objects, output, chains, sources, max_mb=None, built_at=None, log=None):
    """Writes the pack from `objects` (read_objects' tuples); returns the row counts per category.

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
    counts = collections.Counter()
    nameless = collections.Counter()
    truck_by = collections.defaultdict(collections.Counter)  # chain name -> how the truck stops matched
    truck_names = collections.defaultdict(collections.Counter)
    rows = []
    seen = skipped = 0

    for osm_type, osm_id, tags, lat, lon in objects:
        seen += 1
        verdicts = classify(tags, chains)
        if not verdicts:
            skipped += 1
            continue
        lat, lon = round(lat, 6), round(lon, 6)
        for verdict in verdicts:
            rows.append((lat, lon, verdict.category, verdict.name, verdict.brand, extra_json(verdict.extra)))
            clusters.add(lat, lon, verdict.category)
            counts[verdict.category] += 1
            if verdict.name is None:
                nameless[verdict.category] += 1
            if verdict.category == "truck":
                chain, by_qid, by_name = chains.match(tags)
                how = "hgv" if chain is None else ("both" if by_qid and by_name else "qid" if by_qid else "name")
                truck_by[chain["name"] if chain else "(hgv=yes only)"][how] += 1
                if chain:
                    truck_names[chain["name"]][verdict.brand or verdict.name or "(no name)"] += 1
            if len(rows) >= 50_000:
                flush(db, rows)
    flush(db, rows)

    cluster_rows = clusters.rows()
    db.executemany("INSERT INTO cluster VALUES (?,?,?,?,?,?,?)", cluster_rows)
    rtree = True
    try:
        db.execute(RTREE)
    except sqlite3.OperationalError as e:
        rtree = False
        print(f"::warning::This SQLite has no R-tree module ({e}); the pack is written without road_rtree",
              file=sys.stderr)
    if rtree:
        # Outside the try: a failed insert must fail the build, never leave an empty or partial R-tree.
        db.execute("INSERT INTO road_rtree SELECT id, lat, lat, lon, lon FROM spot")
    newest = max(dates.values())
    db.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("extract_date", newest),
            ("extract_dates", json.dumps(dates, sort_keys=True)),
            ("built_at", built_at),
            ("chains_sha256", chains.sha256),
            ("schema", str(SCHEMA_VERSION)),
            ("sources", json.dumps([s["name"] for s in sources])),
            ("categories", json.dumps(list(CATEGORIES))),
            ("attribution", ATTRIBUTION),
        ],
    )
    db.commit()
    db.executescript(INDEXES)
    db.commit()
    db.execute("VACUUM")
    db.close()

    size_mb = os.path.getsize(tmp) / 1e6
    log("== Road pack summary ==")
    log(f"{seen} objects read, {sum(counts.values())} spot rows kept, {skipped} objects not spots"
        f" ({'with' if rtree else 'WITHOUT'} R-tree), in {time.time() - started:.0f}s")
    for category in CATEGORIES:
        log(f"category {category}: {counts[category]} ({nameless[category]} without a name)")
    log(f"{len(cluster_rows)} cluster rows for zooms {CLUSTER_ZOOMS[0]} to {CLUSTER_ZOOMS[-1]}")
    log("truck stops (how each matched: brand name, QID, both, or hgv=yes alone):")
    for chain in chains.chains:
        by = truck_by[chain["name"]]
        names = ", ".join(f"{n} ({c})" for n, c in truck_names[chain["name"]].most_common(3)) or "none"
        flag = "" if sum(by.values()) else "  <-- NONE SEEN: wrong brand or QID, or a build without the whole country?"
        log(f"  {chain['name']}: {sum(by.values())} (name {by['name']}, qid {by['qid']}, both {by['both']}); {names}{flag}")
    log(f"  hgv=yes alone: {truck_by['(hgv=yes only)']['hgv']}")
    log("extracts: " + ", ".join(f"{name} {date}" for name, date in sorted(dates.items())))
    log(f"extract_date {newest}, built_at {built_at}")
    log(f"{os.path.basename(output)}: {size_mb:.1f} MB")
    if max_mb is not None and size_mb > max_mb:
        os.remove(tmp)
        raise SystemExit(f"{output} would be {size_mb:.0f} MB, over the {max_mb:g} MB limit (--max-mb, ROAD_MAX_MB)")
    os.replace(tmp, output)
    return dict(counts)


def flush(db, rows):
    if rows:
        db.executemany(
            "INSERT INTO spot (lat, lon, category, name, brand, extra) VALUES (?,?,?,?,?,?)",
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
    parser.add_argument("--check-chains", action="store_true",
                        help="validate chains.json and exit (build.sh runs this before it downloads anything)")
    args = parser.parse_args()
    try:
        chains = load_chains(args.chains)
    except ChainsError as e:
        raise SystemExit(f"ERROR: {e}") from None
    if args.check_chains:
        print(f"{args.chains}: {len(chains.chains)} truck stop chains, ok")
        return
    if not (args.pbf and args.output and args.sources):
        parser.error("pbf, output and --sources are required")
    build(read_objects(args.pbf), args.output, chains, parse_sources(args.sources), max_mb=args.max_mb)


if __name__ == "__main__":
    main()
