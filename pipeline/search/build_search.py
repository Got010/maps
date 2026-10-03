#!/usr/bin/env python3
"""Build the search pack, search-<region>.sqlite, from an OSM extract.

One table, `feature`, holds everything the search box can find, plus an
FTS5 trigram index over a normalised copy of each row's text:

  kind 0  locality  boroughs, neighbourhoods, towns    (place=*)
  kind 1  place     shops, restaurants, parks, stations (amenity, shop, ...)
  kind 2  street    named roads, one row per name per ~2 km cell, with
                    the town they're in ("Main Street", "Nyack, NY")
  kind 3  address   unused since schema 2; addresses have their own tables

Addresses (schema 2) live outside the big trigram index, because there are
millions of them and their text is mostly repeats ("123", "Main St"):

  address_street  one row per street name per town ("Main Street", "Nyack",
                  "NY", the mean of its addresses' positions) with its own
                  small trigram index, address_street_fts, over
                  "main st nyack orangetown ny"
  address         the house numbers on those streets, keyed (street_id,
                  number_key), so the app finds "123 Main St" or the nearest
                  number to it with a B-tree lookup; lat/lon are stored as
                  integer microdegrees, half the size of REALs

They come from OSM addr:* tags and, with --address-points, the NYS Address
Points file (see addresses.py). A state point within 25 m of an OSM one with
the same number and street is the same house and is dropped. A town missing
from the tags is the nearest town or neighbourhood (LocalityFinder). The app
uses these tables when a pack has them and otherwise falls back to the
schema 1 behaviour, where addresses were kind 3 rows in `feature`.

A second table, `landmark`, holds points that guidance talks about rather
than things to search for: traffic signals and stop signs, prominent roadside
places ("past the gas station"), and what the driver is warned about (speed
and licence-plate cameras, school zones). The app reads it with small
bounding-box queries along the route (see core/search Landmarks). Packs built
before it existed simply lack the table, and the app then skips landmarks.

The app reads it with SQLite's FTS5 (see core/search). PRAGMA
application_id marks the file as a search pack so an import can recognise
it whatever its name.

Usage: build_search.py <input.osm.pbf> <output.sqlite> <region>
         [--state NY] [--address-points points.csv] [--bounds w,s,e,n] [--max-mb N]
"""

import argparse
import math
import os
import sqlite3
import sys
import time

import osmium

from normalize import house_number_key, normalize
from states import state_at

APPLICATION_ID = 0x53425331  # "SBS1", matches PackKind.SEARCH_APPLICATION_ID
# 2: address_street, address_street_fts and address tables, and streets carry
#    their town. The app detects each table, so older packs still open.
SCHEMA_VERSION = 2

LOCALITY, PLACE, STREET, ADDRESS = 0, 1, 2, 3

# Where an address came from. Of two copies of one house, the lower source wins.
SOURCE_OSM, SOURCE_STATE = 0, 1
DUPLICATE_METERS = 25.0
STATE_NAMES = {"new york": "NY", "new jersey": "NJ", "connecticut": "CT", "pennsylvania": "PA"}

LOCALITY_IMPORTANCE = {
    "city": 100,
    "borough": 90,
    "suburb": 80,
    "town": 70,
    "quarter": 60,
    "neighbourhood": 60,
    "village": 60,
    "island": 55,
    "hamlet": 50,
}

# Tag keys that make a named object a place, and values to skip.
PLACE_KEYS = ["amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "historic"]
SKIP_VALUES = {"yes", "no", "parking_space", "bench", "waste_basket", "vending_machine", "bicycle_parking"}
STATION_TAGS = {
    ("railway", "station"),
    ("railway", "halt"),
    ("public_transport", "station"),
    ("aeroway", "aerodrome"),
    ("aeroway", "terminal"),
    ("natural", "beach"),
    ("natural", "peak"),
}

STREET_HIGHWAYS = {
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street", "pedestrian",
}
MAJOR_HIGHWAYS = {"motorway", "trunk", "primary"}

# Roadside places that make good landmarks in spoken directions: easy to see
# from the car and common enough to matter. Category is what the app speaks.
LANDMARK_POIS = {
    ("amenity", "fuel"): "fuel",
    ("amenity", "fast_food"): "fast_food",
    ("amenity", "bank"): "bank",
    ("amenity", "pharmacy"): "pharmacy",
    ("shop", "chemist"): "pharmacy",
    ("shop", "supermarket"): "supermarket",
    ("amenity", "school"): "school",
}
# Signals and signs: position only, no name.
LANDMARK_SIGNS = {"traffic_signals": "traffic_signals", "stop": "stop"}
SCHOOL_SIGNS = ("S5-1", "S1-1", "school")
STREET_CELL_DEGREES = 0.02  # about 2 km; one search row per street name per cell

# The town an untagged address or a street is in: the nearest locality, with
# bigger places reaching farther. A place counts within LOCALITY_RADIUS_KM
# times its scale, and the smallest distance / scale wins, so a Buffalo
# street 5 km from downtown is in Buffalo rather than in the suburb whose
# centre is 3 km away, and a neighbourhood wins only close to its centre.
LOCALITY_SCALE = {
    "city": 3.0,
    "borough": 3.0,
    "town": 1.5,
    "village": 1.0,
    "hamlet": 0.8,
    "suburb": 0.4,
    "quarter": 0.3,
    "neighbourhood": 0.3,
}
LOCALITY_RADIUS_KM = 5.0
KM_PER_DEGREE = 111.2

SCHEMA = f"""
PRAGMA application_id = {APPLICATION_ID};
PRAGMA user_version = {SCHEMA_VERSION};
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE feature (
  id INTEGER PRIMARY KEY,
  kind INTEGER NOT NULL,
  name TEXT NOT NULL,
  category TEXT,
  address TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  importance INTEGER NOT NULL,
  name_key TEXT NOT NULL,
  search_text TEXT NOT NULL,
  opening_hours TEXT,
  phone TEXT,
  website TEXT,
  cuisine TEXT,
  osm_ref TEXT
);
CREATE TABLE landmark (
  id INTEGER PRIMARY KEY,
  category TEXT NOT NULL,
  name TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL
);
CREATE TABLE address_street (
  id INTEGER PRIMARY KEY,
  street TEXT NOT NULL,
  city TEXT,
  state TEXT NOT NULL,
  street_key TEXT NOT NULL,
  search_text TEXT NOT NULL,
  lat_e6 INTEGER NOT NULL,
  lon_e6 INTEGER NOT NULL
);
CREATE TABLE address (
  street_id INTEGER NOT NULL,
  number_key INTEGER NOT NULL,
  id INTEGER NOT NULL,
  number TEXT NOT NULL,
  postcode TEXT,
  lat_e6 INTEGER NOT NULL,
  lon_e6 INTEGER NOT NULL,
  PRIMARY KEY (street_id, number_key, id)
) WITHOUT ROWID;
-- Every address from every source before duplicates merge; dropped after.
CREATE TABLE address_raw (
  number TEXT NOT NULL,
  number_key INTEGER NOT NULL,
  street TEXT NOT NULL,
  street_key TEXT NOT NULL,
  city TEXT,
  alt_city TEXT,
  postcode TEXT,
  state TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  source INTEGER NOT NULL
);
"""

# Built after the bulk insert, which is much faster than indexing as we go.
INDEXES = """
CREATE INDEX feature_name_key ON feature(name_key);
CREATE INDEX landmark_lat_lon ON landmark(lat, lon);
CREATE VIRTUAL TABLE feature_fts USING fts5(
  search_text, content='', tokenize='trigram', detail='none'
);
INSERT INTO feature_fts(rowid, search_text) SELECT id, search_text FROM feature;
INSERT INTO feature_fts(feature_fts) VALUES ('optimize');
CREATE INDEX address_street_key ON address_street(street_key);
CREATE VIRTUAL TABLE address_street_fts USING fts5(
  search_text, content='', tokenize='trigram', detail='none'
);
INSERT INTO address_street_fts(rowid, search_text) SELECT id, search_text FROM address_street;
INSERT INTO address_street_fts(address_street_fts) VALUES ('optimize');
"""


def place_category(tags):
    """The OSM value that makes this a searchable place, e.g. "cafe", or None."""
    for key in PLACE_KEYS:
        value = tags.get(key)
        if value and value not in SKIP_VALUES:
            return value
    for key, value in STATION_TAGS:
        if tags.get(key) == value:
            return value if key != "public_transport" else "station"
    if tags.get("building") and tags.get("name"):
        return "building"
    return None


def place_importance(tags, category):
    score = 30
    if "wikidata" in tags or "wikipedia" in tags:
        score += 20
    if category in ("station", "aerodrome", "terminal"):
        score += 15
    if category in ("attraction", "museum", "park", "stadium", "university", "hospital"):
        score += 10
    return score


def landmark_category(tags):
    """What guidance would call this object, e.g. "traffic_signals", or None.

    Alerts come first: a camera mapped on a signal pole is worth a warning
    more than a landmark.
    """
    if tags.get("highway") == "speed_camera" or tags.get("enforcement") == "maxspeed":
        return "speed_camera"
    if is_alpr(tags):
        return "alpr_camera"
    if is_school_zone(tags):
        return "school_zone"
    sign = LANDMARK_SIGNS.get(tags.get("highway"))
    if sign:
        return sign
    for (key, value), category in LANDMARK_POIS.items():
        if tags.get(key) == value:
            return category
    if tags.get("amenity") == "place_of_worship" and tags.get("religion") == "christian":
        return "church"
    return None


def is_alpr(tags):
    """Licence-plate readers as DeFlock maps them (Flock Safety and others)."""
    if tags.get("man_made") != "surveillance" and tags.get("surveillance") is None:
        return False
    kind = (tags.get("surveillance:type") or "").upper()
    camera = (tags.get("camera:type") or "").upper()
    operator = " ".join(tags.get(k, "") for k in ("operator", "manufacturer", "brand")).lower()
    return kind == "ALPR" or camera == "ALPR" or "flock" in operator


def is_school_zone(tags):
    """A mapped school zone: hazard=school_zone, or a school warning or speed sign."""
    if tags.get("hazard") == "school_zone":
        return True
    sign = tags.get("traffic_sign") or ""
    if sign and any(s.lower() in sign.lower() for s in SCHOOL_SIGNS):
        return True
    return "school" in (tags.get("maxspeed:conditional") or "").lower()


def landmark_name(tags, category):
    """Signals, signs and alerts are spoken by category; places by name or brand."""
    if category in ("traffic_signals", "stop", "speed_camera", "alpr_camera", "school_zone"):
        return None
    return tags.get("brand") or tags.get("name")


def format_address(tags, with_street=True):
    """'123 Main Street, Brooklyn 11201' from addr:* tags, or None."""
    street = tags.get("addr:street") or tags.get("addr:place")
    number = tags.get("addr:housenumber")
    first = " ".join(p for p in (number, street) if p) if with_street else None
    city = tags.get("addr:city") or tags.get("addr:borough")
    locality = " ".join(p for p in (city, tags.get("addr:postcode")) if p)
    parts = [p for p in (first, locality) if p]
    return ", ".join(parts) or None


def search_text(*parts):
    return " " + normalize(" ".join(p for p in parts if p)) + " "


def display_address(number, street, city, state, postcode):
    """'123 Main Street, Nyack, NY 10960'. The app builds the same line from
    the address tables (AddressFormat.display in core/search)."""
    line = f"{number} {street}"
    if city:
        line += f", {city}"
    region = " ".join(p for p in (state, postcode) if p)
    if region:
        line += f", {region}"
    return line


def clean_number(number):
    """'123a' -> '123A', '12 - 34' -> '12-34', '123;125' -> '123'."""
    number = (number or "").split(";")[0].strip()
    return " ".join("-".join(p.strip() for p in number.split("-")).split()).upper()


def state_code(value):
    """'NY' from 'NY', 'ny' or 'New York'; None otherwise."""
    value = (value or "").strip()
    if len(value) == 2 and value.isalpha():
        return value.upper()
    return STATE_NAMES.get(value.lower())


class LocalityFinder:
    """The town an address or a street is in; see LOCALITY_SCALE."""

    CELL = 0.1  # degrees; nearest() looks two cells each way, at least 15 km
    CACHE_CELL = 0.005  # one answer per ~500 m square

    def __init__(self, localities):
        self.grid = {}
        for category, name, lat, lon in localities:
            scale = LOCALITY_SCALE.get(category)
            if scale is None:
                continue
            key = (math.floor(lat / self.CELL), math.floor(lon / self.CELL))
            self.grid.setdefault(key, []).append((scale, name, lat, lon))
        self.cache = {}

    def nearest(self, lat, lon):
        """The locality's name, or None when none is close enough."""
        key = (round(lat / self.CACHE_CELL), round(lon / self.CACHE_CELL))
        if key not in self.cache:
            self.cache[key] = self._nearest(key[0] * self.CACHE_CELL, key[1] * self.CACHE_CELL)
        return self.cache[key]

    def _nearest(self, lat, lon):
        cos_lat = math.cos(math.radians(lat))
        cy, cx = math.floor(lat / self.CELL), math.floor(lon / self.CELL)
        best, best_score = None, LOCALITY_RADIUS_KM
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                for scale, name, plat, plon in self.grid.get((cy + dy, cx + dx), ()):
                    km = math.hypot((plat - lat) * KM_PER_DEGREE, (plon - lon) * KM_PER_DEGREE * cos_lat)
                    if km / scale <= best_score:
                        best, best_score = name, km / scale
        return best


def meters_apart(lat1, lon1, lat2, lon2):
    """Flat-earth distance; fine within a block."""
    dy = (lat2 - lat1) * KM_PER_DEGREE * 1000
    dx = (lon2 - lon1) * KM_PER_DEGREE * 1000 * math.cos(math.radians(lat1))
    return math.hypot(dx, dy)


class Collector:
    """Turns OSM objects into feature rows and writes them in batches."""

    def __init__(self, db):
        self.db = db
        self.rows = []
        self.streets = {}  # (name_key, cell) -> row
        self.counts = {LOCALITY: 0, PLACE: 0, STREET: 0, ADDRESS: 0}
        self.landmarks = []
        self.landmark_count = 0
        self.localities = []  # (category, name, lat, lon), for LocalityFinder
        self.address_rows = []
        # Address points staged per source, and those with no usable number or street.
        self.address_counts = {SOURCE_OSM: 0, SOURCE_STATE: 0}
        self.address_skipped = 0

    def add(self, kind, name, lat, lon, importance, osm_ref, tags, category=None, address=None):
        cuisine = tags.get("cuisine")
        row = (
            kind,
            name,
            category,
            address,
            round(lat, 6),
            round(lon, 6),
            importance,
            normalize(name),
            search_text(name, address, category.replace("_", " ") if category else None,
                        cuisine.replace(";", " ").replace("_", " ") if cuisine else None),
            tags.get("opening_hours"),
            tags.get("phone") or tags.get("contact:phone"),
            tags.get("website") or tags.get("contact:website"),
            cuisine,
            osm_ref,
        )
        self.rows.append(row)
        self.counts[kind] += 1
        if kind == LOCALITY:
            self.localities.append((category, name, lat, lon))
        if len(self.rows) >= 50_000:
            self.flush()

    def address_point(self, number, street, lat, lon, source,
                      city=None, postcode=None, state=None, alt_city=None):
        """Stages one address for finish_addresses. False if it can't be searched."""
        number = clean_number(number)
        street = (street or "").strip()
        key = house_number_key(number)
        street_key = normalize(street)
        if key is None or not street_key:
            self.address_skipped += 1
            return False
        self.address_rows.append((
            number, key, street, street_key,
            (city or "").strip() or None, (alt_city or "").strip() or None,
            (postcode or "").strip() or None, state_code(state),
            round(lat, 6), round(lon, 6), source,
        ))
        self.address_counts[source] += 1
        if len(self.address_rows) >= 50_000:
            self.flush()
        return True

    def osm_address(self, tags, lat, lon):
        street = tags.get("addr:street") or tags.get("addr:place")
        number = tags.get("addr:housenumber")
        if number and street:
            self.address_point(
                number, street, lat, lon, SOURCE_OSM,
                city=tags.get("addr:city") or tags.get("addr:borough"),
                postcode=tags.get("addr:postcode"), state=tags.get("addr:state"),
            )

    def flush(self):
        self.db.executemany(
            "INSERT INTO feature (kind, name, category, address, lat, lon, importance, name_key,"
            " search_text, opening_hours, phone, website, cuisine, osm_ref)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            self.rows,
        )
        self.rows.clear()
        if self.address_rows:
            self.db.executemany(
                "INSERT INTO address_raw (number, number_key, street, street_key, city, alt_city,"
                " postcode, state, lat, lon, source) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                self.address_rows,
            )
            self.address_rows.clear()
        if self.landmarks:
            self.db.executemany(
                "INSERT INTO landmark (category, name, lat, lon) VALUES (?,?,?,?)", self.landmarks
            )
            self.landmarks.clear()

    def landmark(self, tags, lat, lon, nodes_only=False):
        """Adds a landmark row if the object is one. Signals and signs count only as nodes."""
        category = landmark_category(tags)
        if category is None:
            return
        if nodes_only and category in ("traffic_signals", "stop"):
            return
        self.landmarks.append((category, landmark_name(tags, category), round(lat, 6), round(lon, 6)))
        self.landmark_count += 1
        if len(self.landmarks) >= 50_000:
            self.flush()

    def point(self, tags, lat, lon, osm_ref):
        """A node or an area's centre: locality, place or address.

        A place with an address also gives an address row, so its number and
        street find it without the name.
        """
        name = tags.get("name")
        place = tags.get("place")
        if name and place in LOCALITY_IMPORTANCE:
            self.add(LOCALITY, name, lat, lon, LOCALITY_IMPORTANCE[place], osm_ref, tags, category=place)
            return
        category = place_category(tags) if name else None
        if category:
            self.add(PLACE, name, lat, lon, place_importance(tags, category), osm_ref, tags,
                     category=category, address=format_address(tags))
        self.osm_address(tags, lat, lon)

    def street(self, tags, lat, lon, osm_ref):
        name = tags.get("name")
        highway = tags.get("highway")
        if not name or highway not in STREET_HIGHWAYS:
            return
        key = (normalize(name), round(lat / STREET_CELL_DEGREES), round(lon / STREET_CELL_DEGREES))
        if key in self.streets:
            return
        importance = 45 if highway in MAJOR_HIGHWAYS else 35
        self.streets[key] = True
        self.add(STREET, name, lat, lon, importance, osm_ref, tags, category="street")


def state_finder(override=None):
    """(lat, lon) -> two-letter state: override if given, else states.state_at
    (NJ inside the northern New Jersey area, NY elsewhere), cached per ~500 m."""
    if override:
        return lambda lat, lon: override
    cache = {}

    def find(lat, lon):
        key = (round(lat / LocalityFinder.CACHE_CELL), round(lon / LocalityFinder.CACHE_CELL))
        if key not in cache:
            cache[key] = state_at(lat, lon)
        return cache[key]

    return find


def street_towns(db, finder, state_of):
    """Gives each street row its town, "Nyack, NY", also in its search text."""
    rows = db.execute("SELECT id, name, lat, lon FROM feature WHERE kind = ?", (STREET,)).fetchall()
    updates = []
    for row_id, name, lat, lon in rows:
        city = finder.nearest(lat, lon)
        state = state_of(lat, lon)
        address = ", ".join(p for p in (city, state) if p)
        updates.append((address, search_text(name, city, state, "street"), row_id))
    db.executemany("UPDATE feature SET address = ?, search_text = ? WHERE id = ?", updates)


def finish_addresses(db, finder, state_of):
    """Merges duplicates in address_raw into address_street and address.

    Rows with the same street and number are one house when within
    DUPLICATE_METERS: the first source (OSM) keeps its position and takes
    the other's town and ZIP if it lacked them. An address without a town
    gets the nearest locality, and one without a state state_of's. Returns (addresses, streets, duplicates).
    """
    # (street_key, city_key, state) -> [id, street, city, source, other town names, sum lat, sum lon, count]
    streets = {}
    batch = []
    written = 0
    duplicates = 0

    def flush():
        db.executemany(
            "INSERT INTO address (street_id, number_key, id, number, postcode, lat_e6, lon_e6)"
            " VALUES (?,?,?,?,?,?,?)",
            batch,
        )
        batch.clear()

    def write(kept):
        nonlocal written
        for number, key, street, street_key, city, alt_city, postcode, state, lat, lon, source in kept:
            city = city or finder.nearest(lat, lon)
            state = state or state_of(lat, lon)
            group = (street_key, normalize(city or ""), state)
            entry = streets.get(group)
            if entry is None:
                entry = streets[group] = [len(streets) + 1, street, city, source, set(), 0.0, 0.0, 0]
            elif source < entry[3]:
                entry[1], entry[3] = street, source  # OSM's spelling over the state file's capitals
            if alt_city and len(entry[4]) < 3:
                entry[4].add(alt_city)
            entry[5] += lat
            entry[6] += lon
            entry[7] += 1
            written += 1
            batch.append((entry[0], key, written, number, postcode, round(lat * 1e6), round(lon * 1e6)))
        if len(batch) >= 50_000:
            flush()

    group, kept = None, []
    rows = db.execute(
        "SELECT number, number_key, street, street_key, city, alt_city, postcode, state, lat, lon, source"
        " FROM address_raw ORDER BY street_key, number, source"
    )
    for row in rows:
        if (row[3], row[0]) != group:
            write(kept)
            group, kept = (row[3], row[0]), []
        same = next((i for i, k in enumerate(kept) if meters_apart(k[8], k[9], row[8], row[9]) <= DUPLICATE_METERS), None)
        if same is None:
            kept.append(row)
            continue
        duplicates += 1
        k = kept[same]
        kept[same] = k[:4] + tuple(a or b for a, b in zip(k[4:8], row[4:8])) + k[8:]
    write(kept)
    flush()

    db.executemany(
        "INSERT INTO address_street (id, street, city, state, street_key, search_text, lat_e6, lon_e6)"
        " VALUES (?,?,?,?,?,?,?,?)",
        [
            (sid, street, city, state, key, search_text(street, city, *sorted(alts - {city}), state),
             round(lat / n * 1e6), round(lon / n * 1e6))
            for (key, _, state), (sid, street, city, _, alts, lat, lon, n) in streets.items()
        ],
    )
    db.execute("DROP TABLE address_raw")
    return written, len(streets), duplicates


def table_sizes(db):
    """Bytes per table and index, largest first; {} if SQLite lacks dbstat."""
    try:
        return dict(db.execute("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name ORDER BY 2 DESC"))
    except sqlite3.Error:
        return {}


def ring_centre(area):
    """Mean of the first outer ring's vertices: close enough for a search pin."""
    for ring in area.outer_rings():
        points = [(n.lat, n.lon) for n in ring if n.location.valid()]
        if points:
            return sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points)
    return None


def way_middle(way):
    nodes = [n for n in way.nodes if n.location.valid()]
    if not nodes:
        return None
    middle = nodes[len(nodes) // 2]
    return middle.lat, middle.lon


def way_start(way):
    for n in way.nodes:
        if n.location.valid():
            return n.lat, n.lon
    return None


def build(pbf, output, region, state=None, address_points=None, bounds=None, max_mb=None):
    """Builds the pack; returns the row counts per kind (ADDRESS counts the
    address table). Addresses and streets without addr:state are in `state`
    if given, else wherever states.state_at puts them. Fails if the file ends
    up larger than max_mb."""
    if os.path.exists(output):
        os.remove(output)
    tmp = output + ".part"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript("PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;" + SCHEMA)
    collector = Collector(db)
    started = time.time()

    processor = (
        osmium.FileProcessor(pbf)
        .with_locations()
        .with_areas()
        .with_filter(osmium.filter.EmptyTagFilter())
    )
    for obj in processor:
        tags = obj.tags
        if obj.is_node():
            collector.point(tags, obj.location.lat, obj.location.lon, f"n{obj.id}")
            collector.landmark(tags, obj.location.lat, obj.location.lon)
        elif obj.is_way():
            if "highway" in tags:
                middle = way_middle(obj)
                if middle:
                    collector.street(tags, *middle, f"w{obj.id}")
                # A road with a school speed limit: warn where the zone starts.
                if is_school_zone(tags):
                    start = way_start(obj)
                    if start:
                        collector.landmark(tags, *start)
        elif obj.is_area():
            # Roads come through as ways; an area tagged highway is a plaza.
            if "highway" in tags and place_category(tags) is None:
                continue
            centre = ring_centre(obj)
            if centre:
                prefix = "w" if obj.from_way() else "r"
                collector.point(tags, *centre, f"{prefix}{obj.orig_id()}")
                collector.landmark(tags, *centre, nodes_only=True)
    attribution = "© OpenStreetMap contributors, ODbL"
    if address_points:
        import addresses  # only needed with a state file

        addresses.load(collector, address_points, SOURCE_STATE, bounds=bounds)
        attribution += "; NYS Address Points, NYS GIS Program Office, CC0"
    collector.flush()

    finder = LocalityFinder(collector.localities)
    state_of = state_finder(state)
    street_towns(db, finder, state_of)
    written, address_streets, duplicates = finish_addresses(db, finder, state_of)
    collector.counts[ADDRESS] = written

    db.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("region", region),
            ("state", state or "by location"),
            ("source", os.path.basename(pbf)),
            ("address_points", os.path.basename(address_points) if address_points else ""),
            ("built_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
            ("attribution", attribution),
        ],
    )
    db.commit()
    db.executescript(INDEXES)
    db.commit()
    db.execute("VACUUM")
    sizes = table_sizes(db)
    db.close()
    os.replace(tmp, output)

    counts = collector.counts
    log = lambda line: print(line, file=sys.stderr)  # noqa: E731
    log(
        f"    {counts[LOCALITY]} localities, {counts[PLACE]} places, {counts[STREET]} streets, "
        f"{collector.landmark_count} landmarks in {time.time() - started:.0f}s"
    )
    log(
        f"    addresses: {collector.address_counts[SOURCE_OSM]} from OSM, "
        f"{collector.address_counts[SOURCE_STATE]} from the state file, "
        f"{collector.address_skipped} without a number or street skipped, {duplicates} duplicates merged; "
        f"{written} addresses on {address_streets} streets"
    )
    size_mb = os.path.getsize(output) / 1e6
    log(f"    {os.path.basename(output)}: {size_mb:.1f} MB")
    for name, size in list(sizes.items())[:12]:
        log(f"      {name:<28} {size / 1e6:8.1f} MB")
    if max_mb is not None and size_mb > max_mb:
        raise SystemExit(f"{output} is {size_mb:.0f} MB, over the {max_mb} MB limit (--max-mb)")
    return counts


def parse_bounds(text):
    """'minlon,minlat,maxlon,maxlat' -> a 4-tuple of floats."""
    parts = [float(p) for p in text.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("bounds are minlon,minlat,maxlon,maxlat")
    return tuple(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pbf")
    parser.add_argument("output")
    parser.add_argument("region")
    parser.add_argument("--state",
                        help="two-letter state for every address that doesn't say"
                             " (default: by location, see states.py)")
    parser.add_argument("--address-points", help="NYS Address Points as CSV or GeoJSON (see addresses.py)")
    parser.add_argument("--bounds", type=parse_bounds,
                        help="minlon,minlat,maxlon,maxlat: keep only address points inside")
    parser.add_argument("--max-mb", type=float, help="fail if the pack is larger than this")
    args = parser.parse_args()
    build(args.pbf, args.output, args.region, state=args.state.upper() if args.state else None,
          address_points=args.address_points, bounds=args.bounds, max_mb=args.max_mb)


if __name__ == "__main__":
    main()
