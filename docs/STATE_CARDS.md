# State-line cards

What changes when a road crosses a state line, from data written into the app. Offline, no pack, no network, no request.

## What the owner sees

- **Route sheet.** Each driving route option lists the state lines it crosses, in order: "Crosses into Ohio in 29 mi".
  A tap opens that state's card.
- **On a crossing.** With the map open (or guiding), a GPS fix that moves into another state opens "Welcome to Ohio"
  as a dismissible sheet. It opens on the fields that differ from the state just left; "All fields" shows the whole
  card. During guidance, with voice on, one line is spoken ("Entering Ohio. Handheld phone use banned for all drivers.
  70 interstate rural, 65 urban."), the first clause of the handheld and speed fields when those changed; a clause
  that carries "verify" is left out of speech. It uses the hazard-alert voice path (`UtteranceKind.HAZARD_ALERT`), so
  the voice mode and the voice toggles apply as they do for camera alerts.
- **Map options > This state.** The card for the state the phone is in, with every field.
- **Settings > Driving > State-line cards** (on by default) turns the route lines, the sheet, the spoken line and the
  Map options row off together. It is exported (bundle schema 10, `EXPORT.md`).
- **The card.** Twelve fields: time zone, handheld phone, speed limits, tolls, state police short code, move over,
  radar detectors, open container, cannabis, sales tax, rest-area overnight, daylight time. Each shows the date it was
  compiled and, where the value says "verify", a small "verify" tag. The footer reads "From public sources; check before
  relying on it." The text is informational and phrased as fact, never as advice. The sheet opens full height and
  scrolls, so a phone on its side shows all of it.

## Privacy

The current state is held in memory by the map screen (`LiveState`) and nowhere else. Nothing about crossings, times or
places is written, exported or logged (HANDOFF.md, "never the owner's places, drives, routes or times"; `TripRegions`,
"never where the phone went"). The route-sheet lines are worked out from the route's own points when the options are
shown and dropped with them. The card is a view of static data keyed by a state code computed on the fly.

## The module

`:core:states` (plain Kotlin, tests in `core/states/src/test`):

| File | Does |
|---|---|
| `StateOutlines.kt` | Parses `state-outlines.geojson` (51 features keyed by `postal`, about 2 km accurate, a margin out to sea). `stateAt(lat, lon)` by ray casting (the approach of `Region.contains` in core/packs, copied, not coupled); `isClearOf` for the hysteresis. A point in no outline (more than about 2 km offshore) is `null`, and callers keep the last known state. |
| `StateCards.kt` | Parses `state-cards.json` into `StateCard(code, name, fields)`, `Field(key, label, value, sources, asOf)`; `diff(from, to)`; `spoken(card, changed)`. |
| `Crossings.kt` | `crossingsAlong(polyline)`: a state change counts only when the route is at least 1 km from the border of the state it left, so a road that hugs a border, or dips across it, does not flap. The distance to the line is placed by bisection between the two points either side of it. |
| `LiveState.kt` | The live state machine: a crossing is reported when a state other than the current one is seen twice in a row, the two fixes at least 500 m apart. Fixes in no state are ignored. |
| `StateNames.kt` | The 51 names, so a screen can draw "Ohio" without reading the data (a test keeps it equal to the cards). |
| `StateData.kt` | Reads the two resources once, lazily (call it off the main thread). |

The route sheet's lines come from `RouteAlerts.crossings` (core/search) via `StateLines` (feature/explore), computed apart
from the camera counts so they show without a search pack.

## The data

`core/states/src/main/resources/app/southbound/core/states/` holds `state-cards.json`, `state-outlines.geojson` and the
compiler's `README.md` (sources, dates, how it was gathered, the caveat that the per-field values are a starting point to
spot-check against state DOT and DMV pages, and what "verify" means). The files are Java resources, not Android assets, on
purpose: the release gate scans `assets/*.json` for host literals and would read every source URL in the cards as a host
the app talks to. They are copied unchanged from the program's `specs/data/state-cards/`; to refresh them, replace the
files and the README together and keep `StateCardsTest` green (51 records, twelve fields each, none empty).

## Checking it on the phone

1. Plan a route between two towns in different states, none near home. The route sheet lists "Crosses into <state> in N mi".
   Tap it: the card opens with the changed fields first. Rotate the phone with the card open.
2. Settings > Driving > State-line cards off: the line disappears. On again: it returns.
3. At the first real crossing the card appears by itself; with voice guidance on, one spoken line.
4. Map options > This state: every field shows a value and a date.
