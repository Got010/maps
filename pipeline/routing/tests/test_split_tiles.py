"""Tests for split_tiles.py. Run: python3 -m unittest discover -s pipeline/routing/tests"""

import io
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

    def test_main_levels_go_to_main(self):
        self.assertEqual(["main"], self.packs_at(40.7580, -73.9855, level=0))
        self.assertEqual(["main"], self.packs_at(39.9526, -75.1636, level=1))
        self.assertEqual(["main"], self.packs_at(40.0, -82.0, level=0))  # Ohio: still a main road tile

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
        self.assertEqual([], self.packs_at(40.0, -82.0))  # Ohio
        self.assertEqual([], self.packs_at(37.5, -78.0))  # Virginia


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
                st.tile_path(2, st.tile_id_at(2, 40.0, -82.0)): b"ohio",
            }
            for rel, data in files.items():
                os.makedirs(os.path.dirname(os.path.join(tiles, rel)), exist_ok=True)
                with open(os.path.join(tiles, rel), "wb") as f:
                    f.write(data)
            with open(os.path.join(tiles, "valhalla.json"), "w") as f:
                f.write("{}")
            result = st.split(tiles, out, regions, log=io.StringIO())

            self.assertEqual((2, len(b"highways") + len(b"arterials")), result["main"])
            self.assertEqual((1, len(b"buffalo")), result["western"])
            self.assertEqual((1, len(b"ohio")), result[None])
            self.assertEqual(
                ["routing-main.tar", "routing-njs.tar", "routing-pae.tar", "routing-western.tar"],
                sorted(os.listdir(out)),
            )
            with tarfile.open(os.path.join(out, "routing-pae.tar")) as tar:
                (member,) = tar.getmembers()
                self.assertEqual(st.tile_path(2, st.tile_id_at(2, 39.9526, -75.1636)), member.name)
                self.assertEqual(b"philadelphia", tar.extractfile(member).read())
            # The app tells a routing pack by tar's "ustar" mark at byte 257 (PackKind.detect).
            with open(os.path.join(out, "routing-main.tar"), "rb") as f:
                self.assertEqual(b"ustar", f.read(262)[257:262])
            # The same tiles give the same file.
            first = open(os.path.join(out, "routing-main.tar"), "rb").read()
            st.split(tiles, out, regions, log=io.StringIO())
            self.assertEqual(first, open(os.path.join(out, "routing-main.tar"), "rb").read())

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
