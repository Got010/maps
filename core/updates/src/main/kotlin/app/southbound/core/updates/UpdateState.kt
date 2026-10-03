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
        code == 404 && !hasToken -> UpdateState.Failed(
            "GitHub answered Not Found. The repository is private, so Southbound needs a read-only token " +
                "(Settings > Updates).",
            needsToken = true,
        )
        code == 404 -> UpdateState.Failed("GitHub answered Not Found: the token can't see the repository, or there's no release yet.", needsToken = true)
        code == 401 -> UpdateState.Failed("GitHub refused the token. It may have expired: paste a new one.", needsToken = true)
        code == 403 && !hasToken -> UpdateState.Failed("GitHub turned the request away (its limit without a token). Try again in an hour.")
        code == 403 -> UpdateState.Failed("GitHub refused access. The token needs read-only access to the repository's contents.", needsToken = true)
        code in 500..599 -> UpdateState.Failed("GitHub had a problem ($code). Southbound will try again later.")
        else -> UpdateState.Failed("GitHub answered $code.")
    }

    const val NO_CONNECTION = "Couldn't reach GitHub. Southbound will try again when it's online."
}
