# The search bar at the bottom

Owner, October 2026: "I like the idea of the search bar being on the bottom. Work out the UI and UX on
this." This page is that design: what moves where, how search behaves with the keyboard, and what it
takes to build. Built in October 2026 as designed here, not yet run on a phone; "As built" below says where the
code differs from the sketches.

Mockups of every screen below, today's top bar beside them: <https://claude.ai/artifact/K9urGdaszF2wW8i5xgVKoc>
(private to the owner's account).

## Why

On a tall phone held in one hand, the top fifth of the screen is out of the thumb's reach. Today the one
thing every visit starts with, the search bar, sits there. Everything else people touch is already at the
bottom: the map buttons, the place card, the route sheet, Start, the paused-trip bar and the Re-center
pill. Moving search down puts the whole app within one thumb, and the map gets its top edge back.

## The map screen, idle

Everything you touch lives in one stack at the bottom, the **dock**. Bottom to top:

1. **The search pill.** Full width (at most 560 dp in landscape, left-aligned), the same look as
   today's bar: a magnifier, "Search places and addresses", and the Settings gear at its right end.
   It sits on the gesture bar's inset.
2. **A banner, only when needed:** the location prompt ("Allow precise location"), then the paused
   trip's bar ("Paused, to Joe's Pizza", End, Resume). Each moves here from where it is today.
3. **A row of chips, only those that apply:** "From: Stop & Shop" (the start point picked with
   Start here, with its X), then **Home** and **Work** when they are set. Tapping Home or Work opens
   directions straight away, as the chips inside search do today. Unset ones don't show on the map;
   they are set from inside search, as now.

Above the dock, at the right, the map's one **Map options** button stands (B32e; before it the weather, traffic,
Wi-Fi and my-location buttons stood here, and the Re-center pill floated centred above the dock; both are folded into
the button's list, see `docs/map-options.md`), lifted by the dock's height. At the top, only the map's own
things remain: the compass (top right, as now) and the read-only "Here: 54°, partly cloudy" weather chip
(top left).

```
+--------------------------------+
| Here: 54° clear            (N) |
|                                |
|            map                 |
|                                |
|                          [wx]  |
|      ( Re-center )      [tr]  |
|                          [me]  |
| [From: Stop&Shop x] [Home]     |
| [Work]                         |
| +----------------------------+ |
| | Q Search places...      ⚙ | |
| +----------------------------+ |
+--------------------------------+
```

The dock's height is measured, never assumed: the buttons, the Re-center pill, the traffic notes and
the map's attribution all sit relative to it. With a large font or the paused-trip bar showing,
everything moves up together. Today these use fixed offsets (the Re-center pill's 28, 190 and 400 dp, the
traffic notes' 96 dp), so a large font or an extra banner can make them overlap.

## Searching

Tap the pill. The pill becomes the text field in place and the keyboard rises under it, so the field
ends up directly above the keyboard, where the thumb already is. A sheet opens upward from the field and
covers the map (search needs the room; the map comes back the moment you pick or go back).

**Results grow upward from the field, the best match nearest it.** The first result is a thumb's
distance from the keys, the second just above it, and so on; scrolling up shows more. This is the one
real change in habit, and the whole point of the layout: with results top-down, the best match would sit
at the top of the screen again, out of reach. See the decisions below.

```
+--------------------------------+
| ^ more results                 |
| Joe's Pizza  Bleecker St  0.4mi|
| Joe's Pizza  Carmine St   0.3mi|
| Joe's Pizza  Broadway     0.1mi|
| +----------------------------+ |
| | <  joe's pizza           x | |
| +----------------------------+ |
| [ keyboard                   ] |
| [                            ] |
+--------------------------------+
```

With the field empty, the same order holds: **Home** and **Work** chips right above the field, the
recent places above them (most recent nearest), then the saved lists further up. "Search data isn't
installed" and "No matches" show just above the field, where the eye already is.

- **The keyboard's Search key** opens the top result, the one right above the field, and only once the
  results are for what is typed now. Pressing it mid-debounce waits for them. Today it can open the
  previous query's top hit.
- **Scrolling the results hides the keyboard**, and the field drops to the bottom edge with the list
  following it. Tapping the field brings the keyboard back.
- **Back** (the arrow in the field, or the system back gesture) hides the keyboard first, then closes
  search, the way Android's own search bars do.
- **Picking a result** closes the sheet and shows the place card at the bottom, where the dock was.
- **Adding a stop** from the route sheet opens the same search, without the Home and Work chips (as
  today), and returns to the route sheet.
- A weather search ("weather albany") keeps each town's weather at the end of its row.

## Cards and sheets

The bottom holds one thing at a time. The dock gives way to whatever is open, and comes back when it
closes:

| Open | At the bottom |
| --- | --- |
| Nothing | The dock |
| Search | The search sheet, the field on the keyboard |
| A place | The place card (as today) |
| Directions | The route sheet (as today) |
| A tapped camera, sign, incident or weather pin | Its card, in the dock's place |
| A trip | The navigation overlay (as today; no search while driving) |

A tapped pin's card replaces the dock rather than stacking above it, so the map never has two bottom
layers. Closing the card brings the dock back.

## Landscape and large screens

The dock keeps to the left, at most 560 dp wide, with the map buttons at the right edge. The search sheet
is the same width on the left and the map stays visible beside it. The driving layout during a trip is
unchanged.

## Accessibility

- TalkBack reaches the search pill first after the map: the dock is a traversal group placed ahead of the
  map buttons, so the most used control isn't the last one read.
- Every icon in the dock has a spoken label (one of the picked TalkBack fixes).
- Font scaling: the dock and everything positioned from it grow together (the measured height above).
- Reversed results are announced in order of relevance: the best match is first in the reading order,
  though it is drawn last.

## What changes in the code

All of it is in `feature/explore` and `feature/search`. No data, network, permission or pack change, so
no gate baseline change and no data rebuild.

- `ExploreMap.kt`, `Panel.Browse`: the top column becomes a `BottomDock` composable at
  `Alignment.BottomCenter` (chips row, banners, pill). Its measured height (`onSizeChanged`) replaces the
  fixed offsets of the map buttons, the Re-center pill (`bottom = 28.dp`, `PAUSED_BAR_CLEARANCE`), the
  traffic notes (`bottom = 96.dp`) and the tapped-pin cards, and goes into the map's `contentPadding` so
  the attribution clears it. `PausedTripBar`, `StartChip` and `LocationStatus` move into the dock.
  `TodayHereChip` stays at the top.
- `SearchPanel.kt`: the field moves below the list. The column is `imePadding()`ed, the list is a
  `LazyColumn(reverseLayout = true)` with `Modifier.weight(1f)`, and the chips and empty-state groups are
  ordered nearest-first. A nested-scroll connection hides the keyboard when the list is dragged. The
  Search key waits for results that match the current query.
- `SearchBar` (the pill) keeps its look; its settings gear stays at its end.
- Strings: the setting's label ("Search bar at the bottom") and content descriptions.
- `feature/settings` `DrivingSection` and the driving preferences store: the toggle; `core/export`: schema 7
  (see Decisions).

### As built

- The dock is `ExploreMap`'s `Panel.Browse` branch; its measured height (`dockHeight`) lifts the map's buttons (when
  the dock spans the width), the Re-center pill, the traffic notes and the map's attribution. From 672 dp wide the
  dock keeps to the left at 560 dp, with the buttons beside it.
- `SearchPanel(fieldAtBottom = true)` puts the field under the lists, which run `reverseLayout` so the first result
  and the latest recent sit nearest it; a drag on either list hides the keyboard; the results list goes back to
  its start when new results come in.
- The Search key in both layouts opens the top result only for what is typed now: pressed mid-debounce, it opens
  the one that comes in next (the review's search finding).
- The top layout is today's, with the start-point chip and Home and Work under the bar.
- The activity sets `windowSoftInputMode="adjustResize"`. Without it Android also panned the whole window when the
  keyboard came up, so on the phone (0.1.43) the field sat a keyboard's height above the keyboard, with the lists
  pushed up under the status bar. Settings' content pads for the keyboard itself now (`Scaffold`'s
  `contentWindowInsets` with the IME), since Android no longer pans it to a focused field.
- No screenshot tests yet: the picked screenshot-test item would give both layouts a check in CI.

## Decisions (owner, 12 October 2026)

1. **Results order: best match nearest the field**, as drawn above.
2. **Home and Work on the map**, as chips above the search pill once set.
3. **A setting for where the bar sits.** Settings > Driving gets "Search bar at the bottom", on by default.
   Off is today's layout as it is now: the bar at the top, results top-down under it, the banners and the
   start-point chip under the bar. The one change in the top layout is that Home and Work chips sit under
   the bar too, so the two layouts offer the same one-tap trips.

   The toggle is a new setting, so per CLAUDE.md the backup bundle's schema goes from 6 to 7: the
   setting joins `settings.json`'s driving group, `docs/EXPORT.md` and the schema pin test
   (`SouthboundBundleTest`) change with it, and a schema 6 bundle imports with the bar at the bottom. The
   toggle and the schema change add about half a day.

## To check on the phone, once built

- One hand, thumb only: search, pick a result, get directions, start, all without shifting grip.
- The keyboard never covers the field; the first result sits right above it.
- Large font (Settings > Display > Font size, largest): nothing overlaps at the bottom.
- The paused-trip bar, the location prompt and Start here all show in the dock and push the buttons up.
- Landscape: the dock on the left, the buttons on the right, search beside the map.
- Settings > Driving > Search bar at the bottom, off: today's layout comes back, with Home and Work under
  the bar; a backup made either way restores the choice.
