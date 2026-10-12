"""Tests for the address tables and addresses.py.

Run: python3 -m unittest discover -s pipeline/search/tests
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import addresses  # noqa: E402
import build_search  # noqa: E402
from normalize import house_number_key, normalize  # noqa: E402

NYS_BOUNDS = (-79.77, 40.3, -71.75, 45.02)


class HouseNumberTest(unittest.TestCase):
    def test_shared_cases(self):
        # The same cases run against the Kotlin AddressQuery.numberKey.
        with open(os.path.join(os.path.dirname(HERE), "house_number_cases.json")) as f:
            cases = json.load(f)
        self.assertGreater(len(cases), 5)
        for raw, expected in cases:
            self.assertEqual(expected, house_number_key(raw), raw)

    def test_cleanup(self):
        self.assertEqual("123A", build_search.clean_number(" 123a "))
        self.assertEqual("12-34", build_search.clean_number("12 - 34"))
        self.assertEqual("123", build_search.clean_number("123;125"))
        self.assertEqual("NY", build_search.state_code("New York"))
        self.assertEqual("NJ", build_search.state_code("nj"))
        self.assertIsNone(build_search.state_code("Nowhere"))

    def test_display_address(self):
        self.assertEqual(
            "123 Main Street, Nyack, NY 10960",
            build_search.display_address("123", "Main Street", "Nyack", "NY", "10960"),
        )
        self.assertEqual("5 Elm St, NY", build_search.display_address("5", "Elm St", None, "NY", None))


class AddressPackTest(unittest.TestCase):
    """OSM addresses (addresses.osm) plus a state file (address_points.csv)."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "search-test.sqlite")
        cls.counts = build_search.build(
            os.path.join(HERE, "addresses.osm"), cls.out, "test",
            address_points=os.path.join(HERE, "address_points.csv"), bounds=NYS_BOUNDS,
        )
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def addresses(self):
        return self.db.execute(
            "SELECT a.number, s.street, s.city, s.state, a.postcode FROM address a"
            " JOIN address_street s ON s.id = a.street_id ORDER BY s.street_key, s.city, a.number_key"
        ).fetchall()

    def test_addresses(self):
        self.assertEqual(
            [
                ("1", "Broad Street", "Newark", "NJ", None),
                # The cafe and the state point at 123 are the same house as the
                # untagged OSM node; the town and ZIP come from the state point.
                ("123", "Main Street", "Nyack", "NY", "10960"),
                ("125", "Main Street", "Nyack", "NY", "10960"),
                ("131", "Main Street", "Nyack", "NY", "10960"),
                ("123", "Main Street", "Sayville", "NY", "11782"),
                ("31-05", "Steinway Street", "Astoria", "NY", "11103"),
                # OSM's spelling of the street wins over the state's "W 20TH ST".
                ("10", "West 20th Street", "New York", "NY", "10011"),
                ("224", "West 20th Street", "New York", "NY", "10011"),
            ],
            self.addresses(),
        )
        self.assertEqual(8, self.counts[build_search.ADDRESS])
        # None left in the trigram index, and the staging table is gone.
        self.assertEqual(0, self.db.execute("SELECT COUNT(*) FROM feature WHERE kind = 3").fetchone()[0])
        self.assertIsNone(
            self.db.execute("SELECT name FROM sqlite_master WHERE name = 'address_raw'").fetchone()
        )

    def test_number_key_and_position(self):
        rows = self.db.execute(
            "SELECT a.number, a.number_key, a.lat_e6, a.lon_e6 FROM address a"
            " JOIN address_street s ON s.id = a.street_id WHERE s.street_key = 'steinway st'"
        ).fetchall()
        # The OSM node keeps its position over the state point 2 m away.
        self.assertEqual([("31-05", 310005, 40760000, -73915000)], rows)

    def test_street_text_has_town_and_state(self):
        text = self.db.execute(
            "SELECT search_text FROM address_street WHERE street_key = 'main st' AND city = 'Nyack'"
        ).fetchone()[0]
        # The municipality from the state file is searchable too.
        self.assertEqual(" main st nyack orangetown ny ", text)

    def test_street_centre_is_the_mean_of_its_addresses(self):
        lat, lon = self.db.execute(
            "SELECT lat_e6, lon_e6 FROM address_street WHERE street_key = 'main st' AND city = 'Nyack'"
        ).fetchone()
        # 123 (41.0910), 125 (41.0912) and 131 (41.0916).
        self.assertEqual(41091267, lat)
        self.assertEqual(-73920267, lon)

    def test_lookup_uses_the_primary_key(self):
        plan = " ".join(
            str(r[-1])
            for r in self.db.execute(
                "EXPLAIN QUERY PLAN SELECT number FROM address WHERE street_id = 1 AND number_key >= 120"
                " ORDER BY number_key LIMIT 3"
            )
        )
        self.assertIn("PRIMARY KEY", plan)

    def test_street_index(self):
        # How the app finds streets: trigrams AND-ed, then each word confirmed.
        def streets(query):
            tokens = normalize(query).split()
            grams = sorted({t[i:i + 3] for t in tokens for i in range(len(t) - 2)})
            rows = self.db.execute(
                "SELECT s.street, s.city, s.search_text FROM address_street_fts"
                " JOIN address_street s ON s.id = address_street_fts.rowid"
                " WHERE address_street_fts MATCH ? ORDER BY s.city",
                (" AND ".join(f'"{g}"' for g in grams),),
            )
            return [(street, city) for street, city, text in rows if all(f" {t}" in text for t in tokens)]

        self.assertEqual([("Main Street", "Nyack"), ("Main Street", "Sayville")], streets("Main St"))
        self.assertEqual([("Main Street", "Nyack")], streets("main street nyack"))
        self.assertEqual([("Main Street", "Nyack")], streets("main orangetown"))

    def test_streets_carry_their_town(self):
        streets = dict(self.db.execute("SELECT name, address FROM feature WHERE kind = 2"))
        self.assertEqual("Nyack, NY", streets["Main Street"])
        self.assertEqual("NY", streets["County Route 1"])  # no town near
        text = self.db.execute("SELECT search_text FROM feature WHERE name = 'Main Street'").fetchone()[0]
        self.assertEqual(" main st nyack ny st ", text)

    def test_places_keep_their_address_text(self):
        # A place with an address is still found by it in the main index.
        text = self.db.execute("SELECT search_text FROM feature WHERE name = 'Main Street Cafe'").fetchone()[0]
        self.assertIn(" 123 main st ", text)

    def test_meta(self):
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual("by location", meta["state"])
        self.assertEqual("address_points.csv", meta["address_points"])
        self.assertIn("NYS Address Points", meta["attribution"])

    def test_size_guard(self):
        out = os.path.join(self.tmp.name, "too-big.sqlite")
        with self.assertRaises(SystemExit):
            build_search.build(os.path.join(HERE, "addresses.osm"), out, "test", max_mb=0.001)

    def test_a_state_file_that_gives_no_address_stops_the_build(self):
        # The file's header with a column renamed: every row is skipped, which used to ship a pack without addresses.
        empty = os.path.join(self.tmp.name, "renamed.csv")
        with open(os.path.join(HERE, "address_points.csv")) as f:
            rows = f.read().splitlines()
        with open(empty, "w") as f:
            f.write("\n".join([rows[0].replace("X,Y,", "EASTING,NORTHING,")] + rows[1:]) + "\n")
        out = os.path.join(self.tmp.name, "no-addresses.sqlite")
        with self.assertRaises(SystemExit):
            build_search.build(os.path.join(HERE, "addresses.osm"), out, "test", address_points=empty)

    def test_state_override(self):
        out = os.path.join(self.tmp.name, "nj.sqlite")
        build_search.build(os.path.join(HERE, "addresses.osm"), out, "test", state="NJ")
        with sqlite3.connect(out) as db:
            states = {s for (s,) in db.execute("SELECT DISTINCT state FROM address_street")}
        self.assertEqual({"NJ"}, states)


class LocalityFinderTest(unittest.TestCase):
    KM = 1 / build_search.KM_PER_DEGREE

    def test_bigger_places_reach_farther(self):
        # A city 5 km east and a town 3 km west: the city wins (5/3 < 3/1.5).
        finder = build_search.LocalityFinder([
            ("city", "Buffalo", 0.0, 5 * self.KM),
            ("town", "Cheektowaga", 0.0, -3 * self.KM),
        ])
        self.assertEqual("Buffalo", finder.nearest(0.0, 0.0))

    def test_neighbourhood_only_up_close(self):
        finder = build_search.LocalityFinder([("neighbourhood", "Corn Hill", 0.0, 0.0)])
        self.assertEqual("Corn Hill", finder.nearest(0.0, 1 * self.KM))
        self.assertIsNone(finder.nearest(0.0, 2 * self.KM))

    def test_islands_dont_count(self):
        finder = build_search.LocalityFinder([("island", "Long Island", 0.0, 0.0)])
        self.assertIsNone(finder.nearest(0.0, 0.0))


class AddressPointsTest(unittest.TestCase):
    def test_csv_columns_and_cleanup(self):
        rows = list(addresses.points(os.path.join(HERE, "address_points.csv"), NYS_BOUNDS))
        self.assertEqual(
            {"number": "123", "street": "Main St", "city": "Nyack", "alt_city": "Orangetown",
             "postcode": "10960", "state": "NY", "lat": 41.09102, "lon": -73.92002},
            rows[0],
        )
        self.assertEqual("131", rows[2]["number"])  # "131.0" from a float column
        self.assertEqual("10960", rows[2]["postcode"])  # ZIP+4 cut to 5
        self.assertEqual("W 20th St", rows[3]["street"])
        self.assertIsNone(rows[3]["alt_city"])  # the same as the city
        self.assertEqual("31-05", rows[4]["number"])  # from AddressLabel
        # Outside the bounds, or without a position: left out. Without a
        # number: read here, skipped by the collector.
        self.assertEqual(6, len(rows))
        self.assertIsNone(rows[5]["number"])

    def test_statewide_file_columns(self):
        # The header and two rows of the real statewide file (SAM_Master_Statewide_Database.gdb,
        # layer AddressPoints_SAM) as search/build.sh's ogr2ogr writes it, trimmed to the columns
        # read here plus a few others; the third row adds a fraction and the fourth a letter suffix.
        header = ("X,Y,NYSAddressPointID,PrefixAddressNumber,AddressNumber,SuffixAddressNumber,StreetName,"
                  "ZipName,State,ZipCode,PointType,PrimaryPoint,CityTownName,CompleteStreetName,Status,"
                  "AddressLabel,Latitude,Longitude")
        rows = [
            "-73.8192503045,42.6999459098,ALBA074317,,8,,Cherry,Albany,NY,12205,1,Y,Colonie,Cherry Street,"
            "Active,8 Cherry St,42.6999545530001,-73.81925182",
            "-73.8190887117,42.6997753923,ALBA074316,,10,,Cherry,Albany,NY,12205,1,Y,Colonie,Cherry Street,"
            "Active,10 Cherry St,42.6997840350001,-73.8190902269999",
            "-73.81,42.69,X1,,12,1/2,Cherry,Albany,NY,12205,1,Y,Colonie,Cherry Street,Active,12 1/2 Cherry St,42.69,-73.81",
            "-73.81,42.69,X2,,14,A,Cherry,Albany,NY,12205,1,Y,Colonie,Cherry Street,Active,14A Cherry St,42.69,-73.81",
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "address-points-nys.csv")
            with open(path, "w") as f:
                f.write(header + "\n" + "\n".join(rows) + "\n")
            columns = next(addresses.records(path))[0]
            self.assertEqual("PrefixAddressNumber", columns["number_prefix"])
            self.assertEqual("SuffixAddressNumber", columns["number_suffix"])
            self.assertEqual("CompleteStreetName", columns["street"])
            self.assertEqual("Latitude", columns["lat"])
            points = list(addresses.points(path))
        self.assertEqual(
            {"number": "8", "street": "Cherry Street", "city": "Albany", "alt_city": "Colonie",
             "postcode": "12205", "state": "NY", "lat": 42.6999545530001, "lon": -73.81925182},
            points[0],
        )
        self.assertEqual(["8", "10", "12 1/2", "14A"], [p["number"] for p in points])

    def test_columns_by_name_ignoring_case(self):
        columns = addresses.pick_columns(["x", "y", "ADDRESSNUMBER", "completestreetname", "ZIPCODE"])
        self.assertEqual("ADDRESSNUMBER", columns["number"])
        self.assertEqual("completestreetname", columns["street"])
        self.assertEqual("y", columns["lat"])
        self.assertIsNone(columns["city"])

    def test_geojson(self):
        feature = {
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [-73.5, 42.5]},
            "properties": {"addressnumber": 7, "completestreetname": "ELM ST", "zipname": "CATSKILL",
                           "zipcode": "12414"},
        }
        with tempfile.TemporaryDirectory() as tmp:
            for name, body in (
                ("a.geojson", json.dumps({"type": "FeatureCollection", "features": [feature]})),
                ("a.geojsonl", json.dumps(feature) + "\n"),
            ):
                path = os.path.join(tmp, name)
                with open(path, "w") as f:
                    f.write(body)
                (point,) = addresses.points(path)
                self.assertEqual(
                    ("7", "Elm St", "Catskill", "12414", 42.5, -73.5),
                    (point["number"], point["street"], point["city"], point["postcode"],
                     point["lat"], point["lon"]),
                )

    def test_title(self):
        self.assertEqual("W 20th St", addresses.title("W 20TH ST"))
        self.assertEqual("O'Neil Ave", addresses.title("O'NEIL AVE"))
        self.assertEqual("McDonald Ave", addresses.title("McDonald Ave"))


if __name__ == "__main__":
    unittest.main()
