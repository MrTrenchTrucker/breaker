package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.accounts.StoredVerifier
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.presetPrev
import dev.breaker.server.syncapi.auth.runSql
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Before

/**
 * The shared world of the reset store tests. bob (the first account, so the admin, id 1), Alice (id 2,
 * typed with a capital, the account that gets reset) and carol (id 3). Alice has key_version 3, a
 * previous key, five stored transcriptions (change_seq 5), two sessions, an agent token, two hand-off
 * codes and three wrong tries. bob has key_version 2, two transcriptions, a session, an agent token, a
 * hand-off code and two wrong tries. carol is keyless with one session. No reset code exists yet.
 */
internal abstract class ResetStoreTestBase : TempDatabaseTest() {

    protected val clock = MutableClock(TokenFixtures.START)
    protected lateinit var temp: TempDatabase
    protected lateinit var accounts: AccountStore
    protected lateinit var tokens: TokenStore
    protected lateinit var transcriptions: TranscriptionStore
    protected lateinit var store: ResetStore
    protected lateinit var newVerifier: StoredVerifier
    protected lateinit var aliceSession: IssuedToken
    protected lateinit var aliceOther: IssuedToken
    protected lateinit var aliceAgent: IssuedToken
    protected lateinit var bobSession: IssuedToken
    protected lateinit var bobAgent: IssuedToken
    protected lateinit var carolSession: IssuedToken
    protected var bob: Long = 0L
    protected var alice: Long = 0L
    protected var carol: Long = 0L

    @Before
    fun openWorld() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp, clock = clock)
        tokens = TokenFixtures.tokenStore(temp, clock)
        transcriptions = SyncRouteTestSupport.newStore(temp, clock)
        store = ResetStore(accounts, tokens, stepRandom())
        newVerifier = runBlocking { accounts.hashVerifier(AccountFixtures.verifier(77)) }
        bob = TokenFixtures.register(temp, "bob", 2).accountId
        alice = TokenFixtures.register(temp, "Alice", 1).accountId
        carol = TokenFixtures.register(temp, "carol", 3).accountId
        keyUp(bob, 2)
        keyUp(alice, 3)
        presetPrev(temp, alice, KeyPatterns.prevWrappedDek())
        runSql(temp, "UPDATE accounts SET rekeyed_at = ${KeyPatterns.REKEYED_AT_MS} WHERE id = $alice")
        bobSession = SyncRouteTestSupport.issuedSession(tokens, bob)
        bobAgent = SyncRouteTestSupport.issuedAgent(tokens, bob)
        carolSession = SyncRouteTestSupport.issuedSession(tokens, carol)
        aliceSession = SyncRouteTestSupport.issuedSession(tokens, alice)
        aliceOther = SyncRouteTestSupport.issuedSession(tokens, alice)
        aliceAgent = SyncRouteTestSupport.issuedAgent(tokens, alice)
        for (n in 0..4) push(alice, n, 3)
        for (n in 0..1) push(bob, n, 2)
        addHandoff(alice, 1)
        addHandoff(alice, 2)
        addHandoff(bob, 3)
        repeat(3) { wrongTry("alice") }
        repeat(2) { wrongTry("bob") }
    }

    // Every call fills the array with other bytes, so the code and the fresh verifier of a reset differ.
    private fun stepRandom(): SecureRandom = object : SecureRandom() {
        private var calls = 0

        override fun nextBytes(bytes: ByteArray) {
            calls++
            for (index in bytes.indices) {
                bytes[index] = (calls * 17 + index * 3 + 1).toByte()
            }
        }
    }

    private fun keyUp(id: Long, version: Int) {
        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(id, keys) }
        if (result !is StoreKeysResult.Stored) throw AssertionError("sync-api test helper: keys not stored, got $result")
        runSql(temp, "UPDATE accounts SET key_version = $version WHERE id = $id")
    }

    protected fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    protected fun push(id: Long, n: Int, keyVersion: Int) {
        val result = runBlocking { transcriptions.push(id, clientId(n), keyVersion, SyncRouteTestSupport.ENCRYPTED) }
        if (result != PushResult.Stored) throw AssertionError("sync-api test helper: push $n gave $result")
    }

    protected fun addHandoff(id: Long, seed: Int) {
        HandoffFixtures.insertRawRow(temp, ByteArray(32) { seed.toByte() }, id, 0L, AccountFixtures.FIXED_MILLIS + 100_000L)
    }

    protected fun wrongTry(name: String) {
        runBlocking { accounts.attempts.recordWrong(name) }
    }

    /** Writes the one code row of [id], replacing any row it has. The hash is [seed] repeated 32 times. */
    protected fun plantCode(id: Long, seed: Int, expiresAtMs: Long) {
        inTransaction(temp) { connection ->
            connection.prepareStatement("DELETE FROM reset_codes WHERE account_id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO reset_codes (account_id, code_hash, issued_at_ms, expires_at_ms) VALUES (?, ?, 1, ?)",
            ).use { statement ->
                statement.setLong(1, id)
                statement.setBytes(2, ByteArray(32) { seed.toByte() })
                statement.setLong(3, expiresAtMs)
                statement.executeUpdate()
            }
        }
    }

    protected fun reset(name: String): String {
        val result = runBlocking { store.resetAccount(name) }
        if (result !is ResetAccountResult.Reset) throw AssertionError("sync-api test helper: reset of $name gave $result")
        return result.code
    }

    protected fun request(): CompleteResetRequest =
        CompleteResetRequest(ByteArray(16) { i -> (i * 13 + 25).toByte() }, KdfParams(131072, 4, 2), 1, newVerifier)

    protected fun complete(name: String, code: String): CompleteResetResult =
        runBlocking { store.complete(name, code, request()) }

    protected fun completed(result: CompleteResetResult): CompleteResetResult.Completed {
        if (result !is CompleteResetResult.Completed) throw AssertionError("sync-api: expected Completed but got $result")
        return result
    }

    /** One value of the account row of [id], as SQL text: NULL, a number, X'..' for bytes. [expr] is written by a test. */
    protected fun col(expr: String, id: Long = alice): String = cell("SELECT quote($expr) FROM accounts WHERE id = $id")

    protected fun cell(sql: String): String = inTransaction(temp) { connection -> TestDatabases.strings(connection, sql).single() }

    protected fun num(sql: String): Long = inTransaction(temp) { connection -> TestDatabases.longs(connection, sql).single() }

    protected fun isLive(issued: IssuedToken): Boolean = runBlocking { tokens.peek(issued.secret.reveal()) } != null

    protected fun hashHex(code: String): String =
        AccountFixtures.hex(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(StandardCharsets.US_ASCII)))

    /** Every row of the tables a reset or a completion can touch. */
    protected fun worldState(): List<String> = tablesWhere("")

    /** The same rows for every account except Alice. */
    protected fun others(): List<String> = tablesWhere("alice")

    private fun tablesWhere(except: String): List<String> {
        val key = mapOf("accounts" to "id", "tokens" to "account_id", "signin_handoff" to "account_id", "signin_attempts" to "username_lower",
            "transcriptions" to "account_id", "reset_codes" to "account_id")
        val lines = ArrayList<String>()
        for ((table, column) in key) {
            val filter = if (except.isEmpty()) "" else " WHERE $column <> " + (if (column == "username_lower") "'alice'" else "$alice")
            lines.add("table $table")
            lines.addAll(rows("SELECT * FROM $table$filter ORDER BY rowid"))
        }
        return lines
    }

    protected fun rows(sql: String): List<String> = inTransaction<List<String>>(temp) { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { results ->
                val width = results.metaData.columnCount
                val lines = ArrayList<String>()
                while (results.next()) {
                    lines.add((1..width).joinToString(" ") { index -> render(results.getObject(index)) })
                }
                lines
            }
        }
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is ByteArray -> AccountFixtures.hex(value)
        else -> value.toString()
    }
}
