package app.southbound.core.updates

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Runs an update round in the background. Once there's something to download
 * it becomes a foreground job with a progress notification, so a large pack
 * download survives the app being closed.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val asked = inputData.getBoolean(UpdateScheduler.KEY_ASKED, false)
        val result = Updater(applicationContext).run(asked = asked, beforeDownload = { plan ->
            runCatching { setForeground(foregroundInfo(0, plan.bytes)) }
        })
        return when {
            // Only when the connection failed: a missing pack, no space or a refused APK won't change in a minute.
            result is UpdateState.Failed && result.retry && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Long, total: Long) = ForegroundInfo(
        UpdateNotifications.PROGRESS_ID,
        UpdateNotifications.progressNotification(applicationContext, done, total),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
