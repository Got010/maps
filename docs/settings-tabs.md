# Settings in tabs

Owner, October 2026: "There's too many settings to scroll through. Give me suggestions on how to condense settings
tabs to be either compressed unless opened or other options." The options were offered with previews, and the
owner picked: tabs across the top; traffic by state, the phone's states first; Voice as essentials plus "More";
search, summary lines and reopening on the last tab. On the look, the owner picked grouped cards "but not rounded.
Keep cards rectangular"; choices mixed by length; icon and label tabs; and notes cut to one line with an ⓘ. Built
in October 2026, not yet run on a phone.

Before this, Settings was one page of about 90 rows: Voice (about 25), Driving, Updates and the region list, What
each state has, Live data (about 25, 17 of them traffic feeds) and Backup.

## Layout

- **Tabs:** Voice · Driving · Live · Maps · Backup, each an icon over its name, the open one underlined. Swipe or
  tap between them. Settings opens on the tab used last.
- **Cards:** rows sit in square-cornered cards (`SettingsCard`), one per group, on the page's background.
- **Closed groups** (`SettingsGroup`) are one card, or one part of a card, that opens on a tap. Each has a line
  saying what is set inside it, such as "Speed cameras, plate readers, school zones", "Automatic · Amy · chimes off",
  "2 of 13 on" or "Not set (optional)".
- **Notes** under a switch are cut to one line, with an ⓘ when there is more. Tapping the note shows the rest. A
  screen reader reads it whole either way.
- **Choices:** short ones stay segmented buttons, one tap away (Guidance, Volume, Speed limits, Detail, Distances,
  Other audio, Gas price to use). Long ones (Voice engine, Built-in voice, Play through) are one row showing the
  current value, and tapping it opens a menu. The output labels are now full: "Phone speaker", "Bluetooth (media)",
  "Bluetooth, as a call".

## The tabs

- **Voice:** Guidance, Volume, Speech rate, Street names and Test voice. Then two closed groups:
  - **Alerts:** traffic, speed limits, speed cameras, plate readers, school zones, reroutes, transit stops.
  - **More voice options:** the per-mode switches, engine, built-in voice, detail, distances, chimes, vibration,
    other audio, Play through.
- **Driving:** night mode and the search bar's place, then the fuel estimate.
- **Live:** the privacy note, then four cards:
  - **Transit:** subway and rail, then buses.
  - **Incidents and closures:**
    - Each state with its feed's switch, the states of the phone's map regions first (`Coverage.statesWithRegions`;
      Maryland's region brings Delaware and DC).
    - The rest sit under a closed **Other states** group showing how many are on. With no regions picked, every
      state is listed without the group.
    - A state with no feed (New Jersey, DC, Missouri) says why.
    - NYC DOT speeds, and the map-button hint, come last.
  - **Weather.**
  - **Fuel:** gas prices.

  A key field (Bus Time, OHGO, EIA) shows while its feed is on or while a key is saved, so a saved key can always be
  removed. The feed titles are now just the state and the feed ("Ohio (OHGO)"), under the card's "Incidents and
  closures".
- **Maps:**
  - **Updates**, with the GitHub token as a closed group inside it.
  - **Map regions.**
  - **Installed data** and **What each state has**, each a closed group.
- **Backup:** Backup, then About.

## Search

The magnifier in the title bar turns the title into a search field. Results match the start of each word typed,
in a setting's name, its group or its tab. "chime" finds both chimes, "OHGO" Ohio's switch and its key, "mpg" the
fuel economy. Each result says where it lives ("Voice › Alerts").

Picking one closes the search, turns to its tab, opens its group, then scrolls to its row and lights it for a
moment (`JumpTarget`). A row that isn't shown (a key field while its feed is off) still brings up its tab, and the
jump lets go after four seconds. Back closes the search before it closes Settings.

`SettingsIndex` lists every setting with its tab and group. `SettingsIndexTest` reads this module's sources and
fails if a `Toggle`, `Choice`, number field, key field or traffic feed is missing from it, so a new setting can't be
left out of search.

## What it doesn't change

- No setting is added, removed or renamed in storage. Nothing the backup holds changes, so the bundle schema
  stays at 7.
- The last tab is screen state, not a setting. It is kept in its own preferences file (`settings_ui`), which no
  export reads.
- Every control does what it did. Only where it sits and how it looks changed.

## To check on the phone

- Each tab, light and dark, at the largest font: the five tab labels fit, and the cards read as cards.
- Settings reopens on the tab left last, also after an import rebuilds the screen.
- A region picked or dropped moves its state's feed in or out of "Your regions" at once.
- Search for "chime", "ohgo", "mpg" and "token": each opens the right tab and group and lights its row.
- A note's ⓘ opens the whole note. The menus (engine, voice, output) show a check on the current value.
- Back from search returns to the tabs. Back again leaves Settings.
