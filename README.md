# NYS Maps

A privacy-first, offline Android maps app for New York State. Plan and rules:
[HANDOFF.md](HANDOFF.md) and [CLAUDE.md](CLAUDE.md).

**Status: Phase 0.** The app shows an NYC vector basemap from a local PMTiles
pack, in light and dark styles, with a GPS location puck.

## Layout

| Path | What's there |
| --- | --- |
| `app/` | Application module: `MainActivity`, theme, MapLibre init |
| `core/map/` | `NysMap` composable, style loading, GPS-only location flow |
| `core/packs/` | `PackStore`: where packs live and whether one is valid |
| `core/network/` | `NetworkGate` and per-feature toggles (nothing uses the network yet) |
| `feature/explore/` | The first screen: map, permission prompt, recenter button |
| `core/*`, `feature/*` others | Empty modules, filled in by later phases |
| `build-logic/` | Gradle convention plugins, including a ban on Play Services, Firebase and crash SDKs |
| `styles/` | Generated `light.json` and `dark.json`, plus bundled Noto Sans glyphs |
| `pipeline/` | Data pipeline: `make -C pipeline nyc` |

## 1. Build the NYC basemap pack

You need Docker, curl and about 3 GB free disk.

```sh
make -C pipeline nyc
```

This downloads the Geofabrik New York extract (checked against its `.md5`), then
runs Planetiler 0.10.2 in Docker with the OpenMapTiles profile, clipped to the
five-borough bounding box. The first run also downloads water polygons and
Natural Earth data, which are cached in `pipeline/data/sources`. Output:
`pipeline/data/out/basemap-nyc.pmtiles`, with its size and SHA-256 printed at
the end. Set `PLANETILER_HEAP=8g` if Planetiler runs out of memory.

## 2. Build and install the app

You need JDK 17 or newer and the Android SDK (platform 36). Point the build at
the SDK with `ANDROID_HOME` or a `local.properties` file.

```sh
./gradlew :app:installDebug
./gradlew testDebugUnitTest      # unit tests
```

## 3. Push the pack to the phone with adb

Enable USB debugging, plug the phone in, then:

```sh
make -C pipeline push-nyc
```

This streams the file into the app's private storage at
`/data/data/org.nysmaps/files/packs/basemap-nyc.pmtiles`, using `run-as`, so
it needs the **debug** build from step 2. Nothing is written to shared
storage. The script is `pipeline/push-pack.sh` if you want to do it by hand:

```sh
adb shell run-as org.nysmaps mkdir -p files/packs
adb exec-in "run-as org.nysmaps sh -c 'cat > files/packs/basemap-nyc.pmtiles'" \
  < pipeline/data/out/basemap-nyc.pmtiles
```

The app looks for the pack every time it comes to the foreground. Until one is
there, it shows the exact path it expects. A release build can't use `run-as`;
the Phase 3 pack manager adds import from a file.

## Phase 0 exit test

1. On the phone, open Settings > Apps > NYS Maps > Permissions and turn
   **Network** off (GrapheneOS). On other Android builds, use airplane mode.
2. Open the app and grant precise location.
3. Check that the NYC map renders with street and place labels, in light and
   dark mode (switch the system theme; the map follows it).
4. Walk or drive: the blue dot moves and the camera follows it. Pan away and
   the recenter button turns grey. Tap it to follow again.

## Changing the map styles

Edit `pipeline/styles/build_styles.py`, then run `make -C pipeline styles`.
Don't edit the JSON by hand. Both styles pass `gl-style-validate`, and a unit
test checks that they contain no `http` URLs.

## Third-party data

- Map data © OpenStreetMap contributors, ODbL.
- Noto Sans glyphs in `styles/glyphs`, SIL Open Font License 1.1, taken from
  [protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets).
