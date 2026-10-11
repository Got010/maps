"""Tests for dot_cameras.py: state DOT traffic cameras as landmarks.

The fixture holds three real cameras from 511NY's collection (fetched
2026-10-07 by the feed probe), plus a private one and one without a position.

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

import build_search  # noqa: E402
import dot_cameras  # noqa: E402

FIXTURE = os.path.join(HERE, "dot_cameras.json")


class DotCamerasTest(unittest.TestCase):
    def test_public_cameras_with_a_position(self):
        rows = list(dot_cameras.cameras(FIXTURE))
        self.assertEqual(
            ["SSP at NY135 (Crown Castle) - 1", "SSP at NY135 (Crown Castle) - 2", "NY27A at County Line Rd"],
            [name for name, _, _ in rows],
        )
        self.assertEqual((40.700365, -73.491488), rows[0][1:])

    def test_bounds(self):
        # Only the camera east of -73.45 (NY27A at County Line Rd).
        rows = list(dot_cameras.cameras(FIXTURE, bounds=(-73.45, 40.0, -73.0, 41.0)))
        self.assertEqual(["NY27A at County Line Rd"], [name for name, _, _ in rows])

    def test_not_a_collection(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write('{"healthy": true}')
        try:
            with self.assertRaises(ValueError):
                list(dot_cameras.cameras(f.name))
        finally:
            os.remove(f.name)

    def test_loaded_into_the_landmark_table(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = sqlite3.connect(os.path.join(tmp, "t.sqlite"))
            db.executescript(build_search.SCHEMA)
            collector = build_search.Collector(db)
            self.assertEqual(3, dot_cameras.load(collector, FIXTURE))
            collector.flush()
            rows = db.execute("SELECT category, name, direction FROM landmark ORDER BY id").fetchall()
            db.close()
        self.assertEqual(3, len(rows))
        self.assertTrue(all(category == "dot_camera" and direction is None for category, _, direction in rows))
        self.assertEqual("NY27A at County Line Rd", rows[2][1])



# The app's own traffic fixtures (core/traffic): the same answers the build reads, so a feed change shows in both.
TRAFFIC = os.path.join(HERE, "..", "..", "..", "core", "traffic", "src", "test", "resources")


def traffic(name):
    with open(os.path.join(TRAFFIC, name), encoding="utf-8") as f:
        return json.load(f)


class StateReadersTest(unittest.TestCase):
    """Each state's reader on a real answer from its 511 (fixtures printed by scripts/feed-probe.py)."""

    def test_ibi_sites(self):
        rows = dot_cameras.read_ibi(traffic("ibi/pa-cameras.json"))
        self.assertTrue(rows)
        self.assertEqual(("I-176 E/B @ SR 422 Interchange Berks Co.", 40.30142, -75.90045), rows[0])
        self.assertTrue(dot_cameras.read_ibi(traffic("ibi/ct-cameras.json")))
        self.assertTrue(dot_cameras.read_ibi(traffic("ibi/wi-cameras.json")))

    def test_maryland_and_delaware(self):
        md = dot_cameras.read_chart(traffic("mdde/md-cameras.json"))
        self.assertEqual(("I-270 & Old Hundred Rd (MD 109)(CAM 165)", 39.2773, -77.3236), md[0])
        de = dot_cameras.read_deldot(traffic("mdde/de-cameras.json"))
        self.assertEqual(("DE 1 @ MILFORD NECK ROAD (NORTH OFF)", 38.990931, -75.448625), de[0])

    def test_cars_states(self):
        for name in ("in", "mn", "ia", "ne", "ks"):
            rows = dot_cameras.read_cars(traffic(f"cars/{name}-cameras.json"))
            self.assertTrue(rows, name)
            self.assertTrue(all(lat > 36 and lon < -84 for _, lat, lon in rows), name)
        # Indiana's hidden camera (public false) is left out.
        self.assertNotIn("hidden", [n for n, _, _ in dot_cameras.read_cars(traffic("cars/in-cameras.json"))])

    def test_illinois_keeps_its_own(self):
        doc = traffic("travelmidwest/il-cameras.json")
        rows = dot_cameras.read_geojson(doc, keep=lambda p: str(p.get("id", "")).startswith("IL-"))
        self.assertEqual(3, len(rows))  # Indiana's camera rides along in the file and is not Illinois'
        self.assertEqual(("I-355 at Spring Creek Plaza", 41.57659912109375, -88.00050354003906), rows[0])

    def test_michigan_north_and_south_dakota(self):
        mi = dot_cameras.read_midrive(traffic("mi/cameras.json"))
        self.assertEqual(("11 Mile @ Mound NB", 42.491304, -83.04479), mi[0])
        nd = dot_cameras.read_arcgis(traffic("nd/cameras.json"))
        self.assertEqual("Ray - West (US 2 MP 51.3)", nd[0][0])
        self.assertAlmostEqual(48.341772933829745, nd[0][1])
        sd = dot_cameras.read_geojson(traffic("sd/cameras.json")) + dot_cameras.read_geojson(traffic("sd/rwis.json"))
        self.assertEqual(["Watertown North", "Elsewhere", "Agar"], [n for n, _, _ in sd])
        self.assertEqual((44.95, -97.06), sd[0][1:])  # SD511's coordinates come as strings or numbers alike

    def test_new_york(self):
        with open(FIXTURE, encoding="utf-8") as f:
            self.assertEqual(3, len(dot_cameras.read_geojson(json.load(f))))


class RegionsTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(HERE, "..", "..", "regions.json"), encoding="utf-8") as f:
            self.regions = json.load(f)["regions"]

    def test_every_covered_state_has_a_list_or_a_reason(self):
        states = set(dot_cameras.STATE_OF_SOURCE.values())
        self.assertEqual(19, len(states))
        self.assertEqual(states, set(dot_cameras.FETCHERS) | set(dot_cameras.NO_LIST))
        self.assertFalse(set(dot_cameras.FETCHERS) & set(dot_cameras.NO_LIST))

    def test_states_of_a_region(self):
        self.assertEqual(["MD", "DE", "DC"], dot_cameras.states_of("md", self.regions))
        self.assertEqual(["NY", "NJ"], dot_cameras.states_of("nys", self.regions))
        self.assertEqual(["OH"], dot_cameras.states_of("ohn", self.regions))
        for name, region in self.regions.items():
            for state in dot_cameras.states_of(name, self.regions):
                self.assertIn(state, set(dot_cameras.STATE_OF_SOURCE.values()), name)

    def test_fetch_region_writes_one_collection_and_skips_failures(self):
        def broken():
            raise OSError("refused")

        fetchers = {"MD": lambda: [("I-270", 39.2773, -77.3236)], "DE": broken}
        with tempfile.TemporaryDirectory() as tmp:
            out = os.path.join(tmp, "c.json")
            counts = dot_cameras.fetch_region("md", self.regions, out, fetchers=fetchers)
            self.assertEqual({"MD": 1}, counts)  # Delaware failed, DC has no list
            rows = list(dot_cameras.cameras(out))
        self.assertEqual([("I-270", 39.2773, -77.3236)], rows)


if __name__ == "__main__":
    unittest.main()
