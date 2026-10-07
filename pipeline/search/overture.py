#!/usr/bin/env python3
"""Overture Places for the search pack: fetch a region, load it as its own table.

Overture Maps Foundation's places theme (https://docs.overturemaps.org/guides/places/)
fills OSM's gaps in shops, offices and services. It is CDLA Permissive 2.0,
except rows whose source is Foursquare (Apache 2.0) or AllThePlaces (CC0).
Overture warns that a database *joined* with OSM may inherit OSM's ODbL, so
these rows go into their own tables, never into `feature`, and the app
dedupes them against OSM only at query time (core/search OverturePlaces):

  overture_place  one row per place: name, category, address, lat/lon,
                  phone, website, Overture's confidence, and
                  name_key/search_text built by normalize.py exactly as for
                  `feature`, so the Kotlin SearchText matches both
  overture_fts    FTS5 trigram index over search_text (detail='none'),
                  rowid = overture_place.rowid

Kept small (measured with overture_sizes.py on the statewide pack, October
2026, 1,124,477 places: 482.6 MB before, 388.8 MB after): the GERS id and
the source dataset are not stored (the app never read them), and the search
text has the name, the town and ZIP, the categories and the brand but not
the street (the address is still stored and shown). So "pizza nyack" still finds an Overture-only place, "pizza
main st" finds it only through an OSM twin. A higher confidence cut-off
saves less and drops real places (0.6: 27 MB fewer, 81k places gone).

Two steps, so the slow download can be kept and reused:

  overture.py fetch --bounds w,s,e,n --out places.csv [--release R]
      DuckDB reads the release's GeoParquet straight from Overture's public
      S3 bucket (httpfs; no account needed). The files are sorted
      spatially and DuckDB skips row groups by their bbox statistics, so
      New York City reads about 90 MB of the release's 11 GB. Writes the
      rows to a CSV (STAGING_COLUMNS) and the release id to <out>.json.
  build_search.py ... --overture places.csv
      calls load() below: maps each CSV row to a table row (or skips it),
      creates the tables and indexes.

The release: OVERTURE_RELEASE (or --release) names one, "latest" takes the
newest in the bucket; unset, DEFAULT_RELEASE if Overture still publishes it,
else the newest (Overture deletes old releases after a few months, and a
monthly build from a phone shouldn't need a code change for that).

Run as a script needs `pip install duckdb`; load() and the row mapping need
only the standard library.
"""

import argparse
import csv
import json
import math
import os
import re
import ssl
import sys
import urllib.request

from normalize import normalize

# The newest release listed in the bucket when this was written (2026-10-03).
DEFAULT_RELEASE = "2026-09-23.1"
BUCKET = "overturemaps-us-west-2"
BUCKET_URL = f"https://{BUCKET}.s3.amazonaws.com/"
PLACES_PATH = "s3://" + BUCKET + "/release/{release}/theme=places/type=place/*"
MIN_CONFIDENCE = 0.5

# What fetch writes and load reads, in order.
STAGING_COLUMNS = [
    "id", "name", "category", "basic_category", "brand", "freeform", "locality", "postcode",
    "region", "country", "website", "phone", "confidence", "dataset", "operating_status", "lon", "lat",
]

# Overture's taxonomy.primary -> the OSM value the app already uses for the
# same thing (Place.category, labelled by Place.categoryLabel), where one
# clearly fits. Everything else keeps Overture's own slug ("pizza_restaurant"
# -> "Pizza restaurant" on the card).
CATEGORY_MAP = {
    "coffee_shop": "cafe",
    "cafe": "cafe",
    "restaurant": "restaurant",
    "fast_food_restaurant": "fast_food",
    "bar": "bar",
    "pub": "pub",
    "ice_cream_shop": "ice_cream",
    "bakery": "bakery",
    "gas_station": "fuel",
    "ev_charging_station": "charging_station",
    "bank_or_credit_union": "bank",
    "bank": "bank",
    "atm": "atm",
    "pharmacy": "pharmacy",
    "pharmacy_and_drug_store": "pharmacy",
    "supermarket": "supermarket",
    "convenience_store": "convenience",
    "hotel": "hotel",
    "motel": "motel",
    "hostel": "hostel",
    "museum": "museum",
    "park": "park",
    "playground": "playground",
    "library": "library",
    "hospital": "hospital",
    "dentist": "dentist",
    "dental_clinic": "dentist",
    "doctors_office": "doctors",
    "post_office": "post_office",
    "police_department": "police",
    "police_station": "police",
    "fire_department": "fire_station",
    "fire_station": "fire_station",
    "movie_theater": "cinema",
    "cinema": "cinema",
    "theatre": "theatre",
    "parking": "parking",
    "parking_lot": "parking",
    "parking_garage": "parking",
    "gym": "fitness_centre",
    "hair_salon": "hairdresser",
    "laundromat": "laundry",
    "car_wash": "car_wash",
    "car_rental": "car_rental",
    "school": "school",
    "elementary_school": "school",
    "middle_school": "school",
    "high_school": "school",
    "college_university": "university",
    "university": "university",
    "stadium_arena": "stadium",
    "train_station": "station",
    "airport": "aerodrome",
    "zoo": "zoo",
    "beach": "beach",
}

# Rows that are gone for good; "temporarily_closed" ones stay findable.
CLOSED = {"permanently_closed", "closed"}

# A Geofabrik extract in regions.json "sources" -> the state its rows are in.
SOURCE_STATES = {"new-york": "NY", "new-jersey": "NJ"}
STATE_NAMES = {"new york": "NY", "new jersey": "NJ"}

SCHEMA = """
CREATE TABLE overture_place (
  name TEXT NOT NULL,
  category TEXT,
  address TEXT,
  lat REAL NOT NULL,
  lon REAL NOT NULL,
  phone TEXT,
  website TEXT,
  confidence REAL NOT NULL,
  name_key TEXT NOT NULL,
  search_text TEXT NOT NULL
);
"""

# Built after the bulk insert, like build_search.INDEXES.
INDEXES = """
CREATE INDEX overture_place_name_key ON overture_place(name_key);
-- columnsize=0: the app never ranks with bm25, so the per-row sizes are dead weight.
CREATE VIRTUAL TABLE overture_fts USING fts5(
  search_text, content='', tokenize='trigram', detail='none', columnsize=0
);
INSERT INTO overture_fts(rowid, search_text) SELECT rowid, search_text FROM overture_place;
INSERT INTO overture_fts(overture_fts) VALUES ('optimize');
"""

ATTRIBUTION = (
    "Overture Maps Foundation places (overturemaps.org), CDLA Permissive 2.0;"
    " rows from Foursquare under Apache 2.0, from AllThePlaces under CC0"
)


# --- fetch -------------------------------------------------------------------


def fetch_sql(source, bounds, min_confidence=MIN_CONFIDENCE):
    """The DuckDB query for the places inside bounds (minlon, minlat, maxlon,
    maxlat) at source, a read_parquet path or glob. Filtering on the bbox
    struct's columns lets DuckDB skip row groups by their statistics."""
    west, south, east, north = (float(v) for v in bounds)
    return f"""
SELECT
  id,
  names.primary AS name,
  taxonomy.primary AS category,
  basic_category,
  brand.names.primary AS brand,
  addresses[1].freeform AS freeform,
  addresses[1].locality AS locality,
  addresses[1].postcode AS postcode,
  addresses[1].region AS region,
  addresses[1].country AS country,
  websites[1] AS website,
  phones[1] AS phone,
  confidence,
  sources[1].dataset AS dataset,
  operating_status,
  (bbox.xmin + bbox.xmax) / 2 AS lon,
  (bbox.ymin + bbox.ymax) / 2 AS lat
FROM read_parquet('{source}', hive_partitioning = true)
WHERE bbox.xmin >= {west!r} AND bbox.xmax <= {east!r}
  AND bbox.ymin >= {south!r} AND bbox.ymax <= {north!r}
  AND confidence >= {float(min_confidence)!r}
  AND names.primary IS NOT NULL
"""


def _ssl_context():
    # The bucket is public HTTPS; honour a custom CA bundle when one is set.
    cafile = os.environ.get("SSL_CERT_FILE") or os.environ.get("REQUESTS_CA_BUNDLE")
    return ssl.create_default_context(cafile=cafile) if cafile else ssl.create_default_context()


def list_releases():
    """Release ids in Overture's bucket, oldest first ("2026-09-23.1", ...)."""
    url = BUCKET_URL + "?list-type=2&prefix=release/&delimiter=/"
    with urllib.request.urlopen(url, context=_ssl_context(), timeout=60) as response:
        body = response.read().decode()
    return sorted(re.findall(r"<Prefix>release/([^</]+)/</Prefix>", body))


def resolve_release(requested, releases):
    """The release to fetch: requested if given ("latest" = newest), else
    DEFAULT_RELEASE while it's still published, else the newest. releases is
    the bucket's list, or None if it couldn't be read (then trust the name)."""
    if requested == "latest":
        if not releases:
            raise SystemExit("OVERTURE_RELEASE=latest, but the release list couldn't be read")
        return releases[-1]
    if requested:
        if releases is not None and requested not in releases:
            raise SystemExit(f"Overture release {requested} is not published; available: {', '.join(releases)}")
        return requested
    if releases is None or DEFAULT_RELEASE in releases:
        return DEFAULT_RELEASE
    if not releases:
        raise SystemExit("No Overture releases listed in the bucket")
    print(f"==> Overture release {DEFAULT_RELEASE} is gone; using the newest, {releases[-1]}", file=sys.stderr)
    return releases[-1]


def fetch(bounds, out, release=None, source=None, min_confidence=MIN_CONFIDENCE):
    """Writes the places inside bounds to out as CSV and {release, bounds} to
    out.json; reuses out when out.json matches. source (a local parquet,
    for tests) replaces the S3 path and skips httpfs. Returns the row count."""
    import duckdb  # only the fetch step needs it

    if source is None:
        try:
            releases = list_releases()
        except OSError as e:
            print(f"==> Couldn't list Overture releases ({e}); trying the release as named", file=sys.stderr)
            releases = None
        release = resolve_release(release, releases)
    stamp = {"release": release or "local", "bounds": list(bounds), "min_confidence": min_confidence,
             "source": source or PLACES_PATH.format(release=release)}
    if os.path.exists(out) and os.path.exists(out + ".json"):
        with open(out + ".json") as f:
            if json.load(f) == stamp:
                print(f"==> Reusing {os.path.basename(out)} (Overture {stamp['release']})", file=sys.stderr)
                return None
    db = duckdb.connect()
    if source is None:
        # httpfs comes from extensions.duckdb.org on first use. DuckDB asks for the build that matches its own
        # version, which the hash-locked wheel fixes (.github/pipeline-requirements.txt), and checks the
        # extension's signature before loading it; the file itself isn't hashed here.
        db.execute("INSTALL httpfs; LOAD httpfs; SET s3_region = 'us-west-2';")
    print(f"==> Fetching Overture places {stamp['release']} inside {','.join(map(str, bounds))}", file=sys.stderr)
    query = fetch_sql(stamp["source"], bounds, min_confidence)
    tmp = out + ".part"
    db.execute(f"COPY ({query}) TO '{tmp}' (FORMAT csv, HEADER true)")
    rows = db.execute(f"SELECT count(*) FROM read_csv('{tmp}', header = true, all_varchar = true)").fetchone()[0]
    db.close()
    os.replace(tmp, out)
    with open(out + ".json", "w") as f:
        json.dump(stamp, f)
    print(f"    {rows} places ({os.path.getsize(out) / 1e6:.1f} MB CSV)", file=sys.stderr)
    return rows


# --- load --------------------------------------------------------------------


def region_scopes(region, regions_json=None):
    """[(state, bounds or None)] a row must fit one of to be kept, from the
    region's "sources" in regions.json (as clip_extract reads them): nyc ->
    [("NY", nyc's bounds)]; nys -> [("NY", None), ("NJ", njn's bounds)].
    None (keep every row) for a region regions.json doesn't know."""
    path = regions_json or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "regions.json")
    if not os.path.exists(path):
        path = "/regions.json"  # the build container mounts it there
    with open(path) as f:
        regions = json.load(f)["regions"]
    entry = regions.get(region)
    if entry is None:
        return None
    scopes = []
    for source, cut in (entry.get("sources") or {"new-york": "self"}).items():
        state = SOURCE_STATES.get(source)
        if state is None:
            continue
        if cut is None:
            bounds = None
        elif cut == "self":
            bounds = tuple(entry["bounds"])
        else:
            bounds = tuple(regions[cut]["bounds"])
        scopes.append((state, bounds))
    return scopes


def state_code(value):
    """'NY' from 'NY', 'ny' or 'New York'; None otherwise."""
    value = (value or "").strip()
    if len(value) == 2 and value.isalpha():
        return value.upper()
    return STATE_NAMES.get(value.lower())


def in_scope(scopes, state, lat, lon):
    """Whether a row in state at lat/lon belongs to the region. A row whose
    address names no state (or another one) is dropped: Overture's address
    is usually right, and a business's head-office address in another state
    marks a row better left out."""
    if scopes is None:
        return True
    for scope_state, bounds in scopes:
        if state != scope_state:
            continue
        if bounds is None:
            return True
        west, south, east, north = bounds
        if west <= lon <= east and south <= lat <= north:
            return True
    return False


def map_category(category, basic_category):
    """The app's category for a row: the OSM value from CATEGORY_MAP, else
    Overture's taxonomy slug, else its basic category; None if neither."""
    for value in (category, basic_category):
        value = (value or "").strip()
        if value:
            return CATEGORY_MAP.get(value, value)
    return None


def format_address(freeform, locality, postcode):
    """'616 Cliff Rd, Sewaren 07077', shaped like build_search.format_address."""
    postcode = (postcode or "").strip()
    if re.fullmatch(r"\d{5}-\d{4}", postcode):
        postcode = postcode[:5]
    town = " ".join(p for p in ((locality or "").strip(), postcode) if p)
    parts = [p for p in ((freeform or "").strip(), town) if p]
    return ", ".join(parts) or None


def clean_website(url):
    """A link the place card can open: 'www.x.com' -> 'https://www.x.com'."""
    url = (url or "").strip()
    if not url:
        return None
    if not re.match(r"^[a-z][a-z0-9+.-]*://", url, re.IGNORECASE):
        url = "https://" + url
    return url


def search_text(*parts):
    """The same shape as build_search.search_text: ' words of every part '."""
    return " " + normalize(" ".join(p for p in parts if p)) + " "


def place_row(rec, scopes=None, min_confidence=MIN_CONFIDENCE):
    """One staging record (a dict of STAGING_COLUMNS, as strings) -> an
    overture_place row tuple, or None to skip it: no name, confidence below
    min_confidence, closed for good, no usable position, or outside the
    region (in_scope)."""
    name = (rec.get("name") or "").strip()
    if not name:
        return None
    try:
        confidence = float(rec.get("confidence") or 0)
        lat = float(rec["lat"])
        lon = float(rec["lon"])
    except (KeyError, TypeError, ValueError):
        return None
    if confidence < min_confidence or not (math.isfinite(lat) and math.isfinite(lon)):
        return None
    if (rec.get("operating_status") or "").strip().lower() in CLOSED:
        return None
    if not in_scope(scopes, state_code(rec.get("region")), lat, lon):
        return None
    raw_category = (rec.get("category") or "").strip() or None
    basic = (rec.get("basic_category") or "").strip() or None
    category = map_category(raw_category, basic)
    address = format_address(rec.get("freeform"), rec.get("locality"), rec.get("postcode"))
    # The town and ZIP are searchable ("pizza nyack"); the street isn't (see the module notes).
    town = format_address(None, rec.get("locality"), rec.get("postcode"))
    brand = (rec.get("brand") or "").strip()
    name_key = normalize(name)
    words = [
        name,
        town,
        # Overture's own slugs as words too, so "pizza" finds a pizza_restaurant
        # filed under "restaurant", and "deli" a delicatessen.
        *dict.fromkeys(c.replace("_", " ") for c in (category, raw_category, basic) if c),
        brand if brand and normalize(brand) != name_key else None,
    ]
    return (
        name,
        category,
        address,
        round(lat, 6),
        round(lon, 6),
        (rec.get("phone") or "").strip() or None,
        clean_website(rec.get("website")),
        round(confidence, 3),
        name_key,
        search_text(*words),
    )


def read_staging(path):
    """The staging CSV's records as dicts."""
    with open(path, newline="", encoding="utf-8") as f:
        yield from csv.DictReader(f)


def load(db, path, scopes=None, min_confidence=MIN_CONFIDENCE):
    """Creates overture_place from the staging CSV at path (without its
    indexes; see finish). Returns (kept, skipped)."""
    db.executescript(SCHEMA)
    kept = skipped = 0
    batch = []
    for rec in read_staging(path):
        row = place_row(rec, scopes, min_confidence)
        if row is None:
            skipped += 1
            continue
        batch.append(row)
        kept += 1
        if len(batch) >= 50_000:
            _insert(db, batch)
    _insert(db, batch)
    return kept, skipped


def _insert(db, batch):
    db.executemany(
        "INSERT INTO overture_place (name, category, address, lat, lon, phone, website, confidence,"
        " name_key, search_text) VALUES (?,?,?,?,?,?,?,?,?,?)",
        batch,
    )
    batch.clear()


def finish(db):
    """Indexes overture_place; run after the bulk insert."""
    db.executescript(INDEXES)


def release_of(path):
    """The release a staging CSV came from (its .json stamp), or ''."""
    try:
        with open(path + ".json") as f:
            return json.load(f).get("release", "")
    except (OSError, ValueError):
        return ""


def parse_bounds(text):
    parts = [float(p) for p in text.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("bounds are minlon,minlat,maxlon,maxlat")
    return tuple(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fetch", help="download a region's places to a staging CSV")
    f.add_argument("--bounds", type=parse_bounds, required=True, help="minlon,minlat,maxlon,maxlat")
    f.add_argument("--out", required=True)
    f.add_argument("--release", default=os.environ.get("OVERTURE_RELEASE") or None,
                   help=f"release id or 'latest' (default: $OVERTURE_RELEASE, else {DEFAULT_RELEASE})")
    f.add_argument("--source", help="a local GeoParquet to read instead of Overture's bucket")
    f.add_argument("--min-confidence", type=float, default=MIN_CONFIDENCE)
    sub.add_parser("releases", help="list the releases in Overture's bucket")
    args = parser.parse_args()
    if args.command == "releases":
        print("\n".join(list_releases()))
    else:
        fetch(args.bounds, args.out, release=args.release, source=args.source, min_confidence=args.min_confidence)


if __name__ == "__main__":
    main()
