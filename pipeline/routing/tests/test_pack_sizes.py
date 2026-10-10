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


def tile(level, lat, lon, mb, covers):
    return json.dumps([st.tile_path(level, st.tile_id_at(level, lat, lon)), level, int(mb * 1e6), covers])


class PackSizesTest(unittest.TestCase):
    def test_schemes(self):
        tiles = ps.read_index([
            tile(0, 40.8, -73.9, 10, ["nyc", "hudson", "njn", "md"]),
            tile(1, 40.8, -73.9, 20, ["nyc", "hudson", "njn"]),
            tile(1, 39.5, -76.5, 5, ["md"]),
            tile(2, 40.8, -73.9, 100, ["nyc", "hudson", "njn"]),  # the Bronx and Fort Lee: three regions' tile
            tile(2, 41.6, -73.9, 50, ["hudson"]),
            tile(2, 39.3, -76.6, 30, ["md"]),
            tile(2, 40.0, -82.0, 7, []),  # Ohio: no region
        ])
        hudson = ps.sizes(tiles, ["hudson"])
        self.assertAlmostEqual(35 + 150, hudson["per region"])
        self.assertAlmostEqual(35 + 150, hudson["one copy"])
        self.assertAlmostEqual(10 + 20 + 150, hudson["one copy, L0"])  # Maryland's level 1 stays behind
        both = ps.sizes(tiles, ["nyc", "hudson"])
        self.assertAlmostEqual(35 + 250, both["per region"])  # the shared tile comes twice
        self.assertAlmostEqual(35 + 150, both["one copy"])  # and once
        md = ps.sizes(tiles, ["md"])
        self.assertAlmostEqual(10 + 5 + 30, md["one copy, L0"])

    def test_report_prints_every_choice(self):
        out = io.StringIO()
        ps.report(ps.read_index([tile(0, 40.8, -73.9, 10, ["nyc"])]), log=out)
        self.assertEqual(len(ps.CHOICES) + 1, len(out.getvalue().splitlines()))


if __name__ == "__main__":
    unittest.main()
