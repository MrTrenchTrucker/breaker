package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.db.TestDatabases
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import java.sql.SQLException
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class TokenStoreIssueTest : TempDatabaseTest() {

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
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, admin.role)
        assertEquals("sync-api: the second account must be a user", Role.USER, user.role)
        adminId = admin.accountId
        userId = user.accountId
    }

    private fun session(owner: Long): IssueResult = runBlocking { store.issueSession(owner) }

    private fun resolvedId(text: String): Long? = runBlocking { store.resolve(text) }?.tokenId

    private fun textColumn(id: Long, column: String): String? =
        inTransaction<String?>(temp) { connection ->
            connection.prepareStatement("SELECT $column FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    rows.getString(1)
                }
            }
        }

    private fun longColumn(id: Long, column: String): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT $column FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    @Test
    fun `issueSession reports a session token without scope or expiry`() {
        clock.advance(Duration.ofMinutes(5))
        val issued = issuedOf(session(userId))
        assertEquals("sync-api: kind of an issued session token", TokenKind.SESSION, issued.kind)
        assertNull("sync-api: a session token has no scope", issued.scope)
        assertNull("sync-api: a session token never expires", issued.expiresAt)
        assertEquals("sync-api: length of the token text", 43, issued.secret.reveal().length)
    }

    @Test
    fun `issueSession stores the clock time and no scope, label or expiry`() {
        clock.advance(Duration.ofMinutes(5))
        val id = issuedOf(session(userId)).tokenId
        assertEquals("sync-api: stored kind", "session", textColumn(id, "kind"))
        assertEquals("sync-api: stored owner", userId, longColumn(id, "account_id"))
        assertEquals("sync-api: created_at_ms is the clock time", clock.millis(), longColumn(id, "created_at_ms"))
        assertNotEquals("sync-api: the clock must have moved", TokenFixtures.START.toEpochMilli(), clock.millis())
        assertNull("sync-api: a session row stores no scope", textColumn(id, "scope"))
        assertNull("sync-api: a session row stores no label", textColumn(id, "label"))
        assertNull("sync-api: a session row stores no expiry", longColumn(id, "expires_at_ms"))
        assertNull("sync-api: a new row is not revoked", longColumn(id, "revoked_at_ms"))
    }

    @Test
    fun `only the hash of the token text is stored`() {
        val issued = issuedOf(session(userId))
        val text = issued.secret.reveal()
        val stored = TokenFixtures.storedHash(temp, issued.tokenId)
        val expected = checkNotNull(TokenSecret.parse(text)) { "sync-api test: issued text must parse" }.hash()
        assertArrayEquals("sync-api: stored hash is the hash of the 43 characters", expected, stored)
        assertEquals("sync-api: stored hash length", 32, stored.size)
        assertFalse(
            "sync-api: the stored bytes must not be the token text",
            stored.contentEquals(text.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `two session tokens differ and each resolves to its own row`() {
        val first = issuedOf(session(userId))
        val second = issuedOf(session(userId))
        assertNotEquals("sync-api: token ids must differ", first.tokenId, second.tokenId)
        assertNotEquals("sync-api: token texts must differ", first.secret.reveal(), second.secret.reveal())
        assertEquals("sync-api: first token resolves to itself", first.tokenId, resolvedId(first.secret.reveal()))
        assertEquals("sync-api: second token resolves to itself", second.tokenId, resolvedId(second.secret.reveal()))
    }

    @Test
    fun `issueSession for a missing account is refused and stores nothing`() {
        val result = session(9999L)
        assertSame("sync-api: result for an unknown owner", IssueResult.NoSuchAccount, result)
        assertEquals("sync-api: no row for an unknown owner", 0L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `a colliding hash fails and never replaces the live token`() {
        val colliding = TokenFixtures.tokenStore(temp, clock, TokenFixtures.fakeRandom(5))
        val first = issuedOf(runBlocking { colliding.issueSession(userId) })
        TestDatabases.expectFailure<SQLException>("sync-api: second token with the same hash") {
            runBlocking { colliding.issueSession(adminId) }
        }
        assertEquals("sync-api: the first token still resolves", first.tokenId, resolvedId(first.secret.reveal()))
        assertEquals("sync-api: exactly one row after the collision", 1L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `the text form of an issued token carries the id and not the secret`() {
        val tokens = listOf(
            issuedOf(session(userId)),
            issuedOf(runBlocking { store.issueAgent(adminId, AgentScope.ADMIN, "bot", null) }),
        )
        for (issued in tokens) {
            val text = issued.secret.reveal()
            val shown = issued.toString()
            assertFalse("sync-api: toString must not hold the secret (${issued.kind})", shown.contains(text))
            assertFalse(
                "sync-api: toString must not hold the secret start (${issued.kind})",
                shown.contains(text.substring(0, 8)),
            )
            assertTrue("sync-api: toString must name the id (${issued.kind})", shown.contains("id=${issued.tokenId}"))
        }
    }
}
