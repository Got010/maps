"""Checks scripts/release-manifest.py. Run: python3 -m unittest discover -s pipeline/tests"""

import importlib.util
import json
import os
import re
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "..", "scripts", "release-manifest.py")
spec = importlib.util.spec_from_file_location("release_manifest", SCRIPT)
release_manifest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_manifest)


class ReleaseManifestTest(unittest.TestCase):
    def test_lists_every_pack_kind_including_voices(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ["southbound-1.2.3.apk", "basemap-nyc.pmtiles", "routing-nys.tar", "search-nys.sqlite",
                         "transit-nyc.sqlite", "voice-norman.zip", "voice-norman.zip.sha256", "notes.txt"]:
                with open(os.path.join(d, name), "wb") as f:
                    f.write(b"x")
            m = release_manifest.manifest(d, "1.2.3")
        self.assertEqual(10203, m["versionCode"])
        self.assertEqual("southbound-1.2.3.apk", m["app"]["file"])
        self.assertEqual(
            [("basemap", "nyc"), ("routing", "nys"), ("search", "nys"), ("transit", "nyc"), ("voice", "norman")],
            [(p["kind"], p["region"]) for p in m["packs"]],
        )

    def files(self, d, names):
        for name in names:
            with open(os.path.join(d, name), "wb") as f:
                f.write(b"x")

    def test_older_apps_see_only_the_packs_they_know(self):
        with tempfile.TemporaryDirectory() as d:
            self.files(d, ["southbound-1.2.3.apk", "basemap-nyc.pmtiles", "basemap-ct.pmtiles", "routing-nys.tar",
                           "routing-main.tar", "routing-ct.tar", "search-nys.sqlite", "search-ct.sqlite"])
            m = release_manifest.manifest(d, "1.2.3", only={"basemap-nyc.pmtiles", "routing-nys.tar", "search-nys.sqlite"})
        self.assertEqual(1, m["schema"])
        self.assertEqual(["basemap-nyc.pmtiles", "routing-nys.tar", "search-nys.sqlite"], [p["file"] for p in m["packs"]])

    def test_v2_says_which_regions_need_each_routing_and_search_pack(self):
        regions = os.path.join(HERE, "..", "regions.json")
        with tempfile.TemporaryDirectory() as d:
            self.files(d, ["southbound-1.2.3.apk", "basemap-nyc.pmtiles", "basemap-ct.pmtiles", "routing-nys.tar",
                           "routing-main.tar", "routing-ct.tar", "routing-hudson-li-njn-nyc.tar", "search-nys.sqlite",
                           "search-ct.sqlite", "search-nyc.sqlite", "transit-nyc.sqlite"])
            with open(os.path.join(d, "routing-packs.json"), "w") as f:
                json.dump({"routing-main.tar": [], "routing-ct.tar": ["ct"],
                           "routing-hudson-li-njn-nyc.tar": ["hudson", "li", "njn", "nyc"]}, f)
            m = release_manifest.manifest(d, "1.2.3", v2=True, regions_json=regions)
        self.assertEqual(2, m["schema"])
        packs = {p["file"]: p for p in m["packs"]}
        # The packs built whole for older apps: the one-graph routing can't be mixed into a tile folder, and
        # New York's regions have search packs of their own, cut from search-nys.
        self.assertNotIn("routing-nys.tar", packs)
        self.assertNotIn("search-nys.sqlite", packs)
        self.assertEqual([], packs["routing-main.tar"]["regions"])
        self.assertEqual(["hudson", "li", "njn", "nyc"], packs["routing-hudson-li-njn-nyc.tar"]["regions"])
        self.assertEqual(("routing", "hudson-li-njn-nyc"), (packs["routing-hudson-li-njn-nyc.tar"]["kind"],
                                                              packs["routing-hudson-li-njn-nyc.tar"]["region"]))
        self.assertEqual(["nyc"], packs["search-nyc.sqlite"]["regions"])
        self.assertEqual(["ct"], packs["search-ct.sqlite"]["regions"])
        self.assertNotIn("regions", packs["basemap-ct.pmtiles"])
        self.assertNotIn("regions", packs["transit-nyc.sqlite"])

    def test_the_release_lists_the_search_packs_cut_from_search_nys(self):
        # release.yml uploads, carries over and checks NYS_SEARCH_PACKS; split_search.py writes one per region.
        with open(os.path.join(HERE, "..", "regions.json")) as f:
            regions = json.load(f)["regions"]
        cut = [f"search-{name}.sqlite" for name, r in regions.items() if r.get("search_from") == "nys"]
        with open(os.path.join(HERE, "..", "..", ".github", "workflows", "release.yml")) as f:
            listed = re.search(r"^  NYS_SEARCH_PACKS: (.+)$", f.read(), re.M).group(1).split()
        self.assertEqual(cut, listed)


if __name__ == "__main__":
    unittest.main()
