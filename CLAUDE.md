# Southbound - project rules

The app is named Southbound. Application ID and Kotlin package: `app.southbound`.

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

## Distribution
- The APK ships only through GitHub Releases (Obtainium tracks them). No Play Store.
- Every merge to main releases by itself: .github/workflows/release.yml picks
  the next patch version, builds, signs and publishes the APK, and rebuilds the
  map data when the pipeline changed (otherwise it carries the previous
  release's packs over). Nobody tags; a `vX.Y.Z` tag push is only an override
  for a commit already on main. Versions only go up. The 2nd of each month
  rebuilds the data on a schedule. Merges that touch the pipeline each cost a
  full data rebuild (an hour or more), so land related ones together.
- Anything a user does on the phone must work on the signed release build,
  not only debug builds (no `run-as` workflows).
- The owner has no computer, only a phone with Termux and the GitHub app.
  Every step they take (building packs, loading them, releasing) must work from
  the phone: heavy work runs in GitHub Actions, packs load via in-app import.

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
- Always open a pull request on GitHub for your work; never leave it only on a branch.
- Export and import live in `core/export` (format and rules: `docs/EXPORT.md`). The FWX codec in its `fwx/`
  package is a verbatim copy of the program's codec; never edit it here (`CodecCopyTest` fails). A new entry, API
  key, network toggle or setting bumps the bundle schema. The export is read back with the import reader, and
  neither side may log, show or persist a passphrase or an API key.
