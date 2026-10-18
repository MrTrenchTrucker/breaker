package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.controls.ControlTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The back arrow of a title bar carries the back name, so a screen reader says what the arrow does.
 * Each sample changes the name the arrow takes, and the check reports it.
 */
class TitleBarBackNameTest {

    /** The problems with the back arrow that [bar] holds first, or none when it is named by the back word. */
    private fun namingProblems(bar: List<Node>): List<String> {
        val arrow = (bar.firstOrNull() as? Box)?.children?.firstOrNull() as? BackArrow
            ?: return listOf("the title bar has no back arrow first")
        return if (arrow.description == ControlTexts.BACK_ARROW) {
            emptyList()
        } else {
            listOf("the back arrow is named ${arrow.description}")
        }
    }

    /** [bar] with its back arrow replaced by [arrow], the rest of the bar kept. */
    private fun withArrow(bar: List<Node>, arrow: BackArrow): List<Node> {
        val box = bar[0] as Box
        return listOf<Node>(box.copy(children = listOf<Node>(arrow) + box.children.drop(1))) + bar.drop(1)
    }

    @Test
    fun `the back arrow of the title bar is named by the back word`() {
        for (prefix in listOf("settings", "history")) {
            val bar = titleBar(prefix, "Title")
            assertEquals("$prefix: the back arrow is named by the back word", emptyList<String>(), namingProblems(bar))
            val arrow = (bar[0] as Box).children[0] as BackArrow
            assertTrue(
                "$prefix: a back arrow with no name is reported",
                namingProblems(withArrow(bar, BackArrow(arrow.id, arrow.intent))).isNotEmpty(),
            )
            assertTrue(
                "$prefix: a back arrow named another word is reported",
                namingProblems(withArrow(bar, BackArrow(arrow.id, arrow.intent, "Return"))).isNotEmpty(),
            )
        }
    }
}
