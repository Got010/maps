package app.southbound.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitHubReleasesTest {

    @Test
    fun parsesTheLatestRelease() {
        val json = """
            {"tag_name":"v0.1.1","name":"Southbound 0.1.1","draft":false,"prerelease":false,
             "assets":[
               {"id":11,"name":"southbound-0.1.1.apk","size":104857600,"browser_download_url":"https://github.com/x"},
               {"id":12,"name":"southbound.json","size":900},
               {"name":"no id"}
             ]}
        """.trimIndent()
        val release = GitHubReleases.parseRelease(json)
        assertEquals("v0.1.1", release.tag)
        assertEquals(ReleaseAsset(11, "southbound-0.1.1.apk", 104_857_600), release.asset("southbound-0.1.1.apk"))
        assertEquals(2, release.assets.size)
        assertNull(release.asset("routing-nyc.tar"))
    }

    @Test
    fun onlyGitHubOverHttps() {
        assertTrue(GitHubReleases.isAllowed("https", "api.github.com"))
        assertTrue(GitHubReleases.isAllowed("https", "objects.githubusercontent.com"))
        assertTrue(GitHubReleases.isAllowed("https", "release-assets.githubusercontent.com"))
        assertFalse(GitHubReleases.isAllowed("http", "api.github.com"))
        assertFalse(GitHubReleases.isAllowed("https", "githubusercontent.com.evil.example"))
        assertFalse(GitHubReleases.isAllowed("https", "example.com"))
    }

    @Test
    fun aStoppedDownloadCarriesOnWithTheWholeFilesChecksum() {
        val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val asset = ReleaseAsset(7, "search-hudson.sqlite", bytes.size.toLong())
        val whole = sha256(bytes)
        val target = File.createTempFile("southbound", ".part")
        // The first run stopped after 100 000 bytes; the next one gets the rest (an HTTP range).
        target.writeBytes(bytes.copyOfRange(0, 100_000))
        val progress = mutableListOf<Long>()
        val sha = GitHubReleases.receive(bytes.copyOfRange(100_000, bytes.size).inputStream(), target, 100_000, asset, { progress += it })
        assertEquals(whole, sha)
        assertTrue(bytes.contentEquals(target.readBytes()))
        assertEquals(bytes.size.toLong(), progress.last())
        assertTrue(progress.first() > 100_000)
    }

    @Test
    fun aFreshDownloadReplacesWhatWasThere() {
        val bytes = ByteArray(1_000) { it.toByte() }
        val asset = ReleaseAsset(7, "transit-nyc.sqlite", bytes.size.toLong())
        val target = File.createTempFile("southbound", ".part")
        target.writeBytes(ByteArray(5_000) { 9 })
        val sha = GitHubReleases.receive(bytes.inputStream(), target, 0, asset)
        assertEquals(sha256(bytes), sha)
        assertTrue(bytes.contentEquals(target.readBytes()))
    }

    @Test
    fun theSizeStillHasToMatchTheRelease() {
        val asset = ReleaseAsset(7, "routing-main.tar", 10)
        val target = File.createTempFile("southbound", ".part")
        target.writeBytes(ByteArray(4))
        val longer = runCatching { GitHubReleases.receive(ByteArray(7).inputStream(), target, 4, asset) }.exceptionOrNull()
        assertTrue("$longer", longer?.message?.contains("larger") == true)
        target.writeBytes(ByteArray(4))
        val shorter = runCatching { GitHubReleases.receive(ByteArray(3).inputStream(), target, 4, asset) }.exceptionOrNull()
        assertTrue("$shorter", shorter?.message?.contains("stopped after 7 of 10") == true)
    }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun turnedOffMeansNoRequest() {
        val toggles = InMemoryNetworkToggles(mapOf(NetworkFeature.PACK_DOWNLOADS to false))
        val client = GitHubReleases(NetworkGate(toggles), { "token" })
        val error = runCatching { client.latest(NetworkFeature.PACK_DOWNLOADS) }.exceptionOrNull()
        assertTrue(error is NetworkFeatureDisabledException)
        val download = runCatching {
            client.download(NetworkFeature.APP_UPDATES, ReleaseAsset(1, "southbound-0.1.1.apk", 3), File.createTempFile("southbound", ".apk"))
        }.exceptionOrNull()
        assertTrue("$download", download is NetworkFeatureDisabledException)
    }
}
