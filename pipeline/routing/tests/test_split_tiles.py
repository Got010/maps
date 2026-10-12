"""Tests for split_tiles.py. Run: python3 -m unittest discover -s pipeline/routing/tests"""

import io
import json
import os
import sys
import tarfile
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import split_tiles as st  # noqa: E402


class TileNamesTest(unittest.TestCase):
    def test_paths_are_valhallas(self):
        # Valhalla pads a level's ids to a multiple of three digits: 4050 tiles at level 0, 64800 at level 1,
        # 1036800 at level 2.
        self.assertEqual("0/003/015.gph", st.tile_path(0, 3015))
        self.assertEqual("1/037/741.gph", st.tile_path(1, 37741))
        self.assertEqual("2/000/756/425.gph", st.tile_path(2, 756425))
        for level, tile_id in [(0, 0), (1, 64799), (2, 1036799), (2, 756425)]:
            self.assertEqual((level, tile_id), st.tile_from_path(st.tile_path(level, tile_id)))

    def test_not_tiles(self):
        for path in ["valhalla.json", "2/000/756/425.gph.tmp", "3/000/000/001.gph", "2/756/425.gph", "x/1.gph"]:
            self.assertIsNone(st.tile_from_path(path), path)

    def test_tile_at_and_bounds(self):
        # Times Square is in the level 2 tile from 40.75 to 41.0 north, 74.0 to 73.75 west.
        tile = st.tile_id_at(2, 40.7580, -73.9855)
        self.assertEqual((-74.0, 40.75, -73.75, 41.0), st.tile_bounds(2, tile))
        self.assertEqual((-76.0, 38.0, -72.0, 42.0), st.tile_bounds(0, st.tile_id_at(0, 40.7580, -73.9855)))


class AssignmentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.regions = st.load_regions()

    def packs_at(self, lat, lon, level=2):
        return st.regions_for_tile(self.regions, level, st.tile_id_at(level, lat, lon))

    def test_a_region_ending_on_a_tile_edge_does_not_take_the_tile_beyond(self):
        # Hudson and Western meet at 75.5 W, a level 2 tile edge: each side's column is its own region's.
        self.assertEqual(["hudson"], self.packs_at(42.1, -75.4))
        self.assertEqual(["western"], self.packs_at(42.1, -75.6))

    def test_packs_are_named_by_the_regions_they_serve(self):
        self.assertEqual("main", st.pack_for(0, ["nyc"]))
        self.assertEqual("main", st.pack_for(0, [], groups=["east"]))
        # The Midwest's main roads in packs of their own, the seam's in one both parts' phones take.
        self.assertEqual("main-midwest", st.pack_for(0, ["ohn"], groups=["midwest"]))
        self.assertEqual("main-east-midwest", st.pack_for(0, ["ohn", "paw"], groups=["midwest", "east"]))
        # Only level 0 is shared: secondary roads go by region like local streets.
        self.assertEqual("hudson", st.pack_for(1, ["hudson"]))
        self.assertEqual("hudson", st.pack_for(2, ["hudson"]))
        self.assertEqual("hudson-njn-nyc", st.pack_for(2, ["nyc", "hudson", "njn"]))
        self.assertIsNone(st.pack_for(1, []))
        self.assertIsNone(st.pack_for(2, []))
        # As the first test build cut them, levels 0 and 1 shared.
        self.assertEqual("main", st.pack_for(1, ["hudson"], main_levels=(0, 1)))

    def test_main_roads_go_by_group(self):
        def groups_at(lat, lon):
            tile = st.tile_id_at(0, lat, lon)
            return st.main_groups_for_tile(self.regions, 0, tile, st.regions_for_tile(self.regions, 0, tile))
        self.assertEqual(["east"], groups_at(40.75, -73.98))  # Manhattan
        self.assertEqual(["midwest"], groups_at(41.88, -87.63))  # Chicago
        self.assertEqual(["east", "midwest"], groups_at(41.5, -82.0))  # Lake Erie's shore: Ohio and Pennsylvania
        # A tile covering no region goes with the regions whose boxes it touches: Kentucky's with Indiana's and
        # Ohio's, Virginia's with Maryland's and Pennsylvania's.
        self.assertEqual(["midwest"], st.main_groups_for_tile(self.regions, 0, st.tile_id_at(0, 37.5, -85.0), []))
        self.assertEqual(["east"], st.main_groups_for_tile(self.regions, 0, st.tile_id_at(0, 37.5, -78.0), []))
        # One touching no box at all goes with every group.
        self.assertEqual(["east", "midwest"], st.main_groups_for_tile(self.regions, 0, st.tile_id_at(0, 30.0, -90.0), []))
        self.assertEqual(["nyc", "li", "hudson", "capital", "western", "njn", "njs", "ct", "pae", "paw", "md"], st.regions_in_groups(self.regions, ["east"]))
        self.assertEqual(15, len(st.regions_in_groups(self.regions, ["midwest"])))

    def test_local_roads_follow_the_maps(self):
        # Manhattan and the Bronx with Fort Lee across the river: New York City's, the Hudson Valley's and
        # northern New Jersey's maps all reach this tile.
        manhattan = self.packs_at(40.7580, -73.9855)
        for name in ["nyc", "hudson", "njn"]:
            self.assertIn(name, manhattan)
        self.assertNotIn("pae", manhattan)
        # Philadelphia and Camden share a tile.
        self.assertEqual(["njs", "pae"], self.packs_at(39.9526, -75.1636))
        self.assertEqual(["paw"], self.packs_at(40.4406, -79.9959))  # Pittsburgh
        self.assertEqual(["ct"], self.packs_at(41.7658, -72.6734))  # Hartford
        self.assertEqual(["md"], self.packs_at(39.2904, -76.6122))  # Baltimore
        self.assertEqual(["western"], self.packs_at(42.8864, -78.8784))  # Buffalo

    def test_pennsylvania_stays_out_of_new_york_packs(self):
        # The Poconos, under the Hudson Valley's box but inside Pennsylvania's area.
        self.assertEqual(["pae"], self.packs_at(41.10, -75.40))

    def test_tiles_no_region_covers(self):
        self.assertEqual([], self.packs_at(37.5, -85.0))  # Kentucky
        self.assertEqual([], self.packs_at(37.5, -78.0))  # Virginia
        self.assertEqual(["ohs"], self.packs_at(40.0, -82.0))  # Ohio, since the Midwest (planned regions count)


class SplitTest(unittest.TestCase):
    def test_packs_on_disk(self):
        regions = st.load_regions()
        with tempfile.TemporaryDirectory() as tmp:
            tiles = os.path.join(tmp, "tiles")
            out = os.path.join(tmp, "out")
            files = {
                st.tile_path(0, st.tile_id_at(0, 40.75, -73.98)): b"highways",
                st.tile_path(1, st.tile_id_at(1, 40.75, -73.98)): b"arterials",
                st.tile_path(2, st.tile_id_at(2, 42.8864, -78.8784)): b"buffalo",
                st.tile_path(2, st.tile_id_at(2, 39.9526, -75.1636)): b"philadelphia",
                st.tile_path(2, st.tile_id_at(2, 37.5, -85.0)): b"kentucky",
            }
            for rel, data in files.items():
                os.makedirs(os.path.dirname(os.path.join(tiles, rel)), exist_ok=True)
                with open(os.path.join(tiles, rel), "wb") as f:
                    f.write(data)
            with open(os.path.join(tiles, "valhalla.json"), "w") as f:
                f.write("{}")
            index = os.path.join(tmp, "tiles.jsonl")
            result = st.split(tiles, out, regions, log=io.StringIO(), index=index)

            self.assertEqual((1, len(b"highways")), result["main"])
            self.assertEqual((1, len(b"buffalo")), result["western"])
            self.assertEqual((1, len(b"kentucky")), result[None])
            # Manhattan's 1-degree level 1 tile reaches four regions; Philadelphia and Camden's level 2 tile, once,
            # in the pack for both regions.
            arterials = st.pack_for(1, st.regions_for_tile(regions, 1, st.tile_id_at(1, 40.75, -73.98)))
            self.assertEqual((1, len(b"arterials")), result[arterials])
            self.assertEqual(
                sorted(["routing-main.tar", f"routing-{arterials}.tar", "routing-njs-pae.tar", "routing-packs.json",
                        "routing-western.tar"]),
                sorted(os.listdir(out)),
            )
            with open(os.path.join(out, "routing-packs.json")) as f:
                listing = json.load(f)
            # The east's main roads, for every east region's phone (the first layout listed no region: every phone).
            self.assertEqual(["nyc", "li", "hudson", "capital", "western", "njn", "njs", "ct", "pae", "paw", "md"], listing["routing-main.tar"])
            self.assertEqual(["njs", "pae"], listing["routing-njs-pae.tar"])
            self.assertEqual(["western"], listing["routing-western.tar"])
            self.assertEqual(arterials.split("-"), listing[f"routing-{arterials}.tar"])
            with open(index) as f:
                rows = [json.loads(line) for line in f]
            self.assertIn([st.tile_path(2, st.tile_id_at(2, 37.5, -85.0)), 2, 8, []], rows)
            with tarfile.open(os.path.join(out, "routing-njs-pae.tar")) as tar:
                (member,) = tar.getmembers()
                self.assertEqual(st.tile_path(2, st.tile_id_at(2, 39.9526, -75.1636)), member.name)
                self.assertEqual(b"philadelphia", tar.extractfile(member).read())
            # The app tells a routing pack by tar's "ustar" mark at byte 257 (PackKind.detect).
            with open(os.path.join(out, "routing-main.tar"), "rb") as f:
                self.assertEqual(b"ustar", f.read(262)[257:262])
            # The same tiles give the same file.
            with open(os.path.join(out, "routing-main.tar"), "rb") as f:
                first = f.read()
            st.split(tiles, out, regions, log=io.StringIO())
            with open(os.path.join(out, "routing-main.tar"), "rb") as f:
                self.assertEqual(first, f.read())

    def test_a_pack_over_the_limit_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            rel = st.tile_path(0, 0)
            os.makedirs(os.path.join(tmp, "tiles", os.path.dirname(rel)))
            with open(os.path.join(tmp, "tiles", rel), "wb") as f:
                f.write(b"x" * 2_000_000)
            with self.assertRaises(SystemExit):
                st.split(os.path.join(tmp, "tiles"), os.path.join(tmp, "out"), {}, max_mb=1, log=io.StringIO())


if __name__ == "__main__":
    unittest.main()
