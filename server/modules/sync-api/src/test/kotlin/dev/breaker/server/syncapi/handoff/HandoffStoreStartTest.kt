package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What start stores: the code hash, the cap of live codes per account and the expiry. */
internal class HandoffStoreStartTest : TempDatabaseTest() {

    private val clock = MutableClock(HandoffFixtures.START)
    private val challenge = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    private val fiveMinutesMs = 5L * 60L * 1000L

    // The first registered account is the ADMIN, the second is a USER.
    private class World(val temp: TempDatabase, val store: HandoffStore, val admin: Long, val user: Long)

    private class StoredRow(
        val codeHash: ByteArray,
        val accountId: Long,
        val challenge: String,
        val sealedPackage: ByteArray,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    private fun world(): World {
        val temp = openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val store = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        val first = TokenFixtures.register(temp, "alice", 1)
        val second = TokenFixtures.register(temp, "bob", 2)
        return World(temp, store, first.accountId, second.accountId)
    }

    private fun startCode(w: World, accountId: Long): String {
        val result = runBlocking { w.store.start(accountId, challenge, HandoffFixtures.SEALED_PACKAGE_BYTES) }
        if (result !is StartResult.Started) {
            throw AssertionError("sync-api: expected Started but got $result")
        }
        return result.code
    }

    private fun hashOfCode(code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(code.toByteArray(java.nio.charset.StandardCharsets.US_ASCII))

    private fun filled(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    private fun hexOfCodes(codes: List<String>): List<String> =
        codes.map { code -> AccountFixtures.hex(hashOfCode(code)) }

    private fun storedHex(temp: TempDatabase): List<String> =
        HandoffFixtures.storedCodeHashes(temp).map { hash -> AccountFixtures.hex(hash) }

    private fun isTokenCharacter(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '_' || character == '-'

    private fun storedRows(temp: TempDatabase): List<StoredRow> =
        inTransaction(temp) { connection ->
            val sql = "SELECT code_hash, account_id, challenge, package, created_at_ms, expires_at_ms " +
                "FROM signin_handoff ORDER BY created_at_ms ASC, rowid ASC"
            connection.prepareStatement(sql).use { statement ->
                statement.executeQuery().use { rows ->
                    val values = ArrayList<StoredRow>()
                    while (rows.next()) {
                        values.add(
                            StoredRow(rows.getBytes(1), rows.getLong(2), rows.getString(3), rows.getBytes(4), rows.getLong(5), rows.getLong(6)),
                        )
                    }
                    values
                }
            }
        }

    @Test
    fun `a started code is 43 characters of the token alphabet and two starts give two codes`() {
        val w = world()
        val first = startCode(w, w.admin)
        val second = startCode(w, w.admin)
        assertEquals("sync-api: length of the first code", 43, first.length)
        assertEquals("sync-api: length of the second code", 43, second.length)
        assertTrue("sync-api: the first code has a character outside the token alphabet", first.all { c -> isTokenCharacter(c) })
        assertTrue("sync-api: the second code has a character outside the token alphabet", second.all { c -> isTokenCharacter(c) })
        assertFalse("sync-api: two starts must not give the same code", first == second)
    }

    @Test
    fun `only the hash of the code is stored and the code text is in no column`() {
        val w = world()
        val code = startCode(w, w.admin)
        val rows = storedRows(w.temp)
        assertEquals("sync-api: one start stores one row", 1, rows.size)
        val row = rows.single()
        assertArrayEquals("sync-api: the stored code_hash must be the SHA-256 of the code text", hashOfCode(code), row.codeHash)
        assertEquals("sync-api: the stored challenge must be the one given", challenge, row.challenge)
        assertArrayEquals("sync-api: the stored package must be the bytes given", HandoffFixtures.SEALED_PACKAGE_BYTES, row.sealedPackage)
        assertEquals("sync-api: the row belongs to the caller's account", w.admin, row.accountId)
        val challengeBytes = row.challenge.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        val everyColumn = row.codeHash + challengeBytes + row.sealedPackage
        assertFalse("sync-api: the code text must not appear in any column", AccountFixtures.containsBytes(everyColumn, code.toByteArray(java.nio.charset.StandardCharsets.US_ASCII)))
    }

    @Test
    fun `a row gets the created time now and an expiry exactly five minutes later`() {
        val w = world()
        val startMs = HandoffFixtures.START.toEpochMilli()
        startCode(w, w.admin)
        clock.advance(Duration.ofSeconds(7))
        startCode(w, w.user)
        val rows = storedRows(w.temp)
        assertEquals("sync-api: created times", listOf(startMs, startMs + 7_000L), rows.map { row -> row.createdAtMs })
        assertEquals("sync-api: expiry times", listOf(startMs + fiveMinutesMs, startMs + 7_000L + fiveMinutesMs), rows.map { row -> row.expiresAtMs })
    }

    @Test
    fun `five live codes then a sixth displaces the oldest and no other`() {
        val w = world()
        val codes = ArrayList<String>()
        repeat(5) {
            codes.add(startCode(w, w.admin))
            clock.advance(Duration.ofSeconds(1))
        }
        assertEquals("sync-api: five starts leave five rows", 5L, HandoffFixtures.rowCountFor(w.temp, w.admin))
        codes.add(startCode(w, w.admin))
        assertEquals("sync-api: a sixth start leaves five rows", 5L, HandoffFixtures.rowCountFor(w.temp, w.admin))
        assertEquals("sync-api: the oldest code must be the one displaced", hexOfCodes(codes.drop(1)), storedHex(w.temp))
    }

    @Test
    fun `with equal times the row written first is the oldest and is the one displaced`() {
        val w = world()
        val nowMs = HandoffFixtures.START.toEpochMilli()
        // Written in the order 5, 4, 3, 2, 1: the hash order is the reverse of the write order.
        for (seed in listOf(5, 4, 3, 2, 1)) {
            HandoffFixtures.insertRawRow(w.temp, filled(seed), w.admin, nowMs, nowMs + fiveMinutesMs)
        }
        val code = startCode(w, w.admin)
        assertEquals("sync-api: the first written row must be displaced, then the rest in write order, then the new code", listOf(
            AccountFixtures.hex(filled(4)),
            AccountFixtures.hex(filled(3)),
            AccountFixtures.hex(filled(2)),
            AccountFixtures.hex(filled(1)),
            AccountFixtures.hex(hashOfCode(code)),
        ), storedHex(w.temp))
    }

    @Test
    fun `an account with four live codes keeps all four when it starts a fifth`() {
        val w = world()
        val codes = ArrayList<String>()
        repeat(4) {
            codes.add(startCode(w, w.admin))
            clock.advance(Duration.ofSeconds(1))
        }
        codes.add(startCode(w, w.admin))
        assertEquals("sync-api: a fifth start leaves five rows", 5L, HandoffFixtures.rowCountFor(w.temp, w.admin))
        assertEquals("sync-api: no code may be displaced below the cap", hexOfCodes(codes), storedHex(w.temp))
    }

    @Test
    fun `a displacement leaves the codes of another account untouched`() {
        val w = world()
        val userCodes = ArrayList<String>()
        repeat(2) {
            userCodes.add(startCode(w, w.user))
            clock.advance(Duration.ofSeconds(1))
        }
        val adminCodes = ArrayList<String>()
        repeat(5) {
            adminCodes.add(startCode(w, w.admin))
            clock.advance(Duration.ofSeconds(1))
        }
        adminCodes.add(startCode(w, w.admin))
        assertEquals("sync-api: the user keeps both codes", 2L, HandoffFixtures.rowCountFor(w.temp, w.user))
        assertEquals("sync-api: the admin stays at five codes", 5L, HandoffFixtures.rowCountFor(w.temp, w.admin))
        val stored = storedHex(w.temp)
        for (code in userCodes) {
            assertTrue("sync-api: a code of the other account was displaced", stored.contains(hexOfCodes(listOf(code)).single()))
        }
        assertFalse("sync-api: the oldest admin code must be gone", stored.contains(hexOfCodes(listOf(adminCodes.first())).single()))
    }

    @Test
    fun `expired rows are gone after the next start and a live row stays`() {
        val w = world()
        val nowMs = HandoffFixtures.START.toEpochMilli()
        // Dead AT the instant, dead a millisecond before it, and live one millisecond after it.
        HandoffFixtures.insertRawRow(w.temp, filled(1), w.user, nowMs - fiveMinutesMs, nowMs)
        HandoffFixtures.insertRawRow(w.temp, filled(2), w.admin, nowMs - fiveMinutesMs, nowMs - 1L)
        HandoffFixtures.insertRawRow(w.temp, filled(3), w.user, nowMs, nowMs + 1L)
        val code = startCode(w, w.admin)
        assertEquals("sync-api: only the live row and the new code may remain", listOf(AccountFixtures.hex(filled(3)), AccountFixtures.hex(hashOfCode(code))), storedHex(w.temp))
    }

    @Test
    fun `an unknown account id gets NoSuchAccount and nothing is stored`() {
        val w = world()
        val result = runBlocking { w.store.start(w.user + 1000L, challenge, HandoffFixtures.SEALED_PACKAGE_BYTES) }
        assertSame("sync-api: an unknown account must get NoSuchAccount", StartResult.NoSuchAccount, result)
        assertEquals("sync-api: nothing may be stored for an unknown account", 0L, HandoffFixtures.rowCount(w.temp))
    }
}