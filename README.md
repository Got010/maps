# Southbound

A privacy-first, offline Android maps app for New York, New Jersey,
Connecticut, Pennsylvania, Maryland, Delaware, DC, the twelve Midwest
states (Ohio to Kansas and the Dakotas), Virginia, West Virginia and
Kentucky. It installs only
from this repo's GitHub Releases. Plan and rules: [HANDOFF.md](HANDOFF.md) and
[CLAUDE.md](CLAUDE.md).

**Status: Phase 3.** Offline maps, search and directions in thirty regional
packs across those states (pick the ones you want), drive, walk and bike directions
with spoken turn-by-turn guidance, and NYC subway, bus, LIRR and Metro-North
trip planning, all on the phone, plus saved places and recents kept encrypted
on the phone, speed limits while navigating and 3D buildings. Live transit
times, each state's 511 incidents, cameras and signs (with routes steering
around closures), weather and NYC DOT traffic speeds are opt-in extras.

### What each state has

Not every state publishes everything. The app says which, and why, under
Settings > What each state has (`core/packs/Coverage.kt`). In short:

- **No traffic feed:** New Jersey (511NJ refuses GitHub's servers, where the
  feeds are studied and the maps built), DC (no 511) and Missouri (its map
  publishes no feed the app can read).
- **Traffic only with your own free key:** Ohio (OHGO).
- **No avoiding DOT cameras:** New Jersey, DC, Ohio and Missouri.
- **No message signs:** Michigan, North Dakota, South Dakota and Kentucky
  (KYTC's sign layer hasn't changed since August 2025).
- **Traffic speeds and transit:** New York City only.
- **Search** outside New York has OpenStreetMap's businesses and house numbers
  alone; the gas price is the US average outside New York and the Central
  Atlantic states.

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
  latest release's manifest and downloads, for each ticked region, its map,
  search and local streets for directions, plus main roads for every state
  (the east's, the Midwest's, Virginia, West Virginia and Kentucky's, and the
  seams between them, as your regions need)
  and NYC transit, fetching only the
  packs that changed, on Wi-Fi unless you allow mobile data. Each download is checked
  against its published SHA-256 before it replaces the old pack, and the map
  switches to it straight away. **Download now** on the first screen fetches
  everything at once on any network.
- **App updates** (off until you turn them on): a newer APK is downloaded,
  checked to be Southbound, newer, signed with the same key and matching its
  checksum, then handed to Android's installer. Android asks you to allow
  "Install unknown apps" for Southbound, and every update waits for you to
  confirm it on Android's own screen (or from the "tap to install"
  notification): no new version installs without a tap.
- **GitHub token**: not needed. The repository is public (October 2026), so
  GitHub shows its releases to anyone; Obtainium and Settings > Updates work
  without one. A token pasted into Settings > Updates only lifts GitHub's limit
  of 60 requests an hour; if an old one from the private days stops working,
  remove it there.

Requests go to GitHub only and carry nothing about you: no location, search or
route. With the Network permission revoked the app keeps working from the
packs it has.

## Load the map by hand

The app downloads its data by itself (see above). You can also load packs by
hand, for example without a token. Every release carries these files next to
the APK:

| File | What it adds |
| --- | --- |
| `basemap-<region>.pmtiles` | The map itself, one file per region: `nyc` (New York City), `li` (Long Island), `hudson` (Hudson Valley and Catskills), `capital` (Capital Region, Adirondacks and North Country), `western` (Central and Western NY), `njn` (Northern New Jersey, to the Delaware River), `njs` (Southern New Jersey), `ct` (Connecticut), `pae` and `paw` (Eastern and Western Pennsylvania), `md` (Maryland, Delaware and DC). Install any number of them. |
| `search-<region>.sqlite` | Search for places, streets and addresses, one per region (New York's and northern New Jersey's with NYS Address Points and Overture places; the other states' from OpenStreetMap) |
| `search-nys.sqlite` | Search for all of New York and northern New Jersey in one file (for app versions before the per-region packs) |
| `routing-nys.tar` | Directions and turn-by-turn navigation, New York and northern New Jersey, in one file (for app versions before the four states) |
| `routing-main.tar`, `routing-<regions>.tar` | Directions in pieces: main roads everywhere, and each region's local streets; `routing-packs.json` says which regions each piece serves. The app fetches and combines these itself (Settings > Updates); they can't be loaded by hand. |
| `transit-nyc.sqlite` | Subway, bus, LIRR and Metro-North schedules |
| `voice-norman.zip` | An extra built-in voice (about 58 MB), only if you pick it in Settings > Voice |

Load each one the same way:

1. On GitHub, open the repo's **Releases** and the latest release.
2. Download the file from it in your phone's browser. Don't open it with a
   file manager, which may try to unpack it.
3. Get it into Southbound, either way:
   - In the browser's downloads, share the file and pick **Southbound**. This
     works with browsers such as DuckDuckGo that keep downloads to themselves.
     Southbound first asks **Install this pack?**, naming the kind, region, size,
     file and the app it came from; nothing changes until you tap **Install**
     (any other app could share a file too).
   - Or, for a basemap, open Southbound, tap **Import map file** and pick
     the download.

   The app copies the file into its private storage, so you can delete the
   download afterwards.

Southbound recognises each file by its contents, so a renamed download such as
`routing-nys (1).tar` still works. Packs are rebuilt when the pipeline changes
and on the 2nd of every month, from fresh OpenStreetMap and MTA data; other
releases carry the previous packs over. To swap in newer data, share the new
file to Southbound the same way; it replaces the old one.

## Back up and restore

Settings > Backup > **Export** saves your saved places, lists, recents, Home and Work, API keys and
preferences to one file, sealed with a passphrase you choose (at least 12 characters, typed twice, never
stored). **Import** puts such a file back, on this phone or a new one, replacing what is there. Pick the file with
the system file picker; Southbound asks for no storage permission. A file from another app, a wrong passphrase or
a damaged file is refused and changes nothing. Keep the file safe: anyone with the file and the passphrase has your
places and API keys. The format is in [docs/EXPORT.md](docs/EXPORT.md).

Android's own backup and phone-to-phone transfer carry nothing of Southbound's: `allowBackup` is off and
`res/xml/data_extraction_rules.xml` excludes every domain from both cloud backup and device transfer, so Export is
the only way a copy leaves.

## Driving

The search bar sits at the bottom of the map, within the thumb's reach, with the
map's banners (a paused trip, the location prompt) and chips just above it: the
start point picked with Start here, and Home and Work once you save a place as
either from its card, each one tap to directions. Searching puts the field on the
keyboard with the best match right above it. Settings > Driving > Search bar at
the bottom turns it off, putting the bar back at the top. Search looks around
you, or, once you move the map, around the middle of the map, so you can pan to
another town and search there; distances are still from you. Chips by the box
(Gas, Hotels, Food, Coffee, Groceries, Pharmacy, Parking, EV charging) list
those places within 30 miles, nearest first, without typing. Driving routes may include a **Shorter way**: a side street that
is shorter and faster than the first route, which the router's preference for
main roads would otherwise leave out (it names the street, e.g. "via Elm
Street"). In route preview, Options avoids tolls, highways and ferries
and switches to a truck or RV profile with your vehicle's size and weight;
Add stop builds a trip with several stops, which you can reorder, remove or
skip while driving. The navigation screen shows the road you're on and your
arrival time, and after sunset the map and screen go dark (Settings > Driving).
If a trip starts, stops or ends in a region that isn't on the phone, or its
route crosses one, the preview offers **Get for this trip** with the size: that
region's map, search and directions, downloaded on Wi-Fi (or mobile data, one
tap) and removed again once you're back in your own regions after the trip.
Settings > Updates marks such a region, with **Keep** to make it one of yours. Options can also steer around license plate readers
(Avoid plate readers; routes may be longer), and each driving route shows how
many plate readers and speed cameras it passes ("3 plate readers · 1 speed
camera"). Both come from the search pack on the phone, so nothing about the
trip is sent. A route that still passes a reader says so.

## Live traffic

Settings > Live data has a toggle for 511NY incidents and closures (needs a
free developer key from 511ny.org, stored encrypted on the phone) and one for
NYC DOT traffic speeds (no key). With either on, a traffic button appears on
the map. Both feeds are fetched whole for the state or city, so the requests
say nothing about where you are. Directions steer around 511NY closures, and
a closure that appears on your route while navigating is spoken and routed
around.

With the DOT feed on, route options show "X min of delay" from slow stretches
(drawn green, yellow, orange or red on the route), the arrival time turns red
with "+X min" when traffic adds more than two minutes, and "Typical: N min"
gives the time without the delay. 511NY incidents within 60 m of the route are
pinned on it; tap one to read it. Every few minutes during a trip, Southbound
looks for a route that saves 5 minutes or more and offers it in a banner.

## The map

Rotate with two fingers, tilt by dragging two fingers up or down, and zoom
with one finger by double-tapping and holding, then sliding. The compass
appears when the map isn't facing north; tap it to turn back. Panning,
rotating or tilting away from your position shows a Re-center pill; while
navigating the map re-centers by itself after 10 seconds without a touch.
Highway exits show a green sign, and the arrival card says which side of the
street the destination is on and whether parking is listed nearby.

## Voice guidance

Settings > Voice holds every voice option. Turn prompts name a landmark when
the map data has one at the turn ("Turn right after the next light", "at the
stop sign", "past the Shell gas station"), say two quick turns together
("..., then turn left"), and play a short chime first (a different one before
alerts). Speed cameras, school zones and license plate readers (Flock and
other ALPR cameras mapped in OpenStreetMap) on your route are announced about
300 m ahead, each behind its own toggle. All of it comes from the search pack
on the phone, so nothing about the drive is sent; a search pack built before
October 2026 has no landmarks, and guidance simply goes without them until
the next pack update. For phones without a text-to-speech engine the app
has its own voice, Amy; a second one, Norman, is a download (about 58 MB)
that the updater fetches only once you pick it, and Amy speaks until it
arrives. Long-press the voice button while
navigating to mute until the next turn.

## Release checks

Each release APK is compared with `gate/baseline.json`, a list of what the app
can do: permissions, hardware feature declarations, exported components, native
libraries, network types and hosts, dependencies, the bundled voice files'
digests and the signing certificate. A pull request that changes any
of it has to change that file too, so it shows up before a merge. The build
makes the APK unsigned; a separate `sign` job, which runs none of the
repository's code and is the only one that can read the signing key, signs it
only for a commit on `main`. The signing certificate is then checked against the
pinned one before a release is published, and Gradle checks every dependency
against `gradle/verification-metadata.xml`. Details are in `gate/README.md`.
