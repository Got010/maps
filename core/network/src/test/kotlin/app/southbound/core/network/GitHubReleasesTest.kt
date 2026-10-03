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
