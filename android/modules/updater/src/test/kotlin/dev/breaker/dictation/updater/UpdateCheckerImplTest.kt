package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerImplTest {

    private fun checker(
        scheduled: CheckerFakeScheduled = CheckerFakeScheduled { UpdateCheckResult.UpToDate("1.2.3.4") },
        admin: CheckerFakeAdmin = CheckerFakeAdmin(false),
        verifier: CheckerFakeVerifier = CheckerFakeVerifier {
            UpdateVerificationResult.Refused(ApkDownloadReasons.BAD_STATUS)
        },
        install: CheckerFakeInstall = CheckerFakeInstall { InstallResult.Started },
        held: HeldRollback = HeldRollback(),
        reports: CheckerFakeReports = CheckerFakeReports(),
    ): UpdateCheckerImpl = UpdateCheckerImpl(
        runScheduled = scheduled::run,
        isAdmin = admin::isAdmin,
        verifyRelease = verifier::verify,
        installReady = install::install,
        held = held,
        onInstallResult = reports::report,
    )

    @Test
    fun `a non-admin force check asks the scheduled check not to force`() {
        val scheduled = CheckerFakeScheduled { UpdateCheckResult.UpToDate("1.2.3.4") }
        val result = checker(scheduled = scheduled, admin = CheckerFakeAdmin(false)).check(true)
        assertEquals("android_updater: the answer passes through", UpdateCheckResult.UpToDate("1.2.3.4"), result)
        assertEquals("android_updater: asked not to force", listOf(false), scheduled.asked)
    }

    @Test
    fun `an admin force check asks the scheduled check to force`() {
        val scheduled = CheckerFakeScheduled { UpdateCheckResult.UpToDate("1.2.3.4") }
        checker(scheduled = scheduled, admin = CheckerFakeAdmin(true)).check(true)
        assertEquals("android_updater: asked to force", listOf(true), scheduled.asked)
    }

    @Test
    fun `a check that is not forced asks not to force even for an admin`() {
        val scheduled = CheckerFakeScheduled { UpdateCheckResult.UpToDate("1.2.3.4") }
        checker(scheduled = scheduled, admin = CheckerFakeAdmin(true)).check(false)
        assertEquals("android_updater: asked not to force", listOf(false), scheduled.asked)
    }

    @Test
    fun `a role that cannot be read counts as not an admin`() {
        val scheduled = CheckerFakeScheduled { UpdateCheckResult.UpToDate("1.2.3.4") }
        checker(scheduled = scheduled, admin = CheckerFakeAdmin(true, failing = true)).check(true)
        assertEquals("android_updater: asked not to force", listOf(false), scheduled.asked)
    }

    @Test
    fun `a null answer from the scheduled check passes through as null`() {
        val scheduled = CheckerFakeScheduled { null }
        assertNull("android_updater: null passes through", checker(scheduled = scheduled).check(false))
        assertEquals("android_updater: one ask", 1, scheduled.asked.size)
    }

    @Test
    fun `a scheduled check that throws answers the unfinished failure`() {
        val scheduled = CheckerFakeScheduled { throw IllegalStateException("the check failed") }
        val result = checker(scheduled = scheduled).check(false)
        assertEquals(
            "android_updater: the unfinished failure",
            UpdateCheckResult.Failed(CheckReasons.UNFINISHED),
            result,
        )
    }

    @Test
    fun `a refused verification reports the refusal and installs nothing`() {
        val install = CheckerFakeInstall { InstallResult.Started }
        val reports = CheckerFakeReports()
        checker(install = install, reports = reports).install(checkerRelease())
        assertEquals(
            "android_updater: the refusal is reported",
            listOf<InstallResult>(InstallResult.Refused(ApkDownloadReasons.BAD_STATUS)),
            reports.reported,
        )
        assertEquals("android_updater: nothing installed", 0, install.installed.size)
    }

    @Test
    fun `a ready verification that starts the install reports started and clears the held offer`() {
        val held = HeldRollback()
        held.hold(checkerRelease())
        val reports = CheckerFakeReports()
        val verifier = CheckerFakeVerifier { checkerReady("ready.apk") }
        checker(
            verifier = verifier,
            install = CheckerFakeInstall { InstallResult.Started },
            held = held,
            reports = reports,
        ).install(checkerRelease())
        assertEquals("android_updater: started is reported", listOf<InstallResult>(InstallResult.Started), reports.reported)
        assertNull("android_updater: the held offer is cleared", held.current())
    }

    @Test
    fun `a ready verification that the installer refuses keeps the held offer`() {
        val held = HeldRollback()
        held.hold(checkerRelease())
        val reports = CheckerFakeReports()
        checker(
            verifier = CheckerFakeVerifier { checkerReady("ready.apk") },
            install = CheckerFakeInstall { InstallResult.Refused(InstallReasons.NOT_STARTED) },
            held = held,
            reports = reports,
        ).install(checkerRelease())
        assertEquals(
            "android_updater: the refusal is reported",
            listOf<InstallResult>(InstallResult.Refused(InstallReasons.NOT_STARTED)),
            reports.reported,
        )
        assertEquals("android_updater: the held offer is kept", checkerRelease(), held.current())
    }

    @Test
    fun `an exception from verification becomes the unexpected refusal`() {
        val reports = CheckerFakeReports()
        checker(
            verifier = CheckerFakeVerifier { throw IllegalStateException("the verify failed") },
            reports = reports,
        ).install(checkerRelease())
        assertEquals(
            "android_updater: the unexpected refusal",
            listOf<InstallResult>(InstallResult.Refused(UpdateVerificationReasons.UNEXPECTED)),
            reports.reported,
        )
    }

    @Test
    fun `an exception from the installer becomes the unexpected refusal`() {
        val reports = CheckerFakeReports()
        checker(
            verifier = CheckerFakeVerifier { checkerReady("ready.apk") },
            install = CheckerFakeInstall { throw IllegalStateException("the install failed") },
            reports = reports,
        ).install(checkerRelease())
        assertEquals(
            "android_updater: the unexpected refusal",
            listOf<InstallResult>(InstallResult.Refused(UpdateVerificationReasons.UNEXPECTED)),
            reports.reported,
        )
    }

    @Test
    fun `a report that throws changes no answer`() {
        val held = HeldRollback()
        held.hold(checkerRelease())
        val reports = CheckerFakeReports(failing = true)
        checker(
            verifier = CheckerFakeVerifier { checkerReady("ready.apk") },
            held = held,
            reports = reports,
        ).install(checkerRelease())
        assertEquals("android_updater: the report was attempted once", 1, reports.reported.size)
        assertNull("android_updater: the started install still cleared the offer", held.current())
    }

    @Test
    fun `a rollback by a non-admin reports not allowed and verifies nothing`() {
        val held = HeldRollback()
        held.hold(checkerRelease())
        val verifier = CheckerFakeVerifier { checkerReady("ready.apk") }
        val reports = CheckerFakeReports()
        checker(verifier = verifier, admin = CheckerFakeAdmin(false), held = held, reports = reports).rollback()
        assertEquals(
            "android_updater: not allowed is reported",
            listOf<InstallResult>(InstallResult.Refused(RollbackReasons.NOT_ALLOWED)),
            reports.reported,
        )
        assertEquals("android_updater: nothing verified", 0, verifier.verified.size)
    }

    @Test
    fun `a rollback with nothing held reports none held`() {
        val verifier = CheckerFakeVerifier { checkerReady("ready.apk") }
        val reports = CheckerFakeReports()
        checker(verifier = verifier, admin = CheckerFakeAdmin(true), reports = reports).rollback()
        assertEquals(
            "android_updater: none held is reported",
            listOf<InstallResult>(InstallResult.Refused(RollbackReasons.NONE_HELD)),
            reports.reported,
        )
        assertEquals("android_updater: nothing verified", 0, verifier.verified.size)
    }

    @Test
    fun `a rollback with a held release verifies and installs exactly that release`() {
        val held = HeldRollback()
        held.hold(checkerRelease("1.2.3.4"))
        val verifier = CheckerFakeVerifier { checkerReady("ready.apk") }
        val install = CheckerFakeInstall { InstallResult.Started }
        val reports = CheckerFakeReports()
        checker(
            verifier = verifier,
            install = install,
            admin = CheckerFakeAdmin(true),
            held = held,
            reports = reports,
        ).rollback()
        assertEquals("android_updater: the held release was verified", listOf(checkerRelease("1.2.3.4")), verifier.verified)
        assertEquals("android_updater: one install", 1, install.installed.size)
        assertEquals("android_updater: started is reported", listOf<InstallResult>(InstallResult.Started), reports.reported)
        assertNull("android_updater: the offer is cleared", held.current())
    }
}
