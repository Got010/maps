# Android Auto and the landscape driving layout

HANDOFF.md Phase 8 asks for "Android Auto (car app library, no Play Services) or, failing that, a
landscape driving layout". Both are built. This page says what each needs, what was found along
the way, how to test them from a phone, and what is still open.

## The short version

| | Needs Google software on the phone? | Works without it? |
|---|---|---|
| Landscape driving layout (rotate the phone on a dash mount) | No | Yes, always |
| Android Auto car screen (`:feature:car`) | **Yes, Google's Android Auto app** (which itself needs Google Play Services) | The app never depends on it; without Android Auto the car module is never bound and does nothing |

**The decision for the owner.** The Car App Library (`androidx.car.app`) is an AndroidX library and
our code adds no Google Play Services, Firebase or analytics dependency. But a Car App Library app
can only appear on a car screen through the *Android Auto* app (or a car that runs Android
Automotive with its own host). Android Auto is Google's app and depends on Google Play Services
being installed on the phone. On a phone with no Google software, including a GrapheneOS phone
without sandboxed Google Play, Android Auto can't run, so the car screen can't be used. The phone's
landscape layout is the offline answer. Nothing in Southbound itself changes either way.

## What was built

### Landscape driving layout (`:feature:navigation`, `:feature:explore`)

- `NavigationLandscape.kt`: when the window is wider than tall and at least 560 dp wide, the trip
  overlay is a left column 40% of the width. Top: Ferrostar's instruction banner (one type size
  down), the highway sign, the road name and the faster-route offer. Bottom: arrival time, Skip
  stop, trip progress. Over the map on the right: speed limit (top-left), voice button
  (bottom-right), rerouting notice (top-centre).
- `NavigationOverlay` only chooses: `if (rememberDrivingLandscape()) NavigationLandscape(...)`.
  Portrait is unchanged. Six composables went from `private` to `internal` so the landscape file
  can reuse them (`HighwaySign`, `SpeedLimitSign`, `VoiceButton`, `FasterRouteBanner`, `EtaLine`,
  `ArrivedCard`).
- `ExploreMap` insets the map by the column's width during a landscape trip (a `padding(start = ...)`
  on `SouthboundMap`) instead of letting the column cover it. That is why no left-padding hook was
  added to `core/map`: the map view itself starts at 40%, so MapLibre's compass, attribution button
  and puck all stay visible, and `navigationTopPadding` (vertical only) is untouched. The puck
  sits in the lower third of the map area, centred horizontally in it. The system-bar padding passed
  as `contentPadding` drops its left inset in this mode because the column covers that edge.
- Rotation keeps the Activity (`configChanges="orientation|screenSize|..."` was already set), so a
  trip is not interrupted; the layout switches live.

Known gaps: the tap-to-read incident and camera cards are still centred over the whole screen in
a trip (they were left alone to avoid touching lines the cameras work edits), so in landscape they
can cover part of the column for as long as they are open. The compass keeps its 120 dp "below the
banner" offset in landscape, where it could sit higher.

### Android Auto (`:feature:car`)

- `SouthboundCarAppService` (`CarAppService`, NAVIGATION category), `SouthboundCarSession`.
- `TripScreen`: a `NavigationTemplate` with the current turn and its distance, the next turn, the
  arrival time, time and distance left, and a Stop action; a `MessageTemplate` ("Start a trip on
  your phone") when there is no trip and for arrival. A loading routing-info shows while
  rerouting. Distances use the phone's US units and rounding. Turn arrows are our own vector
  drawables (`car_arrow_*`, one arrow rotated per direction, plus a U-turn and a pin); the
  host's maneuver type is also set, so a host that draws its own icons can.
- `TripReporter`: sets the `NavigationManager` callback, calls `navigationStarted()`,
  `updateTrip(Trip)` (next step, destination, estimates, current road) and `navigationEnded()`.
  The host's "stop navigation" ends the trip on the phone.
- Trip state: `core/routing` `TripBroadcast` holds a `StateFlow<TripSnapshot?>` of plain values
  (instruction, manoeuvre type and modifier names, distances, times, rerouting, arrived).
  `ExploreServices.openNavigator` makes it follow the navigator's state for the whole process,
  so the car works whether or not the Activity exists, and gives it the End action. Ferrostar's
  foreground service is what keeps GPS and the trip alive; the Car App Library requires a
  foreground service while a navigation app is active, and this is it.
- Manifest (in `:feature:car`, merged into the app): `androidx.car.app.NAVIGATION_TEMPLATES`
  permission, the `<service>` with `androidx.car.app.category.NAVIGATION`, `minCarApiLevel` 1, and
  the `com.google.android.gms.car.application` meta-data pointing at `res/xml/automotive_app_desc.xml`
  (`<uses name="template" />`). That meta-data *name* is a string Android Auto's host looks for. It
  is not a library, and `build-logic` bans `com.google.android.gms` only as a dependency group.
- Host validation: debug builds accept any host (Desktop Head Unit); release builds use the
  library's `hosts_allowlist_sample` (Android Auto and the Android Automotive emulators).

Starting a trip, searching and choosing a destination are not in the car app. The car mirrors a
trip started on the phone. That keeps every network and search rule exactly where it was.

### Not built: the map on the car screen

Drawing the map needs the surface path: `ACCESS_SURFACE` permission, car API level 2,
`AppManager.setSurfaceCallback`, then rendering MapLibre into the `Surface` the host gives
(`SurfaceContainer`), usually through a `VirtualDisplay` + `Presentation` hosting a `MapView`, with
the style, packs and the puck from the same sources as the phone. That is a self-contained
project with real risk (MapLibre on a virtual display, lifecycle across the two screens, power),
and it can't be built or checked without a device and a car or the Desktop Head Unit. The
navigation template works without a surface: the host shows the turn card and arrival over its
background. Follow-up: a `SurfaceRenderer` in `:feature:car` that owns a second `MapView`, fed by
`TripBroadcast` (it would need the route geometry and location added to `TripSnapshot`).

## Testing

Landscape (phone only, no extra setup):

1. Install the release build, start a trip, rotate the phone.
2. Check: left column shows the turn, distance, road, arrival time; map fills the right 60% with
   the fin in the lower third and the compass at the top-right; speed limit sign top-left of the
   map, voice button bottom-right; Skip stop on a multi-stop trip; rerouting notice when you
   leave the route; the arrival card at the bottom of the column; Re-center after panning the map
   appears at the bottom of the *map* area. Rotate back: portrait is as before and the trip
   never restarted.
3. Try a short window height (a small phone, gesture bar) with a highway exit sign: the road
   name is cut off at the bottom of the column before anything overlaps the arrival time.

Car, on a phone with Android Auto:

1. Open Android Auto's settings, tap "Version" ten times to enable developer mode, open
   *Developer settings* from the menu and turn on **Unknown sources** (Southbound comes from
   GitHub, not the Play Store; Android Auto hides such apps otherwise).
2. Connect to a car (USB or wireless). Southbound should appear in the launcher's navigation
   section. Opening it shows "Start a trip on your phone".
3. Start a trip on the phone: the car screen shows the turn and distance, the next turn and the
   arrival, updating about every 50 ft or minute (not every second, on purpose). Tap Stop: the
   trip ends on the phone. End the trip on the phone: the car returns to the message.
4. Check the car's cluster or notification area shows the next turn (`NavigationManager`).

The Desktop Head Unit emulator needs the Android SDK (`platform-tools` plus the DHU from the SDK
manager) and a computer, so it is not part of the phone-only workflow. If one is ever at hand:
enable *Start head unit server* in Android Auto's developer settings, `adb forward tcp:5277 tcp:5277`,
run `desktop-head-unit`. A debug build is needed there (the release host check is stricter).

## Risks and what is unverified

- **Nothing here was compiled or run.** The environment had no Android SDK, and Google Maven is
  blocked, so `androidx.car.app:app:1.4.0` could not be resolved. CI is the first compile. Likely
  trouble spots, in order: Ferrostar field names used by `TripBroadcast.toTripSnapshot`
  (`VisualInstructionContent.maneuverType` / `maneuverModifier`, `RouteStep.visualInstructions`);
  `Maneuver.TYPE_*` constant names; `androidx.car.app.R.array.hosts_allowlist_sample`;
  `IconCompat` (androidx.core) being on the car module's compile classpath through the library;
  Compose layout details in `NavigationLandscape.kt`.
- **Banned dependencies.** The library should not pull in anything disallowed (its dependencies
  are AndroidX: annotation, core, lifecycle, concurrent-futures). If it ever did,
  `build-logic`'s `banTrackingDependencies` fails the build with the group named, so a bad
  version bump can't slip through.
- **Version.** 1.4.0 (stable since late 2023) was chosen because it is the version I can name with
  confidence. A newer stable exists and can be dropped into `gradle/libs.versions.toml`
  (`carApp`); nothing used here needs more than car API level 1.
- **Template rules.** The host enforces limits at runtime (action strip contents, template refresh
  rate, step and destination counts). Violations show as an error screen on the car, not as a
  build error. The Stop action carries only a title; some hosts want an icon on navigation
  action strips.
- **Release host check.** The allowlist is the library's sample list. If Android Auto's signing
  changes or a head unit isn't on it, the car shows a connection failure; the fix is in
  `SouthboundCarAppService.createHostValidator`.
- **R8.** The release build is minified. The library ships consumer rules, and the service is
  referenced from the manifest, so no extra keep rules were added. Check the release build in a
  car once.
- **Arrow icons.** One rotated arrow per direction, not bent turn glyphs, and white (made for a
  dark card). They are readable but plain; art can replace the drawables without code changes.
- **Duplicated formatting.** `CarDistance` repeats `Units.distance`'s rounding so `:feature:car`
  needn't depend on the MapLibre-carrying `:core:map`.
