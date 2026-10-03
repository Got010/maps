package app.southbound.core.packs

import app.southbound.core.network.MiniJson

/** One file in a release, with what a download of it must match. */
data class ReleaseFile(val name: String, val size: Long, val sha256: String)

data class ReleasePack(val kind: PackKind, val region: String, val file: ReleaseFile)

/**
 * `southbound.json`, published with every release by scripts/release-manifest.py:
 * the app version and the data packs, each with its size and SHA-256. The app
 * reads it to decide what to download, then checks every download against it.
 */
data class ReleaseManifest(
    val version: String,
    val versionCode: Long,
    val app: ReleaseFile?,
    val packs: List<ReleasePack>,
) {
    fun pack(kind: PackKind, region: String): ReleasePack? = packs.firstOrNull { it.kind == kind && it.region == region }

    companion object {
        const val FILE_NAME = "southbound.json"
        const val SCHEMA = 1

        /** Biggest file the app will accept: well above the NYC packs, well below a phone's free space. */
        const val MAX_FILE_BYTES = 4L shl 30

        private val SHA256 = Regex("[0-9a-f]{64}")
        private val VERSION = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{1,2})""")
        private val REGION = Regex("[a-z0-9-]{1,32}")

        /**
         * Parses and checks a manifest. Packs of kinds this app doesn't know are
         * skipped, so an older app keeps working with a newer release.
         *
         * @throws ManifestException when the document is malformed or unsafe.
         */
        fun parse(json: String): ReleaseManifest {
            val root = try {
                MiniJson.parse(json)
            } catch (e: MiniJson.JsonException) {
                throw ManifestException("not JSON: ${e.message}")
            } as? Map<*, *> ?: throw ManifestException("not an object")

            val schema = (root["schema"] as? Long) ?: throw ManifestException("no schema")
            if (schema != SCHEMA.toLong()) throw ManifestException("schema $schema; this app reads $SCHEMA")
            val version = root["version"] as? String ?: throw ManifestException("no version")
            val m = VERSION.matchEntire(version) ?: throw ManifestException("bad version '$version'")
            val (major, minor, patch) = m.destructured
            val versionCode = major.toLong() * 10_000 + minor.toLong() * 100 + patch.toLong()
            if ((root["versionCode"] as? Long) != versionCode) throw ManifestException("versionCode doesn't match $version")

            val app = (root["app"] as? Map<*, *>)?.let { file(it) }
            if (app != null && app.name != "southbound-$version.apk") throw ManifestException("unexpected APK name ${app.name}")

            val packs = (root["packs"] as? List<*>).orEmpty().mapNotNull { item ->
                val p = item as? Map<*, *> ?: throw ManifestException("pack is not an object")
                val kind = PackKind.entries.firstOrNull { it.prefix == p["kind"] } ?: return@mapNotNull null
                val region = p["region"] as? String
                if (region == null || !REGION.matches(region)) throw ManifestException("bad region '$region'")
                val f = file(p)
                // The name decides where the file lands, so it must be exactly the expected one.
                if (f.name != kind.fileName(region)) throw ManifestException("unexpected pack name ${f.name}")
                ReleasePack(kind, region, f)
            }
            if (packs.distinctBy { it.kind to it.region }.size != packs.size) throw ManifestException("duplicate pack")
            return ReleaseManifest(version, versionCode, app, packs)
        }

        private fun file(m: Map<*, *>): ReleaseFile {
            val name = m["file"] as? String ?: throw ManifestException("file without a name")
            if (name.isEmpty() || '/' in name || '\\' in name || name.startsWith(".")) throw ManifestException("unsafe name '$name'")
            val size = m["size"] as? Long ?: throw ManifestException("$name has no size")
            if (size <= 0 || size > MAX_FILE_BYTES) throw ManifestException("$name has size $size")
            val sha = (m["sha256"] as? String)?.lowercase()
            if (sha == null || !SHA256.matches(sha)) throw ManifestException("$name has no valid sha256")
            return ReleaseFile(name, size, sha)
        }
    }
}

class ManifestException(message: String) : IllegalArgumentException("Release manifest: $message")

/** What one update round should fetch. */
data class UpdatePlan(val packs: List<ReleasePack>, val app: ReleaseFile?) {
    val isEmpty: Boolean get() = packs.isEmpty() && app == null
    val bytes: Long get() = packs.sumOf { it.file.size } + (app?.size ?: 0)

    companion object {
        /**
         * The packs the phone should have: a basemap for each region in
         * [basemapRegions], and every other kind of pack the release offers
         * (routing and search are statewide, transit is NYC), restricted to
         * [wanted] kinds.
         */
        fun wanted(manifest: ReleaseManifest, basemapRegions: Set<String>, wanted: Set<PackKind> = PackKind.entries.toSet()): List<ReleasePack> =
            manifest.packs.filter { it.kind in wanted && (it.kind != PackKind.BASEMAP || it.region in basemapRegions) }

        /**
         * Packs among [wanted] whose installed copy (keyed by kind and region)
         * is missing or differs by SHA-256 from the release's; and the APK when
         * the release is newer than [installedVersionCode] and [includeApp] is set.
         */
        fun of(
            manifest: ReleaseManifest,
            basemapRegions: Set<String>,
            installed: Map<Pair<PackKind, String>, String?>,
            wanted: Set<PackKind> = PackKind.entries.toSet(),
            installedVersionCode: Long,
            includeApp: Boolean,
        ): UpdatePlan = UpdatePlan(
            packs = wanted(manifest, basemapRegions, wanted).filter { installed[it.kind to it.region] != it.file.sha256 },
            app = manifest.app?.takeIf { includeApp && manifest.versionCode > installedVersionCode },
        )
    }
}
