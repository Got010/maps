# Scope: the 48 contiguous states

Owner's ask (October 2026): cover the 48 contiguous states (plus DC) with the features New York has today.
This is a scoping document, not a plan of record: nothing here is built. Sizes marked *est.* are guesses to
check with a measured spike before committing to them; the New York numbers are measured (release v0.1.21).

## Where New York stands (the baseline to scale)

| Piece | How it is built | New York, measured |
| --- | --- | --- |
| Basemap | Planetiler per region from Geofabrik extracts | 6 regions; each built in 3-6 min |
| Routing | One Valhalla tile extract (`routing-nys.tar`) | 861 MB, 4.5 min of tile building |
| Search | One SQLite pack: OSM, NYS Address Points, Overture places, landmarks | 831 MB, 28 min |
| Transit | MTA GTFS, RAPTOR on the phone | `transit-nyc.sqlite` |
| Live data | 511NY (CARS), NYC DOT speeds, MTA realtime, EIA gas prices | per-source parsers |
| Cameras | Plate readers and speed cameras from OSM; DOT cameras from 511NY at build time | landmark table |

A full rebuild takes about 30 minutes of wall time and roughly 350 Actions minutes.

## What changes at national scale

### 1. Regions: one per state

- `pipeline/regions.json` becomes 49 regions (48 states + DC), each built from its Geofabrik state extract.
  Large states can split like New York does today (California, Texas). The region picker already handles
  several basemaps drawn as one map, and the updater fetches per ticked region.
- State boundaries for "which state is this point in" (`states.py`, now a hand-drawn NJ polygon): Census TIGER
  cartographic boundaries (public domain), simplified, shipped in the app.
- Basemaps: *est.* 20-30 GB for all of CONUS at today's zoom levels; a typical state is a few hundred MB. Every
  release file must stay under 2 GiB, which per-state packs do.

### 2. Routing: the hard part

Valhalla on the phone reads one tile extract today (`ValhallaConfigFactory.usingTileExtract`). A CONUS extract
is *est.* 15-25 GB: too big for one release file, most phones' spare storage, and a 2-core, 7 GB runner's memory
(a national Valhalla build wants *est.* 32+ GB of RAM).

Proposed design, to be proven by a spike:
- Valhalla's tiles are hierarchical: level 0 (highways, 4° tiles), level 1 (arterials, 1°), level 2 (local
  roads, 0.25°). Ship levels 0 and 1 for all of CONUS as one pack (*est.* 1.5-3 GB), and level 2 per state.
- The phone unpacks them into one tile directory and points Valhalla at it (`tile_dir` instead of
  `tile_extract`). Long trips then route on the national highway levels, and local roads exist wherever the
  driver has the state.
- Build: per-state Valhalla builds would cut tiles at state lines. Either build CONUS once on a bigger machine
  (a GitHub larger runner, which a private repo pays for) and split the result by tile, or build overlapping
  multi-state groups and take each state's own tiles.
- Spike first: does valhalla-mobile 0.6.3 accept `tile_dir` on Android; does a trip cross a missing level-2
  state cleanly; what do the levels really weigh.

### 3. Search and addresses

- One search pack per state, with the same tables. The app opens the packs it has and asks each (results merge
  by score; "nearby" asks the packs whose box contains the point).
- Addresses: New York uses the state's own file. Nationally, Overture's addresses theme (OpenAddresses and the
  USDOT National Address Database, varied licences per source; check before shipping) or NAD directly
  (public domain, but not every state contributes). Per-state official files where they exist, like NYS.
- Overture places: same reader, per-state bounds. Today's statewide pack is 483 MB of Overture alone; a large
  state would be several times that, so `OVERTURE_MIN_CONFIDENCE` or a category filter will matter.

### 4. Live data

- The privacy rule (whole-feed pulls, no location sent) holds only for sources that publish whole-state feeds.
- **WZDx** (USDOT's Work Zone Data Exchange) is a common standard many state DOTs publish openly: one parser
  for work zones and closures in many states. Inventory which states publish it.
- **511 systems** differ by vendor: several states run Castle Rock's CARS platform like New York now (the
  `FiveElevenNyCars` parser may carry over with a different host), others run IBI or their own. Each needs a
  check with the feed probe, as New York did.
- DOT cameras for "Avoid DOT cameras": `pipeline/search/dot_cameras.py` already keys sources by state; each
  state adds one URL once its feed is checked.
- Speeds: only NYC has an open speed feed today. Nationally there is no privacy-safe source; TomTom stays out
  (see HANDOFF).
- Gas prices: EIA already publishes by region nationwide.

### 5. Transit

National transit is its own project: hundreds of GTFS feeds (Mobility Database), a RAPTOR pack per metro area,
realtime per agency. Suggest metro-by-metro after driving works nationally.

## How other offline apps stay small

For scale: offline-first OSM apps keep the whole US to roughly 10 GB, a few hundred MB a state (Organic Maps,
CoMaps and OsmAnd, from general knowledge, not measured here). Southbound's New York today is about 2.5 GB:
`routing-nys.tar` 861 MB, `search-nys.sqlite` 831 MB, plus six basemaps. Where the difference comes from:

- **One file, shared geometry.** Organic Maps' `.mwm` and OsmAnd's `.obf` hold the map, the routing graph and the
  search index for a region in one compact binary file, so each road's geometry is stored once. Southbound
  stores roads three times: in the PMTiles basemap, in Valhalla's tiles and (names and positions) in the search
  pack.
- **Compact encodings.** Quantized, delta-coded coordinates; geometry simplified per zoom; only the tags the app
  shows. Valhalla's tiles carry rich per-edge data for its costing (and for traffic, elevation, restrictions),
  which makes them large.
- **Precomputed shortcuts instead of a big graph.** Vela Maps (a degoogled Android client) routes offline with
  OsmAnd's Java router over `.obf` files baked with highway-hierarchy shortcuts, so a 256 km trip resolves in
  about half a second within 256 MB of memory; its Delaware file with routing is about 71 MB
  ([Vela's spec](https://raw.githubusercontent.com/PimpinPumpkin/Vela/master/SPEC.md)). Vela also streams map
  tiles and places online when it can, and takes traffic, reviews and photos from Google: smaller downloads, but
  requests that reveal where you look, which Southbound's rules don't allow.
- **Small search indexes.** Prefix tries over names rather than SQLite FTS. Southbound's FTS5 indexes are already
  contentless with `detail='none'`; the weight is in what is stored and indexed. In the v0.1.21 statewide pack,
  Overture places are 482 MB of 831 (the table 356 MB, its trigram index 92 MB, its name index 34 MB), addresses
  206 MB, OSM features and their index about 107 MB.

What that means for Southbound, cheapest first:

1. **Trim Overture** (a setting): a higher confidence cut-off, no unread columns, no address in its search text.
   Measured by `pipeline/search/overture_sizes.py` (run by the feed probe workflow).
2. **Routing per state plus a national highway pack** (section 2), which is what the shortcut-based apps do in
   effect: long trips on a small top-level graph, local roads only where you are.
3. **Switching routing engines** (OsmAnd's router, as Vela did) would shrink routing the most but replaces
   Valhalla, which Ferrostar's turn-by-turn, lane guidance, closures-as-polygons and camera avoidance are built
   on. Only worth a spike if (2) falls short.
4. **Streaming** stays out, or opt-in like TomTom was: it breaks the rule that no request reveals where you are.

## Costs and limits

- **Actions minutes**: a national rebuild is *est.* 10-20 times today's, far past a private repo's 2,000 free
  minutes a month. Options: make the repository public (standard runners are free for public repos), rebuild
  only states whose source changed, or pay for minutes or larger runners.
- **Release files**: every file under 2 GiB (per-state packs do this); a release can hold the 150-200 files a
  national set needs.
- **Phone storage**: the driver picks states; a few states plus the national highway pack should fit in a few GB.

## Suggested order

1. Spike: a 3-4 state test (NY, NJ, PA, CT) with per-state basemaps and search, and the routing split above.
   Measure every size and build time.
2. Regions per state, the picker by state, state boundaries, multi-pack search.
3. National routing (highway levels plus per-state local roads).
4. Addresses beyond New York.
5. Live data: WZDx, then per-state 511 adapters and DOT cameras.
6. Transit, metro by metro.

## Decisions for the owner

- Public repository (free Actions minutes) or a paid build budget?
- Which states first after New York?
- How much phone storage to plan for?
