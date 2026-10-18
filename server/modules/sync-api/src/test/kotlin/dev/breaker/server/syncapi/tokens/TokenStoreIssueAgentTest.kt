package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures.issuedOf
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

internal class TokenStoreIssueAgentTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var adminId: Long = 0L
    private var userId: Long = 0L

    // A pair that is one code point, so 64 of them are 128 UTF-16 units.
    private val emoji: String = "\uD83D\uDE00"

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

    private fun issue(
        owner: Long,
        scope: AgentScope = AgentScope.TRANSCRIBE,
        label: String = "bot",
        expiresAt: Instant? = null,
    ): IssueResult = runBlocking { store.issueAgent(owner, scope, label, expiresAt) }

    private fun assertRefused(case: String, expected: IssueResult, attempt: () -> IssueResult) {
        val before = TokenFixtures.tokenRowCount(temp)
        assertSame("sync-api: result for $case", expected, attempt())
        assertEquals("sync-api: $case must store nothing", before, TokenFixtures.tokenRowCount(temp))
    }

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

    private fun expiryColumn(id: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT expires_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    @Test
    fun `an agent token without expiry keeps its scope and label and has no expiry`() {
        val owners = listOf(
            Triple("user owner", userId, AgentScope.TRANSCRIBE),
            Triple("admin owner", adminId, AgentScope.ADMIN),
        )
        for ((name, owner, scope) in owners) {
            val issued = issuedOf(issue(owner, scope, "nightly sync"))
            assertEquals("sync-api: kind for $name", TokenKind.AGENT, issued.kind)
            assertEquals("sync-api: reported scope for $name", scope, issued.scope)
            assertNull("sync-api: reported expiry for $name", issued.expiresAt)
            assertEquals("sync-api: stored scope for $name", scope.dbValue, textColumn(issued.tokenId, "scope"))
            assertEquals("sync-api: stored kind for $name", "agent", textColumn(issued.tokenId, "kind"))
            assertEquals("sync-api: stored label for $name", "nightly sync", textColumn(issued.tokenId, "label"))
            assertNull("sync-api: stored expiry for $name", expiryColumn(issued.tokenId))
        }
    }

    @Test
    fun `a future expiry is reported and stored as the same instant`() {
        val requested = TokenFixtures.START.plusSeconds(3600)
        val issued = issuedOf(issue(userId, expiresAt = requested))
        assertEquals("sync-api: reported expiry", requested, issued.expiresAt)
        assertEquals("sync-api: stored expiry in epoch millis", requested.toEpochMilli(), expiryColumn(issued.tokenId))
    }

    @Test
    fun `an expiry with sub-millisecond digits is reported truncated to the millisecond`() {
        val requested = Instant.ofEpochSecond(TokenFixtures.START.epochSecond + 3600, 1_999_999L)
        val issued = issuedOf(issue(userId, expiresAt = requested))
        val truncated = Instant.ofEpochMilli(requested.toEpochMilli())
        assertNotEquals("sync-api: the request must have sub-millisecond digits", requested, truncated)
        assertEquals("sync-api: reported expiry is the stored millisecond", truncated, issued.expiresAt)
        assertEquals("sync-api: stored expiry", requested.toEpochMilli(), expiryColumn(issued.tokenId))
    }

    @Test
    fun `an expiry must lie strictly after now to the millisecond`() {
        val now = clock.instant()
        assertRefused("an expiry equal to now", IssueResult.InvalidExpiry) { issue(userId, expiresAt = now) }
        assertRefused("an expiry one ms in the past", IssueResult.InvalidExpiry) {
            issue(userId, expiresAt = now.minusMillis(1))
        }
        val issued = issuedOf(issue(userId, expiresAt = now.plusMillis(1)))
        assertEquals("sync-api: an expiry one ms ahead is kept", now.plusMillis(1), issued.expiresAt)
    }

    @Test
    fun `labels that break the rules are refused and store nothing`() {
        val refused = linkedMapOf(
            "empty" to "",
            "65 ascii characters" to "a".repeat(65),
            "bell" to "a\u0007b",
            "nul" to "a\u0000b",
            "newline" to "a\nb",
            "delete" to "a\u007fb",
            "next line control" to "a\u0085b",
            "lone high surrogate" to "a\uD800b",
            "lone low surrogate" to "a\uDC00b",
            "high surrogate at the end" to "a\uD83D",
            "pair in the wrong order" to "\uDE00\uD83D",
            "65 code points of 2 units" to emoji.repeat(65),
        )
        for ((name, label) in refused) {
            assertRefused("label case '$name'", IssueResult.InvalidLabel) { issue(userId, label = label) }
        }
    }

    @Test
    fun `labels within the rules are accepted and stored exactly as given`() {
        val accepted = linkedMapOf(
            "one character" to "x",
            "64 ascii characters" to "a".repeat(64),
            "64 code points of 2 units" to emoji.repeat(64),
            "spaces kept, e-acute kept" to "  caf\u00e9 bot  ",
        )
        for ((name, label) in accepted) {
            val issued = issuedOf(issue(userId, label = label))
            assertEquals("sync-api: stored label for '$name'", label, textColumn(issued.tokenId, "label"))
        }
    }

    @Test
    fun `two agent tokens may share a label for one owner`() {
        val first = issuedOf(issue(userId, label = "same"))
        val second = issuedOf(issue(userId, label = "same"))
        assertNotEquals("sync-api: ids of tokens with one label", first.tokenId, second.tokenId)
        assertEquals("sync-api: rows after two same-label issues", 2L, TokenFixtures.tokenRowCount(temp))
    }

    @Test
    fun `the checks run in the order label, expiry, owner, scope`() {
        val past = clock.instant().minusSeconds(1)
        val future = clock.instant().plusSeconds(60)
        assertRefused("bad label with every other fault", IssueResult.InvalidLabel) {
            issue(9999L, AgentScope.ADMIN, "", past)
        }
        assertRefused("bad label and expiry for a user asking admin", IssueResult.InvalidLabel) {
            issue(userId, AgentScope.ADMIN, "", past)
        }
        assertRefused("bad expiry for an unknown owner", IssueResult.InvalidExpiry) {
            issue(9999L, AgentScope.TRANSCRIBE, "bot", past)
        }
        assertRefused("bad expiry for a user asking admin", IssueResult.InvalidExpiry) {
            issue(userId, AgentScope.ADMIN, "bot", past)
        }
        assertRefused("unknown owner asking admin", IssueResult.NoSuchAccount) {
            issue(9999L, AgentScope.ADMIN, "bot", future)
        }
        assertRefused("a user asking for the admin scope", IssueResult.ScopeNotAllowed) {
            issue(userId, AgentScope.ADMIN, "bot", future)
        }
    }

    @Test
    fun `the admin scope needs an admin owner and the transcribe scope does not`() {
        val adminToken = issuedOf(issue(adminId, AgentScope.ADMIN))
        val userToken = issuedOf(issue(userId, AgentScope.TRANSCRIBE))
        assertEquals("sync-api: scope of the admin's token", AgentScope.ADMIN, adminToken.scope)
        assertEquals("sync-api: scope of the user's token", AgentScope.TRANSCRIBE, userToken.scope)
        assertEquals("sync-api: rows after the two accepted issues", 2L, TokenFixtures.tokenRowCount(temp))
        assertRefused("a user asking for the admin scope", IssueResult.ScopeNotAllowed) {
            issue(userId, AgentScope.ADMIN)
        }
    }
}
