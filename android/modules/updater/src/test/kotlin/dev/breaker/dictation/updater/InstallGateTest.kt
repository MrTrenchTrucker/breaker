package dev.breaker.dictation.updater

import dev.breaker.dictation.core.model.UpdateCheckResult
import dev.breaker.dictation.core.model.UpdateInstallResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The install gate: the guard first, the first-start decision after it, and an install only when the decision allows it. */
internal class InstallGateTest {
    private val rig = GateRig()

    @Test
    fun `a blocked install refuses with the decision sentence and writes nothing`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        rig.sessions.committed = true
        val before = rig.slot.values
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.WAITING), result)
        assertEquals(0, rig.fakes.installer.calls)
        assertEquals(before, rig.slot.values)
        assertNoSlotWrite()
    }

    @Test
    fun `an allowed install delegates to the checker and installs the verified release`() {
        rig.checkFirst()
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertEquals(1, rig.fakes.installer.calls)
    }

    @Test
    fun `an install after an installed code rose leaves the last seen code unchanged`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(null, 10, null)
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertEquals(10, rig.slot.values.lastSeenCode)
    }

    @Test
    fun `an install that goes ahead after a stored sentence keeps the stored sentence`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(null, 11, UpdateSentences.CANCELLED)
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertEquals(UpdateSentences.CANCELLED, rig.slot.values.lastOutcome)
    }

    @Test
    fun `an install that clears a record writes only the clear and does not store not finished`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        rig.sessions.known = false
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertNull(rig.slot.values.record)
        assertNull(rig.slot.values.lastOutcome)
        assertEquals(11, rig.slot.values.lastSeenCode)
        assertEquals(1, rig.fakes.log.snapshot().count { it == "slot.applyChange:true" })
    }

    @Test
    fun `an install refused as not checked leaves the recorded session and the record unchanged`() {
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        val before = rig.slot.values
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_CHECKED), result)
        assertEquals(0, rig.abandon.calls)
        assertEquals(before, rig.slot.values)
        assertNoSlotWrite()
    }

    @Test
    fun `an install decides with start only so an uncommitted session under a day is refused and kept`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        rig.abandon.thrown = IOException("the test abandon fails")
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Refused(UpdateSentences.NOT_FINISHED), result)
        assertEquals(7, rig.slot.values.record?.sessionId)
        assertEquals(0, rig.fakes.installer.calls)
    }

    @Test
    fun `an install after a successful abandon uses the second decision and goes ahead`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(3_600_000L), null), 11, null)
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Installed, result)
        assertEquals(1, rig.abandon.calls)
        assertNull(rig.slot.values.record)
    }

    @Test
    fun `a check is delegated to the checker and writes nothing`() {
        val result = rig.gate.check(false)
        assertEquals(UpdateCheckResult.Available(defaultRelease()), result)
        assertTrue(rig.fakes.log.snapshot().contains("manifest.load"))
        assertTrue(rig.fakes.log.snapshot().none { it.startsWith("slot.") })
    }

    @Test
    fun `a failed clear write refuses the install with the installer failed sentence`() {
        rig.checkFirst()
        rig.slot.values = ledgerWith(LedgerRecord(7, writtenAgo(1_000L), null), 11, null)
        rig.sessions.known = false
        rig.slot.applyFails = true
        val result = rig.gate.install(defaultRelease())
        assertEquals(UpdateInstallResult.Failed(UpdateSentences.INSTALLER_FAILED), result)
        assertEquals(0, rig.fakes.installer.calls)
        assertEquals(7, rig.slot.values.record?.sessionId)
    }

    /** Asserts that the engine wrote nothing to the ledger slot during the call. */
    private fun assertNoSlotWrite() {
        assertTrue(rig.fakes.log.snapshot().none { it.startsWith("slot.storeFinalStatus") || it.startsWith("slot.applyChange") })
    }
}
