package app.southbound.core.updates

/**
 * What a downloaded APK must be before Southbound hands it to the installer:
 * this app, newer, the published file, and signed by the same key (or a key
 * the installed one rotated to). Same rules as the Tunnels updater.
 */
object ApkCheck {
    sealed interface Verdict {
        data object Ok : Verdict
        data class Refused(val reason: String) : Verdict
    }

    /**
     * [installedSigners]: SHA-256 digests of the installed app's current
     * signing certificates. [apkSigners]: the download's current ones.
     * [apkHistory]: the download's signing lineage, empty when it has none.
     */
    fun verify(
        packageName: String,
        installedVersionCode: Long,
        installedSigners: Set<String>,
        apkPackage: String?,
        apkVersionCode: Long,
        apkSigners: Set<String>,
        apkHistory: List<String>,
        expectedSha256: String,
        actualSha256: String,
    ): Verdict = when {
        !expectedSha256.equals(actualSha256, ignoreCase = true) ->
            Verdict.Refused("The download doesn't match the checksum published with the release.")
        apkPackage == null -> Verdict.Refused("The download isn't an Android app.")
        apkPackage != packageName -> Verdict.Refused("The download is $apkPackage, not Southbound.")
        apkVersionCode <= installedVersionCode ->
            Verdict.Refused("The download is version $apkVersionCode, not newer than the installed $installedVersionCode.")
        installedSigners.isEmpty() || apkSigners.isEmpty() -> Verdict.Refused("The signing certificates couldn't be read.")
        apkSigners == installedSigners || installedSigners.all { it in apkHistory } -> Verdict.Ok
        else -> Verdict.Refused("The download is signed with a different key than the installed Southbound.")
    }
}
