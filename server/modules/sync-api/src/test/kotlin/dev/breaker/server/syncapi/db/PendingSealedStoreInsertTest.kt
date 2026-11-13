package dev.breaker.server.syncapi.db

import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.SYNC_BODY_CAP_BYTES
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PendingSealedStore.insert: the order of its checks (account, duplicate id, per-account cap, write), the
 * boundary of the cap, and the arithmetic of the two limits. Rows are read back with list.
 */
internal class PendingSealedStoreInsertTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var store: PendingSealedStore
    private var alice = 0L
    private var bob = 0L

    @Before
    fun setup() {
        temp = openTemp()
        store = PendingSealedStore(temp.db)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun uuid(number: Int): String = "00000000-0000-0000-0000-" + number.toString().padStart(12, '0')

    private fun stamp(number: Int): String = "2026-11-13T00:00:00." + number.toString().padStart(3, '0') + "Z"

    private fun insert(account: Long, number: Int, box: String = "box$number"): PendingInsertResult =
        runBlocking { store.insert(account, uuid(number), box, stamp(number)) }

    private fun rows(account: Long): List<PendingSealedRow> = runBlocking { store.list(account) }

    @Test
    fun `a new id is stored with its text and its time`() {
        assertEquals("sync-api: first insert", PendingInsertResult.Stored, insert(alice, 1, "first-box"))
        assertEquals(
            "sync-api: the stored row",
            listOf(PendingSealedRow(uuid(1), "first-box", stamp(1))),
            rows(alice),
        )
    }

    @Test
    fun `an id that is already a row changes nothing and keeps the first text`() {
        insert(alice, 1, "first-box")
        assertEquals("sync-api: second insert of the id", PendingInsertResult.Duplicate, insert(alice, 1, "second-box"))
        assertEquals(
            "sync-api: the first row stays as it was",
            listOf(PendingSealedRow(uuid(1), "first-box", stamp(1))),
            rows(alice),
        )
    }

    @Test
    fun `an id held by another account is a duplicate and the row stays with its first account`() {
        insert(alice, 1, "alice-box")
        assertEquals("sync-api: bob inserts alice's id", PendingInsertResult.Duplicate, insert(bob, 1, "bob-box"))
        assertEquals("sync-api: bob holds nothing", emptyList<PendingSealedRow>(), rows(bob))
        assertEquals("sync-api: alice's row", listOf(PendingSealedRow(uuid(1), "alice-box", stamp(1))), rows(alice))
    }

    @Test
    fun `an account that does not exist is refused and nothing is stored`() {
        assertEquals("sync-api: no such account", PendingInsertResult.NoSuchAccount, insert(alice + bob + 100L, 1))
        assertEquals("sync-api: alice holds nothing", emptyList<PendingSealedRow>(), rows(alice))
        assertEquals("sync-api: control, a real account stores the same id", PendingInsertResult.Stored, insert(alice, 1))
    }

    @Test
    fun `the 100th row is stored and the 101st is refused as full`() {
        for (number in 1..PENDING_PER_ACCOUNT_MAX) {
            assertEquals("sync-api: insert number $number", PendingInsertResult.Stored, insert(alice, number))
        }
        assertEquals("sync-api: rows after 100", 100, rows(alice).size)
        assertEquals("sync-api: the 101st", PendingInsertResult.PendingFull, insert(alice, 101))
        assertEquals("sync-api: rows after the refused 101st", 100, rows(alice).size)
        assertTrue("sync-api: the 101st id was not stored", rows(alice).none { it.id == uuid(101) })
    }

    @Test
    fun `a duplicate id at the cap is a duplicate and not full`() {
        for (number in 1..PENDING_PER_ACCOUNT_MAX) insert(alice, number)
        assertEquals("sync-api: duplicate of the first row at 100", PendingInsertResult.Duplicate, insert(alice, 1, "other"))
        assertEquals("sync-api: duplicate of the last row at 100", PendingInsertResult.Duplicate, insert(alice, 100, "other"))
        assertEquals("sync-api: rows still 100", 100, rows(alice).size)
        assertEquals("sync-api: the first row text is unchanged", "box1", rows(alice).first { it.id == uuid(1) }.sealedBox)
    }

    @Test
    fun `the cap is per account`() {
        for (number in 1..PENDING_PER_ACCOUNT_MAX) insert(alice, number)
        assertEquals("sync-api: alice is full", PendingInsertResult.PendingFull, insert(alice, 101))
        assertEquals("sync-api: bob still stores", PendingInsertResult.Stored, insert(bob, 201))
        assertEquals("sync-api: bob's row", listOf(PendingSealedRow(uuid(201), "box201", stamp(201))), rows(bob))
    }

    @Test
    fun `an insert on a closed database fails and the caller sees the failure`() {
        temp.db.close()
        var failed = false
        try {
            insert(alice, 1)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("sync-api: a closed database must raise, not answer", failed)
    }

    @Test
    fun `the two limits are the chosen numbers and the largest sealed copy still fits a sync body`() {
        assertEquals("sync-api: rows per account", 100, PENDING_PER_ACCOUNT_MAX)
        assertEquals("sync-api: largest decoded sealed copy", 786_368, MAX_SEALED_BYTES)
        // The owner's record is the sealed bytes minus 48, plus 12 nonce and 16 tag, as base64, in a JSON body of at
        // most 107 characters (taken as 109).
        fun bodyOf(sealed: Int): Long = 4L * ((sealed - 20L + 2L) / 3L) + 109L
        assertEquals("sync-api: body for the largest copy", 1_048_573L, bodyOf(MAX_SEALED_BYTES))
        assertTrue("sync-api: the largest copy fits", bodyOf(MAX_SEALED_BYTES) <= SYNC_BODY_CAP_BYTES)
        assertTrue("sync-api: one byte more does not fit", bodyOf(MAX_SEALED_BYTES + 1) > SYNC_BODY_CAP_BYTES)
    }
}
