"""Tests for the Overture Places layer (overture.py) in the search pack.

Run: python3 -m unittest discover -s pipeline/search/tests

No network: overture_places.csv stands in for what `overture.py fetch`
writes. When DuckDB is installed, FetchTest also runs the real fetch query
against a small GeoParquet it writes with the release's column layout.
"""

import csv
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
from normalize import normalize  # noqa: E402

STAGING = os.path.join(HERE, "overture_places.csv")
NYS_BOUNDS = (-79.77, 40.3, -71.75, 45.02)

try:
    import duckdb  # noqa: F401
except ImportError:
    duckdb = None


def records():
    with open(STAGING, newline="", encoding="utf-8") as f:
        return {r["name"] or r["id"]: r for r in csv.DictReader(f)}


class RowMappingTest(unittest.TestCase):
    def setUp(self):
        self.recs = records()

    def row(self, name, scopes=None):
        return overture.place_row(self.recs[name], scopes)

    def test_row_shape(self):
        row = self.row("Joe's Pizza")
        (gers, name, category, address, lat, lon, phone, website, confidence, dataset,
         name_key, text) = row
        self.assertEqual("08f2a100-0000-4000-8000-000000000001", gers)
        self.assertEqual("Joe's Pizza", name)
        self.assertEqual("pizza_restaurant", category)  # no OSM twin: Overture's slug
        self.assertEqual("7 Carmine St, New York 10014", address)  # ZIP+4 cut to 5
        self.assertEqual((40.7306, -74.002), (lat, lon))
        self.assertEqual("+12123661182", phone)
        self.assertEqual("https://www.joespizzanyc.com", website)
        self.assertEqual(0.95, confidence)
        self.assertEqual("meta", dataset)
        # The same normalisation as `feature`, so the app's SearchText matches.
        self.assertEqual(normalize("Joe's Pizza"), name_key)
        self.assertEqual(" joes pizza 7 carmine st new york 10014 pizza restaurant restaurant ", text)

    def test_category_maps_to_osm_values(self):
        self.assertEqual("cafe", self.row("Blue Bottle Coffee")[2])
        self.assertEqual("bookstore", self.row("Nyack Books")[2])
        self.assertEqual("fuel", overture.map_category("gas_station", "gas_station"))
        self.assertEqual("casual_eatery", overture.map_category(None, "casual_eatery"))
        self.assertIsNone(overture.map_category("", None))

    def test_brand_is_searchable_once(self):
        # The brand equal to the name adds nothing; a different one is a word.
        self.assertEqual(" blue bottle coffee 450 w 15 st new york 10011 cafe coffee shop ",
                         self.row("Blue Bottle Coffee")[11])
        self.assertTrue(self.row("Dunkin' Penn Station")[11].endswith(" coffee shop dunkin "))

    def test_skips(self):
        self.assertIsNone(self.row("Shaky Listing"))  # confidence 0.3
        self.assertIsNone(self.row("08f2a100-0000-4000-8000-000000000005"))  # no name
        self.assertIsNone(self.row("Gone Diner"))  # permanently closed

    def test_region_scopes(self):
        self.assertEqual([("NY", (-74.2591, 40.4774, -73.7004, 40.9176))], overture.region_scopes("nyc"))
        self.assertEqual([("NY", None), ("NJ", (-74.75, 40.3, -73.88, 41.36))], overture.region_scopes("nys"))
        self.assertEqual([("NJ", (-74.75, 40.3, -73.88, 41.36))], overture.region_scopes("njn"))
        self.assertIsNone(overture.region_scopes("test"))

    def test_rows_outside_the_region_are_dropped(self):
        nys, nyc = overture.region_scopes("nys"), overture.region_scopes("nyc")
        self.assertIsNotNone(self.row("Hoboken Terminal Deli", nys))
        self.assertIsNone(self.row("Hoboken Terminal Deli", nyc))  # NJ isn't in the NYC pack
        self.assertIsNone(self.row("Phillipsburg Hardware", nys))  # NJ, west of the njn area
        self.assertIsNone(self.row("Greenwich Bakery", nys))  # Connecticut
        self.assertIsNone(self.row("No State Shop", nys))  # no state at all
        self.assertIsNotNone(self.row("Nyack Books", nys))  # "New York" spelled out
        self.assertIsNone(self.row("Nyack Books", nyc))  # outside the NYC box

    def test_release_choice(self):
        listed = ["2026-08-19.0", overture.DEFAULT_RELEASE, "2099-01-01.0"]
        self.assertEqual(overture.DEFAULT_RELEASE, overture.resolve_release(None, listed))
        self.assertEqual("2099-01-01.0", overture.resolve_release("latest", listed))
        self.assertEqual("2026-08-19.0", overture.resolve_release("2026-08-19.0", listed))
        # The pinned release was deleted: the newest one instead.
        self.assertEqual("2099-01-01.0", overture.resolve_release(None, ["2098-01-01.0", "2099-01-01.0"]))
        # The bucket couldn't be listed: trust the name.
        self.assertEqual("2030-01-01.0", overture.resolve_release("2030-01-01.0", None))
        with self.assertRaises(SystemExit):
            overture.resolve_release("2000-01-01.0", listed)

    def test_fetch_query_filters_on_the_bbox_columns(self):
        sql = overture.fetch_sql("s3://bucket/release/R/theme=places/type=place/*", NYS_BOUNDS)
        self.assertIn("bbox.xmin >= -79.77 AND bbox.xmax <= -71.75", sql)
        self.assertIn("bbox.ymin >= 40.3 AND bbox.ymax <= 45.02", sql)
        self.assertIn("confidence >= 0.5", sql)
        self.assertIn("read_parquet('s3://bucket/release/R/theme=places/type=place/*'", sql)


class PackTest(unittest.TestCase):
    """fixture.osm plus overture_places.csv through build_search.build."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "search-test.sqlite")
        staging = os.path.join(cls.tmp.name, "places.csv")
        with open(STAGING, "rb") as src, open(staging, "wb") as dst:
            dst.write(src.read())
        with open(staging + ".json", "w") as f:
            json.dump({"release": "2026-09-23.1"}, f)
        build_search.build(os.path.join(HERE, "fixture.osm"), cls.out, "test", overture=staging)
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def test_own_tables_and_unchanged_schema_version(self):
        tables = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        self.assertTrue({"overture_place", "overture_fts", "feature", "feature_fts"} <= tables)
        self.assertEqual(build_search.SCHEMA_VERSION, self.db.execute("PRAGMA user_version").fetchone()[0])
        # Nothing of Overture's is in OSM's table.
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM feature WHERE name = 'Joe''s Pizza'").fetchone()[0])

    def test_rows_kept(self):
        names = [r[0] for r in self.db.execute("SELECT name FROM overture_place ORDER BY name")]
        # Region "test" isn't in regions.json, so no state filter; only the
        # unnamed, unsure and closed rows go.
        self.assertEqual(
            ["Blue Bottle Coffee", "Café Grumpy", "Dunkin' Penn Station", "Greenwich Bakery",
             "Hoboken Terminal Deli", "Joe's Pizza", "No State Shop", "Nyack Books", "Phillipsburg Hardware"],
            names,
        )

    def test_trigram_index(self):
        found = self.db.execute(
            "SELECT o.name FROM overture_fts JOIN overture_place o ON o.rowid = overture_fts.rowid"
            " WHERE overture_fts MATCH ? ORDER BY o.name",
            ('"izz"',),
        ).fetchall()
        self.assertEqual([("Joe's Pizza",)], found)
        deli = self.db.execute(
            "SELECT o.name FROM overture_fts JOIN overture_place o ON o.rowid = overture_fts.rowid"
            " WHERE overture_fts MATCH ?",
            ('"del" AND "eli"',),  # how SearchQuery.trigramMatch asks for "deli"
        ).fetchall()
        self.assertEqual([("Hoboken Terminal Deli",)], deli)

    def test_osm_twin_shares_the_name_key(self):
        # The app drops the Overture copy of Café Grumpy: same key, metres apart.
        osm = self.db.execute("SELECT name_key FROM feature WHERE name = 'Café Grumpy'").fetchone()[0]
        ovt = self.db.execute("SELECT name_key FROM overture_place WHERE name = 'Café Grumpy'").fetchone()[0]
        self.assertEqual(osm, ovt)

    def test_meta(self):
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual("2026-09-23.1", meta["overture_release"])
        self.assertIn("Overture Maps Foundation", meta["attribution"])
        self.assertIn("Foursquare under Apache 2.0", meta["attribution"])

    def test_without_overture_no_tables(self):
        out = os.path.join(self.tmp.name, "plain.sqlite")
        build_search.build(os.path.join(HERE, "fixture.osm"), out, "test")
        db = sqlite3.connect(out)
        try:
            tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
            self.assertNotIn("overture_place", tables)
            self.assertEqual("", dict(db.execute("SELECT key, value FROM meta"))["overture_release"])
        finally:
            db.close()

    def test_size_guard(self):
        out = os.path.join(self.tmp.name, "guarded.sqlite")
        with self.assertRaises(SystemExit):
            build_search.build(os.path.join(HERE, "fixture.osm"), out, "test",
                               overture=STAGING, overture_max_mb=0.000001)


@unittest.skipIf(duckdb is None, "DuckDB is not installed (pip install duckdb)")
class FetchTest(unittest.TestCase):
    """The real fetch query against a GeoParquet laid out like Overture's
    places (the columns it reads, with the release's nesting)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.parquet = os.path.join(self.tmp.name, "places.parquet")
        db = duckdb.connect()
        db.execute("""
            CREATE TABLE place (
              id VARCHAR,
              names STRUCT("primary" VARCHAR),
              taxonomy STRUCT("primary" VARCHAR, hierarchy VARCHAR[]),
              basic_category VARCHAR,
              brand STRUCT(names STRUCT("primary" VARCHAR)),
              addresses STRUCT(freeform VARCHAR, locality VARCHAR, postcode VARCHAR, region VARCHAR, country VARCHAR)[],
              websites VARCHAR[],
              phones VARCHAR[],
              confidence DOUBLE,
              sources STRUCT(dataset VARCHAR, license VARCHAR)[],
              operating_status VARCHAR,
              bbox STRUCT(xmin DOUBLE, xmax DOUBLE, ymin DOUBLE, ymax DOUBLE)
            )""")
        db.execute("""
            INSERT INTO place VALUES
            ('a', {'primary': 'Joe''s Pizza'},
             {'primary': 'pizza_restaurant', 'hierarchy': ['food_and_drink', 'restaurant', 'pizza_restaurant']},
             'restaurant', {'names': {'primary': NULL}},
             [{'freeform': '7 Carmine St', 'locality': 'New York', 'postcode': '10014', 'region': 'NY', 'country': 'US'}],
             ['www.joespizzanyc.com'], ['+12123661182'], 0.95,
             [{'dataset': 'meta', 'license': 'CDLA-Permissive-2.0'}], 'open',
             {'xmin': -74.002, 'xmax': -74.002, 'ymin': 40.7306, 'ymax': 40.7306}),
            ('b', {'primary': 'Far Away Cafe'}, {'primary': 'cafe', 'hierarchy': []}, 'cafe',
             {'names': {'primary': NULL}}, [], [], [], 0.9,
             [{'dataset': 'Foursquare', 'license': 'Apache-2.0'}], NULL,
             {'xmin': -118.25, 'xmax': -118.25, 'ymin': 34.05, 'ymax': 34.05}),
            ('c', {'primary': 'Unsure Place'}, {'primary': 'cafe', 'hierarchy': []}, 'cafe',
             {'names': {'primary': NULL}}, [], [], [], 0.2,
             [{'dataset': 'meta', 'license': 'CDLA-Permissive-2.0'}], NULL,
             {'xmin': -73.99, 'xmax': -73.99, 'ymin': 40.75, 'ymax': 40.75}),
            ('d', {'primary': NULL}, {'primary': 'cafe', 'hierarchy': []}, 'cafe',
             {'names': {'primary': NULL}}, [], [], [], 0.9,
             [{'dataset': 'meta', 'license': 'CDLA-Permissive-2.0'}], NULL,
             {'xmin': -73.99, 'xmax': -73.99, 'ymin': 40.75, 'ymax': 40.75}),
            ('e', {'primary': 'Starbucks'}, {'primary': 'coffee_shop', 'hierarchy': []}, 'cafe',
             {'names': {'primary': 'Starbucks'}}, NULL, NULL, NULL, 0.99,
             [{'dataset': 'AllThePlaces', 'license': 'CC0-1.0'}], 'open',
             {'xmin': -73.98, 'xmax': -73.98, 'ymin': 40.76, 'ymax': 40.76})
        """)
        db.execute(f"COPY place TO '{self.parquet}' (FORMAT parquet)")
        db.close()

    def tearDown(self):
        self.tmp.cleanup()

    def test_fetch_writes_staging_rows(self):
        out = os.path.join(self.tmp.name, "staging.csv")
        rows = overture.fetch(NYS_BOUNDS, out, source=self.parquet)
        self.assertEqual(2, rows)  # Los Angeles, the unsure row and the unnamed row stay out
        with open(out, newline="") as f:
            reader = csv.DictReader(f)
            self.assertEqual(overture.STAGING_COLUMNS, reader.fieldnames)
            got = {r["id"]: r for r in reader}
        self.assertEqual({"a", "e"}, set(got))
        a = got["a"]
        self.assertEqual(("Joe's Pizza", "pizza_restaurant", "restaurant", "7 Carmine St", "NY", "meta"),
                         (a["name"], a["category"], a["basic_category"], a["freeform"], a["region"], a["dataset"]))
        self.assertEqual(("www.joespizzanyc.com", "+12123661182"), (a["website"], a["phone"]))
        self.assertAlmostEqual(40.7306, float(a["lat"]))
        self.assertAlmostEqual(-74.002, float(a["lon"]))
        # No address at all: empty fields, and the row maps without a state
        # only when the region doesn't ask for one.
        e = got["e"]
        self.assertEqual(("Starbucks", "", "AllThePlaces"), (e["brand"], e["region"], e["dataset"]))
        self.assertIsNotNone(overture.place_row(e))
        self.assertIsNone(overture.place_row(e, overture.region_scopes("nys")))
        # A second fetch with the same settings reuses the file.
        self.assertIsNone(overture.fetch(NYS_BOUNDS, out, source=self.parquet))


if __name__ == "__main__":
    unittest.main()
