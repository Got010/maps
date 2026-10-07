"""Tests for dot_cameras.py: state DOT traffic cameras as landmarks.

The fixture holds three real cameras from 511NY's collection (fetched
2026-10-07 by the feed probe), plus a private one and one without a position.

Run: python3 -m unittest discover -s pipeline/search/tests
"""

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

    def test_new_york_source(self):
        self.assertTrue(dot_cameras.SOURCES["NY"].startswith("https://"))


if __name__ == "__main__":
    unittest.main()
