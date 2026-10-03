#!/usr/bin/env python3
"""Writes southbound.json, the manifest the app reads to update itself.

    release-manifest.py <dir> <version>   (prints the manifest)

<dir> holds a release's files: southbound-<version>.apk and the data packs.
The app downloads this small file first, then only the APK or packs whose
SHA-256 differs from what it has, and checks each download against it.
Parsed by core/packs ReleaseManifest; keep the two in step.
"""

import hashlib
import json
import os
import re
import sys

SCHEMA = 1
PACK = re.compile(r"^(basemap|routing|search|transit)-([a-z0-9-]+)\.(pmtiles|tar|sqlite)$")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def version_code(version):
    major, minor, patch = (int(p) for p in version.split("."))
    return major * 10_000 + minor * 100 + patch


def entry(directory, name):
    path = os.path.join(directory, name)
    return {"file": name, "size": os.path.getsize(path), "sha256": sha256(path)}


def manifest(directory, version):
    names = sorted(os.listdir(directory))
    apk = f"southbound-{version}.apk"
    packs = []
    for name in names:
        m = PACK.match(name)
        if m:
            packs.append({"kind": m.group(1), "region": m.group(2), **entry(directory, name)})
    return {
        "schema": SCHEMA,
        "version": version,
        "versionCode": version_code(version),
        "app": entry(directory, apk) if apk in names else None,
        "packs": packs,
    }


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    json.dump(manifest(sys.argv[1], sys.argv[2]), sys.stdout, indent=2)
    print()
