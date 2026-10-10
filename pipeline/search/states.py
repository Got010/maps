"""Which state a point is in, for a search pack built from several extracts:
New York plus northern New Jersey (search-nys), or Maryland, Delaware and DC
(search-md). Most OSM addresses carry no addr:state, and the merged extract
no longer says which state a point came from, so the builder asks here.

The New Jersey side is the `area` polygon of the njn region in regions.json
(the state line along the Hudson, the Kill Van Kull and the Arthur Kill, then
the Duke's Line to the Delaware; accurate to about a kilometre). Delaware and
DC are the outlines under "states" in regions.json. Any other point is in the
default state: New York, or the region's "default_state" (Maryland for md). A
region of one state ("state" in regions.json) doesn't ask: build_search.py's
--state names it.

    from states import state_at
    state_at(40.7357, -74.1724)   # "NJ"
    state_at(39.16, -75.52, default="MD")   # "DE"

regions.json is read from next to the search/ directory (pipeline/regions.json,
or /regions.json in the build container, which mounts it there).
"""

import json
import os

REGIONS_JSON = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "regions.json")

# State code -> id of the region in regions.json whose `area` is that state's part.
STATE_AREAS = {"NJ": "njn"}
DEFAULT_STATE = "NY"

_areas = None


def _load():
    global _areas
    if _areas is None:
        with open(REGIONS_JSON) as f:
            doc = json.load(f)
        regions = doc["regions"]
        _areas = {state: regions[region]["area"] for state, region in STATE_AREAS.items()}
        _areas.update(doc.get("states", {}))
    return _areas


def _inside(area, lat, lon):
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


def state_at(lat, lon, default=DEFAULT_STATE):
    """The two-letter state of a point: NJ inside the njn area, DE or DC inside their outlines, else `default`."""
    for state, area in _load().items():
        if _inside(area, lat, lon):
            return state
    return default
