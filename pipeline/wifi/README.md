# Free Wi-Fi pack (`wifi-us.sqlite`)

Built by `make -C pipeline wifi-us` (`wifi/build.sh us`): Geofabrik's extract of every state named in
`regions.json`, filtered with osmium, classified by `build_wifi.py`. Heavy work runs in GitHub Actions
(`release.yml` builds and ships it with the other packs; `wifi.yml` is the pull request check); nothing here runs on
the phone.

## Tiers (the app's confidence labels)

| Tier | Label | Rule |
| --- | --- | --- |
| 1 | Tagged free | `internet_access` is `wlan`, `yes` or `wifi` and `internet_access:fee=no`, or there is no `internet_access:fee` and `fee=no`. `internet_access:fee=customers` is tier 1 with fee `customers` ("Free for customers") |
| 2 | Tagged Wi-Fi (fee not stated) | `internet_access` as above, no `internet_access:fee`, no `fee=no` |
| 3 | Chain policy | `brand:wikidata` is a QID in `chains.json` |
| 4 | Likely | `amenity=library`; `tourism=hotel` or `motel`; `highway=rest_area`; `tourism=information` with `information=office`; `information=visitor_centre` run by the National Park Service (`operator:wikidata=Q308439`) or with "National Park" in `operator` |

A spot takes the lowest tier it matches. `internet_access:fee=yes`, `internet_access=no`, `internet_access=terminal`,
`access=private`, `access=no` and `disused:`/`abandoned:`/`was:`/`demolished:`/`removed:`/`razed:`/`destroyed:`
tagging without a live feature (only a prefixed feature key counts: `was:operator` on a live station does not), `shop=vacant` and `disused=yes` exclude an object from every tier.

Chain sub-objects: a Walmart's pharmacy and garden centre, a McDonald's PlayPlace or a charging station carry the
chain's `brand:wikidata` too. Tier 3 spots of one chain within 60 m of each other are one store, and one row is
kept: the one whose category is in the chain's `categories`, else the one named like the chain (case and spaces
ignored), else the lowest OSM id. Tiers 1, 2 and 4 are never folded (a pharmacy tagged with Wi-Fi of its own stays).
The build log counts the rows folded per chain.

Names: `name`, else `brand`. A chain spot with neither is called by the chain's `name` in `chains.json`; any other
spot with neither keeps a NULL name (the app shows a fallback from the category). The build log counts them per
tier.

Category: the `amenity`, `shop`, `tourism`, `leisure`, `craft`, `healthcare` or `historic` value (as the search
pack's `place_category` picks it, never `yes`), else `station` (railway or public transport station), `halt` or
`aerodrome`, else the `office` or `highway` value, else `place`. Tier 4 uses `library`, `hotel`, `motel`,
`rest_area`, `welcome_center` or `visitor_centre`.

## `chains.json` (edit this to change tier 3)

```json
{"chains": [
  {"name": "Starbucks", "wikidata": ["Q37158"], "fee": "customers"},
  {"name": "Pilot", "wikidata": ["Q64128179"], "fee": "customers", "note": "free text, ignored"}
]}
```

- `name`: shown in the build log only.
- `wikidata`: one or more `brand:wikidata` QIDs. Chains are matched by QID, never by name. To find a chain's QID,
  look at the `brand:wikidata` of one of its shops on openstreetmap.org (or in the Name Suggestion Index,
  `data/brands/` in github.com/osmlab/name-suggestion-index).
- `fee`: `customers` (the default), `no` or `unknown`: the fee column of the chain's spots.
- `categories`: optional list of the chain's main OSM categories, the `amenity`/`shop` value of its stores
  (`["supermarket", "department_store"]` for Walmart, `["fuel"]` for a truck stop). Used only to pick which row
  stands for a store (below); every chain in the file has them.
- `note`: optional.

A typo (unknown key, bad QID, the same QID under two chains, a bad `fee`) stops the build with a message. After a
change, the test build prints each QID with how many objects carry it and the three commonest names: check that the
names are the chain's.

## The file

`PRAGMA application_id` 0x53425731 ("SBW1"), `user_version` 1.

- `meta(key, value)`: `extract_date` (newest Geofabrik extract date), `extract_dates` (JSON, per state), `built_at`,
  `chains_sha256` (also as `chains_version`), `schema` ("1"), `sources` (JSON list of states), `attribution`.
- `spot(id, osm_type, osm_id, name, brand_wikidata, lat, lon, category, tier, fee, opening_hours, website)`;
  `fee` is `no`, `customers` or `unknown`.
- `cluster(zoom, x, y, count, lat, lon, tier_min)`: Web Mercator tiles for zooms 3 to 11 (the app reads `MAX(zoom)`, so packs of either range work), mean position of the
  spots in a tile.
- Indexes `spot_lat_lon(lat, lon)`, `spot_tier_lat_lon(tier, lat, lon)` and `cluster_zoom_x_y(zoom, x, y)`, and the R-tree table
  `wifi_rtree(id, minlat, maxlat, minlon, maxlon)` (same ids; absent only if the build's SQLite has no R-tree,
  which the build warns about).

## Settings

`WIFI_MAX_MB` (default 60) fails the build if the pack is bigger; `FREE_SOURCES=1` deletes each extract once it
is filtered (at most one is on disk). `WIFI_SOURCES="district-of-columbia"` builds from those Geofabrik names only,
for a quick test pack (data.yml's smoke build does this); such a pack must not be shipped (release.yml sets it blank; a blank value is the same as unset). The build fails when
the finished pack has no `wifi_rtree` table, unless `WIFI_ALLOW_NO_RTREE=1`. Reruns reuse the filtered files
while the extract, `chains.json` and `build.sh` are unchanged. osmium runs in one container for the whole build.

CI: `data.yml` builds the DC smoke pack on any pipeline pull request; `wifi.yml` builds the whole pack when
`pipeline/wifi` changes (or by hand).

## Tests

`python3 -m unittest discover -s wifi/tests` (part of `make -C pipeline test`). `tests/fixture.osm` holds
synthetic points with generic names for every rule; the rest of the rules are tested on tag dictionaries.
