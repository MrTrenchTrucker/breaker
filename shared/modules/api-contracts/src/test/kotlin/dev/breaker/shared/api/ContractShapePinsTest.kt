package dev.breaker.shared.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Six facts of the shared wire types that no Kotlin test pinned, because the python contract tests that read this
 * source as text were the only ones to notice a change: a default that must stay null, an enum that must keep both
 * of its values, an optional field that must stay optional, and two fields that must stay non-null. The
 * non-null cases are pinned by the compiler: each one assigns the field to a non-null String, so a field made
 * nullable stops this file from compiling, which turns the test run red.
 */
class ContractShapePinsTest {

    @Test
    fun `an agent token request that names no scope has no scope`() {
        val request = AgentTokenCreateRequest(name = "agent", ownerUsername = "ada", expiresAt = null)
        assertNull("api-contracts: scope is optional and defaults to none", request.scope)
    }

    @Test
    fun `the agent token scopes are transcribe and admin in that order`() {
        assertEquals(
            "api-contracts: the two scopes of an agent token",
            listOf("TRANSCRIBE", "ADMIN"),
            AgentTokenScope.values().map { it.name },
        )
        assertEquals(AgentTokenScope.ADMIN, AgentTokenScope.fromString("admin"))
    }

    @Test
    fun `an error body with only the error word has no message`() {
        val body = ApiError("boom")
        assertEquals("boom", body.error)
        assertNull("api-contracts: message is optional and defaults to none", body.message)
    }

    @Test
    fun `a transcription result carries its text as a non-null string`() {
        val text: String = TranscriptionResult(text = "hello", segments = emptyList(), language = "en").text
        assertEquals("hello", text)
    }

    @Test
    fun `a sync push carries its encrypted body as a non-null string and may leave resave unset`() {
        val push = SyncPush(clientId = "c-1", keyVersion = 0, encrypted = "sealed", resave = null)
        val body: String = push.encrypted
        assertEquals("sealed", body)
        assertNull("api-contracts: resave is optional", push.resave)
    }

    @Test
    fun `a health body with only a status has no forwarding target and no uptime`() {
        val health = Health(status = "ok")
        assertNull("api-contracts: forwardingTo is optional and defaults to none", health.forwardingTo)
        assertNull("api-contracts: uptime is optional and defaults to none", health.uptime)
    }
}
