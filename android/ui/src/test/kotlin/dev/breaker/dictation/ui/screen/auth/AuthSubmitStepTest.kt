package dev.breaker.dictation.ui.screen.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The hand-over step: the submit gets the array, and the box is wiped afterwards whether the submit
 * returns or throws.
 */
class AuthSubmitStepTest {

    /** A plainly fake password. */
    private fun fixture(): CharArray = "fixture-only-xyz".toCharArray()

    @Test
    fun `the wipe runs after the submit returns`() {
        val events = mutableListOf<String>()
        submitThenWipe(fixture(), { events.add("submit") }) { events.add("wipe") }
        assertEquals(listOf("submit", "wipe"), events)
    }

    @Test
    fun `the wipe runs once when the submit throws and the failure reaches the caller`() {
        val events = mutableListOf<String>()
        val thrown = assertThrows(IllegalStateException::class.java) {
            submitThenWipe(
                fixture(),
                {
                    events.add("submit")
                    throw IllegalStateException("fixture failure")
                },
            ) { events.add("wipe") }
        }
        assertEquals("fixture failure", thrown.message)
        assertEquals(listOf("submit", "wipe"), events)
    }

    @Test
    fun `the submit gets the very array it was given and the step leaves it as it was`() {
        val given = fixture()
        var seen: CharArray? = null
        submitThenWipe(given, { seen = it }) { }
        assertSame(given, seen)
        assertEquals("fixture-only-xyz", String(given))
    }

    @Test
    fun `the wipe does not run before the submit`() {
        val events = mutableListOf<String>()
        var wipedEarly = true
        submitThenWipe(
            fixture(),
            {
                wipedEarly = events.contains("wipe")
                events.add("submit")
            },
        ) { events.add("wipe") }
        assertFalse("the wipe ran before the submit", wipedEarly)
        assertEquals(listOf("submit", "wipe"), events)
    }
}
