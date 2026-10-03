package app.southbound.core.updates

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** The updater's notifications: download progress, and "tap to install" when Android wants a confirmation. */
object UpdateNotifications {
    const val PROGRESS_ID = 4101
    private const val CONFIRM_ID = 4102
    private const val CHANNEL = "updates"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    fun progressNotification(context: Context, done: Long, total: Long): Notification {
        ensureChannel(context)
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.updates_downloading))
            .setContentText(context.getString(R.string.updates_progress, done shr 20, total shr 20))
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun progress(context: Context, done: Long, total: Long) {
        if (!allowed(context)) return
        context.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(context, done, total))
    }

    fun confirmInstall(context: Context, confirm: Intent) {
        if (!allowed(context)) return
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context, 0, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.updates_ready))
            .setContentText(context.getString(R.string.updates_ready_body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(CONFIRM_ID, notification)
    }

    fun cancelProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID)
    }

    fun cancelConfirm(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CONFIRM_ID)
    }

    private fun allowed(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
