> This file describes `state-cards.sourced.json`, the sourced copy kept in this repo; the rest of the note is the program's README verbatim (it also names files not kept here).

# state-cards: data for the offline road-trip feature

Compiled 2026-10-23. Two data files plus this note.

- `state-cards.json`: 51 records (50 states + DC) keyed by two-letter postal code. Every field is one
  plain-English sentence; `sources` is an array of URLs; `as_of` is the compile date. A value containing the
  word `verify` is one the compiler could not confirm against a primary source, or where sources conflict.
- `state-outlines.geojson`: 51 polygons (50 states + DC), property `postal` only, from Natural Earth 1:10m
  simplified to about 2 km; `state-outlines-1km.geojson` and `state-outlines-50m.geojson` are labelled alternates.
- `downloads/`: the raw Natural Earth 1:10m and 1:50m files, kept separate from the generated files.

## How this was gathered, and a caveat

The session's outbound proxy allowed only GitHub (`raw.githubusercontent.com`, `api.github.com`) and the
WebSearch tool. Every direct fetch of the comparative tables was refused by the proxy (CONNECT 403,
"connect_rejected"): `en.wikipedia.org`, `www.ghsa.org`, `www.iihs.org`, `taxfoundation.org`,
`www.nhtsa.gov`, `www.ncsl.org`, `www.fhwa.dot.gov`, `www2.census.gov`, `legiscan.com`.

So the per-field values came from (a) WebSearch result summaries, which quote the pages below, and
(b) the compiler's prior knowledge of those same tables, cross-checked field by field with targeted searches
for 2025-2026 changes. The URLs in `sources` name the tables each value corresponds to; they are the places to
re-check, not pages that were read in full during this session. Treat the whole file as a starting point to be
spot-checked against state DOT / DMV pages before release, especially the `verify` values.

## Field by field: source and date

| Field | Source used | Date of source |
|---|---|---|
| `name`, `time_zone`, `daylight_time` | Wikipedia "Time in the United States" (state and county split table); US DOT time zone boundaries (49 CFR 71). Stable since 2007. | table current to 2026 |
| `handheld_phone` | GHSA "Distracted Driving" state table and NCSL "Distracted driving / cellphone use"; 2025 additions confirmed by press coverage: Colorado (Jan 1 2025), Iowa (Jul 1 2025), Pennsylvania (Jun 5 2025), Louisiana (Aug 1 2025, citations Jan 1 2026, kplctv.com), South Carolina (Sep 1 2025). Kansas 2026 law is school/work zones only (wibw.com, Aug 2026). Montana remains the only state with no statewide texting ban. | GHSA/NCSL 2026; press 2025-2026 |
| `speed_limits` | IIHS "Maximum posted speed limits" table, with 2025-2026 changes: North Dakota 80 on I-29/I-94 from Aug 1 2025 (HB 1298, inforum.com); Montana HB 312 (2025) keeps 80 rural / 65 urban; Missouri SB 1408 allows 75 on posted interstates from Aug 28 2026 (ky3.com). Values are rural interstate / urban interstate maxima in mph; truck limits noted where lower. | IIHS table 2026; press 2025-2026 |
| `tolls` | Wikipedia "E-ZPass" and "Electronic toll collection"; Ohio Turnpike release of Sep 28 2026 and NCDOT release of Sep 2 2026 on E-ZPass / NC Quick Pass acceptance in Colorado, Kansas, Oklahoma and parts of Texas via the Central US hub (these four carry `verify` because the exact facilities covered are not listed); Mackinac Bridge Authority FAQ (MacPass only, 2019); Wikipedia "Palmetto Pass" (Cross Island Parkway toll-free since 2021). | 2026 |
| `state_police` | Missouri State Highway Patrol brochure SHP-319 (state-by-state list), confirmed per state where an official page turned up: Alabama *HP (alea.gov), Mississippi *47 (mississippi.gov), Missouri *55 (mo.gov), Nebraska *55 (NDOT, Jan 2026), Ohio #677 (OSHP document), Tennessee *THP (tn.gov), Wyoming #HELP, Virginia #77, New Jersey #77 (njoag.gov), Oklahoma *55 retired (okcfox.com). Codes known only from unofficial compilations carry `verify`. | 2026 |
| `move_over` | GHSA "Move Over Laws" summary; Land Line Media coverage (Feb 2026: 29 states cover all stopped vehicles); state DOT pages for Florida, Wisconsin, Nevada, West Virginia; Scott's Law (Illinois). States whose statute the compiler could not confirm as extending to any stopped vehicle with hazards carry `verify` on that point only; the baseline (emergency, tow, maintenance vehicles) holds in every state. | 2026 |
| `radar_detector` | Virginia Code 46.2-1079; DC Municipal Regulations Title 18; vendor summaries (Escort, Uniden, Radenso) agree these are the only two bans for private cars. | 2026 |
| `open_container` | Wikipedia "United States open-container laws"; FHWA Notice N 4510.905 (Oct 1 2025) FY2026 non-conforming list as reported by secondary sources: Alaska, Connecticut, Delaware, Hawaii, Louisiana, Maine, Mississippi, Missouri, Ohio, Tennessee, Virginia, Wyoming. Arkansas and West Virginia were on older FHWA lists (FY2009) but not the FY2026 one, so they carry `verify`. | FHWA FY2026 |
| `cannabis` | Wikipedia "Legality of cannabis by U.S. jurisdiction"; MADD 2026 summary (24 adult-use states + DC); Virginia retail sales law (NBC Washington, 2026: sales from Jul 1 2027); Nebraska medical program not yet operating (NORML, Sep 2026); Idaho medical initiative on the Nov 2026 ballot. | Oct 2026 |
| `sales_tax` | Tax Foundation "State and Local Sales Tax Rates, Midyear 2026" (rates as of Jul 1 2026); no statewide base-rate changes in 2026 per TaxCloud and Vertex. "plus local" marks states with local add-ons. | Jul 2026 |
| `rest_area_overnight` | Wikipedia "Rest area" table; The Wayward Home "Can you sleep at a rest area" (2026); Boondocker's Bible state summary (2021); Roadtrippers. This field is the softest: sources conflict for many states and posted signs always govern, so 26 states carry `verify`. | 2021-2026 |

## Outlines

Three files, all 51 features (50 states + DC), property `postal` only, coordinates rounded to 4 decimals
(about 11 m), features sorted by `postal`. Scripts are in `scratchpad/scripts/` (`filter_ne.py`,
`repair_dc.py`, `compare_ne.py`, `diag_missing.py`, `build_10m_v2.py`).

| File | Built from | Border accuracy | Size | gzipped | Vertices |
|---|---|---|---|---|---|
| `state-outlines.geojson` (use this) | Natural Earth 1:10m, coverage-simplified at 0.02 deg, 2 km seaward margin | about 2 km | 347,022 B | 111,340 B | 17,682 |
| `state-outlines-1km.geojson` | Natural Earth 1:10m, coverage-simplified at 0.01 deg, 1 km seaward margin | about 1 km | 557,911 B | 177,761 B | 28,567 |
| `state-outlines-50m.geojson` | Natural Earth 1:50m, DC polygon replaced from 1:10m, no margin | 2-3 km at straight borders | 221,376 B | 71,228 B | 11,205 |

Why the 1:50m file (the preferred source in the brief) is not the primary deliverable:

- Its DC polygon is wrong by a lot: the southwest edge runs through downtown instead of along the Virginia bank
  of the Potomac, and the corners are about 2 km off, so the National Mall and Georgetown resolved to Virginia
  (31 percent of DC's area misassigned). That is repaired in `state-outlines-50m.geojson` by substituting the
  1:10m DC polygon and re-cutting the Maryland and Virginia edges that abut it.
- Its straight-line state borders are offset by 1.5 to 3 km in places: a strip of Virginia about 3 km wide along
  the North Carolina line reads as NC, with similar strips on IA/MO, KS/CO, ND/MT, NE/WY and SD/WY. That is at
  the edge of the 2 km target exactly where the app needs to be right, at state-line crossings.
- It omits Block Island (RI) and Kahoolawe (HI), and assigns the Tiverton / Little Compton strip of RI to MA.
- With no seaward margin, city centres on the water (New York, Galveston, Pensacola, Atlantic City, Astoria,
  Miami, El Paso, Brownsville, Juneau) fall into no polygon at all.

How the 1:10m files were built (`build_10m_v2.py`):

1. `shapely.coverage_simplify` (GEOS 3.14) simplifies all 51 polygons as one topological coverage, so shared
   land borders stay identical on both sides (no slivers, no overlaps between states).
2. Each state is then extended outward by the tolerance (a mitred buffer), and the extension is cut back with
   the neighbours' raw and simplified polygons and with the states already processed, so a margin never enters
   another state's territory and never overlaps another state's margin. This margin covers coastlines, river
   borders with Mexico and Canada, bays and lake shores, so a GPS fix on a waterfront street still resolves.
3. Islands under 0.0002 square degrees (about 2 km2) are dropped.

Checks on the primary file: 60 test points (coastal city centres, river-border cities, the Bristol TN/VA and
Kansas City MO/KS pairs, Washington DC and Arlington, Block Island, Tiverton RI, points 1 km either side of the
VA/NC and KS/CO lines) all resolve to the right state, except Chincoteague VA (Natural Earth's barrier-island
coastline misses the town by 2.4 km). Overlap between any two states totals 0.0005 square degrees nationwide
(buffer slivers; the 1 km file has none). A point that still falls outside every polygon is more than 2 km
offshore; the app should keep the last known state in that case.

- Sources: `https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson`
  and `.../ne_50m_admin_1_states_provinces.geojson` (the GitHub mirror of naturalearthdata.com), both
  downloaded 2026-10-23 into `downloads/`; filtered to `iso_a2 == "US"`.
- Fallback not needed: the US Census `cb_2023_us_state_20m` file was unreachable (`www2.census.gov` refused by
  the proxy), but both Natural Earth downloads succeeded.
- Licence: Natural Earth is in the public domain (no restrictions on use; attribution appreciated).

## Licences

- `state-outlines.geojson`: public domain (Natural Earth).
- `state-cards.json`: compiled facts; the underlying statutes and government tables are public; Wikipedia text
  was not copied, only facts from its tables.
