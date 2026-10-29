package dev.breaker.dictation.updater

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins the slot operations of the ledger store: the final status, the decision write, and the removal of the record. */
internal class LedgerSlotTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ledgerFile(): File = File(tmp.root, "update-ledger.txt")

    private fun storeAt(file: File): LedgerStore = LedgerStore(file, FakeClock(1_700_000_000_123L, CallLog()))

    /** Makes the write with [sequence] fail: a directory sits where that write's temporary file would go. */
    private fun blockTempName(file: File, sequence: Int) {
        Files.createDirectory(File(tmp.root, ledgerTempName(file.name, sequence)).toPath())
    }

    @Test
    fun `storeFinalStatus writes only when a record exists`() {
        val file = ledgerFile()
        val store = storeAt(file)
        assertFalse("with no record nothing is stored", store.storeFinalStatus("Success"))
        assertFalse("with no record no file is written", Files.exists(file.toPath()))
        store.sessionOpened(42)
        store.recordAttempt(7L)
        assertTrue("the recorded session takes the status", store.storeFinalStatus("Success"))
        assertEquals("the status is stored", "Success", store.read().record?.finalStatus)
        assertEquals("the times are unchanged", 7L, store.lastAttemptMillis())
    }

    @Test
    fun `applyChange clears the record and keeps the times`() {
        val store = storeAt(ledgerFile())
        store.sessionOpened(42)
        store.recordAttempt(7L)
        store.applyChange(false, FieldEdit.Keep, FieldEdit.Keep)
        assertEquals("a kept record stays", 42, store.recordedSessionId())
        store.applyChange(true, FieldEdit.Keep, FieldEdit.Keep)
        assertNull("the record is removed", store.recordedSessionId())
        assertEquals("the attempt time is kept", 7L, store.lastAttemptMillis())
    }

    @Test
    fun `applyChange keeps sets and clears the last seen code and the last outcome`() {
        val store = storeAt(ledgerFile())
        store.applyChange(false, FieldEdit.Put(3), FieldEdit.Put("text"))
        assertEquals("a put sets the code", 3, store.read().lastSeenCode)
        assertEquals("a put sets the outcome", "text", store.read().lastOutcome)
        store.applyChange(false, FieldEdit.Keep, FieldEdit.Clear)
        assertEquals("a keep leaves the code", 3, store.read().lastSeenCode)
        assertNull("a clear removes the outcome", store.read().lastOutcome)
    }

    @Test
    fun `clearRecord removes the record and keeps the times code and outcome`() {
        val store = storeAt(ledgerFile())
        store.sessionOpened(42)
        store.recordAttempt(7L)
        store.recordDefinitiveCheck(6L)
        store.recordUpdate(8L)
        store.applyChange(false, FieldEdit.Put(3), FieldEdit.Put("text"))
        store.clearRecord()
        assertNull("the record is removed", store.recordedSessionId())
        assertEquals("the attempt time is kept", 7L, store.lastAttemptMillis())
        assertEquals("the definitive check time is kept", 6L, store.lastDefinitiveCheckMillis())
        assertEquals("the update time is kept", 8L, store.lastUpdateMillis())
        assertEquals("the last seen code is kept", 3, store.read().lastSeenCode)
        assertEquals("the last outcome is kept", "text", store.read().lastOutcome)
    }

    @Test
    fun `clearRecord with no record writes nothing and creates no file`() {
        val missing = ledgerFile()
        storeAt(missing).clearRecord()
        assertFalse("no record means no file is created", Files.exists(missing.toPath()))

        val times = File(tmp.root, "times.txt")
        val timesOnly = LedgerValues(null, 5L, null, null, null, null)
        Files.write(times.toPath(), LedgerCodec.encode(timesOnly).toByteArray(Charsets.UTF_8))
        blockTempName(times, 1)
        storeAt(times).clearRecord()
        assertEquals("the times survive a call that writes nothing", 5L, storeAt(times).lastAttemptMillis())
    }

    @Test
    fun `clearRecord throws IOException when its write fails`() {
        val file = ledgerFile()
        val store = storeAt(file)
        store.sessionOpened(42)
        blockTempName(file, 2)
        val thrown = runCatching { store.clearRecord() }.exceptionOrNull()
        assertTrue("clearRecord must throw IOException, got $thrown", thrown is IOException)
        assertEquals("the record stays after the failed write", 42, store.recordedSessionId())
    }
}
