package dev.breaker.dictation.wiring

import dev.breaker.dictation.updater.InstallReasons
import dev.breaker.dictation.updater.InstallResult
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * One package session as the install run drives it: write the APK, hand it to the system installer,
 * or throw the session away. The Android adapter implements it over the platform session; a JVM test
 * implements it with a fake.
 */
interface PackageSession {
    /** Copies the APK from [input] into the session; [length] is the byte count of the APK. */
    fun writeApk(input: InputStream, length: Long)

    /** Hands the written session to the system installer. */
    fun commit()

    /** Drops the session and what was written to it. */
    fun abandon()

    /** Releases the handle of the session. */
    fun close()
}

/** Opens one new package session. It may throw. */
fun interface PackageSessionOpener {
    fun open(): PackageSession
}

/**
 * Runs one install of a verified APK file: open a session, write the APK into it, commit it.
 *
 * Any failure after the session is open abandons the session and answers Refused(NOT_STARTED); a failing
 * abandon changes nothing. Close runs once in every path that has a session, after abandon or commit, and
 * its exception is swallowed. A failure to open answers Refused(NOT_STARTED) and closes nothing.
 * Started means the commit was handed to the system installer: the real outcome is the system dialog and
 * is not known here.
 *
 * Never throws.
 */
class PackageInstallRun(private val opener: PackageSessionOpener) {

    fun run(apk: File): InstallResult {
        val session: PackageSession = try {
            opener.open()
        } catch (e: Exception) {
            return InstallResult.Refused(InstallReasons.NOT_STARTED)
        }
        val answer: InstallResult = try {
            writeAndCommit(session, apk)
            InstallResult.Started
        } catch (e: Exception) {
            quietly { session.abandon() }
            InstallResult.Refused(InstallReasons.NOT_STARTED)
        }
        quietly { session.close() }
        return answer
    }

    /** Streams the file into the session, closing the file stream first, then commits. */
    private fun writeAndCommit(session: PackageSession, apk: File) {
        FileInputStream(apk).use { input -> session.writeApk(input, apk.length()) }
        session.commit()
    }

    private fun quietly(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            // best effort: the answer does not depend on it
        }
    }
}
