import gzip
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, ".."))

import forecast_pack as fp  # noqa: E402


def fixture(name):  # noqa: D103
    with open(os.path.join(HERE, name)) as f:
        return json.load(f)


# gridpoints_aly_72_63.json is the first period of the real answer for Albany
# (api.weather.gov/gridpoints/ALY/72,63/forecast, 2026-10-07, from the feed probe). The night period below
# follows the same shape, with the two-code icon the NWS uses when a period changes partway.
NIGHT = {
    "number": 2, "name": "Tonight", "startTime": "2026-10-07T18:00:00-04:00", "endTime": "2026-10-08T06:00:00-04:00",
    "isDaytime": False, "temperature": 51, "temperatureUnit": "F",
    "probabilityOfPrecipitation": {"unitCode": "wmoUnit:percent", "value": 22},
    "icon": "https://api.weather.gov/icons/land/night/sct/rain_showers,20?size=medium",
    "shortForecast": "Partly Cloudy then Slight Chance Rain Showers",
}


class ForecastPackTest(unittest.TestCase):
    def test_lattice_covers_the_box(self):
        pts = fp.lattice((-74.0, 40.0, -73.4, 40.6), 0.3)
        self.assertEqual(9, len(pts))
        self.assertEqual((40.0, -74.0), pts[0])
        self.assertEqual((40.6, -73.4), pts[-1])
        # The statewide box at the default step.
        with open(os.path.join(HERE, "..", "..", "regions.json")) as f:
            bounds = tuple(json.load(f)["regions"]["nys"]["bounds"])
        self.assertEqual(432, len(fp.lattice(bounds)))

    def test_the_pack_covers_every_basemap_region(self):
        # Since October 2026 the lattice spans every covered state, keeping the points some basemap region holds.
        with open(os.path.join(HERE, "..", "..", "regions.json")) as f:
            regions = json.load(f)["regions"]
        kept = fp.covered([
            (39.95, -75.17),  # Philadelphia
            (39.29, -76.61),  # Baltimore
            (38.90, -77.04),  # Washington, DC
            (41.77, -72.67),  # Hartford
            (42.65, -73.76),  # Albany
            (42.36, -71.06),  # Boston: no region
            (39.40, -78.90),  # West Virginia, inside Maryland's box but not its area
            (40.00, -81.00),  # Ohio
        ], regions)
        self.assertEqual([(39.95, -75.17), (39.29, -76.61), (38.90, -77.04), (41.77, -72.67), (42.65, -73.76)], kept)
        pts = fp.covered(fp.lattice(tuple(regions["all"]["bounds"])), regions)
        self.assertEqual(477, len(pts))

    def test_icon_codes(self):
        self.assertEqual("skc", fp.icon_code("https://api.weather.gov/icons/land/day/skc?size=medium"))
        self.assertEqual("sct", fp.icon_code("https://api.weather.gov/icons/land/night/sct/rain_showers,20?size=medium"))
        self.assertEqual("rain_showers", fp.icon_code("https://api.weather.gov/icons/land/day/rain_showers,40?size=medium"))
        self.assertIsNone(fp.icon_code(None))
        self.assertIsNone(fp.icon_code("https://example.com/x.png"))

    def test_periods_from_the_real_answer(self):
        real = fixture("gridpoints_aly_72_63.json")
        real["properties"]["periods"].append(NIGHT)
        periods = fp.periods_of(real)
        self.assertEqual(2, len(periods))
        self.assertEqual([1791396000, 1791410400, 1, 66, 2, "skc", "This Afternoon", "Sunny"], periods[0])
        self.assertEqual("sct", periods[1][5])
        self.assertEqual(0, periods[1][2])
        self.assertEqual(22, periods[1][4])

    def test_a_whole_real_answer(self):
        # gridpoints_buf_77_43.json: a whole real answer (BUF 77,43, south of Rochester, 2026-10-08 00:41Z, printed
        # by this workflow's branch run): 14 periods, starting with tonight.
        periods = fp.periods_of(fixture("gridpoints_buf_77_43.json"))
        self.assertEqual(14, len(periods))
        self.assertEqual([1791417600, 1791453600, 0, 51, 24, "rain_showers", "Tonight", "Slight Chance Rain Showers"], periods[0])
        self.assertEqual("few", periods[1][5])  # "day/few/rain_showers,20": the period starts sunny
        self.assertEqual("Columbus Day", periods[9][6])
        self.assertEqual(91, periods[7][4])

    def test_build_skips_points_without_a_forecast_and_shares_cells(self):
        answers = {
            "https://api.weather.gov/points/42.6,-73.8": {"properties": {"gridId": "ALY", "gridX": 72, "gridY": 63}},
            "https://api.weather.gov/points/42.6,-73.7": {"properties": {"gridId": "ALY", "gridX": 72, "gridY": 63}},
            "https://api.weather.gov/points/43.5,-77.5": None,  # Lake Ontario: 404
            "https://api.weather.gov/gridpoints/ALY/72,63/forecast": fixture("gridpoints_aly_72_63.json"),
        }
        asked = []

        def fetch(url):
            asked.append(url)
            return answers[url]

        cache = {}
        result = fp.build([(42.6, -73.8), (42.6, -73.7), (43.5, -77.5)], cache, fetch=fetch, workers=1)
        self.assertEqual(2, len(result["points"]))
        self.assertEqual(1, result["cells"])
        self.assertEqual(1, asked.count("https://api.weather.gov/gridpoints/ALY/72,63/forecast"))
        self.assertEqual({"42.6,-73.8": ["ALY", 72, 63], "42.6,-73.7": ["ALY", 72, 63], "43.5,-77.5": None}, cache)
        # A second run asks only for forecasts: the cells come from the cache.
        asked.clear()
        fp.build([(42.6, -73.8), (43.5, -77.5)], cache, fetch=fetch, workers=1)
        self.assertEqual(["https://api.weather.gov/gridpoints/ALY/72,63/forecast"], asked)

    def test_a_failing_point_is_retried_next_run(self):
        def fetch(url):
            raise TimeoutError("slow")

        cache = {}
        result = fp.build([(42.6, -73.8)], cache, fetch=fetch, workers=1)
        self.assertEqual([], result["points"])
        self.assertEqual({}, cache)

    def test_output_is_small_gzip_json(self):
        pack = {"v": 1, "points": [{"lat": 42.6, "lon": -73.8, "periods": fp.periods_of(fixture("gridpoints_aly_72_63.json"))}]}
        raw = json.dumps(pack, separators=(",", ":")).encode()
        self.assertEqual(pack, json.loads(gzip.decompress(gzip.compress(raw))))


if __name__ == "__main__":
    unittest.main()
