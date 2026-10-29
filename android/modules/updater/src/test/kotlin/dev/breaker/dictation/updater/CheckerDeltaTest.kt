package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UpdateInstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A byte cap that differs from the built-in cap, so that a test can tell which one the checker passed on. */
private const val INSTALLER_TEST_CAP: Long = 1_048_576L

/** Pins the installer's digest answer, the reading of the installed version, and the list of fixed sentences. */
internal class CheckerDeltaTest {

    /** Runs a check on [rig] so that the default release is remembered, then empties the call log. */
    private fun checked(rig: CheckerRig): CheckerRig {
        rig.checker.check(false)
        rig.log.clear()
        return rig
    }

    @Test
    fun `a digest mismatch from the installer is refused with the digest sentence`() {
        val rig = checked(CheckerRig())
        rig.installer.outcome = InstallerResult.DigestMismatch
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.DIGEST), result)
        assertEquals(1, rig.installer.calls)
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordUpdate") })
        assertEquals(
            listOf(
                "clock",
                "installed.current",
                "download:$APK_URL",
                "stream.open",
                "stream.close",
                "installer.install",
                "discard",
            ),
            rig.log.snapshot(),
        )
    }

    @Test
    fun `an unreadable installed version stops check() before anything is remembered`() {
        val rig = CheckerRig()
        rig.installed.reading = InstalledReading.Unreadable
        assertEquals(UpdateCheckResult.Failed(UpdateSentences.INSTALLED_UNREADABLE), rig.checker.check(false))
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordDefinitive") })
        rig.log.clear()
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), rig.checker.install(defaultRelease()))
        assertEquals(listOf("clock"), rig.log.snapshot())
    }

    @Test
    fun `an unreadable installed version stops install() before recordUpdate`() {
        val rig = checked(CheckerRig())
        rig.installed.reading = InstalledReading.Unreadable
        assertEquals(
            UpdateInstallResult.Failed(UpdateSentences.INSTALLED_UNREADABLE),
            rig.checker.install(defaultRelease()),
        )
        assertEquals(listOf("clock", "installed.current"), rig.log.snapshot())
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `the installer receives the verified digest and the byte cap`() {
        val rig = CheckerRig()
        rig.apkByteCap = INSTALLER_TEST_CAP
        checked(rig)
        assertEquals(UpdateInstallResult.Installed, rig.checker.install(defaultRelease()))
        assertEquals(defaultRelease().sha256, rig.installer.receivedSha256)
        assertEquals(INSTALLER_TEST_CAP, rig.installer.receivedCap)
    }

    @Test
    fun `every new fixed sentence is listed in the fixed sentence list`() {
        val added = listOf(
            UpdateSentences.INSTALLED_UNREADABLE,
            UpdateSentences.CANCELLED,
            UpdateSentences.UNCONFIRMED,
            UpdateSentences.WAITING,
            UpdateSentences.NOT_FINISHED,
        )
        for (sentence in added) {
            assertTrue("a new fixed sentence is missing from the fixed sentence list", UpdateSentences.ALL.contains(sentence))
        }
    }
}
