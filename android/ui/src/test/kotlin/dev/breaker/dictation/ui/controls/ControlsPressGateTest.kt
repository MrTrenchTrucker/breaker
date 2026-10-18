package dev.breaker.dictation.ui.controls

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The press-redraw rule of the button view, checked on its source text the way
 * ControlsViewGateTest checks the drawing rules: the view's `drawableStateChanged`
 * must call `super` and then `invalidate()`, so a press redraws the face once the
 * framework flips the view's own pressed flag, and the redraw lands after the
 * framework's own state work rather than before it.
 *
 * The rule has a file of its own because the cap forced one: ControlsViewGateTest
 * stands at 295 lines and the module card's soft cap is 300 on every file including
 * tests, so this rule goes here rather than growing that file past the cap.
 *
 * The test runs the rule on short made-up samples first — a wrong one must be
 * reported with the right words, a correct one must not — and only then on the
 * real file, read from the main tree the way every gate reads it. A file that
 * cannot be found is a violation, never a pass. Comments are blanked and string
 * literals are stripped, so a sample holds code only.
 */
class ControlsPressGateTest {

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("controls: press: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("controls: press: $what must not be reported", emptyList<String>(), problems)

    /**
     * Every line of the `drawableStateChanged` body of [text] that breaks the press-redraw
     * rule, each as `line: what is wrong`: the body must call `super` and then `invalidate()`,
     * never one without the other and never the invalidation before the super call.
     */
    private fun pressStateProblems(text: String): List<String> {
        val body = withoutCommentsAndStrings(text).lines()
            .dropWhile { !it.contains("override fun drawableStateChanged") }
            .drop(1)
            .takeWhile { it.isNotBlank() }
        if (body.isEmpty()) return listOf("the view never overrides drawableStateChanged")
        val superLine = body.indexOfFirst { it.contains("super.drawableStateChanged()") }
        val invalidateLine = body.indexOfFirst { it.contains("invalidate()") }
        return listOfNotNull(
            "the body never calls super".takeUnless { superLine >= 0 },
            "the body never invalidates".takeUnless { invalidateLine >= 0 },
            "invalidate() runs before super".takeUnless { superLine in 0 until invalidateLine },
        ).mapIndexed { number, problem -> "${number + 1}: $problem" }
    }

    /** A failure here means a press never redraws the button, so the pressed face never shows after the framework flips its flag, or the redraw lands before the framework's own state work. */
    @Test
    fun `drawableStateChanged invalidates after super`() {
        assertFires(
            "a body that calls super but never invalidates",
            pressStateProblems("override fun drawableStateChanged() {\n    super.drawableStateChanged()\n}\n"),
            "never invalidates",
        )
        assertFires(
            "a body that invalidates before it calls super",
            pressStateProblems("override fun drawableStateChanged() {\n    invalidate()\n    super.drawableStateChanged()\n}\n"),
            "before super",
        )
        assertQuiet(
            "the real body of the view, super first and invalidate after",
            pressStateProblems("override fun drawableStateChanged() {\n    super.drawableStateChanged()\n    invalidate()\n}\n"),
        )
        val real = MAIN_SOURCES.toMap()["render/controls/Button3dView.kt"]
        assertTrue("controls: press: render/controls/Button3dView.kt was not read from the main tree", real != null)
        assertQuiet("the real Button3dView", pressStateProblems(real ?: ""))
    }
}
