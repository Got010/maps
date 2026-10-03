# Private NYS Maps - Claude Code Handoff

Last updated: 2026-10-03

## Overview

This is a privacy-first Android maps app for all of New York State. Maps, search, driving, walking and cycling navigation, and NYC transit routing all run on-device, and live traffic is an opt-in layer. It ships as an APK on GitHub and runs on GrapheneOS with no Google Play Services.

**Goals**

- Google Maps-grade UI and navigation for NYS: a fast vector map, search, and turn-by-turn directions with voice.
- Every core feature works offline once the data packs are installed.
- NYC transit covers subway, bus, LIRR and Metro-North, with on-device trip planning and realtime arrivals.
- Live traffic and incidents come from public feeds, fetched in bulk so no server learns where you are.

**Non-goals for v1**

- Reviews, photos, popular times and Street View. No open source for these exists.
- Other states, iOS, accounts and cloud sync.

**Privacy rules.** These are hard constraints and go into CLAUDE.md verbatim.

1. No Google Play Services, Firebase, analytics or crash-reporting SDKs.
2. No request ever carries your location, search text or route. The one exception is opt-in TomTom traffic, which is off by default.
3. Live data is fetched as whole-region feeds, and all filtering happens on the phone.
4. Every network feature has its own toggle. Once packs are installed, the app works with Android's Network permission revoked.
5. History and saved places stay on-device, encrypted at rest.

## Tech stack and architecture

The app is a Kotlin and Compose shell around four proven open-source engines, each reading a data pack from local storage. Two open Android projects, [Vialix](https://github.com/igalmarino/vialix) and [NavMaster](https://github.com/francescobuzle-a11y/navmaster), already run this exact MapLibre + Valhalla + Ferrostar combination with PMTiles, so the integration path is known.

| Layer | Choice | Notes |
| --- | --- | --- |
| Language and UI | Kotlin, Jetpack Compose, Material 3 | arm64 only, minSdk 31 |
| Map rendering | [MapLibre Native Android](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/) | Reads PMTiles via `pmtiles://file://` since 11.7. `pmtiles://asset://` is not supported, so packs live in app files storage |
| Basemap | PMTiles built by Planetiler from OSM | One file per region, no tile server |
| Routing | Valhalla on-device via valhalla-mobile | Car, bike and foot costing, fully offline |
| Navigation | [Ferrostar](https://stadiamaps.github.io/ferrostar/android-getting-started.html) | Guidance state, rerouting, timing of spoken instructions |
| Voice | System TTS plus a bundled Piper voice | See Voice guidance |
| Search | SQLite FTS5 | Addresses and places, offline |
| Transit | GTFS compiled to SQLite, RAPTOR router in Kotlin | Offline trip planning |
| Location | `android.location.LocationManager` | No Google fused location |
| User data | Room DB, encrypted with an Android Keystore key | Saved places, history, settings |

```
Build pipeline (your computer)
  OSM extract | NYS addresses | Overture places | MTA GTFS
        |
        |  make release
        v
Data packs (GitHub Releases, SHA-256 verified)
  Basemap tiles | Routing graph | Search DBs | Transit DB
        |
        |  download once, or import a file
        v
Phone app (works with Network permission off)
  MapLibre map | Valhalla routing | Ferrostar guidance
  FTS5 search  | RAPTOR transit   | Voice: TTS + Piper
        ^
        |  bulk pulls, no location sent *
        |
Live feeds (opt-in, each behind its own toggle)
  511NY incidents | NYC DOT speeds | MTA realtime | TomTom flow *

* TomTom tile requests reveal the map area viewed, so that layer is off by default
```

The pipeline builds packs once a month, and the phone needs nothing else to map, search, route and speak. Live feeds only add traffic and realtime transit on top.

## Data pipeline

A build pipeline on your computer turns public datasets into six data packs, which the phone downloads once and refreshes monthly. Start with an NYC-only extract so each build takes minutes, then point the same scripts at the full state.

| Pack | Built from | Tool | Refresh | Est. size, statewide |
| --- | --- | --- | --- | --- |
| `basemap-<region>.pmtiles` | OSM New York extract (Geofabrik); `basemap-njn` from the New Jersey extract | Planetiler, OpenMapTiles or Protomaps schema | Monthly | 300-800 MB |
| `routing.tar` | Same OSM extracts, New York merged with northern New Jersey | Valhalla tile builder | Monthly | 300-600 MB |
| `addresses.sqlite` | [NYS Address Points](https://gis.ny.gov/streets-addresses) plus OSM addresses | Built into `search-nys.sqlite` (address tables, see Phase 7) by `pipeline/search/addresses.py` | Monthly | ~40 MB per million addresses |
| `places.sqlite` | OSM POIs plus [Overture Places](https://docs.overturemaps.org/guides/places/) | DuckDB script into FTS5 | Monthly | 100-250 MB |
| `transit-nyc.sqlite` | MTA GTFS: subway, 6 bus files, LIRR, Metro-North | Python compiler | Weekly | 50-150 MB |
| Voice model | Piper voice for sherpa-onnx (Amy) | Bundled in the APK | Rarely | about 55 MB with libs |
| `voice-<voice>.zip` | Extra Piper voices (Norman), from the pinned sherpa-onnx tts-models | `pipeline/voices/build.sh` | Rarely | about 58 MB each, downloaded only when picked |

The sizes are guesses to replace after the first real build. Expect 1 to 2.5 GB for the whole state.

- **Regions.** Split the basemap into NYC, Long Island, Hudson Valley, Capital and North Country, Central and Western NY, and Northern New Jersey (`njn`), so you install only what you need. Keep routing as one graph (New York plus northern New Jersey) so cross-region trips work.
- **Addresses.** NYS Address Points is the state's 911-grade address file. It is CC0-licensed, refreshed twice a month, and fills OSM's upstate address gaps.
- **Places.** Overture is CDLA Permissive 2.0, with Foursquare rows under Apache 2.0 and AllThePlaces rows under CC0. Keep it in its own table and dedupe against OSM at query time, because Overture notes that a database joined with OSM may inherit ODbL.
- **Hours.** Opening hours come from OSM's `opening_hours` tag.
- **Search.** Use FTS5 with a trigram tokenizer for typo tolerance, ranked by distance and category. Skip libpostal, whose model is gigabytes.
- **Integrity.** Each release ships a manifest with a SHA-256 per pack. The app verifies a pack before swapping it in.
- **Attribution.** An About screen credits OSM (ODbL), Overture, the Foursquare notice, NYS GIS and MTA.

## Voice guidance

Voice runs fully offline through either the phone's system TTS engine or a Piper voice bundled in the app. GrapheneOS ships no TTS engine yet. Its own on-device [Speech Services](https://grapheneos.social/@GrapheneOS/116608395761254464) went public in May 2026 with one US English voice, and it must be picked as the preferred engine until it is bundled with the OS. The bundled voice guarantees directions still speak if no system engine is set.

All toggles live in Settings > Navigation > Voice. The first one also sits on the navigation screen.

| Toggle | Options | Default |
| --- | --- | --- |
| Voice guidance (nav screen button) | On, Alerts only, Muted | On |
| Per travel mode | Driving, cycling, walking, transit, each on or off | Walking off, rest on |
| Voice engine | System TTS, Built-in voice | System if one is active |
| Street names | On, Off | On |
| Detail level | Full, Brief | Full |
| Traffic and incident alerts | On, Off | On |
| Speed limit alerts | Off, Voice, Beep | Off |
| Reroute announcement | On, Off | On |
| Transit stop alerts | On, Off | On |
| Guidance volume | Softer, Normal, Louder | Normal |
| Other audio | Lower it, Pause it | Lower it |
| Output | Phone speaker, Bluetooth media, Bluetooth as call | Bluetooth media |
| Speech rate | Slider | 1.0x |
| Test voice | Button | - |

"Alerts only" speaks incidents, reroutes and arrival, nothing else.

- Ferrostar ships `AndroidTtsObserver`, which fires Android TTS at the right moment. Wrap it in a `GuidanceVoice` observer that checks the toggles, then sends text to the system engine or to sherpa-onnx.
- Request audio focus as transient-may-duck for "Lower it" and plain transient for "Pause it".
- Store settings in DataStore. The nav-screen button cycles On, Alerts only, Muted.
- Walking with voice off falls back to vibration cues at turns.
- The bundled Piper voice costs about 25 MB of sherpa-onnx native libs plus about 30 MB per voice ([source](https://medium.com/@patare.vivek/running-neural-text-to-speech-on-device-with-piper-and-sherpa-onnx-58f4eed29247)).

## Live traffic

Live traffic is doable from free public feeds, and three of the four sources can be pulled whole so no server learns where you are. Only TomTom, the one source with flow speeds on nearly every road, sees which map area you request, so it stays opt-in and off by default.

| Source | What it gives | Coverage | Access | Privacy |
| --- | --- | --- | --- | --- |
| [511NY API](https://511ny.org/developers/doc) | Incidents, closures, construction, cameras, message signs, winter road conditions, WZDx work zones | Statewide | Free developer key, 10 calls per 60 seconds | Bulk statewide pull, no location sent |
| [NYC DOT Traffic Speeds](https://data.cityofnewyork.us/Transportation/DOT-Traffic-Speeds/i4gi-tjb9) | Speed and travel time per sensor link | Five boroughs, mostly highways and major arterials | Open data API, no key for light use | Bulk pull, no location sent |
| [MTA service alerts](https://api.mta.info/) | Transit disruptions | MTA system | No key | Bulk pull, no location sent |
| [TomTom Traffic](https://docs.tomtom.com/pricing) | Flow and incident vector tiles | Nearly all roads statewide | Free tier of 200K tile requests a month | Tile requests reveal the area you view |

**How traffic reaches the user, in build order**

1. **Show it.** A traffic layer colors road segments by speed, with incident pins that open details and camera stills.
2. **Avoid closures.** 511NY closures on or near the route become Valhalla `exclude_polygons` on the route request. A new incident on your route triggers a spoken reroute offer.
3. **Honest ETAs.** Adjust the ETA from live speeds on the links your route covers.
4. **Traffic-aware routing.** Map-match speed links to Valhalla edges and write live speeds into Valhalla's traffic data. This is the hard step: confirm valhalla-mobile supports live traffic on-device before committing to it.

**Rules**

- Poll only while navigating or while the traffic layer is visible. 511NY every 2 to 5 minutes to stay under its throttle, NYC DOT every 1 to 2 minutes.
- API keys for 511NY, MTA Bus Time and TomTom are entered in Settings, never committed to the public repo.
- The NYC DOT open-data copy looked stale when checked: on Sep 29 its newest row was from Sep 25. Measure its real refresh rate before relying on it.
- A TomTom request can go through an optional personal relay, such as your own server, so TomTom sees the relay's IP instead of the phone's. It still sees which tiles are requested.

## Transit

NYC transit trip planning runs on-device from MTA schedules, and realtime arrivals layer on top from MTA's public feeds. Subway, LIRR, Metro-North and service-alert feeds [need no key](https://api.mta.info/). Bus realtime comes from [MTA Bus Time](https://bustime.mta.info/wiki/Developers/GTFSRt), which requires a free key.

**Schedules (offline)**

- The pipeline compiles MTA GTFS into `transit-nyc.sqlite`: supplemented subway, the six borough bus files, LIRR and Metro-North.
- It precomputes walking transfers between nearby stops and station entrances from OSM footpaths.
- A RAPTOR router in Kotlin plans trips on the phone: earliest arrival, fewest transfers and least walking, shown as alternatives.

**Realtime (online, opt-in)**

- Subway feeds are split by line group. Fetch all groups, not just the lines on your trip, since fetching only those would reveal which lines you ride.
- Arrival boards per station, delays applied to planned trips, and alerts pinned to affected lines.
- MTA also publishes elevator and escalator status, useful for step-free routing.

**UI**

- Trip planner with depart-at and arrive-by, line bullets in MTA colors, and live countdowns.
- Station view with the next departures in each direction.
- Voice or vibration alerts for "your stop is next" and "get off now", tied to the transit stop toggle.

**Later**

- NYC Ferry, PATH and NJ Transit for cross-river trips.
- Upstate systems such as CDTA, NFTA, RTS and Centro. Find and verify their GTFS feeds through the Mobility Database catalog.

## Roadmap

Build NYC end to end first, then scale the same pipeline to the state. Each phase ends with a test you can run on the phone.

### Where things stand (October 2026)

- **Phases 0, 1 and 2 are done** and on `main`. Tested on the owner's phone:
  - map, search, directions preview for drive, walk and bike, transit planning, departure boards, settings and in-app updates
  - Phase 0's GPS follow and Phase 1's drive with spoken turns need a trip inside NYC; the owner lives outside NYC. Route preview works from anywhere with **Start here** on a place card.
- **Phase 3 is built, pending its first statewide release and a test on the phone:**
  - `pipeline/regions.json` defines six basemap regions (nyc, li, hudson, capital, western, njn) and `nys`, the routing and search region (New York and northern New Jersey). `make -C pipeline state` builds what a release ships: six basemaps, `routing-nys.tar`, `search-nys.sqlite`, `transit-nyc.sqlite`. `core/packs Regions.kt` mirrors the file (unit-tested against it).
  - The release workflow creates a draft release first and every job uploads straight to it (one job per pack, since a private repo's Actions artifact storage can't hold 2 GB), then `publish` writes the manifest and publishes. **Watch the first run:** a statewide Valhalla build on a 2-core, 7 GB runner is untested; the data job has a 5-hour timeout. A full data rebuild costs roughly 300 Actions minutes of the 2000 a private repo gets per month.
  - The app shows a region picker (first run and Settings > Updates); the updater fetches a basemap per ticked region plus the statewide packs, and drops `routing-nyc`/`search-nyc` once the `nys` ones arrive. Several basemaps draw as one map (StyleTemplate duplicates the source and layers per pack).
  - 511NY incidents and NYC DOT speeds are a traffic layer behind their toggles (Settings > Live data), with the 511NY key stored encrypted. Closures near a trip become Valhalla `exclude_polygons` on route requests; a closure that appears on the route mid-trip is spoken and the trip is rerouted around it. 511NY's and NYC DOT's hosts were unreachable from the dev container, so both parsers are tested against hand-written fixtures only: **check the first real fetch** (Settings shows feed errors under the traffic button).
  - Not done: the optional TomTom flow layer (read its terms first), and NYS Address Points for upstate search.
- **Northern New Jersey is in** (owner: "so central to getting in and out of NY"; pending the first release and a check on the phone):
  - `basemap-njn.pmtiles` is a sixth region, "Northern New Jersey", box `-74.75,40.30,-73.88,41.36` (Bergen, Hudson, Essex, Passaic, Morris, Union, Middlesex and part of Monmouth, Somerset, Sussex and Mercer), built by Planetiler from Geofabrik's New Jersey extract. NJ Transit and PATH are out of scope: `transit-nyc` stays MTA only.
  - `routing-nys.tar` and `search-nys.sqlite` keep their ids but are now built from New York whole plus New Jersey cut to the njn box, joined with `osmium merge` (`clip_extract` in `pipeline/lib/common.sh`; sources per region in `regions.json`). The `nys` box is now `-79.77,40.30,-71.75,45.02`. Renaming the packs to something like `nyn` is a migration (UpdatePlan, PackStore, old installs) left for later; until then only their display name says New Jersey.
  - Boxes overlap: NYC's covers Newark and Jersey City, njn's covers Staten Island and the Bronx. njn therefore also has an `area` polygon, the New Jersey side of the state line (accurate to about a kilometre). `Regions.basemapsAt` gives a point inside it to njn alone, so a phone with only the NYC map is told the Newark map is missing (`RouteRegions.missing`); elsewhere overlaps behave as before. njn is last in the lists so the picker still opens on NYC.
  - `pipeline/search/states.py` answers "NJ or NY" for a point from that polygon, for the address work to label addresses (OSM mostly lacks `addr:state`).
  - Build cost: `basemap-njn` is about the size of a small region (guess 150-300 MB, one more matrix job of perhaps 30-60 min); `routing-nys` and `search-nys` grow by NJ's share (roughly 15-25 percent more input), so the statewide jobs run correspondingly longer plus a few minutes for the clip and merge. A full rebuild is about 15-25 percent more Actions minutes than before (about 300 per rebuild, now perhaps 350-375). The first release after this change rebuilds everything, since `basemap-njn.pmtiles` is new. Unmeasured: replace these with the first real numbers.
- **Phase 4 is started** (same caveat: test on the phone):
  - Saved places, lists and recents live in `core/userdata`: one JSON document, AES-GCM under an Android Keystore key, in the no-backup directory. The place card has Save (checkbox per list, new lists), and the search panel shows the lists and recents while the box is empty.
  - Speed limits come from the route itself: Ferrostar already asks Valhalla for `shape_attributes.speed_limit`, so `SpeedLimits` reads the current one from the navigation state. The navigation screen shows a US-style sign, red when over, and Speed limit alerts (Off, Voice or Beep) warn once per stretch: after 8 s over the limit plus 5 mph, then again only if the limit changes and you are still over, you were under it for a minute and go over again, or you are far more over (15 mph or 20%) than at the last warning (`SpeedWarningPolicy`). Only where OSM has `maxspeed`.
  - Buildings stand up from zoom 15 (`building-3d` fill-extrusion layer in build_styles.py).
  - Left: lane guidance, traffic-aware routing, upstate transit (see the Phase 4 list).
- **Releases are automatic.** Every merge to `main` publishes the next `vX.Y.Z` with the signed APK, the packs and `southbound.json`. Packs are rebuilt only when `pipeline/` changes, on the 2nd of each month, or on request; otherwise they're carried over. See `.github/workflows/release.yml`.
- **The app updates itself** from the latest release (`core/updates`). Map data downloads are on by default; app updates are off until turned on. While the repo is private, a read-only GitHub token goes in Settings > Updates.
- **Not verified yet:**
  - Metro-North realtime trip-id matching: MTA's feeds weren't reachable from the dev environment, so the GTFS-RT parser is tested against fixtures only.
  - A first-run download of all packs on a fresh install.
  - The Phase 3 exit test: a Buffalo to Montauk route, and a new 511NY incident appearing within 5 minutes.
- **Working notes for the next session:**
  - Android code only compiles in GitHub Actions; Google Maven is blocked in the dev container.
  - Plain-Kotlin modules can be tested locally by copying sources into a small JVM Gradle project (core/network, core/packs, core/traffic, core/map's pure files, core/routing's RouteOptions).
  - `make -C pipeline test` runs the pipeline's Python tests, including the regions check.
  - Release assets of the private repo can be fetched through `api.github.com` (they redirect to a signed download URL).
  - Work goes through a PR on the session's branch; the owner merges from the GitHub app.

**Phase 0: map on screen, NYC only**

- [x] Scaffold the Gradle multi-module project from the repo layout below
- [x] Pipeline: NYC extract through Planetiler into `basemap-nyc.pmtiles`
- [x] App loads the pack from files storage, with light and dark styles
- [x] Location puck from `LocationManager` GPS

Exit test: the map renders and follows you with Network permission revoked.

**Phase 1: search and directions, NYC**

- [x] Valhalla tiles for NYC and valhalla-mobile wired in
- [x] Address and place search with a place-card bottom sheet
- [x] Route preview for drive, walk and bike, with alternatives
- [x] Ferrostar turn-by-turn with rerouting
- [x] Voice guidance with every toggle, on system TTS and the bundled voice

Exit test: a full drive with spoken directions works in airplane mode.

**Phase 2: NYC transit**

- [x] GTFS compiler and RAPTOR router
- [x] Trip planner and station departure boards
- [x] Realtime subway, LIRR, Metro-North and alerts, plus Bus Time with your key

Exit test: planner results match MTA's published schedule on 10 sample trips.

**Phase 3: statewide and live traffic**

- [x] Point the pipeline at the full NYS extract and cut regional basemap packs
- [x] Pack manager: download, verify, update, and import from a file, with a region picker
- [x] 511NY incidents with closure avoidance, and the NYC DOT speed layer
- [ ] Optional TomTom flow layer

Exit test: a Buffalo to Montauk route computes offline, and a new 511NY incident appears within 5 minutes. (Code is in; run it on the phone after the first statewide release.)

**Phase 4: polish toward Google-grade**

- [ ] Lane guidance (Ferrostar's banner `laneInfo` is empty for Valhalla routes; see the risk below), [x] speed limits, [x] 3D buildings
- [x] Saved places, lists and recents, encrypted
- [ ] Traffic-aware routing (needs the live-traffic-in-Valhalla spike first)
- [ ] Upstate transit systems (verify CDTA, NFTA, RTS and Centro GTFS feeds in the Mobility Database; the pipeline's transit builder is MTA-specific today)
- [x] GitHub Actions: APK on each tag, monthly data rebuild

**Phase 5: the feel of the map and of guidance** (guidance half)

- [x] Landmark directions: a `landmark` table in the search pack (signals, stop signs, fuel, fast food, banks, pharmacies, supermarkets, churches, schools), matched to each turn on the phone (`core/search Landmarks`) and worked into the spoken prompt (`core/voice InstructionText`, `GuidancePolicy`). Needs the next search pack build; older packs work without landmarks.
- [x] Smoother voice: next prompt synthesized ahead (`GuidanceVoice.prepare`), quick second turns chained with "then", no cut-offs except reroutes and closure alerts (`SpeechQueue`), chimes generated in code (`Chime`)
- [x] Voice options: a second Piper voice (Norman, public domain) as a downloadable `voice-norman.zip` pack, fetched only once picked (`VoiceSelection`, `UpdatePlan` voices), so the APK keeps only Amy; distance style, chimes; mute until the next turn (long-press the voice button)
- [x] Haptic turns even with voice on (Settings > Voice > Vibrate at turns)
- [x] Speed camera, school zone and ALPR camera alerts from OSM tags in the same table, spoken ~300 m ahead; `ExploreServices.guidance.routeAlerts` lists them for the map
- Not verified on a phone yet: the Android code was written without a local build (CI compiles it). School zones come only from `hazard=school_zone`, school traffic signs and `maxspeed:conditional` with "school"; plain `amenity=school` near the route is a landmark, not an alert, so NYC's many schools don't each set off a warning.

Map half:

- [x] Two-finger rotate and tilt, with a compass button that snaps back to north (MapLibre's compass, fades when facing north)
- [x] One-finger zoom: double-tap and hold, slide up or down
- [x] A "Re-center" pill when the map leaves the puck; tap it, or wait 10 s while navigating
- [x] Slowdown colors on the active route and in the route picker, with "X min of delay" (`RouteTraffic.slowdowns`, NYC DOT links only; TomTom flow isn't built)
- [x] ETA in red with "+X min" when traffic adds more than 2 min, "Typical: N min"
- [x] Incident pins along the route line with the tap-to-read card
- [x] "Faster route found, saves N min" banner every ~3 min mid-trip (`FasterRoute`)
- [x] Arrival: side of street (computed on the phone from the route end, since Ferrostar's route model doesn't carry Valhalla's `side_of_street`), parking nearby from the search pack (`SearchIndex.nearby`; only named parking is in the pack)
- [x] Highway signposts from the banner's exit numbers
- Not verified: none of this has been built or run (the Android build can't run in the dev container); the pure parts are unit-tested.

**Phase 6: driving essentials** (picked by the owner, October 2026; built, not yet run on a phone)

- [x] Home and Work shortcuts: set from the place card's Save dialog (Set as Home / Set as Work), two chips at the top of the search panel while the box is empty. An unset chip asks you to search and pick; the pick sets it.
- [x] Route options: avoid tolls, highways and ferries, and a Car or Truck/RV profile (height, width, length in feet, weight in tons) in an Options sheet on the route preview. Saved in preferences (`RouteOptionsStore`), applied as Valhalla `costing_options` injected into each request (`RouteOptions.withPrefs`), and so also used by reroutes and skip-stop. Driving only; walking and cycling ignore them.
- [x] Multi-stop trips: Add stop on a place card while planning (or from the route sheet), stops listed in the sheet with up, down and remove, Skip stop during a trip. Not done: adding a stop in the middle of a trip.
- [x] Navigation extras: current road name, arrival clock time in the phone's 12/24 hour format, larger instruction text (Ferrostar `InstructionRowTheme`), and night mode by sunset (NOAA equations in `core/map SunTimes`, Settings > Driving toggle, on by default).
- [x] Missing-region warning in the route preview with a Download button that ticks the region and starts an update check.

Exit test: set Home, plan Home, a stop in Albany, then Buffalo avoiding tolls, start it, and the map warns about any missing region first; the navigation screen shows the road name and arrival time.

**Phase 7: data and routing quality**

- [ ] NYS Address Points in the search pack (CC0, twice-monthly) and a smaller `search-nys.sqlite`: index addresses from the state file instead of OSM, cap trigram text, measure before and after
  - Built (search pack schema 2, not yet run on a phone or with the real state file): addresses left the trigram index for `address_street` (one row per street per town, own small trigram index) and `address` (house numbers keyed by street and number, B-tree only). Synthetic measure, 1M addresses: 214 MB as schema 1 `feature` rows, 42 MB in the new tables; a lookup takes under 1 ms. Streets and addresses carry their town (tags, else the nearest locality) and state (`states.py`).
  - The app parses "123 Main St, Nyack NY 10960" (`AddressQuery`): exact number, else "Approximate: nearest number on this street" interpolated from the neighbours, then the street; a typed town or ZIP ranks first but never filters. Older packs still work.
  - To turn the state file on: set the repository variable `ADDRESS_POINTS_URL` (Settings > Secrets and variables > Actions > Variables) to the statewide NYS Address Points zip from https://gis.ny.gov/streets-addresses, then run the Release workflow with "Rebuild data". Check the search job's log for the column mapping and per-table sizes; `pipeline/search/addresses.py --check <file>` shows how a file will be read.
- [ ] Traffic-aware ETAs, then routing: adjust the ETA from NYC DOT speeds on the route's links; then the spike on live speeds in on-device Valhalla before committing to traffic-aware routing
- [ ] Lane guidance: carry Valhalla's lane data through Ferrostar's banner parsing (patch or upstream, see Risks) and draw lanes under the instruction
- [ ] Walking and cycling detail: step-free and stairs preferences for walking (pedestrian costing options), elevation profile for cycling (needs an elevation source in the pack)

Exit test: an upstate house number searches to the right spot; a highway exit in NYC shows which lanes to take; an ETA across Manhattan changes with the live speeds.

**Phase 8: live data and the car**

- [ ] 511NY cameras and message signs on the map: tap a camera for its still, show sign text
- [ ] Android Auto (car app library, no Play Services) or, failing that, a landscape driving layout

Exit test: a camera still opens from the traffic layer; the app runs on a car screen or in landscape with a driving layout.

## Repo layout and distribution

One GitHub repo holds the app, the data pipeline and the map styles. APKs and data packs ship as GitHub Release assets, and Obtainium on the phone tracks new APK releases.

```
nys-maps/
  CLAUDE.md               project rules for Claude Code
  HANDOFF.md              this document
  app/                    Android application module
  core/
    network/              NetworkGate: every request, per-feature toggles
    map/                  MapLibre wrapper, pack loading, layers
    routing/              valhalla-mobile + Ferrostar
    voice/                GuidanceVoice, system TTS, sherpa-onnx
    search/               FTS5 queries and ranking
    transit/              GTFS reader, RAPTOR, GTFS-RT client
    traffic/              511NY, NYC DOT, TomTom clients
    packs/                download, verify, import, update
    userdata/             encrypted saved places and history
  feature/
    explore/ search/ directions/ navigation/ transit/ settings/
  pipeline/
    Makefile              make nyc, make state, make release
    tiles/ routing/ search/ places/ transit/
  styles/                 MapLibre style JSON, light and dark
```

- **APK.** Sign with your own release key, attach to a tagged release, and point Obtainium at the repo.
- **Packs.** Publish a separate data release each month with a signed manifest. Each GitHub release asset must stay under 2 GB, which the regional split already handles.
- **Offline install.** The pack manager can import packs from a file, so you can load them over USB and keep Network permission off entirely.
- **Secrets.** No keys in the repo. Keys live in encrypted app settings.

## Claude Code kickoff

Create the repo, save this document as `HANDOFF.md`, add the `CLAUDE.md` below, then paste the first prompt. Keep each session to one phase so reviews stay small.

**CLAUDE.md**

```markdown
# NYS Maps - project rules

## Hard constraints
- Android, Kotlin, Jetpack Compose. minSdk 31, arm64-v8a only.
- NO Google Play Services, Firebase, GMS location, analytics or crash SDKs.
- Location comes from android.location.LocationManager (GPS provider).
- Every network request goes through :core:network NetworkGate. Each feature
  has its own toggle, off by default except pack downloads.
- No request may contain the user's location, search text or route,
  except the opt-in TomTom traffic layer.
- The app must work with the Network permission revoked once packs are installed.
- API keys are user-entered in Settings and stored encrypted. Never commit keys.

## Stack
- MapLibre Native Android; basemap via pmtiles://file:// from app files storage.
- Valhalla on-device via valhalla-mobile; guidance via Ferrostar.
- Voice: system TTS first, bundled sherpa-onnx Piper voice as fallback.
- Search: SQLite FTS5 (trigram). Transit: RAPTOR over transit-nyc.sqlite.

## Working rules
- Follow HANDOFF.md phases in order. Stop at each phase's exit test and report.
- Unit-test every parser: GTFS, GTFS-RT, 511NY, NYC DOT, pack manifest.
- Pipeline is reproducible: make -C pipeline nyc | state | release.
- Commit small, with clear messages.
```

**First prompt**

```
Read CLAUDE.md and HANDOFF.md. Do Phase 0 only.
1. Scaffold the multi-module Gradle project from the repo layout.
2. Build pipeline/tiles: download the Geofabrik New York extract, clip to the
   five boroughs, run Planetiler in Docker, output basemap-nyc.pmtiles.
3. App: load that file from app files storage with a light and dark style,
   show a GPS location puck.
4. Tell me how to push the pack to the phone with adb for testing.
Stop when the Phase 0 exit test passes and show me what you built.
```

## Risks and open questions

The biggest gap to Google isn't code, it's data: no reviews, photos or popular times, and thinner business coverage upstate. Everything below is solvable but worth checking early.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Ferrostar drops Valhalla lane data, reading lanes only from banner sub-components ([discussion](https://github.com/stadiamaps/ferrostar/discussions/932)) | No lane guidance | Patch the parser or upstream a fix in Phase 4 |
| Live traffic in on-device Valhalla is unverified | Traffic-aware routing may need another approach | Prove it in a spike before Phase 4 |
| NYC DOT open-data copy may lag | Stale speeds | Measure refresh, and look for the direct DOT feed |
| TomTom terms on caching tiles and using data with another routing engine | Terms violation | Read the terms before shipping that layer |
| Pack sizes of 1 to 2.5 GB | Storage, first-run download | Regional packs, file import |
| GPS plus rendering during long drives | Battery and heat | Profile in Phase 1, cap frame rate while navigating |
| Cartography quality | Looks cheap next to Google | Start from a proven OpenMapTiles or Protomaps style and iterate |
| Upstate transit feeds unverified | Gaps outside NYC | Check each agency in the Mobility Database |

- [ ] Read the 511NY Developer Access Agreement before requesting a key
- [ ] Decide whether packs will be public. If yes, handle ODbL share-alike for OSM-derived packs

## Sources

- [511NY API documentation](https://511ny.org/developers/doc)
- [NYC DOT Traffic Speeds, NYC Open Data](https://data.cityofnewyork.us/Transportation/DOT-Traffic-Speeds/i4gi-tjb9)
- [MTA realtime feeds](https://api.mta.info/) and [MTA Bus Time GTFS-RT](https://bustime.mta.info/wiki/Developers/GTFSRt)
- [TomTom pricing](https://docs.tomtom.com/pricing)
- [MapLibre Android PMTiles example](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)
- [Ferrostar Android guide](https://stadiamaps.github.io/ferrostar/android-getting-started.html)
- [Vialix](https://github.com/igalmarino/vialix) and [NavMaster](https://github.com/francescobuzle-a11y/navmaster)
- [GrapheneOS Speech Services announcement](https://grapheneos.social/@GrapheneOS/116608395761254464)
- [Piper with sherpa-onnx on Android](https://medium.com/@patare.vivek/running-neural-text-to-speech-on-device-with-piper-and-sherpa-onnx-58f4eed29247)
- [Overture Places guide](https://docs.overturemaps.org/guides/places/)
- [NYS Streets and Address Points](https://gis.ny.gov/streets-addresses)
