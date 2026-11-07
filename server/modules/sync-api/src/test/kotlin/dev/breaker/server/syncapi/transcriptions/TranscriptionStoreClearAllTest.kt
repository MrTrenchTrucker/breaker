package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TestDatabases
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Covers TranscriptionStore.clearAll: live rows only, one change number per row, delete records
 * in the feed, and nothing touching already-deleted rows or other accounts. */
internal class TranscriptionStoreClearAllTest {

    private lateinit var temp: TempDatabase
    private lateinit var store: TranscriptionStore

    // 36 characters: 8+1+4+1+4+1+4+1+12
    private fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    @Before
    fun setUp() {
        temp = TestDatabases.openTemp()
        store = TranscriptionStore(temp.db, AccountFixtures.fixedClock)
    }

    @After
    fun tearDown() {
        temp.close()
    }

    private fun push(account: Long, client: String, version: Int, encrypted: String): Long =
        runBlocking {
            val result = store.push(account, client, version, encrypted)
            check(result is PushResult.Stored) { "push refused: $result" }
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_no FROM transcriptions WHERE account_id = ? AND client_id = ?")
                    .use { statement ->
                        statement.setLong(1, account)
                        statement.setString(2, client)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no row for $client" }
                            rows.getLong(1)
                        }
                    }
            }
        }

    private fun changeSeqOf(account: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { statement ->
                    statement.setLong(1, account)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "no account $account" }
                        rows.getLong(1)
                    }
                }
            }
        }

    private fun liveRowsOf(account: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 0")
                    .use { statement ->
                        statement.setLong(1, account)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no count" }
                            rows.getLong(1)
                        }
                    }
            }
        }

    private fun deletedRowsOf(account: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT count(*) FROM transcriptions WHERE account_id = ? AND deleted = 1")
                    .use { statement ->
                        statement.setLong(1, account)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no count" }
                            rows.getLong(1)
                        }
                    }
            }
        }

    private fun pageOf(account: Long): String =
        runBlocking {
            val result = store.page(account, 0L, null)
            when (result) {
                is PageResult.Page -> result.records.map { "${it.clientId}:${it.change}:${if (it.deleted) "deleted" else "live"}" }.joinToString(",")
                is PageResult.Gone -> "GONE"
            }
        }

    @Test
    fun `marks live rows only one change number per row in change order after the head`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        push(alice, id(2), 0, "bbb")
        val headBefore = changeSeqOf(alice)
        val marked = runBlocking { store.clearAll(alice) }
        assertEquals("marked count", 2, marked)
        assertEquals("change seq after", headBefore + 2, changeSeqOf(alice))
        assertEquals("live rows after", 0, liveRowsOf(alice))
        assertEquals("deleted rows after", 2, deletedRowsOf(alice))
        val page = pageOf(alice)
        assertTrue("page has delete records", page.contains("deleted"))
        val changes = page.split(",").map { it.split(":")[1].toInt() }
        assertTrue("change numbers after head", changes.all { it > headBefore })
        assertTrue("change numbers in order", changes.sorted() == changes)
    }

    @Test
    fun `a row already deleted is untouched and the head does not move for it`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        push(alice, id(2), 0, "bbb")
        runBlocking { store.delete(alice, id(1)) }
        val headBefore = changeSeqOf(alice)
        val marked = runBlocking { store.clearAll(alice) }
        assertEquals("only the live row marked", 1, marked)
        assertEquals("head moves by one", headBefore + 1, changeSeqOf(alice))
        val page = pageOf(alice)
        assertTrue("two delete records", page.split(",").filter { it.contains("deleted") }.size == 2)
        assertEquals("live rows zero", 0, liveRowsOf(alice))
        assertEquals("deleted rows two", 2, deletedRowsOf(alice))
    }

    @Test
    fun `a repeat clears nothing and answers zero`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        val headBefore = changeSeqOf(alice)
        val first = runBlocking { store.clearAll(alice) }
        assertEquals("first clears one", 1, first)
        val second = runBlocking { store.clearAll(alice) }
        assertEquals("repeat answers zero", 0, second)
        assertEquals("head did not move", headBefore + 1, changeSeqOf(alice))
    }

    @Test
    fun `another account untouched`() = runBlocking {
        val alice = register("Alice", 1)
        val bob = register("Bob", 2)
        push(alice, id(1), 0, "aaa")
        push(bob, id(2), 0, "bbb")
        val bobBefore = changeSeqOf(bob)
        val marked = runBlocking { store.clearAll(alice) }
        assertEquals("alice cleared", 1, marked)
        assertEquals("bob head unchanged", bobBefore, changeSeqOf(bob))
        assertEquals("bob live rows one", 1, liveRowsOf(bob))
        assertEquals("bob deleted rows zero", 0, deletedRowsOf(bob))
    }

    @Test
    fun `cleared rows bodies are gone and deleted set`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        runBlocking { store.clearAll(alice) }
        temp.db.transaction { connection ->
            connection.prepareStatement("SELECT encrypted, stored_at_ms, deleted FROM transcriptions WHERE account_id = ?")
                .use { statement ->
                    statement.setLong(1, alice)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "no row" }
                        assertEquals("body cleared", "-", rows.getString(1))
                        assertEquals("stored_at_ms set", AccountFixtures.FIXED_MILLIS, rows.getLong(2))
                        assertEquals("deleted set", 1, rows.getInt(3))
                    }
                }
        }
    }

    @Test
    fun `an account with no live row answers zero`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        runBlocking { store.delete(alice, id(1)) }
        val marked = runBlocking { store.clearAll(alice) }
        assertEquals("zero live rows", 0, marked)
    }

    @Test
    fun `head after equals head before plus rows marked`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        push(alice, id(2), 0, "bbb")
        push(alice, id(3), 0, "ccc")
        val headBefore = changeSeqOf(alice)
        val marked = runBlocking { store.clearAll(alice) }
        assertEquals("three marked", 3, marked)
        assertEquals("head after", headBefore + 3, changeSeqOf(alice))
    }

    @Test
    fun `each cleared row takes the next change number in order`() = runBlocking {
        val alice = register("Alice", 1)
        push(alice, id(1), 0, "aaa")
        push(alice, id(2), 0, "bbb")
        val headBefore = changeSeqOf(alice)
        runBlocking { store.clearAll(alice) }
        val page = runBlocking { store.page(alice, 0L, null) }
        check(page is PageResult.Page) { "unexpected page $page" }
        assertEquals(
            "the delete records carry the next two numbers",
            listOf(headBefore + 1, headBefore + 2),
            page.records.map { it.change },
        )
        assertEquals("the head is the last number", headBefore + 2, changeSeqOf(alice))
    }

    private fun register(name: String, seed: Int): Long =
        runBlocking {
            val result = AccountFixtures.newStore(temp).register(
                name, AccountFixtures.salt(seed), AccountFixtures.validKdf,
                1, AccountFixtures.verifier(seed),
            )
            check(result is dev.breaker.server.syncapi.accounts.RegisterResult.Registered) { "not registered: $result" }
            result.accountId
        }
}
