package dev.breaker.dictation.ui.screen.howitworks

import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.firstrun.FirstRunPages
import dev.breaker.dictation.ui.screen.firstrun.FirstRunWords
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * The How it works screen, checked on its rendered tree.
 *
 * The screen reuses the welcome headings and whys from the first-run pages:
 * each permission page's heading and the short reason under it. The words are
 * the same ones the first-run pages show, so the wording stays in one place.
 *
 * The screen is static: it has no actions, no intents, and no state. It is
 * described as nodes and nothing else, so the test reads the tree it renders.
 */
class HowItWorksScreenTest {

    private val screen = HowItWorksScreen()

    @Test
    fun `the screen renders with the correct title`() {
        val rendered = screen.render()
        assertEquals("How it works", rendered.title)
        assertEquals(HowItWorksNodeIds.SCREEN, rendered.id)
    }

    @Test
    fun `the screen reuses the first-run headings and whys`() {
        val rendered = screen.render()
        val labels = rendered.nodes.filterIsInstance<dev.breaker.dictation.ui.screen.Label>()
        val headings = labels.filter { it.role == dev.breaker.dictation.ui.screen.TypeRole.DISPLAY }
        val whys = labels.filter { it.role == dev.breaker.dictation.ui.screen.TypeRole.BODY }
        // The title is the only DISPLAY label
        assertEquals(1, headings.size)
        assertEquals(HowItWorksTexts.TITLE, headings.single().text)
        // Each permission page contributes a heading and a why
        val permissionPages = FirstRunPages.ORDER.filter { FirstRunWords.heading(it) != null }
        assertEquals(permissionPages.size, whys.size / 2)
        // The whys are the setup texts
        val whyTexts = whys.map { it.text }
        assertTrue(whyTexts.contains(SetupTexts.WHY_MICROPHONE))
        assertTrue(whyTexts.contains(SetupTexts.WHY_OVERLAY))
        assertTrue(whyTexts.contains(SetupTexts.WHY_NOTIFICATIONS))
        assertTrue(whyTexts.contains(SetupTexts.WHY_ACCESSIBILITY))
    }

    @Test
    fun `the screen has the correct node ids`() {
        val rendered = screen.render()
        val ids = rendered.nodes.map { it.id }
        assertTrue(ids.contains(HowItWorksNodeIds.BACKGROUND))
        assertTrue(ids.contains(HowItWorksNodeIds.TITLE))
        // Each permission page has a heading and a why node
        for (page in FirstRunPages.ORDER) {
            val heading = FirstRunWords.heading(page)
            val why = FirstRunWords.why(page)
            if (heading != null) {
                assertTrue(ids.contains("${HowItWorksNodeIds.PREFIX}.${page.name}.heading"))
            }
            if (why != null) {
                assertTrue(ids.contains("${HowItWorksNodeIds.PREFIX}.${page.name}.why"))
            }
        }
    }

    @Test
    fun `each page has its own heading and why id, pairwise different`() {
        val rendered = screen.render()
        val pageIds = rendered.nodes.map { it.id }
            .filterNot { it == HowItWorksNodeIds.BACKGROUND || it == HowItWorksNodeIds.TITLE }
        assertTrue("howitworks: the page nodes exist", pageIds.isNotEmpty())
        assertPairwiseDifferent(pageIds)
    }

    @Test
    fun `the pairwise check fires on a repeated id`() {
        try {
            assertPairwiseDifferent(listOf("howitworks.item.a.heading", "howitworks.item.a.heading"))
            fail("the check did not fire on a repeated id")
        } catch (expected: AssertionError) {
            assertTrue("the failure names the pairwise check", expected.message.orEmpty().contains("pairwise"))
        }
    }

    /** Fails unless no id in [ids] appears twice. */
    private fun assertPairwiseDifferent(ids: List<String>) {
        assertEquals("howitworks: the page ids are pairwise different, got $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `the screen has no actions`() {
        val rendered = screen.render()
        val actions = rendered.nodes.filterIsInstance<dev.breaker.dictation.ui.screen.Action>()
        assertEquals(0, actions.size)
    }


    @Test
    fun `the keyboard tile page has its heading and its reason on the screen`() {
        val labels = screen.render().nodes.filterIsInstance<dev.breaker.dictation.ui.screen.Label>().map { it.text }
        assertTrue("the keyboard tile heading is not on the screen", labels.contains(SetupTexts.HEADING_KEYBOARD_TILE))
        assertTrue("the keyboard tile reason is not on the screen", labels.contains(SetupTexts.WHY_KEYBOARD_TILE))
    }
}
