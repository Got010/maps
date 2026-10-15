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
        self.assertEqual(3, self.db.execute("PRAGMA user_version").fetchone()[0])
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
        rows = self.db.execute("SELECT category, name, lat, lon, direction FROM landmark ORDER BY lat, category").fetchall()
        self.assertEqual(
            [
                # The fixture tags a direction on the stop sign and a camera:direction on the gas station: only cameras keep one.
                ("traffic_signals", None, 40.74, -73.99, None),
                ("stop", None, 40.741, -73.99, None),
                # Brand beats the name: "past the Shell".
                ("fuel", "Shell", 40.742, -73.991, None),
                ("speed_camera", None, 40.743, -73.99, 135),
                # Plate readers keep who makes them, for the map's camera card.
                ("alpr_camera", "Flock Safety", 40.744, -73.99, 22),  # direction=NNE
                ("alpr_camera", "Motorola Solutions", 40.7445, -73.99, 270),  # direction=-90
                ("alpr_camera", None, 40.7448, -73.99, None),  # direction=90-180, a range; its camera:direction=45 doesn't overrule it
                ("alpr_camera", None, 40.7449, -73.99, 225),  # camera:direction=SW alone
                # Node 35 is a plain camera: not an alert.
                ("school_zone", None, 40.746, -73.99, None),
                # The start of a road with a school speed limit.
                ("school_zone", None, 40.747, -73.99, None),
                # A speed camera node tagged speed_camera=traffic_signals is a red light camera.
                ("red_light_camera", None, 40.749, -73.99, 0),
                # A signal with a school crossing sign: the alert and the light both.
                ("school_zone", None, 40.75, -73.99, None),
                ("traffic_signals", None, 40.75, -73.99, None),
                # "school_bus" isn't a school zone sign: only the light.
                ("traffic_signals", None, 40.751, -73.99, None),
                # The school plaza (a closed road) once, where it starts; the hazard-only closed way at its centre.
                ("school_zone", None, 40.752, -73.99, None),
                ("school_zone", None, 40.75225, -73.9895, None),
            ],
            rows,
        )

    def test_direction_column(self):
        info = {r[1]: r for r in self.db.execute("PRAGMA table_info(landmark)")}
        self.assertEqual("INTEGER", info["direction"][2])
        self.assertEqual(0, info["direction"][3], "direction must be nullable")
        self.assertEqual(
            ["id", "category", "name", "lat", "lon", "direction"], [r[1] for r in self.db.execute("PRAGMA table_info(landmark)")]
        )
        bad = self.db.execute(
            "SELECT count(*) FROM landmark WHERE direction < 0 OR direction > 359"
            " OR (direction IS NOT NULL AND typeof(direction) != 'integer')"
        ).fetchone()[0]
        self.assertEqual(0, bad)

    def test_parse_direction(self):
        parse = build_search.parse_direction
        # Numbers: rounded to an integer (half up), then reduced modulo 360.
        for raw, expected in [
            ("0", 0), ("90", 90), ("359", 359), ("360", 0), ("450", 90), ("720", 0),
            ("-90", 270), ("-0", 0), ("+45", 45), ("22.4", 22), ("22.5", 23), ("89.6", 90),
            ("359.5", 0), ("359.4", 359), ("180.", 180), (".5", 1), (" 270 ", 270), ("007", 7),
        ]:
            self.assertEqual(expected, parse(raw), raw)
        # The 16 compass names, any case.
        compass = {
            "N": 0, "NNE": 22, "NE": 45, "ENE": 67, "E": 90, "ESE": 112, "SE": 135, "SSE": 157,
            "S": 180, "SSW": 202, "SW": 225, "WSW": 247, "W": 270, "WNW": 292, "NW": 315, "NNW": 337,
        }
        for raw, expected in compass.items():
            self.assertEqual(expected, parse(raw), raw)
            self.assertEqual(expected, parse(raw.lower()), raw.lower())
        self.assertEqual(22, parse(" nne "))
        # Not one bearing: NULL.
        for raw in [
            None, "", " ", "90-180", "90 - 180", "90;270", "90,270", "N;S", "N-S", "NE;SW", "both", "Both",
            "forward", "backward", "north", "up", "NNNE", "90deg", "90\u00b0", "1e2", "nan", "inf", "--90",
            "9" * 400, "\u0663\u0660",
        ]:
            self.assertIsNone(parse(raw), repr(raw))

    def test_landmark_direction_is_for_cameras_only(self):
        direction = build_search.landmark_direction
        self.assertEqual(90, direction({"direction": "90"}, "alpr_camera"))
        self.assertEqual(270, direction({"direction": "W"}, "speed_camera"))
        self.assertIsNone(direction({}, "alpr_camera"))
        self.assertIsNone(direction({"direction": "both"}, "speed_camera"))
        for category in ["fuel", "stop", "traffic_signals", "school_zone", "church", "bank", "supermarket"]:
            self.assertIsNone(direction({"direction": "90"}, category), category)
            self.assertIsNone(direction({"camera:direction": "90"}, category), category)

    def test_camera_direction_is_the_fallback(self):
        direction = build_search.landmark_direction
        for category in ["alpr_camera", "speed_camera"]:
            # camera:direction alone, in any form the direction tag takes.
            self.assertEqual(135, direction({"camera:direction": "135"}, category))
            self.assertEqual(315, direction({"camera:direction": "NW"}, category))
            self.assertEqual(270, direction({"camera:direction": "-90"}, category))
            self.assertIsNone(direction({"camera:direction": "90;270"}, category))
            self.assertIsNone(direction({"camera:direction": "forward"}, category))
            # Both present: direction wins, even when camera:direction would parse.
            self.assertEqual(90, direction({"direction": "90", "camera:direction": "180"}, category))
            # A direction that isn't one bearing is not overruled by camera:direction.
            self.assertIsNone(direction({"direction": "both", "camera:direction": "180"}, category))
            self.assertIsNone(direction({"direction": "90-180", "camera:direction": "180"}, category))

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
        self.assertEqual("school_zone", cat({"traffic_sign": "US:R2-1[20];US:S5-1"}))
        self.assertEqual("traffic_signals", cat({"highway": "traffic_signals", "traffic_sign": "school_bus"}))
        self.assertIsNone(cat({"amenity": "cafe"}))

    def test_other_plate_readers(self):
        cat = build_search.landmark_category
        # Tolls are cashless, every gantry and booth photographs plates: their own category, which the app counts
        # as plate readers.
        self.assertEqual("toll", cat({"highway": "toll_gantry"}))
        self.assertEqual("toll", cat({"barrier": "toll_booth", "name": "Tappan Zee Toll Plaza"}))
        self.assertEqual("Toll gantry", build_search.landmark_name({"highway": "toll_gantry", "operator": "MTA"}, "toll"))
        self.assertEqual("Toll booth", build_search.landmark_name({"barrier": "toll_booth", "name": "Plaza"}, "toll"))
        self.assertIsNone(build_search.landmark_direction({"highway": "toll_gantry", "direction": "90"}, "toll"))
        # Other words for the type, any case.
        for kind in ["ANPR", "lpr", "alpr"]:
            self.assertEqual("alpr_camera", cat({"man_made": "surveillance", "surveillance:type": kind}), kind)
            self.assertEqual("alpr_camera", cat({"man_made": "surveillance", "camera:type": kind}), kind)
        # Makers that only make plate readers count anywhere.
        for maker in ["Vigilant Solutions", "Leonardo ELSAG", "Genetec AutoVu", "Rekor", "Perceptics", "Neology"]:
            self.assertEqual("alpr_camera", cat({"man_made": "surveillance", "manufacturer": maker}), maker)
        # Makers of all sorts of cameras count only on traffic.
        self.assertIsNone(cat({"man_made": "surveillance", "manufacturer": "Motorola Solutions"}))
        self.assertIsNone(cat({"man_made": "surveillance", "manufacturer": "Genetec", "surveillance:zone": "town"}))
        self.assertEqual("alpr_camera", cat({"man_made": "surveillance", "manufacturer": "Motorola Solutions", "surveillance:zone": "traffic"}))
        # A plain camera is not a plate reader, nor is a road that charges a toll.
        self.assertIsNone(cat({"man_made": "surveillance", "surveillance:type": "camera", "surveillance:zone": "traffic"}))
        self.assertIsNone(cat({"highway": "motorway", "toll": "yes"}))

    def test_red_light_cameras(self):
        cat = build_search.landmark_category
        self.assertEqual("red_light_camera", cat({"highway": "speed_camera", "speed_camera": "traffic_signals"}))
        self.assertEqual("red_light_camera", cat({"highway": "red_light_camera"}))
        self.assertEqual("red_light_camera", cat({"enforcement": "traffic_signals"}))
        self.assertEqual("speed_camera", cat({"highway": "speed_camera"}))
        self.assertEqual("speed_camera", cat({"enforcement": "maxspeed"}))
        # Spoken by category, and its direction kept like a speed camera's.
        self.assertIsNone(build_search.landmark_name({"highway": "red_light_camera", "operator": "City"}, "red_light_camera"))
        self.assertEqual(90, build_search.landmark_direction({"direction": "E"}, "red_light_camera"))

    def test_sign_codes(self):
        self.assertEqual(["s1-1", "r2-1"], build_search.sign_codes("US:S1-1;US:R2-1[25]"))
        self.assertEqual(["school"], build_search.sign_codes(" school "))
        self.assertEqual([], build_search.sign_codes(None))

    def test_meta(self):
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual("test", meta["region"])
        self.assertIn("OpenStreetMap", meta["attribution"])


class TollTest(unittest.TestCase):
    """tolls.osm: every way a toll is mapped becomes one "toll" landmark."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "search-tolls.sqlite")
        build_search.build(os.path.join(HERE, "tolls.osm"), cls.out, "test")
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def test_tolls(self):
        rows = self.db.execute("SELECT category, name, lat, lon, direction FROM landmark ORDER BY lat, lon").fetchall()
        self.assertEqual(
            [
                # Where the ramp ends on the toll road and where the free road starts at its end; not where the
                # street crosses it, nor at the end only a footpath reaches.
                ("toll", "Toll road", 41.0, -73.99, None),
                ("toll", "Toll road", 41.0, -73.97, None),
                # The mapped gantry, and not the join 900 m from it.
                ("toll", "Toll gantry", 42.0, -73.99, None),
                # Drawn as a line, and as a closed way (once, not again as an area).
                ("toll", "Toll gantry", 43.0, -73.999, None),
                ("toll", "Toll booth", 43.101, -73.999, None),
                # The toll enforcement relation's device; the speed one's isn't a toll.
                ("toll", "Toll", 44.0, -74.0, None),
            ],
            rows,
        )

    def test_toll_roads(self):
        self.assertTrue(build_search.is_toll_road({"highway": "motorway", "toll": "yes"}))
        self.assertTrue(build_search.is_toll_road({"highway": "primary", "toll:motorcar": "yes"}))
        self.assertFalse(build_search.is_toll_road({"highway": "motorway", "toll": "no"}))
        self.assertFalse(build_search.is_toll_road({"highway": "motorway", "toll:hgv": "yes"}))
        self.assertFalse(build_search.is_toll_road({"highway": "footway", "toll": "yes"}))
        self.assertTrue(build_search.is_toll_enforcement({"type": "enforcement", "enforcement": "toll"}))
        self.assertFalse(build_search.is_toll_enforcement({"type": "enforcement", "enforcement": "traffic_signals"}))


if __name__ == "__main__":
    unittest.main()
