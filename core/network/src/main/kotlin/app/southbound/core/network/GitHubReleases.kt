package app.southbound.core.network

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A file attached to a GitHub release. */
data class ReleaseAsset(val id: Long, val name: String, val size: Long)

data class GitHubRelease(val tag: String, val assets: List<ReleaseAsset>) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/**
 * Southbound's own releases on GitHub: the release list entry and its files.
 * Modelled on the Tunnels updater. Every request runs through [gate] under the
 * feature the caller names; requests carry nothing about the user (no
 * location, search or route), only which release file is wanted.
 *
 * Redirects are followed by hand so each hop is checked against
 * [isAllowed], and [token] (needed while the repository is private) only ever
 * goes to the API host, never to the download host GitHub redirects to.
 * Blocking: call off the main thread.
 */
class GitHubReleases(
    private val gate: NetworkGate,
    private val token: () -> String?,
    private val owner: String = OWNER,
    private val repo: String = REPO,
) {
    class HttpException(val code: Int) : IOException("GitHub answered HTTP $code")

    /** The newest full release (GitHub skips drafts and pre-releases here). */
    fun latest(feature: NetworkFeature): GitHubRelease = gate.request(feature) {
        val conn = open("https://$API_HOST/repos/$owner/$repo/releases/latest", "application/vnd.github+json")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
            parseRelease(conn.inputStream.use { readCapped(it, MAX_JSON_BYTES) })
        } finally {
            conn.disconnect()
        }
    }

    /** A small text file of a release, such as the manifest. */
    fun text(feature: NetworkFeature, asset: ReleaseAsset): String = gate.request(feature) {
        val conn = open(assetUrl(asset), "application/octet-stream")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
            conn.inputStream.use { readCapped(it, MAX_JSON_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams [asset] into [target] and returns its SHA-256 (lower-case hex).
     * The size must match the release's. [cancelled] is polled between reads.
     */
    fun download(
        feature: NetworkFeature,
        asset: ReleaseAsset,
        target: File,
        onProgress: (done: Long) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): String = gate.request(feature) {
        val conn = open(assetUrl(asset), "application/octet-stream")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (cancelled()) throw InterruptedIOException("Cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        done += n
                        if (asset.size in 1 until done) throw IOException("${asset.name} is larger than the release says")
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        onProgress(done)
                    }
                }
            }
            if (asset.size > 0 && done != asset.size) throw IOException("${asset.name} stopped after $done of ${asset.size} bytes")
            digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun assetUrl(asset: ReleaseAsset) = "https://$API_HOST/repos/$owner/$repo/releases/assets/${asset.id}"

    private fun open(start: String, accept: String): HttpURLConnection {
        var url = URL(start)
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowed(url.protocol, url.host)) throw IOException("Refused to connect to ${url.host}: not GitHub over HTTPS")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", NetworkGate.USER_AGENT)
                if (url.host.equals(API_HOST, ignoreCase = true)) {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    token()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
                }
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                url = URL(url, location ?: throw IOException("GitHub redirected without a target"))
                return@repeat
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val OWNER = "StrongHorse44"
        const val REPO = "maps"
        const val API_HOST = "api.github.com"
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_BYTES = 64 * 1024
        const val MAX_JSON_BYTES = 4 * 1024 * 1024

        /** Hosts a request may reach, including GitHub's download redirects. HTTPS only, checked on every hop. */
        fun isAllowed(scheme: String, host: String): Boolean {
            if (!scheme.equals("https", ignoreCase = true)) return false
            val h = host.lowercase()
            return h == API_HOST || h == "github.com" || h.endsWith(".githubusercontent.com")
        }

        /** GitHub's JSON for one release. */
        fun parseRelease(json: String): GitHubRelease {
            val r = MiniJson.parse(json) as? Map<*, *> ?: throw MiniJson.JsonException("a release object expected")
            val tag = r["tag_name"] as? String ?: throw MiniJson.JsonException("release without tag_name")
            val assets = (r["assets"] as? List<*>).orEmpty().mapNotNull { a ->
                val m = a as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
                val name = m["name"] as? String ?: return@mapNotNull null
                ReleaseAsset(id, name, (m["size"] as? Number)?.toLong() ?: -1L)
            }
            return GitHubRelease(tag, assets)
        }

        private fun readCapped(input: InputStream, max: Int): String {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > max) throw IOException("GitHub's answer is larger than expected")
                out.write(buffer, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
