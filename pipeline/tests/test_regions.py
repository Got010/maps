"""Checks pipeline/regions.json. Run: python3 -m unittest discover -s pipeline/tests"""

import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class RegionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(os.path.dirname(HERE), "regions.json")) as f:
            cls.regions = json.load(f)["regions"]

    def test_ids_fit_pack_names(self):
        # PackKind.regionFromName and the release manifest accept [a-z0-9]+.
        for name in self.regions:
            self.assertRegex(name, r"^[a-z0-9]+$")

    def test_bounds_are_sane(self):
        for name, region in self.regions.items():
            minlon, minlat, maxlon, maxlat = region["bounds"]
            self.assertLess(minlon, maxlon, name)
            self.assertLess(minlat, maxlat, name)
            self.assertTrue(-80 <= minlon and maxlon <= -71, name)
            self.assertTrue(40 <= minlat and maxlat <= 45.1, name)
            self.assertIsInstance(region["basemap"], bool)
            self.assertTrue(region["name"])

    def test_basemaps_cover_the_state(self):
        state = self.regions["nys"]["bounds"]
        self.assertFalse(self.regions["nys"]["basemap"])
        basemaps = [r["bounds"] for r in self.regions.values() if r["basemap"]]
        self.assertEqual(5, len(basemaps))
        # Every corner and the midpoint of each edge of the state box lies in some basemap region,
        # and a few cities too.
        points = [
            (-73.9857, 40.7484),  # Manhattan
            (-73.1, 40.8),  # Suffolk
            (-73.9, 41.7),  # Poughkeepsie
            (-73.7562, 42.6526),  # Albany
            (-73.45, 44.7),  # Plattsburgh
            (-78.8784, 42.8864),  # Buffalo
            (-77.6088, 43.1566),  # Rochester
            (-76.1474, 43.0481),  # Syracuse
            (-75.9180, 42.0987),  # Binghamton
            (-75.2327, 43.1009),  # Utica
            (-75.9108, 43.9748),  # Watertown
        ]
        for lon, lat in points:
            self.assertTrue(
                any(b[0] <= lon <= b[2] and b[1] <= lat <= b[3] for b in basemaps),
                f"{lon},{lat} is in no basemap region",
            )
            self.assertTrue(state[0] <= lon <= state[2] and state[1] <= lat <= state[3])


if __name__ == "__main__":
    unittest.main()
