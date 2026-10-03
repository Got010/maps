# Southbound

A privacy-first, offline Android maps app for New York State. It installs only
from this repo's GitHub Releases. Plan and rules: [HANDOFF.md](HANDOFF.md) and
[CLAUDE.md](CLAUDE.md).

**Status: Phase 1.** Offline NYC map, place and address search, drive, walk
and bike directions, and spoken turn-by-turn guidance, all on the phone.

## Install

Everything below works from a phone. No computer needed.

Download `southbound-<version>.apk` from the latest release and open it to
install. Each release also has a `.sha256` file. A newer APK installs over the
old one and keeps your data. [Obtainium](https://github.com/ImranR98/Obtainium)
is optional: it watches this repo's releases and installs new ones for you.

## Load the NYC map

The app ships without map data. There are three NYC packs, each in the Map
data release:

| File | What it adds |
| --- | --- |
| `basemap-nyc.pmtiles` | The map itself (needed first) |
| `search-nyc.sqlite` | Search for places, streets and addresses |
| `routing-nyc.tar` | Directions and turn-by-turn navigation |

Load each one the same way:

1. On GitHub, open the repo's **Releases** and find the newest **Map data**
   pre-release. If there isn't one, go to **Actions > Map data > Run workflow**
   and wait about 15 minutes.
2. Download the file from it in your phone's browser. Don't open it with a
   file manager, which may try to unpack it.
3. Get it into Southbound, either way:
   - In the browser's downloads, share the file and pick **Southbound**. This
     works with browsers such as DuckDuckGo that keep downloads to themselves.
   - Or, for the basemap, open Southbound, tap **Import map file** and pick
     the download.

   The app copies the file into its private storage, so you can delete the
   download afterwards.

Southbound recognises each file by its contents, so a renamed download such as
`routing-nyc (1).tar` still works. The Map data workflow also runs on the 2nd
of every month. To swap in newer data, share the new file to Southbound the
same way; it replaces the old one.

With a computer and adb you can instead push the pack straight to the app's
import folder: `make -C pipeline push-nyc`.

## Build the NYC packs yourself

The Map data workflow does this on GitHub. To run it locally you need Docker,
curl and about 6 GB free disk.

```sh
make -C pipeline nyc
```

This downloads the Geofabrik New York extract (checked against its `.md5`), then
runs Planetiler 0.10.2 in Docker with the OpenMapTiles profile, clipped to the
five-borough bounding box. The first run also downloads water polygons and
Natural Earth data, which are cached in `pipeline/data/sources`. Output:
`pipeline/data/out/basemap-nyc.pmtiles`, with its size and SHA-256 printed at
the end. Set `PLANETILER_HEAP=8g` if Planetiler runs out of memory.

The same run builds the other two packs from the extract, cut to the same box
with osmium:

- `routing-nyc.tar`: Valhalla 3.6.3 tiles (the version valhalla-mobile is
  built on), packed into one tar.
- `search-nyc.sqlite`: places, streets and addresses with an SQLite FTS5
  trigram index, built by `pipeline/search/build_search.py`. Its unit tests run
  with `make -C pipeline test`.

`make -C pipeline tiles-nyc`, `routing-nyc` and `search-nyc` build one each.

## Releasing

Push a tag (`git tag v0.1.0 && git push origin v0.1.0`) and GitHub Actions
does the rest.
`.github/workflows/release.yml` runs the tests, builds the release APK signed
with your key, verifies the signature and publishes
`southbound-0.1.0.apk` plus its checksum as a GitHub release. Tags must look
like `v1.2.3` (each part 0-99). The version code is derived from the tag, so each
new tag must be higher than the last or phones will refuse the update.

### One-time signing setup (in Termux)

Android only installs an update signed with the same key as the installed app.
**If you lose this key, every user has to uninstall and reinstall.** Keep a
backup somewhere other than GitHub and this phone, such as a password manager.

```sh
pkg install openjdk-17
termux-setup-storage          # lets Termux save to your phone's shared storage
keytool -genkeypair -v -keystore southbound-release.jks -alias southbound \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Southbound"
cp southbound-release.jks ~/storage/shared/Download/   # the backup copy
base64 -w0 southbound-release.jks > ~/storage/shared/Download/southbound-key.txt
```

`keytool` asks for a password twice; use the same one for both secrets below.
Open `southbound-key.txt` in a text editor, copy all of it, then in the GitHub
app or website go to the repo's Settings > Secrets and variables > Actions and add:

| Secret | Value |
| --- | --- |
| `SOUTHBOUND_KEYSTORE_BASE64` | The contents of `southbound-key.txt` |
| `SOUTHBOUND_KEYSTORE_PASSWORD` | The password you gave keytool |
| `SOUTHBOUND_KEY_ALIAS` | `southbound` |
| `SOUTHBOUND_KEY_PASSWORD` | The same password |

Then delete `southbound-key.txt`; the `.jks` backup is what matters.

To release, create a tag from the repo's Releases page (Draft a new release >
Choose a tag > type `v0.1.0` > Create new tag > Publish), or push one with git.
The Release workflow builds the APK and attaches it to that release within a
few minutes; until then the release has no APK.

## Developing

You need JDK 17 or newer and the Android SDK (platform 36), found through
`ANDROID_HOME` or `local.properties`.

```sh
scripts/fetch-voice.sh
./gradlew testDebugUnitTest lintDebug :app:installDebug
```

`fetch-voice.sh` downloads the offline voice, which is too big for git: the
sherpa-onnx library (into `third-party/`) and the Piper "Amy" voice (bundled as
app assets). Each download is checked against a pinned SHA-256. CI and the
release workflow run it too.

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
| `core/search/` | Search over the search pack: query parsing, FTS5 lookup, ranking |
| `core/routing/` | Valhalla on the phone (valhalla-mobile) and Ferrostar navigation, GPS only |
| `core/voice/` | Voice guidance: system TTS, the bundled Piper voice, audio routing, settings |
| `feature/explore/` | The main screen: map, search, place card, directions, navigation |
| `feature/search/`, `feature/directions/`, `feature/navigation/`, `feature/settings/` | Those screens and sheets |
| `core/*`, `feature/*` others | Empty modules, filled in by later phases |
| `build-logic/` | Gradle convention plugins, including a ban on Play Services, Firebase and crash SDKs |
| `styles/` | Generated `light.json` and `dark.json`, plus bundled Noto Sans glyphs |
| `pipeline/` | Data pipeline: `make -C pipeline nyc` |

### Changing the map styles

Edit `pipeline/styles/build_styles.py`, then run `make -C pipeline styles`.
Don't edit the JSON by hand; CI fails if the JSON doesn't match the script.

## Phase 0 exit test

1. Install the release APK and import the NYC map as above.
2. On the phone, open Settings > Apps > Southbound > Permissions and turn
   **Network** off (GrapheneOS). On other Android builds, use airplane mode.
3. Open the app and grant precise location.
4. Check that the NYC map renders with street and place labels, in light and
   dark mode (switch the system theme; the map follows it).
5. Walk or drive: the blue dot moves and the camera follows it. Pan away and
   the recenter button turns grey. Tap it to follow again.

## Phase 1 exit test

1. Install the release APK and load all three NYC packs as above.
2. Turn the Network permission off (GrapheneOS) or use airplane mode.
3. Search for a place, such as "Grand Central". Its card shows the address and
   distance. Long-press the map to drop a pin anywhere.
4. Tap **Directions**. Drive, walk and bike each show up to three routes; tap
   one to pick it.
5. Tap **Start** and drive. The next turn shows at the top, time and distance
   left at the bottom, and each turn is spoken. Take a wrong turn: the app says
   "Rerouting" and finds a new route.
6. In Settings, try the voice toggles and **Test voice**. With voice engine set
   to **Built-in**, it uses the bundled Piper voice.

## Third-party data

- Map data © OpenStreetMap contributors, ODbL. Tiles use the OpenMapTiles
  schema, whose license requires the visible credit
  "© OpenMapTiles © OpenStreetMap contributors"; the styles carry it.
- Routing by [Valhalla](https://github.com/valhalla/valhalla) (MIT) through
  valhalla-mobile; navigation by [Ferrostar](https://github.com/stadiamaps/ferrostar)
  (BSD-3-Clause).
- Offline voice: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
  (Apache-2.0) with the Piper en_US "Amy" low voice (dataset from
  [MycroftAI/mimic3-voices](https://github.com/MycroftAI/mimic3-voices); its
  MODEL_CARD ships with the voice) and espeak-ng data (GPL-3.0), downloaded at
  build time.
- Noto Sans glyphs in `styles/glyphs`, SIL Open Font License 1.1, taken from
  [protomaps/basemaps-assets](https://github.com/protomaps/basemaps-assets).
