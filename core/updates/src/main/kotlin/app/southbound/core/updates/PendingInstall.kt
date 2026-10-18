package app.southbound.core.updates

/**
 * An app update handed to Android's installer that the person has neither confirmed nor dismissed yet. Every update
 * waits for Android's confirmation, so a daily round would otherwise download the same APK again and open another
 * installer session each day until the tap. While one is pending, a round leaves that version alone, until:
 * - the person confirms it (the app restarts on it, so it is no longer newer) or dismisses it (the installer says
 *   so, and the marker goes: [InstallResultReceiver]);
 * - a newer release names another version;
 * - the person asks for a check themselves (Check now), which asks again at once;
 * - [MAX_WAIT_MILLIS] pass (a swiped-away notification says nothing), and it is asked again.
 */
object PendingInstall {
    const val MAX_WAIT_MILLIS = 3L * 24 * 60 * 60 * 1000

    /**
     * Whether a round should download and offer [offered] now. [pending] is the version waiting for an answer since
     * [since] (millis), or null; [asked] is a check the person started.
     */
    fun shouldDownload(pending: String?, since: Long, offered: String, now: Long, asked: Boolean): Boolean =
        asked || pending != offered || now - since !in 0 until MAX_WAIT_MILLIS
}
