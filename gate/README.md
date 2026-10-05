# Release gate

`baseline.json` records what the shipped APK (the release build) can do: its permissions (with
`maxSdkVersion` and `usesPermissionFlags`), components another app or the system can reach (exported
ones, plus every service, receiver and provider) with their intent-filter actions, categories and data,
package queries, native libraries and any code outside the main dex files, network API types, host and
URL literals, runtime and file dependencies, SDK levels, `debuggable`, backup and cleartext-traffic
flags, the network security config's trust anchors, cleartext rules and pins, and the signing
certificate. The CI workflow builds the release APK (unsigned: no job that runs repository code has the
signing key), runs `gate.py check` against this file, and fails on any difference. So a change to what
Southbound can do only gets in if the same pull request changes `baseline.json`, where the owner sees it
before merging. In a pull request the gate also lists, as warnings, every field the pull request changes in
`baseline.json` itself.

The Release workflow (`release.yml`) builds and gates the same unsigned APK, then signs it in a `sign` job
that checks out nothing and runs no repository code (only GitHub's own actions, `gh` and the Android SDK's
`zipalign` and `apksigner`): it is the only job that references the four `SOUTHBOUND_*` signing secrets, and it
runs only for a commit that is on `main`. The `release-check` job then runs `gate.py cert --require-pin` on
the signed APK: it refuses to publish an APK that is unsigned or signed with a certificate other than
`signing.cert_sha256`. That check runs before the data packs are built and before anything is published.
Two accepted exceptions to "no job with a write token runs repository code", both only for a commit on
`main`: the `data` jobs run the pipeline's scripts and third-party tools with a write token (each pack is
uploaded straight to the draft release, too large for artifact storage), and `publish` runs
`scripts/release-manifest.py` (standard library only) next to its token. The signature schemes are the ones
the shipped releases carry: v2 only (`SIGNING_SCHEMES` in the workflows; v0.1.12's signing block holds v2
and nothing else).

The full contract (fields, normalisation, exit codes) is `specs/gate-baseline.md` in the program repo.
`gate.py` uses only the Python standard library; in CI it cross-checks its manifest reading against
`aapt2 dump badging` and `aapt2 dump permissions`. `file-dependencies.init.gradle` lists file
dependencies and desugaring modules, which `gradle dependencies` doesn't show.

`gate.py`, `test_gate.py`, `annotate-file.sh`, `file-dependencies.init.gradle` and `testdata/` are copies
of the files in the Prikey repository, byte for byte, so that every repo runs the same gate. A fix to
them goes to Prikey first. This README and `baseline.json` are Southbound's own.

## What the baseline records for Southbound

- **Network.** The app requests `INTERNET`, so every `network_api` type and every `hosts` entry has a note
  saying which part of the app it belongs to. Every request in the app's own code goes through
  `:core:network` `NetworkGate`, which has one toggle per feature (`NetworkFeature`), off by default except
  pack downloads. Hosts and types that come from a library (MapLibre's Mapbox-era strings, Kotlin and
  Android tooling messages) say so, and are never contacted by app code. What the baseline cannot show:
  - **GitHub.** `GitHubReleases.isAllowed` accepts `api.github.com`, `github.com` and any
    `*.githubusercontent.com` over HTTPS (checked on every redirect hop). The release asset bytes come from
    `*.githubusercontent.com`, which the gate cannot record (a leading-dot suffix is not a host literal; a
    gate 2.1 item), so only `api.github.com` and `github.com` are in the list.
  - **511NY.** `FiveElevenNy.absoluteUrl` passes any absolute camera-image URL in the feed through to
    `NetworkGate.get` (redirects followed), so the hosts the feed names are contacted under `TRAFFIC_511NY`
    and are not in the baseline.
  - **Other HTTP clients.** MapLibre Native has its own HTTP stack (OkHttp), unused because the style is
    bundled and tiles are `pmtiles://file://`. valhalla-mobile's `com/valhalla/valhalla/http/ValhallaHttpClient`
    is a second client outside `NetworkGate`, inert because Valhalla's `default.json` has no
    `mjolnir.tile_url` and `OfflineRouter` sets only `tile_extract`. Ferrostar's HTTP classes are removed by
    R8. None of this can be proved from the APK.
  - **Connectivity.** MapLibre's `ConnectivityReceiver` and WorkManager read `ConnectivityManager` and
    `Network*` state (getters only, no sockets).
- **Native libraries.** MapLibre, valhalla-mobile, Ferrostar, sherpa-onnx (with ONNX Runtime), JNA,
  AndroidX SQLite, graphics path and DataStore's shared counter (`libdatastore_shared_counter.so`). The
  release is zipaligned with `-P 16`, so they are 16 KB page-aligned. A new one is a gate change.
- **Map styles.** `assets/*.json` (the light and dark style) and the Valhalla default configuration are
  scanned for URLs too (`scan.extra_text_paths`).
- **Pin.** `signing.cert_sha256` is the certificate of the first and the latest release at the time
  (v0.1.0 and v0.1.12, identical), read from the APKs' signing blocks with `gate.py fingerprint`.

## When the gate fails

The log lists each difference as `GATE + field: value` (new in the APK), `GATE - field: value` (gone
from the APK) or `GATE ~ field: old -> new`, and then prints the whole baseline this APK would need
(also as `baseline-generated` notice annotations, see below). If the change is intended, replace
`baseline.json` with it in the pull request, give every new `hosts` or `network_api` entry a `note`
saying why it is there (an entry without a note fails the check), and list the change under GATE
CHANGES in the pull request description.

For many entries from one domain or one library, a `notes` rule fills the note: `{"field": "hosts",
"suffix": "doubleclick.net", "note": "..."}` (a literal domain of two labels or more; it matches that
domain and its subdomains) or `{"field": "network_api", "prefix": "Lorg/bouncycastle/", "note": "..."}`
(a type package of two segments or more). Patterns are refused, so no rule can note a host nobody has
looked at. A filled note starts with `(rule <suffix or prefix>)`, and a pull request's new entries are
listed with their notes.

With the Android SDK and Google's Maven repository at hand, the same can be done locally (run
`scripts/fetch-voice.sh` first):

```sh
./gradlew --init-script gate/file-dependencies.init.gradle -Pgate.configuration=releaseRuntimeClasspath \
  :app:assembleRelease :app:dependencies --configuration releaseRuntimeClasspath gateFileDependencies > build/gradle.log
python3 gate/gate.py generate --apk app/build/outputs/apk/release/app-release-unsigned.apk --deps build/gradle.log
```

`python3 gate/gate.py fingerprint --apk <published apk>` prints the signing certificate's SHA-256 from
the APK's signing block, for setting the pin where the SDK is missing. It reads one scheme (the highest
of v3.1, v3, v2) and each signer's first certificate, so it fits single-signer v2/v3 apps without key
rotation; the release-check job's `gate.py cert`, which verifies with `apksigner`, is authoritative. Run
the script's own tests with `python3 -m unittest discover -s gate`.

## Dependency verification metadata

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact Gradle downloads, and CI runs
Gradle with `--dependency-verification=strict`, so a swapped dependency or plugin fails the build before
it runs. That includes the sherpa-onnx AAR and the POM `scripts/fetch-voice.sh` writes next to it (the
local Maven repository in `third-party/m2`); the script also checks the AAR against its own pinned
SHA-256 before Gradle sees it. The file was written on Linux, so it pins the Linux `aapt2`; a local build
on macOS or Windows needs `--dependency-verification=lenient` (never in CI). After changing a dependency,
delete the file in the pull request: the next CI run writes a new one from the real build tasks (the
debug and release variants and their unit tests, which is everything the Release workflow resolves too),
attaches it as `verification-metadata-android`, and fails. Commit that file. The Release workflow never
writes it: without the file it stops.

## Bringing a file back from CI without downloading artifacts

Agent sessions can't download artifacts, so CI also prints these files xz-compressed and
base64-encoded in numbered notice annotations titled `<label> <i>/<n> <sha256>`
(`gate/annotate-file.sh`): `verification-metadata-android` and `baseline-generated`. Read the check run's
annotations through the API, keep the chunks with the label and digest you want, check none is missing
or repeated, join them in order, decode, decompress, and compare the SHA-256 with the title.
