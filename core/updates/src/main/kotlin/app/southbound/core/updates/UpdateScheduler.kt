package app.southbound.core.updates

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
    private const val RECHECK_HOURS = 6L

    fun allowMobileData(context: Context): Boolean = prefs(context).getBoolean(KEY_MOBILE, false)

    fun setAllowMobileData(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOBILE, allowed).apply()
        schedule(context)
    }

    /** POSIX millis of the last successful check, or 0. */
    fun lastCheck(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0)

    fun latestVersion(context: Context): String? = prefs(context).getString(KEY_LATEST, null)

    internal fun recordCheck(context: Context, version: String) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).putString(KEY_LATEST, version).apply()
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

    /** Checks right away; [anyNetwork] lets a download the user asked for use mobile data. */
    fun checkNow(context: Context, anyNetwork: Boolean) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(constraints(context, anyNetwork))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        // A tap that widens the network may replace a round waiting for Wi-Fi; otherwise let a running one finish.
        val policy = if (anyNetwork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, request)
    }

    private fun constraints(context: Context, anyNetwork: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (anyNetwork || allowMobileData(context)) NetworkType.CONNECTED else NetworkType.UNMETERED)
        .setRequiresStorageNotLow(true)
        .build()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
