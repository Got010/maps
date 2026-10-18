# Export format (bundle schema 9)

Settings > Backup > Export writes one file with everything Southbound keeps for you; Import reads it back. The file
is an FWX v1 container (the fieldwork program's `specs/export-container.md`): a plaintext header, then AES-256-GCM
chunks under a key derived from the passphrase (PBKDF2-HMAC-SHA256, 600,000 iterations, then HKDF). The codec in
`core/export/src/main/kotlin/app/southbound/core/export/fwx/` is a verbatim copy of the program's codec 1.2.0 (only
its package line and one provenance line differ; `CodecCopyTest` fails on any other edit, and checks the hashes
against `core/export/SOURCES.sha256`), with its tests 1.2.0-r1 and the shared vectors in
`core/export/src/test/resources/fwx-v1/`. Never edit those files here: a fix goes to the program's codec first.

- **App ID** `southbound`, **schema** 9. The importer accepts schemas 1 to 9 (see "Schema history").
- **File name** `southbound-<yyyyMMdd-HHmmss>Z.fwx` (UTC). The header (app, schema, time) is readable without the
  passphrase; nothing else is, and the file length shows roughly how much it holds.
- **Never in it**: the Keystore keys, the map and search packs, the fetched gas price (a cache), the downloaded voice.
- **Export writes only to the document the system file picker returned** (no storage permission, no other file).

## Entries

Each entry is UTF-8 JSON, written in this order; all four are always present.

| Entry | Holds |
|---|---|
| `manifest.json` | `{"app":"southbound","schema":9,"app_version":"0.1.14","lists":n,"saved_places":n,"recents":n,"home":0\|1,"work":0\|1,"api_keys":n,"settings":n,"wifi_marks":n}`. Always first. `wifi_marks` (schema 9) counts the Wi-Fi marks; a schema 1 to 8 manifest has no such field. The importer checks every count against what the other entries hold. |
| `places.json` | The saved places file's own JSON (`UserDataCodec`, `"version":1`): `lastId`, `lists` (`id`, `name`, `builtIn`), `saved` (`id`, `list`, `savedAt`, `place`), `recents` (`seenAt`, `place`), `home`, `work`, and from schema 9 `wifiMarks` (a list of `osmRef`, `worked`, `markedAt`: the user's "Wi-Fi worked" / "Didn't work" marks on free Wi-Fi spots, one per OSM reference, always present, an empty list when there are none; a schema 1 to 8 file has no such field). A `place` is `name`, `lat`, `lon`, `address`, `category`, `osmRef`. |
| `api-keys.json` | `{"<ApiKey name>":"<key>"}` for the keys that are set: `MTA_BUS_TIME`, `NY_511`, `GITHUB_RELEASES`, `EIA`, `OHGO` (schema 6). The keys are in the clear inside the encrypted bundle: they are secrets, and the passphrase is what protects them. |
| `settings.json` | Seven groups, every field required, nothing else allowed: `network` (the 27 `NetworkFeature` toggles by name; schemas 1 and 2 had 8, without `WEATHER`, schema 3 had 9, without `TRAFFIC_511PA` and `TRAFFIC_CTROADS`, schema 4 had 11, without `TRAFFIC_CHART_MD` and `TRAFFIC_DELDOT`, schema 5 had 13, without the eleven Midwest toggles `TRAFFIC_511WI` to `TRAFFIC_OHGO`, and schemas 6 and 7 had 24, without `TRAFFIC_511VA`, `TRAFFIC_WV511` and `TRAFFIC_KYTC`), `route` (the 16 `RoutePrefs` fields; schema 1 had 15, without `avoid_dot_cameras`), `driving` (`night_by_sunset`, `search_at_bottom`; schemas 1 to 6 had `night_by_sunset` alone), `fuel` (`mpg`, `price_per_gallon`, `price_source`, `region`), `voice` (the 24 `VoiceSettings` fields; enums by name), `packs` (`regions`: the wanted basemap region ids, the user's own; a region got for a trip from the route preview is left out, and an import makes every region it lists the user's own; `wifi`, schema 9: the Settings > Maps switch for the free Wi-Fi pack; a schema 1 to 8 bundle has no such field and an import leaves the phone's switch as it is), `updates` (`allow_mobile_data`). |

## Import rules

- The header's app ID and schema are checked before the passphrase is asked for (a bundle from another app is
  refused by name), and the dialog shows the file's date as "not yet verified".
- Import is **replace**, not merge: saved places, lists, recents, Home, Work, API keys and preferences become exactly
  what the file holds. The dialog says so before the file and the passphrase are asked for.
- The whole file is verified first (header MAC, every chunk, the end record) and its contents are checked in memory.
  Every entry is read strictly: exactly the fields the file's schema lists (an unknown or missing field, a value of the wrong
  type, a fractional or negative number where a whole one belongs, `version` other than 1 are all refused, never
  defaulted or dropped), and a key named twice in one object is refused (the JSON reader is asked to reject
  duplicates for these entries only; the app's own readers keep their behaviour). Then: enums by name, numbers finite and inside the range the app's own setting enforces,
  strings within their bounds (place name 2,048 characters, address 4,096, category 256, OSM reference 64, list
  name 1,024, API key 1,024; at most 500 lists, 40,000 saved places, 50 recents, 20,000 Wi-Fi marks, each mark for a different OSM reference, a mark's time not negative), unique ids, every saved place in
  a list that exists, `lastId` past every id, both built-in lists present, regions only ones Southbound knows,
  the manifest's counts equal to what the entries hold. Entry sizes are capped (manifest 4 KiB, places 8 MiB,
  API keys 16 KiB, settings 64 KiB) before anything is allocated. Wrong app, wrong passphrase, a damaged or cut
  file and bad contents all stop here, with nothing changed.
- If the saved places file exists but cannot be read, import (and export) stop with nothing changed: the
  "never silently reset unreadable user data" rule (B03) holds. Resolve that notice first (Keep trying, or Start fresh,
  which sets the old file aside under a new name).
- **The commit point.** The checked entries are written to `noBackupFilesDir/import.pending`, sealed under this
  phone's Keystore like the saved places file (temp file, fsync, atomic rename). Before that rename nothing has
  changed. After it the import is decided, and applying it is repeatable: saved places (one atomic file replace),
  API keys (one commit), then each preference store, every one written with a synchronous commit (never a queued
  write) that throws if it fails. The kept import is deleted only after the last of them has returned, so a process
  that dies in the middle leaves the journal to finish the job. If applying stops for any reason (a full disk, the process
  ending), the file stays and the import is finished at the next start, or with Finish import in Settings >
  Backup; Discard drops it. Nothing is half-forgotten, and no plaintext is written to disk at any point. Finishing it re-checks that
  nothing unreadable would be overwritten, as a fresh import does. At start it also checks that the saved places are
  still the ones the import found or brings, by a digest of their `places.json` form; an import kept by a version
  before schema 9 recorded that digest without the `wifiMarks` field, so with no marks both forms are accepted.
- **The summary** after an import (and the "will turn on" line of a kept one) names every network feature the import
  switched from off to on, or says "none", and says so when the import also turns the free Wi-Fi switch (Settings >
  Maps) on, since that downloads the Wi-Fi pack. Its counts include the Wi-Fi marks. Features already on are not listed. If a kept import is finished after a
  partial apply, the list can omit ones that had already been switched on.
- Passphrases: the writer requires at least 12 characters, typed twice; readers accept any. The passphrase is never
  stored, logged or put in saved state, and the key buffers are zeroed after use (the Compose text field's own String
  cannot be).

## Export rules

- Export reads every part of the stores; a saved API key that cannot be decrypted fails the export instead of being
  left out.
- The entries are built, then checked by the **same** decoder import uses, and the sizes against the same limits,
  before the destination is opened, so Southbound never writes a file it would refuse to read (an over-long place name
  fails the export with the rule that was broken, never the name).
- The file is sealed in memory (ciphertext only), written to the picked document, then read back through the import
  reader with the same passphrase and compared with what was written. On any failure after the document was opened it
  is deleted; the summary shows counts only, never a place name or a key.

## Changing the format

A new entry, a new `ApiKey` or `NetworkFeature`, a new setting, or any change to an entry's fields bumps the schema;
the importer then declares the range it supports and needs a tested migration for every older schema.
`SouthboundBundleTest`'s "schema 9 pins what the stores hold" fails when one of those changes without the schema.

### Schema history

- **1** (v0.1.14): the four entries above.
- **2** (October 2026): `settings.json`'s `route` group gained `avoid_dot_cameras` ("Avoid DOT cameras"). The header,
  the manifest and the settings carry the same schema; a schema 1 bundle is read with schema 1's fields exactly and
  imports with `avoid_dot_cameras` off.
- **3** (October 2026): `settings.json`'s `network` group gained `WEATHER` (Settings > Live data > Weather). A schema 1
  or 2 bundle is read with its own fields exactly and imports with weather off.
- **4** (October 2026): the `network` group gained `TRAFFIC_511PA` and `TRAFFIC_CTROADS` (Settings > Live data >
  Incidents and closures in Pennsylvania, in Connecticut). A schema 1 to 3 bundle is read with its own fields exactly
  and imports with both off.
- **5** (October 2026): the `network` group gained `TRAFFIC_CHART_MD` and `TRAFFIC_DELDOT` (Settings > Live data >
  Incidents and closures in Maryland, in Delaware). A schema 1 to 4 bundle is read with its own fields exactly and
  imports with both off.
- **6** (October 2026): the `network` group gained the eleven Midwest toggles `TRAFFIC_511WI`, `TRAFFIC_TRAVEL_MIDWEST`,
  `TRAFFIC_NDDOT`, `TRAFFIC_SD511`, `TRAFFIC_511IN`, `TRAFFIC_511MN`, `TRAFFIC_511IA`, `TRAFFIC_511NE`,
  `TRAFFIC_KANDRIVE`, `TRAFFIC_MIDRIVE` and `TRAFFIC_OHGO` (Settings > Live data > Incidents and closures in
  Wisconsin, Illinois, North Dakota, South Dakota, Indiana, Minnesota, Iowa, Nebraska, Kansas, Michigan, Ohio), and
  `api-keys.json` the `OHGO` key. A schema 1 to 5 bundle is read with its own fields exactly and imports with all
  eleven off.
- **7** (October 2026): the `driving` group gained `search_at_bottom` (Settings > Driving > Search bar at the bottom,
  `docs/bottom-search.md`). A schema 1 to 6 bundle is read with its own fields exactly and imports with it on, the
  app's default.
- **8** (October 2026): the `network` group gained `TRAFFIC_511VA`, `TRAFFIC_WV511` and `TRAFFIC_KYTC` (Settings >
  Live data > Incidents and closures in Virginia, West Virginia, Kentucky). A schema 1 to 7 bundle is read with its
  own fields exactly and imports with all three off.
- **9** (October 2026): `places.json` gained `wifiMarks`, `settings.json`'s `packs` group gained `wifi`, and the manifest
  gained `wifi_marks` (the free Wi-Fi layer: the user's local "Wi-Fi worked / Didn't work" marks, and the Settings > Maps
  switch for the Wi-Fi pack). A schema 1 to 8 bundle is read with its own fields exactly (no `wifiMarks`, no `wifi`, no
  `wifi_marks`; a schema 9 field in an older bundle is refused) and imports with no marks and leaves the Free Wi-Fi
  switch as it is on the phone (a schema 9 bundle restores the switch exactly, on or off); the import replaces the marks
  like everything else. Exports are written as schema 9. The marks also live in the saved
  places file (`UserDataCodec`, still `"version":1`: a file without `wifiMarks` opens with none).

## Checking an export from Termux

`python3 fwx.py --list <file>` (the program's `tools/fwx.py`; Termux needs `pkg install python python-cryptography`)
asks for the passphrase, verifies the file and prints one line per entry. For this bundle: `manifest.json`,
`places.json`, `api-keys.json`, `settings.json` and `OK 4 entries`. Only ever `--list`; `--extract` writes the
plaintext (your places and API keys) to storage.
