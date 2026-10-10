#!/usr/bin/env python3
"""Cuts a search pack built for several regions into one per region, so a
phone downloads search only for the regions it has the map of.

    split_search.py <search-nys.sqlite> <out_dir> [--source=nys] [--regions=regions.json] [--max-mb=1900]

search/build.sh runs it after building search-nys.sqlite (New York and northern
New Jersey in one index; older apps still download that one). Every basemap
region whose "search_from" in regions.json names the source gets
search-<region>.sqlite: the same header (application id, user_version),
tables, indexes and full-text indexes, holding the rows whose point the app
would give that region (Regions.basemapsAt in core/packs: a region whose area
holds the point owns it, else every region whose box holds it). Regions
overlap, so a row near a boundary is in each pack whose region holds it, and
the app shows it once (core/search SearchPacks). Rows no such region holds
(the New York extract reaches a little into Connecticut, Pennsylvania and
Canada, which have packs of their own or none) are left out and counted. Row
ids stay as they are in the source.

How each table's rows are placed:

  feature, landmark, overture_place   by lat, lon
  address                             by lat_e6, lon_e6 (millionths of a degree)
  address_street                      kept when one of its addresses is
  meta                                copied, with "region" the pack's region and "cut_from" the source

A table it doesn't know stops the split, so a table added to build_search.py
needs a rule here.
"""

import argparse
import json
import os
import re
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REGIONS_JSON = os.path.join(HERE, "..", "regions.json")

# Table -> its latitude and longitude columns, and the scale that makes them degrees.
PLACED = {
    "feature": ("lat", "lon", 1.0),
    "landmark": ("lat", "lon", 1.0),
    "overture_place": ("lat", "lon", 1.0),
    "address": ("lat_e6", "lon_e6", 1e-6),
}
# Table -> the table its rows follow, and the columns that link them (its own, the other's).
FOLLOWING = {"address_street": ("address", "id", "street_id")}
COPIED = {"meta"}
# Full-text index -> the table it indexes and the column its rowid is.
INDEXED = {
    "feature_fts": ("feature", "id"),
    "address_street_fts": ("address_street", "id"),
    "overture_fts": ("overture_place", "rowid"),
}
# FTS5 keeps each index in tables named after it; they are rebuilt with it.
FTS_SHADOWS = ("_data", "_idx", "_config", "_docsize", "_content")


def in_box(bounds, lon, lat):
    return bounds[0] <= lon <= bounds[2] and bounds[1] <= lat <= bounds[3]


def in_area(area, lon, lat):
    """Ray casting; the same as Region.insideArea in core/packs Regions.kt."""
    inside = False
    previous = area[-1]
    for point in area:
        if (point[1] > lat) != (previous[1] > lat) and (
            lon < (previous[0] - point[0]) * (lat - point[1]) / (previous[1] - point[1]) + point[0]
        ):
            inside = not inside
        previous = point
    return inside


def load_regions(path=REGIONS_JSON):
    """The basemap regions, in file order: id -> region."""
    with open(path) as f:
        regions = json.load(f)["regions"]
    return {name: r for name, r in regions.items() if r["basemap"]}


def targets(regions, source):
    """The regions whose search pack is cut from `source`, in file order."""
    return [name for name, r in regions.items() if r.get("search_from") == source]


def owners_at(regions, lon, lat):
    """The regions a point belongs to, like Regions.basemapsAt in Regions.kt."""
    found = [
        name for name, r in regions.items()
        if in_box(r["bounds"], lon, lat) and ("area" not in r or in_area(r["area"], lon, lat))
    ]
    return [n for n in found if "area" in regions[n]] or found


def masker(regions, wanted):
    """A function (lat, lon) -> bit i set for each wanted[i] that owns the point."""
    bits = {name: 1 << i for i, name in enumerate(wanted)}

    def mask(lat, lon):
        m = 0
        for name in owners_at(regions, lon, lat):
            m |= bits.get(name, 0)
        return m

    return mask


def quote(name):
    return '"' + name.replace('"', '""') + '"'


def schema(db):
    """The source's tables, full-text indexes and indexes, checked against the rules above."""
    tables, fts, indexes = [], [], []
    rows = db.execute("SELECT type, name, tbl_name, sql FROM src.sqlite_master ORDER BY rowid").fetchall()
    virtual = {name for kind, name, _, sql in rows if kind == "table" and (sql or "").upper().startswith("CREATE VIRTUAL")}
    for kind, name, table, sql in rows:
        if name.startswith("sqlite_") or sql is None:
            continue
        if kind == "table" and name in virtual:
            if name not in INDEXED:
                raise SystemExit(f"split_search.py doesn't know the full-text index {name}")
            fts.append((name, sql))
        elif kind == "table":
            if any(name == v + s for v in virtual for s in FTS_SHADOWS):
                continue
            if name not in PLACED and name not in FOLLOWING and name not in COPIED:
                raise SystemExit(f"split_search.py doesn't know the table {name}: give it a rule")
            tables.append((name, sql))
        elif kind == "index" and table not in virtual:
            indexes.append(sql)
    return tables, fts, indexes


def keys_of(db, table):
    """The columns that pick one row: rowid, or a WITHOUT ROWID table's primary key."""
    info = db.execute(f"PRAGMA src.table_info({quote(table)})").fetchall()
    sql = db.execute("SELECT sql FROM src.sqlite_master WHERE name = ?", (table,)).fetchone()[0]
    if "WITHOUT ROWID" in sql.upper():
        return [c[1] for c in sorted((c for c in info if c[5]), key=lambda c: c[5])]
    return ["rowid"]


def columns_of(db, schema_name, table):
    return [c[1] for c in db.execute(f"PRAGMA {schema_name}.table_info({quote(table)})")]


def mark(db, table, keys, lat, lon, scale, mask):
    """Writes keep_<table>: each row's keys and the bits of the regions it goes to. Returns (rows kept, left out)."""
    keep = quote("keep_" + table)
    if keys == ["rowid"]:
        db.execute(f"CREATE TABLE {keep} (k INTEGER PRIMARY KEY, mask INTEGER NOT NULL)")
        select = f"SELECT rowid, {quote(lat)}, {quote(lon)} FROM src.{quote(table)}"
    else:
        cols = ", ".join(quote(k) for k in keys)
        db.execute(f"CREATE TABLE {keep} ({cols}, mask INTEGER NOT NULL, PRIMARY KEY ({cols})) WITHOUT ROWID")
        select = f"SELECT {cols}, {quote(lat)}, {quote(lon)} FROM src.{quote(table)}"
    counts = [0, 0]

    def rows():
        for row in db.execute(select):
            m = mask(row[-2] * scale, row[-1] * scale)
            if m:
                counts[0] += 1
                yield (*row[:-2], m)
            else:
                counts[1] += 1

    db.executemany(f"INSERT INTO {keep} VALUES ({', '.join('?' * (len(keys) + 1))})", rows())
    db.commit()
    return tuple(counts)


def write(db, region, bit, source, tables, fts, indexes, keys):
    """Fills the attached pack dst with one region's rows from src, as the marks say; returns rows per table."""
    for name in ("application_id", "user_version"):
        value = db.execute(f"PRAGMA src.{name}").fetchone()[0]
        db.execute(f"PRAGMA dst.{name} = {int(value)}")
    for _, sql in tables:
        db.execute(sql.replace("CREATE TABLE ", "CREATE TABLE dst.", 1))
    counts = {}
    # A table that follows another is filled once that one is.
    for name, _ in sorted(tables, key=lambda t: t[0] in FOLLOWING):
        q = quote(name)
        cols = columns_of(db, "src", name)
        listed = ", ".join(quote(c) for c in cols)
        selected = ", ".join(f"s.{quote(c)}" for c in cols)
        if name in PLACED and keys[name] == ["rowid"]:
            db.execute(
                f"INSERT INTO dst.{q} (rowid, {listed}) SELECT s.rowid, {selected}"
                f" FROM {quote('keep_' + name)} k JOIN src.{q} s ON s.rowid = k.k WHERE k.mask & ? ORDER BY k.k",
                (bit,),
            )
        elif name in PLACED:
            on = " AND ".join(f"s.{quote(c)} = k.{quote(c)}" for c in keys[name])
            db.execute(
                f"INSERT INTO dst.{q} ({listed}) SELECT {selected}"
                f" FROM {quote('keep_' + name)} k JOIN src.{q} s ON {on} WHERE k.mask & ?",
                (bit,),
            )
        elif name in FOLLOWING:
            other, own, link = FOLLOWING[name]
            db.execute(
                f"INSERT INTO dst.{q} (rowid, {listed}) SELECT s.rowid, {selected} FROM src.{q} s"
                f" WHERE s.{quote(own)} IN (SELECT {quote(link)} FROM dst.{quote(other)}) ORDER BY s.rowid"
            )
        else:
            db.execute(f"INSERT INTO dst.{q} ({listed}) SELECT {listed} FROM src.{q}")
        counts[name] = db.execute(f"SELECT count(*) FROM dst.{q}").fetchone()[0]
    db.execute("INSERT OR REPLACE INTO dst.meta (key, value) VALUES ('region', ?)", (region,))
    db.execute("INSERT OR REPLACE INTO dst.meta (key, value) VALUES ('cut_from', ?)", (source,))
    db.commit()
    for sql in indexes:
        db.execute(re.sub(r"^CREATE (UNIQUE )?INDEX ", lambda m: m.group(0) + "dst.", sql, count=1))
    for name, sql in fts:
        table, rowid = INDEXED[name]
        db.execute(sql.replace("CREATE VIRTUAL TABLE ", "CREATE VIRTUAL TABLE dst.", 1))
        listed = ", ".join(quote(c) for c in columns_of(db, "dst", name))
        db.execute(
            f"INSERT INTO dst.{quote(name)} (rowid, {listed}) SELECT {quote(rowid)}, {listed} FROM dst.{quote(table)}"
        )
        db.execute(f"INSERT INTO dst.{quote(name)} ({quote(name)}) VALUES ('optimize')")
    db.commit()
    db.execute("VACUUM dst")
    return counts


def split(source_path, out_dir, source, regions, max_mb=None, log=sys.stderr):
    """Writes search-<region>.sqlite into out_dir for every region cut from `source`; returns {file: rows per table}."""
    wanted = targets(regions, source)
    if not wanted:
        raise SystemExit(f'No region in regions.json has "search_from": "{source}"')
    started = time.time()
    os.makedirs(out_dir, exist_ok=True)
    # The marks go in a scratch database next to the packs, the source is attached read as "src".
    work = os.path.join(out_dir, f"split-{source}.work")
    if os.path.exists(work):
        os.remove(work)
    db = sqlite3.connect(work)
    written = {}
    try:
        db.execute("PRAGMA journal_mode = OFF")
        db.execute("PRAGMA synchronous = OFF")
        db.execute("ATTACH ? AS src", (source_path,))
        tables, fts, indexes = schema(db)
        mask = masker(regions, wanted)
        keys = {}
        for name, _ in tables:
            if name in PLACED:
                lat, lon, scale = PLACED[name]
                keys[name] = keys_of(db, name)
                kept, left = mark(db, name, keys[name], lat, lon, scale, mask)
                print(f"    {name}: {kept} rows in {', '.join(wanted)}, {left} in none of them left out", file=log)
        for i, region in enumerate(wanted):
            name = f"search-{region}.sqlite"
            path = os.path.join(out_dir, name)
            part = path + ".part"
            if os.path.exists(part):
                os.remove(part)
            db.execute("ATTACH ? AS dst", (part,))
            try:
                db.execute("PRAGMA dst.journal_mode = OFF")
                counts = write(db, region, 1 << i, source, tables, fts, indexes, keys)
            finally:
                db.execute("DETACH dst")
            size_mb = os.path.getsize(part) / 1e6
            rows = ", ".join(f"{counts[t]} {t}" for t in counts if t not in COPIED)
            print(f"    {name}: {size_mb:.1f} MB ({rows})", file=log)
            if max_mb is not None and size_mb > max_mb:
                os.remove(part)
                raise SystemExit(f"{name} is {size_mb:.0f} MB, over the {max_mb:.0f} MB limit (--max-mb)")
            os.replace(part, path)
            written[name] = counts
    finally:
        db.close()
        os.remove(work)
    print(f"    cut {os.path.basename(source_path)} into {len(written)} packs in {time.time() - started:.0f}s", file=log)
    return written


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("source_pack", help="the search pack to cut, e.g. data/out/search-nys.sqlite")
    parser.add_argument("out_dir")
    parser.add_argument("--source", default="nys", help="the region id regions.json's \"search_from\" names")
    parser.add_argument("--regions", default=REGIONS_JSON, help="pipeline/regions.json")
    parser.add_argument("--max-mb", type=float, help="fail if a pack is larger than this")
    args = parser.parse_args(argv)
    split(args.source_pack, args.out_dir, args.source, load_regions(args.regions), max_mb=args.max_mb)


if __name__ == "__main__":
    main()
