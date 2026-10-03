#!/usr/bin/env python3
"""Writes the GTFS-realtime test fixtures with the official encoder.

    pip install gtfs-realtime-bindings
    python3 make_fixtures.py

Fixtures are committed; rerun only to change them. GtfsRealtimeTest checks
the Kotlin parser against these bytes.
"""

from google.transit import gtfs_realtime_pb2 as rt

NOW = 1_790_985_600

feed = rt.FeedMessage()
feed.header.gtfs_realtime_version = "2.0"
feed.header.timestamp = NOW

e = feed.entity.add(id="subway-1")
tu = e.trip_update
tu.trip.trip_id = "050950_R..N93R"
tu.trip.route_id = "R"
tu.trip.start_date = "20261006"
tu.trip.direction_id = 0
s = tu.stop_time_update.add(stop_id="R31N")
s.arrival.time = NOW + 100
s.departure.time = NOW + 130
s = tu.stop_time_update.add(stop_sequence=5, stop_id="R30N")
s.arrival.delay = -45
s = tu.stop_time_update.add(stop_id="R29N")
s.schedule_relationship = rt.TripUpdate.StopTimeUpdate.SKIPPED

e = feed.entity.add(id="lirr-1")
e.trip_update.trip.trip_id = "GO202_26_1916"
e.trip_update.delay = 300
e.trip_update.timestamp = NOW - 20

e = feed.entity.add(id="canceled")
e.trip_update.trip.trip_id = "GONE"
e.trip_update.trip.schedule_relationship = rt.TripDescriptor.CANCELED

e = feed.entity.add(id="vehicle")
e.vehicle.trip.trip_id = "050950_R..N93R"
e.vehicle.position.latitude = 40.6
e.vehicle.position.longitude = -74.0

e = feed.entity.add(id="deleted", is_deleted=True)
e.trip_update.trip.trip_id = "DELETED"

data = feed.SerializeToString()
# An unknown length-delimited field (like NYCT's extension 1001) must be skipped.
data += bytes([0xCA, 0x3E, 0x03, 0x01, 0x02, 0x03])
open("trip_updates.pb", "wb").write(data)

feed = rt.FeedMessage()
feed.header.gtfs_realtime_version = "2.0"
feed.header.timestamp = NOW
e = feed.entity.add(id="lmm:planned_work:1")
a = e.alert
p = a.active_period.add(start=NOW - 3600, end=NOW + 3600)
a.active_period.add(start=NOW + 86_400)
a.informed_entity.add(agency_id="MTASBWY", route_id="A")
a.informed_entity.add(stop_id="A27")
ie = a.informed_entity.add()
ie.trip.route_id = "C"
a.effect = rt.Alert.REDUCED_SERVICE
a.header_text.translation.add(text="<p>A trains run local</p>", language="en-html")
a.header_text.translation.add(text="A trains run local", language="en")
a.description_text.translation.add(text="Café détour ✓", language="en")
open("alerts.pb", "wb").write(feed.SerializeToString())
