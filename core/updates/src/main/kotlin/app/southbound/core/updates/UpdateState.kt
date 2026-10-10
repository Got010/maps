package app.southbound.core.updates

/** Where the updater is, for Settings and the first-run screen. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** Fetching [file], [done] of [total] bytes; [overallDone] of [overallTotal] across the whole round. */
    data class Downloading(
        val file: String,
        val done: Long,
        val total: Long,
        val overallDone: Long,
        val overallTotal: Long,
    ) : UpdateState

    /** A new app version is downloaded and checked; Android is asking the user to confirm. */
    data class AwaitingInstall(val version: String) : UpdateState

    data class UpToDate(val version: String) : UpdateState

    /** Finished a round: what was installed. */
    data class Updated(val packs: List<String>, val appVersion: String?) : UpdateState

    data class Failed(val message: String, val needsToken: Boolean = false) : UpdateState
}

/** Plain words for failures, shown in Settings. */
object UpdateErrors {
    fun forHttp(code: Int, hasToken: Boolean): UpdateState.Failed = when {
        // The repository is public (October 2026): updates need no token. One saved earlier is still sent, so a
        // stale one is the likely cause of a refusal.
        code == 404 && !hasToken -> UpdateState.Failed("GitHub answered Not Found: there's no release yet. Southbound will try again later.")
        code == 404 -> UpdateState.Failed("GitHub answered Not Found. Updates need no token: remove the saved one (Settings > Updates).", needsToken = true)
        code == 401 -> UpdateState.Failed("GitHub refused the token. It may have expired: remove it (updates need none) or paste a new one.", needsToken = true)
        code == 403 && !hasToken -> UpdateState.Failed("GitHub turned the request away (its limit without a token). Try again in an hour.")
        code == 403 -> UpdateState.Failed("GitHub refused the token. Updates need none: remove it (Settings > Updates).", needsToken = true)
        code in 500..599 -> UpdateState.Failed("GitHub had a problem ($code). Southbound will try again later.")
        else -> UpdateState.Failed("GitHub answered $code.")
    }

    const val NO_CONNECTION = "Couldn't reach GitHub. Southbound will try again when it's online."
}
