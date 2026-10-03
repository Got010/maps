"""Tests for states.py. Run: python3 -m unittest discover -s pipeline/search/tests"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from states import state_at  # noqa: E402


class StateAtTest(unittest.TestCase):
    def test_new_jersey(self):
        for name, lat, lon in [
            ("Newark", 40.7357, -74.1724),
            ("Jersey City", 40.7178, -74.0431),
            ("Hoboken", 40.7439, -74.0324),
            ("Hackensack", 40.8859, -74.0435),
            ("Paterson", 40.9168, -74.1718),
            ("Morristown", 40.7968, -74.4815),
            ("Elizabeth", 40.6640, -74.2107),
            ("Perth Amboy", 40.5068, -74.2654),
        ]:
            self.assertEqual("NJ", state_at(lat, lon), name)

    def test_new_york(self):
        for name, lat, lon in [
            ("Manhattan", 40.7484, -73.9857),
            ("Chelsea Piers", 40.7470, -74.0080),
            ("Washington Heights", 40.8500, -73.9390),
            ("Brooklyn", 40.6782, -73.9442),
            ("Staten Island, St. George", 40.6437, -74.0765),
            ("Staten Island, Tottenville", 40.5057, -74.2444),
            ("Yonkers", 40.9312, -73.8988),
            ("Suffern", 41.1148, -74.1496),
            ("Albany", 42.6526, -73.7562),
            ("Buffalo", 42.8864, -78.8784),
        ]:
            self.assertEqual("NY", state_at(lat, lon), name)

    def test_default(self):
        self.assertEqual("XX", state_at(42.65, -73.75, default="XX"))


if __name__ == "__main__":
    unittest.main()
