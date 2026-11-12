package dev.breaker.dictation.ui.screen.auth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who owns the password array the submit callback receives: the callback gets its own copy, made
 * once when it fires, and the array the caller passed in is zeroed as before.
 */
class AuthIntentHandlerOwnershipTest {

    /** A plainly fake password of sixteen characters, which the policy rates Ok. */
    private fun okFixture(): CharArray = "fixture-only-xyz".toCharArray()

    /** Twelve copies of one letter, which the policy rates Weak. */
    private fun weakFixture(): CharArray = CharArray(12) { 'a' }

    /** The handler source, read from this module so the check follows the module and not one machine. */
    private fun handlerSource(): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/screen/auth/AuthIntentHandler.kt")
        assertTrue("the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /**
     * The problems in [text] about where the array copy is made: none when the text holds exactly one
     * copy and it sits from the one-shot function's declaration to the end of the text, one entry
     * for each broken rule otherwise. Comment lines are dropped first so prose cannot count.
     */
    private fun copyProblemsIn(text: String): List<String> {
        val code = text.lines()
            .filterNot {
                val line = it.trim()
                line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
            }
            .joinToString("\n")
        val start = code.indexOf("private fun onSubmitOnce(")
        if (start < 0) return listOf("the declaration of the one-shot function was not found")
        val problems = mutableListOf<String>()
        if (code.substring(0, start).contains("copyOf(")) {
            problems.add("a copy is made before the one-shot function")
        }
        val inside = code.substring(start).split("copyOf(").size - 1
        if (inside != 1) problems.add("the one-shot function holds $inside copies, not one")
        return problems
    }

    /** A sample whose copy is hoisted above the one-shot function, which the check must report. */
    private val HOISTED_SAMPLE =
        """
        internal class Sample {
            fun submit(password: CharArray) {
                val own = password.copyOf()
                onSubmitOnce(own)
            }

            private fun onSubmitOnce(password: CharArray) {
                onSubmit(password)
            }
        }
        """.trimIndent()

    @Test
    fun `the callback gets an array that is not the caller array`() {
        var seen: CharArray? = null
        val handler = AuthIntentHandler { _, password, _ -> seen = password }
        val caller = okFixture()
        handler.submit(caller)
        assertNotSame(caller, checkNotNull(seen))
    }

    @Test
    fun `the callback copy still holds the typed text after submit returns while the caller array is zero`() {
        var seen: CharArray? = null
        val handler = AuthIntentHandler { _, password, _ -> seen = password }
        val caller = okFixture()
        handler.submit(caller)
        assertEquals("fixture-only-xyz", String(checkNotNull(seen)))
        for (index in caller.indices) {
            assertEquals('\u0000', caller[index])
        }
    }

    @Test
    fun `a throwing callback leaves its copy zero and the failure reaches the caller`() {
        var seen: CharArray? = null
        val handler = AuthIntentHandler { _, password, _ ->
            seen = password
            throw IllegalStateException("fixture failure")
        }
        val caller = okFixture()
        assertThrows(IllegalStateException::class.java) { handler.submit(caller) }
        val copy = checkNotNull(seen)
        assertEquals(16, copy.size)
        for (index in copy.indices) {
            assertEquals('\u0000', copy[index])
        }
        for (index in caller.indices) {
            assertEquals('\u0000', caller[index])
        }
    }

    @Test
    fun `a too short submit makes no callback`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _ -> calls++ }
        handler.submit("short".toCharArray())
        assertEquals(0, calls)
    }

    @Test
    fun `a submit while busy makes no second callback`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _ -> calls++ }
        handler.submit(okFixture())
        assertTrue(handler.current() is AuthState.Busy)
        handler.submit(okFixture())
        assertEquals(1, calls)
    }

    @Test
    fun `a submit while warning makes no second callback`() {
        var calls = 0
        val handler = AuthIntentHandler { _, _, _ -> calls++ }
        handler.submit(weakFixture())
        assertTrue(handler.current() is AuthState.Warning)
        handler.submit(weakFixture())
        assertEquals(1, calls)
    }

    @Test
    fun `the copy is made once and only inside the one-shot function`() {
        assertEquals(emptyList<String>(), copyProblemsIn(handlerSource()))
        assertFalse(
            "the check must report a copy hoisted above the one-shot function",
            copyProblemsIn(HOISTED_SAMPLE).isEmpty(),
        )
    }
}
