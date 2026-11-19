package dev.breaker.dictation.ui.screen.about

import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The notice labels the About screen draws for the notices it is given: one label per notice, in order, after the
 * second line, each with its own id, and the text reaches the label unchanged. With no notice there is no label.
 * Hand-traced only: nothing here has been compiled or run.
 */
class AboutNoticeLabelsTest {

    private val first = "Notice one: test text only."
    private val second = "Notice two: test text only."

    private fun labelsOf(screen: Screen): List<Label> = screen.nodes.filterIsInstance<Label>()

    private fun noticeLabels(screen: Screen): List<Label> =
        labelsOf(screen).filter { label -> label.id.startsWith(AboutNodeIds.NOTICES) }

    @Test
    fun `two passed notices render as two labels with their exact text in the given order`() {
        val screen = AboutScreen(listOf(first, second)).render()
        assertEquals(listOf(first, second), noticeLabels(screen).map { it.text })
    }

    @Test
    fun `the notice labels come right after the second line and before the admin rows`() {
        val state = AboutState(admin = true, updateAvailable = true, installReady = true)
        val ids = AboutScreen(listOf(first, second)).render(state).nodes.map { it.id }
        assertEquals(ids.indexOf(AboutNodeIds.LINE_2) + 1, ids.indexOf(AboutNodeIds.NOTICES))
        assertEquals(ids.indexOf(AboutNodeIds.NOTICES) + 1, ids.indexOf(AboutNodeIds.notice(1)))
        assertTrue("the notice labels must come before the check row", ids.indexOf(AboutNodeIds.notice(1)) < ids.indexOf(AboutNodeIds.CHECK))
    }

    @Test
    fun `each notice label has its own node id and the first keeps the old id`() {
        val ids = noticeLabels(AboutScreen(listOf(first, second)).render()).map { it.id }
        assertEquals(listOf("about.notices", "about.notices.1"), ids)
        assertEquals("the notice ids must not collide", ids.size, ids.toSet().size)
    }

    @Test
    fun `an empty notice list draws no notice label and the default screen draws today's two body lines`() {
        val empty = AboutScreen(emptyList()).render()
        assertEquals("an empty list must draw no notice label", 0, noticeLabels(empty).size)

        val default = AboutScreen().render()
        assertEquals("the default screen must draw no notice label", 0, noticeLabels(default).size)
        assertEquals(
            "the default screen must carry exactly today's two body lines",
            listOf(AboutTexts.LINE_1, AboutTexts.LINE_2),
            labelsOf(default).filter { it.role == TypeRole.BODY }.map { it.text },
        )
    }

    @Test
    fun `the shown notice text is the given text byte for byte and the test data is plain ASCII`() {
        val text = noticeLabels(AboutScreen(listOf(first)).render()).single().text
        assertEquals(first, text)
        assertTrue("the shown text must match the given text byte for byte", text.toByteArray(Charsets.UTF_8).contentEquals(first.toByteArray(Charsets.UTF_8)))
        assertTrue("the test data must be plain ASCII", first.all { it.code < 128 } && second.all { it.code < 128 })
    }

    @Test
    fun `the notice label uses the muted body style`() {
        val label = noticeLabels(AboutScreen(listOf(first)).render()).single()
        assertEquals(TypeRole.BODY, label.role)
        assertEquals(PaletteSlot.TEXT_MUTED, label.color)
    }
}
