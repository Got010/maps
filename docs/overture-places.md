# Overture Places in search

OSM covers New York's landmarks and most shops well, but many small businesses,
offices and services are missing from it. [Overture Maps Foundation](https://overturemaps.org)'s
places theme fills that gap: about 5 times as many named places as OSM in the
NYC box. The search pack now carries them in their own tables, and the app
merges them with OSM's places when it ranks results.

## What is fetched

`pipeline/search/overture.py fetch` reads one Overture release's places
(`s3://overturemaps-us-west-2/release/<release>/theme=places/type=place/*`,
public, no account needed) with DuckDB and its `httpfs` extension. It keeps the
rows whose `bbox` lies inside the region's bounds, with `confidence >= 0.5`
and a `names.primary`. The places files are sorted by location, so DuckDB
skips row groups by their bbox statistics and reads only a small slice of the
release's 11 GB:

| Region | Row groups read | Data read* | Rows fetched | Rows kept | Overture tables |
| --- | --- | --- | --- | --- | --- |
| `nyc` | 37 of 4,864 | 88 MB | 504,843 | 376,567 | 163 MB |
| `nys` (NY + northern NJ) | 163 of 4,864 | 323 MB | 1,966,572 | 1,124,477 | 483 MB |

\* Measured with release `2026-09-23.1` on 2026-10-03, by reading every
column except geometry. DuckDB reads only the columns in the query, so its
reads should be smaller.

The released `search-nys.sqlite` is 366 MB, so expect about 850 MB with
Overture. `SEARCH_MAX_MB` (1,900) still has headroom, and `OVERTURE_MAX_MB`
(700) fails the build if the Overture tables grow by about half again.

Columns read: `id`, `names.primary`, `taxonomy.primary`, `basic_category`,
`brand.names.primary`, `addresses[1].{freeform, locality, postcode, region, country}`,
`websites[1]`, `phones[1]`, `confidence`, `sources[1].dataset`,
`operating_status`, `bbox`. **Note:** this release has no `categories` column.
Overture replaced it with `taxonomy` (`primary`, `hierarchy`, `alternates`) and
`basic_category`. The query was tested here against real rows of
`2026-09-23.1`, copied locally.

`load()`, called by `build_search.py --overture`, then drops rows that:

- are `permanently_closed`;
- have an address in a state the region doesn't build from. The region's
  `sources` in `regions.json` decide: `nyc` keeps NY rows inside its box, and
  `nys` keeps NY rows anywhere plus NJ rows inside the `njn` box. This drops
  Connecticut, Pennsylvania, Vermont, Massachusetts, Ontario and Quebec, and
  businesses filed under a head office in another state;
- have no state at all (about 0.2%).

In the NYC box the datasets are meta 39%, BrightQuery 38%, Microsoft 12%,
Foursquare 10%, AllThePlaces 1% and DAC under 1%.

## Pack layout

Two new tables in `search-<region>.sqlite`, never mixed into `feature`:

```sql
CREATE TABLE overture_place (
  id TEXT NOT NULL,          -- GERS id; not indexed (~50 B/row saved)
  name TEXT NOT NULL, category TEXT, address TEXT,
  lat REAL NOT NULL, lon REAL NOT NULL, phone TEXT, website TEXT,
  confidence REAL NOT NULL, dataset TEXT,   -- meta, Foursquare, AllThePlaces, ...
  name_key TEXT NOT NULL, search_text TEXT NOT NULL
);
CREATE INDEX overture_place_name_key ON overture_place(name_key);
CREATE VIRTUAL TABLE overture_fts USING fts5(search_text, content='',
  tokenize='trigram', detail='none', columnsize=0);  -- rowid = overture_place.rowid
```

The id is plain `TEXT`, not the `TEXT PRIMARY KEY` first planned: a primary
key would add a unique index that nothing reads. `name_key` and `search_text`
come from `normalize.py`, the same as for `feature`, so the Kotlin
`SearchText` matches them. `search_text` holds the name, the address, the
category, Overture's taxonomy slug and basic category as words, and the brand
when it differs from the name. "pizza" therefore finds a `pizza_restaurant`
and "deli" a `delicatessen`.

Categories: a small `CATEGORY_MAP` turns Overture slugs into the OSM values the
app already uses (`coffee_shop` becomes `cafe`, `gas_station` becomes `fuel`,
`bank_or_credit_union` becomes `bank`, `parking` becomes `parking`, and so
on). Every other slug is kept as is, and `Place.categoryLabel` turns
`pizza_restaurant` into "Pizza restaurant".

Websites without a scheme get `https://`, and ZIP+4 codes are cut to five
digits. `meta` records `overture_release` and adds Overture to `attribution`.

**Overture does not change `SCHEMA_VERSION`** (2 then; 3 since the landmark `direction`
column). The tables are purely additive, and
`SearchIndex` detects them by name (`hasOverture`), the same way it handles the
address and landmark tables. Older packs, and packs built with `OVERTURE=0`,
search OSM alone.

## In the app (core/search)

- `SearchIndex.candidates()`: for a plain search (not the address or street
  sub-lookups), it reads the matching `overture_fts` rows overall and within
  15 km, up to 150 of each. Rows with the query's soft words come first, then
  the highest confidence. They become `Candidate`s with
  `Place.source = PlaceSource.OVERTURE`, `id = 1 shl 42 + rowid` (above the
  address ids) and `osmRef = null`.
- `OverturePlaces.merge()` dedupes at query time. An Overture row is dropped
  when an OSM place or locality has the same `name_key` within 60 m, or when
  one name starts the other ("Starbucks" and "Starbucks Coffee") within 30 m.
  The OSM place keeps its own data but takes a missing phone or website from
  the Overture row. In the NYC box about 35k of the 473k Overture rows have a
  same-name OSM place within 60 m.
- Ranking: `importance = 30 - 5 - round((1 - confidence) * 10)`, plus 10 for
  attraction, museum, park, stadium, university or hospital. That is OSM's
  place base less 5, so OSM wins ties, and low-confidence rows sink. A nearby
  Overture shop still beats a far-away OSM one.
- `SearchIndex.place(id)` also reopens Overture ids.
- Overture rows are **search only**. `landmarks()` (guidance landmarks and
  camera alerts) and `nearby()` ("Parking nearby") read OSM's tables alone.
  This keeps the licences apart and keeps spoken landmarks on OSM.
- The place card shows no per-place source line. The credit is on the About
  screen.

I checked this against the released `search-nys.sqlite` with the NYC Overture
rows attached, running `SearchIndex` on a desktop JVM through a sqlite-jdbc
stand-in for androidx.sqlite (a scratch setup that is not in the repo). The
OSM "Joe's Pizza" on Carmine St hid its Overture twin. "dentist" and "katz"
returned Overture offices next to OSM ones. "sewaren deli" (NJ) now finds a
place OSM lacks. Queries took 3 to 130 ms on the desktop.

## Licences and attribution

- Overture places: **CDLA Permissive 2.0**. Rows whose source is Foursquare
  are **Apache 2.0**, and rows from AllThePlaces are **CC0**. The `dataset`
  column records which applies to each row.
- Overture notes that a database *joined* with OSM may inherit ODbL. Because
  of that, the rows live in their own tables and are merged with OSM only in
  memory, per query. Nothing written to the pack combines them.
- About screen (`feature/settings` `settings_about_body`) now reads: "Extra
  places in search: © Overture Maps Foundation (overturemaps.org), CDLA
  Permissive 2.0, kept apart from the OpenStreetMap data. They include data
  from Foursquare Labs, Inc. under the Apache License 2.0 (Copyright 2024
  Foursquare Labs, Inc. All rights reserved.) and from AllThePlaces under
  CC0." The pack's `meta.attribution` gets the same credit.

## Turning it on and off

| Where | Setting |
| --- | --- |
| `pipeline/search/build.sh` | `OVERTURE=1` turns it on (default off). Other settings: `OVERTURE_RELEASE` (a release id or `latest`), `OVERTURE_MIN_CONFIDENCE` (default 0.5; higher makes a smaller pack), `OVERTURE_MAX_MB` (default 700). |
| `release.yml` (data job) | On by default. To change it from the phone, set repository variables (Settings > Secrets and variables > Actions > Variables): `OVERTURE=0` turns it off, and `OVERTURE_RELEASE` and `OVERTURE_MIN_CONFIDENCE` work as above. |
| `data.yml` (NYC PR check) | On. It is cheap, about a 90 MB read and a minute or two, and it is the only place the real S3 and httpfs path runs before a release. |
| `ci.yml` | Installs `duckdb==1.5.6`, so `make -C pipeline test` also runs the fetch query against a GeoParquet written by the test. Without DuckDB that test is skipped. |

Release choice: when `OVERTURE_RELEASE` is unset, the build uses
`overture.DEFAULT_RELEASE` (`2026-09-23.1`) while it is still in the bucket,
and the newest release once Overture deletes it, logging the switch. A monthly
build from the phone therefore needs no code change. `meta.overture_release`
records which release was used. `overture.py releases` lists what is
published.

The staging CSV stays in `pipeline/data/tmp/overture-<region>.csv`, with a
`.json` stamp of the release, bounds and threshold. It is reused while those
stay the same.

## Tests

- `pipeline/search/tests/test_overture.py` covers row mapping and skips,
  region scopes, category mapping, release choice, the fetch SQL, and the
  tables built through `build_search.build`: FTS lookups, meta and the size
  guard. When DuckDB is installed it also runs the real query against a
  GeoParquet laid out like the release's. Fixture:
  `tests/overture_places.csv`. `make -C pipeline test` needs no network.
- `core/search/src/test/.../OverturePlacesTest.kt` covers importance, dedupe
  distances and the prefix rule, streets not counting as twins, phone and
  website hand-over, and that OSM wins ties.

## Unverified

- **DuckDB `httpfs` in `python:3.12-slim` on GitHub Actions.** The extension
  downloads at runtime from extensions.duckdb.org, which this sandbox blocks,
  so the `INSTALL httpfs; LOAD httpfs; SET s3_region='us-west-2'` path and the
  anonymous S3 glob have not been run end to end. The `spatial` extension is
  not needed: the query uses only plain `bbox` struct fields. The bucket
  itself is reachable, and I listed and read the release over HTTPS here. The
  first `data.yml` run on this PR is the real test.
- **S3 path and schema** were verified for `2026-09-23.1` only. Overture
  changed the schema recently (`categories` was replaced by `taxonomy` and
  `basic_category`), and a future release may rename columns again; the fetch
  would then fail loudly.
- **The exact Foursquare notice.** The wording above is from memory of
  Foursquare's Apache 2.0 NOTICE for its open-source places and Overture's
  attribution guidance. Before release, check it against
  https://docs.overturemaps.org/attribution/ and the Foursquare OS Places
  NOTICE file.
- The sizes for `nys` are from a local load of the same rows (this sandbox's
  SQLite). The real build adds the OSM tables around them.
