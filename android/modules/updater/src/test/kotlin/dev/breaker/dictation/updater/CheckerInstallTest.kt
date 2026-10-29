package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.ReleaseInfo
import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UpdateInstallResult
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the install gate, the staged file checks and the answers of the install, each one a fixed sentence. */
internal class CheckerInstallTest {

    /** Runs a check on [rig] so that the default release is remembered, then empties the call log. */
    private fun checked(rig: CheckerRig): CheckerRig {
        rig.checker.check(false)
        rig.log.clear()
        return rig
    }

    @Test
    fun `an install before any check is refused as not checked`() {
        val rig = CheckerRig()
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), result)
        assertEquals(listOf("clock"), rig.log.snapshot())
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `a release that is not the remembered one never reaches the download or the installer`() {
        val rig = checked(CheckerRig())
        val result = rig.checker.install(defaultRelease().copy(version = "1.2.1"))
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), result)
        assertEquals(listOf("clock"), rig.log.snapshot())
        assertFalse(rig.log.snapshot().any { it.startsWith("download:") })
        assertFalse(rig.log.snapshot().any { it.startsWith("installer.") })
    }

    @Test
    fun `a failed later check keeps the last verified release for the install`() {
        val rig = checked(CheckerRig())
        rig.manifestWith(manifestJson(signature = ManifestVectors.SIG_UNPINNED))
        rig.auth.session = adminSession()
        assertEquals(UpdateCheckResult.Failed(UpdateSentences.SIGNATURE), rig.checker.check(true))
        rig.log.clear()
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertEquals(1, rig.installer.calls)
    }

    @Test
    fun `an installed build that is not older is refused as not newer`() {
        val rig = checked(CheckerRig())
        rig.installed.build = InstalledBuild(versionCode = 12, versionName = INSTALLED_NAME)
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_NEWER), result)
        assertEquals(listOf("clock", "installed.current"), rig.log.snapshot())
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `a digest mismatch refuses the install and discards the staged file`() {
        val rig = checked(CheckerRig())
        rig.stageBytes("tampered payload".toByteArray(Charsets.UTF_8))
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.DIGEST), result)
        assertEquals(0, rig.installer.calls)
        assertEquals(
            listOf("clock", "installed.current", "download:$APK_URL", "stream.open", "stream.close", "discard"),
            rig.log.snapshot(),
        )
    }

    @Test
    fun `an empty staged file is refused as empty`() {
        val rig = checked(CheckerRig())
        rig.stageBytes(ByteArray(0))
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.EMPTY), result)
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `a staged file over the byte cap is refused as too large`() {
        val rig = CheckerRig()
        rig.apkByteCap = 10L
        checked(rig)
        rig.stageBytes(ByteArray(11))
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.APK_TOO_LARGE), result)
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `a staged file of exactly the byte cap is judged by its digest`() {
        val rig = CheckerRig()
        rig.apkByteCap = 10L
        checked(rig)
        rig.stageBytes(ByteArray(10))
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.DIGEST), result)
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `an unreadable staged file gives a failure`() {
        val rig = checked(CheckerRig())
        rig.stageBroken()
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Failed(UpdateSentences.READ), result)
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `a staged file that cannot be opened gives a failure`() {
        val rig = checked(CheckerRig())
        rig.stageUnopenable()
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Failed(UpdateSentences.OPEN), result)
        assertEquals(0, rig.installer.calls)
    }

    @Test
    fun `an unavailable download gives a failure`() {
        val rig = checked(CheckerRig())
        rig.downloads.next = ApkDownload.Unavailable
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Failed(UpdateSentences.DOWNLOAD), result)
        assertEquals(listOf("clock", "installed.current", "download:$APK_URL"), rig.log.snapshot())
    }

    @Test
    fun `an installer refusal is a refusal and records no update`() {
        val rig = checked(CheckerRig())
        rig.installer.outcome = InstallerResult.Refused
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.INSTALLER_REFUSED), result)
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordUpdate") })
    }

    @Test
    fun `an installer failure is a failure and records no update`() {
        val rig = checked(CheckerRig())
        rig.installer.outcome = InstallerResult.Failed
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Failed(UpdateSentences.INSTALLER_FAILED), result)
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordUpdate") })
    }

    @Test
    fun `a signed empty release is refused as empty although its digest matches`() {
        val rig = CheckerRig()
        val emptyRelease = ReleaseInfo(
            version = "1.2.0",
            apkUrl = APK_URL,
            sha256 = EmptyFileVector.EMPTY_DIGEST,
            signature = EmptyFileVector.SIG_CURRENT_EMPTY,
        )
        rig.manifestWith(
            manifestJson(
                sha256 = EmptyFileVector.EMPTY_DIGEST,
                signature = EmptyFileVector.SIG_CURRENT_EMPTY,
            ),
        )
        assertEquals(UpdateCheckResult.Available(emptyRelease), rig.checker.check(false))
        assertEquals(
            StreamDigestResult.Matched(0L),
            verifyStreamDigest(ByteArrayInputStream(ByteArray(0)), EmptyFileVector.EMPTY_DIGEST, MAX_APK_BYTES),
        )
        rig.stageBytes(ByteArray(0))
        rig.log.clear()
        val result = rig.checker.install(emptyRelease)
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.EMPTY), result)
        assertEquals(0, rig.installer.calls)
        assertFalse(rig.log.snapshot().contains("installer.install"))
        assertTrue(rig.log.snapshot().contains("discard"))
    }
}
