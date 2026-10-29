package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the text of the drop line: it names the platform session id and the recorded id, or says that none is recorded. */
internal class DropMessageTest {
    @Test
    fun `drop message names the platform id and the recorded id`() {
        assertEquals("final status for session 5 dropped; recorded session 7", dropMessage(5, 7))
    }

    @Test
    fun `drop message says no session is recorded when none is`() {
        assertEquals("final status for session 5 dropped; no session recorded", dropMessage(5, null))
    }
}
