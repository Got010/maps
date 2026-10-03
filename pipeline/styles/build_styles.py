#!/usr/bin/env python3
"""Generate the light and dark MapLibre styles in styles/.

Both styles share one layer list over the OpenMapTiles schema that Planetiler
writes, and differ only in palette. Edit this file, not the JSON output.

The source URL is the placeholder {{PMTILES_URL}}; the app substitutes the
pmtiles://file:// URL of the installed basemap pack at load time. Glyphs load
from bundled assets, so the styles make no network requests.
"""

import json
from pathlib import Path

STYLES_DIR = Path(__file__).resolve().parents[2] / "styles"

SOURCE = "openmaptiles"
FONT_REGULAR = ["NotoSans-Regular"]
FONT_MEDIUM = ["NotoSans-Medium"]
FONT_ITALIC = ["NotoSans-Italic"]

ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors"

PALETTES = {
    "light": {
        "background": "#f5f3ef",
        "residential": "#efece6",
        "commercial": "#f2ede6",
        "industrial": "#ebe8e3",
        "hospital": "#f7e7e7",
        "school": "#f3efdc",
        "cemetery": "#dde8d6",
        "park": "#c9e7c1",
        "wood": "#cfe5c5",
        "wetland": "#d4e6dd",
        "sand": "#f2ead2",
        "water": "#9fc9ee",
        "waterway": "#9fc9ee",
        "building": "#e6e2dc",
        "building_outline": "#d8d3cb",
        "aeroway": "#e3e0ea",
        "boundary": "#9e9cab",
        "rail": "#bcbcbc",
        "path": "#c7c3bc",
        "service": "#ffffff",
        "minor": "#ffffff",
        "minor_casing": "#d9d5ce",
        "secondary": "#ffffff",
        "secondary_casing": "#d0cbc2",
        "primary": "#fdf1b8",
        "primary_casing": "#e4cf82",
        "motorway": "#fbd373",
        "motorway_casing": "#dca94a",
        "tunnel_opacity": 0.55,
        "text": "#3c3c3c",
        "text_minor": "#6a6a6a",
        "text_halo": "#ffffff",
        "water_text": "#3d6f99",
        "park_text": "#3f7a3a",
        "poi_text": "#7a5b3a",
        "place_text": "#262626",
        "neighbourhood_text": "#707070",
    },
    "dark": {
        "background": "#1c1f24",
        "residential": "#20242a",
        "commercial": "#23262c",
        "industrial": "#22252a",
        "hospital": "#2a2326",
        "school": "#27261f",
        "cemetery": "#1f2922",
        "park": "#1d3024",
        "wood": "#1c2d22",
        "wetland": "#1c2a2a",
        "sand": "#2b2923",
        "water": "#0f2c42",
        "waterway": "#0f2c42",
        "building": "#2a2e35",
        "building_outline": "#33373f",
        "aeroway": "#272631",
        "boundary": "#6c6a7a",
        "rail": "#4a4d55",
        "path": "#3b3f47",
        "service": "#353a42",
        "minor": "#3a3f48",
        "minor_casing": "#2a2e35",
        "secondary": "#474d57",
        "secondary_casing": "#2d3138",
        "primary": "#5c5a4a",
        "primary_casing": "#3a3930",
        "motorway": "#7a6337",
        "motorway_casing": "#4d3f24",
        "tunnel_opacity": 0.5,
        "text": "#d7d7d7",
        "text_minor": "#a0a0a0",
        "text_halo": "#1c1f24",
        "water_text": "#7fa9cc",
        "park_text": "#7fb584",
        "poi_text": "#c9ac8a",
        "place_text": "#ececec",
        "neighbourhood_text": "#9a9a9a",
    },
}

# Road fill widths in pixels per zoom. Casing is the fill plus an outline.
ROAD_CLASSES = {
    "motorway": {"classes": ["motorway"], "minzoom": 5,
                 "width": [[5, 0.6], [10, 1.6], [14, 5], [18, 26]]},
    "primary": {"classes": ["trunk", "primary"], "minzoom": 7,
                "width": [[7, 0.5], [10, 1.1], [14, 4], [18, 22]]},
    "secondary": {"classes": ["secondary", "tertiary"], "minzoom": 9,
                  "width": [[9, 0.4], [12, 1.1], [14, 3], [18, 18]]},
    "minor": {"classes": ["minor", "busway"], "minzoom": 12,
              "width": [[12, 0.5], [14, 1.8], [18, 14]]},
    "service": {"classes": ["service"], "minzoom": 14,
                "width": [[14, 0.8], [18, 7]]},
}
# Draw order, lowest first.
ROAD_ORDER = ["service", "minor", "secondary", "primary", "motorway"]


def interpolate(stops, base=1.5):
    expr = ["interpolate", ["exponential", base], ["zoom"]]
    for zoom, value in stops:
        expr += [zoom, value]
    return expr


def casing_width(stops):
    return interpolate([[z, w + (0.6 if z < 12 else 1.6)] for z, w in stops])


def class_filter(classes):
    return ["match", ["get", "class"], classes, True, False]


def brunnel_filter(brunnel):
    value = ["coalesce", ["get", "brunnel"], ""]
    if brunnel == "ground":
        return ["!", ["match", value, ["bridge", "tunnel"], True, False]]
    return ["==", value, brunnel]


def name_expr():
    return ["coalesce", ["get", "name:en"], ["get", "name_en"], ["get", "name:latin"],
            ["get", "name"]]


def text_paint(color, halo, halo_width=1.4):
    return {"text-color": color, "text-halo-color": halo,
            "text-halo-width": halo_width, "text-halo-blur": 0.4}


def fill(layer_id, source_layer, color, filter_=None, minzoom=None, opacity=None):
    layer = {"id": layer_id, "type": "fill", "source": SOURCE,
             "source-layer": source_layer, "paint": {"fill-color": color}}
    if filter_ is not None:
        layer["filter"] = filter_
    if minzoom is not None:
        layer["minzoom"] = minzoom
    if opacity is not None:
        layer["paint"]["fill-opacity"] = opacity
    return layer


def road_layers(p):
    layers = []
    for brunnel in ["tunnel", "ground", "bridge"]:
        opacity = p["tunnel_opacity"] if brunnel == "tunnel" else 1
        layers.append({
            "id": f"road-path-{brunnel}", "type": "line", "source": SOURCE,
            "source-layer": "transportation", "minzoom": 14,
            "filter": ["all", class_filter(["path", "track"]), brunnel_filter(brunnel)],
            "layout": {"line-cap": "round", "line-join": "round"},
            "paint": {"line-color": p["path"], "line-opacity": opacity,
                      "line-width": interpolate([[14, 0.6], [18, 2.5]]),
                      "line-dasharray": [2, 1.5]},
        })
        for key in ROAD_ORDER:
            spec = ROAD_CLASSES[key]
            casing_key = f"{key}_casing" if f"{key}_casing" in p else "minor_casing"
            layers.append({
                "id": f"road-{key}-casing-{brunnel}", "type": "line", "source": SOURCE,
                "source-layer": "transportation", "minzoom": max(spec["minzoom"], 10),
                "filter": ["all", class_filter(spec["classes"]), brunnel_filter(brunnel)],
                "layout": {"line-cap": "butt" if brunnel == "tunnel" else "round",
                           "line-join": "round"},
                "paint": {"line-color": p[casing_key], "line-opacity": opacity,
                          "line-width": casing_width(spec["width"])},
            })
        for key in ROAD_ORDER:
            spec = ROAD_CLASSES[key]
            layers.append({
                "id": f"road-{key}-{brunnel}", "type": "line", "source": SOURCE,
                "source-layer": "transportation", "minzoom": spec["minzoom"],
                "filter": ["all", class_filter(spec["classes"]), brunnel_filter(brunnel)],
                "layout": {"line-cap": "round", "line-join": "round"},
                "paint": {"line-color": p[key], "line-opacity": opacity,
                          "line-width": interpolate(spec["width"])},
            })
        layers.append({
            "id": f"rail-{brunnel}", "type": "line", "source": SOURCE,
            "source-layer": "transportation", "minzoom": 11,
            "filter": ["all", class_filter(["rail", "transit"]), brunnel_filter(brunnel)],
            "paint": {"line-color": p["rail"], "line-opacity": opacity,
                      "line-width": interpolate([[11, 0.6], [18, 3]])},
        })
    return layers


def label_layers(p):
    return [
        {
            "id": "water-name-line", "type": "symbol", "source": SOURCE,
            "source-layer": "waterway", "minzoom": 12,
            "filter": ["has", "name"],
            "layout": {"text-field": name_expr(), "text-font": FONT_ITALIC,
                       "text-size": 12, "symbol-placement": "line",
                       "text-letter-spacing": 0.1},
            "paint": text_paint(p["water_text"], p["text_halo"]),
        },
        {
            "id": "water-name", "type": "symbol", "source": SOURCE,
            "source-layer": "water_name",
            "filter": ["==", ["geometry-type"], "Point"],
            "layout": {"text-field": name_expr(), "text-font": FONT_ITALIC,
                       "text-size": interpolate([[8, 11], [14, 15]], 1.2),
                       "text-letter-spacing": 0.1, "text-max-width": 7},
            "paint": text_paint(p["water_text"], p["text_halo"]),
        },
        {
            "id": "water-name-lake-line", "type": "symbol", "source": SOURCE,
            "source-layer": "water_name",
            "filter": ["==", ["geometry-type"], "LineString"],
            "layout": {"text-field": name_expr(), "text-font": FONT_ITALIC,
                       "text-size": interpolate([[8, 11], [14, 15]], 1.2),
                       "text-letter-spacing": 0.1, "symbol-placement": "line"},
            "paint": text_paint(p["water_text"], p["text_halo"]),
        },
        {
            "id": "road-name", "type": "symbol", "source": SOURCE,
            "source-layer": "transportation_name", "minzoom": 13,
            "filter": ["!", class_filter(["rail", "transit", "ferry"])],
            "layout": {"text-field": name_expr(), "text-font": FONT_REGULAR,
                       "text-size": interpolate([[13, 10], [18, 15]], 1.2),
                       "symbol-placement": "line", "text-rotation-alignment": "map",
                       "text-pitch-alignment": "viewport"},
            "paint": text_paint(p["text"], p["text_halo"], 1.6),
        },
        {
            "id": "road-ref", "type": "symbol", "source": SOURCE,
            "source-layer": "transportation_name", "minzoom": 9, "maxzoom": 15,
            "filter": ["all", class_filter(["motorway", "trunk"]), ["has", "ref"]],
            "layout": {"text-field": ["get", "ref"], "text-font": FONT_MEDIUM,
                       "text-size": 11, "symbol-placement": "line",
                       "symbol-spacing": 400, "text-rotation-alignment": "viewport"},
            "paint": text_paint(p["text"], p["text_halo"], 2),
        },
        {
            "id": "poi-park", "type": "symbol", "source": SOURCE,
            "source-layer": "poi", "minzoom": 14,
            "filter": ["all", class_filter(["park"]), ["has", "name"]],
            "layout": {"text-field": name_expr(), "text-font": FONT_MEDIUM,
                       "text-size": 12, "text-max-width": 8},
            "paint": text_paint(p["park_text"], p["text_halo"]),
        },
        {
            "id": "poi", "type": "symbol", "source": SOURCE,
            "source-layer": "poi", "minzoom": 15,
            "filter": ["all", ["!", class_filter(["park"])], ["has", "name"],
                       ["<=", ["coalesce", ["get", "rank"], 99],
                        ["step", ["zoom"], 5, 16, 20, 17, 99]]],
            "layout": {"text-field": name_expr(), "text-font": FONT_REGULAR,
                       "text-size": 11, "text-max-width": 8,
                       "symbol-sort-key": ["coalesce", ["get", "rank"], 99]},
            "paint": text_paint(p["poi_text"], p["text_halo"]),
        },
        {
            "id": "housenumber", "type": "symbol", "source": SOURCE,
            "source-layer": "housenumber", "minzoom": 17,
            "layout": {"text-field": ["get", "housenumber"], "text-font": FONT_REGULAR,
                       "text-size": 10},
            "paint": text_paint(p["text_minor"], p["text_halo"], 1),
        },
        {
            "id": "place-neighbourhood", "type": "symbol", "source": SOURCE,
            "source-layer": "place", "minzoom": 12,
            "filter": class_filter(["neighbourhood", "quarter", "suburb"]),
            "layout": {"text-field": name_expr(), "text-font": FONT_MEDIUM,
                       "text-size": interpolate([[12, 10], [16, 13]], 1.2),
                       "text-transform": "uppercase", "text-letter-spacing": 0.12,
                       "text-max-width": 7},
            "paint": text_paint(p["neighbourhood_text"], p["text_halo"]),
        },
        {
            "id": "place-town", "type": "symbol", "source": SOURCE,
            "source-layer": "place", "minzoom": 8,
            "filter": class_filter(["town", "village", "hamlet"]),
            "layout": {"text-field": name_expr(), "text-font": FONT_MEDIUM,
                       "text-size": interpolate([[8, 11], [14, 15]], 1.2),
                       "text-max-width": 8},
            "paint": text_paint(p["place_text"], p["text_halo"], 1.6),
        },
        {
            "id": "place-city", "type": "symbol", "source": SOURCE,
            "source-layer": "place", "maxzoom": 14,
            "filter": class_filter(["city"]),
            "layout": {"text-field": name_expr(), "text-font": FONT_MEDIUM,
                       "text-size": interpolate([[4, 12], [12, 20]], 1.2),
                       "text-max-width": 8},
            "paint": text_paint(p["place_text"], p["text_halo"], 1.8),
        },
    ]


def build(name, p):
    landuse = [
        ("residential", ["residential", "suburb", "neighbourhood"]),
        ("commercial", ["commercial", "retail"]),
        ("industrial", ["industrial", "railway", "garages"]),
        ("hospital", ["hospital"]),
        ("school", ["school", "university", "college", "kindergarten"]),
        ("cemetery", ["cemetery"]),
    ]
    layers = [{"id": "background", "type": "background",
               "paint": {"background-color": p["background"]}}]
    layers += [fill(f"landuse-{key}", "landuse", p[key], class_filter(classes))
               for key, classes in landuse]
    layers += [
        fill("landcover-wood", "landcover", p["wood"], class_filter(["wood", "forest"])),
        fill("landcover-grass", "landcover", p["park"], class_filter(["grass"])),
        fill("landcover-wetland", "landcover", p["wetland"], class_filter(["wetland"])),
        fill("landcover-sand", "landcover", p["sand"], class_filter(["sand", "beach"])),
        fill("park", "park", p["park"], opacity=0.7),
        fill("aeroway-area", "aeroway", p["aeroway"],
             ["==", ["geometry-type"], "Polygon"], minzoom=11),
        {"id": "waterway", "type": "line", "source": SOURCE, "source-layer": "waterway",
         "paint": {"line-color": p["waterway"],
                   "line-width": interpolate([[8, 0.5], [14, 2], [18, 6]])}},
        fill("water", "water", p["water"],
             ["!=", ["coalesce", ["get", "brunnel"], ""], "tunnel"]),
        {"id": "building", "type": "fill", "source": SOURCE, "source-layer": "building",
         "minzoom": 13, "maxzoom": 16,
         "paint": {"fill-color": p["building"], "fill-outline-color": p["building_outline"],
                   "fill-opacity": interpolate([[13, 0], [14.5, 1]], 1)}},
        # Close in, buildings stand up: OpenMapTiles carries each one's height.
        {"id": "building-3d", "type": "fill-extrusion", "source": SOURCE,
         "source-layer": "building", "minzoom": 15,
         "paint": {"fill-extrusion-color": p["building"],
                   "fill-extrusion-height": ["interpolate", ["linear"], ["zoom"], 15, 0, 16,
                                             ["coalesce", ["get", "render_height"], 5]],
                   "fill-extrusion-base": ["interpolate", ["linear"], ["zoom"], 15, 0, 16,
                                           ["coalesce", ["get", "render_min_height"], 0]],
                   "fill-extrusion-opacity": 0.75}},
        {"id": "aeroway-runway", "type": "line", "source": SOURCE, "source-layer": "aeroway",
         "minzoom": 11, "filter": ["==", ["geometry-type"], "LineString"],
         "paint": {"line-color": p["aeroway"],
                   "line-width": interpolate([[11, 3], [16, 40]])}},
    ]
    layers += road_layers(p)
    layers.append({
        "id": "boundary-state", "type": "line", "source": SOURCE, "source-layer": "boundary",
        "filter": ["all", ["<=", ["get", "admin_level"], 4], ["!=", ["get", "maritime"], 1]],
        "paint": {"line-color": p["boundary"], "line-dasharray": [3, 2],
                  "line-width": interpolate([[4, 0.6], [12, 1.8]])},
    })
    layers += label_layers(p)
    return {
        "version": 8,
        "name": f"Southbound {name}",
        "metadata": {"generated-by": "pipeline/styles/build_styles.py"},
        "sources": {SOURCE: {"type": "vector", "url": "{{PMTILES_URL}}",
                             "attribution": ATTRIBUTION}},
        "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
        "layers": layers,
    }


def main():
    for name, palette in PALETTES.items():
        path = STYLES_DIR / f"{name}.json"
        path.write_text(json.dumps(build(name, palette), indent=2) + "\n")
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
