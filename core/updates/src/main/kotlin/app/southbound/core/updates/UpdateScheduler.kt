package app.southbound.core.updates

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

/**
 * When updates run: once a day in the background, and at app start if the
 * last check is more than [RECHECK_HOURS] old. Big downloads wait for Wi-Fi
 * unless the user allows mobile data.
 */
object UpdateScheduler {
    private const val DAILY = "southbound-updates-daily"
    private const val NOW = "southbound-updates-now"
    private const val PREFS = "updates"
    private const val KEY_MOBILE = "allow_mobile_data"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LATEST = "latest_version"
    private const val KEY_PENDING = "pending_install_version"
    private const val KEY_PENDING_SINCE = "pending_install_since"

    /** Input of a round the person started (Check now, Download now), which asks again for a pending update. */
    const val KEY_ASKED = "asked"
    private const val RECHECK_HOURS = 6L

    fun allowMobileData(context: Context): Boolean = prefs(context).getBoolean(KEY_MOBILE, false)

    fun setAllowMobileData(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOBILE, allowed).apply()
        schedule(context)
    }

    /** [setAllowMobileData] with a synchronous commit (an import). Throws [java.io.IOException] if the write fails. */
    fun replaceAllowMobileData(context: Context, allowed: Boolean) {
        if (!prefs(context).edit().putBoolean(KEY_MOBILE, allowed).commit()) throw java.io.IOException("Couldn't save the update options")
        schedule(context)
    }

    /** POSIX millis of the last successful check, or 0. */
    fun lastCheck(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0)

    fun latestVersion(context: Context): String? = prefs(context).getString(KEY_LATEST, null)

    internal fun recordCheck(context: Context, version: String) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putString(KEY_LATEST, version).apply()
    }

    /** The app update waiting for Android's confirmation and since when ([PendingInstall]); null when none. */
    internal fun pendingInstall(context: Context): Pair<String?, Long> =
        prefs(context).let { it.getString(KEY_PENDING, null) to it.getLong(KEY_PENDING_SINCE, 0) }

    internal fun recordPendingInstall(context: Context, version: String) {
        prefs(context).edit().putString(KEY_PENDING, version).putLong(KEY_PENDING_SINCE, System.currentTimeMillis()).apply()
    }

    internal fun clearPendingInstall(context: Context) {
        prefs(context).edit().remove(KEY_PENDING).remove(KEY_PENDING_SINCE).apply()
    }

    /** Call at app start: keeps the daily job and checks now if it's been a while. */
    fun onAppStart(context: Context) {
        schedule(context)
        val stale = System.currentTimeMillis() - lastCheck(context) > TimeUnit.HOURS.toMillis(RECHECK_HOURS)
        if (stale) checkNow(context, anyNetwork = false)
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints(context, anyNetwork = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * GrapheneOS's Network toggle revokes INTERNET. Then WorkManager sees no
     * network and an update would wait forever, so say so instead.
     */
    fun networkPermitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    /** Whether a round is queued (waiting for a network, Wi-Fi or a retry), running, or neither. */
    enum class Queue { IDLE, WAITING, RUNNING }

    fun queue(context: Context): Flow<Queue> {
        val work = WorkManager.getInstance(context)
        return combine(work.getWorkInfosForUniqueWorkFlow(NOW), work.getWorkInfosForUniqueWorkFlow(DAILY)) { now, daily ->
            val all = now + daily
            when {
                all.any { it.state == WorkInfo.State.RUNNING } -> Queue.RUNNING
                now.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> Queue.WAITING
                else -> Queue.IDLE
            }
        }
    }

    /** Checks right away; [anyNetwork] lets a download the user asked for use mobile data. */
    fun checkNow(context: Context, anyNetwork: Boolean) {
        if (!networkPermitted(context)) {
            UpdateStatus.set(UpdateState.Failed(NO_NETWORK_PERMISSION))
            return
        }
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            // A tap that widens the network is the person asking: a pending app update is offered again.
            .setInputData(workDataOf(KEY_ASKED to anyNetwork))
            .setConstraints(constraints(context, anyNetwork))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // A tap that widens the network may replace a round waiting for Wi-Fi; otherwise let a running one finish.
        val policy = if (anyNetwork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
    }

    // No "storage not low" constraint: on a nearly full phone Android would
    // never start the job, and nothing would say why. The updater checks free
    // space itself and reports exactly how much the update needs.
    private fun constraints(context: Context, anyNetwork: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (anyNetwork || allowMobileData(context)) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .build()

    const val NO_NETWORK_PERMISSION =
        "Southbound's Network permission is off, so it can't reach GitHub. Turn it on in Settings > Apps > " +
            "Southbound > Permissions to update, then off again if you like."

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
