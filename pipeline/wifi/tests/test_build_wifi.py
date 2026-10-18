"""Tests for the Wi-Fi pack builder. Run: python3 -m unittest discover -s pipeline/wifi/tests

The rules are pure functions of an object's tags, so most tests need no pyosmium; the one that reads the
fixture file (synthetic points and generic names) skips itself without it."""

import json
import math
import os
import sqlite3
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
WIFI = os.path.dirname(HERE)
sys.path.insert(0, WIFI)

import build_wifi  # noqa: E402
from build_wifi import ChainsError, classify, parse_chains  # noqa: E402

try:
    import osmium  # noqa: F401

    HAVE_OSMIUM = True
except ImportError:
    HAVE_OSMIUM = False

CHAINS = build_wifi.load_chains(os.path.join(HERE, "chains-test.json"))
COFFEE = "Q37158"


def verdict(**tags):
    """classify() of tags given as keyword arguments; "__" stands for ":" in a key."""
    return classify({k.replace("__", ":"): v for k, v in tags.items()}, CHAINS)


def summary(v):
    return None if v is None else (v.tier, v.fee, v.category)


class TierTest(unittest.TestCase):
    def test_tier_1_tagged_free(self):
        for ia in ("wlan", "yes", "wifi"):
            self.assertEqual((1, "no", "cafe"), summary(verdict(amenity="cafe", name="A", internet_access=ia,
                                                               internet_access__fee="no")), ia)

    def test_tier_1_fee_no_without_an_internet_fee_tag(self):
        self.assertEqual((1, "no", "cafe"), summary(verdict(amenity="cafe", name="A", internet_access="yes", fee="no")))

    def test_internet_fee_wins_over_fee(self):
        # internet_access:fee=customers with fee=no is still "customers"; fee=yes beside internet_access:fee=no is free.
        self.assertEqual((1, "customers", "cafe"), summary(verdict(
            amenity="cafe", name="A", internet_access="wlan", internet_access__fee="customers", fee="no")))
        self.assertEqual((1, "no", "cafe"), summary(verdict(
            amenity="cafe", name="A", internet_access="wlan", internet_access__fee="no", fee="yes")))

    def test_tier_1_free_for_customers(self):
        self.assertEqual((1, "customers", "cafe"), summary(verdict(
            amenity="cafe", name="A", internet_access="wlan", internet_access__fee="customers")))

    def test_tier_2_wifi_with_no_fee_stated(self):
        self.assertEqual((2, "unknown", "cafe"), summary(verdict(amenity="cafe", name="A", internet_access="wlan")))

    def test_tier_2_takes_any_other_fee_value(self):
        for fee in ("yes", "unknown", "maybe"):
            self.assertEqual((2, "unknown", "cafe"), summary(verdict(
                amenity="cafe", name="A", internet_access="wlan", fee=fee)), fee)

    def test_an_internet_fee_value_nobody_understands_claims_neither_tier(self):
        self.assertIsNone(verdict(amenity="cafe", name="A", internet_access="wlan", internet_access__fee="donation"))
        # ... but a library still falls through to tier 4.
        self.assertEqual((4, "unknown", "library"), summary(verdict(
            amenity="library", name="A", internet_access="wlan", internet_access__fee="donation")))

    def test_values_are_read_case_and_space_insensitively(self):
        self.assertEqual((1, "no", "cafe"), summary(verdict(
            amenity="cafe", name="A", internet_access=" WLAN ", internet_access__fee="No")))

    def test_wired_internet_is_not_wifi(self):
        self.assertIsNone(verdict(amenity="cafe", name="A", internet_access="wired"))

    def test_tier_3_chain_by_wikidata_only(self):
        v = verdict(amenity="cafe", name="Anything", brand__wikidata=COFFEE)
        self.assertEqual((3, "customers", "cafe"), summary(v))
        self.assertEqual(COFFEE, v.brand_wikidata)
        # The name alone never matches a chain.
        self.assertIsNone(verdict(amenity="cafe", name="Example Coffee"))
        self.assertIsNone(verdict(amenity="cafe", name="Example Coffee", brand__wikidata="Q99999999"))

    def test_tier_3_fee_comes_from_the_config(self):
        chains = parse_chains(json.dumps({"chains": [{"name": "X", "wikidata": ["Q1"], "fee": "no"}]}))
        self.assertEqual("no", classify({"shop": "supermarket", "name": "X", "brand:wikidata": "Q1"}, chains).fee)
        chains = parse_chains(json.dumps({"chains": [{"name": "X", "wikidata": ["Q1"]}]}))
        self.assertEqual("customers", classify({"shop": "supermarket", "name": "X", "brand:wikidata": "Q1"}, chains).fee)

    def test_a_chain_with_several_qids(self):
        self.assertEqual(3, verdict(shop="supermarket", name="A", brand__wikidata="Q7963529").tier)
        self.assertEqual(3, verdict(shop="department_store", name="A", brand__wikidata="Q483551").tier)

    def test_tier_4_likely(self):
        cases = [
            (dict(amenity="library"), "library"),
            (dict(tourism="hotel"), "hotel"),
            (dict(tourism="motel"), "motel"),
            (dict(highway="rest_area"), "rest_area"),
            (dict(tourism="information", information="office"), "welcome_center"),
            (dict(tourism="information", information="visitor_centre", operator__wikidata="Q308439"), "visitor_centre"),
            (dict(tourism="information", information="visitor_centre", operator="Example National Park Service Unit"),
             "visitor_centre"),
            (dict(tourism="information", information="visitor_centre", operator="EXAMPLE NATIONAL PARK"),
             "visitor_centre"),
        ]
        for tags, category in cases:
            self.assertEqual((4, "unknown", category), summary(verdict(name="A", **tags)), tags)

    def test_tier_4_leaves_out_what_is_not_likely(self):
        for tags in (
            dict(tourism="information", information="board"),
            dict(tourism="information", information="guidepost"),
            dict(tourism="information", information="visitor_centre", operator="Example Town"),
            dict(tourism="information", information="visitor_centre"),
            dict(tourism="information"),
            dict(tourism="hostel"),
            dict(amenity="cafe"),
        ):
            self.assertIsNone(verdict(name="A", **tags), tags)

    def test_tier_4_may_be_nameless_but_the_others_may_not(self):
        v = verdict(highway="rest_area")
        self.assertEqual((4, None), (v.tier, v.name))
        self.assertIsNone(verdict(amenity="cafe", internet_access="wlan"))
        self.assertIsNone(verdict(amenity="cafe", brand__wikidata=COFFEE))

    def test_name_falls_back_to_brand(self):
        v = verdict(shop="books", brand="Example Brand", internet_access="wlan")
        self.assertEqual("Example Brand", v.name)
        self.assertEqual("Named", verdict(shop="books", name="Named", brand="Example Brand", internet_access="wlan").name)

    def test_category_is_the_osm_value(self):
        for tags, category in (
            (dict(amenity="cafe", shop="books"), "cafe"),
            (dict(shop="books", tourism="hotel"), "books"),
            (dict(tourism="attraction"), "attraction"),
            (dict(highway="services"), "services"),
            (dict(office="company"), "company"),
            (dict(leisure="park"), "place"),
        ):
            self.assertEqual(category, verdict(name="A", internet_access="wlan", **tags).category, tags)

    def test_hours_website_and_wikidata_are_copied(self):
        v = verdict(amenity="cafe", name="A", internet_access="wlan", opening_hours="Mo-Fr 08:00-17:00",
                    website="https://example.org/a", brand__wikidata=COFFEE)
        self.assertEqual(("Mo-Fr 08:00-17:00", "https://example.org/a", COFFEE),
                         (v.opening_hours, v.website, v.brand_wikidata))
        v = verdict(amenity="cafe", name="A", internet_access="wlan", contact__website="https://example.org/c")
        self.assertEqual(("https://example.org/c", None, None), (v.website, v.opening_hours, v.brand_wikidata))


class ExclusionTest(unittest.TestCase):
    def test_a_fee_excludes_everywhere(self):
        for tags in (
            dict(amenity="cafe"),
            dict(amenity="library"),
            dict(tourism="hotel"),
            dict(highway="rest_area"),
            dict(amenity="cafe", brand__wikidata=COFFEE),
            dict(amenity="cafe", brand__wikidata=COFFEE, fee="no"),
        ):
            self.assertIsNone(verdict(name="A", internet_access="wlan", internet_access__fee="yes", **tags), tags)
        # ... even without internet_access itself (a library says the Wi-Fi costs money).
        self.assertIsNone(verdict(name="A", amenity="library", internet_access__fee="yes"))

    def test_no_internet_excludes_everywhere(self):
        for tags in (dict(amenity="library"), dict(tourism="motel"), dict(amenity="cafe", brand__wikidata=COFFEE),
                     dict(amenity="cafe", fee="no")):
            self.assertIsNone(verdict(name="A", internet_access="no", **tags), tags)

    def test_terminals_are_not_wifi(self):
        for tags in (dict(amenity="library"), dict(amenity="cafe", brand__wikidata=COFFEE)):
            self.assertIsNone(verdict(name="A", internet_access="terminal", **tags), tags)

    def test_disused_and_abandoned_only(self):
        self.assertIsNone(verdict(name="A", disused__amenity="cafe", internet_access="wlan"))
        self.assertIsNone(verdict(name="A", abandoned__shop="books", internet_access="wlan", internet_access__fee="no"))
        self.assertIsNone(verdict(name="A", disused__amenity="library"))
        # A live feature key beside a leftover disused: one is fine.
        self.assertEqual(2, verdict(name="A", amenity="cafe", disused__shop="books", internet_access="wlan").tier)

    def test_untagged_and_unrelated(self):
        self.assertIsNone(verdict())
        self.assertIsNone(verdict(highway="residential", name="A"))
        self.assertIsNone(verdict(amenity="bench"))


class PrecedenceTest(unittest.TestCase):
    def test_a_tagged_chain_takes_tier_1(self):
        self.assertEqual((1, "no", "cafe"), summary(verdict(
            amenity="cafe", name="A", brand__wikidata=COFFEE, internet_access="wlan", internet_access__fee="no")))

    def test_a_chain_tagged_with_a_fee_is_out(self):
        self.assertIsNone(verdict(amenity="cafe", name="A", brand__wikidata=COFFEE, internet_access="wlan",
                                  internet_access__fee="yes"))

    def test_tagged_without_a_fee_beats_the_chain(self):
        self.assertEqual((2, "unknown", "cafe"), summary(verdict(
            amenity="cafe", name="A", brand__wikidata=COFFEE, internet_access="wlan")))

    def test_a_tagged_library_takes_the_tagged_tier(self):
        self.assertEqual((2, "unknown", "library"), summary(verdict(
            amenity="library", name="A", internet_access="wlan")))
        self.assertEqual((1, "customers", "hotel"), summary(verdict(
            tourism="hotel", name="A", internet_access="wlan", internet_access__fee="customers")))

    def test_a_chain_beats_a_category(self):
        self.assertEqual(3, verdict(tourism="hotel", name="A", brand__wikidata=COFFEE).tier)


class ClusterTest(unittest.TestCase):
    @staticmethod
    def reference_tile(lat, lon, zoom):
        """The textbook slippy-map formula, written the other way round from tile_of."""
        n = 2 ** zoom
        x = int((lon + 180) / 360 * n)
        lat_r = math.radians(lat)
        y = int((1 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2 * n)
        return x, y

    def test_tile_of_known_points(self):
        self.assertEqual((0, 0), build_wifi.tile_of(60.0, -170.0, 0))
        self.assertEqual((0, 0), build_wifi.tile_of(85.0, -179.9, 3))
        self.assertEqual((7, 7), build_wifi.tile_of(-85.0, 179.9, 3))
        # The Greenwich meridian and the equator are the corner between four tiles.
        self.assertEqual((4, 4), build_wifi.tile_of(0.0, 0.0, 3))
        self.assertEqual((3, 3), build_wifi.tile_of(0.0001, -0.0001, 3))
        self.assertEqual((4, 4), build_wifi.tile_of(-0.0001, 0.0001, 3))

    def test_tile_of_matches_the_textbook_formula_at_two_zooms(self):
        for lat, lon in ((38.5, -98.0), (40.0, -100.0), (47.6, -122.3), (25.8, -80.2), (44.9, -68.7)):
            for zoom in (3, 9):
                self.assertEqual(self.reference_tile(lat, lon, zoom), build_wifi.tile_of(lat, lon, zoom), (lat, lon, zoom))

    def test_tile_of_concrete_cells(self):
        self.assertEqual((1, 3), build_wifi.tile_of(38.5, -98.0, 3))
        self.assertEqual((116, 196), build_wifi.tile_of(38.5, -98.0, 9))
        self.assertEqual((113, 193), build_wifi.tile_of(40.0, -100.0, 9))

    def test_tile_of_clamps_the_edges(self):
        self.assertEqual((7, 7), build_wifi.tile_of(-90.0, 180.0, 3))
        self.assertEqual((0, 0), build_wifi.tile_of(90.0, -180.0, 3))

    def test_cells_count_mean_and_best_tier(self):
        clusters = build_wifi.Clusters()
        # Two points a few hundred metres apart share a zoom 3 cell and a zoom 9 cell; one 0.3 degrees east
        # shares zoom 3 but not zoom 9 (a zoom 9 tile is 0.7 degrees wide).
        clusters.add(38.50, -98.00, 3)
        clusters.add(38.51, -98.01, 1)
        clusters.add(38.50, -97.00, 4)
        rows = {(z, x, y): (c, la, lo, t) for z, x, y, c, la, lo, t in clusters.rows()}
        z3 = [v for k, v in rows.items() if k[0] == 3]
        self.assertEqual([(3, 38.503333, -97.67, 1)], [(c, la, lo, t) for c, la, lo, t in z3])
        z9 = sorted(v for k, v in rows.items() if k[0] == 9)
        self.assertEqual([(1, 38.5, -97.0, 4), (2, 38.505, -98.005, 1)], z9)
        self.assertEqual({3, 4, 5, 6, 7, 8, 9}, {k[0] for k in rows})

    def test_every_zoom_from_3_to_9_and_no_other(self):
        self.assertEqual([3, 4, 5, 6, 7, 8, 9], list(build_wifi.CLUSTER_ZOOMS))


class ChainsTest(unittest.TestCase):
    def good(self, **overrides):
        entry = {"name": "X", "wikidata": ["Q1"], "fee": "customers"}
        entry.update(overrides)
        return json.dumps({"chains": [entry]})

    def test_the_real_config_loads(self):
        chains = build_wifi.load_chains(os.path.join(WIFI, "chains.json"))
        names = {c["name"] for c in chains.chains}
        for expected in ("Starbucks", "Panera Bread", "McDonald's", "Dunkin'", "Chick-fil-A", "Taco Bell", "Target",
                         "Walmart", "Best Buy", "Apple Store", "The Home Depot", "Lowe's", "Pilot", "Flying J",
                         "Love's Travel Stops", "TravelCenters of America", "Petro Stopping Centers"):
            self.assertIn(expected, names)
        self.assertEqual(len(chains.by_qid), sum(len(c["wikidata"]) for c in chains.chains))
        self.assertEqual("Q37158", chains.by_qid["Q37158"]["wikidata"][0])
        self.assertEqual(64, len(chains.sha256))

    def test_loading_the_test_config(self):
        self.assertEqual(["Q37158", "Q38076", "Q483551", "Q7963529"], CHAINS.qids())
        self.assertEqual("customers", CHAINS.by_qid["Q38076"]["fee"])  # the default

    def test_sha256_is_of_the_file_text(self):
        import hashlib

        with open(os.path.join(HERE, "chains-test.json"), "rb") as f:
            self.assertEqual(hashlib.sha256(f.read()).hexdigest(), CHAINS.sha256)

    def test_malformed_files_fail_loudly(self):
        bad = {
            "not json": "{",
            "not an object": "[]",
            "no chains key": "{}",
            "extra top-level key": '{"chains": [], "other": 1}',
            "empty list": '{"chains": []}',
            "chains not a list": '{"chains": {}}',
            "entry not an object": '{"chains": ["Starbucks"]}',
            "no name": '{"chains": [{"wikidata": ["Q1"]}]}',
            "empty name": self.good(name=" "),
            "no wikidata": '{"chains": [{"name": "X"}]}',
            "wikidata a string": self.good(wikidata="Q1"),
            "wikidata empty": self.good(wikidata=[]),
            "bad QID": self.good(wikidata=["37158"]),
            "QID with a space": self.good(wikidata=["Q1 "]),
            "QID that would break a shell": self.good(wikidata=["Q1;rm"]),
            "bad fee": self.good(fee="free"),
            "unknown key": self.good(wikidta=["Q2"]),
            "note not text": self.good(note=5),
            "same QID twice": json.dumps({"chains": [{"name": "A", "wikidata": ["Q1"]}, {"name": "B", "wikidata": ["Q1"]}]}),
        }
        for label, text in bad.items():
            with self.assertRaises(ChainsError, msg=label):
                parse_chains(text)

    def test_the_command_line_stops_on_a_bad_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "chains.json")
            with open(path, "w") as f:
                f.write("{")
            old = sys.argv
            sys.argv = ["build_wifi.py", "--chains", path, "--print-qids"]
            try:
                with self.assertRaises(SystemExit) as raised:
                    build_wifi.main()
            finally:
                sys.argv = old
            self.assertIn("not valid JSON", str(raised.exception))


class SourcesTest(unittest.TestCase):
    def test_date_of(self):
        self.assertEqual("2026-10-17", build_wifi.date_of("2026-10-17T20:21:15Z"))
        self.assertEqual("2026-10-17", build_wifi.date_of("2026-10-17"))
        with self.assertRaises(SystemExit):
            build_wifi.date_of("yesterday")

    def test_parse_sources_inline_and_from_a_file(self):
        inline = '[{"name": "ohio", "timestamp": "2026-10-17T20:21:15Z"}]'
        self.assertEqual("ohio", build_wifi.parse_sources(inline)[0]["name"])
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "s.json")
            with open(path, "w") as f:
                f.write(inline)
            self.assertEqual(build_wifi.parse_sources(inline), build_wifi.parse_sources(path))
        for bad in ("[]", '[{"name": "ohio"}]'):
            with self.assertRaises(SystemExit):
                build_wifi.parse_sources(bad)


def objects():
    """A few hand-made objects for the writer tests: (type, id, tags, lat, lon)."""
    return [
        ("n", 1, {"amenity": "cafe", "name": "A", "internet_access": "wlan", "internet_access:fee": "no",
                  "opening_hours": "24/7"}, 38.5, -98.0),
        ("n", 2, {"amenity": "library", "name": "B"}, 38.51, -98.01),
        ("w", 3, {"highway": "rest_area"}, 40.0, -100.0),
        ("n", 4, {"amenity": "cafe", "name": "C", "brand:wikidata": COFFEE}, 38.5, -97.0),
        ("n", 5, {"amenity": "cafe", "name": "D", "internet_access:fee": "yes"}, 38.5, -97.0),
        ("n", 6, {"amenity": "cafe", "name": "E", "brand:wikidata": COFFEE, "internet_access": "no"}, 38.5, -97.0),
    ]


SOURCES = [{"name": "ohio", "timestamp": "2026-10-15T20:00:00Z"}, {"name": "kansas", "timestamp": "2026-10-17T20:21:15Z"}]


class WriterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "wifi-us.sqlite")
        cls.lines = []
        cls.tiers = build_wifi.build(objects(), cls.out, CHAINS, SOURCES, max_mb=5, built_at="2026-10-18",
                                     log=cls.lines.append)
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def test_the_pack_is_marked_and_versioned(self):
        self.assertEqual(0x53425731, self.db.execute("PRAGMA application_id").fetchone()[0])
        self.assertEqual(b"SBW1", (0x53425731).to_bytes(4, "big"))
        self.assertEqual(1, self.db.execute("PRAGMA user_version").fetchone()[0])
        with open(self.out, "rb") as f:
            header = f.read(100)
        self.assertEqual(b"SQLite format 3\x00", header[:16])
        self.assertEqual(b"SBW1", header[68:72])  # application_id in the file header
        self.assertEqual(1, int.from_bytes(header[60:64], "big"))  # user_version

    def test_tables_and_indexes(self):
        names = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')")}
        self.assertLessEqual({"meta", "spot", "cluster", "spot_lat_lon", "spot_tier_lat_lon"}, names)
        spot = [r[1] for r in self.db.execute("PRAGMA table_info(spot)")]
        self.assertEqual(["id", "osm_type", "osm_id", "name", "brand_wikidata", "lat", "lon", "category", "tier",
                          "fee", "opening_hours", "website"], spot)
        cluster = [r[1] for r in self.db.execute("PRAGMA table_info(cluster)")]
        self.assertEqual(["zoom", "x", "y", "count", "lat", "lon", "tier_min"], cluster)
        self.assertEqual(["lat", "lon"], [r[2] for r in self.db.execute("PRAGMA index_info(spot_lat_lon)")])
        self.assertEqual(["tier", "lat", "lon"], [r[2] for r in self.db.execute("PRAGMA index_info(spot_tier_lat_lon)")])

    def test_rows(self):
        rows = self.db.execute("SELECT osm_type, osm_id, name, tier, fee, category, opening_hours FROM spot ORDER BY id").fetchall()
        self.assertEqual([
            ("n", 1, "A", 1, "no", "cafe", "24/7"),
            ("n", 2, "B", 4, "unknown", "library", None),
            ("w", 3, None, 4, "unknown", "rest_area", None),
            ("n", 4, "C", 3, "customers", "cafe", None),
        ], rows)
        self.assertEqual({1: 1, 3: 1, 4: 2}, self.tiers)

    def test_the_rtree_answers_a_box_when_it_exists(self):
        try:
            rows = self.db.execute(
                "SELECT s.name FROM wifi_rtree r JOIN spot s ON s.id = r.id"
                " WHERE r.minlat >= 38.4 AND r.maxlat <= 38.6 AND r.minlon >= -98.1 AND r.maxlon <= -97.9 ORDER BY s.id"
            ).fetchall()
        except sqlite3.OperationalError:
            self.skipTest("this SQLite has no R-tree module; the writer warns and goes without")
        self.assertEqual([("A",), ("B",)], rows)

    def test_meta(self):
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual("2026-10-17", meta["extract_date"])
        self.assertEqual({"ohio": "2026-10-15", "kansas": "2026-10-17"}, json.loads(meta["extract_dates"]))
        self.assertEqual("2026-10-18", meta["built_at"])
        self.assertEqual(CHAINS.sha256, meta["chains_sha256"])
        self.assertEqual(CHAINS.sha256, meta["chains_version"])
        self.assertEqual("1", meta["schema"])
        self.assertEqual(["ohio", "kansas"], json.loads(meta["sources"]))

    def test_clusters_cover_every_spot_at_every_zoom(self):
        for zoom in build_wifi.CLUSTER_ZOOMS:
            self.assertEqual(4, self.db.execute("SELECT SUM(count) FROM cluster WHERE zoom = ?", (zoom,)).fetchone()[0], zoom)
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM cluster WHERE zoom NOT BETWEEN 3 AND 9").fetchone()[0])
        # One tile holds all four at zoom 3 (best tier 1); at zoom 9 there are three cells.
        self.assertEqual([(4, 1)], self.db.execute("SELECT count, tier_min FROM cluster WHERE zoom = 3").fetchall())
        self.assertEqual([1, 1, 2], [r[0] for r in self.db.execute("SELECT count FROM cluster WHERE zoom = 9 ORDER BY count")])

    def test_the_log_names_tiers_chains_dates_and_size(self):
        text = "\n".join(self.lines)
        for needle in ("tier 1 Tagged free: 1", "tier 3 Chain policy: 1", "tier 4 Likely: 2", "Q37158 Example Coffee: 2 seen, 1 kept",
                       "Q38076 Example Burgers: 0 seen", "NONE SEEN", "kansas 2026-10-17", "extract_date 2026-10-17", "MB"):
            self.assertIn(needle, text)

    def test_over_the_limit_fails_and_leaves_no_pack(self):
        out = os.path.join(self.tmp.name, "big.sqlite")
        with self.assertRaises(SystemExit) as raised:
            build_wifi.build(objects(), out, CHAINS, SOURCES, max_mb=0.000001, log=lambda line: None)
        self.assertIn("over the", str(raised.exception))
        self.assertFalse(os.path.exists(out))
        self.assertFalse(os.path.exists(out + ".part"))

    def test_a_rebuild_replaces_the_pack(self):
        out = os.path.join(self.tmp.name, "again.sqlite")
        build_wifi.build(objects(), out, CHAINS, SOURCES, log=lambda line: None)
        build_wifi.build(objects()[:2], out, CHAINS, SOURCES, log=lambda line: None)
        db = sqlite3.connect(out)
        try:
            self.assertEqual(2, db.execute("SELECT count(*) FROM spot").fetchone()[0])
        finally:
            db.close()


@unittest.skipUnless(HAVE_OSMIUM, "pyosmium is not installed")
class FixtureTest(unittest.TestCase):
    """Reads tests/fixture.osm (synthetic points, generic names) through pyosmium into a pack."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "wifi-fixture.sqlite")
        cls.tiers = build_wifi.build(build_wifi.read_objects(os.path.join(HERE, "fixture.osm")), cls.out, CHAINS,
                                     SOURCES, log=lambda line: None)
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def spot(self, osm_type, osm_id):
        return self.db.execute(
            "SELECT name, tier, fee, category, lat, lon, brand_wikidata, opening_hours, website"
            " FROM spot WHERE osm_type = ? AND osm_id = ?", (osm_type, osm_id)).fetchone()

    def test_counts(self):
        self.assertEqual({1: 6, 2: 4, 3: 2, 4: 11}, self.tiers)

    def test_every_rule_on_a_node(self):
        expected = {
            1: ("Fixture Cafe One", 1, "no", "cafe"),
            2: ("Fixture Cafe Two", 1, "no", "cafe"),
            3: ("Fixture Cafe Three", 1, "customers", "cafe"),
            4: ("Fixture Cafe Four", 2, "unknown", "cafe"),
            5: ("Fixture Cafe Five", 2, "unknown", "cafe"),
            9: ("Example Coffee", 3, "customers", "cafe"),
            10: ("Example Coffee", 1, "no", "cafe"),
            13: ("Fixture Library Thirteen", 4, "unknown", "library"),
            14: ("Fixture Hotel Fourteen", 4, "unknown", "hotel"),
            15: ("Fixture Motel Fifteen", 4, "unknown", "motel"),
            16: (None, 4, "unknown", "rest_area"),
            17: ("Fixture Welcome Center", 4, "unknown", "welcome_center"),
            18: ("Fixture Park Visitor Center", 4, "unknown", "visitor_centre"),
            24: ("Fixture Library Twenty-four", 2, "unknown", "library"),
            26: ("Example Brand", 2, "unknown", "books"),
            27: ("Fixture Federal Visitor Center", 4, "unknown", "visitor_centre"),
            28: ("Fixture Hotel Twenty-eight", 1, "customers", "hotel"),
            30: ("Example Mart", 3, "customers", "department_store"),
            31: ("Fixture Cafe Far", 1, "no", "cafe"),
            32: ("Fixture Library Far", 4, "unknown", "library"),
        }
        for osm_id, row in expected.items():
            self.assertEqual(row, self.spot("n", osm_id)[:4], osm_id)

    def test_what_is_left_out(self):
        for osm_id in (6, 7, 8, 11, 12, 19, 20, 21, 22, 23, 25, 29):
            self.assertIsNone(self.spot("n", osm_id), osm_id)

    def test_fields_are_copied(self):
        row = self.spot("n", 1)
        self.assertEqual(("Mo-Fr 08:00-17:00", "https://example.org/one"), (row[7], row[8]))
        self.assertEqual("Q37158", self.spot("n", 9)[6])
        self.assertEqual("https://example.org/brand", self.spot("n", 26)[8])
        self.assertEqual((38.51, -98.0), self.spot("n", 1)[4:6])

    def test_a_closed_way_sits_at_the_mean_of_its_corners(self):
        row = self.spot("w", 201)
        self.assertEqual(("Fixture Library Building", 4, "unknown", "library"), row[:4])
        self.assertAlmostEqual(39.001, row[4], places=6)
        self.assertAlmostEqual(-96.999, row[5], places=6)

    def test_an_open_way_sits_at_its_middle_node(self):
        row = self.spot("w", 202)
        self.assertEqual(("Fixture Rest Area", 4, "unknown", "rest_area"), row[:4])
        self.assertEqual((39.1, -97.099), row[4:6])

    def test_a_multipolygon_relation_sits_at_its_ring_centre(self):
        row = self.spot("r", 301)
        self.assertEqual(("Fixture Hotel Complex", 4, "unknown", "hotel"), row[:4])
        self.assertAlmostEqual(39.201, row[4], places=6)
        self.assertAlmostEqual(-97.199, row[5], places=6)
        # Its untagged member way is not a spot of its own.
        self.assertIsNone(self.spot("w", 203))


if __name__ == "__main__":
    unittest.main()
