package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Behaviour of the ledger, and of deleteAll, through the store. Written without a compiler, not run. */
class SampleLedgerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val encoder = CountingWavEncoder()

    private fun storeOn(root: File, vararg ids: String) = SampleStore(root, ScriptedIdSource(*ids), encoder)

    private fun ledgerLines(root: File): List<String> =
        File(root, "ledger.txt").readText().split('\n').filter { it.isNotEmpty() }

    @Test
    fun `a confirmed id belongs to its own kind only`() {
        val store = storeOn(tmp.newFolder("root"))
        assertEquals(StoreResult.Ok(Unit), store.markConfirmed(PhraseKind.WAKE, "w1"))
        assertEquals(StoreResult.Ok(Unit), store.markConfirmed(PhraseKind.SEND, "s1"))
        assertEquals(StoreResult.Ok(Unit), store.markConfirmed(PhraseKind.WAKE, "w2"))
        assertEquals(setOf("w1", "w2"), store.confirmed(PhraseKind.WAKE))
        assertEquals(setOf("s1"), store.confirmed(PhraseKind.SEND))
    }

    @Test
    fun `the ledger survives a new store on the same root`() {
        val root = tmp.newFolder("root")
        val first = storeOn(root)
        first.markConfirmed(PhraseKind.WAKE, "w1")
        first.saveJob("job-1")
        first.setServerDeletePending(true)
        val second = storeOn(root)
        assertEquals(setOf("w1"), second.confirmed(PhraseKind.WAKE))
        assertEquals("job-1", second.loadJob())
        assertTrue(second.serverDeletePending())
    }

    @Test
    fun `marking the same id twice keeps one line`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        store.markConfirmed(PhraseKind.WAKE, "w1")
        store.markConfirmed(PhraseKind.WAKE, "w1")
        assertEquals(listOf("confirmed wake w1"), ledgerLines(root))
        assertEquals(setOf("w1"), store.confirmed(PhraseKind.WAKE))
    }

    @Test
    fun `the ledger file has the documented lines in order`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        store.setServerDeletePending(true)
        store.saveJob("j1")
        store.markConfirmed(PhraseKind.SEND, "s1")
        store.markConfirmed(PhraseKind.WAKE, "w1")
        assertEquals("confirmed wake w1\nconfirmed send s1\njob j1\nserver-delete-pending\n", File(root, "ledger.txt").readText())
    }

    @Test
    fun `a malformed ledger line is ignored and the other lines still read`() {
        val root = tmp.newFolder("root")
        File(root, "ledger.txt").writeText(
            listOf(
                "garbage",
                "confirmed wake ../x",
                "confirmed wake ok1",
                "confirmed other z",
                "confirmed wake",
                "confirmed wake a b",
                "job",
                "future-fact x",
                "confirmed send s1",
                "job job-7",
                "",
            ).joinToString("\n"),
        )
        val store = storeOn(root)
        assertEquals(setOf("ok1"), store.confirmed(PhraseKind.WAKE))
        assertEquals(setOf("s1"), store.confirmed(PhraseKind.SEND))
        assertEquals("job-7", store.loadJob())
        assertFalse(store.serverDeletePending())
    }

    @Test
    fun `a write leaves no temp ledger file behind`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        store.markConfirmed(PhraseKind.WAKE, "w1")
        store.saveJob("j1")
        store.clearJob()
        assertEquals(listOf("ledger.txt"), root.list()!!.toList())
    }

    @Test
    fun `a blocked temp ledger path fails the write and keeps the old ledger`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        assertEquals(StoreResult.Ok(Unit), store.markConfirmed(PhraseKind.WAKE, "w1"))
        File(root, "ledger.txt.tmp/inner").also { it.parentFile.mkdirs() }.writeText("x")
        assertEquals(StoreResult.IoFailed, store.markConfirmed(PhraseKind.WAKE, "w2"))
        assertEquals(setOf("w1"), store.confirmed(PhraseKind.WAKE))
        assertEquals(listOf("confirmed wake w1"), ledgerLines(root))
    }

    @Test
    fun `markConfirmed refuses ids that could forge or escape and writes nothing`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        val ids = listOf(
            "../x", "/abs", "a/b", "", "a".repeat(65), "a b", "x\nconfirmed send y", "x\nconfirmed send",
        )
        for (id in ids) {
            assertEquals("id: $id", StoreResult.InvalidId, store.markConfirmed(PhraseKind.WAKE, id))
        }
        assertFalse(File(root, "ledger.txt").exists())
        assertEquals(emptySet<String>(), store.confirmed(PhraseKind.WAKE))
        assertEquals(emptySet<String>(), store.confirmed(PhraseKind.SEND))
        assertEquals(StoreResult.Ok(Unit), store.markConfirmed(PhraseKind.WAKE, "a".repeat(64)))
    }

    @Test
    fun `a job id with a line break is refused`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        assertEquals(StoreResult.InvalidId, store.saveJob("j1\nconfirmed wake evil"))
        assertEquals(StoreResult.InvalidId, store.saveJob("j1\rconfirmed wake evil"))
        assertNull(store.loadJob())
        assertEquals(emptySet<String>(), store.confirmed(PhraseKind.WAKE))
        assertFalse(File(root, "ledger.txt").exists())
    }

    @Test
    fun `a job id of the wrong length is refused and 128 characters is allowed`() {
        val store = storeOn(tmp.newFolder("root"))
        assertEquals(StoreResult.InvalidId, store.saveJob(""))
        assertEquals(StoreResult.InvalidId, store.saveJob("j".repeat(129)))
        assertNull(store.loadJob())
        assertEquals(StoreResult.Ok(Unit), store.saveJob("j".repeat(128)))
        assertEquals("j".repeat(128), store.loadJob())
    }

    @Test
    fun `the job id can be saved replaced and cleared`() {
        val store = storeOn(tmp.newFolder("root"))
        assertNull(store.loadJob())
        assertEquals(StoreResult.Ok(Unit), store.saveJob("job-1"))
        assertEquals("job-1", store.loadJob())
        store.saveJob("job-2")
        assertEquals("job-2", store.loadJob())
        assertEquals(StoreResult.Ok(Unit), store.clearJob())
        assertNull(store.loadJob())
    }

    @Test
    fun `the server delete pending mark can be set and cleared`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root)
        assertFalse(store.serverDeletePending())
        store.setServerDeletePending(true)
        assertTrue(store.serverDeletePending())
        store.setServerDeletePending(false)
        assertFalse(store.serverDeletePending())
        assertFalse(File(root, "ledger.txt").readText().contains("server-delete-pending"))
    }

    @Test
    fun `a folder in the place of the ledger is unreadable and counts as a pending delete`() {
        val root = tmp.newFolder("root")
        File(root, "ledger.txt").mkdirs()
        val store = storeOn(root)
        assertEquals(LedgerState.UNREADABLE, store.serverDeleteState())
        assertTrue(store.serverDeletePending())
        assertEquals(StoreResult.IoFailed, store.setServerDeletePending(false))
    }

    @Test
    fun `a dangling symlink in the place of the ledger is unreadable and counts as a pending delete`() {
        val root = tmp.newFolder("root")
        symlinkOrSkip(File(root, "ledger.txt"), File(root, "nowhere"))
        val store = storeOn(root)
        assertEquals(LedgerState.UNREADABLE, store.serverDeleteState())
        assertTrue(store.serverDeletePending())
        assertEquals(StoreResult.IoFailed, store.setServerDeletePending(false))
    }

    @Test
    fun `a symlink to a readable ledger is not followed and is unreadable`() {
        val root = tmp.newFolder("root")
        val real = File(tmp.newFolder("elsewhere"), "real.txt")
        real.writeText("server-delete-pending\n")
        symlinkOrSkip(File(root, "ledger.txt"), real)
        assertEquals(LedgerState.UNREADABLE, storeOn(root).serverDeleteState())
    }

    @Test
    fun `a missing ledger is clear and not pending`() {
        val store = storeOn(tmp.newFolder("root"))
        assertEquals(LedgerState.CLEAR, store.serverDeleteState())
        assertFalse(store.serverDeletePending())
    }

    @Test
    fun `a ledger with the pending line is pending`() {
        val store = storeOn(tmp.newFolder("root"))
        assertEquals(StoreResult.Ok(Unit), store.setServerDeletePending(true))
        assertEquals(LedgerState.PENDING, store.serverDeleteState())
        assertTrue(store.serverDeletePending())
    }

    @Test
    fun `a stored ledger without the pending line is clear`() {
        val store = storeOn(tmp.newFolder("root"))
        assertEquals(StoreResult.Ok(Unit), store.markConfirmed(PhraseKind.WAKE, "w1"))
        assertEquals(LedgerState.CLEAR, store.serverDeleteState())
        assertFalse(store.serverDeletePending())
    }

    @Test
    fun `deleteAll removes samples and temp files and the ledger facts but keeps the pending mark`() {
        val root = tmp.newFolder("root")
        val store = storeOn(root, "w1", "w2", "s1")
        store.put(PhraseKind.WAKE, floatArrayOf(0.1f))
        store.put(PhraseKind.WAKE, floatArrayOf(0.1f))
        store.put(PhraseKind.SEND, floatArrayOf(0.1f))
        File(root, "wake/w9.wav.tmp").writeText("half")
        store.markConfirmed(PhraseKind.WAKE, "w5")
        store.markConfirmed(PhraseKind.SEND, "s5")
        store.saveJob("job-1")
        store.setServerDeletePending(true)
        assertEquals(StoreResult.Ok(Unit), store.deleteAll())
        assertEquals(emptyList<String>(), store.list(PhraseKind.WAKE))
        assertEquals(emptyList<String>(), store.list(PhraseKind.SEND))
        assertEquals(emptyList<String>(), File(root, "wake").list()!!.toList())
        assertEquals(emptyList<String>(), File(root, "send").list()!!.toList())
        assertEquals(emptySet<String>(), store.confirmed(PhraseKind.WAKE))
        assertEquals(emptySet<String>(), store.confirmed(PhraseKind.SEND))
        assertNull(store.loadJob())
        assertTrue(store.serverDeletePending())
        assertEquals(listOf("server-delete-pending"), ledgerLines(root))
    }

    @Test
    fun `deleteAll keeps a pending mark that was not set as not set`() {
        val store = storeOn(tmp.newFolder("root"))
        store.markConfirmed(PhraseKind.WAKE, "w1")
        assertEquals(StoreResult.Ok(Unit), store.deleteAll())
        assertFalse(store.serverDeletePending())
    }

    @Test
    fun `deleteAll on a store that was never used succeeds`() {
        val store = storeOn(File(tmp.root, "fresh"))
        assertEquals(StoreResult.Ok(Unit), store.deleteAll())
        assertEquals(emptySet<String>(), store.confirmed(PhraseKind.WAKE))
    }

    @Test
    fun `the ledger file is readable by its owner only`() {
        val root = tmp.newFolder("root")
        storeOn(root).markConfirmed(PhraseKind.WAKE, "w1")
        val permissions = try {
            Files.getPosixFilePermissions(File(root, "ledger.txt").toPath())
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
            return
        }
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }
}
