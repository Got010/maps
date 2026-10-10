"""Checks pipeline/regions.json. Run: python3 -m unittest discover -s pipeline/tests"""

import json
import os
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))

SOURCES = {"new-york", "new-jersey", "connecticut", "pennsylvania", "maryland", "delaware", "district-of-columbia"}  # fetch_source


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
            self.assertTrue(-80.6 <= minlon and maxlon <= -71, name)
            self.assertTrue(37.8 <= minlat and maxlat <= 45.1, name)
            self.assertIsInstance(region["basemap"], bool)
            self.assertTrue(region["name"])

    def test_basemaps_cover_the_state(self):
        state = self.regions["nys"]["bounds"]
        self.assertFalse(self.regions["nys"]["basemap"])
        offered = {n: r for n, r in self.regions.items() if r["basemap"] and not r.get("planned")}
        basemaps = [r["bounds"] for r in offered.values()]
        self.assertEqual(6, len(basemaps))
        # Every basemap region the app offers lies inside the routing and search region.
        for name, region in offered.items():
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
        for name, region in self.regions.items():
            for lon, lat in region.get("area", []):
                self.assertTrue(in_box(region["bounds"], lon, lat), f"{name} {lon},{lat}")
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
            # Northern New Jersey reaches the Delaware River.
            "Phillipsburg, NJ": (-75.1902, 40.6937, "njn"),
            "Hackettstown, NJ": (-74.8291, 40.8540, "njn"),
            "Newton, NJ": (-74.7527, 41.0582, "njn"),
            # Planned regions: Pennsylvania across the river.
            "Easton, PA": (-75.2207, 40.6884, "pae"),
            "Milford, PA": (-74.8024, 41.3223, "pae"),  # inside Hudson Valley's box
            "Stroudsburg, PA": (-75.1946, 40.9868, "pae"),
            "Scranton": (-75.6624, 41.4090, "pae"),
            "Allentown": (-75.4714, 40.6023, "pae"),
            "Philadelphia": (-75.1636, 39.9526, "pae"),
            "Morrisville, PA": (-74.7880, 40.2115, "pae"),
            "Chester, PA": (-75.3557, 39.8496, "pae"),
            "Harrisburg": (-76.8867, 40.2732, "pae"),
            "Pittsburgh": (-79.9959, 40.4406, "paw"),
            "Erie": (-80.0852, 42.1292, "paw"),
            "State College": (-77.8600, 40.7934, "paw"),
            "Sayre, PA": (-76.5155, 41.9790, "pae"),
            "Waverly, NY": (-76.5277, 42.0103, "western"),
            # Southern New Jersey.
            "Trenton": (-74.7597, 40.2171, "njs"),
            "Camden": (-75.1196, 39.9259, "njs"),
            "Atlantic City": (-74.4229, 39.3643, "njs"),
            "Cape May": (-74.9060, 38.9351, "njs"),
            "Penns Grove, NJ": (-75.4680, 39.7290, "njs"),
            # Connecticut, inside Long Island's and Hudson Valley's boxes.
            "Stamford": (-73.5387, 41.0534, "ct"),
            "Greenwich": (-73.6285, 41.0262, "ct"),
            "Danbury": (-73.4540, 41.3948, "ct"),
            "New Haven": (-72.9279, 41.3083, "ct"),
            "Hartford": (-72.6734, 41.7658, "ct"),
            "Groton": (-72.0784, 41.3501, "ct"),
            "Port Chester, NY": (-73.6657, 41.0018, ["li", "hudson"]),  # both New York boxes, not Connecticut
            "Brewster, NY": (-73.6171, 41.3973, "hudson"),
            "Port Jefferson, NY": (-73.0693, 40.9465, "li"),
            "Orient Point, NY": (-72.2892, 41.1590, "li"),
            "Fishers Island, NY": (-72.0090, 41.2620, []),  # north of Long Island's box, and not Connecticut
            # Maryland, Delaware and DC.
            "Baltimore": (-76.6122, 39.2904, "md"),
            "Annapolis": (-76.4922, 38.9784, "md"),
            "Washington, DC": (-77.0369, 38.9072, "md"),
            "Wilmington, DE": (-75.5466, 39.7447, "md"),
            "Dover, DE": (-75.5244, 39.1582, "md"),
            "Ocean City, MD": (-75.0849, 38.3365, "md"),
            "Hagerstown": (-77.7200, 39.6418, "md"),
            "Cumberland, MD": (-78.7625, 39.6528, "md"),
            "Gettysburg, PA": (-77.2311, 39.8309, "paw"),
            "York, PA": (-76.7277, 39.9626, "pae"),
        }
        for name, (lon, lat, expected) in cases.items():
            self.assertEqual(expected if isinstance(expected, list) else [expected], self.basemaps_at(lon, lat), name)


if __name__ == "__main__":
    unittest.main()
