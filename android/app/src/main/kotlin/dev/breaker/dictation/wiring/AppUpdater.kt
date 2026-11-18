package dev.breaker.dictation.wiring

import android.content.pm.PackageInfo
import android.content.res.AssetManager
import dev.breaker.dictation.BreakerApp
import dev.breaker.dictation.host.MainLooperPost
import dev.breaker.dictation.http.JdkHttpTransport
import dev.breaker.dictation.updater.InstallResult
import dev.breaker.dictation.updater.Updater
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's one updater: built once from the app's values and started once per process.
 *
 * The updater takes the server address, the admin session, the installed version from the package
 * manager, the key list from the app's assets, and the platform installer and timer. The check blocks,
 * so [start] runs it on its own background thread and never on the main thread. The key list is the
 * asset named update_keys.txt, written only when a key file is named; a missing or unreadable asset gives
 * an empty key list, and an empty key list refuses every update.
 *
 * This object names Android types, so its behaviour is covered by source pins in the app's source-scan tests,
 * and the device checks are listed with the record.
 */
object AppUpdater {
    private const val KEY_ASSET: String = "update_keys.txt"
    private const val UPDATE_START_THREAD: String = "update-start"
    private val starting: AtomicBoolean = AtomicBoolean(false)
    private val lock: Any = Any()
    private var built: Updater? = null

    /** The answer of the last install request, or null when no install was answered yet. */
    @Volatile
    var lastInstallResult: InstallResult? = null

    /** The one holder that carries an install answer to the About screen, handed over on the main thread. */
    internal val installAnswers: InstallAnswerRelay = InstallAnswerRelay(MainLooperPost())

    /** The app's updater, built on first use and kept for the life of the process. */
    fun updater(app: BreakerApp): Updater =
        synchronized(lock) {
            built ?: build(app).also { built = it }
        }

    /** Runs the updater's start (the first check and the daily timer) once per process, off the main thread. */
    fun start(app: BreakerApp) {
        if (!starting.compareAndSet(false, true)) return
        Thread(Runnable { runStart(app) }, UPDATE_START_THREAD).start()
    }

    private fun runStart(app: BreakerApp) {
        try {
            updater(app).start()
        } catch (e: Exception) {
            // best effort: the next app start tries again
        }
    }

    private fun build(app: BreakerApp): Updater =
        buildAppUpdater(
            transport = JdkHttpTransport(app.compositionRoot.serverUrl),
            serverUrl = app.compositionRoot.serverUrl,
            apkFolder = { File(app.cacheDir, "updates").also { it.mkdirs() } },
            pinnedKeys = UpdateKeyList.parse(keyListText(app)),
            isAdmin = { appIsAdmin(app.compositionRoot.addressedSession) },
            installedVersionCode = { installedInfo(app).longVersionCode },
            installedVersionName = { installedInfo(app).versionName ?: "" },
            installer = AndroidApkInstaller(app, onSessionFinished = { code -> installAnswers.reportFinished(code) }),
            times = UpdateTimesStore(File(app.filesDir, "updater")),
            nowMs = System::currentTimeMillis,
            scheduler = HandlerTimerScheduler(),
            onInstallResult = { result ->
                lastInstallResult = result
                installAnswers.report(result)
            },
        )

    private fun installedInfo(app: BreakerApp): PackageInfo {
        val info: PackageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        return info
    }

    private fun keyListText(app: BreakerApp): String =
        try {
            val assets: AssetManager = app.assets
            assets.open(KEY_ASSET).use { stream -> String(stream.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            ""
        }
}
