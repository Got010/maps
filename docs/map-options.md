# Map options (B32e)

One floating button on the browse screen's map, bottom right, in place of the separate Weather, Free Wi-Fi,
Traffic and My location buttons and the Re-center pill. It is a layers icon with the label "Map options".

A tap opens a short list above it, one pill per control, top to bottom:

| Row | Shown when | Tap | Long press |
|---|---|---|---|
| Re-center ("My location" while the map follows the puck) | there is a location fix | follows the puck again, closes the list | |
| Traffic | the Live-data traffic toggle is on | shows or hides the layer; the list stays open | |
| Weather | the Live-data weather toggle is on | shows or hides the layer; the list stays open | |
| Free Wi-Fi | the Free Wi-Fi pack is installed | shows or hides the layer; the list stays open | opens "Show on the map" (the types chooser) and closes the list |

The list and its scrim are drawn above pin cards and traffic notes. A tap outside the list or the back gesture closes it; rotating the phone keeps it open. The button has a small dot
while any layer is showing: the primary colour, or gold (the Wi-Fi gold) when the Wi-Fi layer is showing with a type
filter active. The
Free Wi-Fi row has its own gold dot while a filter is active.

"Show on the map" applies on any way out: Done, a swipe down, a tap outside or Back all set the filter and turn the
Free Wi-Fi layer on, as long as the choices show something. When the choices show nothing (Done is disabled), closing it changes
nothing. Reset ticks everything again.

New controls for the map go into this list, not onto the map.

Code: `feature/explore` `MapOptions.kt` (the composables), `MapOptionsState.kt` (which rows, which dot; unit-tested).
The trip screen has no options button; it keeps the Re-center pill (`RecenterPill.kt`) and the automatic re-center
after 10 seconds without a touch. View state only: nothing is stored or exported.
