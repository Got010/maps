"""Tests for search/build.sh's address_points_input: which dataset inside an unpacked NYS Address Points download
ogr2ogr is given. The October 2026 zip nests the geodatabase (X.gdb/X.gdb/a00000001.gdbtable); the outer folder holds
only the inner one and cannot be opened. Needs bash; build.sh itself runs a whole build, so the function is cut out of
it and run alone. Run: python3 -m unittest discover -s pipeline/search/tests"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD_SH = os.path.join(os.path.dirname(HERE), "build.sh")
GDB = "SAM_Master_Statewide_Database.gdb"


def function_source():
    with open(BUILD_SH) as f:
        text = f.read()
    match = re.search(r"^address_points_input\(\) \{\n.*?^\}\n", text, re.S | re.M)
    assert match, "build.sh has no address_points_input function"
    return match.group(0)


@unittest.skipUnless(shutil.which("bash") and shutil.which("find"), "needs bash and find")
class AddressPointsInputTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = os.path.join(self.tmp.name, "address-points")
        os.mkdir(self.dir)

    def touch(self, *parts):
        path = os.path.join(self.dir, *parts)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, "wb").close()

    def run_function(self, label="the.zip"):
        script = "set -euo pipefail\n" + function_source() + 'address_points_input "$1" "$2"\n'
        return subprocess.run(["bash", "-c", script, "bash", self.dir, label], capture_output=True, text=True)

    def test_flat_geodatabase(self):
        for name in ("a00000001.gdbtable", "a00000004.gdbtable", "gdb"):
            self.touch(GDB, name)
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./" + GDB + "\n", result.stdout)

    def test_nested_geodatabase_is_the_inner_folder(self):
        for name in ("a00000001.gdbtable", "a00000004.gdbtable", "gdb"):
            self.touch(GDB, GDB, name)
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./%s/%s\n" % (GDB, GDB), result.stdout)

    def test_deeper_wrapper_folders(self):
        self.touch("export", "2026-10", GDB, GDB, "a00000001.gdbtable")
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./export/2026-10/%s/%s\n" % (GDB, GDB), result.stdout)

    def test_the_geodatabase_with_the_catalog_table_is_preferred(self):
        self.touch("A.gdb", "a00000004.gdbtable")
        self.touch("Z.gdb", "a00000001.gdbtable")
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./Z.gdb\n", result.stdout)

    def test_a_geodatabase_without_the_catalog_table_is_still_used(self):
        self.touch(GDB, GDB, "a00000004.gdbtable")
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./%s/%s\n" % (GDB, GDB), result.stdout)

    def test_a_gdbtable_outside_a_gdb_folder_is_not_a_geodatabase(self):
        self.touch("tables", "a00000001.gdbtable")
        result = self.run_function()
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("No .gdb, .shp or .gpkg in the.zip", result.stderr)

    def test_shapefile(self):
        for name in ("address_points.shp", "address_points.dbf", "address_points.shx"):
            self.touch("shp", name)
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./shp/address_points.shp\n", result.stdout)

    def test_geopackage(self):
        self.touch("address_points.gpkg")
        result = self.run_function()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("./address_points.gpkg\n", result.stdout)

    def test_a_geodatabase_wins_over_a_shapefile(self):
        self.touch("old", "points.shp")
        self.touch(GDB, "a00000001.gdbtable")
        result = self.run_function()
        self.assertEqual("./" + GDB + "\n", result.stdout)

    def test_nothing_usable(self):
        self.touch("readme.txt")
        result = self.run_function("SAM.zip")
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertEqual("No .gdb, .shp or .gpkg in SAM.zip\n", result.stderr)

    def test_an_outer_geodatabase_folder_with_no_tables_says_so(self):
        os.makedirs(os.path.join(self.dir, GDB, GDB))
        result = self.run_function("SAM.zip")
        self.assertEqual(1, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("holds no .gdbtable files", result.stderr)
        self.assertIn("SAM.zip", result.stderr)


if __name__ == "__main__":
    unittest.main()
