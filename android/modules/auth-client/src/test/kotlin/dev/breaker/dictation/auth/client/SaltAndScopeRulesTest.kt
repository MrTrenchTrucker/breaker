package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaltAndScopeRulesTest {
    @Test
    fun `a secure salt is sixteen bytes long`() {
        assertEquals("salt length", 16, AccountRules.secureSalt().size)
    }

    @Test
    fun `two secure salts differ`() {
        val first = AccountRules.secureSalt()
        val second = AccountRules.secureSalt()
        assertTrue("two salts were equal", !first.contentEquals(second))
    }

    @Test
    fun `the user role gets the transcribe scope and nothing else`() {
        assertEquals(setOf(TokenScope.TRANSCRIBE), AccountRules.scopesFor(UserRole.USER))
    }

    @Test
    fun `the admin role gets the transcribe and admin scopes`() {
        assertEquals(setOf(TokenScope.TRANSCRIBE, TokenScope.ADMIN), AccountRules.scopesFor(UserRole.ADMIN))
    }
}
