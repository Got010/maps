# Road pack (`road-us.sqlite`)

Built by `make -C pipeline road-us` (`road/build.sh us`): Geofabrik's extract of every state named in
`regions.json`, filtered with osmium, classified by `build_road.py`. It is built the way the Free Wi-Fi pack is
(`wifi/`), and nothing is shared with it at run time. Heavy work runs in GitHub Actions (`release.yml` builds and
ships it with the other packs; `road.yml` is the pull request check); nothing here runs on the phone.

## Categories

Every spot is a point: a node as it is, a way at its middle node (a closed way at the mean of its corners), a
relation at the mean of its first outer ring's corners. An object that fits two categories is a row in each.

| `category` | OSM rule | Fields kept in `extra` |
| --- | --- | --- |
| `camp` | `tourism=camp_site` | `fee`, `drinking_water`, `toilets`, `shower`, `reservation`, `opening_hours`, `operator`, `tents`, `caravans` |
| `rv` | `tourism=caravan_site` | the same as `camp`, and `sanitary_dump_station` |
| `rest` | `highway=rest_area`, `highway=services` | `toilets`, `drinking_water`, `opening_hours`, `operator` |
| `dump` | `amenity=sanitary_dump_station` | `fee`, `opening_hours` |
| `water` | `amenity=drinking_water` | `fee`, `seasonal` |
| `toilet` | `amenity=toilets` | `fee`, `opening_hours`, `wheelchair` |
| `shower` | `amenity=shower` | `fee`, `opening_hours` |
| `fuel` | `amenity=fuel` | `diesel` (`true` when `fuel:diesel=yes`), `hgv`, `opening_hours` |
| `truck` | `amenity=fuel` or `highway=services` with `hgv=yes`, or with a brand of `chains.json` | `shower`, `opening_hours` |
| `laundry` | `shop=laundry` | `opening_hours` |
| `library` | `amenity=library` | `opening_hours` |
| `pharmacy` | `amenity=pharmacy` | `opening_hours` |
| `hospital` | `amenity=hospital` | `emergency` |
| `clinic` | `amenity=clinic`; `amenity=doctors` with `healthcare=clinic` | `opening_hours` |
| `grocery` | `shop=supermarket` | `opening_hours` |
| `park` | `boundary=national_park`; `boundary=protected_area` with a `protect_class` from 2 to 6 (`1;4` counts: any class in the list); `leisure=park` only with `park:type=state_park` or "State Park" in the `operator` (any case) | `operator`, `fee` |

- `name` and `brand` are columns (`name` is the `name` tag only, `brand` the `brand` tag only; either may be NULL).
- The fields in `extra` are OpenStreetMap's own words, trimmed (`yes`, `no`, `customers`, an `opening_hours`
  string...). A field the object does not have, or has empty, has no key: **absent means unknown, never false**.
  `diesel` is the one field that is not a tag: `true`, or no key (`fuel:diesel=no` leaves no key; `hgv=no` stays
  as `"hgv": "no"`, since OpenStreetMap says it).
- Addresses, phone numbers, websites, e-mail and every other tag are dropped. An object tagged `access=private` or
  `access=no` is left out of every category (the Wi-Fi pack does the same); any other `access` value stays, and so
  does a place tagged as closed.
- A **truck stop** (`truck`) is a fuel station or a services area (`highway=services`) with `hgv=yes`, or whose
  `brand` is one of `chains.json`'s names (whole, ignoring case and apostrophe style) or whose `brand:wikidata` is
  one of its QIDs. A truck stop is also `fuel` (or `rest`).

## `chains.json` (edit this to change the truck stop brands)

```json
{"chains": [
  {"name": "Pilot", "brands": ["Pilot", "Pilot Flying J"], "wikidata": ["Q64128179"], "note": "free text, ignored"}
]}
```

- `name`: shown in the build log.
- `brands`: names matched against the `brand` tag. `wikidata`: `brand:wikidata` QIDs. A chain needs at least one
  of the two; a match by either counts.
- A typo (unknown key, bad QID, the same QID or brand under two chains) stops the build with a message, before
  anything is downloaded. The build log counts, per chain, the truck stops found by name and by QID, and flags a
  chain with none.

## The file

`PRAGMA application_id` 0x53425231 ("SBR1"), `user_version` 1.

- `meta(key, value)`: `extract_date` (newest Geofabrik extract date), `extract_dates` (JSON, per state), `built_at`,
  `chains_sha256`, `schema` ("1"), `sources` (JSON list of states), `categories` (JSON list, the table's order),
  `attribution`.
- `spot(id, lat, lon, category, name, brand, extra)`; `extra` is a JSON object text, never NULL (`{}` when there is
  nothing to say).
- `cluster(zoom, x, y, category, count, lat, lon)`: Web Mercator tiles for zooms 3 to 11 (the app reads `MAX(zoom)`),
  per category, with the mean position of the spots in a tile.
- Indexes `spot_lat_lon(lat, lon)`, `spot_cat_lat_lon(category, lat, lon)` and `cluster_zoom_x_y(zoom, x, y)`, and
  the R-tree table `road_rtree(id, minlat, maxlat, minlon, maxlon)` (same ids as `spot`; absent only if the build's
  SQLite has no R-tree, which the build warns about and then refuses unless `ROAD_ALLOW_NO_RTREE=1`).

## Measured

First full build (`road.yml`, 2026-10-23, every covered state, about 8 minutes): 33.2 MB, 168,035 spots (R-tree rows
equal) and 74,853 cluster rows for zooms 3 to 11. Spots per category: camp 12,749, rv 2,137, rest 2,292, dump 699,
water 10,109, toilet 31,073, shower 1,923, fuel 46,709, truck 2,167, laundry 4,668, library 9,502, pharmacy 9,300,
hospital 3,155, clinic 10,005, grocery 16,602, park 4,945. That build kept `access=private` and `access=no` objects;
the exclusion above came after it, so the next build's counts are a little lower. `road.yml`'s floors are about half
of these counts.

## Settings

`ROAD_MAX_MB` (default 64; the first full build measured 33 MB) fails the build if the pack is bigger; `FREE_SOURCES=1` deletes each extract once it is
filtered (at most one is on disk). `ROAD_SOURCES="district-of-columbia"` builds from those Geofabrik names only, for
a quick test pack (data.yml's smoke build does this); such a pack must not be shipped (release.yml sets it blank; a
blank value is the same as unset). Reruns reuse the filtered files while the extract and `build.sh` are unchanged.
osmium runs in one container for the whole build.

The osmium filter is wider than the rules above, since it can only name tag values: all `leisure=park`,
`boundary=protected_area` and `amenity=doctors` objects pass it and `build_road.py` then applies the rest. A test
(`FilterTest`) keeps the filter and the classifier in step.

CI: `data.yml` builds the DC smoke pack on any pipeline pull request that does not touch `pipeline/road`;
`road.yml` builds the whole pack when `pipeline/road` changes (or by hand), and asserts the marks, a floor of spots
per category and the cluster zooms.

## Tests

`python3 -m unittest discover -s road/tests` (part of `make -C pipeline test`). `tests/fixture.osm` holds
synthetic points with generic names for every category and rule (a way, a boundary relation, a city park that must be
left out, the protect_class filter, the diesel flag); the rest of the rules are tested on tag dictionaries.
