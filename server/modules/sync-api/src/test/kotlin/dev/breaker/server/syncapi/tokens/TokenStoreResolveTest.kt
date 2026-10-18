package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

internal class TokenStoreResolveTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var adminId: Long = 0L
    private var userId: Long = 0L

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        val admin = TokenFixtures.register(temp, "admin", 1)
        val user = TokenFixtures.register(temp, "member", 2)
        val filler = TokenFixtures.register(temp, "filler", 3)
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, admin.role)
        assertEquals("sync-api: the second account must be a user", Role.USER, user.role)
        adminId = admin.accountId
        userId = user.accountId
        // Two rows owned by a third account push every later token id above every account
        // id, so a result that swaps the token id and the account id cannot pass.
        session(filler.accountId)
        session(filler.accountId)
    }

    private fun session(owner: Long): IssuedToken = issuedOf(runBlocking { store.issueSession(owner) })

    private fun agent(owner: Long, scope: AgentScope, expiresAt: Instant? = null): IssuedToken =
        issuedOf(runBlocking { store.issueAgent(owner, scope, "bot", expiresAt) })

    private fun resolve(text: String): ResolvedToken? = runBlocking { store.resolve(text) }

    private fun rowsOf(accountId: Long): Long = inTransaction<Long>(temp) { connection ->
        connection.prepareStatement("SELECT count(*) FROM tokens WHERE account_id = ?").use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api test: count returned no row" }
                rows.getLong(1)
            }
        }
    }

    // One text line per row with every column, so any write by resolve changes the list.
    private fun snapshot(): List<String> = inTransaction<List<String>>(temp) { connection ->
        TestDatabases.strings(
            connection,
            "SELECT id || '|' || account_id || '|' || kind || '|' || ifnull(scope, '-') || '|' || " +
                "ifnull(label, '-') || '|' || hex(token_hash) || '|' || created_at_ms || '|' || " +
                "ifnull(expires_at_ms, '-') || '|' || ifnull(revoked_at_ms, '-') FROM tokens ORDER BY id",
        )
    }

    @Test
    fun `a session token resolves to its owner and its role`() {
        val adminSession = session(adminId)
        val userSession = session(userId)
        assertEquals(
            "sync-api: session token of the admin account",
            ResolvedToken(adminSession.tokenId, adminId, Role.ADMIN, TokenKind.SESSION, null, null),
            resolve(adminSession.secret.reveal()),
        )
        assertEquals(
            "sync-api: session token of the user account",
            ResolvedToken(userSession.tokenId, userId, Role.USER, TokenKind.SESSION, null, null),
            resolve(userSession.secret.reveal()),
        )
    }

    @Test
    fun `an agent token resolves with its stored and effective scope`() {
        val transcribe = agent(userId, AgentScope.TRANSCRIBE)
        val admin = agent(adminId, AgentScope.ADMIN)
        assertEquals(
            "sync-api: transcribe token of a user owner",
            ResolvedToken(
                transcribe.tokenId, userId, Role.USER, TokenKind.AGENT, AgentScope.TRANSCRIBE, AgentScope.TRANSCRIBE,
            ),
            resolve(transcribe.secret.reveal()),
        )
        assertEquals(
            "sync-api: admin token of an admin owner",
            ResolvedToken(admin.tokenId, adminId, Role.ADMIN, TokenKind.AGENT, AgentScope.ADMIN, AgentScope.ADMIN),
            resolve(admin.secret.reveal()),
        )
    }

    @Test
    fun `a demoted owner cannot use the admin scope of an old token and gets it back with the role`() {
        val text = agent(adminId, AgentScope.ADMIN).secret.reveal()
        assertEquals("sync-api: effective scope before demotion", AgentScope.ADMIN, resolve(text)?.effectiveScope)
        TokenFixtures.setRole(temp, adminId, Role.USER)
        val demoted = resolve(text)
        assertEquals("sync-api: role read after demotion", Role.USER, demoted?.role)
        assertEquals("sync-api: stored scope after demotion", AgentScope.ADMIN, demoted?.storedScope)
        assertEquals("sync-api: effective scope after demotion", AgentScope.TRANSCRIBE, demoted?.effectiveScope)
        TokenFixtures.setRole(temp, adminId, Role.ADMIN)
        assertEquals("sync-api: effective scope with the role back", AgentScope.ADMIN, resolve(text)?.effectiveScope)
    }

    @Test
    fun `the role of a session token follows the owner role at each resolve`() {
        val text = session(adminId).secret.reveal()
        assertEquals("sync-api: role before the change", Role.ADMIN, resolve(text)?.role)
        TokenFixtures.setRole(temp, adminId, Role.USER)
        assertEquals("sync-api: role after demotion", Role.USER, resolve(text)?.role)
        TokenFixtures.setRole(temp, adminId, Role.ADMIN)
        assertEquals("sync-api: role after the role is back", Role.ADMIN, resolve(text)?.role)
    }

    @Test
    fun `a well-formed token that was never issued resolves to null`() {
        session(userId)
        val neverIssued = TokenSecret.generate(TokenFixtures.fakeRandom(77)).reveal()
        assertNull("sync-api: random never-issued token", resolve(neverIssued))
        assertNull("sync-api: 43 A characters", resolve("A".repeat(43)))
    }

    @Test
    fun `presented strings of the wrong shape resolve to null`() {
        val text = session(userId).secret.reveal()
        val head = text.substring(0, 42)
        assertNotNull("sync-api: the real token resolves", resolve(text))
        val malformed = linkedMapOf(
            "empty" to "",
            "42 characters" to head,
            "44 characters with a trailing newline" to text + "\n",
            "44 characters with padding" to text + "=",
            "43 characters ending in a padding sign" to head + "=",
            "43 characters ending in a space" to head + " ",
            "43 characters ending in a newline" to head + "\n",
            "43 characters ending in e-acute" to head + "\u00e9",
            "43 characters with a leading space" to " " + head,
            "43 characters ending in a plus sign" to head + "+",
        )
        for ((name, presented) in malformed) {
            assertNull("sync-api: presented string '$name'", resolve(presented))
        }
    }

    @Test
    fun `another spelling of the same bytes does not resolve`() {
        val text = session(userId).secret.reveal()
        val other = TokenFixtures.otherSpellingOfSameBytes(text)
        assertNotEquals("sync-api: the spelling must differ", text, other)
        assertNotNull("sync-api: the issued spelling resolves", resolve(text))
        assertNull("sync-api: the other spelling must not resolve", resolve(other))
    }

    @Test
    fun `an agent token is alive until its expiry instant and dead from it`() {
        val expiry = TokenFixtures.START.plusSeconds(60)
        val text = agent(userId, AgentScope.TRANSCRIBE, expiry).secret.reveal()
        clock.set(expiry.minusMillis(1))
        assertNotNull("sync-api: one ms before the expiry", resolve(text))
        clock.set(expiry)
        assertNull("sync-api: at the expiry instant", resolve(text))
        clock.set(expiry.plusMillis(1))
        assertNull("sync-api: one ms after the expiry", resolve(text))
        clock.set(expiry.plusSeconds(3600))
        assertNull("sync-api: an hour after the expiry", resolve(text))
    }

    @Test
    fun `an agent token without expiry still resolves ten years later`() {
        val text = agent(userId, AgentScope.TRANSCRIBE).secret.reveal()
        clock.advance(Duration.ofDays(3650))
        assertNotNull("sync-api: agent token without expiry after 3650 days", resolve(text))
    }

    @Test
    fun `a session token still resolves ten years later`() {
        val text = session(userId).secret.reveal()
        clock.advance(Duration.ofDays(3650))
        assertNotNull("sync-api: session token after 3650 days", resolve(text))
    }

    @Test
    fun `each of several tokens resolves to its own row`() {
        val first = session(adminId)
        val second = agent(userId, AgentScope.TRANSCRIBE)
        val third = agent(adminId, AgentScope.ADMIN)
        for (issued in listOf(third, first, second)) {
            assertEquals("sync-api: id for ${issued.kind}", issued.tokenId, resolve(issued.secret.reveal())?.tokenId)
        }
    }

    @Test
    fun `deleting the owner account removes its tokens and they stop resolving`() {
        val userSession = session(userId)
        val userAgent = agent(userId, AgentScope.TRANSCRIBE)
        val adminSession = session(adminId)
        assertEquals("sync-api: rows of the user before the delete", 2L, rowsOf(userId))
        inTransaction<Int>(temp) { connection ->
            connection.prepareStatement("DELETE FROM accounts WHERE id = ?").use { statement ->
                statement.setLong(1, userId)
                statement.executeUpdate()
            }
        }
        assertNull("sync-api: session of a deleted owner", resolve(userSession.secret.reveal()))
        assertNull("sync-api: agent token of a deleted owner", resolve(userAgent.secret.reveal()))
        assertEquals("sync-api: rows of the user after the delete", 0L, rowsOf(userId))
        assertEquals(
            "sync-api: the other owner is untouched",
            adminSession.tokenId,
            resolve(adminSession.secret.reveal())?.tokenId,
        )
    }

    @Test
    fun `resolve never writes, not even for an expired token`() {
        val sessionToken = session(adminId)
        val forever = agent(userId, AgentScope.TRANSCRIBE)
        val expiring = agent(adminId, AgentScope.ADMIN, TokenFixtures.START.plusSeconds(60))
        val before = snapshot()
        assertEquals("sync-api: rows in the snapshot", 5, before.size)
        repeat(5) {
            assertNotNull("sync-api: session resolves", resolve(sessionToken.secret.reveal()))
            assertNotNull("sync-api: agent token resolves", resolve(forever.secret.reveal()))
            assertNotNull("sync-api: expiring token resolves while alive", resolve(expiring.secret.reveal()))
        }
        clock.set(TokenFixtures.START.plusSeconds(61))
        repeat(5) { assertNull("sync-api: expired token", resolve(expiring.secret.reveal())) }
        assertEquals("sync-api: resolve changed the table", before, snapshot())
    }

    @Test
    fun `a malformed token never reaches the database`() {
        val text = session(userId).secret.reveal()
        temp.db.close()
        assertNull("sync-api: malformed text after the close", resolve(text.substring(0, 42)))
        assertNull("sync-api: empty text after the close", resolve(""))
        TestDatabases.expectFailure<IllegalStateException>("sync-api: a well-formed token on a closed database") {
            resolve(text)
        }
    }
}
