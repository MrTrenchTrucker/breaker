package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins what a check does in each branch: the schedule gate, the attempt record, the verification and the answer. */
internal class CheckerCheckTest {

    /** The exact call order of a check that runs to the end with the default manifest and an install at code 11. */
    private fun fullCheckLog(now: Long): List<String> = listOf(
        "clock",
        "auth.currentSession",
        "ledger.lastDefinitive",
        "ledger.lastAttempt",
        "ledger.recordAttempt:$now",
        "manifest.load",
        "installed.current",
        "ledger.recordDefinitive:$now",
    )

    @Test
    fun `a check that is not due returns null and makes no fetch`() {
        val rig = CheckerRig()
        rig.ledger.definitive = NOW_MILLIS - 1L
        val result = rig.checker.check(false)
        assertNull(result)
        assertEquals(
            listOf("clock", "auth.currentSession", "ledger.lastDefinitive", "ledger.lastAttempt"),
            rig.log.snapshot(),
        )
    }

    @Test
    fun `an attempt is recorded once before the manifest is fetched`() {
        val rig = CheckerRig()
        rig.checker.check(false)
        assertEquals(fullCheckLog(NOW_MILLIS), rig.log.snapshot())
    }

    @Test
    fun `a forced call without a session is not forced`() {
        val rig = CheckerRig()
        rig.ledger.definitive = NOW_MILLIS - 1L
        val result = rig.checker.check(true)
        assertNull(result)
        assertFalse(rig.log.snapshot().contains("manifest.load"))
    }

    @Test
    fun `an administrator forced check runs although the day has not passed`() {
        val rig = CheckerRig()
        rig.auth.session = adminSession()
        rig.ledger.definitive = NOW_MILLIS - 1L
        val result = rig.checker.check(true)
        assertEquals(UpdateCheckResult.Available(defaultRelease()), result)
        assertTrue(rig.log.snapshot().contains("manifest.load"))
    }

    @Test
    fun `an unavailable server fails and keeps the last definitive check time`() {
        val rig = CheckerRig()
        rig.manifests.next = ManifestLoad.Unavailable
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.Failed(UpdateSentences.SERVER_UNREACHABLE), result)
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordDefinitive") })
    }

    @Test
    fun `a manifest the reader refuses gives a failure with the reader sentence and never up to date`() {
        val rig = CheckerRig()
        rig.manifestWith("{}")
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.Failed(ManifestReader.MISSING_FIELD), result)
        assertFalse(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `an APK address off the server origin gives a failure before the signature is read`() {
        val rig = CheckerRig()
        rig.manifestWith(manifestJson(apkUrl = "https://other.example.test:8443/v1/apk/breaker.apk"))
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.Failed(UpdateSentences.ORIGIN), result)
        assertFalse(rig.log.snapshot().contains("installed.current"))
    }

    @Test
    fun `a signature that no pinned key verifies gives a failure and reads no installed build`() {
        val rig = CheckerRig()
        rig.manifestWith(manifestJson(signature = ManifestVectors.SIG_UNPINNED))
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.Failed(UpdateSentences.SIGNATURE), result)
        assertFalse(rig.log.snapshot().contains("installed.current"))
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordDefinitive") })
    }

    @Test
    fun `an installed build with the same code is up to date and records the check`() {
        val rig = CheckerRig()
        rig.installed.build = InstalledBuild(versionCode = 12, versionName = INSTALLED_NAME)
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.UpToDate(INSTALLED_NAME), result)
        assertTrue(rig.log.snapshot().contains("ledger.recordDefinitive:$NOW_MILLIS"))
    }

    @Test
    fun `a newer offered code gives the verified release as available`() {
        val rig = CheckerRig()
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.Available(defaultRelease()), result)
    }

    @Test
    fun `an up to date result carries the installed display name`() {
        val rig = CheckerRig()
        rig.installed.build = InstalledBuild(versionCode = 12, versionName = "2.0.0-named")
        val result = rig.checker.check(false)
        assertEquals(UpdateCheckResult.UpToDate("2.0.0-named"), result)
    }

    @Test
    fun `the definitive check time is the clock time of the call`() {
        val rig = CheckerRig()
        rig.checker.check(false)
        assertTrue(rig.log.snapshot().contains("ledger.recordDefinitive:$NOW_MILLIS"))
        assertFalse(rig.log.snapshot().contains("ledger.recordDefinitive:${NOW_MILLIS + 1L}"))
    }
}
