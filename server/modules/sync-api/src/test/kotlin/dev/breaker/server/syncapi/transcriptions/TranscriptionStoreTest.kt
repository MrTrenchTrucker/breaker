package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.sql.SQLException
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class TranscriptionStoreTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TranscriptionStore
    private var alice: Long = 0L
    private var bob: Long = 0L
    private val firstClient: String = TranscriptionFixtures.clientId(1)
    private val secondClient: String = TranscriptionFixtures.clientId(2)

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TranscriptionFixtures.store(temp, clock)
        alice = TranscriptionFixtures.accountId(temp, "alice")
        bob = TranscriptionFixtures.accountId(temp, "bob")
    }

    private fun assertRefusedByCheck(case: String, block: () -> Unit) {
        val failure = TestDatabases.expectFailure<SQLException>(case, block)
        assertTrue(
            "sync-api: $case was refused for another reason: ${failure.message}",
            failure.message.orEmpty().contains("check", ignoreCase = true),
        )
    }

    @Test
    fun `a second push of the same pair answers already held and keeps one row`() {
        assertEquals(PushResult.Stored, TranscriptionFixtures.push(store, alice, firstClient))
        assertEquals(PushResult.AlreadyHeld, TranscriptionFixtures.push(store, alice, firstClient))
        assertEquals("sync-api: one row for the pair", 1L, TranscriptionFixtures.rowCount(temp))
    }

    @Test
    fun `a repeated push keeps the first encrypted text and the first stored time`() {
        TranscriptionFixtures.push(store, alice, firstClient, encrypted = "first-text")
        clock.advance(Duration.ofSeconds(90))
        assertEquals(
            PushResult.AlreadyHeld,
            TranscriptionFixtures.push(store, alice, firstClient, encrypted = "second-text"),
        )
        val rows = TranscriptionFixtures.list(store, alice)
        assertEquals("sync-api: one row after the repeat", 1, rows.size)
        assertEquals("sync-api: the first text is kept", "first-text", rows.single().encrypted)
        assertEquals(
            "sync-api: the first stored time is kept",
            TokenFixtures.START.toEpochMilli(),
            rows.single().storedAtMs,
        )
    }

    @Test
    fun `a push at 999 milliseconds stores the whole second below it`() {
        clock.set(TokenFixtures.START.plusMillis(999))
        TranscriptionFixtures.push(store, alice, firstClient)
        assertEquals(
            "sync-api: the stored time is the whole second below the push",
            TokenFixtures.START.toEpochMilli(),
            TranscriptionFixtures.list(store, alice).single().storedAtMs,
        )
    }

    @Test
    fun `a push above the current key version answers version refused and stores nothing`() {
        val result = TranscriptionFixtures.push(store, alice, firstClient, keyVersion = KeyVersions.CURRENT + 1)
        assertEquals(PushResult.VersionRefused, result)
        assertEquals("sync-api: a refused push stores no row", 0L, TranscriptionFixtures.rowCount(temp))
    }

    @Test
    fun `a push at the current key version is stored with that version`() {
        val result = TranscriptionFixtures.push(store, alice, firstClient, keyVersion = KeyVersions.CURRENT)
        assertEquals(PushResult.Stored, result)
        assertEquals(
            "sync-api: the stored key version is the pushed one",
            KeyVersions.CURRENT,
            TranscriptionFixtures.list(store, alice).single().keyVersion,
        )
    }

    @Test
    fun `the existence check of a push looks only at the asking account`() {
        assertEquals(PushResult.Stored, TranscriptionFixtures.push(store, alice, firstClient))
        assertEquals(
            "sync-api: another account's push of the same client id is stored",
            PushResult.Stored,
            TranscriptionFixtures.push(store, bob, firstClient),
        )
        assertEquals("sync-api: each account keeps its own row", 2L, TranscriptionFixtures.rowCount(temp))
    }

    @Test
    fun `the store keeps the encrypted text exactly as it was pushed`() {
        val text = "  AbC+/9==  "
        TranscriptionFixtures.push(store, alice, firstClient, encrypted = text)
        assertEquals(
            "sync-api: the stored text must be the pushed text",
            text,
            TranscriptionFixtures.list(store, alice).single().encrypted,
        )
    }

    @Test
    fun `a push that the database check refuses throws and stores nothing`() {
        assertRefusedByCheck("a 35 character client id") {
            TranscriptionFixtures.push(store, alice, "x".repeat(35))
        }
        assertRefusedByCheck("a negative key version") {
            TranscriptionFixtures.push(store, alice, firstClient, keyVersion = -1)
        }
        assertRefusedByCheck("an empty encrypted text") {
            TranscriptionFixtures.push(store, alice, firstClient, encrypted = "")
        }
        assertEquals("sync-api: a refused push stores nothing", 0L, TranscriptionFixtures.rowCount(temp))
    }

    @Test
    fun `a push for an account that does not exist throws and stores nothing`() {
        val pushedText = "c2VjcmV0LXRleHQtbmV2ZXItbG9nZ2VkLTgwMA=="
        val missing = alice + 1000L
        val failure = TestDatabases.expectFailure<SQLException>("a push for a missing account") {
            TranscriptionFixtures.push(store, missing, firstClient, encrypted = pushedText)
        }
        assertTrue(
            "sync-api: the refusal must name the foreign key: ${failure.message}",
            failure.message.orEmpty().contains("foreign key", ignoreCase = true),
        )
        // The exception and every cause must not carry any part of the pushed text.
        var cause: Throwable? = failure
        while (cause != null) {
            val text = cause.message.orEmpty()
            assertFalse("sync-api: an exception message carries the encrypted text", text.contains(pushedText))
            assertFalse("sync-api: an exception message carries the start of the encrypted text", text.contains(pushedText.substring(0, 12)))
            assertFalse("sync-api: an exception message carries the end of the encrypted text", text.contains(pushedText.takeLast(12)))
            cause = cause.cause
        }
        assertEquals("sync-api: nothing stored for the missing account", 0L, TranscriptionFixtures.rowCount(temp))
    }

    @Test
    fun `the list returns only the rows of the asking account`() {
        TranscriptionFixtures.push(store, alice, firstClient)
        TranscriptionFixtures.push(store, bob, firstClient)
        TranscriptionFixtures.push(store, bob, secondClient)
        assertEquals(
            "sync-api: alice sees only her own row",
            listOf(firstClient),
            TranscriptionFixtures.list(store, alice).map { row -> row.clientId },
        )
        assertEquals(
            "sync-api: bob sees his two rows",
            listOf(firstClient, secondClient),
            TranscriptionFixtures.list(store, bob).map { row -> row.clientId },
        )
    }

    @Test
    fun `an account with nothing stored gets an empty list`() {
        TranscriptionFixtures.push(store, bob, firstClient)
        assertEquals(
            "sync-api: a fresh account has no rows",
            emptyList<StoredTranscription>(),
            TranscriptionFixtures.list(store, alice),
        )
    }
}
