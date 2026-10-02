# Data pipeline. Run from the repo root as: make -C pipeline <target>
# Outputs land in pipeline/data/out (git-ignored).

.PHONY: nyc state release tiles-nyc routing-nyc search-nyc push-nyc styles glyphs test clean

## nyc: every NYC pack built so far: basemap, routing and search
nyc: tiles-nyc routing-nyc search-nyc

tiles-nyc:
	tiles/build.sh nyc

routing-nyc:
	routing/build.sh nyc

search-nyc:
	search/build.sh nyc

## test: unit tests for the pipeline's own code (needs Python 3 and pyosmium)
test:
	python3 -m unittest discover -s search/tests

## push-nyc: copy basemap-nyc.pmtiles to a USB-connected phone
push-nyc:
	./push-pack.sh data/out/basemap-nyc.pmtiles

## state: statewide regional packs (Phase 3)
state:
	@echo "Statewide packs arrive in Phase 3." >&2; exit 1

## release: manifest and GitHub release upload (Phase 3)
release:
	@echo "Release packaging arrives in Phase 3." >&2; exit 1

## styles: regenerate styles/light.json and styles/dark.json
styles:
	python3 styles/build_styles.py

## glyphs: download the bundled label fonts into styles/glyphs
glyphs:
	styles/fetch-glyphs.sh

clean:
	rm -rf data/out data/tmp
