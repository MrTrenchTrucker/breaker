package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateInstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the installGuard member: the refusals and failure it gives, the order of its checks, and that it writes nothing. */
internal class CheckerGuardTest {

    /** Runs a check on [rig] so that the default release is remembered, then empties the call log. */
    private fun checked(rig: CheckerRig): CheckerRig {
        rig.checker.check(false)
        rig.log.clear()
        return rig
    }

    /** Asserts that the call record holds no ledger write, no installer call, no download, no stream and no discard. */
    private fun assertNoEffects(rig: CheckerRig) {
        val prefixes = listOf("ledger.", "installer.", "download:", "stream.", "discard", "manifest.")
        val effects = rig.log.snapshot().filter { entry -> prefixes.any { entry.startsWith(it) } }
        assertEquals(emptyList<String>(), effects)
    }

    @Test
    fun `installGuard refuses an install before any check and a release that is not the checked one`() {
        val fresh = CheckerRig()
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), fresh.checker.installGuard(defaultRelease()))
        assertEquals(emptyList<String>(), fresh.log.snapshot())

        val other = checked(CheckerRig())
        val result = other.checker.installGuard(defaultRelease().copy(version = "1.2.1"))
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), result)
        assertEquals(emptyList<String>(), other.log.snapshot())
    }

    @Test
    fun `installGuard fails with the unreadable sentence when the installed version cannot be read`() {
        val rig = checked(CheckerRig())
        rig.installed.reading = InstalledReading.Unreadable
        val result = rig.checker.installGuard(defaultRelease())
        assertEquals(UpdateInstallResult.Failed(UpdateSentences.INSTALLED_UNREADABLE), result)
        assertEquals(listOf("installed.current"), rig.log.snapshot())
    }

    @Test
    fun `installGuard refuses a release that is not newer than the installed build`() {
        val rig = checked(CheckerRig())
        rig.installed.build = InstalledBuild(versionCode = 12, versionName = INSTALLED_NAME)
        val result = rig.checker.installGuard(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_NEWER), result)
        assertEquals(listOf("installed.current"), rig.log.snapshot())
    }

    @Test
    fun `installGuard returns null for a checked, readable and newer release and reads the installed version once`() {
        val rig = checked(CheckerRig())
        val result = rig.checker.installGuard(defaultRelease())
        assertTrue("a checked, readable and newer release must pass the guard", result == null)
        assertEquals(listOf("installed.current"), rig.log.snapshot())
    }

    @Test
    fun `installGuard gives the not checked refusal when the release is unchecked and unreadable`() {
        val rig = CheckerRig()
        rig.installed.reading = InstalledReading.Unreadable
        val result = rig.checker.installGuard(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), result)
        assertEquals(emptyList<String>(), rig.log.snapshot())
    }

    @Test
    fun `a refusing installGuard writes no ledger record and calls no installer or download`() {
        val unchecked = CheckerRig()
        val unrememberedResult = unchecked.checker.installGuard(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), unrememberedResult)
        assertNoEffects(unchecked)

        val otherRelease = checked(CheckerRig())
        val otherResult = otherRelease.checker.installGuard(defaultRelease().copy(version = "1.2.1"))
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), otherResult)
        assertNoEffects(otherRelease)

        val notNewer = checked(CheckerRig())
        notNewer.installed.build = InstalledBuild(versionCode = 12, versionName = INSTALLED_NAME)
        val notNewerResult = notNewer.checker.installGuard(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_NEWER), notNewerResult)
        assertNoEffects(notNewer)
    }

    @Test
    fun `install after a null installGuard gives the same installed result and the same call record`() {
        val rig = checked(CheckerRig())
        assertTrue("the guard must pass before install is called", rig.checker.installGuard(defaultRelease()) == null)
        rig.log.clear()
        assertEquals(UpdateInstallResult.Installed, rig.checker.install(defaultRelease()))
        assertEquals(
            listOf(
                "clock",
                "installed.current",
                "download:$APK_URL",
                "stream.open",
                "stream.close",
                "installer.install",
                "ledger.recordUpdate:$NOW_MILLIS",
                "discard",
            ),
            rig.log.snapshot(),
        )
    }
}
