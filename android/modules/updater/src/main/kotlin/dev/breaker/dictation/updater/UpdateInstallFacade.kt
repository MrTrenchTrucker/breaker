package dev.breaker.dictation.updater

import android.content.Context
import android.content.IntentSender
import dev.breaker.dictation.core.port.AuthService
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.UpdateChecker
import java.io.File

/**
 * The install module as the app uses it: the update checker, with the install gate in front of each install; the start
 * calls; and the commit results. The app keeps one instance, made by the companion's create function.
 */
public class UpdateInstallFacade private constructor(
    /** The update checker: the daily check, and the install, which the start rules gate before the platform sees it. */
    public val updates: UpdateChecker,
    private val start: StartRun,
    private val commits: CommitRouter,
) {
    /**
     * The factory of the facade. The companion is unnamed, so the language gives it the implicit name Companion; the
     * create function is the only member of the companion.
     */
    public companion object {
        /**
         * Builds the facade for one ledger file, and is called once per process. A second call for the same ledger file
         * throws IllegalStateException. The call also removes the staged file in [stagingDir] and the stale temporary
         * files that an interrupted ledger write left beside [ledgerFile].
         */
        public fun create(context: Context, ledgerFile: File, stagingDir: File, serverBase: String, pinnedKeys: List<Pair<String, String>>, clock: Clock, auth: AuthService, confirmation: ConfirmationPort, commitSender: IntentSender): UpdateInstallFacade {
            CreateGuard.claim(ledgerFile.absolutePath)
            try {
                val ledger = LedgerStore(ledgerFile, clock)
                val installed = PackageInfoInstalledVersion(context)
                val installer = context.packageManager.packageInstaller
                val opener = JdkUpdateHttpOpener()
                val slot = ApkStaging(stagingDir)
                val keys = pinnedKeys.map { PinnedKey(it.first, it.second) }
                val checker = ReleaseUpdateChecker(
                    clock,
                    auth,
                    ledger,
                    ManifestHttpSource(serverBase, opener),
                    HttpApkDownloader(opener, slot),
                    PackageInstallerAdapter(installer, context.packageName, commitSender, ledger),
                    installed,
                    keys,
                )
                val start = StartRun(
                    clock,
                    ledger,
                    installed,
                    PlatformSessionQuery(installer),
                    PlatformSessionAbandon(installer),
                    LogcatDropLog(),
                )
                slot.removeQuietly()
                sweepStaleLedgerTemps(ledgerFile)
                return UpdateInstallFacade(InstallGate(checker, start), start, CommitRouter(confirmation))
            } catch (error: Throwable) {
                CreateGuard.release(ledgerFile.absolutePath)
                throw error
            }
        }
    }

    /**
     * The commit receiver of the app calls this for a pending user action that carries a handle. The confirmation port is
     * started for that handle, and nothing else happens.
     */
    public fun onCommitResult(status: CommitStatus, handle: ConfirmationHandle?) {
        commits.onResult(status, handle)
    }

    /** The app calls this at each start and on resume, before each daily check, and after a pending confirmation. */
    public fun onStart(): StartResult = start.onStart()

    /**
     * The commit receiver of the app calls this for each final status, with the platform's session id. A status whose
     * session id is not the recorded one is dropped: nothing changes and nothing is shown.
     */
    public fun onFinalStatus(sessionId: Int, status: CommitStatus): StartResult = start.onFinalStatus(sessionId, status)

    /** The app calls this for the user's dismiss action. */
    public fun onDismiss(): StartResult = start.onDismiss()
}
