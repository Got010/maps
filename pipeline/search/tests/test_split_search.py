"""Tests for split_search.py. Run: python3 -m unittest discover -s pipeline/search/tests"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build_search  # noqa: E402
import overture  # noqa: E402
import split_search  # noqa: E402

CUT = ["nyc", "li", "hudson", "capital", "western", "njn"]

# name, lat, lon
FEATURES = [
    ("Empire State Building", 40.7484, -73.9857),  # New York City
    ("Albany City Hall", 42.6526, -73.7562),  # Capital Region
    ("Newark Penn Station", 40.7347, -74.1644),  # in New York City's box, but northern New Jersey's area
    ("Riverdale Park", 40.910, -73.905),  # where New York City's box and the Hudson Valley's overlap
    ("Hartford Library", 41.7658, -72.6734),  # Connecticut: its own pack
    ("Boston Common", 42.3550, -71.0656),  # in no region
]


def source_pack(path):
    """A search-nys.sqlite in miniature: build_search's and overture's schema, a few rows in known places."""
    db = sqlite3.connect(path)
    db.executescript(build_search.SCHEMA + overture.SCHEMA)
    db.execute("DROP TABLE address_raw")  # build_search drops it once the addresses are merged
    for i, (name, lat, lon) in enumerate(FEATURES, start=1):
        db.execute(
            "INSERT INTO feature (id, kind, name, lat, lon, importance, name_key, search_text)"
            " VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            (i * 10, build_search.PLACE, name, lat, lon, name.lower(), name.lower()),
        )
    db.executemany(
        "INSERT INTO landmark (id, category, name, lat, lon) VALUES (?, ?, ?, ?, ?)",
        [(1, "alpr", None, 40.75, -73.99), (2, "traffic_signals", None, 42.8864, -78.8784)],  # NYC, Buffalo
    )
    db.execute(
        "INSERT INTO overture_place (rowid, name, lat, lon, confidence, name_key, search_text)"
        " VALUES (7, 'Suffolk Diner', 40.8, -73.1, 0.9, 'suffolk diner', 'suffolk diner')"  # Long Island
    )
    # Main Street runs from the Hudson Valley (below 42.4) into the Capital Region; Broadway is in Manhattan.
    db.executemany(
        "INSERT INTO address_street (id, street, city, state, street_key, search_text, lat_e6, lon_e6)"
        " VALUES (?, ?, ?, 'NY', ?, ?, ?, ?)",
        [(1, "Main Street", "Stuyvesant", "main st", "main st stuyvesant", 42400000, -73700000),
         (2, "Broadway", "New York", "broadway", "broadway new york", 40750000, -73990000)],
    )
    db.executemany(
        "INSERT INTO address (street_id, number_key, id, number, lat_e6, lon_e6) VALUES (?, ?, ?, ?, ?, ?)",
        [(1, 10, 1, "10", 42390000, -73700000), (1, 20, 2, "20", 42410000, -73700000),
         (2, 1, 3, "1", 40750000, -73990000)],
    )
    db.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [("region", "nys"), ("attribution", "© OpenStreetMap contributors, ODbL")],
    )
    db.commit()
    db.executescript(build_search.INDEXES)
    overture.finish(db)
    db.execute(f"PRAGMA application_id = {build_search.APPLICATION_ID}")
    db.execute(f"PRAGMA user_version = {build_search.SCHEMA_VERSION}")
    db.commit()
    db.close()


class SplitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.source = os.path.join(cls.tmp.name, "search-nys.sqlite")
        source_pack(cls.source)
        cls.out = os.path.join(cls.tmp.name, "out")
        with open(os.devnull, "w") as log:
            cls.written = split_search.split(cls.source, cls.out, "nys", split_search.load_regions(), log=log)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def pack(self, region):
        db = sqlite3.connect(os.path.join(self.out, f"search-{region}.sqlite"))
        self.addCleanup(db.close)
        return db

    def names(self, region):
        return sorted(r[0] for r in self.pack(region).execute("SELECT name FROM feature"))

    def test_one_pack_per_region_cut_from_nys(self):
        self.assertEqual(CUT, split_search.targets(split_search.load_regions(), "nys"))
        self.assertEqual(sorted(f"search-{r}.sqlite" for r in CUT), sorted(os.listdir(self.out)))

    def test_rows_go_where_the_app_puts_their_point(self):
        self.assertEqual(["Empire State Building", "Riverdale Park"], self.names("nyc"))
        self.assertEqual(["Riverdale Park"], self.names("hudson"))
        self.assertEqual(["Albany City Hall"], self.names("capital"))
        # Newark is northern New Jersey's alone, though New York City's box covers it.
        self.assertEqual(["Newark Penn Station"], self.names("njn"))
        self.assertEqual([], self.names("li"))
        # Connecticut and Boston are in none of the packs.
        everywhere = [name for r in CUT for name in self.names(r)]
        self.assertNotIn("Hartford Library", everywhere)
        self.assertNotIn("Boston Common", everywhere)

    def test_ids_stay_as_they_were(self):
        self.assertEqual([(10, 10)], self.pack("nyc").execute(
            "SELECT id, rowid FROM feature WHERE name = 'Empire State Building'").fetchall())
        self.assertEqual([(7, "Suffolk Diner")], self.pack("li").execute("SELECT rowid, name FROM overture_place").fetchall())

    def test_landmarks(self):
        self.assertEqual([(1, "alpr")], self.pack("nyc").execute("SELECT id, category FROM landmark").fetchall())
        self.assertEqual([(2, "traffic_signals")], self.pack("western").execute("SELECT id, category FROM landmark").fetchall())

    def test_a_street_goes_with_its_addresses(self):
        for region, number in (("hudson", "10"), ("capital", "20")):
            db = self.pack(region)
            self.assertEqual([(1, "Main Street")], db.execute("SELECT id, street FROM address_street").fetchall(), region)
            self.assertEqual([(number,)], db.execute("SELECT number FROM address").fetchall(), region)
        nyc = self.pack("nyc")
        self.assertEqual([(2, "Broadway")], nyc.execute("SELECT id, street FROM address_street").fetchall())
        self.assertEqual([], self.pack("li").execute("SELECT * FROM address_street").fetchall())

    def match(self, region, fts, word):
        """Rowids of `fts` matching `word`, asked the way core/search SearchQuery asks: every trigram of it."""
        query = " AND ".join(f'"{word[i:i + 3]}"' for i in range(len(word) - 2))
        return [r[0] for r in self.pack(region).execute(f"SELECT rowid FROM {fts} WHERE {fts} MATCH ?", (query,))]

    def test_full_text_indexes_are_rebuilt(self):
        self.assertEqual([10], self.match("nyc", "feature_fts", "empire"))
        self.assertEqual([], self.match("nyc", "feature_fts", "albany"))
        self.assertEqual([20], self.match("capital", "feature_fts", "albany"))
        self.assertEqual([2], self.match("nyc", "address_street_fts", "broadway"))
        self.assertEqual([], self.match("capital", "address_street_fts", "broadway"))
        self.assertEqual([7], self.match("li", "overture_fts", "diner"))

    def test_header_indexes_and_meta(self):
        db = self.pack("capital")
        self.assertEqual(build_search.APPLICATION_ID, db.execute("PRAGMA application_id").fetchone()[0])
        self.assertEqual(build_search.SCHEMA_VERSION, db.execute("PRAGMA user_version").fetchone()[0])
        indexes = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL")}
        self.assertLessEqual({"feature_name_key", "landmark_lat_lon", "address_street_key", "overture_place_name_key"}, indexes)
        meta = dict(db.execute("SELECT key, value FROM meta"))
        self.assertEqual("capital", meta["region"])
        self.assertEqual("nys", meta["cut_from"])
        self.assertEqual("© OpenStreetMap contributors, ODbL", meta["attribution"])

    def test_counts_per_pack(self):
        self.assertEqual(2, self.written["search-nyc.sqlite"]["feature"])
        self.assertEqual(1, self.written["search-capital.sqlite"]["address"])

    def test_a_table_without_a_rule_stops_the_split(self):
        source = os.path.join(self.tmp.name, "extra.sqlite")
        source_pack(source)
        db = sqlite3.connect(source)
        db.execute("CREATE TABLE extra (x)")
        db.commit()
        db.close()
        with open(os.devnull, "w") as log, self.assertRaises(SystemExit) as e:
            split_search.split(source, os.path.join(self.tmp.name, "extra"), "nys", split_search.load_regions(), log=log)
        self.assertIn("extra", str(e.exception))

    def test_too_big_stops_the_split(self):
        with open(os.devnull, "w") as log, self.assertRaises(SystemExit):
            split_search.split(self.source, os.path.join(self.tmp.name, "big"), "nys", split_search.load_regions(),
                               max_mb=0.000001, log=log)
        self.assertFalse(any(n.endswith(".sqlite") for n in os.listdir(os.path.join(self.tmp.name, "big"))))


class RealPackTest(unittest.TestCase):
    """Every table of a pack build_search.py writes, with Overture, has a rule."""

    def test_builder_tables_are_known(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "search-nys.sqlite")
            staging = os.path.join(tmp, "places.csv")
            with open(os.path.join(HERE, "overture_places.csv"), "rb") as src, open(staging, "wb") as dst:
                dst.write(src.read())
            with open(staging + ".json", "w") as f:
                json.dump({"release": "2026-09-23.1"}, f)
            build_search.build(os.path.join(HERE, "fixture.osm"), out, "nys", overture=staging)
            with open(os.devnull, "w") as log:
                written = split_search.split(out, os.path.join(tmp, "out"), "nys", split_search.load_regions(), log=log)
            # The fixture is in Manhattan.
            self.assertGreater(written["search-nyc.sqlite"]["feature"], 0)
            self.assertEqual({"meta", "feature", "landmark", "address_street", "address", "overture_place"},
                             set(written["search-nyc.sqlite"]))


if __name__ == "__main__":
    unittest.main()
