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
