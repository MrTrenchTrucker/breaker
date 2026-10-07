package dev.breaker.dictation.commit

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The handle [FocusedFieldRegistry.publishScoped] returns: closing it clears
 * only its own publish, and a stale or repeated close is harmless.
 */
internal class FocusedFieldHandleTest {

    /** A field that is only ever compared by identity; it is never asked to type. */
    private class StubField : FocusedField {
        override fun commitText(text: String): FieldCommit = FieldCommit.ACCEPTED
    }

    @Test
    fun `closing the handle of the only publish leaves no current field`() {
        val registry = FocusedFieldRegistry()
        val handleA: AutoCloseable = registry.publishScoped(StubField())

        handleA.close()

        assertNull("commit: closing the only handle must clear the current field", registry.current())
    }

    @Test
    fun `closing an older handle after a newer publish is a stale no-op`() {
        val registry = FocusedFieldRegistry()
        val handleA: AutoCloseable = registry.publishScoped(StubField())
        val fieldB = StubField()
        registry.publishScoped(fieldB)

        handleA.close()

        assertSame("commit: a stale close for an older handle must not clear the newer field", fieldB, registry.current())
    }

    @Test
    fun `closing the same handle twice is harmless`() {
        val registry = FocusedFieldRegistry()
        val handleA: AutoCloseable = registry.publishScoped(StubField())

        handleA.close()
        handleA.close()

        assertNull("commit: a second close of the same handle must leave the registry empty", registry.current())
    }

    @Test
    fun `a field published after a close becomes current`() {
        val registry = FocusedFieldRegistry()
        val handleA: AutoCloseable = registry.publishScoped(StubField())
        handleA.close()
        val fieldB = StubField()

        registry.publishScoped(fieldB)

        assertSame("commit: a field published after a close must be current", fieldB, registry.current())
    }
}
