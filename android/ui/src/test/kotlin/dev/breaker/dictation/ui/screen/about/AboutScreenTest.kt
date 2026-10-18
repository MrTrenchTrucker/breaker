package dev.breaker.dictation.ui.screen.about

import dev.breaker.dictation.ui.screen.TypeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The About screen, checked on its rendered tree.
 *
 * The screen shows exactly two lines: what Breaker does and the license.
 * No version line unless one already exists on main.
 *
 * The screen is static: it has no actions, no intents, and no state. It is
 * described as nodes and nothing else, so the test reads the tree it renders.
 */
class AboutScreenTest {

    private val screen = AboutScreen()

    @Test
    fun `the screen renders with the correct title`() {
        val rendered = screen.render()
        assertEquals("About", rendered.title)
        assertEquals(AboutNodeIds.SCREEN, rendered.id)
    }

    @Test
    fun `the screen shows exactly the two ruled lines`() {
        val rendered = screen.render()
        val labels = rendered.nodes.filterIsInstance<dev.breaker.dictation.ui.screen.Label>()
        val bodyLabels = labels.filter { it.role == TypeRole.BODY }
        assertEquals(2, bodyLabels.size)
        assertEquals("Breaker turns your voice into text, hands free, on this phone.", bodyLabels[0].text)
        assertEquals("Open source under the Apache License 2.0.", bodyLabels[1].text)
    }

    @Test
    fun `the screen has the correct node ids`() {
        val rendered = screen.render()
        val ids = rendered.nodes.map { it.id }
        assertTrue(ids.contains(AboutNodeIds.BACKGROUND))
        assertTrue(ids.contains(AboutNodeIds.TITLE))
        assertTrue(ids.contains(AboutNodeIds.LINE_1))
        assertTrue(ids.contains(AboutNodeIds.LINE_2))
    }

    @Test
    fun `the screen has no actions`() {
        val rendered = screen.render()
        val actions = rendered.nodes.filterIsInstance<dev.breaker.dictation.ui.screen.Action>()
        assertEquals(0, actions.size)
    }

    @Test
    fun `the screen has no version line`() {
        val rendered = screen.render()
        val labels = rendered.nodes.filterIsInstance<dev.breaker.dictation.ui.screen.Label>()
        val texts = labels.map { it.text }
        assertTrue(texts.none { it.contains("version", ignoreCase = true) })
        assertTrue(texts.none { it.contains("v\\d".toRegex()) })
    }
}
