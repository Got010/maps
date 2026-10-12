#!/usr/bin/env python3
"""Compile MTA GTFS feeds into the transit pack, transit-<region>.sqlite.

The app plans trips on the phone with RAPTOR (core/transit), which wants
trips grouped into patterns: trips of one route that stop at exactly the same
stops in the same order, sorted so no trip overtakes another. This script
does that grouping once, here, and stores each trip's times as a compact
varint blob, which keeps every MTA schedule for NYC in tens of megabytes.

Tables (all ids are this pack's integer ids, not GTFS ids):

  feed           one row per system: subway, bus, lirr, mnr
  stop           platforms, bus stops and stations; parent links platforms
                 to their station. change_seconds is the minimum time to
                 change trains at the stop.
  route          lines, with MTA colours
  service        GTFS calendar: days bitmask (Mon=1 .. Sun=64) and date range
  service_date   calendar_dates exceptions (added=1 adds, 0 removes)
  pattern        route + ordered stops (varint blob) + pickup/drop-off flags
  pattern_stop   which patterns serve each stop, for departure boards
  trip           pattern, service, GTFS trip id and the times blob
  transfer       walking links between nearby stops, in seconds

Times blob: for each stop i, zigzag varints of (arrival_i - departure_{i-1})
and (departure_i - arrival_i), with departure_{-1} = trip.start. Times are
seconds after midnight of the service day, and may exceed 24 hours.

Usage: build_transit.py <output.sqlite> <region> <name>=<feed.zip> [...]
  where name is subway, bus, lirr or mnr. Several zips may share a name (the
  six MTA bus files all go in as bus); their ids are one namespace.
"""

import argparse
import csv
import io
import math
import os
import sqlite3
import sys
import time
import zipfile
from collections import defaultdict

APPLICATION_ID = 0x53425431  # "SBT1", matches PackKind.TRANSIT_APPLICATION_ID
SCHEMA_VERSION = 1

FEEDS = {
    "subway": "NYC Subway",
    "bus": "MTA Bus",
    "lirr": "Long Island Rail Road",
    "mnr": "Metro-North Railroad",
}

DAY_COLUMNS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]

# Walking links between stops of different stations, for transfers.
WALK_RADIUS_M = 400
WALK_SPEED_MPS = 1.33  # 4.8 km/h
WALK_DETOUR = 1.25  # streets aren't straight lines
DEFAULT_CHANGE_SECONDS = 60  # board another vehicle at the same stop
DEFAULT_STATION_CHANGE_SECONDS = 120  # between platforms of one station

PICKUP_OK = 1
DROP_OFF_OK = 2

SCHEMA = f"""
PRAGMA application_id = {APPLICATION_ID};
PRAGMA user_version = {SCHEMA_VERSION};
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE feed (id INTEGER PRIMARY KEY, name TEXT NOT NULL, title TEXT NOT NULL, version TEXT);
CREATE TABLE stop (
  id INTEGER PRIMARY KEY,
  feed INTEGER NOT NULL,
  gtfs_id TEXT NOT NULL,
  name TEXT NOT NULL,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  parent INTEGER,
  is_station INTEGER NOT NULL,
  change_seconds INTEGER NOT NULL
);
CREATE TABLE route (
  id INTEGER PRIMARY KEY,
  feed INTEGER NOT NULL,
  gtfs_id TEXT NOT NULL,
  short_name TEXT,
  long_name TEXT,
  type INTEGER NOT NULL,
  color TEXT,
  text_color TEXT,
  sort_order INTEGER
);
CREATE TABLE service (
  id INTEGER PRIMARY KEY,
  feed INTEGER NOT NULL,
  gtfs_id TEXT NOT NULL,
  days INTEGER NOT NULL,
  start_date INTEGER NOT NULL,
  end_date INTEGER NOT NULL
);
CREATE TABLE service_date (service INTEGER NOT NULL, date INTEGER NOT NULL, added INTEGER NOT NULL);
CREATE TABLE pattern (
  id INTEGER PRIMARY KEY,
  route INTEGER NOT NULL,
  headsign TEXT,
  direction INTEGER,
  stops BLOB NOT NULL,
  flags BLOB NOT NULL
);
CREATE TABLE pattern_stop (stop INTEGER NOT NULL, pattern INTEGER NOT NULL, position INTEGER NOT NULL);
CREATE TABLE trip (
  id INTEGER PRIMARY KEY,
  pattern INTEGER NOT NULL,
  service INTEGER NOT NULL,
  gtfs_id TEXT NOT NULL,
  headsign TEXT,
  short_name TEXT,
  start INTEGER NOT NULL,
  times BLOB NOT NULL
);
CREATE TABLE transfer (from_stop INTEGER NOT NULL, to_stop INTEGER NOT NULL, seconds INTEGER NOT NULL);
"""

INDEXES = """
CREATE INDEX stop_parent ON stop(parent);
CREATE INDEX stop_gtfs ON stop(feed, gtfs_id);
CREATE INDEX service_date_service ON service_date(service);
CREATE INDEX pattern_stop_stop ON pattern_stop(stop);
CREATE INDEX trip_pattern ON trip(pattern, start);
CREATE INDEX trip_start ON trip(start);
CREATE INDEX trip_gtfs ON trip(gtfs_id);
CREATE INDEX transfer_from ON transfer(from_stop);
"""


# ---------------------------------------------------------------- encoding

def zigzag(n):
    return (n << 1) if n >= 0 else ((-n << 1) - 1)


def write_varint(out, n):
    while n >= 0x80:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)


def encode_uints(values):
    out = bytearray()
    for v in values:
        write_varint(out, v)
    return bytes(out)


def encode_times(arrivals, departures):
    """The times blob for one trip; see the module docstring."""
    out = bytearray()
    previous = departures[0]
    for arrival, departure in zip(arrivals, departures):
        write_varint(out, zigzag(arrival - previous))
        write_varint(out, zigzag(departure - arrival))
        previous = departure
    return bytes(out)


def read_varint(data, pos):
    shift = result = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def unzigzag(n):
    return (n >> 1) ^ -(n & 1)


def decode_times(start, blob):
    """Inverse of encode_times, for tests and checks."""
    arrivals, departures = [], []
    pos, previous = 0, start
    while pos < len(blob):
        d1, pos = read_varint(blob, pos)
        d2, pos = read_varint(blob, pos)
        arrival = previous + unzigzag(d1)
        departure = arrival + unzigzag(d2)
        arrivals.append(arrival)
        departures.append(departure)
        previous = departure
    return arrivals, departures


def decode_uints(blob):
    values, pos = [], 0
    while pos < len(blob):
        v, pos = read_varint(blob, pos)
        values.append(v)
    return values


# ---------------------------------------------------------------- parsing

def parse_time(text):
    """'25:03:10' -> 90190 seconds; '' -> None. GTFS allows hours past 24."""
    text = text.strip()
    if not text:
        return None
    h, m, s = text.split(":")
    return int(h) * 3600 + int(m) * 60 + int(s)


def fill_missing(times):
    """Interpolates blank (non-timepoint) times by position between known ones."""
    known = [i for i, t in enumerate(times) if t is not None]
    if not known:
        return None
    if known[0] != 0 or known[-1] != len(times) - 1:
        return None  # GTFS requires the first and last times
    for a, b in zip(known, known[1:]):
        for i in range(a + 1, b):
            times[i] = round(times[a] + (times[b] - times[a]) * (i - a) / (b - a))
    return times


def read_table(archive, name):
    """Rows of a GTFS file as dicts, or [] if the file is absent."""
    try:
        raw = archive.open(name)
    except KeyError:
        return []
    text = io.TextIOWrapper(raw, encoding="utf-8-sig", newline="")
    for row in csv.DictReader(text):
        yield {k.strip(): (v.strip() if v else "") for k, v in row.items() if k}


def distance_m(lat1, lon1, lat2, lon2):
    """Equirectangular distance; exact enough within a few km."""
    x = math.radians(lon2 - lon1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return math.hypot(x, y) * 6_371_008.8


def walk_seconds(meters):
    return math.ceil(meters * WALK_DETOUR / WALK_SPEED_MPS)


# ---------------------------------------------------------------- compiler

class Compiler:
    def __init__(self):
        self.feeds = {}  # name -> id
        self.feed_versions = defaultdict(set)
        self.stops = {}  # (feed, gtfs_id) -> dict
        self.routes = {}  # (feed, gtfs_id) -> dict
        self.services = {}  # (feed, gtfs_id) -> dict
        self.service_dates = {}  # (service id, date) -> added
        self.patterns = {}  # (route id, stops tuple, flags tuple) -> pattern dict
        self.trip_ids = set()
        self.station_changes = {}  # stop id -> seconds
        self.gtfs_transfers = []  # (from stop id, to stop id, seconds)
        self.skipped_trips = 0

    def feed_id(self, name):
        if name not in self.feeds:
            self.feeds[name] = len(self.feeds) + 1
        return self.feeds[name]

    def add(self, name, path):
        feed = self.feed_id(name)
        started = time.time()
        with zipfile.ZipFile(path) as archive:
            for row in read_table(archive, "feed_info.txt"):
                if row.get("feed_version"):
                    self.feed_versions[name].add(row["feed_version"])
            self._stops(feed, archive)
            self._routes(feed, archive)
            self._services(feed, archive)
            trips = self._trips(feed, archive)
            self._stop_times(feed, archive, trips)
            self._transfers(feed, archive)
        print(f"    {os.path.basename(path)} as {name} in {time.time() - started:.0f}s", file=sys.stderr)

    def _stops(self, feed, archive):
        rows = list(read_table(archive, "stops.txt"))
        for row in rows:
            key = (feed, row["stop_id"])
            if key in self.stops:
                continue  # the bus files repeat shared stops
            location_type = int(row.get("location_type") or 0)
            if location_type not in (0, 1):
                continue  # entrances and nodes aren't used yet
            self.stops[key] = {
                "id": len(self.stops) + 1,
                "feed": feed,
                "gtfs_id": row["stop_id"],
                "name": row["stop_name"],
                "lat": float(row["stop_lat"]),
                "lon": float(row["stop_lon"]),
                "parent_gtfs": row.get("parent_station") or None,
                "is_station": 1 if location_type == 1 else 0,
            }
        for row in rows:
            stop = self.stops.get((feed, row["stop_id"]))
            if stop and stop["parent_gtfs"]:
                parent = self.stops.get((feed, stop["parent_gtfs"]))
                stop["parent"] = parent["id"] if parent else None

    def _routes(self, feed, archive):
        for row in read_table(archive, "routes.txt"):
            key = (feed, row["route_id"])
            if key in self.routes:
                continue
            self.routes[key] = {
                "id": len(self.routes) + 1,
                "feed": feed,
                "gtfs_id": row["route_id"],
                "short_name": row.get("route_short_name") or None,
                "long_name": row.get("route_long_name") or None,
                "type": int(row.get("route_type") or 3),
                "color": (row.get("route_color") or "").upper() or None,
                "text_color": (row.get("route_text_color") or "").upper() or None,
                "sort_order": int(row["route_sort_order"]) if row.get("route_sort_order") else None,
            }

    def _service(self, feed, gtfs_id):
        key = (feed, gtfs_id)
        if key not in self.services:
            # Only in calendar_dates: no weekly pattern, dates added one by one.
            self.services[key] = {
                "id": len(self.services) + 1, "feed": feed, "gtfs_id": gtfs_id,
                "days": 0, "start_date": 0, "end_date": 0,
            }
        return self.services[key]

    def _services(self, feed, archive):
        for row in read_table(archive, "calendar.txt"):
            service = self._service(feed, row["service_id"])
            days = sum(1 << i for i, day in enumerate(DAY_COLUMNS) if row.get(day) == "1")
            service.update(days=service["days"] | days, start_date=int(row["start_date"]),
                           end_date=int(row["end_date"]))
        for row in read_table(archive, "calendar_dates.txt"):
            service = self._service(feed, row["service_id"])
            self.service_dates[(service["id"], int(row["date"]))] = 1 if row["exception_type"] == "1" else 0

    def _trips(self, feed, archive):
        trips = {}
        for row in read_table(archive, "trips.txt"):
            if (feed, row["trip_id"]) in self.trip_ids:
                continue
            route = self.routes.get((feed, row["route_id"]))
            if route is None:
                continue
            trips[row["trip_id"]] = {
                "route": route["id"],
                "service": self._service(feed, row["service_id"])["id"],
                "headsign": row.get("trip_headsign") or None,
                "short_name": row.get("trip_short_name") or None,
                "direction": int(row["direction_id"]) if row.get("direction_id") else None,
            }
        return trips

    def _stop_times(self, feed, archive, trips):
        rows = defaultdict(list)
        for row in read_table(archive, "stop_times.txt"):
            trip_id = row["trip_id"]
            if trip_id not in trips:
                continue
            stop = self.stops.get((feed, row["stop_id"]))
            flags = (0 if row.get("pickup_type") == "1" else PICKUP_OK) | \
                    (0 if row.get("drop_off_type") == "1" else DROP_OFF_OK)
            rows[trip_id].append((
                int(row["stop_sequence"]),
                stop["id"] if stop else None,
                parse_time(row.get("arrival_time", "")),
                parse_time(row.get("departure_time", "")),
                flags,
            ))
        for trip_id, stop_times in rows.items():
            self._add_trip(feed, trip_id, trips[trip_id], sorted(stop_times))

    def _add_trip(self, feed, trip_id, trip, stop_times):
        if len(stop_times) < 2 or any(s[1] is None for s in stop_times):
            self.skipped_trips += 1
            return
        arrivals = [s[2] if s[2] is not None else s[3] for s in stop_times]
        departures = [s[3] if s[3] is not None else s[2] for s in stop_times]
        arrivals, departures = fill_missing(arrivals), fill_missing(departures)
        if arrivals is None or departures is None:
            self.skipped_trips += 1
            return
        stops = tuple(s[1] for s in stop_times)
        flags = list(s[4] for s in stop_times)
        flags[0] &= PICKUP_OK  # nobody gets off at the first stop or on at the last
        flags[-1] &= DROP_OFF_OK
        key = (trip["route"], stops, tuple(flags))
        pattern = self.patterns.get(key)
        if pattern is None:
            pattern = {"route": trip["route"], "stops": stops, "flags": tuple(flags),
                       "headsign": trip["headsign"], "direction": trip["direction"], "trips": []}
            self.patterns[key] = pattern
        self.trip_ids.add((feed, trip_id))
        pattern["trips"].append({
            "gtfs_id": trip_id, "service": trip["service"], "headsign": trip["headsign"],
            "short_name": trip["short_name"], "arrivals": arrivals, "departures": departures,
        })

    def _transfers(self, feed, archive):
        for row in read_table(archive, "transfers.txt"):
            if row.get("from_trip_id") or row.get("to_trip_id") or row.get("from_route_id"):
                continue  # trip-to-trip (in-seat) transfers aren't modelled yet
            if row.get("transfer_type") == "3":
                continue  # transfer not possible
            seconds = int(row["min_transfer_time"]) if row.get("min_transfer_time") else None
            source = self.stops.get((feed, row["from_stop_id"]))
            target = self.stops.get((feed, row["to_stop_id"]))
            if not source or not target:
                continue
            if source["id"] == target["id"]:
                if seconds is not None:
                    self.station_changes[source["id"]] = seconds
            else:
                # None: the feed allows the transfer without saying how long it takes (write() walks it).
                self.gtfs_transfers.append((source["id"], target["id"], seconds))

    # ------------------------------------------------------------ output

    def split_overtaking(self):
        """
        RAPTOR assumes trips in a pattern never overtake each other. Sorts
        each pattern's trips by start time and moves any trip that would
        pass the one before it at some stop into its own sub-pattern.
        """
        result = []
        for pattern in self.patterns.values():
            groups = []
            for trip in sorted(pattern["trips"], key=lambda t: t["departures"][0]):
                for group in groups:
                    last = group[-1]
                    if all(a <= b for a, b in zip(last["departures"], trip["departures"])) and \
                            all(a <= b for a, b in zip(last["arrivals"], trip["arrivals"])):
                        group.append(trip)
                        break
                else:
                    groups.append([trip])
            for group in groups:
                result.append({**pattern, "trips": group})
        return result

    def walking_transfers(self, stops):
        """Links between stops within WALK_RADIUS_M, using a grid to find neighbours.

        A cell is WALK_RADIUS_M on each side: a degree of longitude is shorter than one of latitude away from the
        equator (about 84 km at 40.7 N), so its cells are wider in degrees, sized for the stop farthest from the
        equator so no pair within the radius is ever two cells apart.
        """
        cell_lat = WALK_RADIUS_M / 111_000
        far = max((abs(s["lat"]) for s in stops), default=0.0)
        cell_lon = WALK_RADIUS_M / (111_000 * max(math.cos(math.radians(far)), 0.01))
        grid = defaultdict(list)
        for s in stops:
            grid[(int(s["lat"] / cell_lat), int(s["lon"] / cell_lon))].append(s)
        links = {}
        for s in stops:
            cy, cx = int(s["lat"] / cell_lat), int(s["lon"] / cell_lon)
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    for t in grid.get((cy + dy, cx + dx), ()):
                        if t["id"] == s["id"]:
                            continue
                        same_station = s.get("parent") and s.get("parent") == t.get("parent")
                        d = distance_m(s["lat"], s["lon"], t["lat"], t["lon"])
                        if same_station:
                            seconds = self.station_changes.get(s["parent"], DEFAULT_STATION_CHANGE_SECONDS)
                        elif d <= WALK_RADIUS_M:
                            seconds = walk_seconds(d)
                        else:
                            continue
                        links[(s["id"], t["id"])] = seconds
        return links

    def write(self, output, region):
        tmp = output + ".part"
        for path in (output, tmp):
            if os.path.exists(path):
                os.remove(path)
        db = sqlite3.connect(tmp)
        db.executescript("PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;" + SCHEMA)

        db.executemany("INSERT INTO feed VALUES (?,?,?,?)", [
            (fid, name, FEEDS.get(name, name), ",".join(sorted(self.feed_versions[name])) or None)
            for name, fid in self.feeds.items()
        ])

        # Only stops some trip uses, plus the stations those stops belong to.
        patterns = self.split_overtaking()
        used = {stop for p in patterns for stop in p["stops"]}
        all_stops = {s["id"]: s for s in self.stops.values()}
        used |= {all_stops[s]["parent"] for s in used if all_stops[s].get("parent")}
        stops = [s for s in all_stops.values() if s["id"] in used]
        db.executemany("INSERT INTO stop VALUES (?,?,?,?,?,?,?,?,?)", [
            (s["id"], s["feed"], s["gtfs_id"], s["name"], round(s["lat"], 6), round(s["lon"], 6),
             s.get("parent"), s["is_station"],
             self.station_changes.get(s["id"],
                                      self.station_changes.get(s.get("parent"), DEFAULT_CHANGE_SECONDS)))
            for s in stops
        ])

        used_routes = {p["route"] for p in patterns}
        db.executemany("INSERT INTO route VALUES (?,?,?,?,?,?,?,?,?)", [
            (r["id"], r["feed"], r["gtfs_id"], r["short_name"], r["long_name"], r["type"],
             r["color"], r["text_color"], r["sort_order"])
            for r in self.routes.values() if r["id"] in used_routes
        ])

        used_services = {t["service"] for p in patterns for t in p["trips"]}
        db.executemany("INSERT INTO service VALUES (?,?,?,?,?,?)", [
            (s["id"], s["feed"], s["gtfs_id"], s["days"], s["start_date"], s["end_date"])
            for s in self.services.values() if s["id"] in used_services
        ])
        db.executemany("INSERT INTO service_date VALUES (?,?,?)", [
            (service, date, added) for (service, date), added in self.service_dates.items()
            if service in used_services
        ])

        trip_count = 0
        for pid, p in enumerate(patterns, start=1):
            db.execute("INSERT INTO pattern VALUES (?,?,?,?,?,?)",
                       (pid, p["route"], p["headsign"], p["direction"],
                        encode_uints(p["stops"]), bytes(p["flags"])))
            db.executemany("INSERT INTO pattern_stop VALUES (?,?,?)",
                           [(stop, pid, i) for i, stop in enumerate(p["stops"])])
            rows = []
            for t in p["trips"]:
                trip_count += 1
                rows.append((trip_count, pid, t["service"], t["gtfs_id"],
                             t["headsign"] if t["headsign"] != p["headsign"] else None,
                             t["short_name"], t["departures"][0],
                             encode_times(t["arrivals"], t["departures"])))
            db.executemany("INSERT INTO trip VALUES (?,?,?,?,?,?,?,?)", rows)

        links = self.walking_transfers(stops)
        by_id = {s["id"]: s for s in stops}

        def transfer_seconds(source, target, seconds):
            """The feed's time, else the walk the grid found, else the walk between the two (never 0 seconds)."""
            if seconds is not None:
                return seconds
            if (source, target) in links:
                return links[(source, target)]
            a, b = by_id.get(source), by_id.get(target)
            if a is None or b is None:
                return DEFAULT_STATION_CHANGE_SECONDS
            return walk_seconds(distance_m(a["lat"], a["lon"], b["lat"], b["lon"]))

        for source, target, seconds in self.gtfs_transfers:
            if source in used and target in used:
                links[(source, target)] = transfer_seconds(source, target, seconds)
        # GTFS transfers between stations apply to their platforms too.
        children = defaultdict(list)
        for s in stops:
            if s.get("parent"):
                children[s["parent"]].append(s["id"])
        for source, target, seconds in self.gtfs_transfers:
            for a in children.get(source, ()):
                for b in children.get(target, ()):
                    links[(a, b)] = transfer_seconds(a, b, seconds)
        db.executemany("INSERT INTO transfer VALUES (?,?,?)",
                       [(a, b, s) for (a, b), s in links.items()])

        valid = [s for s in self.services.values() if s["id"] in used_services and s["start_date"]]
        dates = [d for (_, d) in self.service_dates]
        db.executemany("INSERT INTO meta VALUES (?,?)", [
            ("region", region),
            ("built_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
            ("valid_from", str(min([s["start_date"] for s in valid] + dates, default=0))),
            ("valid_to", str(max([s["end_date"] for s in valid] + dates, default=0))),
            ("attribution", "Schedules: MTA, under the MTA data feeds terms"),
        ])
        db.commit()
        db.executescript(INDEXES)
        db.commit()
        db.execute("VACUUM")
        db.close()
        os.replace(tmp, output)
        counts = {"stops": len(stops), "routes": len(used_routes), "patterns": len(patterns),
                  "trips": trip_count, "transfers": len(links), "skipped_trips": self.skipped_trips}
        print("    " + ", ".join(f"{v} {k}" for k, v in counts.items()), file=sys.stderr)
        return counts


def build(output, region, feeds):
    compiler = Compiler()
    for name, path in feeds:
        compiler.add(name, path)
    return compiler.write(output, region)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("output")
    parser.add_argument("region")
    parser.add_argument("feeds", nargs="+", metavar="name=feed.zip")
    args = parser.parse_args()
    feeds = []
    for spec in args.feeds:
        name, _, path = spec.partition("=")
        if name not in FEEDS or not path:
            parser.error(f"bad feed '{spec}'; expected one of {', '.join(FEEDS)}=<zip>")
        feeds.append((name, path))
    build(args.output, args.region, feeds)


if __name__ == "__main__":
    main()
