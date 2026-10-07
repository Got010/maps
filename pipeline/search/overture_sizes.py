#!/usr/bin/env python3
"""Measures what trimming Overture Places would save in a search pack.

Fetches a region's places once (overture.py fetch), builds the overture_place
and overture_fts tables the way a release does, then rebuilds them in
variants and prints each one's size: higher confidence cut-offs, without the
two columns the app never reads (the GERS id and the source dataset), and
without the address in the search text. Also shows what a higher cut-off
would drop (categories and sample names), to judge whether it is worth it.

    overture_sizes.py --region nys --work DIR     (needs duckdb, as fetch does)

Run by .github/workflows/feed-probe.yml; nothing here changes a pack.
"""

import argparse
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import overture  # noqa: E402
from normalize import normalize  # noqa: E402

# (label, min confidence, drop id and dataset, address out of the search text)
VARIANTS = [
    ("release today (0.5)", 0.5, False, False),
    ("0.6", 0.6, False, False),
    ("0.7", 0.7, False, False),
    ("0.8", 0.8, False, False),
    ("0.5, no id/dataset", 0.5, True, False),
    ("0.5, no address in text", 0.5, False, True),
    ("0.5, no id/dataset, no address in text", 0.5, True, True),
    ("0.6, no id/dataset, no address in text", 0.6, True, True),
    ("0.7, no id/dataset, no address in text", 0.7, True, True),
]


def sizes(path):
    db = sqlite3.connect(path)
    try:
        per = dict(db.execute("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name"))
    except sqlite3.Error:
        per = {}
    rows = db.execute("SELECT count(*) FROM overture_place").fetchone()[0]
    db.close()
    return os.path.getsize(path), rows, per


def without_address(search_text, address):
    """The search text with the address's words taken out (they follow the name; see overture.place_row)."""
    if not address:
        return search_text
    words = " " + normalize(address) + " "
    return search_text.replace(words, " ", 1) if words.strip() else search_text


def build_variant(base, out, min_conf, drop_ids, no_address):
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.create_function("without_address", 2, without_address, deterministic=True)
    db.executescript("PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;" + overture.SCHEMA)
    db.execute("ATTACH ? AS base", (base,))
    db.execute(
        "INSERT INTO overture_place (id, name, category, address, lat, lon, phone, website, confidence, dataset,"
        " name_key, search_text) SELECT "
        + ("'', " if drop_ids else "id, ")
        + "name, category, address, lat, lon, phone, website, confidence, "
        + ("NULL, " if drop_ids else "dataset, ")
        + "name_key, "
        + ("without_address(search_text, address)" if no_address else "search_text")
        + " FROM base.overture_place WHERE confidence >= ? ORDER BY rowid",
        (min_conf,),
    )
    db.commit()
    db.execute("DETACH base")
    overture.finish(db)
    db.commit()
    db.execute("VACUUM")
    db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--region", default="nys")
    parser.add_argument("--work", required=True)
    args = parser.parse_args()
    os.makedirs(args.work, exist_ok=True)

    import json

    with open(os.path.join(HERE, "..", "regions.json")) as f:
        bounds = tuple(json.load(f)["regions"][args.region]["bounds"])
    staging = os.path.join(args.work, f"overture-{args.region}.csv")
    overture.fetch(bounds, staging)

    base = os.path.join(args.work, "base.sqlite")
    if os.path.exists(base):
        os.remove(base)
    db = sqlite3.connect(base)
    kept, skipped = overture.load(db, staging, overture.region_scopes(args.region))
    db.commit()
    print(f"loaded {kept} places, skipped {skipped}")

    print("\nconfidence of the rows kept today:")
    for lo, n in db.execute(
        "SELECT round(confidence - 0.05, 1) AS b, count(*) FROM overture_place GROUP BY b ORDER BY b"
    ):
        print(f"  {lo:.1f}-{lo + 0.1:.1f}: {n}")
    for lo, hi in ((0.5, 0.6), (0.6, 0.7), (0.7, 0.8)):
        print(f"\ncategories with confidence {lo}-{hi} (dropped by a {hi} cut-off), top 25:")
        for cat, n in db.execute(
            "SELECT coalesce(category, '(none)'), count(*) FROM overture_place WHERE confidence >= ? AND confidence < ?"
            " GROUP BY 1 ORDER BY 2 DESC LIMIT 25",
            (lo, hi),
        ):
            print(f"  {n:>7}  {cat}")
        sample = [r[0] for r in db.execute(
            "SELECT name FROM overture_place WHERE confidence >= ? AND confidence < ? ORDER BY random() LIMIT 20",
            (lo, hi),
        )]
        print("  sample:", "; ".join(sample))
    has_phone = db.execute("SELECT avg(phone IS NOT NULL), avg(website IS NOT NULL) FROM overture_place").fetchone()
    print(f"\nshare with a phone {has_phone[0]:.2f}, with a website {has_phone[1]:.2f}")
    db.close()

    print("\nvariant sizes (MB): file | rows | overture_place | overture_fts* | name_key index")
    for label, conf, drop_ids, no_address in VARIANTS:
        out = os.path.join(args.work, "variant.sqlite")
        build_variant(base, out, conf, drop_ids, no_address)
        total, rows, per = sizes(out)
        fts = sum(v for k, v in per.items() if k.startswith("overture_fts"))
        print(
            f"  {label:<42} {total / 1e6:7.1f} | {rows:>8} | {per.get('overture_place', 0) / 1e6:6.1f} |"
            f" {fts / 1e6:6.1f} | {per.get('overture_place_name_key', 0) / 1e6:5.1f}",
            flush=True,
        )
        os.remove(out)


if __name__ == "__main__":
    main()
