package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// TranscriptionStore.pull, at store level: the page it returns, the session number it sets, the purge
// at the floor of the live sessions, and the rollback of the number when the purge fails. Every change
// number comes from a real push or delete; the only raw SQL is the reads and the trigger.
internal class TranscriptionStorePullTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var tokens: TokenStore
    private lateinit var records: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        records = SyncRouteTestSupport.newStore(temp)
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
        push(bob, 1)
    }

    // One digit makes the client id, built from the same short pieces as the other store tests.
    private fun clientId(n: Int): String = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + n.toString().padStart(12, '0')

    private fun push(accountId: Long, n: Int) {
        val result = runBlocking { records.push(accountId, clientId(n), 0, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push $n", PushResult.Stored, result)
    }

    private fun delete(accountId: Long, n: Int) {
        val result = runBlocking { records.delete(accountId, clientId(n)) }
        assertEquals("sync-api: setup delete $n", DeleteResult.Deleted, result)
    }

    private fun newSession(accountId: Long): IssuedToken =
        SyncRouteTestSupport.issuedSession(tokens, accountId)

    private fun pull(accountId: Long, session: IssuedToken, since: Long?): PageResult =
        runBlocking { records.pull(tokens, accountId, session.tokenId, since) }

    private fun pageOf(accountId: Long, since: Long?): PageResult =
        runBlocking { records.page(accountId, since) }

    private fun revoke(session: IssuedToken): Boolean = runBlocking { tokens.revokeSession(session.tokenId) }

    private fun changeNumbers(accountId: Long): List<Long> = inTransaction(temp) { connection ->
        TestDatabases.longs(
            connection,
            "SELECT change_no FROM transcriptions WHERE account_id = $accountId ORDER BY change_no",
        )
    }

    private fun purgedThrough(accountId: Long): Long = inTransaction(temp) { connection ->
        TestDatabases.longs(connection, "SELECT purged_through FROM accounts WHERE id = $accountId").single()
    }

    // The number a session holds: null when it has none. A raw read, the store never returns it.
    private fun storedNumber(tokenId: Long): Long? = inTransaction<Long?>(temp) { connection ->
        connection.prepareStatement("SELECT last_pulled_change FROM tokens WHERE id = ?").use { statement ->
            statement.setLong(1, tokenId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api test: no token row with id $tokenId" }
                val value = rows.getLong(1)
                if (rows.wasNull()) null else value
            }
        }
    }

    // Makes every purge of a delete record fail: the DELETE in the purge aborts, and the transaction
    // rolls back.
    private fun createFailingPurgeTrigger() {
        inTransaction(temp) { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TRIGGER pull_purge_failure BEFORE DELETE ON transcriptions " +
                        "WHEN OLD.deleted = 1 BEGIN SELECT RAISE(ABORT, 'forced'); END",
                )
            }
        }
    }

    private fun dropFailingPurgeTrigger() {
        inTransaction(temp) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER pull_purge_failure")
            }
        }
    }

    // The pulled page is the page the reader gives for the same since: same changes, clients, next and more.
    private fun assertSamePage(expected: PageResult, actual: PageResult) {
        val want = expected as PageResult.Page
        val got = actual as PageResult.Page
        assertEquals(
            "sync-api: pulled changes",
            want.records.map { it.change },
            got.records.map { it.change },
        )
        assertEquals(
            "sync-api: pulled clients",
            want.records.map { it.clientId },
            got.records.map { it.clientId },
        )
        assertEquals("sync-api: pulled next", want.next, got.next)
        assertEquals("sync-api: pulled more", want.more, got.more)
    }

    @Test
    fun `pull with a since returns the same page as page and sets the session number`() {
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        val session = newSession(alice)
        val expected = pageOf(alice, 1L)
        val pulled = pull(alice, session, 1L)
        assertSamePage(expected, pulled)
        val got = pulled as PageResult.Page
        assertEquals(
            "sync-api: the page holds change 2 and the delete record at 3",
            listOf(2L, 3L),
            got.records.map { it.change },
        )
        assertFalse("sync-api: no more", got.more)
        assertEquals("sync-api: next is the head", 3L, got.next)
        assertEquals("sync-api: the session holds number 1", 1L, storedNumber(session.tokenId))
        assertEquals("sync-api: nothing purged at floor 1", 0L, purgedThrough(alice))
    }

    @Test
    fun `pull with no since returns the same page as page and sets no number`() {
        push(alice, 1)
        push(alice, 2)
        val session = newSession(alice)
        val expected = pageOf(alice, null)
        val pulled = pull(alice, session, null)
        assertSamePage(expected, pulled)
        val got = pulled as PageResult.Page
        assertEquals("sync-api: two live records", listOf(1L, 2L), got.records.map { it.change })
        assertNull("sync-api: a pull with no since sets no number", storedNumber(session.tokenId))
        assertEquals("sync-api: purged_through unchanged", 0L, purgedThrough(alice))
    }

    @Test
    fun `a Gone pull writes no number and no purged_through and no row`() {
        // Changes: push 1 is 1, push 2 is 2, delete 1 is 3, delete 2 is 4, push 3 is 5.
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        delete(alice, 2)
        push(alice, 3)
        val early = newSession(alice)
        val late = newSession(alice)
        pull(alice, late, 5L)
        pull(alice, early, 5L)
        assertEquals("sync-api: setup number 5 on the late session", 5L, storedNumber(late.tokenId))
        assertEquals("sync-api: setup number 5 on the early session", 5L, storedNumber(early.tokenId))
        assertEquals("sync-api: setup purged_through 4", 4L, purgedThrough(alice))
        assertEquals("sync-api: setup change numbers", listOf(5L), changeNumbers(alice))

        val fresh = newSession(alice)
        val gone = pull(alice, fresh, 2L)
        assertTrue("sync-api: a since below purged_through is Gone", gone is PageResult.Gone)
        assertNull("sync-api: the Gone pull sets no number", storedNumber(fresh.tokenId))
        assertEquals("sync-api: the Gone pull writes no purged_through", 4L, purgedThrough(alice))
        assertEquals("sync-api: the Gone pull writes no row", listOf(5L), changeNumbers(alice))
        assertEquals("sync-api: the other sessions keep 5", 5L, storedNumber(early.tokenId))

        // Accept control: the same session pulls at the purged_through and gets a page.
        val accepted = pull(alice, fresh, 4L)
        assertTrue("sync-api: control, a since at purged_through is a page", accepted is PageResult.Page)
        assertEquals("sync-api: control, the session holds number 4", 4L, storedNumber(fresh.tokenId))
    }

    @Test
    fun `a pull purges the delete record at the floor and keeps the one above`() {
        // Changes: push 1 is 1, push 2 is 2, delete 1 is 3, delete 2 is 4.
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        delete(alice, 2)
        val lagging = newSession(alice)
        val caller = newSession(alice)
        pull(alice, lagging, 3L)
        assertEquals("sync-api: setup, no purge while the caller has no number", 0L, purgedThrough(alice))

        pull(alice, caller, 4L)

        assertEquals("sync-api: the floor is the lagging number 3", 3L, storedNumber(lagging.tokenId))
        assertEquals("sync-api: purged_through is the removed number 3", 3L, purgedThrough(alice))
        assertEquals("sync-api: change 4 is kept", listOf(4L), changeNumbers(alice))
        assertEquals("sync-api: another account keeps its row", listOf(1L), changeNumbers(bob))
    }

    @Test
    fun `a pull with no since still purges when the lagging session is revoked`() {
        // Changes: push 1 is 1, push 2 is 2, delete 1 is 3, delete 2 is 4.
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        delete(alice, 2)
        val lagging = newSession(alice)
        val caller = newSession(alice)
        pull(alice, lagging, 3L)
        pull(alice, caller, 4L)
        assertEquals("sync-api: setup, the lagging number holds the floor at 3", 3L, purgedThrough(alice))

        assertTrue("sync-api: setup, the lagging session is revoked", revoke(lagging))
        val pulled = pull(alice, caller, null)

        assertTrue("sync-api: the pull with no since is a page", pulled is PageResult.Page)
        assertEquals("sync-api: no since keeps the number 4", 4L, storedNumber(caller.tokenId))
        assertEquals("sync-api: the purge reaches 4 once the lagging one is gone", 4L, purgedThrough(alice))
        assertEquals("sync-api: no delete record is left", emptyList<Long>(), changeNumbers(alice))
    }

    @Test
    fun `a lagging session holds a delete record the caller has passed`() {
        // Changes: push 1 is 1, push 2 is 2, delete 1 is 3.
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        val lagging = newSession(alice)
        val caller = newSession(alice)
        pull(alice, lagging, 2L)
        pull(alice, caller, 3L)

        assertEquals("sync-api: the lagging session holds 2", 2L, storedNumber(lagging.tokenId))
        assertEquals("sync-api: the caller holds 3", 3L, storedNumber(caller.tokenId))
        assertEquals("sync-api: the delete record at 3 is held", 0L, purgedThrough(alice))
        assertEquals("sync-api: change numbers held", listOf(2L, 3L), changeNumbers(alice))

        // The lagging session passes the record: the purge now removes it.
        pull(alice, lagging, 3L)
        assertEquals("sync-api: purged_through is 3 after the release", 3L, purgedThrough(alice))
        assertEquals("sync-api: change 2 is kept", listOf(2L), changeNumbers(alice))
    }

    @Test
    fun `a failing purge rolls the number back and writes nothing`() {
        // Changes: push 1 is 1, push 2 is 2, delete 1 is 3.
        push(alice, 1)
        push(alice, 2)
        delete(alice, 1)
        val session = newSession(alice)
        val changesBefore = changeNumbers(alice)
        val purgedBefore = purgedThrough(alice)

        createFailingPurgeTrigger()
        var thrown: Exception? = null
        try {
            pull(alice, session, 3L)
        } catch (failure: Exception) {
            thrown = failure
        } finally {
            dropFailingPurgeTrigger()
        }
        assertNotNull("sync-api: the forced purge failure reached the caller", thrown)
        assertNull("sync-api: the number set before the purge is rolled back", storedNumber(session.tokenId))
        assertEquals("sync-api: purged_through unchanged", purgedBefore, purgedThrough(alice))
        assertEquals("sync-api: change numbers unchanged", changesBefore, changeNumbers(alice))

        // Accept control: with the trigger dropped the same pull sets the number and purges.
        pull(alice, session, 3L)
        assertEquals("sync-api: control, the number is 3", 3L, storedNumber(session.tokenId))
        assertEquals("sync-api: control, purged_through is 3", 3L, purgedThrough(alice))
        assertEquals("sync-api: control, change 2 is kept", listOf(2L), changeNumbers(alice))
    }
}
