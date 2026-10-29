package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateInstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the whole install path as one ordered call record, and the ways the path can end early. */
internal class CheckerInstallFlowTest {

    /** Runs a check on [rig] so that the default release is remembered, then empties the call log. */
    private fun checked(rig: CheckerRig): CheckerRig {
        rig.checker.check(false)
        rig.log.clear()
        return rig
    }

    @Test
    fun `a verified install reads the version, stages the file, installs, records the update and discards once in order`() {
        val rig = checked(CheckerRig())
        val result = rig.checker.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertEquals(1, rig.installer.calls)
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

    @Test
    fun `an installer that throws lets the exception through, discards the file and records no update`() {
        val rig = checked(CheckerRig())
        rig.installer.thrown = IllegalStateException("the installer stopped")
        var caught: IllegalStateException? = null
        try {
            rig.checker.install(defaultRelease())
        } catch (error: IllegalStateException) {
            caught = error
        }
        assertNotNull(caught)
        assertTrue(rig.log.snapshot().contains("discard"))
        assertFalse(rig.log.snapshot().any { it.startsWith("ledger.recordUpdate") })
    }

    @Test
    fun `the session token never appears in any port call`() {
        val rig = CheckerRig()
        rig.auth.session = adminSession()
        rig.checker.check(false)
        rig.checker.install(defaultRelease())
        assertFalse(rig.log.snapshot().any { it.contains(HIDDEN_TOKEN) })
    }
}
