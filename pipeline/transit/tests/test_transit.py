"""Tests for the transit pack compiler, on small GTFS feeds built in memory."""

import os
import sqlite3
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import build_transit as bt  # noqa: E402


def write_feed(path, files):
    with zipfile.ZipFile(path, "w") as z:
        for name, lines in files.items():
            z.writestr(name, "\n".join(lines) + "\n")


SUBWAY = {
    "agency.txt": ["agency_id,agency_name,agency_url,agency_timezone", "MTA NYCT,MTA,https://mta.info,America/New_York"],
    "stops.txt": [
        "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station",
        "101,Alpha,40.7000,-74.0000,1,",
        "101N,Alpha,40.7000,-74.0000,,101",
        "101S,Alpha,40.7000,-74.0000,,101",
        "102,Beta,40.7100,-74.0000,1,",
        "102N,Beta,40.7100,-74.0000,,102",
        "102S,Beta,40.7100,-74.0000,,102",
        "103,Gamma,40.7200,-74.0000,1,",
        "103S,Gamma,40.7200,-74.0000,,103",
        "999,Unused,41.0,-74.0,1,",
    ],
    "routes.txt": [
        "agency_id,route_id,route_short_name,route_long_name,route_type,route_color,route_text_color,route_sort_order",
        "MTA NYCT,1,1,Local,1,d82233,FFFFFF,20",
    ],
    "calendar.txt": [
        "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
        "Weekday,1,1,1,1,1,0,0,20260101,20261231",
    ],
    "calendar_dates.txt": ["service_id,date,exception_type", "Weekday,20260704,2", "Holiday,20260704,1"],
    "trips.txt": [
        "route_id,trip_id,service_id,trip_headsign,direction_id",
        "1,T1,Weekday,Gamma,1",
        "1,T2,Weekday,Gamma,1",
        "1,T3,Weekday,Gamma,1",  # overtakes T2
        "1,T4,Holiday,Gamma,1",
        "1,SHORT,Weekday,Beta,1",
        "1,N1,Weekday,Alpha,0",
    ],
    "stop_times.txt": [
        "trip_id,stop_id,arrival_time,departure_time,stop_sequence",
        "T1,101S,08:00:00,08:00:00,1", "T1,102S,08:05:00,08:05:30,2", "T1,103S,08:10:00,08:10:00,3",
        "T2,101S,08:10:00,08:10:00,1", "T2,102S,08:20:00,08:20:00,2", "T2,103S,08:40:00,08:40:00,3",
        "T3,101S,08:12:00,08:12:00,1", "T3,102S,08:16:00,08:16:00,2", "T3,103S,08:20:00,08:20:00,3",
        "T4,101S,24:30:00,24:30:00,1", "T4,102S,,,2", "T4,103S,24:40:00,24:40:00,3",
        "SHORT,101S,09:00:00,09:00:00,1", "SHORT,102S,09:05:00,09:05:00,2",
        "N1,102N,09:00:00,09:00:00,1", "N1,101N,09:05:00,09:05:00,2",
    ],
    # 101S to 102S: a transfer with no time given (walked, never 0 seconds).
    "transfers.txt": ["from_stop_id,to_stop_id,transfer_type,min_transfer_time", "101,101,2,180", "102,103,2,300", "101S,102S,0,"],
}

BUS_HEADER = {
    "stops.txt": ["stop_id,stop_name,stop_lat,stop_lon", "500,MAIN ST/1 AV,40.7001,-74.0001", "501,MAIN ST/5 AV,40.7300,-74.0000"],
    "routes.txt": ["route_id,agency_id,route_short_name,route_long_name,route_type,route_color", "B1,MTA NYCT,B1,Main St,3,00AEEF"],
    "calendar.txt": [
        "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date",
        "Sat,0,0,0,0,0,1,0,20260101,20261231",
    ],
}


def bus_feed(trip_id):
    return {
        **BUS_HEADER,
        "trips.txt": ["route_id,service_id,trip_id,trip_headsign,direction_id", f"B1,Sat,{trip_id},5 AV,0"],
        "stop_times.txt": [
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type",
            f"{trip_id},10:00:00,10:00:00,500,1,0,1",
            f"{trip_id},10:07:00,10:07:00,501,2,1,0",
        ],
    }


class EncodingTest(unittest.TestCase):
    def test_times_round_trip(self):
        arrivals = [100, 200, 260, 90_000]
        departures = [100, 230, 260, 90_000]
        blob = bt.encode_times(arrivals, departures)
        self.assertEqual(bt.decode_times(100, blob), (arrivals, departures))

    def test_negative_deltas_survive(self):
        # Bad data can have time going backwards; zigzag keeps it intact.
        self.assertEqual(bt.decode_times(500, bt.encode_times([500, 400], [500, 450])), ([500, 400], [500, 450]))

    def test_uints_round_trip(self):
        values = [0, 1, 127, 128, 300, 16_384, 2_000_000]
        self.assertEqual(bt.decode_uints(bt.encode_uints(values)), values)

    def test_parse_time_past_midnight(self):
        self.assertEqual(bt.parse_time("25:03:10"), 90_190)
        self.assertEqual(bt.parse_time(" 8:00:00"), 28_800)
        self.assertIsNone(bt.parse_time(""))

    def test_fill_missing_interpolates(self):
        self.assertEqual(bt.fill_missing([0, None, None, 300]), [0, 100, 200, 300])
        self.assertIsNone(bt.fill_missing([None, 10]))


class WalkingTransferTest(unittest.TestCase):
    def test_stops_east_west_within_the_radius_are_linked(self):
        # 351 m apart east-west at 40.7 N: once two longitude cells apart, so never linked.
        lon = -74.0 + 351 / (111_000 * __import__("math").cos(__import__("math").radians(40.7)))
        stops = [{"id": 1, "lat": 40.7, "lon": -74.0}, {"id": 2, "lat": 40.7, "lon": lon}]
        links = bt.Compiler().walking_transfers(stops)
        self.assertIn((1, 2), links)
        self.assertIn((2, 1), links)
        self.assertEqual(bt.walk_seconds(bt.distance_m(40.7, -74.0, 40.7, lon)), links[(1, 2)])

    def test_far_stops_are_not(self):
        stops = [{"id": 1, "lat": 40.7, "lon": -74.0}, {"id": 2, "lat": 40.7, "lon": -73.99}]  # about 840 m
        self.assertEqual({}, bt.Compiler().walking_transfers(stops))


class BuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        subway = os.path.join(cls.dir.name, "subway.zip")
        bus1 = os.path.join(cls.dir.name, "bus1.zip")
        bus2 = os.path.join(cls.dir.name, "bus2.zip")
        write_feed(subway, SUBWAY)
        write_feed(bus1, bus_feed("BUS-A"))
        write_feed(bus2, bus_feed("BUS-B"))
        cls.out = os.path.join(cls.dir.name, "transit.sqlite")
        cls.counts = bt.build(cls.out, "test", [("subway", subway), ("bus", bus1), ("bus", bus2)])
        cls.db = sqlite3.connect(cls.out)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.dir.cleanup()

    def q(self, sql, *args):
        return self.db.execute(sql, args).fetchall()

    def stop_id(self, gtfs_id):
        return self.q("SELECT id FROM stop WHERE gtfs_id = ?", gtfs_id)[0][0]

    def test_marks_file_as_transit_pack(self):
        self.assertEqual(self.q("PRAGMA application_id")[0][0], 0x53425431)
        self.assertEqual(self.q("PRAGMA user_version")[0][0], bt.SCHEMA_VERSION)

    def test_shared_bus_stops_and_routes_are_merged(self):
        self.assertEqual(len(self.q("SELECT 1 FROM stop WHERE gtfs_id IN ('500', '501')")), 2)
        self.assertEqual(len(self.q("SELECT 1 FROM route WHERE gtfs_id = 'B1'")), 1)
        self.assertEqual(len(self.q("SELECT 1 FROM trip WHERE gtfs_id LIKE 'BUS-%'")), 2)

    def test_drops_unused_stops_keeps_their_stations(self):
        self.assertEqual(self.q("SELECT 1 FROM stop WHERE gtfs_id = '999'"), [])
        self.assertEqual(self.q("SELECT is_station FROM stop WHERE gtfs_id = '101'"), [(1,)])
        self.assertEqual(self.q("SELECT parent FROM stop WHERE gtfs_id = '101S'"), [(self.stop_id("101"),)])

    def test_route_colour_is_upper_case(self):
        self.assertEqual(self.q("SELECT color FROM route WHERE gtfs_id = '1'"), [("D82233",)])

    def test_overtaking_trip_gets_its_own_pattern(self):
        patterns = {gtfs: p for gtfs, p in self.q("SELECT gtfs_id, pattern FROM trip")}
        self.assertEqual(patterns["T1"], patterns["T2"])
        self.assertNotEqual(patterns["T2"], patterns["T3"])
        self.assertNotEqual(patterns["T1"], patterns["SHORT"])

    def test_trips_sorted_and_times_decoded(self):
        start, blob = self.q("SELECT start, times FROM trip WHERE gtfs_id = 'T1'")[0]
        self.assertEqual(bt.decode_times(start, blob), ([28_800, 29_100, 29_400], [28_800, 29_130, 29_400]))

    def test_blank_times_interpolated_past_midnight(self):
        start, blob = self.q("SELECT start, times FROM trip WHERE gtfs_id = 'T4'")[0]
        self.assertEqual(bt.decode_times(start, blob)[1], [88_200, 88_500, 88_800])

    def test_pickup_and_drop_off_flags(self):
        flags = self.q("SELECT p.flags FROM pattern p JOIN trip t ON t.pattern = p.id WHERE t.gtfs_id = 'BUS-A'")[0][0]
        # First stop: pickup only (drop_off_type=1 too). Last stop: drop-off only.
        self.assertEqual(list(flags), [bt.PICKUP_OK, bt.DROP_OFF_OK])

    def test_pattern_stops_and_index(self):
        stops = bt.decode_uints(self.q(
            "SELECT p.stops FROM pattern p JOIN trip t ON t.pattern = p.id WHERE t.gtfs_id = 'T1'")[0][0])
        self.assertEqual(stops, [self.stop_id("101S"), self.stop_id("102S"), self.stop_id("103S")])
        served = self.q("SELECT count(DISTINCT pattern) FROM pattern_stop WHERE stop = ?", self.stop_id("103S"))
        self.assertEqual(served[0][0], 2)  # T1, T2 and T4 share one; T3 has its own

    def test_calendar(self):
        days, start, end = self.q("SELECT days, start_date, end_date FROM service WHERE gtfs_id = 'Weekday'")[0]
        self.assertEqual((days, start, end), (0b11111, 20260101, 20261231))
        holiday = self.q("SELECT s.days, d.date, d.added FROM service s JOIN service_date d ON d.service = s.id "
                         "WHERE s.gtfs_id = 'Holiday'")
        self.assertEqual(holiday, [(0, 20260704, 1)])

    def test_change_time_from_self_transfer(self):
        self.assertEqual(self.q("SELECT change_seconds FROM stop WHERE gtfs_id = '101S'"), [(180,)])
        self.assertEqual(self.q("SELECT change_seconds FROM stop WHERE gtfs_id = '102S'"), [(bt.DEFAULT_CHANGE_SECONDS,)])

    def test_transfers(self):
        links = {(a, b): s for a, b, s in self.q("SELECT from_stop, to_stop, seconds FROM transfer")}
        # Platforms of one station, with the station's change time.
        self.assertEqual(links[(self.stop_id("101N"), self.stop_id("101S"))], 180)
        # A GTFS station transfer reaches the platforms.
        self.assertEqual(links[(self.stop_id("102S"), self.stop_id("103S"))], 300)
        # A bus stop 10 m from a platform gets a short walk; one 3 km away doesn't.
        self.assertLess(links[(self.stop_id("500"), self.stop_id("101S"))], 30)
        self.assertNotIn((self.stop_id("501"), self.stop_id("101S")), links)
        # A transfer the feed gives no time for is walked: it used to take 0 seconds.
        self.assertEqual(links[(self.stop_id("101S"), self.stop_id("102S"))], bt.walk_seconds(bt.distance_m(40.70, -74.0, 40.71, -74.0)))
