package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The invariant of the start result: a sentence is present exactly when the surface shows one. */
internal class StartResultTest {

    @Test
    fun `a sentence shown with no text throws`() {
        val thrown = runCatching { StartResult(Shown.SENTENCE, null) }.exceptionOrNull()
        assertTrue("a sentence shown with no text must be refused", thrown is IllegalArgumentException)
    }

    @Test
    fun `a sentence shown with blank text throws`() {
        val thrown = runCatching { StartResult(Shown.SENTENCE, "   ") }.exceptionOrNull()
        assertTrue("blank text is no text", thrown is IllegalArgumentException)
    }

    @Test
    fun `a nothing result with a text throws`() {
        val thrown = runCatching { StartResult(Shown.NOTHING, "text") }.exceptionOrNull()
        assertTrue("nothing shown must carry no text", thrown is IllegalArgumentException)
    }

    @Test
    fun `an installed result with a text throws`() {
        val thrown = runCatching { StartResult(Shown.INSTALLED, "text") }.exceptionOrNull()
        assertTrue("the installed state must carry no text", thrown is IllegalArgumentException)
    }

    @Test
    fun `a sentence shown with its text gives both members`() {
        val result = StartResult(Shown.SENTENCE, "The update did not finish.")
        assertEquals(Shown.SENTENCE, result.shown)
        assertEquals("The update did not finish.", result.sentence)
    }
}
