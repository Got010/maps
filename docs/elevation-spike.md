# Elevation for walking and cycling: spike

Phase 7 asks for an elevation profile for cycling and "needs an elevation source in the pack". This is the spike: what Valhalla can do with elevation, what it costs for New York plus northern New Jersey, and what to build. Nothing here is implemented in the app or pipeline yet. Numbers marked *measured* come from real data fetched while writing this; *estimate* is reasoning; *unverified* means read from source or docs but not run.

## Short answer

Do two separate things, because they need very different amounts of data:

1. **Bake grades into the routing tiles at build time (CI only).** Valhalla's `valhalla_build_tiles` has an elevation stage that reads HGT tiles and writes each road's grade into the tile. The app then gets "Avoid hills", grade-aware cycling speeds and ETAs, and the wheelchair grade cap with **no extra bytes on the phone** (the grade fields already exist in every tile's edge records). Cost: a ~175 MB download and some extra build time in the Actions job.
2. **For the profile chart, ship a small elevation raster and sample it on the phone along the route.** Recommended: 6 arc-second (about 185 m) cells for the 29 land tiles, about **8 MB** deflated *(measured on 5 tiles, extrapolated)*, as a table in `search-nys.sqlite`. A 3 arc-second version is about 24 MB if the chart needs finer detail. Do not ship Valhalla's own HGT tiles to the phone (see below).

Step 1 alone makes today's "Avoid hills" switch work. Step 2 only adds the chart and total climb.

## What is in the app today

The routing pack is built by `pipeline/routing/build.sh` with Valhalla 3.6.3 and **no elevation data**. Without elevation, every edge has grade 0, so:

- Cycling `use_hills` has no effect. The new "Avoid hills" switch is sent (`use_hills` 0.1) but changes nothing, and its note in the sheet says so. When step 1 ships, delete that note (`options_bike_hills_note`).
- Walking step-free's `use_hills` 0.1 and the wheelchair `max_grade` cap (12 percent by default) are likewise inert.
- Bike ETAs ignore climbs and descents.

## How Valhalla uses elevation

All from Valhalla 3.6.3 (the version valhalla-mobile 0.6.3 pins), read from its source and docs.

| Use | Needs elevation where | Notes |
| --- | --- | --- |
| Per-edge grade for costing (`use_hills`, bike speed by grade, `max_grade`) | **Build time only.** `ElevationBuilder` (a `valhalla_build_tiles` stage) reads `additional_data.elevation` and writes `weighted_grade`, `max_up_slope` and `max_down_slope` into each directed edge | If `additional_data.elevation` is unset the stage does nothing. The fields are fixed-width bits in `DirectedEdge`, so tile size does not change |
| `elevation_interval` on a `route` request: an `elevation` array per leg, one sample every N meters | **Runtime**: samples HGT tiles from `additional_data.elevation` | Docs recommend 30 m, "matching the default data source's resolution". Needs the HGT files on the phone |
| `height` action: elevations under a shape | **Runtime**, same HGT tiles | valhalla-mobile's README (main branch) lists `height` for Android and says "`height` needs a directory of elevation tiles in the config; without them every height is null". *Unverified* that 0.6.3, the version we pin, already has it |

So the routing-quality half of elevation is nearly free on the phone, and only the half that returns elevation numbers needs data there.

## Data source and size

`valhalla_build_elevation` downloads Tilezen/Mapzen "skadi" tiles from `https://elevation-tiles-prod.s3.us-east-1.amazonaws.com/skadi/N40/N40W074.hgt.gz`. Each tile is a 1 degree by 1 degree HGT: 3601 x 3601 big-endian int16 samples (1 arc-second, about 30 m), 25.9 MB uncompressed *(measured)*. Files are named for their south-west corner. The script can keep them gzipped (default), decompress them (`-d`), or recompress as lz4 (`-z`, about 12 percent bigger than gzip, "several times faster to decompress"). `--from-bbox` snaps the box outward to whole degrees, `--from-geojson` needs shapely, and `--from-tiles` needs a finished graph (so it cannot be used to build the first one).

Download sizes *(measured with HEAD requests against the real bucket)*:

| Area | Tiles | Gzip download | Uncompressed |
| --- | --- | --- | --- |
| Snapped box of the `nys` region (`-79.77,40.30,-71.75,45.02` becomes lon -80..-71, lat 40..46) | 54 | 398 MB | about 1.4 GB |
| Only the tiles that hold New York or northern New Jersey land | 29 | **175 MB** | **about 750 MB** |

The 29 tiles, as `N<lat>W<lon>`: N40 W075 W074 W073 W072; N41 W076 W075 W074 W073 W072; N42 W080 W079 W078 W077 W076 W075 W074; N43 W080 W079 W078 W077 W076 W075 W074; N44 W077 W076 W075 W074; N45 W075 W074. This list is by hand from the state outline (the earlier "13 to 16 tiles at 25 MB" guess undercounted, since New York spans nine degrees of longitude), so check it against the finished graph's bounding tiles with `--from-tiles` once.

Shipping those HGT files to the phone is what the `elevation_interval` and `height` route need. At 750 MB uncompressed, or 175 MB gzipped with every sample paying for a gunzip of a whole tile, or lz4 at roughly 200 MB, it is bigger than the routing pack itself for a feature that draws one chart. **Not recommended.**

## Options

| | What the phone gets | Pack growth | Gives | Costs |
| --- | --- | --- | --- | --- |
| A. Bake grades at build time | Nothing new | 0 | Avoid hills, grade-aware bike ETA, wheelchair grade cap | CI: download 175 MB, extra build stage |
| B. Valhalla HGT tiles in a pack | Elevation dir + config key | 175 to 750 MB | `elevation_interval` on routes, `height` | Huge; slow reads; needs valhalla-mobile config plumbing |
| C. Coarse raster, sampled on the phone | A small table | 8 MB (6") or 24 MB (3") | Profile chart, total ascent and descent, walking too | Pipeline script, a sampler in Kotlin, a chart |
| D. Profile from baked grades via `trace_attributes` | Nothing new | 0 | Relative profile (no absolute heights) | Grades are one of 16 buckets per edge, so errors add up on long routes; needs `trace_attributes` in valhalla-mobile 0.6.3 *(unverified)* |

### Raster sizes (option C), measured

Downloaded five representative tiles (NYC, Hudson Valley, N42W075, N43W075, North Country N44W074), averaged to coarser cells, stored as int16 metres, delta-coded along rows and deflated (zlib 9):

| Cell size | Cells per tile | Raw | Deflated, land-heavy tile | Deflated, NYC tile (mostly water) |
| --- | --- | --- | --- | --- |
| 3 arc-second (about 90 m) | 1200 x 1200 | 2.9 MB | 0.88 to 1.04 MB | 0.30 MB |
| 6 arc-second (about 185 m) | 600 x 600 | 0.72 MB | 0.28 to 0.33 MB | 0.10 MB |
| 9 arc-second (about 280 m) | 400 x 400 | 0.32 MB | 0.14 to 0.16 MB | 0.05 MB |

Extrapolating to the 29 tiles (about 20 full land tiles, the rest partial): **3": about 24 MB, 6": about 8 MB, 9": about 4 MB**. Halving the vertical precision to 2 m cuts each by about a third, at the cost of a staircase in flat areas, so not worth it. *Estimate*: the real number comes from running the script on all 29 tiles.

A 6" cell is 185 m north-south and about 135 m east-west at 43 degrees north. That is plenty for "this route climbs 120 m, steepest near the end" and for Hudson Valley or Catskill hills. It smooths anything narrower than a couple of city blocks: bluffs, cuts and a bridge approach. Use 3" if that matters.

## Recommendation

1. **Now (cheap, unlocks real behaviour): option A.** In `pipeline/routing/build.sh`: fetch the 29 `.hgt.gz` tiles into `<work>/elevation/N40/N40W074.hgt.gz` and so on (plain `curl` loop, no Python needed; the layout is Valhalla's own), pass `--additional-data-elevation /data/$WORK/elevation` to `valhalla_build_config` (the key is `additional_data.elevation`), and leave the rest of the command alone. Gzipped tiles are read directly. Check in the log that the elevation stage ran, and spot-check `trace_attributes` or the route's speed on a known hill. The pack should be the same size (compare `report_pack` before and after). Estimated extra build time: tens of minutes on a 2-core runner (*unmeasured*, the data job has a 5 hour timeout). Then remove the "changes nothing yet" note from the Avoid hills switch.
2. **Then, if the chart is wanted: option C at 6 arc-second.** A pipeline step (`pipeline/search/elevation.py`) downloads the same 29 tiles, averages 6x6, writes `elevation(row, col, z BLOB)` blocks of 100 x 100 cells into `search-nys.sqlite`, plus a one-row `elevation_meta` for the origin and cell size. On the phone a `ElevationProfile` samples bilinearly along the selected route's geometry every 100 m and returns a list of (distance, metres), total ascent and descent. The search pack grows by about 8 MB. It needs a schema bump (search pack schema 3) and a decision on whether older packs simply show no chart.

Option B is out. Option D is the fallback if the raster is judged too much, but it only gives a relative chart.

Licensing: SRTM and the USGS data in the skadi tiles are public domain; Tilezen asks for an attribution line (put it in the About screen and the pack manifest notes when step 2 ships).

## Unverified

- That `valhalla_build_tiles` 3.6.3 runs its elevation stage from `additional_data.elevation` alone, and the exact `valhalla_build_config` flag spelling. The stage's source reads `additional_data.elevation` and returns without it; nobody has run the build here.
- That 0.6.3 of valhalla-mobile exposes `height` and `trace_attributes` (its main-branch README does).
- That the 29-tile list covers every land tile of the `nys` clip; a missing tile only means grade 0 there.
- Build time and the real raster size across all 29 tiles.
- The bike speed model really uses the baked grade (from the bicycle costing's grade-based speed factor); check that an uphill leg gets a longer ETA than its reverse after step 1.
