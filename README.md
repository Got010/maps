# Southbound

A privacy-first, offline Android maps app for New York State. It installs only
from this repo's GitHub Releases. Plan and rules: [HANDOFF.md](HANDOFF.md) and
[CLAUDE.md](CLAUDE.md).

**Status: Phase 3.** Offline maps for all of New York State in five regional
packs, statewide place and address search, drive, walk and bike directions
with spoken turn-by-turn guidance, and NYC subway, bus, LIRR and Metro-North
trip planning, all on the phone. Live transit times, 511NY incidents (with
routes steering around closures) and NYC DOT traffic speeds are opt-in extras.

## Install

Everything below works from a phone. No computer needed.

Every merge to `main` publishes a new release automatically, with the APK, the
data packs and `southbound.json` (a manifest of every file's size and
SHA-256). Download `southbound-<version>.apk` from the latest release and open
it to install. That's the only install you do.

### Updates, from inside the app

Southbound keeps itself current from its own releases (Settings > Updates):

- **Map data** (on by default): pick your regions on the first screen (or in
  Settings > Updates). On first start, and then daily, Southbound reads the
  latest release's manifest and downloads a map for each ticked region plus
  the statewide search and routing data and NYC transit, fetching only the
  packs that changed, on Wi-Fi unless you allow mobile data. Each download is checked
  against its published SHA-256 before it replaces the old pack, and the map
  switches to it straight away. **Download now** on the first screen fetches
  everything at once on any network.
- **App updates** (off until you turn them on): a newer APK is downloaded,
  checked to be Southbound, newer, signed with the same key and matching its
  checksum, then handed to Android's installer. Android asks you to allow
  "Install unknown apps" for Southbound and to confirm the first update. After
  that Southbound is its own installer, so later updates install without a
  prompt (Android 12+).
- **GitHub token**: while this repository is private, GitHub only shows its
  releases to a token. Create a fine-grained personal access token (GitHub >
  Settings > Developer settings > Fine-grained tokens) with access to
  `StrongHorse44/maps` and the read-only **Contents** permission, and paste it
  into Settings > Updates. It's stored encrypted and sent only to
  `api.github.com`. Making the repository public removes this step.

Requests go to GitHub only and carry nothing about you: no location, search or
route. With the Network permission revoked the app keeps working from the
packs it has.

## Load the map by hand

The app downloads its data by itself (see above). You can also load packs by
hand, for example without a token. Every release carries these files next to
the APK:

| File | What it adds |
| --- | --- |
| `basemap-<region>.pmtiles` | The map itself, one file per region: `nyc` (New York City), `li` (Long Island), `hudson` (Hudson Valley and Catskills), `capital` (Capital Region, Adirondacks and North Country), `western` (Central and Western NY). Install any number of them. |
| `search-nys.sqlite` | Search for places, streets and addresses, whole state |
| `routing-nys.tar` | Directions and turn-by-turn navigation, whole state |
| `transit-nyc.sqlite` | Subway, bus, LIRR and Metro-North schedules |

Load each one the same way:

1. On GitHub, open the repo's **Releases** and the latest release.
2. Download the file from it in your phone's browser. Don't open it with a
   file manager, which may try to unpack it.
3. Get it into Southbound, either way:
   - In the browser's downloads, share the file and pick **Southbound**. This
     works with browsers such as DuckDuckGo that keep downloads to themselves.
   - Or, for a basemap, open Southbound, tap **Import map file** and pick
     the download.

   The app copies the file into its private storage, so you can delete the
   download afterwards.

Southbound recognises each file by its contents, so a renamed download such as
`routing-nys (1).tar` still works. Packs are rebuilt when the pipeline changes
and on the 2nd of every month, from fresh OpenStreetMap and MTA data; other
releases carry the previous packs over. To swap in newer data, share the new
file to Southbound the same way; it replaces the old one.

## Live traffic

Settings > Live data has a toggle for 511NY incidents and closures (needs a
free developer key from 511ny.org, stored encrypted on the phone) and one for
NYC DOT traffic speeds (no key). With either on, a traffic button appears on
the map. Both feeds are fetched whole for the state or city, so the requests
say nothing about where you are. Directions steer around 511NY closures, and
a closure that appears on your route while navigating is spoken and routed
around.
