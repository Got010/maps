# Data pipeline. Run from the repo root as: make -C pipeline <target>
# Outputs land in pipeline/data/out (git-ignored).
#
# Regions are listed in regions.json. Any pack builds as <kind>-<region>:
#   make -C pipeline tiles-western routing-nys search-nys transit-nyc
# Voice packs build as voice-<voice> (see lib/voices.sh): make -C pipeline voice-norman

REGIONS := nyc li hudson capital western njn nys
BASEMAP_REGIONS := nyc li hudson capital western njn
# Downloadable voices; amy is bundled in the APK instead.
VOICES := norman
VOICE_PACKS := $(addprefix voice-,$(VOICES))
STATE_PACKS := $(addprefix tiles-,$(BASEMAP_REGIONS)) routing-nys search-nys transit-nyc $(VOICE_PACKS)
ALL_PACKS := $(foreach k,tiles routing search transit,$(addprefix $(k)-,$(REGIONS))) $(VOICE_PACKS)

.PHONY: nyc state release $(ALL_PACKS) push-nyc styles glyphs test clean

## nyc: every NYC-only pack: basemap, routing, search and transit (quick, for tests)
nyc: tiles-nyc routing-nyc search-nyc transit-nyc

## state: what a release ships: six regional basemaps, routing and search for New York and northern New Jersey (nys), NYC transit, voice packs
state: $(STATE_PACKS)

$(addprefix tiles-,$(REGIONS)): tiles-%:
	tiles/build.sh $*

$(addprefix routing-,$(REGIONS)): routing-%:
	routing/build.sh $*

$(addprefix search-,$(REGIONS)): search-%:
	search/build.sh $*

$(addprefix transit-,$(REGIONS)): transit-%:
	transit/build.sh $*

$(VOICE_PACKS): voice-%:
	voices/build.sh $*

## test: unit tests for the pipeline's own code (needs Python 3 and pyosmium; duckdb, if installed, also tests the Overture fetch query)
test:
	python3 -m unittest discover -s search/tests
	python3 -m unittest discover -s transit/tests
	python3 -m unittest discover -s tests

## push-nyc: copy basemap-nyc.pmtiles to a USB-connected phone
push-nyc:
	./push-pack.sh data/out/basemap-nyc.pmtiles

## release: packs are published by .github/workflows/release.yml on every merge to main
release:
	@echo "Releases are built by GitHub Actions: merge to main, or run the Release workflow with 'Rebuild data'." >&2; exit 1

## styles: regenerate styles/light.json and styles/dark.json
styles:
	python3 styles/build_styles.py

## glyphs: download the bundled label fonts into styles/glyphs
glyphs:
	styles/fetch-glyphs.sh

clean:
	rm -rf data/out data/tmp
