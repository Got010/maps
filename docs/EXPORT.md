# Export format (bundle schema 1)

Settings > Backup > Export writes one file with everything Southbound keeps for you; Import reads it back. The file
is an FWX v1 container (the fieldwork program's `specs/export-container.md`): a plaintext header, then AES-256-GCM
chunks under a key derived from the passphrase (PBKDF2-HMAC-SHA256, 600,000 iterations, then HKDF). The codec in
`core/export/src/main/kotlin/app/southbound/core/export/fwx/` is a verbatim copy of the program's codec 1.2.0 (only
its package line and one provenance line differ; `CodecCopyTest` fails on any other edit, and checks the hashes
against `core/export/SOURCES.sha256`), with its tests 1.2.0-r1 and the shared vectors in
`core/export/src/test/resources/fwx-v1/`. Never edit those files here: a fix goes to the program's codec first.

- **App ID** `southbound`, **schema** 1. The importer accepts schema 1 only.
- **File name** `southbound-<yyyyMMdd-HHmmss>Z.fwx` (UTC). The header (app, schema, time) is readable without the
  passphrase; nothing else is, and the file length shows roughly how much it holds.
- **Never in it**: the Keystore keys, the map and search packs, the fetched gas price (a cache), the downloaded voice.
- **Export writes only to the document the system file picker returned** (no storage permission, no other file).

## Entries

Each entry is UTF-8 JSON, written in this order; all four are always present.

| Entry | Holds |
|---|---|
| `manifest.json` | `{"app":"southbound","schema":1,"app_version":"0.1.14","lists":n,"saved_places":n,"recents":n,"home":0\|1,"work":0\|1,"api_keys":n,"settings":n}`. Always first. The importer checks every count against what the other entries hold. |
| `places.json` | The saved places file's own JSON (`UserDataCodec`, `"version":1`): `lastId`, `lists` (`id`, `name`, `builtIn`), `saved` (`id`, `list`, `savedAt`, `place`), `recents` (`seenAt`, `place`), `home`, `work`. A `place` is `name`, `lat`, `lon`, `address`, `category`, `osmRef`. |
| `api-keys.json` | `{"<ApiKey name>":"<key>"}` for the keys that are set: `MTA_BUS_TIME`, `NY_511`, `GITHUB_RELEASES`, `EIA`. The keys are in the clear inside the encrypted bundle: they are secrets, and the passphrase is what protects them. |
| `settings.json` | Seven groups, every field required, nothing else allowed: `network` (the 8 `NetworkFeature` toggles by name), `route` (the 15 `RoutePrefs` fields), `driving` (`night_by_sunset`), `fuel` (`mpg`, `price_per_gallon`, `price_source`, `region`), `voice` (the 24 `VoiceSettings` fields; enums by name), `packs` (`regions`: the wanted basemap region ids), `updates` (`allow_mobile_data`). |

## Import rules

- The header's app ID and schema are checked before the passphrase is asked for (a bundle from another app is
  refused by name), and the dialog shows the file's date as "not yet verified".
- Import is **replace**, not merge: saved places, lists, recents, Home, Work, API keys and preferences become exactly
  what the file holds. The dialog says so before the file and the passphrase are asked for.
- The whole file is verified first (header MAC, every chunk, the end record) and its contents are checked in memory.
  Every entry is read strictly: exactly the fields schema 1 lists (an unknown or missing field, a value of the wrong
  type, a fractional or negative number where a whole one belongs, `version` other than 1 are all refused, never
  defaulted or dropped), and a key named twice in one object is refused (the JSON reader is asked to reject
  duplicates for these entries only; the app's own readers keep their behaviour). Then: enums by name, numbers finite and inside the range the app's own setting enforces,
  strings within their bounds (place name 2,048 characters, address 4,096, category 256, OSM reference 64, list
  name 1,024, API key 1,024; at most 500 lists, 40,000 saved places, 50 recents), unique ids, every saved place in
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
  nothing unreadable would be overwritten, as a fresh import does.
- **The summary** after an import (and the "will turn on" line of a kept one) names every network feature the import
  switched from off to on, or says "none". Features already on are not listed. If a kept import is finished after a
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

A new entry, a new `ApiKey` or `NetworkFeature`, a new setting, or any change to an entry's fields bumps the schema to
2; the importer then declares the range it supports and needs a tested migration for schema 1. `SouthboundBundleTest`'s
"schema 1 pins what the stores hold" fails when one of those changes without the schema.

## Checking an export from Termux

`python3 fwx.py --list <file>` (the program's `tools/fwx.py`; Termux needs `pkg install python python-cryptography`)
asks for the passphrase, verifies the file and prints one line per entry. For this bundle: `manifest.json`,
`places.json`, `api-keys.json`, `settings.json` and `OK 4 entries`. Only ever `--list`; `--extract` writes the
plaintext (your places and API keys) to storage.
