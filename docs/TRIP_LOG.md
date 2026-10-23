# Trip log

Mileage per leg for a reimbursement claim: the miles to paste into Lumen's Share Summary (its typed mileage line) or a
claim form. Offline, no pack, no network, no request, no setting. Card OQ352 (a): distance and date per planned leg only,
no trail, no coordinates, no time beyond the day, sealed with the saved places, shared as CSV.

## What the owner sees

- **Log this leg.** In the route preview (driving, walking or cycling; not transit), under the route options and above
  Start. A leg is one planned route from A to B, logged only by this tap: Start, a reroute, Pause and Resume never touch
  the log, so a resumed trip cannot log twice. A multi-stop route is one leg; to claim each stop, plan each hop. A route
  from a start picked on the map (Start here on a place) can be logged too: that is how a past drive is logged, with
  both ends picked and the date set on the sheet.
- **The leg sheet.** Trip (the trips already used as chips, the last one preselected), date (today; the arrows move it
  a day), miles (the selected route option's distance, prefilled; a change marks the leg "edited"), mode, From and To.
  From and To are never filled from the map or a pack: you type them or tap a chip. The chips are labels already in the
  log, newest first, at most 8; for From the trip's previous To comes first; for To, **What I searched: <text>** appears
  only when the destination is the place you just picked from a typed search (see Privacy). Autocorrect is off. The
  trip (40 characters) and From and To (80) stop at their caps as you type, with a counter from three quarters of the
  way, so what is saved is what is on screen, never cut. Retyping the planned miles another way ("212.40") is not a
  change. Save writes the leg and the preview's row then reads "Logged to <trip> · 212.4 mi" with Edit. A field out of
  bounds keeps the sheet open with a sentence that says which.
- **Map options > Trip log** (the last row; not during guidance). The trips, newest first, each with its total miles and
  kilometres, the number of legs and the day range; under a trip its legs by day (date, mode, miles, "edited", From to
  To), each with Edit and Delete. Per trip: Share CSV, Share mileage line, Save CSV file, Rename (into an existing name,
  the two trips become one) and Delete. Deletes ask first. The list scrolls, in landscape too. An empty log says how to
  log a leg.
- **Share CSV / Share mileage line** open Android's share sheet with the text only (no file, no link). Its Copy puts the
  mileage line on the clipboard for Lumen's Share Summary. Share CSV is offered only while the trip's CSV is at most
  64 KiB (about a thousand typical legs); a longer trip says "Too long to share as text" and points to Save CSV file. If
  the share sheet can't open, the log says so and nothing is sent.
- **Save CSV file** asks where to save `southbound-legs-<yyyyMMdd>.csv` with the system file picker, writes the file
  there, reads it back, and removes it if anything went wrong. While the picker is up only the trip's name is kept, in
  saved state, so the file is written even if Android stopped Southbound meanwhile: the rows are rebuilt from the log
  when the picker answers. If the trip is no longer in the log then, the empty file is removed and the log says so.

## The record

A leg is exactly: `id` (from the saved places' id counter, never reused: an order, not a time), `trip` (1-40 characters,
one line: Lumen's trip bounds, so the names match), `on` (`YYYY-MM-DD`, a real day), `meters` (whole, 1 to 10,000,000),
`mode` (DRIVE, WALK, BIKE), `from` and `to` (1-80 characters, one line) and `edited` (true once the distance was changed
by hand). One rule, `Leg.problem()`, for the sheet, the export's check and the import. At most 5,000 legs; the 5,001st is
refused with a sentence.

**Never** a coordinate, a place reference, an OSM reference, a map or pack place name, a road, an address, the route's
line, a time of day, a duration or the GPS trail. `UserDataTest` pins the eight fields of a leg's JSON.

## The CSV and the mileage line

`trip,date,from,to,mode,miles,km,source`, one row per leg by day, CRLF line ends, no total row. Miles and kilometres to
one decimal from the whole metres; `source` is `planned` or `edited`; RFC 4180 quoting; a text field starting with `=`,
`+`, `-`, `@` or a tab gets a leading `'` so a spreadsheet never runs it as a formula.

The mileage line: `412.3 mi (663.6 km), 5 legs, 2026-10-24 to 2026-10-30`, at most 60 characters (Lumen cuts its typed
mileage line there): past that the kilometres go, then the day range. Its miles are the sum of the CSV rows' miles as
printed, so the line and the file agree.

## Storage and export

The legs live in the saved places file, `noBackupFilesDir/places.sealed`, the document `KeystoreSealer` already seals
(AES-256-GCM under the Keystore key `southbound-user-data`; written to a temp file, fsynced, renamed). No new file, key,
preference, database or cache. The document stays `"version":1`: a file written before the trip log has no `legs` and
loads with none. While that file is unreadable every leg change is refused and counted, as any explicit save is.

Settings > Backup > Export carries the legs (bundle schema 12, `EXPORT.md`). Importing a schema 12 file replaces the log
exactly; importing an older file never empties it: the saved places are replaced as before and the trip log on the
phone is kept.

## Privacy

What is kept is a day, a distance and your own labels for each leg you chose to log; never where the phone went. The
labels are yours. **What I searched** is the one chip that carries text from elsewhere in the app: the query you typed in
Search, held in memory only from the pick until the route preview closes or guidance starts, and offered only for the
place it found. Southbound's copy for the chip is never in saved state; Search's own field keeps what you typed in the
screen's saved state while Search shows it, as it did before the trip log. Search text usually names the place, so once saved in a leg it is a place name in your log, sealed
like a saved place's name, leaving the phone only in a CSV you share or your encrypted export, and never more precise
than what you typed. The sheet says this once, under the chip.

## Code

- `core/userdata`: `Legs.kt` (`Leg`, `Leg.problem`, `Leg.clean`, `LegLog`, `Distance`), `LegCsv.kt`; `UserData.addLeg`,
  `editLeg`, `deleteLeg`, `renameTrip`, `deleteTrip`; `UserDataStore`'s five calls. Tests: `LegLogTest`, `LegCsvTest`,
  `UserDataTest`.
- `core/export`: `PlacesEntry` reads and checks `legs`, `BackupData.placesFor` keeps the phone's legs for an older
  bundle. Tests: `SouthboundBundleTest`, `BackupServiceTest`.
- `feature/explore`: `LegDraft` (the sheet's rules, `LegDraftTest`), `LegSheet.kt`, `TripLogSheet.kt`, `LegShare.kt`;
  `ExploreMap` (the row in the preview, the sheets, the searched text), `MapOptions` (the Trip log row).
- `feature/search`: `SearchPanel`'s `onSearched` reports the typed query of a pick.
