package app.southbound.core.packs

import app.southbound.core.network.MiniJson

/** One file in a release, with what a download of it must match. */
data class ReleaseFile(val name: String, val size: Long, val sha256: String)

/**
 * A pack in a release. [regions] (southbound-v2.json only) are the basemap regions that need it: a routing pack
 * of local roads names the regions its tiles cover, a search pack the regions it serves; an empty list is every
 * phone's (routing-main.tar). Null when the manifest doesn't say, and the pack goes by its kind (see [UpdatePlan]).
 */
data class ReleasePack(val kind: PackKind, val region: String, val file: ReleaseFile, val regions: List<String>? = null)

/**
 * `southbound.json`, published with every release by scripts/release-manifest.py:
 * the app version and the data packs, each with its size and SHA-256. The app
 * reads it to decide what to download, then checks every download against it.
 *
 * Releases since the four-state packs also publish `southbound-v2.json` ([FILE_NAME_V2], schema 2): every pack
 * but the one-graph routing-nys.tar, with the regions each routing and search pack serves. This app reads it when
 * the release has it ([parseV2]); southbound.json stays as older apps know it.
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
        const val FILE_NAME_V2 = "southbound-v2.json"
        const val SCHEMA_V2 = 2

        /** Biggest file the app will accept: well above the NYC packs, well below a phone's free space. */
        const val MAX_FILE_BYTES = 4L shl 30

        private val SHA256 = Regex("[0-9a-f]{64}")
        private val VERSION = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{1,2})""")
        // A routing pack is named by the regions its tiles cover ("ct-hudson-li-njn-njs-nyc"): up to 64 characters.
        private val REGION = Regex("[a-z0-9-]{1,64}")
        private val REGION_ID = Regex("[a-z0-9]{1,32}")

        /**
         * Parses and checks a manifest. Packs of kinds this app doesn't know are
         * skipped, so an older app keeps working with a newer release.
         *
         * @throws ManifestException when the document is malformed or unsafe.
         */
        fun parse(json: String): ReleaseManifest = parse(json, SCHEMA)

        /** Parses and checks a southbound-v2.json; see [parse]. */
        fun parseV2(json: String): ReleaseManifest = parse(json, SCHEMA_V2)

        private fun parse(json: String, expectedSchema: Int): ReleaseManifest {
            val root = try {
                MiniJson.parse(json)
            } catch (e: MiniJson.JsonException) {
                throw ManifestException("not JSON: ${e.message}")
            } as? Map<*, *> ?: throw ManifestException("not an object")

            val schema = (root["schema"] as? Long) ?: throw ManifestException("no schema")
            if (schema != expectedSchema.toLong()) throw ManifestException("schema $schema; this file should be $expectedSchema")
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
                // Only a v2 routing pack (unpacked into the tile folder) may name several regions. Any other pack is
                // stored as <kind>-<region>, which PackStore reads back with a one-word region: one with a hyphen
                // would never be found installed and would download again every round. Skipped, as an unknown kind.
                if (!(kind == PackKind.ROUTING && expectedSchema == SCHEMA_V2) && !REGION_ID.matches(region)) return@mapNotNull null
                val f = file(p)
                // The name decides where the file lands, so it must be exactly the expected one.
                if (f.name != kind.fileName(region)) throw ManifestException("unexpected pack name ${f.name}")
                val regions = (p["regions"] as? List<*>)?.map { r ->
                    (r as? String)?.takeIf { REGION_ID.matches(it) } ?: throw ManifestException("bad region '$r' for ${f.name}")
                }
                ReleasePack(kind, region, f, regions)
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
         * [basemapRegions], a voice pack for each voice in [voices] (none
         * unless the user picked one: they're big), the free Wi-Fi pack only when [wifi] (the user turned it on in
         * Settings > Maps), a pack that names its regions (southbound-v2.json's routing and search packs) when one of
         * them is picked or it names none (routing-main.tar: every phone), and
         * every other pack the release offers (southbound.json's statewide
         * routing and search, NYC transit), restricted to [wanted] kinds.
         */
        fun wanted(
            manifest: ReleaseManifest,
            basemapRegions: Set<String>,
            wanted: Set<PackKind> = PackKind.entries.toSet(),
            voices: Set<String> = emptySet(),
            wifi: Boolean = false,
        ): List<ReleasePack> =
            manifest.packs.filter { pack ->
                pack.kind in wanted && when (pack.kind) {
                    PackKind.BASEMAP -> pack.region in basemapRegions
                    PackKind.VOICE -> pack.region in voices
                    PackKind.WIFI -> wifi
                    else -> pack.regions?.let { it.isEmpty() || it.any { r -> r in basemapRegions } } ?: true
                }
            }

        /**
         * What adding [added] to the picked [basemapRegions] downloads, in bytes: the packs the regions together
         * want that the picked ones alone don't (a routing pack two of them share counts once, and not at all if
         * a picked region has it already).
         */
        fun addedBytes(manifest: ReleaseManifest, basemapRegions: Set<String>, added: Set<String>): Long {
            val now = wanted(manifest, basemapRegions).map { it.file.name }.toSet()
            return wanted(manifest, basemapRegions + added).filter { it.file.name !in now }.sumOf { it.file.size }
        }

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
            voices: Set<String> = emptySet(),
            wifi: Boolean = false,
        ): UpdatePlan = UpdatePlan(
            packs = wanted(manifest, basemapRegions, wanted, voices, wifi).filter { installed[it.kind to it.region] != it.file.sha256 },
            app = manifest.app?.takeIf { includeApp && manifest.versionCode > installedVersionCode },
        )
    }
}
