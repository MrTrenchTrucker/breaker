package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TestDatabases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TokenKindsTest {

    @Test
    fun `the stored text of the token kinds is session and agent`() {
        assertEquals(
            "sync-api: token kinds and their stored text",
            listOf("session", "agent"),
            TokenKind.entries.map { kind -> kind.dbValue },
        )
    }

    @Test
    fun `the stored text of the agent scopes is transcribe and admin`() {
        assertEquals(
            "sync-api: agent scopes and their stored text",
            listOf("transcribe", "admin"),
            AgentScope.entries.map { scope -> scope.dbValue },
        )
    }

    @Test
    fun `every token kind maps back to itself from its stored text`() {
        for (kind in TokenKind.entries) {
            assertSame("sync-api: kind ${kind.name} did not map back to itself", kind, TokenKind.fromDb(kind.dbValue))
        }
    }

    @Test
    fun `every agent scope maps back to itself from its stored text`() {
        for (scope in AgentScope.entries) {
            assertSame("sync-api: scope ${scope.name} did not map back to itself", scope, AgentScope.fromDb(scope.dbValue))
        }
    }

    @Test
    fun `an unknown token kind fails loudly and names the value`() {
        val failure = TestDatabases.expectFailure<IllegalStateException>("unknown kind") { TokenKind.fromDb("device") }
        assertTrue("sync-api: message should name the value, was: ${failure.message}", failure.message.orEmpty().contains("device"))
        assertTrue(
            "sync-api: message should carry the module prefix, was: ${failure.message}",
            failure.message.orEmpty().contains("sync-api:"),
        )
    }

    @Test
    fun `an unknown agent scope fails loudly and names the value`() {
        val failure = TestDatabases.expectFailure<IllegalStateException>("unknown scope") { AgentScope.fromDb("root") }
        assertTrue("sync-api: message should name the value, was: ${failure.message}", failure.message.orEmpty().contains("root"))
        assertTrue(
            "sync-api: message should carry the module prefix, was: ${failure.message}",
            failure.message.orEmpty().contains("sync-api:"),
        )
    }

    @Test
    fun `the stored text is matched case sensitively`() {
        TestDatabases.expectFailure<IllegalStateException>("kind 'Session'") { TokenKind.fromDb("Session") }
        TestDatabases.expectFailure<IllegalStateException>("scope 'Admin'") { AgentScope.fromDb("Admin") }
    }

    @Test
    fun `an empty stored text is unknown`() {
        TestDatabases.expectFailure<IllegalStateException>("empty kind") { TokenKind.fromDb("") }
        TestDatabases.expectFailure<IllegalStateException>("empty scope") { AgentScope.fromDb("") }
    }
}
