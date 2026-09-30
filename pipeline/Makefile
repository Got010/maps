# Data pipeline. Run from the repo root as: make -C pipeline <target>
# Outputs land in pipeline/data/out (git-ignored).

.PHONY: nyc state release tiles-nyc styles glyphs clean

## nyc: every NYC pack built so far (Phase 0: basemap only)
nyc: tiles-nyc

tiles-nyc:
	tiles/build.sh nyc

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
