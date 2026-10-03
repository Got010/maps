#!/usr/bin/env python3
"""Build the search pack, search-<region>.sqlite, from an OSM extract.

One table, `feature`, holds everything the search box can find, plus an
FTS5 trigram index over a normalised copy of each row's text:

  kind 0  locality  boroughs, neighbourhoods, towns    (place=*)
  kind 1  place     shops, restaurants, parks, stations (amenity, shop, ...)
  kind 2  street    named roads, one row per name per ~2 km cell
  kind 3  address   house number + street

The app reads it with SQLite's FTS5 (see core/search). PRAGMA
application_id marks the file as a search pack so an import can recognise
it whatever its name.

Usage: build_search.py <input.osm.pbf> <output.sqlite> <region>
"""

import argparse
import os
import sqlite3
import sys
import time

import osmium

from normalize import normalize

APPLICATION_ID = 0x53425331  # "SBS1", matches PackKind.SEARCH_APPLICATION_ID
SCHEMA_VERSION = 1

LOCALITY, PLACE, STREET, ADDRESS = 0, 1, 2, 3

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
STREET_CELL_DEGREES = 0.02  # about 2 km; one search row per street name per cell

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
"""

# Built after the bulk insert, which is much faster than indexing as we go.
INDEXES = """
CREATE INDEX feature_name_key ON feature(name_key);
CREATE VIRTUAL TABLE feature_fts USING fts5(
  search_text, content='', tokenize='trigram', detail='none'
);
INSERT INTO feature_fts(rowid, search_text) SELECT id, search_text FROM feature;
INSERT INTO feature_fts(feature_fts) VALUES ('optimize');
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


class Collector:
    """Turns OSM objects into feature rows and writes them in batches."""

    def __init__(self, db):
        self.db = db
        self.rows = []
        self.streets = {}  # (name_key, cell) -> row
        self.counts = {LOCALITY: 0, PLACE: 0, STREET: 0, ADDRESS: 0}

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
        if len(self.rows) >= 50_000:
            self.flush()

    def flush(self):
        self.db.executemany(
            "INSERT INTO feature (kind, name, category, address, lat, lon, importance, name_key,"
            " search_text, opening_hours, phone, website, cuisine, osm_ref)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            self.rows,
        )
        self.rows.clear()

    def point(self, tags, lat, lon, osm_ref):
        """A node or an area's centre: locality, place or address."""
        name = tags.get("name")
        place = tags.get("place")
        if name and place in LOCALITY_IMPORTANCE:
            self.add(LOCALITY, name, lat, lon, LOCALITY_IMPORTANCE[place], osm_ref, tags, category=place)
            return
        category = place_category(tags) if name else None
        if category:
            self.add(PLACE, name, lat, lon, place_importance(tags, category), osm_ref, tags,
                     category=category, address=format_address(tags))
            return
        if tags.get("addr:housenumber") and (tags.get("addr:street") or tags.get("addr:place")):
            street = tags.get("addr:street") or tags.get("addr:place")
            label = f"{tags['addr:housenumber']} {street}"
            self.add(ADDRESS, label, lat, lon, 10, osm_ref, tags,
                     address=format_address(tags, with_street=False))

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


def build(pbf, output, region):
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
        elif obj.is_way():
            if "highway" in tags:
                middle = way_middle(obj)
                if middle:
                    collector.street(tags, *middle, f"w{obj.id}")
        elif obj.is_area():
            # Roads come through as ways; an area tagged highway is a plaza.
            if "highway" in tags and place_category(tags) is None:
                continue
            centre = ring_centre(obj)
            if centre:
                prefix = "w" if obj.from_way() else "r"
                collector.point(tags, *centre, f"{prefix}{obj.orig_id()}")
    collector.flush()

    db.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("region", region),
            ("source", os.path.basename(pbf)),
            ("built_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
            ("attribution", "© OpenStreetMap contributors, ODbL"),
        ],
    )
    db.commit()
    db.executescript(INDEXES)
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, output)

    counts = collector.counts
    print(
        f"    {counts[LOCALITY]} localities, {counts[PLACE]} places, {counts[STREET]} streets, "
        f"{counts[ADDRESS]} addresses in {time.time() - started:.0f}s",
        file=sys.stderr,
    )
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("pbf")
    parser.add_argument("output")
    parser.add_argument("region")
    args = parser.parse_args()
    build(args.pbf, args.output, args.region)


if __name__ == "__main__":
    main()
