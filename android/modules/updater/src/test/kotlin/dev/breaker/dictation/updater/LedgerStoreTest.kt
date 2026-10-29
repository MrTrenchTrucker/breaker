package dev.breaker.dictation.updater

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins the ledger file as the store reads and writes it: fresh reads, the failure rules, and the one write point. */
internal class LedgerStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clockTime = 1_700_000_000_123L

    private fun ledgerFile(): File = File(tmp.root, "update-ledger.txt")

    private fun storeAt(file: File): LedgerStore = LedgerStore(file, FakeClock(clockTime, CallLog()))

    private fun writeText(file: File, values: LedgerValues) {
        Files.write(file.toPath(), LedgerCodec.encode(values).toByteArray(Charsets.UTF_8))
    }

    /** Makes the write with [sequence] fail: a directory sits where that write's temporary file would go. */
    private fun blockTempName(file: File, sequence: Int) {
        Files.createDirectory(File(tmp.root, ledgerTempName(file.name, sequence)).toPath())
    }

    @Test
    fun `a store reads the file again on every call`() {
        val file = ledgerFile()
        val store = storeAt(file)
        assertNull("a missing file reads no attempt time", store.lastAttemptMillis())
        writeText(file, LedgerValues(null, 5L, null, null, null, null))
        assertEquals("the first written value is seen", 5L, store.lastAttemptMillis())
        writeText(file, LedgerValues(null, 6L, null, null, null, null))
        assertEquals("the next written value is seen", 6L, store.lastAttemptMillis())
    }

    @Test
    fun `a malformed UTF-8 file reads as empty`() {
        val file = ledgerFile()
        Files.write(file.toPath(), ByteArray(1) { 0xFF.toByte() })
        val store = storeAt(file)
        assertNull("a file that is not UTF-8 reads no attempt time", store.lastAttemptMillis())
        assertNull("a file that is not UTF-8 reads no record", store.recordedSessionId())
    }

    @Test
    fun `a failed write leaves the next read at the value the file holds`() {
        val file = ledgerFile()
        writeText(file, LedgerValues(null, 5L, null, null, null, null))
        blockTempName(file, 1)
        val store = storeAt(file)
        store.recordAttempt(9L)
        assertEquals("the failed write must not change the file", 5L, store.lastAttemptMillis())
    }

    @Test
    fun `sessionOpened into a folder that is a file throws IOException`() {
        val blocker = File(tmp.root, "blocker")
        Files.write(blocker.toPath(), ByteArray(0))
        val store = storeAt(File(blocker, "update-ledger.txt"))
        val thrown = runCatching { store.sessionOpened(42) }.exceptionOrNull()
        assertTrue("sessionOpened must throw IOException, got $thrown", thrown is IOException)
    }

    @Test
    fun `sessionOpened stamps the time of the injected clock`() {
        val store = storeAt(ledgerFile())
        store.sessionOpened(42)
        assertEquals("the record carries the clock time", clockTime, store.read().record?.writtenAtMs)
    }

    @Test
    fun `sessionOpened then recordedSessionId returns the opened session and no final status`() {
        val store = storeAt(ledgerFile())
        store.sessionOpened(42)
        assertEquals("the recorded session", 42, store.recordedSessionId())
        assertNull("a new record has no final status", store.read().record?.finalStatus)
    }

    @Test
    fun `sessionOpened over a record with a final status gives the new session and no final status`() {
        val store = storeAt(ledgerFile())
        store.sessionOpened(42)
        assertTrue("the recorded session takes the status", store.storeFinalStatus("Success"))
        store.sessionOpened(43)
        assertEquals("the new session replaces the old one", 43, store.recordedSessionId())
        assertNull("the earlier final status must not carry over", store.read().record?.finalStatus)
    }

    @Test
    fun `member writes keep the record keys and the other times`() {
        val store = storeAt(ledgerFile())
        store.sessionOpened(42)
        store.recordAttempt(7L)
        store.recordDefinitiveCheck(6L)
        store.recordUpdate(8L)
        assertEquals("the record survives the member writes", 42, store.recordedSessionId())
        assertEquals("the attempt time is stored", 7L, store.lastAttemptMillis())
        assertEquals("the definitive check time is stored", 6L, store.lastDefinitiveCheckMillis())
        assertEquals("the update time is stored", 8L, store.lastUpdateMillis())
    }

    @Test
    fun `a failed member write does not throw`() {
        val blocker = File(tmp.root, "blocker")
        Files.write(blocker.toPath(), ByteArray(0))
        val store = storeAt(File(blocker, "update-ledger.txt"))
        store.recordAttempt(1L)
        store.recordDefinitiveCheck(2L)
        store.recordUpdate(3L)
        assertNull("the failed writes left no attempt time", store.lastAttemptMillis())
    }

    @Test
    fun `a write after a failed temp write uses a new temp name`() {
        val file = ledgerFile()
        writeText(file, LedgerValues(null, 5L, null, null, null, null))
        blockTempName(file, 1)
        val store = storeAt(file)
        store.recordAttempt(9L)
        assertEquals("the blocked write must leave the file as it was", 5L, store.lastAttemptMillis())
        store.recordUpdate(10L)
        assertEquals("the next write must use another temp name", 10L, store.lastUpdateMillis())
    }

    @Test
    fun `the ledger file is moved into place by one write method only`() {
        val source = File("src/main/kotlin/dev/breaker/dictation/updater/LedgerStore.kt").readText()
        assertEquals("only the one write method calls the move", 1, source.split("Files.move(").size - 1)
    }

    @Test
    fun `the temp file is deleted in the failure path of the write method`() {
        val source = File("src/main/kotlin/dev/breaker/dictation/updater/LedgerStore.kt").readText()
        val marker = "if (!moved) deleteQuietly(temp)"
        assertEquals("the failure path removes the temp file once", 1, source.split(marker).size - 1)
    }
}
