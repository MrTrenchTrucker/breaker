package dev.breaker.dictation.wiring

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.breaker.dictation.updater.ApkInstaller
import dev.breaker.dictation.updater.InstallReasons
import dev.breaker.dictation.updater.InstallResult
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android adapter of the install port: hands one verified APK to the system installer through a
 * PackageInstaller session. The port only ever receives a file that the updater has already verified.
 *
 * Before a session is opened, the check for the "install unknown apps" permission runs; when it is off, the
 * adapter opens that settings page and answers Refused(NOT_ALLOWED).
 *
 * The confirm intent is started only when it resolves to an activity of a system package other than this
 * app. Otherwise the session is abandoned and nothing is started; the intent is never written to a log.
 *
 * The status receiver is registered when a session is committed and is removed when the last session this
 * adapter opened is finished or abandoned. The status PendingIntent is explicit: it is addressed to this
 * package by a private action name and carries the session id. It is FLAG_MUTABLE on API 31 and later,
 * because the system fills the status and the confirm intent into the intent it was given; before API 31
 * a PendingIntent is mutable by default, so no flag is added there.
 *
 * On API 33 and later the receiver is registered as not exported. Below API 33 the platform has no such
 * flag, so the receiver is registered the plain way and only a session id this adapter opened is accepted.
 *
 * This file names Android types, so the JVM cannot run it; its source pins are in UpdateInstallerGateTest,
 * and the device checks are listed with the record.
 */
class AndroidApkInstaller(private val context: Context) : ApkInstaller {

    private val statusAction: String = context.packageName + ".action.INSTALL_STATUS"
    private val ownSessions: MutableSet<Int> = ConcurrentHashMap.newKeySet<Int>()
    private val receiverOn: AtomicBoolean = AtomicBoolean(false)
    private val opener: PackageSessionOpener = PackageSessionOpener { openAdapterSession() }
    private val statusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receivedContext: Context, received: Intent) {
            onStatus(received)
        }
    }

    override fun launch(apk: File): InstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            openUnknownSourcesPage()
            return InstallResult.Refused(InstallReasons.NOT_ALLOWED)
        }
        return PackageInstallRun(opener).run(apk)
    }

    private fun openAdapterSession(): PackageSession {
        val installer: PackageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId: Int = installer.createSession(params)
        ownSessions.add(sessionId)
        val session: PackageInstaller.Session = try {
            installer.openSession(sessionId)
        } catch (e: Exception) {
            forget(sessionId)
            installer.abandonSession(sessionId)
            throw e
        }
        return SessionAdapter(sessionId, session)
    }

    /** The platform session behind the PackageSession port, for one session id this adapter opened. */
    private inner class SessionAdapter(
        private val sessionId: Int,
        private val session: PackageInstaller.Session,
    ) : PackageSession {

        override fun writeApk(input: InputStream, length: Long) {
            session.openWrite(APK_NAME, 0L, length).use { out: OutputStream ->
                input.copyTo(out, COPY_BUFFER_BYTES)
                out.flush()
                session.fsync(out)
            }
        }

        override fun commit() {
            ensureReceiver()
            session.commit(statusIntent(sessionId).intentSender)
        }

        override fun abandon() {
            try {
                session.abandon()
            } finally {
                forget(sessionId)
            }
        }

        override fun close() {
            session.close()
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(statusAction)
            .setPackage(context.packageName)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        return PendingIntent.getBroadcast(context, sessionId, intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun ensureReceiver() {
        if (!receiverOn.compareAndSet(false, true)) return
        val filter = IntentFilter(statusAction)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(statusReceiver, filter)
            }
        } catch (e: Exception) {
            receiverOn.set(false)
            throw e
        }
    }

    private fun onStatus(received: Intent) {
        val sessionId: Int = received.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, NO_SESSION_ID)
        val status: Int = received.getIntExtra(PackageInstaller.EXTRA_STATUS, NO_STATUS)
        val confirm: Intent? = confirmIntent(received)
        val ours: Boolean = ownSessions.contains(sessionId)
        when (InstallStatusRule.decide(status, confirm != null, ours)) {
            StatusAction.IGNORE -> Unit
            StatusAction.SHOW_CONFIRM -> showConfirm(sessionId, confirm)
            StatusAction.FINISHED -> forget(sessionId)
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(received: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            received.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            received.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun showConfirm(sessionId: Int, confirm: Intent?) {
        if (confirm == null) return
        try {
            if (!isSystemTarget(confirm)) {
                abandonById(sessionId)
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(confirm)
        } catch (e: Exception) {
            abandonById(sessionId)
        }
    }

    /**
     * True only when the confirm intent resolves to an activity of a system package that is not this app.
     * No resolution, no component, this app's own package and a package without the system flag answer false.
     */
    private fun isSystemTarget(confirm: Intent): Boolean {
        val target: ResolveInfo = context.packageManager.resolveActivity(confirm, 0) ?: return false
        val component: ActivityInfo = target.activityInfo ?: return false
        if (component.packageName == context.packageName) return false
        val owner: ApplicationInfo = component.applicationInfo ?: return false
        return (owner.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun abandonById(sessionId: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(sessionId)
        } catch (e: Exception) {
            // best effort: the session may already be gone
        } finally {
            forget(sessionId)
        }
    }

    /** Forgets one session id; the receiver is removed when no session of this adapter is left. */
    private fun forget(sessionId: Int) {
        ownSessions.remove(sessionId)
        if (ownSessions.isEmpty() && receiverOn.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(statusReceiver)
            } catch (e: Exception) {
                // best effort: the receiver is already gone
            }
        }
    }

    private fun openUnknownSourcesPage() {
        try {
            val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
            page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(page)
        } catch (e: Exception) {
            // best effort: the refusal is answered whether or not the settings page opens
        }
    }

    private companion object {
        const val APK_NAME: String = "update.apk"
        const val COPY_BUFFER_BYTES: Int = 65_536
        const val NO_SESSION_ID: Int = -1
        const val NO_STATUS: Int = Int.MIN_VALUE
    }
}
