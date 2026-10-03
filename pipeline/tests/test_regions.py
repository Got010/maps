"""Checks pipeline/regions.json. Run: python3 -m unittest discover -s pipeline/tests"""

import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

SOURCES = {"new-york", "new-jersey"}  # what fetch_source in lib/common.sh knows


def in_box(bounds, lon, lat):
    return bounds[0] <= lon <= bounds[2] and bounds[1] <= lat <= bounds[3]


def in_area(area, lon, lat):
    """Ray casting; the same as Region.insideArea in core/packs Regions.kt."""
    inside = False
    previous = area[-1]
    for point in area:
        if (point[1] > lat) != (previous[1] > lat) and (
            lon < (previous[0] - point[0]) * (lat - point[1]) / (previous[1] - point[1]) + point[0]
        ):
            inside = not inside
        previous = point
    return inside


class RegionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(os.path.dirname(HERE), "regions.json")) as f:
            cls.regions = json.load(f)["regions"]

    def basemaps_at(self, lon, lat):
        """The basemap regions a point belongs to, like Regions.basemapsAt in Regions.kt."""
        found = [
            name
            for name, r in self.regions.items()
            if r["basemap"] and in_box(r["bounds"], lon, lat) and ("area" not in r or in_area(r["area"], lon, lat))
        ]
        return [n for n in found if "area" in self.regions[n]] or found

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
        self.assertEqual(6, len(basemaps))
        # Every basemap region lies inside the routing and search region.
        for name, region in self.regions.items():
            b = region["bounds"]
            self.assertTrue(state[0] <= b[0] and b[2] <= state[2] and state[1] <= b[1] and b[3] <= state[3], name)
        # A few cities lie in some basemap region and in the state box.
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
            (-74.1724, 40.7357),  # Newark
            (-74.0431, 40.7178),  # Jersey City
            (-74.0435, 40.8859),  # Hackensack
            (-74.4815, 40.7968),  # Morristown
            (-74.4518, 40.4862),  # New Brunswick
        ]
        for lon, lat in points:
            self.assertTrue(any(in_box(b, lon, lat) for b in basemaps), f"{lon},{lat} is in no basemap region")
            self.assertTrue(in_box(state, lon, lat))

    def test_northern_new_jersey(self):
        njn = self.regions["njn"]
        self.assertTrue(njn["basemap"])
        self.assertEqual({"new-jersey": "self"}, njn["sources"])
        # The area polygon stays inside the box it refines.
        for lon, lat in njn["area"]:
            self.assertTrue(in_box(njn["bounds"], lon, lat), f"{lon},{lat}")
        # Routing and search take New York whole plus New Jersey cut to njn's box.
        self.assertEqual({"new-york": None, "new-jersey": "njn"}, self.regions["nys"]["sources"])

    def test_sources_are_known(self):
        for name, region in self.regions.items():
            for source, clip in region.get("sources", {"new-york": "self"}).items():
                self.assertIn(source, SOURCES, name)
                self.assertTrue(clip is None or clip == "self" or clip in self.regions, name)

    def test_overlaps_resolve_to_the_right_region(self):
        cases = {
            # New Jersey side of the Hudson: inside NYC's box, but njn's.
            "Newark": (-74.1724, 40.7357, "njn"),
            "Jersey City": (-74.0431, 40.7178, "njn"),
            "Hoboken": (-74.0324, 40.7439, "njn"),
            "Hackensack": (-74.0435, 40.8859, "njn"),
            "Paterson": (-74.1718, 40.9168, "njn"),
            "Elizabeth": (-74.2107, 40.6640, "njn"),
            "Bayonne": (-74.1143, 40.6687, "njn"),
            "Perth Amboy": (-74.2654, 40.5068, "njn"),
            "Fort Lee": (-73.9701, 40.8509, "njn"),
            "Morristown": (-74.4815, 40.7968, "njn"),
            "Ridgewood": (-74.1165, 40.9793, "njn"),  # also inside Hudson Valley's box
            # New York side.
            "Manhattan": (-73.9857, 40.7484, "nyc"),
            "Chelsea Piers": (-74.0080, 40.7470, "nyc"),
            "Washington Heights": (-73.9390, 40.8500, "nyc"),
            "Brooklyn": (-73.9442, 40.6782, "nyc"),
            "St. George, Staten Island": (-74.0765, 40.6437, "nyc"),
            "Tottenville, Staten Island": (-74.2444, 40.5057, "nyc"),
            "Great Kills, Staten Island": (-74.1510, 40.5540, "nyc"),
            "Yonkers": (-73.8988, 40.9312, "hudson"),
            "Nyack": (-73.9179, 41.0906, "hudson"),
            "Suffern, NY": (-74.1496, 41.1148, "hudson"),
            "Port Jervis, NY": (-74.69, 41.375, "hudson"),
        }
        for name, (lon, lat, expected) in cases.items():
            self.assertEqual([expected], self.basemaps_at(lon, lat), name)


if __name__ == "__main__":
    unittest.main()
