# Southbound

A privacy-first, offline Android maps app for New York State. It installs only
from this repo's GitHub Releases. Plan and rules: [HANDOFF.md](HANDOFF.md) and
[CLAUDE.md](CLAUDE.md).

**Status: Phase 0.** The app shows an NYC vector basemap from a local PMTiles
pack, in light and dark styles, with a GPS location puck.

## Install

Point [Obtainium](https://github.com/ImranR98/Obtainium) at this repo, or
download `southbound-<version>.apk` from the latest release. Each release also
has a `.sha256` file.

## Load the NYC map pack

The app ships without map data. Build the pack (below), plug the phone in with
USB debugging on, open Southbound once, then run:

```sh
make -C pipeline push-nyc
```

This runs `adb push` into the app's import folder,
`/sdcard/Android/data/app.southbound/files/packs/`. The release APK can read
that folder and other apps can't. By hand:

```sh
adb shell mkdir -p /sdcard/Android/data/app.southbound/files/packs
adb push pipeline/data/out/basemap-nyc.pmtiles /sdcard/Android/data/app.southbound/files/packs/
```

The app checks for the pack every time it comes to the foreground. Until one is
there, it shows the exact path it expects. Phase 3's pack manager adds
download and import from a file on the phone.

## Build the NYC basemap pack

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

## Releasing

Push a tag and GitHub Actions does the rest:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release.yml` runs the tests, builds the release APK signed
with your key, verifies the signature and publishes
`southbound-0.1.0.apk` plus its checksum as a GitHub release. Tags must look
like `v1.2.3` (each part 0-99). The version code is derived from the tag, so each
new tag must be higher than the last or phones will refuse the update.

### One-time signing setup

Android only installs an update signed with the same key as the installed app.
**If you lose this key, every user has to uninstall and reinstall.** Back it up
somewhere outside GitHub.

```sh
keytool -genkeypair -v -keystore southbound-release.jks -alias southbound \
  -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 southbound-release.jks   # on macOS: base64 -i southbound-release.jks
```

Add these in GitHub under Settings > Secrets and variables > Actions:

| Secret | Value |
| --- | --- |
| `SOUTHBOUND_KEYSTORE_BASE64` | The base64 output above |
| `SOUTHBOUND_KEYSTORE_PASSWORD` | Keystore password |
| `SOUTHBOUND_KEY_ALIAS` | `southbound` |
| `SOUTHBOUND_KEY_PASSWORD` | Key password |

## Developing

You need JDK 17 or newer and the Android SDK (platform 36), found through
`ANDROID_HOME` or `local.properties`.

```sh
./gradlew testDebugUnitTest lintDebug :app:installDebug
```

A debug build has a different signature from the releases, so uninstall the
release build before installing a debug one, and the other way round. CI
(`.github/workflows/ci.yml`) runs unit tests, lint and a debug build on every
pull request, and checks the styles and pipeline scripts.

| Path | What's there |
| --- | --- |
| `app/` | Application module: `MainActivity`, theme, MapLibre init, release signing |
| `core/map/` | `SouthboundMap` composable, style loading, GPS-only location flow |
| `core/packs/` | `PackStore`: where packs live and whether one is valid |
| `core/network/` | `NetworkGate` and per-feature toggles (nothing uses the network yet) |
| `feature/explore/` | The first screen: map, permission prompt, recenter button |
| `core/*`, `feature/*` others | Empty modules, filled in by later phases |
| `build-logic/` | Gradle convention plugins, including a ban on Play Services, Firebase and crash SDKs |
| `styles/` | Generated `light.json` and `dark.json`, plus bundled Noto Sans glyphs |
| `pipeline/` | Data pipeline: `make -C pipeline nyc` |

### Changing the map styles

Edit `pipeline/styles/build_styles.py`, then run `make -C pipeline styles`.
Don't edit the JSON by hand; CI fails if the JSON doesn't match the script.

## Phase 0 exit test

1. Install the release APK and load the NYC pack as above.
2. On the phone, open Settings > Apps > Southbound > Permissions and turn
   **Network** off (GrapheneOS). On other Android builds, use airplane mode.
3. Open the app and grant precise location.
4. Check that the NYC map renders with street and place labels, in light and
   dark mode (switch the system theme; the map follows it).
5. Walk or drive: the blue dot moves and the camera follows it. Pan away and
   the recenter button turns grey. Tap it to follow again.

## Third-party data

- Map data © OpenStreetMap contributors, ODbL.
- Noto Sans glyphs in `styles/glyphs`, SIL Open Font License 1.1, taken from
  [protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets).
