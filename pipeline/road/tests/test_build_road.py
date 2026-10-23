"""Tests for the Road pack builder. Run: python3 -m unittest discover -s pipeline/road/tests

The rules are pure functions of an object's tags, so most tests need no pyosmium; the one that reads the
fixture file (synthetic points and generic names) skips itself without it."""

import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROAD = os.path.dirname(HERE)
sys.path.insert(0, ROAD)

import build_road  # noqa: E402
from build_road import ChainsError, classify, parse_chains  # noqa: E402

try:
    import osmium  # noqa: F401

    HAVE_OSMIUM = True
except ImportError:
    HAVE_OSMIUM = False

CHAINS = build_road.load_chains(os.path.join(HERE, "chains-test.json"))


def tags_of(**tags):
    """Tags given as keyword arguments; "__" stands for ":" in a key."""
    return {k.replace("__", ":"): v for k, v in tags.items()}


def categories(**tags):
    return [v.category for v in classify(tags_of(**tags), CHAINS)]


def extra(category, **tags):
    for v in classify(tags_of(**tags), CHAINS):
        if v.category == category:
            return v.extra
    raise AssertionError(f"{category} is not among {categories(**tags)}")


class CategoryTest(unittest.TestCase):
    def test_each_simple_tag_is_its_category(self):
        for tags, expected in [
            (dict(tourism="camp_site"), "camp"),
            (dict(tourism="caravan_site"), "rv"),
            (dict(highway="rest_area"), "rest"),
            (dict(highway="services"), "rest"),
            (dict(amenity="sanitary_dump_station"), "dump"),
            (dict(amenity="drinking_water"), "water"),
            (dict(amenity="toilets"), "toilet"),
            (dict(amenity="shower"), "shower"),
            (dict(amenity="fuel"), "fuel"),
            (dict(shop="laundry"), "laundry"),
            (dict(amenity="library"), "library"),
            (dict(amenity="pharmacy"), "pharmacy"),
            (dict(amenity="hospital"), "hospital"),
            (dict(amenity="clinic"), "clinic"),
            (dict(shop="supermarket"), "grocery"),
            (dict(boundary="national_park"), "park"),
        ]:
            self.assertEqual([expected], categories(**tags), tags)

    def test_every_category_is_covered_by_a_rule(self):
        seen = set()
        for tags in (dict(tourism="camp_site"), dict(tourism="caravan_site"), dict(highway="rest_area"),
                     dict(amenity="sanitary_dump_station"), dict(amenity="drinking_water"), dict(amenity="toilets"),
                     dict(amenity="shower"), dict(amenity="fuel", hgv="yes"), dict(shop="laundry"),
                     dict(amenity="library"), dict(amenity="pharmacy"), dict(amenity="hospital"),
                     dict(amenity="clinic"), dict(shop="supermarket"), dict(boundary="national_park")):
            seen.update(categories(**tags))
        self.assertEqual(set(build_road.CATEGORIES), seen)
        self.assertEqual(set(build_road.CATEGORIES), set(build_road.FIELDS))

    def test_doctors_are_clinics_only_when_tagged_as_one(self):
        self.assertEqual(["clinic"], categories(amenity="doctors", healthcare="clinic"))
        self.assertEqual([], categories(amenity="doctors"))
        self.assertEqual([], categories(amenity="doctors", healthcare="doctor"))

    def test_other_tags_are_no_spots(self):
        for tags in (dict(amenity="cafe"), dict(tourism="hotel"), dict(shop="convenience"), dict(amenity="parking"),
                     dict(tourism="camp_pitch"), dict(highway="residential"), dict(shop="laundry_pickup"), dict(name="A")):
            self.assertEqual([], categories(**tags), tags)

    def test_values_match_whatever_the_case(self):
        self.assertEqual(["toilet"], categories(amenity=" Toilets "))

    def test_private_and_no_access_are_left_out_of_every_category(self):
        for access in ("private", "no", " Private ", "NO"):
            for tags in ({"amenity": "toilets"}, {"tourism": "camp_site"}, {"amenity": "fuel", "hgv": "yes"},
                         {"highway": "services", "hgv": "yes"}, {"boundary": "national_park"},
                         {"amenity": "doctors", "healthcare": "clinic"}):
                self.assertEqual([], categories(access=access, **tags), (access, tags))

    def test_other_access_values_stay(self):
        for access in ("yes", "customers", "permit", "permissive", "destination", ""):
            self.assertEqual(["toilet"], categories(amenity="toilets", access=access), access)


class TruckTest(unittest.TestCase):
    def test_hgv_yes_on_fuel_or_services(self):
        self.assertEqual(["fuel", "truck"], categories(amenity="fuel", hgv="yes"))
        self.assertEqual(["rest", "truck"], categories(highway="services", hgv="yes"))
        self.assertEqual(["fuel"], categories(amenity="fuel", hgv="no"))
        self.assertEqual(["fuel"], categories(amenity="fuel", hgv="designated"))
        self.assertEqual([], categories(shop="convenience", hgv="yes"))
        self.assertEqual(["rest"], categories(highway="rest_area", hgv="yes"))

    def test_a_brand_of_chains_json_by_name_or_by_qid(self):
        self.assertEqual(["fuel", "truck"], categories(amenity="fuel", brand="Example Truck"))
        self.assertEqual(["fuel", "truck"], categories(amenity="fuel", brand="  example TRUCK stop "))
        self.assertEqual(["fuel", "truck"], categories(amenity="fuel", brand="Something", brand__wikidata="Q111"))
        self.assertEqual(["fuel", "truck"], categories(amenity="fuel", brand__wikidata="Q222"))
        self.assertEqual(["rest", "truck"], categories(highway="services", brand="Diner Stop"))

    def test_other_brands_and_other_kinds_of_place_are_not_trucks(self):
        self.assertEqual(["fuel"], categories(amenity="fuel", brand="Example Fuel", brand__wikidata="Q999"))
        self.assertEqual(["fuel"], categories(amenity="fuel", brand="Example Truck Stop Annex"))
        self.assertEqual([], categories(shop="convenience", brand="Example Truck", brand__wikidata="Q111"))

    def test_curly_apostrophes_match(self):
        chains = parse_chains('{"chains": [{"name": "Loves", "brands": ["Love\'s"]}]}')
        for brand in ("Love's", "Love’s", "love‘s"):
            self.assertEqual(["fuel", "truck"], [v.category for v in classify(
                {"amenity": "fuel", "brand": brand}, chains)], brand)

    def test_the_real_chains_cover_the_five_brands(self):
        chains = build_road.load_chains(os.path.join(ROAD, "chains.json"))
        for brand in ("Pilot", "Flying J", "Love's", "TA", "Petro"):
            self.assertEqual(["fuel", "truck"], [v.category for v in classify(
                {"amenity": "fuel", "brand": brand}, chains)], brand)
        self.assertEqual(5, len(chains.chains))
        self.assertEqual(5, len(chains.by_qid))


class ParkTest(unittest.TestCase):
    def test_national_parks(self):
        self.assertEqual(["park"], categories(boundary="national_park"))

    def test_protected_areas_need_a_class_from_2_to_6(self):
        for protect_class in ("2", "3", "4", "5", "6", " 4 ", "1;4", "7;2", "2;5"):
            self.assertEqual(["park"], categories(boundary="protected_area", protect_class=protect_class), protect_class)
        for protect_class in ("1", "1a", "1b", "7", "8", "9", "97", "20", "", "x"):
            self.assertEqual([], categories(boundary="protected_area", protect_class=protect_class), protect_class)
        self.assertEqual([], categories(boundary="protected_area"))

    def test_a_city_park_is_not_a_park_but_a_state_park_is(self):
        self.assertEqual([], categories(leisure="park"))
        self.assertEqual([], categories(leisure="park", operator="City of Springfield"))
        self.assertEqual([], categories(leisure="park", park__type="city_park"))
        self.assertEqual(["park"], categories(leisure="park", park__type="state_park"))
        self.assertEqual(["park"], categories(leisure="park", operator="Ohio State Park Service"))
        self.assertEqual(["park"], categories(leisure="park", operator="Fixture STATE PARKS Division"))
        self.assertEqual([], categories(leisure="nature_reserve", operator="Ohio State Park"))
        self.assertEqual([], categories(boundary="administrative"))

    def test_one_row_per_category_even_when_two_rules_match(self):
        self.assertEqual(["park"], categories(boundary="national_park", leisure="park", park__type="state_park"))


class ExtraTest(unittest.TestCase):
    def test_camp_fields(self):
        self.assertEqual(
            {"fee": "yes", "drinking_water": "yes", "toilets": "yes", "shower": "no", "reservation": "required",
             "opening_hours": "Mar-Nov", "operator": "County", "tents": "yes", "caravans": "no"},
            extra("camp", tourism="camp_site", fee="yes", drinking_water="yes", toilets="yes", shower="no",
                  reservation="required", opening_hours="Mar-Nov", operator="County", tents="yes", caravans="no",
                  sanitary_dump_station="yes", phone="1", website="x", addr__street="y"))

    def test_rv_adds_the_dump_station(self):
        self.assertEqual({"sanitary_dump_station": "yes", "caravans": "yes"},
                         extra("rv", tourism="caravan_site", sanitary_dump_station="yes", caravans="yes", website="x"))

    def test_absent_means_unknown_never_false(self):
        self.assertEqual({}, extra("camp", tourism="camp_site"))
        self.assertEqual({}, extra("camp", tourism="camp_site", fee=" ", tents=""))
        self.assertEqual({"shower": "no"}, extra("camp", tourism="camp_site", shower="no"))

    def test_diesel_is_true_or_absent(self):
        self.assertEqual({"diesel": True}, extra("fuel", amenity="fuel", **{"fuel__diesel": "yes"}))
        self.assertEqual({"diesel": True}, extra("fuel", amenity="fuel", **{"fuel__diesel": "YES"}))
        for other in ("no", "", "maybe"):
            self.assertEqual({}, extra("fuel", amenity="fuel", **{"fuel__diesel": other}), other)
        self.assertNotIn("diesel", extra("fuel", amenity="fuel"))

    def test_fuel_and_truck_fields(self):
        tags = dict(amenity="fuel", hgv="yes", opening_hours="24/7", shower="yes", brand="Example Truck",
                    fuel__diesel="yes", phone="1")
        self.assertEqual({"diesel": True, "hgv": "yes", "opening_hours": "24/7"}, extra("fuel", **tags))
        self.assertEqual({"shower": "yes", "opening_hours": "24/7"}, extra("truck", **tags))

    def test_the_rest_of_the_categories(self):
        self.assertEqual({"toilets": "yes", "drinking_water": "no", "opening_hours": "24/7", "operator": "DOT"},
                         extra("rest", highway="rest_area", toilets="yes", drinking_water="no", opening_hours="24/7",
                               operator="DOT", fee="no"))
        self.assertEqual({"fee": "no", "opening_hours": "24/7"},
                         extra("dump", amenity="sanitary_dump_station", fee="no", opening_hours="24/7", seasonal="yes"))
        self.assertEqual({"fee": "no", "seasonal": "yes"},
                         extra("water", amenity="drinking_water", fee="no", seasonal="yes", opening_hours="x"))
        self.assertEqual({"fee": "no", "opening_hours": "dawn-dusk", "wheelchair": "yes"},
                         extra("toilet", amenity="toilets", fee="no", opening_hours="dawn-dusk", wheelchair="yes"))
        self.assertEqual({"fee": "yes", "opening_hours": "6-22"}, extra("shower", amenity="shower", fee="yes", opening_hours="6-22"))
        self.assertEqual({"opening_hours": "x"}, extra("laundry", shop="laundry", opening_hours="x", website="w"))
        self.assertEqual({"opening_hours": "x"}, extra("library", amenity="library", opening_hours="x", internet_access="wlan"))
        self.assertEqual({"opening_hours": "x"}, extra("pharmacy", amenity="pharmacy", opening_hours="x", brand="B"))
        self.assertEqual({"emergency": "yes"}, extra("hospital", amenity="hospital", emergency="yes", operator="O"))
        self.assertEqual({"opening_hours": "x"}, extra("clinic", amenity="clinic", opening_hours="x"))
        self.assertEqual({"opening_hours": "x"}, extra("grocery", shop="supermarket", opening_hours="x"))
        self.assertEqual({"operator": "NPS", "fee": "yes"}, extra("park", boundary="national_park", operator="NPS", fee="yes"))

    def test_no_field_is_an_address_phone_or_website(self):
        banned = re.compile(r"addr|phone|website|email|contact|url")
        for category, fields in build_road.FIELDS.items():
            for field in fields:
                self.assertIsNone(banned.search(field), (category, field))

    def test_name_and_brand_are_columns(self):
        (v,) = classify(tags_of(amenity="pharmacy", name=" Fixture ", brand="Example Drug", website="w"), CHAINS)
        self.assertEqual(("Fixture", "Example Drug"), (v.name, v.brand))
        (v,) = classify(tags_of(amenity="toilets"), CHAINS)
        self.assertEqual((None, None), (v.name, v.brand))  # no name falls back to nothing: the app names it from the category
        (v,) = classify(tags_of(amenity="toilets", brand="B"), CHAINS)
        self.assertIsNone(v.name)

    def test_extra_json_is_compact_and_sorted(self):
        self.assertEqual('{"diesel":true,"hgv":"yes"}', build_road.extra_json({"hgv": "yes", "diesel": True}))
        self.assertEqual("{}", build_road.extra_json({}))
        self.assertEqual('{"operator":"Parc Québec"}', build_road.extra_json({"operator": "Parc Québec"}))


class ChainsTest(unittest.TestCase):
    def test_loading_the_test_config(self):
        self.assertEqual(["Q111", "Q222"], sorted(CHAINS.by_qid))
        self.assertEqual({"example truck", "example truck stop", "diner stop"}, set(CHAINS.by_brand))

    def test_sha256_is_of_the_file_text(self):
        text = '{"chains": [{"name": "A", "brands": ["A"]}]}'
        self.assertEqual(64, len(parse_chains(text).sha256))
        self.assertNotEqual(parse_chains(text).sha256, parse_chains(text.replace("A", "B")).sha256)

    def test_malformed_files_fail_loudly(self):
        good = {"name": "A", "brands": ["A"], "wikidata": ["Q1"]}
        bad = [
            "not json",
            "[]",
            '{"chains": []}',
            '{"chains": {}}',
            json.dumps({"chains": [good], "extra": 1}),
            json.dumps({"chains": ["A"]}),
            json.dumps({"chains": [{"brands": ["A"]}]}),
            json.dumps({"chains": [{"name": "A"}]}),
            json.dumps({"chains": [{"name": "A", "brands": []}]}),
            json.dumps({"chains": [{**good, "colour": "red"}]}),
            json.dumps({"chains": [{**good, "wikidata": ["37158"]}]}),
            json.dumps({"chains": [{**good, "wikidata": "Q1"}]}),
            json.dumps({"chains": [{**good, "brands": "A"}]}),
            json.dumps({"chains": [{**good, "brands": [" "]}]}),
            json.dumps({"chains": [{**good, "note": 5}]}),
            json.dumps({"chains": [good, {"name": "B", "wikidata": ["Q1"]}]}),
            json.dumps({"chains": [good, {"name": "B", "brands": ["a"]}]}),
        ]
        for text in bad:
            with self.assertRaises(ChainsError, msg=text):
                parse_chains(text)

    def test_the_command_line_stops_on_a_bad_file_and_checks_a_good_one(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "chains.json")
            with open(path, "w") as f:
                f.write("{")
            script = os.path.join(ROAD, "build_road.py")
            result = subprocess.run([sys.executable, "-B", script, "--chains", path, "--check-chains"],
                                    capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("not valid JSON", result.stderr)
            result = subprocess.run([sys.executable, "-B", script, "--chains", os.path.join(ROAD, "chains.json"),
                                     "--check-chains"], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


def objects():
    """Four objects in the shape read_objects yields: a toilet, a diesel pump at a truck stop (two rows), a hospital
    and something that is no spot."""
    return [
        ("n", 1, {"amenity": "toilets", "fee": "no"}, 38.5, -98.0),
        ("n", 2, {"amenity": "fuel", "name": "P", "brand": "Example Truck", "fuel:diesel": "yes"}, 38.51, -98.0),
        ("w", 3, {"amenity": "hospital", "name": "H", "emergency": "yes"}, 40.0, -100.0),
        ("n", 4, {"amenity": "cafe", "name": "C"}, 38.52, -98.0),
    ]


SOURCES = [{"name": "ohio", "timestamp": "2026-10-15T20:00:00Z"}, {"name": "kansas", "timestamp": "2026-10-17T20:21:15Z"}]


class WriterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "road-us.sqlite")
        cls.lines = []
        cls.counts = build_road.build(objects(), cls.out, CHAINS, SOURCES, max_mb=5, built_at="2026-10-18",
                                      log=cls.lines.append)
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def test_the_pack_is_marked_and_versioned(self):
        self.assertEqual(0x53425231, build_road.APPLICATION_ID)
        self.assertEqual(0x53425231, self.db.execute("PRAGMA application_id").fetchone()[0])
        self.assertEqual(b"SBR1", (0x53425231).to_bytes(4, "big"))
        self.assertEqual(1, self.db.execute("PRAGMA user_version").fetchone()[0])
        with open(self.out, "rb") as f:
            header = f.read(100)
        self.assertEqual(b"SQLite format 3\x00", header[:16])
        self.assertEqual(b"SBR1", header[68:72])  # application_id in the file header
        self.assertEqual(1, int.from_bytes(header[60:64], "big"))  # user_version

    def test_tables_and_indexes(self):
        names = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type IN ('table', 'index')")}
        self.assertLessEqual({"meta", "spot", "cluster", "spot_lat_lon", "spot_cat_lat_lon", "cluster_zoom_x_y"}, names)
        spot = [r[1] for r in self.db.execute("PRAGMA table_info(spot)")]
        self.assertEqual(["id", "lat", "lon", "category", "name", "brand", "extra"], spot)
        cluster = [r[1] for r in self.db.execute("PRAGMA table_info(cluster)")]
        self.assertEqual(["zoom", "x", "y", "category", "count", "lat", "lon"], cluster)
        self.assertEqual(["lat", "lon"], [r[2] for r in self.db.execute("PRAGMA index_info(spot_lat_lon)")])
        self.assertEqual(["category", "lat", "lon"], [r[2] for r in self.db.execute("PRAGMA index_info(spot_cat_lat_lon)")])
        self.assertEqual(["zoom", "x", "y"], [r[2] for r in self.db.execute("PRAGMA index_info(cluster_zoom_x_y)")])

    def test_rows(self):
        rows = self.db.execute("SELECT id, lat, lon, category, name, brand, extra FROM spot ORDER BY id").fetchall()
        self.assertEqual([
            (1, 38.5, -98.0, "toilet", None, None, '{"fee":"no"}'),
            (2, 38.51, -98.0, "fuel", "P", "Example Truck", '{"diesel":true}'),
            (3, 38.51, -98.0, "truck", "P", "Example Truck", "{}"),
            (4, 40.0, -100.0, "hospital", "H", None, '{"emergency":"yes"}'),
        ], rows)
        self.assertEqual({"toilet": 1, "fuel": 1, "truck": 1, "hospital": 1}, self.counts)

    def test_extra_is_never_null_and_always_json(self):
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM spot WHERE extra IS NULL").fetchone()[0])
        for (extra,) in self.db.execute("SELECT extra FROM spot"):
            self.assertIsInstance(json.loads(extra), dict)

    def test_the_rtree_answers_a_box_when_it_exists(self):
        try:
            rows = self.db.execute(
                "SELECT s.category FROM road_rtree r JOIN spot s ON s.id = r.id"
                " WHERE r.minlat >= 38.4 AND r.maxlat <= 38.6 AND r.minlon >= -98.1 AND r.maxlon <= -97.9 ORDER BY s.id"
            ).fetchall()
        except sqlite3.OperationalError:
            self.skipTest("this SQLite has no R-tree module; the writer warns and goes without")
        self.assertEqual([("toilet",), ("fuel",), ("truck",)], rows)

    def test_the_rtree_holds_one_row_per_spot(self):
        spots = self.db.execute("SELECT count(*) FROM spot").fetchone()[0]
        try:
            rows = self.db.execute("SELECT count(*) FROM road_rtree").fetchone()[0]
        except sqlite3.OperationalError:
            self.skipTest("this SQLite has no R-tree module; the writer warns and goes without")
        self.assertEqual(4, spots)
        self.assertEqual(spots, rows)

    def test_a_failed_rtree_insert_fails_the_build(self):
        # Only the CREATE may be forgiven; an insert that fails must not leave an empty or partial road_rtree.
        try:
            sqlite3.connect(":memory:").execute("CREATE VIRTUAL TABLE t USING rtree(id, a, b)")
        except sqlite3.OperationalError:
            self.skipTest("this SQLite has no R-tree module")
        original = build_road.RTREE
        build_road.RTREE = "CREATE VIRTUAL TABLE road_rtree USING rtree(id, minlat, maxlat)"
        try:
            with tempfile.TemporaryDirectory() as tmp:
                with self.assertRaises(sqlite3.OperationalError):
                    build_road.build(objects(), os.path.join(tmp, "road.sqlite"), CHAINS, SOURCES, max_mb=5,
                                     built_at="2026-10-18", log=lambda line: None)
        finally:
            build_road.RTREE = original

    def test_meta(self):
        meta = dict(self.db.execute("SELECT key, value FROM meta"))
        self.assertEqual("2026-10-17", meta["extract_date"])
        self.assertEqual({"ohio": "2026-10-15", "kansas": "2026-10-17"}, json.loads(meta["extract_dates"]))
        self.assertEqual("2026-10-18", meta["built_at"])
        self.assertEqual(CHAINS.sha256, meta["chains_sha256"])
        self.assertEqual("1", meta["schema"])
        self.assertEqual(["ohio", "kansas"], json.loads(meta["sources"]))
        self.assertEqual(list(build_road.CATEGORIES), json.loads(meta["categories"]))
        self.assertIn("OpenStreetMap", meta["attribution"])

    def test_clusters_cover_every_spot_at_every_zoom_by_category(self):
        for zoom in build_road.CLUSTER_ZOOMS:
            self.assertEqual(4, self.db.execute("SELECT SUM(count) FROM cluster WHERE zoom = ?", (zoom,)).fetchone()[0], zoom)
        self.assertEqual((3, 11), self.db.execute("SELECT min(zoom), max(zoom) FROM cluster").fetchone())
        # Zoom 3: one tile holds the three Kansas rows and one the hospital, never two categories in a row.
        rows = self.db.execute("SELECT category, count FROM cluster WHERE zoom = 3 ORDER BY category").fetchall()
        self.assertEqual([("fuel", 1), ("hospital", 1), ("toilet", 1), ("truck", 1)], rows)

    def test_the_log_names_categories_chains_dates_and_size(self):
        text = "\n".join(self.lines)
        for needle in ("category toilet: 1 (1 without a name)", "category fuel: 1 (0", "category truck: 1",
                       "Example Truck Stop: 1 (name 1, qid 0, both 0)", "Example Plaza: 0", "NONE SEEN",
                       "kansas 2026-10-17", "extract_date 2026-10-17", "MB"):
            self.assertIn(needle, text)

    def test_over_the_limit_fails_and_leaves_no_pack(self):
        out = os.path.join(self.tmp.name, "big.sqlite")
        with self.assertRaises(SystemExit) as raised:
            build_road.build(objects(), out, CHAINS, SOURCES, max_mb=0.000001, log=lambda line: None)
        self.assertIn("over the", str(raised.exception))
        self.assertFalse(os.path.exists(out))
        self.assertFalse(os.path.exists(out + ".part"))

    def test_a_rebuild_replaces_the_pack(self):
        out = os.path.join(self.tmp.name, "again.sqlite")
        build_road.build(objects(), out, CHAINS, SOURCES, log=lambda line: None)
        build_road.build(objects()[:1], out, CHAINS, SOURCES, log=lambda line: None)
        db = sqlite3.connect(out)
        try:
            self.assertEqual(1, db.execute("SELECT count(*) FROM spot").fetchone()[0])
        finally:
            db.close()

    def test_sources_and_dates(self):
        self.assertEqual("2026-10-17", build_road.date_of("2026-10-17T20:21:15Z"))
        self.assertEqual("2026-10-17", build_road.date_of(" 2026-10-17 "))
        with self.assertRaises(SystemExit):
            build_road.date_of("yesterday")
        self.assertEqual(SOURCES, build_road.parse_sources(json.dumps(SOURCES)))
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "sources.json")
            with open(path, "w") as f:
                json.dump(SOURCES, f)
            self.assertEqual(SOURCES, build_road.parse_sources(path))
        for bad in ("[]", '[{"name": "ohio"}]', '["ohio"]'):
            with self.assertRaises(SystemExit, msg=bad):
                build_road.parse_sources(bad)


class FilterTest(unittest.TestCase):
    """build.sh's osmium filter and the classifier must stay in step: everything classify() accepts has to get
    through the filter, or the pack silently loses it.

    The filter is exact-case (osmium matches values byte for byte); OSM values are lower-case in practice, so the
    classifier's case-folding is belt and braces and not covered here."""

    @staticmethod
    def filters():
        with open(os.path.join(ROAD, "build.sh")) as f:
            match = re.search(r'^FILTERS="([^"]+)"', f.read(), re.M)
        assert match, "build.sh has no FILTERS=\"...\" line"
        return match.group(1).split()

    @staticmethod
    def matches(expressions, osm_type, tags):
        """osmium tags-filter's rule: [types/]key[=value,value...], any expression matching keeps the object."""
        for expression in expressions:
            types, _, rest = expression.rpartition("/") if "/" in expression else ("nwr", "", expression)
            key, _, values = rest.partition("=")
            if osm_type in types and key in tags and (not values or tags[key] in values.split(",")):
                return True
        return False

    @staticmethod
    def fixture_objects():
        root = ET.parse(os.path.join(HERE, "fixture.osm")).getroot()
        for element in root:
            if element.tag in ("node", "way", "relation"):
                yield element.tag[0], element.get("id"), {t.get("k"): t.get("v") for t in element.findall("tag")}

    def test_everything_the_classifier_accepts_passes_the_filter(self):
        expressions = self.filters()
        accepted = 0
        for osm_type, osm_id, tags in self.fixture_objects():
            if classify(tags, CHAINS):
                accepted += 1
                self.assertTrue(self.matches(expressions, osm_type, tags), f"{osm_type}{osm_id} {tags}")
        self.assertGreater(accepted, 30)

    def test_every_rule_passes_the_filter_as_node_way_and_relation(self):
        expressions = self.filters()
        rules = [dict(tourism="camp_site"), dict(tourism="caravan_site"), dict(highway="rest_area"),
                 dict(highway="services"), dict(amenity="sanitary_dump_station"), dict(amenity="drinking_water"),
                 dict(amenity="toilets"), dict(amenity="shower"), dict(amenity="fuel"), dict(shop="laundry"),
                 dict(amenity="library"), dict(amenity="pharmacy"), dict(amenity="hospital"), dict(amenity="clinic"),
                 dict(amenity="doctors", healthcare="clinic"), dict(shop="supermarket"),
                 dict(boundary="national_park"), dict(boundary="protected_area", protect_class="2"),
                 dict(leisure="park", park__type="state_park"), dict(leisure="park", operator="Ohio State Park")]
        for tags in rules:
            tags = tags_of(**tags)
            self.assertTrue(classify({"name": "A", **tags}, CHAINS), tags)
            for osm_type in "nwr":
                self.assertTrue(self.matches(expressions, osm_type, tags), (osm_type, tags))

    def test_the_test_has_teeth(self):
        expressions = [e for e in self.filters() if "library" not in e]
        library = {"amenity": "library", "name": "A"}
        self.assertTrue(classify(library, CHAINS))
        self.assertFalse(self.matches(expressions, "n", library))
        self.assertFalse(self.matches(self.filters(), "n", {"amenity": "cafe"}))
        self.assertFalse(self.matches(self.filters(), "n", {"tourism": "hotel"}))

    def test_the_filter_keeps_ways_relations_and_their_nodes(self):
        with open(os.path.join(ROAD, "build.sh")) as f:
            text = f.read()
        self.assertTrue(all(e.startswith("nwr/") for e in self.filters()))
        self.assertNotRegex(re.sub(r"^#.*$", "", text, flags=re.M), r"--omit-referenced|tags-filter[^\n]* -R\b")


@unittest.skipUnless(HAVE_OSMIUM, "pyosmium is not installed")
class FixtureTest(unittest.TestCase):
    """Reads tests/fixture.osm (synthetic points, generic names) through pyosmium into a pack."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.out = os.path.join(cls.tmp.name, "road-fixture.sqlite")
        cls.counts = build_road.build(build_road.read_objects(os.path.join(HERE, "fixture.osm")), cls.out,
                                      build_road.load_chains(os.path.join(ROAD, "chains.json")), SOURCES,
                                      log=lambda line: None)
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.tmp.cleanup()

    def names(self, category):
        return sorted(r[0] for r in self.db.execute("SELECT name FROM spot WHERE category = ?", (category,)))

    def spot(self, category, name):
        row = self.db.execute("SELECT lat, lon, name, brand, extra FROM spot WHERE category = ? AND name = ?",
                              (category, name)).fetchone()
        self.assertIsNotNone(row, (category, name))
        return row[0], row[1], row[2], row[3], json.loads(row[4])

    def test_counts(self):
        self.assertEqual({"camp": 5, "rv": 1, "rest": 4, "dump": 1, "water": 1, "toilet": 2, "shower": 1, "fuel": 6,
                          "truck": 5, "laundry": 1, "library": 1, "pharmacy": 1, "hospital": 2, "clinic": 2,
                          "grocery": 1, "park": 8}, self.counts)
        self.assertEqual(set(build_road.CATEGORIES), set(self.counts))

    def test_one_of_each_category_with_its_fields(self):
        _, _, _, _, camp = self.spot("camp", "Fixture Camp One")
        self.assertEqual({"fee": "yes", "drinking_water": "yes", "toilets": "yes", "shower": "no",
                          "reservation": "recommended", "opening_hours": "Mar-Nov", "operator": "Fixture County",
                          "tents": "yes", "caravans": "no"}, camp)  # no address, phone, website or dump station
        self.assertEqual({}, self.spot("camp", "Fixture Camp Bare")[4])
        self.assertEqual({}, self.spot("camp", "Fixture Camp Blank Fields")[4])
        self.assertEqual(("Example RV", {"sanitary_dump_station": "yes", "fee": "yes", "caravans": "yes"}),
                         self.spot("rv", "Fixture RV Park")[3:])
        self.assertEqual({"toilets": "yes", "drinking_water": "yes", "opening_hours": "24/7", "operator": "Fixture DOT"},
                         self.spot("rest", "Fixture Rest Area")[4])
        self.assertEqual({"fee": "no", "opening_hours": "24/7"}, self.spot("dump", "Fixture Dump")[4])
        self.assertEqual({"fee": "yes", "opening_hours": "06:00-22:00"}, self.spot("shower", "Fixture Shower")[4])
        self.assertEqual({"opening_hours": "Mo-Su 06:00-22:00"}, self.spot("laundry", "Fixture Laundromat")[4])
        self.assertEqual({"opening_hours": "Mo-Fr 09:00-17:00"}, self.spot("library", "Fixture Library")[4])
        self.assertEqual(("Example Drug", {"opening_hours": "Mo-Su 08:00-22:00"}), self.spot("pharmacy", "Fixture Pharmacy")[3:])
        self.assertEqual({"emergency": "yes"}, self.spot("hospital", "Fixture Hospital")[4])
        self.assertEqual({}, self.spot("hospital", "Fixture Day Hospital")[4])
        self.assertEqual(("Example Mart", {"opening_hours": "Mo-Su 07:00-23:00"}), self.spot("grocery", "Fixture Market")[3:])

    def test_water_and_toilets_keep_their_fields_and_may_have_no_name(self):
        rows = self.db.execute("SELECT name, brand, extra FROM spot WHERE category IN ('water', 'toilet') ORDER BY id").fetchall()
        self.assertEqual([
            (None, None, '{"fee":"no","seasonal":"yes"}'),
            (None, None, '{"fee":"no","opening_hours":"dawn-dusk","wheelchair":"yes"}'),
            (None, None, "{}"),
        ], rows)

    def test_the_diesel_flag_is_true_or_absent(self):
        self.assertEqual({"diesel": True, "opening_hours": "24/7"}, self.spot("fuel", "Fixture Gas Diesel")[4])
        # fuel:diesel=no leaves no key, never a false; hgv=no is OpenStreetMap's own word and stays.
        self.assertEqual({"hgv": "no"}, self.spot("fuel", "Fixture Gas Plain")[4])
        self.assertNotIn(b"false", b"".join(r[0].encode() for r in self.db.execute("SELECT extra FROM spot")))
        self.assertEqual({"diesel": True, "hgv": "yes", "opening_hours": "24/7"}, self.spot("fuel", "Fixture Truck Fuel")[4])

    def test_truck_stops(self):
        self.assertEqual(["Fixture Loves", "Fixture Name Only Brand", "Fixture Truck Fuel", "Fixture Truck Plaza",
                          "Pilot Travel Center"], self.names("truck"))
        self.assertEqual({"shower": "yes", "opening_hours": "24/7"}, self.spot("truck", "Fixture Truck Fuel")[4])
        # A Pilot snack shop is no fuel station, so no truck stop; a service plaza with hgv=yes is a rest area too.
        self.assertNotIn("Pilot Snacks", self.names("truck") + self.names("fuel") + self.names("rest"))
        self.assertIn("Fixture Truck Plaza", self.names("rest"))
        self.assertIn("Fixture Truck Fuel", self.names("fuel"))
        self.assertNotIn("Fixture Gas Plain", self.names("truck"))

    def test_clinics_need_the_clinic_tag_on_doctors(self):
        self.assertEqual(["Fixture Clinic", "Fixture Doctors Clinic"], self.names("clinic"))

    def test_parks_only_by_the_class_filter_and_state_park_rule(self):
        self.assertEqual(
            ["Fixture Monument", "Fixture National Park", "Fixture National Park Node", "Fixture Preserve Mixed",
             "Fixture Preserve Six", "Fixture Preserve Two", "Fixture State Park A", "Fixture State Park B"],
            self.names("park"))
        for left_out in ("Fixture City Park", "Fixture Pocket Park", "Fixture Preserve Seven",
                         "Fixture Preserve Wilderness", "Fixture Preserve No Class", "Fixture Forest Reserve"):
            self.assertNotIn(left_out, self.names("park"))
        self.assertEqual({"operator": "Fixture State Parks Division", "fee": "yes"}, self.spot("park", "Fixture State Park B")[4])

    def test_what_is_left_out(self):
        for name in ("Fixture Hotel", "Fixture Cafe", "Fixture Single Doctor", "Pilot Snacks", "Fixture Private Toilet",
                     "Fixture Closed Camp", "Fixture Private Truck Stop"):
            self.assertEqual(0, self.db.execute("SELECT count(*) FROM spot WHERE name = ?", (name,)).fetchone()[0], name)
        # Untagged corner nodes and member ways are no spots.
        self.assertEqual(42, self.db.execute("SELECT count(*) FROM spot").fetchone()[0])

    def test_access_private_and_no_are_left_out_but_other_access_stays(self):
        def has(name, category):
            return self.db.execute("SELECT count(*) FROM spot WHERE category = ? AND name = ?",
                                   (category, name)).fetchone()[0] == 1

        self.assertTrue(has("Fixture Permit Camp", "camp"))
        self.assertFalse(has("Fixture Closed Camp", "camp"))
        self.assertFalse(has("Fixture Private Toilet", "toilet"))
        # One private truck stop is neither a fuel station nor a truck stop.
        self.assertFalse(has("Fixture Private Truck Stop", "fuel") or has("Fixture Private Truck Stop", "truck"))

    def test_a_closed_way_sits_at_the_mean_of_its_corners(self):
        lat, lon, *_ = self.spot("camp", "Fixture Camp Area")
        self.assertAlmostEqual(39.001, lat, places=6)
        self.assertAlmostEqual(-96.999, lon, places=6)

    def test_an_open_way_sits_at_its_middle_node(self):
        lat, lon, *_ = self.spot("rest", "Fixture Rest Way")
        self.assertEqual((39.1, -97.099), (lat, lon))

    def test_a_boundary_relation_sits_at_its_ring_centre(self):
        lat, lon, *_ = self.spot("park", "Fixture National Park")
        self.assertAlmostEqual(39.201, lat, places=6)
        self.assertAlmostEqual(-97.199, lon, places=6)
        lat, lon, *_ = self.spot("park", "Fixture Monument")
        self.assertAlmostEqual(39.301, lat, places=6)
        self.assertAlmostEqual(-97.299, lon, places=6)

    def test_every_spot_is_in_the_rtree_and_the_clusters(self):
        spots = self.db.execute("SELECT count(*) FROM spot").fetchone()[0]
        try:
            self.assertEqual(spots, self.db.execute("SELECT count(*) FROM road_rtree").fetchone()[0])
        except sqlite3.OperationalError:
            self.skipTest("this SQLite has no R-tree module")
        for zoom in build_road.CLUSTER_ZOOMS:
            self.assertEqual(spots, self.db.execute("SELECT SUM(count) FROM cluster WHERE zoom = ?", (zoom,)).fetchone()[0])


if __name__ == "__main__":
    unittest.main()
