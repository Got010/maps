"""Tests for the search pack builder. Run: python3 -m unittest discover -s pipeline/search/tests"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import build_search  # noqa: E402
from normalize import normalize  # noqa: E402


class NormalizeTest(unittest.TestCase):
    def test_shared_cases(self):
        # The same cases run against the Kotlin SearchText in core/search.
        with open(os.path.join(os.path.dirname(HERE), "normalize_cases.json")) as f:
            for raw, expected in json.load(f):
                self.assertEqual(expected, normalize(raw), raw)


class BuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "search-test.sqlite")
        cls.counts = build_search.build(os.path.join(HERE, "fixture.osm"), cls.out, "test")
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def rows(self, kind):
        return self.db.execute(
            "SELECT name, category, address FROM feature WHERE kind = ? ORDER BY name", (kind,)
        ).fetchall()

    def test_header_marks_a_search_pack(self):
        self.assertEqual(0x53425331, self.db.execute("PRAGMA application_id").fetchone()[0])
        self.assertEqual(2, self.db.execute("PRAGMA user_version").fetchone()[0])
        with open(self.out, "rb") as f:
            header = f.read(72)
        self.assertEqual(b"SQLite format 3\x00", header[:16])
        self.assertEqual(b"SBS1", header[68:72])

    def test_places(self):
        self.assertEqual(
            [
                ("Café Grumpy", "cafe", "224 West 20th Street, New York 10011"),
                ("Empire State Building", "attraction", None),
                ("Shell Gas", "fuel", None),
            ],
            self.rows(build_search.PLACE),
        )

    def test_place_details(self):
        hours, cuisine, ref = self.db.execute(
            "SELECT opening_hours, cuisine, osm_ref FROM feature WHERE name = 'Café Grumpy'"
        ).fetchone()
        self.assertEqual(("Mo-Fr 07:00-19:00", "coffee_shop", "n1"), (hours, cuisine, ref))

    def test_area_centre_and_ref(self):
        lat, lon, ref, importance = self.db.execute(
            "SELECT lat, lon, osm_ref, importance FROM feature WHERE name = 'Empire State Building'"
        ).fetchone()
        self.assertAlmostEqual(40.7484, lat, places=3)
        self.assertAlmostEqual(-73.9856, lon, places=3)
        self.assertEqual("w100", ref)
        self.assertEqual(60, importance)  # base 30 + wikidata 20 + attraction 10

    def test_localities(self):
        self.assertEqual([("Flatiron District", "neighbourhood", None)], self.rows(build_search.LOCALITY))

    def test_addresses(self):
        # Since schema 2 addresses have their own tables (see test_addresses.py).
        self.assertEqual([], self.rows(build_search.ADDRESS))
        rows = self.db.execute(
            "SELECT a.number, s.street, s.city, s.state, a.postcode FROM address a"
            " JOIN address_street s ON s.id = a.street_id ORDER BY a.number_key"
        ).fetchall()
        self.assertEqual(
            [
                ("123", "Court Street", "Brooklyn", "NY", "11201"),
                ("224", "West 20th Street", "New York", "NY", "10011"),  # Café Grumpy's
            ],
            rows,
        )

    def test_streets_are_grouped_per_cell(self):
        # Two nearby Broadway segments make one row; the far one makes another.
        self.assertEqual(2, len([r for r in self.rows(build_search.STREET) if r[0] == "Broadway"]))
        self.assertNotIn("Some Path", [r[0] for r in self.rows(build_search.STREET)])

    def test_trigram_index_finds_normalised_text(self):
        # The index stores no positions (detail=none), so the app ANDs the
        # trigrams of each query word, then keeps rows whose text really
        # contains the word. This mirrors core/search.
        # Words shorter than 3 letters have no trigrams and are only checked
        # in that second step.
        def search(query):
            tokens = normalize(query).split()
            grams = sorted({t[i:i + 3] for t in tokens for i in range(len(t) - 2)})
            match = " AND ".join(f'"{g}"' for g in grams)
            rows = self.db.execute(
                "SELECT f.name, f.search_text FROM feature_fts JOIN feature f ON f.id = feature_fts.rowid"
                " WHERE feature_fts MATCH ? ORDER BY f.name",
                (match,),
            )
            return [name for name, text in rows if all(f" {t}" in text for t in tokens)]

        self.assertEqual(["Café Grumpy"], search("grump"))
        self.assertEqual(["Café Grumpy"], search("Cafe W 20th"))
        self.assertEqual(["Café Grumpy"], search("coffee"))
        self.assertEqual(["Café Grumpy"], search("224 W 20"))
        self.assertEqual(["Empire State Building"], search("empire"))
        # A word must start at a word boundary, so "rumpy" finds nothing.
        self.assertEqual([], search("rumpy"))

    def test_landmarks(self):
        rows = self.db.execute("SELECT category, name, lat, lon FROM landmark ORDER BY lat").fetchall()
        self.assertEqual(
            [
                ("traffic_signals", None, 40.74, -73.99),
                ("stop", None, 40.741, -73.99),
                # Brand beats the name: "past the Shell".
                ("fuel", "Shell", 40.742, -73.991),
                ("speed_camera", None, 40.743, -73.99),
                # Plate readers keep who makes them, for the map's camera card.
                ("alpr_camera", "Flock Safety", 40.744, -73.99),
                ("alpr_camera", "Motorola Solutions", 40.7445, -73.99),
                ("alpr_camera", None, 40.7448, -73.99),
                # Node 35 is a plain camera: not an alert.
                ("school_zone", None, 40.746, -73.99),
                # The start of a road with a school speed limit.
                ("school_zone", None, 40.747, -73.99),
            ],
            rows,
        )

    def test_landmark_box_query_uses_the_index(self):
        plan = " ".join(
            str(r[-1])
            for r in self.db.execute(
                "EXPLAIN QUERY PLAN SELECT category FROM landmark"
                " WHERE lat BETWEEN 40.73 AND 40.75 AND lon BETWEEN -74.0 AND -73.98"
            )
        )
        self.assertIn("landmark_lat_lon", plan)

    def test_category_box_query_uses_the_category_index(self):
        # The app loads every plate reader, and the ones near each route, by category.
        plan = " ".join(
            str(r[-1])
            for r in self.db.execute(
                "EXPLAIN QUERY PLAN SELECT id FROM landmark WHERE category = 'alpr_camera'"
                " AND lat BETWEEN 40.73 AND 40.75 AND lon BETWEEN -74.0 AND -73.98"
            )
        )
        self.assertIn("landmark_category_lat_lon", plan)
        whole = " ".join(
            str(r[-1])
            for r in self.db.execute("EXPLAIN QUERY PLAN SELECT id FROM landmark WHERE category = 'alpr_camera'")
        )
        self.assertIn("landmark_category_lat_lon", whole)

    def test_alpr_name(self):
        name = build_search.alpr_name
        self.assertEqual("Flock Safety", name({"manufacturer": "Flock Safety"}))
        self.assertEqual("Flock Safety", name({"brand": "Flock", "operator": "Town Police"}))
        self.assertEqual("Flock Safety", name({"operator": "flock safety"}))
        self.assertEqual("Motorola Solutions", name({"manufacturer": "Motorola Solutions", "operator": "NYPD"}))
        self.assertEqual("NYPD", name({"operator": " NYPD "}))
        self.assertIsNone(name({"surveillance:type": "ALPR"}))
        # Guidance never names signals or other alerts.
        self.assertIsNone(build_search.landmark_name({"highway": "speed_camera", "operator": "NYC DOT"}, "speed_camera"))

    def test_landmark_category(self):
        cat = build_search.landmark_category
        self.assertEqual("alpr_camera", cat({"man_made": "surveillance", "camera:type": "ALPR"}))
        self.assertEqual("alpr_camera", cat({"man_made": "surveillance", "manufacturer": "Flock Safety"}))
        self.assertEqual("church", cat({"amenity": "place_of_worship", "religion": "christian", "name": "St Paul"}))
        self.assertIsNone(cat({"amenity": "place_of_worship", "religion": "jewish"}))
        self.assertEqual("pharmacy", cat({"shop": "chemist", "name": "CVS"}))
        self.assertEqual("school_zone", cat({"traffic_sign": "US:S5-1"}))
        self.assertIsNone(cat({"amenity": "cafe"}))

    def test_meta(self):
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual("test", meta["region"])
        self.assertIn("OpenStreetMap", meta["attribution"])


if __name__ == "__main__":
    unittest.main()
