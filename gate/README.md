# Release gate

`baseline.json` records what the shipped APK (the release build) can do: its permissions (with
`maxSdkVersion` and `usesPermissionFlags`), `uses-feature` declarations, application-level `meta-data`,
components another app or the system can reach (exported ones, plus every service, receiver and
provider) with their intent-filter actions, categories and data, package queries, native libraries and
any code outside the main dex files, network API types (arrays of them too), host and URL literals
(suffix literals such as `.githubusercontent.com` too), digests of the bundled voice files, runtime and
file dependencies, SDK levels, `debuggable`, backup and cleartext-traffic flags, the network security
config's trust anchors, cleartext rules and pins, and the signing certificate.

The CI workflow builds the release APK (unsigned: no job that runs repository code has the
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
the shipped releases carry: v2 only (`SIGNING_SCHEMES` in the workflows; v0.1.12 carries no signature
scheme other than v2, its signing block also holds AGP's dependency-info and padding blocks, which are not
signatures).

When a run fails after the draft exists, `cleanup` deletes the draft and the run's APK artifacts, so only
"Re-run all jobs" recovers it. The release queue holds one waiting run and a newer one replaces it, so a
scheduled data refresh that is still waiting can be replaced by a push (that month's refresh is skipped;
start it by hand with Run workflow on `main`, "Rebuild data" ticked).

The full contract (fields, normalisation, exit codes) is `specs/gate-baseline.md` in the program repo.
`gate.py` uses only the Python standard library; in CI it cross-checks its manifest reading against
`aapt2 dump badging` and `aapt2 dump permissions`. `file-dependencies.init.gradle` lists file
dependencies and desugaring modules, which `gradle dependencies` doesn't show.

`gate.py`, `test_gate.py`, `annotate-file.sh`, `file-dependencies.init.gradle` and `testdata/` are copies
of the files in the Prikey repository, byte for byte, so that every repo runs the same gate (gate 2.1,
`python3 gate/gate.py --version`; Prikey pull request 16). A fix to them goes to Prikey first. This README
and `baseline.json` are Southbound's own: the 2.1 section below is Prikey's, adapted.

## What the baseline records for Southbound

- **Network.** The app requests `INTERNET`, so every `network_api` type and every `hosts` entry has a note
  saying which part of the app it belongs to. Every request in the app's own code goes through
  `:core:network` `NetworkGate`, which has one toggle per feature (`NetworkFeature`), off by default except
  pack downloads. Hosts and types that come from a library (MapLibre's Mapbox-era strings, Kotlin and
  Android tooling messages) say so, and are never contacted by app code. What the baseline cannot show:
  - **GitHub.** `GitHubReleases.isAllowed` accepts `api.github.com`, `github.com` and any
    `*.githubusercontent.com` over HTTPS (checked on every redirect hop). The release asset bytes come from
    `*.githubusercontent.com`, which gate 2.1 records as the suffix `.githubusercontent.com` (see "Hosts the
    gate now records" below); the individual hosts GitHub redirects to are not named in the list.
  - **511NY.** `FiveElevenNy.absoluteUrl` passes any absolute camera-image URL in the feed through to
    `NetworkGate.get` (redirects followed), so the hosts the feed names are contacted under `TRAFFIC_511NY`
    and are not in the baseline.
  - **Other HTTP clients.** MapLibre Native has its own HTTP stack (OkHttp), unused because the style is
    bundled and tiles are `pmtiles://file://`. valhalla-mobile's `com/valhalla/valhalla/http/ValhallaHttpClient`
    is a second client outside `NetworkGate`, inert because Valhalla's `default.json` has no
    `mjolnir.tile_url` and `OfflineRouter` sets only `tile_extract`. Ferrostar's HTTP classes are removed by
    R8. None of this can be proved from the APK.
  - **Connectivity.** WorkManager reads `ConnectivityManager`/`Network*` state for its network constraints,
    and MapLibre's `ConnectivityReceiver` reads `ConnectivityManager` and `NetworkInfo` (getters only, no
    sockets).
- **Native libraries.** MapLibre, valhalla-mobile, Ferrostar, sherpa-onnx (with ONNX Runtime), JNA,
  AndroidX SQLite, graphics path and DataStore's shared counter (`libdatastore_shared_counter.so`). The
  release is zipaligned with `-P 16`, so they are 16 KB page-aligned. A new one is a gate change.
- **Map styles.** `assets/*.json` (the light and dark style) and the Valhalla default configuration are
  scanned for URLs too (`scan.extra_text_paths`).
- **Bundled voice.** `scan.asset_digest_paths` is `["assets/piper/*"]`, so `assets` holds the SHA-256 and
  size of Amy's model (`assets/piper/amy/model.onnx`), her tokens, the eSpeak NG data and the two
  `*.sha256` files `scripts/fetch-voice.sh` writes. A swapped voice or phoneme file shows as
  `GATE ~ assets:` with both digests. Norman, the optional second voice, is a release download, not part
  of the APK.
- **Hosts the gate now records.** `.githubusercontent.com` is the suffix `GitHubReleases.isAllowed`
  accepts for GitHub's download redirects (pack downloads and app updates, each behind its
  `NetworkFeature` toggle); `.mapbox.com` and `.mapbox.cn` are MapLibre's own host checks and are never
  contacted by app code. MapLibre's and valhalla-mobile's own HTTP clients are not behind `NetworkGate`;
  the baseline cannot show that they are unused, the notes say why they are believed to be.
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

## Gate 2.1 (`python3 gate/gate.py --version`; the baseline schema is still 2)

The 2.1 additions are optional sections, so every baseline written before them still loads and checks.
A section that is missing from a baseline counts as empty: it only differs from an APK that has entries,
and then `check` reports them as additions (`GATE + uses_features: ...`). `generate` writes a section only
when the APK has entries, so a baseline without any is regenerated byte for byte. Adopting it was: copy the
new gate into the repo, regenerate the baseline from the latest release APK in the same pull request, with
a note for every new `hosts` and `network_api` entry. Never land a new `gate.py` without its regenerated
baseline: CI is red until then.

- `network_api` also records array descriptors (`[Ljavax/net/ssl/TrustManager;`, `[[Ljava/net/URL;`), each
  with its own note. A `network_api` prefix rule covers arrays of the library's types too; framework
  arrays still get a note each.
- `hosts` also records suffix literals with their leading dot (`.githubusercontent.com`, and
  `*.example.com` as `.example.com`). A `hosts` rule on `example.com` covers `.example.com` too.
- `uses_features`: sorted `NAME;required=true|false` (`glEsVersion=0x30000;required=true` for a GL
  version, `NAME;version=0x400003;required=true` for a feature with a version).
- `meta_data`: sorted application-level `<meta-data>`; an entry is `NAME;value=TEXT` or
  `NAME;resource=TEXT` (a bare `NAME` when it has neither). A reference is resolved through
  `resources.arsc`: a file becomes `NAME;resource=sha256:DIGEST` (the digest of the file's content); a value
  becomes `NAME;value=TEXT` or `NAME;resource=TEXT` after the attribute that held the reference (a
  framework resource is `NAME;resource=android:0x01040001`), with `|` between values that differ by
  configuration. A reference that is a file in one configuration and a value in another, or that does not
  resolve, is exit 2. A component's own meta-data is not recorded.
- `provider_resources`: for every provider `<meta-data android:resource>` (FileProvider's paths file)
  `{provider, meta, sha256, elements}`, the compiled XML as one JSON array per element. Southbound's APK
  has none. The canonical form is frozen (specs/gate-baseline.md, section 1.1), because baselines hold
  these digests.
- `assets`: `{path, sha256, size}` of the inflated content of every top-level APK entry matching
  `scan.asset_digest_paths` (zip globs matched case-insensitively, `*` also matches `/`; nested archives
  are not looked into; `null` is an error, a missing key means the defaults). When the key is missing the
  defaults are `*.tflite`, `*.onnx`, `*.ort` and `*.task`; a list in the baseline replaces them (`[]`
  turns it off). Southbound lists `["assets/piper/*"]`.
- `--compare-to` names the baseline file it was given: "the base branch has no <the `--baseline`
  argument>".
- `gate.py` has no guard against replacing a published APK (`gh release upload --clobber`): that check
  lives in the publishing job of release.yml ("A release that is already out is replaced only when it was
  made from this very commit").

### Canonical form of a compiled XML resource (frozen from 2.1)

Quoted from `specs/gate-baseline.md`, section 1.1 (Prikey pull request 16). It decides the digests in
`provider_resources` and in `meta_data` entries that point at a file (`resource=sha256:...`), so it does not
change; Southbound has no provider resources, but its two `meta_data` references use it.

`elements`/canonical form (frozen). The compiled XML resource is written as one line per element and per
non-blank text node. Each line is one compact JSON array (`json.dumps(..., ensure_ascii=True,
separators=(",", ":"))`): `[DEPTH,"tag",[["key","value"],...]]` for an element, with attributes sorted by
key, and `[DEPTH,"#text",[["#text","stripped text"]]]` for a text node. DEPTH is the number of parent
elements.

**Key.** An attribute whose resource ID is one the gate knows (`ATTR_IDS`) is `android:NAME` whatever its
namespace (an attribute whose name disagrees with its known ID is exit 2); an attribute in the Android
namespace with no known ID is also `android:NAME`, but exit 2 if its name is a known one with a missing or
different ID. Any other attribute is its plain `NAME` when it has no namespace, and `{namespace URI}NAME` in
any other namespace, so a decoy in a foreign namespace can't stand in for the plain attribute.

**Value.** The attribute's raw string when it has one, for any type. With no raw string: a boolean is `true`
or `false`; an integer, decimal or hex typed, is its unsigned decimal value; a resource reference is JSON
`null`; anything else is `(type 0xTT)0xDDDDDDDD` (type and data, lower-case hex, two and eight digits). JSON
escaping keeps quotes and newlines inside the value, so no value can spell out another attribute or element.

**Refused (exit 2)** in every compiled XML the gate decodes (manifest, network security config, provider
resources): an element with two attributes that read as the same key; a string attribute with no raw copy,
or whose raw and typed copies differ; a resource reference that also carries a raw string (FileProvider and
the network security config read the raw string through getAttributeValue, PackageParser the typed value).
The string pool's order, the compiler's version and attribute order do not matter.

`sha256` is the SHA-256 of the lines joined by `\n` (UTF-8). If a resource has several configuration
variants, identical ones count once, the lines of each variant follow one another sorted by (kind, lines),
and variants are separated by a line `---`. A file that is not compiled XML contributes one line `raw sha256
<hex>`. The file's name is not recorded. In `meta_data`, a reference that resolves to a file is
`NAME;resource=sha256:<that digest>`.

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
