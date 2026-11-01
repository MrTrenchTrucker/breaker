package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class TranscriptionStoreTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TranscriptionStore(temp.db, clock)
        // The first registered account is the admin and the second a user. The store does not
        // read roles, so the role does not matter here; both accounts start at key version 0.
        alice = TokenFixtures.register(temp, "alice", 1).accountId
        bob = TokenFixtures.register(temp, "bob", 2).accountId
    }

    private fun push(accountId: Long, clientId: String, keyVersion: Int, encrypted: String): PushResult =
        runBlocking { store.push(accountId, clientId, keyVersion, encrypted) }

    private fun rows(accountId: Long): List<TranscriptionRow> = runBlocking { store.list(accountId) }

    private fun setKeyVersion(accountId: Long, keyVersion: Int) =
        TranscriptionFixtures.setKeyVersion(temp, accountId, keyVersion)

    @Test
    fun `a new client id is stored with the account key version and the server clock`() {
        assertEquals(
            "sync-api: a new client id must be stored",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "first"),
        )
        assertEquals(
            "sync-api: the stored row",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 0, "first", AccountFixtures.FIXED_MILLIS)),
            rows(alice),
        )
    }

    @Test
    fun `a repeat of the same pair is a no-op and leaves one row`() {
        assertEquals(
            "sync-api: first push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "first"),
        )
        assertEquals(
            "sync-api: the repeat of the same pair must be a no-op",
            PushResult.NoOp,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "first"),
        )
        assertEquals("sync-api: a repeat must not add a row", 1, rows(alice).size)
    }

    @Test
    fun `the same pair with other text is a no-op and keeps the first text`() {
        assertEquals(
            "sync-api: first push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "first"),
        )
        assertEquals(
            "sync-api: the same pair with other text must be a no-op",
            PushResult.NoOp,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "second"),
        )
        assertEquals(
            "sync-api: the stored text must not be overwritten",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 0, "first", AccountFixtures.FIXED_MILLIS)),
            rows(alice),
        )
    }

    @Test
    fun `a higher key version replaces the row and leaves one row with the new clock time`() {
        assertEquals("sync-api: first push", PushResult.Stored, push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "old"))
        setKeyVersion(alice, 1)
        clock.advance(Duration.ofSeconds(5))
        assertEquals(
            "sync-api: a higher key version must replace the row",
            PushResult.Replaced,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "new"),
        )
        assertEquals(
            "sync-api: the replaced row",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 1, "new", AccountFixtures.FIXED_MILLIS + 5_000L)),
            rows(alice),
        )
        assertEquals("sync-api: the replace must leave one row", 1L, TranscriptionFixtures.rowCount(temp, alice))
    }

    @Test
    fun `a lower key version is refused and the stored row is kept`() {
        setKeyVersion(alice, 1)
        assertEquals("sync-api: first push", PushResult.Stored, push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "v1"))
        assertEquals(
            "sync-api: a lower key version must be refused",
            PushResult.RefusedLower,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "v0"),
        )
        assertEquals(
            "sync-api: the stored row must be kept",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 1, "v1", AccountFixtures.FIXED_MILLIS)),
            rows(alice),
        )
    }

    @Test
    fun `a push above the account key version is refused even when the client id is stored`() {
        assertEquals("sync-api: first push", PushResult.Stored, push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "old"))
        assertEquals(
            "sync-api: a push above the account key version must be refused before the row lookup",
            PushResult.RefusedAboveAccount,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "new"),
        )
        assertEquals(
            "sync-api: the stored row must not be replaced",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 0, "old", AccountFixtures.FIXED_MILLIS)),
            rows(alice),
        )
    }

    @Test
    fun `a new client id above the account key version is refused and nothing is stored`() {
        assertEquals(
            "sync-api: a new client id above the account key version must be refused",
            PushResult.RefusedAboveAccount,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "x"),
        )
        assertEquals("sync-api: nothing may be stored", emptyList<TranscriptionRow>(), rows(alice))
        // Control: the same push is stored once the account is at that key version.
        setKeyVersion(alice, 1)
        assertEquals(
            "sync-api: control, the same push at the account key version",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 1, "x"),
        )
    }

    @Test
    fun `a new client id below the account key version is refused and the same id at that version is stored`() {
        setKeyVersion(alice, 1)
        assertEquals(
            "sync-api: a new client id below the account key version must be refused",
            PushResult.RefusedLower,
            push(alice, TranscriptionFixtures.CLIENT_TWO, 0, "below"),
        )
        assertEquals("sync-api: nothing may be stored", emptyList<TranscriptionRow>(), rows(alice))
        // Control: the same client id at the account key version is stored.
        assertEquals(
            "sync-api: control, the same client id at the account key version",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_TWO, 1, "at"),
        )
    }

    @Test
    fun `two accounts with the same client id do not touch each other`() {
        assertEquals(
            "sync-api: alice's push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "alice text"),
        )
        assertEquals(
            "sync-api: bob's push of the same client id is a new row, not a no-op",
            PushResult.Stored,
            push(bob, TranscriptionFixtures.CLIENT_ONE, 0, "bob text"),
        )
        setKeyVersion(bob, 1)
        assertEquals(
            "sync-api: bob's replace",
            PushResult.Replaced,
            push(bob, TranscriptionFixtures.CLIENT_ONE, 1, "bob newer"),
        )
        assertEquals(
            "sync-api: alice's row must be unchanged by bob's replace",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 0, "alice text", AccountFixtures.FIXED_MILLIS)),
            rows(alice),
        )
        assertEquals(
            "sync-api: bob's own row",
            listOf(TranscriptionRow(TranscriptionFixtures.CLIENT_ONE, 1, "bob newer", AccountFixtures.FIXED_MILLIS)),
            rows(bob),
        )
    }

    @Test
    fun `a replace keeps the row where it was in the list`() {
        // Insert order THREE, ONE, TWO differs from the client id order (ONE, TWO, THREE). The times
        // rise with the insert order, so a sort by client id or by time would move the replaced row.
        assertEquals(
            "sync-api: first push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_THREE, 0, "three"),
        )
        clock.advance(Duration.ofSeconds(1))
        assertEquals(
            "sync-api: second push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "one"),
        )
        clock.advance(Duration.ofSeconds(1))
        assertEquals(
            "sync-api: third push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_TWO, 0, "two"),
        )
        setKeyVersion(alice, 1)
        clock.advance(Duration.ofSeconds(1))
        assertEquals(
            "sync-api: the replace of the first inserted row",
            PushResult.Replaced,
            push(alice, TranscriptionFixtures.CLIENT_THREE, 1, "three again"),
        )
        val listed = rows(alice)
        assertEquals(
            "sync-api: the replaced row must keep its place at the front",
            listOf(
                TranscriptionFixtures.CLIENT_THREE,
                TranscriptionFixtures.CLIENT_ONE,
                TranscriptionFixtures.CLIENT_TWO,
            ),
            listed.map { row -> row.clientId },
        )
        assertEquals("sync-api: the replaced row text", "three again", listed[0].encrypted)
        assertEquals("sync-api: the replaced row key version", 1, listed[0].keyVersion)
        assertEquals(
            "sync-api: the replaced row carries the new time",
            AccountFixtures.FIXED_MILLIS + 3_000L,
            listed[0].storedAtMs,
        )
    }

    @Test
    fun `an account with no rows lists nothing`() {
        assertEquals("sync-api: an empty account must list nothing", emptyList<TranscriptionRow>(), rows(alice))
    }

    @Test
    fun `a push for an account id with no row throws an illegal state error`() {
        val missingAccount = 9999L
        TestDatabases.expectFailure<IllegalStateException>("push for a missing account") {
            push(missingAccount, TranscriptionFixtures.CLIENT_ONE, 0, "x")
        }
        // Control: the same push for an account that exists is stored.
        assertEquals(
            "sync-api: control, the push for an existing account",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "x"),
        )
    }

    @Test
    fun `rows are listed in insert order and not in client id order`() {
        // The client ids sort as ONE, TWO, THREE; the rows are inserted as THREE, ONE, TWO.
        assertEquals(
            "sync-api: first push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_THREE, 0, "three"),
        )
        assertEquals(
            "sync-api: second push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "one"),
        )
        assertEquals(
            "sync-api: third push",
            PushResult.Stored,
            push(alice, TranscriptionFixtures.CLIENT_TWO, 0, "two"),
        )
        assertEquals(
            "sync-api: the list must follow the insert order",
            listOf(
                TranscriptionFixtures.CLIENT_THREE,
                TranscriptionFixtures.CLIENT_ONE,
                TranscriptionFixtures.CLIENT_TWO,
            ),
            rows(alice).map { row -> row.clientId },
        )
    }
}
