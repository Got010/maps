# Southbound

A privacy-first, offline Android maps app for New York State. It installs only
from this repo's GitHub Releases. Plan and rules: [HANDOFF.md](HANDOFF.md) and
[CLAUDE.md](CLAUDE.md).

**Status: Phase 3.** Offline maps for all of New York State in five regional
packs, statewide place and address search, drive, walk and bike directions
with spoken turn-by-turn guidance, and NYC subway, bus, LIRR and Metro-North
trip planning, all on the phone, plus saved places and recents kept encrypted
on the phone, speed limits while navigating and 3D buildings. Live transit
times, 511NY incidents (with routes steering around closures) and NYC DOT
traffic speeds are opt-in extras.

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
| `voice-norman.zip` | An extra built-in voice (about 58 MB), only if you pick it in Settings > Voice |

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

## Driving

Save a place as Home or Work from its card and both appear as one-tap chips in
the search box. In route preview, Options avoids tolls, highways and ferries
and switches to a truck or RV profile with your vehicle's size and weight;
Add stop builds a trip with several stops, which you can reorder, remove or
skip while driving. The navigation screen shows the road you're on and your
arrival time, and after sunset the map and screen go dark (Settings > Driving).
If a route crosses a region whose map isn't installed, the preview says so and
offers to download it.

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
