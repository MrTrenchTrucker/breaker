package dev.breaker.dictation.commit

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The registry alone: one current field, replaced by a newer one, cleared only by its own token. */
internal class FocusedFieldRegistryTest {

    /** A field that is only ever compared by identity; it is never asked to type. */
    private class StubField : FocusedField {
        override fun commitText(text: String): FieldCommit = FieldCommit.ACCEPTED
    }

    @Test
    fun `an empty registry has no current field`() {
        val registry = FocusedFieldRegistry()

        assertNull("commit: a new registry must hold no field", registry.current())
    }

    @Test
    fun `a published field is the current field`() {
        val registry = FocusedFieldRegistry()
        val field = StubField()

        registry.publish(field)

        assertSame("commit: the published field must be current", field, registry.current())
    }

    @Test
    fun `a second publish replaces the first and gives a different token`() {
        val registry = FocusedFieldRegistry()
        val first = StubField()
        val second = StubField()

        val firstToken: FocusedFieldRegistry.Token = registry.publish(first)
        val secondToken: FocusedFieldRegistry.Token = registry.publish(second)

        assertSame("commit: the newer field must replace the older one", second, registry.current())
        assertNotSame("commit: each publish must return its own token", firstToken, secondToken)
    }

    @Test
    fun `clearing with the current token leaves no field`() {
        val registry = FocusedFieldRegistry()
        val token: FocusedFieldRegistry.Token = registry.publish(StubField())

        registry.clear(token)

        assertNull("commit: clearing the current token must remove the field", registry.current())
    }

    @Test
    fun `a stale clear after a newer publish leaves the newer field in place`() {
        val registry = FocusedFieldRegistry()
        val older = StubField()
        val newer = StubField()
        val olderToken: FocusedFieldRegistry.Token = registry.publish(older)
        registry.publish(newer)

        registry.clear(olderToken)

        assertSame("commit: a late clear for an older field must not clear the newer one", newer, registry.current())
    }

    @Test
    fun `the newer token still clears after a stale clear was ignored`() {
        val registry = FocusedFieldRegistry()
        val olderToken: FocusedFieldRegistry.Token = registry.publish(StubField())
        val newerToken: FocusedFieldRegistry.Token = registry.publish(StubField())

        registry.clear(olderToken)
        registry.clear(newerToken)

        assertNull("commit: the current token must still clear its field", registry.current())
    }

    @Test
    fun `clearing all removes the current field whichever token it has`() {
        val registry = FocusedFieldRegistry()
        registry.publish(StubField())
        registry.publish(StubField())

        registry.clearAll()

        assertNull("commit: clearAll must remove the current field", registry.current())
    }

    @Test
    fun `clearing an empty registry is harmless`() {
        val registry = FocusedFieldRegistry()

        registry.clearAll()

        assertNull("commit: clearAll on an empty registry must leave it empty", registry.current())
    }

    @Test
    fun `clearing the same token twice is harmless`() {
        val registry = FocusedFieldRegistry()
        val token: FocusedFieldRegistry.Token = registry.publish(StubField())

        registry.clear(token)
        registry.clear(token)

        assertNull("commit: a second clear with the same token must leave the registry empty", registry.current())
    }

    @Test
    fun `an old token cleared again does not remove a field published after it was cleared`() {
        val registry = FocusedFieldRegistry()
        val token: FocusedFieldRegistry.Token = registry.publish(StubField())
        registry.clear(token)
        val later = StubField()
        registry.publish(later)

        registry.clear(token)

        assertSame("commit: a spent token must not clear a later field", later, registry.current())
    }

    @Test
    fun `a field published after a clear becomes current`() {
        val registry = FocusedFieldRegistry()
        val token: FocusedFieldRegistry.Token = registry.publish(StubField())
        registry.clear(token)
        val later = StubField()

        registry.publish(later)

        assertSame("commit: a field published after a clear must be current", later, registry.current())
    }
}
