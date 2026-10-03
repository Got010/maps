"""Checks scripts/release-manifest.py. Run: python3 -m unittest discover -s pipeline/tests"""

import importlib.util
import os
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


if __name__ == "__main__":
    unittest.main()
