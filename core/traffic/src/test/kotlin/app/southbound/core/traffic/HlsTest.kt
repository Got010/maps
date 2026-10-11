package app.southbound.core.traffic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [Hls]: from a camera's playlist to the newest segment. */
class HlsTest {
    private val master = "https://strmr5.sha.maryland.gov/rtplive/abc/playlist.m3u8"

    @Test
    fun `a master playlist gives its first variant`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1041000,CODECS="avc1.4d401f",RESOLUTION=640x480
            chunklist_w123.m3u8
        """.trimIndent()
        assertEquals(Hls.Next.Playlist("https://strmr5.sha.maryland.gov/rtplive/abc/chunklist_w123.m3u8"), Hls.next(master, playlist))
    }

    @Test
    fun `a media playlist gives its newest segment`() {
        val url = "https://strmr5.sha.maryland.gov/rtplive/abc/chunklist_w123.m3u8"
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXT-X-MEDIA-SEQUENCE:812
            #EXTINF:4.0,
            media_w123_812.ts
            #EXTINF:4.0,
            media_w123_813.ts
        """.trimIndent()
        assertEquals(Hls.Next.Segment("https://strmr5.sha.maryland.gov/rtplive/abc/media_w123_813.ts"), Hls.next(url, playlist))
    }

    @Test
    fun `only https links come back`() {
        assertNull(Hls.resolve(master, "http://strmr5.sha.maryland.gov/x.ts"))
        assertNull(Hls.resolve(master, "file:///etc/passwd"))
        assertNull(Hls.resolve(master, "bad link with spaces"))
        assertEquals("https://video.deldot.gov:443/live/A.stream/media_1.ts", Hls.resolve("https://video.deldot.gov:443/live/A.stream/chunklist.m3u8", "media_1.ts"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `something that isn't a playlist is refused`() {
        Hls.next(master, "<html>not found</html>")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty playlist is refused`() {
        Hls.next(master, "#EXTM3U\n#EXT-X-ENDLIST\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a cleartext segment is refused`() {
        Hls.next(master, "#EXTM3U\n#EXTINF:4.0,\nhttp://other.example.com/a.ts\n")
    }
}
