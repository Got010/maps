# Spike: live speeds in on-device Valhalla

HANDOFF Phase 7, "Traffic-aware ETAs, then routing", and the Risks row "Live
traffic in on-device Valhalla is unverified". The question: can
valhalla-mobile 0.6.3 (Valhalla 3.6.3) route with live NYC DOT speeds on the
phone, and what would it take?

**Short answer:** probably yes, with no native code. Valhalla reads live
speeds from a memory-mapped `traffic.tar` that sits next to the tile extract,
and the app can write that file from Kotlin. Nothing about this has run on a
phone yet. Three things decide it: whether valhalla-mobile's config accepts
`mjolnir.traffic_extract`, whether writes from Kotlin reach Valhalla's mapping,
and whether NYC DOT links match onto Valhalla edges well enough. A one-day
probe (below) answers all three before we commit to the full build (about a
week). Until then, the app re-ranks Valhalla's routes by live delay, which
ships in this change.

Status of each claim:

- **Checked** means read in this repo or measured here.
- **From memory** means it comes from Valhalla's sources and docs as I
  remember them. I couldn't open them in this session: the valhalla-mobile AAR
  isn't in the Gradle cache, and fetching it from Maven Central was blocked
  in the dev container. Check these against the `3.6.3` tag before writing
  code.

## 1. How Valhalla does live traffic (from memory)

- **The traffic tar.** `mjolnir.traffic_extract` in the Valhalla config names
  a tar with one entry per routing tile, at the same path as the tile
  (`2/000/753/123.gph`). `GraphReader` memory-maps it (`midgard::tar`,
  `MAP_SHARED`) next to `mjolnir.tile_extract`. A tile missing from the
  traffic tar just has no live speeds.
- **The entry layout** (`baldr/traffictile.h`). Each entry is a 32-byte
  `TrafficTileHeader` followed by one 64-bit `TrafficSpeed` per directed edge,
  in the tile's directed-edge order:
  - The header holds `tile_id` u64, `last_update` u64,
    `traffic_tile_version` u32 (must equal `TRAFFIC_TILE_VERSION`, 3 as I
    recall), `directed_edge_count` u32, and two spare u32s.
  - Each `TrafficSpeed` is a bitfield: `overall_encoded_speed:7`,
    `encoded_speed1..3:7` (km/h ÷ 2; 127 means unknown; 0 means closed),
    `breakpoint1:8`, `breakpoint2:8` (fractions of the edge ÷ 255, for up to
    three sub-segment speeds), `congestion1..3:6`, `has_incidents:1`, `spare:1`.
  - An all-zero record has no live speed, because validity is tested as
    `breakpoint1 != 0`. One speed for a whole edge is
    `overall = speed1 = kph >> 1`, `breakpoint1 = 255`, the rest unknown.
- **Building the file.** `valhalla_build_extract -c valhalla.json
  --with-traffic` writes a traffic tar matching the tile extract, with
  headers filled in and every speed zeroed (no live data).
  `valhalla_traffic_demo_utils` (`--get-tile-id`, `--get-traffic-dir`,
  `--generate-live-traffic tile,edge,speed`) is Valhalla's reference for
  finding an edge's tile and index and writing one record. Its source is the
  best spec for the encoding.
- **Updating while running.** The design is that another process rewrites
  speeds in place while Valhalla serves routes. Readers load each record as a
  `volatile` 64-bit value, and nothing has to reload.
- **Costing.** `costing_options.<auto|truck>.speed_types` selects the speed
  sources. The default is all of `["freeflow","constrained","predicted","current"]`,
  so `current` (live) is already on. Internally it becomes the costing's
  `flow_mask`. Live speed fades into the predicted or free-flow speed over the
  first hour of the route (`LIVE_SPEED_FADE = 1/3600` in `GraphTile::GetSpeed`),
  so a jam an hour away barely counts. Ferrostar's request carries no
  `date_time`. Adding `"date_time":{"type":0}` (depart now) makes sure live
  speeds apply. It also switches Valhalla from bidirectional A* to
  time-dependent forward A*, which is slower on long trips. Measure this on the
  phone.

## 2. Can valhalla-mobile do it?

- **Checked.** `OfflineRouter` builds the engine with
  `Valhalla(context, ValhallaConfigFactory.usingTileExtract(path), ValhallaConfigManager(...))`.
  The config is a `valhalla-models-config` 0.5.2 object, which valhalla-mobile
  writes out as Valhalla's JSON config. Routing goes through `routeRaw(body)`,
  so any request field can be added (as `exclude_polygons` already is).
- **From memory.** valhalla-mobile has no traffic API: no call to set or
  update speeds. Its config model is generated from Valhalla's config schema,
  so `mjolnir` should have a `trafficExtract` field
  (`config.copy(mjolnir = config.mjolnir.copy(trafficExtract = ...))`). If it
  doesn't, write the JSON config file ourselves. The native side is stock
  Valhalla `actor_t`, and traffic support is in `baldr` with no build flag, so
  the Android build should honour `traffic_extract` like any other.
- **Writing from the app.** No JNI is needed. Open the traffic tar with
  `RandomAccessFile(...).channel.map(READ_WRITE, ...)`, which is also a
  `MAP_SHARED` mapping of the same file, so Valhalla sees the same pages. Parse
  the tar's 512-byte headers once to find each tile's entry offset, then
  `putLong(entry + 32 + 8 * edgeIndex, record)` (little-endian). Do the writes
  under the same `synchronized(valhalla)` lock that `OfflineRouter.routes`
  holds, so a route never sees a half-written update. The lock also covers us
  because Android has no `byteBufferViewVarHandle` before API 33.

## 3. The map-matching problem

NYC DOT gives about 150 sensor links (count from memory). Each is a polyline
(`link_points`, often coarse and sometimes cut short) with a speed and a
travel time. Valhalla wants a speed per directed edge id, so the pipeline
needs a link-to-edge table:

1. **At build time** (`pipeline/routing/build.sh`, same Docker image, right
   after `valhalla_build_extract`):
   - Fetch the DOT feed once to get each link's geometry. The links are
     stable; their speeds aren't needed here.
   - Send each polyline to Valhalla's `trace_attributes`
     (`shape_match: map_snap`, costing `auto`, `filters` for `edge.id`,
     `edge.length`, `edge.begin_shape_index` and `edge.end_shape_index`).
     Either `valhalla_service valhalla.json trace_attributes '<json>'` in
     one-shot mode or pyvalhalla's `Actor` works.
   - Decode each `edge.id` (a GraphId: level 3 bits, tile 22 bits, index
     21 bits) into a tile and an index. Write
     `link_id → [(tile_path, edge_index, fraction_of_link)]`.
   - Report how many links matched and how much of each link's length the
     match covers. Drop poor matches rather than guess.
2. **Ship it with the routing pack.** It's about 150 links × ~20 edges, so
   tens of KB of JSON. Graph ids change on every tile build, so the table only
   holds for the `routing-<region>.tar` it was built with. Ship it next to that
   tar, or inside the routing pack's release, and have the app ignore it when
   the pack versions differ.
3. **The traffic tar itself.** It is headers plus zeros. Two options:
   - The pipeline ships a small per-tile list of `(tile_path, tile_id,
     directed_edge_count)`, and the phone writes the tar into app files storage
     on first use. There is no download, and the file stays writable. This is
     the one to build.
   - Ship `valhalla_build_extract --with-traffic`'s output, compressed.
     Zeros compress to almost nothing, but on disk it's 8 bytes per directed
     edge across the whole graph, likely tens of MB for `nys`. Keeping only the
     tiles NYC DOT touches (about a dozen at levels 0 to 2) brings it to a few
     MB.
4. **On each feed poll** (`TRAFFIC_POLL_MS`, 60 s; NYC DOT itself updates
   every few minutes):
   - For every link with a fresh speed, write its edges' records. That's
     about 3,000 eight-byte writes.
   - Zero every edge whose link is stale, missing, or turned off. The file
     persists, so a jam must never outlive its data. Do the same on start, and
     when the traffic toggle goes off.
   - Speeds should be the link's mph converted to km/h, not the "delay" model
     `RouteTraffic` uses.

## 4. What changes in the app if it works

- **No double counting.** With live speeds in Valhalla, route durations and
  Ferrostar's `durationRemaining` already include traffic on the NYC DOT links.
  The "+X min" from `RouteTraffic`/`TripTraffic` would then count it twice. The
  ETA would have to switch to Valhalla's duration and keep the DOT delay only
  for the colouring, or show "typical" from a second, no-traffic route.
- **Reroutes and alternatives.** These avoid jams by themselves, including
  Ferrostar's off-route reroutes. Today those take Valhalla's first route,
  which knows nothing of traffic (Ferrostar's reroutes through
  `Navigator`'s route provider, and `rerouteAround`/`skipNextStop` use
  `routes.first()`).
- **Faster-route detours.** The "faster route" check's detour squares
  (`FASTER_ROUTE_TRY_DETOUR_SECONDS`) become unnecessary.
- **Coverage stays narrow.** NYC DOT links are mostly NYC highways and major
  arterials. Upstate and local streets get nothing. The opt-in TomTom flow
  tiles would need on-phone map matching of vector tiles, which is a separate
  and larger job.

## 5. The cheaper alternatives

1. **Re-rank Valhalla's alternatives by live delay.** Done in this change.
   `TrafficRanking.order`, used by route preview, moves an alternative to the
   front when duration plus live delay makes it at least 2 minutes faster than
   Valhalla's first route, and it also has at least 2 minutes less delay, so
   nothing changes without traffic. Previously the options carried their delay
   but index 0 was always Valhalla's pick. The rules:
   - It doesn't re-rank when tolls, highways or ferries are avoided. Those are
     soft penalties in Valhalla, and an alternative may break them.
   - It never puts a route with more plate readers in front when "avoid plate
     readers" is on.

   Its limits:
   - It only chooses among the 1 to 3 routes Valhalla found without traffic.
   - It doesn't apply to reroutes.
2. **Steer around the worst stretches with `exclude_polygons`.** Done already,
   mid-trip only: the faster-route check squares off slow links when the route
   ahead has 2+ minutes of delay. Exclusions are hard. A jam on the only way
   in makes the route fail or go far around, and the squares share Valhalla's
   10 km perimeter limit with closures and plate-reader squares. Valhalla 3.6.3
   (the version valhalla-mobile 0.6.3 ships) does have a soft per-edge penalty,
   `linear_cost_factors` on matched lines (see the reader-aware routing item in
   HANDOFF.md), but it is untried here; the traffic tar is the other soft option.
3. **The ETA from live speeds on the route ahead.** Done in this change.
   `TripTraffic` matches once per route or snapshot and trims to the remaining
   route every 250 m. It is the same delay the "faster route" check compares.

## 6. Recommendation and estimate

Keep 1 to 3 (they ship now). Run a **one-day probe** before committing to the
full build:

1. Pipeline: add `--with-traffic` to `valhalla_build_extract` for
   `routing-nyc`, and publish `traffic-nyc.tar` as a test asset (half a day).
2. App: behind a Settings > Live data "Traffic routing (experimental)" switch
   (no `run-as`; it must work on the release build):
   - Pass `traffic_extract` in the config.
   - Write 5 km/h on every edge of one known FDR Drive stretch. Find the edges
     with `trace_attributes` in the pipeline, hard-coded for the probe.
   - Add `date_time: {type: 0}` to requests.
   - Check on the phone that the Midtown-to-Lower-Manhattan route leaves the
     FDR and that its duration rises.
   (Half a day.)

If the probe passes, the full build is about **5 to 7 days**:

| Piece | Estimate |
| --- | --- |
| Pipeline link-to-edge table with match report, and the per-tile edge-count list, in `routing/build.sh` | 1.5 days |
| `core/traffic` `TrafficTar` writer: tar index, `TrafficSpeed` encoding, stale-clearing; unit-tested against a tar written by `valhalla_build_extract` in CI | 1.5 days |
| Config plumbing in `OfflineRouter`, `date_time`, pack-version pairing of the table | 1 day |
| ETA rework to avoid double counting, plus "typical" | 1 day |
| On-phone verification, routing-time check of time-dependent A* on `nys` | 1 day |

If the probe fails, the fallbacks are:

- If valhalla-mobile can't take `traffic_extract`, raise it upstream: the
  config is the only gap.
- If writes aren't visible to Valhalla, re-create the router after each poll.
  This is slow, about a second per tile-extract open, but it's once a minute.
- Otherwise stay on re-ranking plus detour squares.

## 7. Unverified, to check first

- `TrafficSpeed` bit layout, the validity rule (`breakpoint1 != 0`) and
  `TRAFFIC_TILE_VERSION` at Valhalla 3.6.3.
- Whether `valhalla-models-config` 0.5.2's `mjolnir` has `trafficExtract`.
- Whether `traffic_extract` is opened `MAP_SHARED` on Android, and whether
  Kotlin's mapping sees the same pages (the probe tests this).
- Whether live speeds apply without `date_time` in the request, and how much
  slower time-dependent A* is on the phone.
- How many NYC DOT links match cleanly with `trace_attributes`, given the
  coarse and truncated `link_points`.
- The cost figures in `TripTraffic` were measured on a desktop JVM (about
  10 ms to match 200 links on a 5,000-point route, under 1 ms to trim), not
  on a phone.
