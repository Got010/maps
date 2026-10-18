package app.southbound.core.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * Hands a checked APK to Android's installer, which always asks the person to
 * confirm: every update needs a tap on Android's own "Update Southbound?"
 * screen, so no new version runs on the phone unseen. [InstallResultReceiver]
 * brings up that confirmation (over the app, or as a "tap to install"
 * notification).
 */
object AppInstaller {

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android's confirmation every time, even once Southbound is its own installer of record.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 1 shl 20)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                // Mutable: the installer adds the result extras.
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } finally {
            // The session keeps its own copy.
            apk.delete()
        }
    }
}

/** The installer's answer: ask the user, or report a failure. On success Android restarts the app. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                UpdateStatus.requestConfirm(confirm)
                UpdateNotifications.confirmInstall(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                UpdateScheduler.clearPendingInstall(context)
                UpdateStatus.set(UpdateState.Idle)
            }
            else -> {
                // Dismissed or refused: the next round offers it again.
                UpdateScheduler.clearPendingInstall(context)
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val text = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "The update was cancelled. Southbound will offer it again next time."
                } else {
                    "Android didn't install the update" + (message?.let { ": $it" } ?: ".")
                }
                UpdateStatus.set(UpdateState.Failed(text))
                UpdateNotifications.cancelConfirm(context)
            }
        }
    }
}
