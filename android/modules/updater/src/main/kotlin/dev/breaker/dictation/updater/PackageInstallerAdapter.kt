package dev.breaker.dictation.updater

import android.content.IntentSender
import android.content.pm.PackageInstaller
import java.io.IOException

/** Asks the platform whether a session the module recorded is still held, and whether a commit was made for it. */
internal class PlatformSessionQuery(private val installer: PackageInstaller) : SessionQuery {
    override fun exists(sessionId: Int): Boolean = installer.getSessionInfo(sessionId) != null

    override fun isCommitted(sessionId: Int): Boolean = installer.getSessionInfo(sessionId)?.isCommitted == true
}

/**
 * Abandons a platform session. An IOException or a SecurityException is passed on; any other failure of the platform
 * is reported as an IOException, as the port requires.
 */
internal class PlatformSessionAbandon(private val installer: PackageInstaller) : SessionAbandon {
    override fun abandon(sessionId: Int) {
        try {
            installer.abandonSession(sessionId)
        } catch (error: SecurityException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("the platform session could not be abandoned", error)
        }
    }
}

/**
 * Hands the staged file to the platform installer through one session. An attempt that fails before the commit is
 * ended: its session is abandoned, and the record is cleared when the abandon went through. Once the commit has been
 * called nothing is abandoned, because the platform may already hold the session for installation.
 */
internal class PackageInstallerAdapter(
    private val installer: PackageInstaller,
    private val packageName: String,
    private val commitSender: IntentSender,
    private val record: InstallRecord,
) : PackageInstallerPort {

    override fun install(staged: StagedApk, expectedSha256: String, capBytes: Long): InstallerResult {
        abandonRecordedSession()
        val sessionId = try {
            installer.createSession(sessionParams())
        } catch (error: SecurityException) {
            return InstallerResult.Refused
        } catch (error: IOException) {
            return InstallerResult.Failed
        }
        val session = try {
            record.sessionOpened(sessionId)
            installer.openSession(sessionId)
        } catch (error: Exception) {
            return endWithError(sessionId, error)
        }
        val copyFailure = try {
            sealChecked(session, staged, expectedSha256, capBytes)
        } catch (error: Exception) {
            session.close()
            return endWithError(sessionId, error)
        }
        if (copyFailure != null) {
            session.close()
            return endAttempt(sessionId, copyFailure)
        }
        return try {
            commit(session)
        } finally {
            session.close()
        }
    }

    /** The parameters of a full install of this app's package. */
    private fun sessionParams(): PackageInstaller.SessionParams {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(packageName)
        return params
    }

    /**
     * Copies the staged bytes into the session through the digest check and seals the session stream. Returns null when
     * the bytes matched and the stream is sealed, and otherwise the result that ends the attempt.
     */
    private fun sealChecked(session: PackageInstaller.Session, staged: StagedApk, expectedSha256: String, capBytes: Long): InstallerResult? {
        val input = staged.open() ?: return InstallerResult.Failed
        input.use { source ->
            session.openWrite(STAGED_FILE_NAME, 0L, -1L).use { out ->
                val failure = resultOfCheckedCopy(copyChecked(source, out, expectedSha256, capBytes))
                if (failure != null) return failure
                out.flush()
                session.fsync(out)
            }
        }
        return null
    }

    /** Commits the sealed session. A failure of the commit is reported as Failed, and the session is left as it is. */
    private fun commit(session: PackageInstaller.Session): InstallerResult =
        try {
            session.commit(commitSender)
            InstallerResult.Accepted
        } catch (error: IOException) {
            InstallerResult.Failed
        } catch (error: SecurityException) {
            InstallerResult.Failed
        }

    /**
     * Ends an attempt that failed with [error] after its session existed. A SecurityException refuses the install, an
     * IOException fails it, and any other failure ends the attempt as Failed and is then rethrown to the caller.
     */
    private fun endWithError(sessionId: Int, error: Exception): InstallerResult {
        if (error is SecurityException) return endAttempt(sessionId, InstallerResult.Refused)
        if (error is IOException) return endAttempt(sessionId, InstallerResult.Failed)
        endAttempt(sessionId, InstallerResult.Failed)
        throw error
    }

    /** Abandons the session, and clears the record only when the abandon went through; returns [result]. */
    private fun endAttempt(sessionId: Int, result: InstallerResult): InstallerResult {
        if (abandonQuietly(sessionId)) clearRecordQuietly()
        return result
    }

    /**
     * Abandons a session that a record still names before a new attempt. The engine clears the record before it allows an
     * install, so this step is the defensive step of the record rules and is not reached through the install gate.
     */
    private fun abandonRecordedSession() {
        val recorded = record.recordedSessionId() ?: return
        if (abandonQuietly(recorded)) clearRecordQuietly()
    }

    /** True when the platform accepted the abandon. Any failure means the session may still be held. */
    private fun abandonQuietly(sessionId: Int): Boolean =
        try {
            installer.abandonSession(sessionId)
            true
        } catch (error: Exception) {
            false
        }

    /**
     * Clears the record. A failed clear is ignored: the platform session is already gone, so the next start reads the
     * session as gone and clears the record itself.
     */
    private fun clearRecordQuietly() {
        try {
            record.clearRecord()
        } catch (error: IOException) {
            Unit
        }
    }
}
