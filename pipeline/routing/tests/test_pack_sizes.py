"""Tests for pack_sizes.py. Run: python3 -m unittest discover -s pipeline/routing/tests"""

import io
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import pack_sizes as ps  # noqa: E402
import split_tiles as st  # noqa: E402


def tile(level, lat, lon, mb, packs):
    return json.dumps([st.tile_path(level, st.tile_id_at(level, lat, lon)), level, int(mb * 1e6), packs])


class PackSizesTest(unittest.TestCase):
    def test_schemes(self):
        tiles = ps.read_index([
            tile(0, 40.8, -73.9, 10, ["main"]),
            tile(1, 40.8, -73.9, 20, ["main"]),
            tile(1, 39.5, -76.5, 5, ["main"]),
            tile(2, 40.8, -73.9, 100, ["nyc", "hudson", "njn"]),  # the Bronx and Fort Lee: three regions' tile
            tile(2, 41.6, -73.9, 50, ["hudson"]),
            tile(2, 39.3, -76.6, 30, ["md"]),
            tile(2, 40.0, -82.0, 7, []),  # Ohio: no region
        ])
        hudson = ps.sizes(tiles, ["hudson"])
        self.assertAlmostEqual(35 + 150, hudson["regions"])
        self.assertAlmostEqual(35 + 150, hudson["kept"])
        both = ps.sizes(tiles, ["nyc", "hudson"])
        self.assertAlmostEqual(35 + 250, both["regions"])  # the shared tile comes twice
        self.assertAlmostEqual(35 + 150, both["kept"])  # and is kept once
        self.assertAlmostEqual(35 + 150, both["states"])  # New York's regions are one state pack
        # Only level 0 shared; the level 1 tile of each chosen degree square comes with it.
        self.assertAlmostEqual(10 + 20 + 150, both["blocks+1"])
        md = ps.sizes(tiles, ["md"])
        self.assertAlmostEqual(35 + 30, md["blocks 1"])

    def test_report_prints_every_choice(self):
        out = io.StringIO()
        ps.report(ps.read_index([tile(0, 40.8, -73.9, 10, ["main"])]), log=out)
        self.assertEqual(len(ps.CHOICES) + 1, len(out.getvalue().splitlines()))


if __name__ == "__main__":
    unittest.main()
