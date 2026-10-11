package app.southbound.core.traffic

import java.net.URI
import java.net.URISyntaxException

/**
 * Just enough of HLS (RFC 8216) to show one frame of a camera that only streams video (CHART's and DelDOT's, both
 * Wowza servers): a master playlist names a media playlist (`chunklist_....m3u8`), which names the segments, the
 * newest last. The card fetches the newest segment through the gate and decodes a frame of it on the phone; the
 * player itself is never used, so every request still goes through NetworkGate.
 */
object Hls {
    /** Playlists are a few hundred bytes; a segment of a couple of seconds a few hundred KB. */
    const val MAX_PLAYLIST_BYTES = 256 * 1024
    const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024

    /** What a playlist points to next: another playlist (a master's variant), or the segment to show. */
    sealed interface Next {
        data class Playlist(val url: String) : Next
        data class Segment(val url: String) : Next
    }

    /**
     * Reads a playlist fetched from [url]: a master playlist gives its first variant; a media playlist its newest
     * (last) segment. Links are resolved against [url]; only https comes back.
     *
     * @throws IllegalArgumentException when it isn't a playlist or names nothing usable.
     */
    fun next(url: String, playlist: String): Next {
        val lines = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "#EXTM3U") { "not a video playlist" }
        val links = lines.filterNot { it.startsWith("#") }
        val master = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        val link = (if (master) links.firstOrNull() else links.lastOrNull()) ?: throw IllegalArgumentException("an empty video playlist")
        val resolved = resolve(url, link) ?: throw IllegalArgumentException("a video link that isn't https")
        return if (master) Next.Playlist(resolved) else Next.Segment(resolved)
    }

    /** [link] against [base], https only (Android blocks cleartext). */
    fun resolve(base: String, link: String): String? = try {
        URI(base).resolve(link).toString().takeIf { it.startsWith("https://") }
    } catch (e: URISyntaxException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
