#!/usr/bin/env python3
"""Read the NYS Address Points file into the search pack's address tables.

NYS Address Points is the state's 911-grade address file (the NYS GIS
Program Office's Statewide Address Management, SAM): about one point per
addressed building, CC0, refreshed on the second and fourth Friday of each
month. It fills OSM's upstate address gaps. Published at
https://gis.ny.gov/streets-addresses as a statewide file geodatabase or
shapefile zip; the same layer is the ArcGIS service
https://nysgeohub.ny.gov/arcgis/rest/services/Address/NYS_Address_Points/FeatureServer/0.

search/build.sh downloads the zip from $ADDRESS_POINTS_URL and converts it
with GDAL's ogr2ogr to a CSV in WGS84 with X and Y columns, which is what
this module reads. It also reads GeoJSON (a FeatureCollection, or one
Feature per line), so a small hand-made file works too.

Columns are found by name, ignoring case; the first name present wins:

  number    AddressLabel's leading number if hyphenated (Queens "12-34"),
            else PrefixAddressNumber + AddressNumber + SuffixAddressNumber
  street    CompleteStreetName, else StreetName
  city      ZipName (the postal town, "Nyack"), else PostalCommunityName,
            PostalCity, CityTownName
  alt city  CityTownName (the municipality, "Orangetown"): searchable, not shown
  postcode  ZipCode, Zip5, ZIP
  state     State
  position  Latitude / Longitude, else Y / X (from ogr2ogr GEOMETRY=AS_XY),
            else the GeoJSON point

The SAM field names above are from the service's published schema
(AddressNumber, AddressLabel, CompleteStreetName, ZipCode, CityTownName);
the others are best guesses with fallbacks. Run this file on a download to
see which columns it picked:  addresses.py --check points.csv

Points go through Collector.address_point, so duplicates of OSM addresses
merge in build_search.finish_addresses.
"""

import argparse
import csv
import json
import re
import sys

FIELDS = {
    "label": ["AddressLabel", "FullAddress"],
    "number": ["AddressNumber", "AddNum", "HouseNumber", "number", "addr:housenumber"],
    "number_prefix": ["PrefixAddressNumber", "AddressNumberPrefix", "AddNum_Pre"],
    "number_suffix": ["SuffixAddressNumber", "AddressNumberSuffix", "AddNum_Suf"],
    "street": ["CompleteStreetName", "StreetName", "FullStreetName", "street", "addr:street"],
    "city": ["ZipName", "PostalCommunityName", "PostalCity", "PostalCommunity", "city", "addr:city",
             "CityTownName"],
    "alt_city": ["CityTownName", "Municipality"],
    "postcode": ["ZipCode", "Zip5", "ZIP", "PostalCode", "postcode", "addr:postcode"],
    "state": ["State", "addr:state"],
    "lat": ["Latitude", "lat", "Y"],
    "lon": ["Longitude", "lon", "lng", "X"],
}
HYPHENATED = re.compile(r"^\s*(\d+\s*-\s*\d+[A-Za-z]?)\b")
ORDINAL_CAPS = re.compile(r"^(\d+)(ST|ND|RD|TH)$")


def pick_columns(names):
    """Maps each FIELDS key to the matching column name present, or None."""
    lower = {n.lower(): n for n in names}
    return {key: next((lower[c.lower()] for c in candidates if c.lower() in lower), None)
            for key, candidates in FIELDS.items()}


def tidy(value):
    """Strips; drops ogr2ogr's '.0' on integer columns; '' -> None."""
    if value is None:
        return None
    value = str(value).strip()
    if re.fullmatch(r"\d+\.0", value):
        value = value[:-2]
    return value or None


def title(text):
    """'W 20TH ST' -> 'W 20th St'. Mixed-case text is left alone."""
    if not text or text != text.upper():
        return text
    words = []
    for word in text.split():
        ordinal = ORDINAL_CAPS.match(word)
        if ordinal:
            words.append(ordinal.group(1) + ordinal.group(2).lower())
        else:
            words.append("'".join(p.capitalize() for p in word.split("'")))
    return " ".join(words)


def house_number(get):
    label = get("label")
    match = HYPHENATED.match(label or "")
    if match:
        return match.group(1)
    number = get("number")
    if number:
        return "".join(p for p in (get("number_prefix"), number, get("number_suffix")) if p)
    if label:
        return label.split()[0]
    return None


def records(path):
    """Yields (columns, row getter) per point from a CSV or GeoJSON file."""
    if path.lower().endswith(".csv"):
        with open(path, newline="", encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            columns = pick_columns(reader.fieldnames or [])
            for row in reader:
                yield columns, row, None
        return
    with open(path, encoding="utf-8") as f:
        first = f.read(1)
        f.seek(0)
        if first == "{" and not path.lower().endswith((".geojsonl", ".geojsons", ".ndjson")):
            features = json.load(f).get("features", [])
        else:
            features = (json.loads(line) for line in f if line.strip())
        columns = None
        for feature in features:
            props = feature.get("properties") or {}
            if columns is None:
                columns = pick_columns(props.keys())
            geometry = feature.get("geometry") or {}
            point = geometry.get("coordinates") if geometry.get("type") == "Point" else None
            yield columns, props, point


def points(path, bounds=None):
    """Yields dicts: number, street, city, alt_city, postcode, state, lat, lon.

    Points outside bounds (minlon, minlat, maxlon, maxlat) or without a
    position are left out.
    """
    for columns, row, point in records(path):
        def get(key):
            column = columns.get(key)
            return tidy(row.get(column)) if column else None

        try:
            if point:
                lon, lat = float(point[0]), float(point[1])
            else:
                lat, lon = float(get("lat")), float(get("lon"))
        except (TypeError, ValueError):
            continue
        if bounds and not (bounds[0] <= lon <= bounds[2] and bounds[1] <= lat <= bounds[3]):
            continue
        city = get("city")
        alt_city = get("alt_city")
        yield {
            "number": house_number(get),
            "street": title(get("street")),
            "city": title(city),
            "alt_city": title(alt_city) if alt_city != city else None,
            "postcode": (get("postcode") or "")[:5] or None,
            "state": get("state"),
            "lat": lat,
            "lon": lon,
        }


def load(collector, path, source, bounds=None):
    """Stages every point in path as an address from source. Returns how many."""
    count = 0
    for p in points(path, bounds):
        if collector.address_point(
            p["number"], p["street"], p["lat"], p["lon"], source,
            city=p["city"], postcode=p["postcode"], state=p["state"],
            alt_city=p["alt_city"],
        ):
            count += 1
    print(f"    {count} address points from {path}", file=sys.stderr)
    return count


def main():
    parser = argparse.ArgumentParser(description="Show how an address point file will be read.")
    parser.add_argument("--check", required=True, metavar="FILE", help="CSV or GeoJSON address points")
    parser.add_argument("-n", type=int, default=5, help="sample rows to print")
    args = parser.parse_args()
    for columns, _, _ in records(args.check):
        for key, column in columns.items():
            print(f"  {key:<14} <- {column}")
        break
    total = 0
    for p in points(args.check):
        if total < args.n:
            print(f"  {p}")
        total += 1
    print(f"{total} points with a position")


if __name__ == "__main__":
    main()
