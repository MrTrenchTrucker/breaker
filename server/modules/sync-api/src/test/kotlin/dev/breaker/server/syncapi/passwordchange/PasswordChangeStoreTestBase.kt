package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import dev.breaker.server.syncapi.accounts.StoredVerifier
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.auth.presetPrev
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.DeleteResult
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before

/**
 * The shared world of the password change store tests: alice and bob are keyed at key_version 1,
 * alice has two sessions and an agent token, bob has one session. Nothing is written until a test asks.
 */
internal abstract class PasswordChangeStoreTestBase : TempDatabaseTest() {

    protected lateinit var temp: TempDatabase
    protected lateinit var accounts: AccountStore
    protected lateinit var tokens: TokenStore
    protected lateinit var store: PasswordChangeStore
    protected lateinit var transcriptions: TranscriptionStore
    protected lateinit var newVerifier: StoredVerifier
    protected lateinit var session: IssuedToken
    protected lateinit var otherSession: IssuedToken
    protected lateinit var agent: IssuedToken
    protected lateinit var bobSession: IssuedToken
    protected var alice: Long = 0L
    protected var bob: Long = 0L

    private val columnNames = listOf(
        "salt", "kdf_memory_kib", "kdf_iterations", "kdf_parallelism", "kdf_version",
        "verifier_algo", "verifier_iters", "verifier_salt", "verifier_hash",
        "wrapped_dek", "wrapped_box_privkey", "box_pubkey", "prev_wrapped_dek", "key_version", "rekeyed_at",
    )

    @Before
    fun openWorld() {
        temp = openTemp()
        accounts = AccountFixtures.newStore(temp)
        tokens = TokenFixtures.tokenStore(temp, AccountFixtures.fixedClock)
        store = PasswordChangeStore(accounts, tokens)
        transcriptions = SyncRouteTestSupport.newStore(temp)
        alice = keyedAccount("alice", 1)
        bob = keyedAccount("bob", 2)
        session = SyncRouteTestSupport.issuedSession(tokens, alice)
        otherSession = SyncRouteTestSupport.issuedSession(tokens, alice)
        agent = SyncRouteTestSupport.issuedAgent(tokens, alice)
        bobSession = SyncRouteTestSupport.issuedSession(tokens, bob)
        newVerifier = runBlocking { accounts.hashVerifier(AccountFixtures.verifier(77)) }
    }

    /** A registered account with the base key patterns stored, so its key_version is 1. */
    protected fun keyedAccount(name: String, seed: Int): Long {
        val id = TokenFixtures.register(temp, name, seed).accountId
        val keys = KeyBundle(KeyPatterns.wrappedDek(), KeyPatterns.wrappedBoxPrivkey(), KeyPatterns.boxPubkey())
        val result = runBlocking { accounts.storeKeys(id, keys) }
        check(result is StoreKeysResult.Stored) { "sync-api test helper: the keys of $name were not stored" }
        return id
    }

    /** The request of a change; [shift] moves every byte pattern so two requests differ in every key member. */
    protected fun newRequest(keyVersion: Int = 2, shift: Int = 0): PasswordChangeRequest =
        PasswordChangeRequest(
            ByteArray(16) { i -> (i * 13 + 25 + shift).toByte() },
            KdfParams(131072, 4, 2),
            1,
            newVerifier,
            ByteArray(60) { i -> (i * 3 + 21 + shift).toByte() },
            ByteArray(60) { i -> (i * 5 + 22 + shift).toByte() },
            ByteArray(32) { i -> (i * 7 + 23 + shift).toByte() },
            ByteArray(60) { i -> (i * 11 + 24 + shift).toByte() },
            keyVersion,
        )

    protected fun change(accountId: Long, tokenId: Long, request: PasswordChangeRequest): PasswordChangeResult =
        runBlocking { store.change(accountId, tokenId, request) }

    protected fun changed(result: PasswordChangeResult): PasswordChangeResult.Changed {
        if (result !is PasswordChangeResult.Changed) {
            throw AssertionError("sync-api: expected Changed but got $result")
        }
        return result
    }

    /** The account columns a change writes, as text, in the order of [expectedColumns]. */
    protected fun snapshot(accountId: Long): List<String> =
        rowsOf("SELECT " + columnNames.joinToString(", ") + " FROM accounts WHERE id = " + accountId).single()

    /** What a change with [request] must leave in the columns; [prev] is the text of prev_wrapped_dek. */
    protected fun expectedColumns(request: PasswordChangeRequest, prev: String): List<String> = listOf(
        AccountFixtures.hex(request.newSalt),
        "131072", "4", "2", "1",
        newVerifier.algo,
        newVerifier.iterations.toString(),
        AccountFixtures.hex(newVerifier.salt),
        AccountFixtures.hex(newVerifier.hash),
        AccountFixtures.hex(request.newWrappedDek),
        AccountFixtures.hex(request.newWrappedBoxPrivkey),
        AccountFixtures.hex(request.newBoxPubkey),
        prev,
        request.newKeyVersion.toString(),
        AccountFixtures.FIXED_MILLIS.toString(),
    )

    protected fun assertColumns(accountId: Long, expected: List<String>, label: String) {
        val actual = snapshot(accountId)
        for ((index, name) in columnNames.withIndex()) {
            assertEquals("sync-api: $label column $name", expected[index], actual[index])
        }
    }

    /** Every row the change can touch, as text: both accounts, tokens, hand-off codes, wrong tries, transcriptions. */
    protected fun fullState(): List<String> {
        val lines = ArrayList<String>()
        lines.add("alice " + snapshot(alice).joinToString(" "))
        lines.add("bob " + snapshot(bob).joinToString(" "))
        val queries = listOf(
            "SELECT 'tokens', id, kind, revoked_at_ms, expires_at_ms FROM tokens ORDER BY id",
            "SELECT 'handoff', account_id, hex(code_hash) FROM signin_handoff ORDER BY code_hash",
            "SELECT 'attempts', username_lower, wrong_count, locked_until_ms, last_attempt_ms " +
                "FROM signin_attempts ORDER BY username_lower",
            "SELECT 'rows', account_id, client_id, key_version, deleted, change_no FROM transcriptions ORDER BY id",
        )
        for (query in queries) {
            for (row in rowsOf(query)) {
                lines.add(row.joinToString(" "))
            }
        }
        return lines
    }

    // [sql] is always written by a test, never input.
    private fun rowsOf(sql: String): List<List<String>> =
        inTransaction<List<List<String>>>(temp) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val width = rows.metaData.columnCount
                    val lines = ArrayList<List<String>>()
                    while (rows.next()) {
                        lines.add((1..width).map { index -> render(rows.getObject(index)) })
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

    /** A not deleted transcription of [accountId] at key_version 1. */
    protected fun pushOld(accountId: Long, clientId: String) {
        val result = runBlocking { transcriptions.push(accountId, clientId, 1, SyncRouteTestSupport.ENCRYPTED) }
        assertEquals("sync-api: setup push of $clientId", PushResult.Stored, result)
    }

    protected fun deleteRow(accountId: Long, clientId: String) {
        val result = runBlocking { transcriptions.delete(accountId, clientId) }
        assertEquals("sync-api: setup delete of $clientId", DeleteResult.Deleted, result)
    }

    /** A 36 character client id that differs from the shared ones; [n] is one digit. */
    protected fun clientId(n: Int): String = SyncRouteTestSupport.CLIENT_ID.dropLast(1) + n.toString()

    protected fun holdPrev() {
        presetPrev(temp, alice, KeyPatterns.prevWrappedDek())
    }

    protected fun wrongTries(name: String, count: Int) {
        repeat(count) { runBlocking { accounts.verify(name, AccountFixtures.verifier(99)) } }
    }

    protected fun wrongCountOf(name: String): Long? {
        val info = runBlocking { accounts.attempts.attemptOf(name) }
        return info?.wrongCount
    }

    protected fun addCodes(accountId: Long, vararg seeds: Int) {
        for (seed in seeds) {
            val hash = ByteArray(32) { seed.toByte() }
            HandoffFixtures.insertRawRow(temp, hash, accountId, 0L, AccountFixtures.FIXED_MILLIS + 100_000L)
        }
    }

    protected fun codeCount(accountId: Long): Long = HandoffFixtures.rowCountFor(temp, accountId)

    protected fun isLive(issued: IssuedToken): Boolean = runBlocking { tokens.resolve(issued.secret.reveal()) } != null

    protected fun revokedAt(issued: IssuedToken): Long? = TokenFixtures.revokedAtMs(temp, issued.tokenId)
}
