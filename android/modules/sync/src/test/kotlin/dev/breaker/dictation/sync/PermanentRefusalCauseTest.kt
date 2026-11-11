package dev.breaker.dictation.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The refusal keeps the cause it was built with, and its message is the reason. */
class PermanentRefusalCauseTest {
    @Test
    fun `a refusal built with a cause keeps that cause`() {
        val cause = RuntimeException("underlying")
        val refusal = PermanentRefusalException(400, "bad body", cause)
        assertSame("android_sync: the cause is kept", cause, refusal.cause)
        assertEquals("android_sync: the message is the reason", "bad body", refusal.message)
    }

    @Test
    fun `a refusal built without a cause has none`() {
        assertNull("android_sync: no cause", PermanentRefusalException(400, "bad body").cause)
    }
}
