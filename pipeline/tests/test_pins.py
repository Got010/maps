"""Checks that the pipeline's containers and Python packages stay pinned.
Run: python3 -m unittest discover -s pipeline/tests"""

import glob
import os
import re
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PIPELINE = os.path.dirname(HERE)
LOCK = os.path.join(os.path.dirname(PIPELINE), ".github", "pipeline-requirements.txt")

# name:tag@sha256:<64 hex>: the tag says what the digest is, the digest is what runs.
PINNED = re.compile(r"^[a-z0-9./_-]+(:[A-Za-z0-9._-]+)?@sha256:[0-9a-f]{64}$")
IMAGE_DEFAULT = re.compile(r'^([A-Z_]+_IMAGE)="\$\{\1:-([^}]*)\}"', re.M)


def scripts():
    return sorted(glob.glob(os.path.join(PIPELINE, "**", "*.sh"), recursive=True))


def read(path):
    with open(path) as f:
        return f.read()


class ImageTest(unittest.TestCase):
    def test_every_image_is_pinned_by_digest(self):
        found = {}
        for path in scripts():
            for var, image in IMAGE_DEFAULT.findall(read(path)):
                found[var] = image
                self.assertRegex(image, PINNED, f"{var} in {os.path.relpath(path, PIPELINE)}")
                self.assertIn(":", image.split("@")[0], f"{var} keeps its tag next to the digest")
        # The five images the pack builders use (osmium, Valhalla, Planetiler, Python, GDAL).
        self.assertEqual(
            {"OSMIUM_IMAGE", "VALHALLA_IMAGE", "PLANETILER_IMAGE", "PYTHON_IMAGE", "GDAL_IMAGE"}, set(found)
        )

    def test_docker_run_takes_its_image_from_a_variable(self):
        # A literal image name in a script would bypass the pins above.
        for path in scripts():
            text = read(path)
            for image in re.findall(r"""["' ](?:ghcr\.io|docker\.io|quay\.io)/[^\s"']+""", text):
                self.fail(f"{os.path.relpath(path, PIPELINE)} names {image.strip()} outside an *_IMAGE default")
            for line in text.splitlines():
                if "docker run" in line:
                    self.assertTrue(
                        "_IMAGE" in line or line.rstrip().endswith("\\"),
                        f"docker run without an image variable: {line.strip()}",
                    )

    def test_pinned_tags_are_not_floating(self):
        for path in scripts():
            for var, image in IMAGE_DEFAULT.findall(read(path)):
                self.assertNotIn(":latest", image, var)


class PipTest(unittest.TestCase):
    def test_pip_installs_require_hashes(self):
        installs = 0
        for path in scripts():
            for line in read(path).splitlines():
                if re.search(r"\bpip3? install\b", line):
                    installs += 1
                    for flag in ("--require-hashes", "--only-binary :all:", "-r "):
                        self.assertIn(flag, line, f"{os.path.relpath(path, PIPELINE)}: {line.strip()}")
        self.assertGreaterEqual(installs, 1)

    def test_the_lock_hashes_every_package(self):
        text = read(LOCK)
        requirements = re.findall(r"^([A-Za-z0-9_.-]+==[^\s\\]+)[^\n]*\\\n((?:\s+--hash=[^\n]+\n?)+)", text, re.M)
        names = {r[0].split("==")[0].lower() for r in requirements}
        self.assertLessEqual({"osmium", "duckdb"}, names)
        # Every line that names a package is one of those, so none is unhashed.
        listed = re.findall(r"^[A-Za-z0-9_.-]+==", text, re.M)
        self.assertEqual(len(listed), len(requirements))
        for _, hashes in requirements:
            for h in re.findall(r"--hash=sha256:([0-9a-f]+)", hashes):
                self.assertEqual(64, len(h))

    def test_the_search_build_mounts_the_lock_ci_installs(self):
        text = read(os.path.join(PIPELINE, "search", "build.sh"))
        self.assertIn(".github/pipeline-requirements.txt", text)
        self.assertIn(":/requirements.txt:ro", text)
        self.assertTrue(os.path.isfile(LOCK))


if __name__ == "__main__":
    unittest.main()
