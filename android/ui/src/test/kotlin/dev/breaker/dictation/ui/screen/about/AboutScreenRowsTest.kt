package dev.breaker.dictation.ui.screen.about

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.TypeRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The About screen's rows, checked on its rendered tree.
 *
 * The default face is today's: the two body lines and nothing else, so the five
 * tests in AboutScreenTest keep passing and are not edited. An admin state adds
 * the rows the decision allows and the one status sentence it names.
 */
class AboutScreenRowsTest {

    private val screen = AboutScreen()

    @Test
    fun `the default render is today's tree and nothing else`() {
        val rendered = screen.render()
        assertEquals(
            "the default face must be the four nodes it has always been",
            listOf(AboutNodeIds.BACKGROUND, AboutNodeIds.TITLE, AboutNodeIds.LINE_1, AboutNodeIds.LINE_2),
            rendered.nodes.map { it.id },
        )
        val body = rendered.nodes.filterIsInstance<Label>().filter { it.role == TypeRole.BODY }
        assertEquals(
            "the default face must carry the two body lines",
            listOf(AboutTexts.LINE_1, AboutTexts.LINE_2),
            body.map { it.text },
        )
        assertEquals("the default face must have no actions", 0, rendered.nodes.filterIsInstance<Action>().size)
    }

    @Test
    fun `an admin sees the check row and no other row when nothing is offered`() {
        val rendered = screen.render(AboutState(admin = true))
        val actions = rendered.nodes.filterIsInstance<Action>()
        assertEquals(listOf(AboutNodeIds.CHECK), actions.map { it.id })
        assertEquals(AboutTexts.CHECK_LABEL, actions.single().text)
        assertEquals(ScreenIntent.About(AboutActions.CHECK), actions.single().intent)
    }

    @Test
    fun `an available and ready update adds the install row after the check row`() {
        val rendered = screen.render(AboutState(admin = true, updateAvailable = true, installReady = true))
        val actions = rendered.nodes.filterIsInstance<Action>()
        assertEquals(listOf(AboutNodeIds.CHECK, AboutNodeIds.INSTALL), actions.map { it.id })
        assertEquals(AboutTexts.INSTALL, actions[1].text)
        assertEquals(ScreenIntent.About(AboutActions.INSTALL), actions[1].intent)
    }

    @Test
    fun `an available update that is not ready shows the sentence and no install row`() {
        val state = AboutState(admin = true, updateAvailable = true)
        val actions = screen.render(state).nodes.filterIsInstance<Action>()
        assertEquals("only the check row may show", listOf(AboutNodeIds.CHECK), actions.map { it.id })
        assertEquals("the sentence must say an update is available", AboutTexts.OFFERED, statusOf(state))
    }

    @Test
    fun `a ready build with no available update shows no install row and no sentence`() {
        val state = AboutState(admin = true, installReady = true)
        val actions = screen.render(state).nodes.filterIsInstance<Action>()
        assertEquals("only the check row may show", listOf(AboutNodeIds.CHECK), actions.map { it.id })
        assertEquals("ready alone must draw no sentence", null, statusOf(state))
    }

    @Test
    fun `a non-admin sees no row and no sentence, whatever the phase`() {
        val rendered = screen.render(AboutState(admin = false, updateAvailable = true, installReady = true, checking = true, installing = true, failed = true, upToDate = true))
        assertEquals("a non-admin must see no row", 0, rendered.nodes.filterIsInstance<Action>().size)
        assertEquals("a non-admin must see no sentence", 0, statusLabels(rendered).size)
    }

    @Test
    fun `a person who is not an admin gets no decision whatever the state`() {
        val state = AboutState(admin = false, updateAvailable = true, installReady = true, checking = true)
        assertEquals("the decision for a non-admin must be empty", AboutDecision(emptySet(), null), decideAbout(state))
    }

    @Test
    fun `the decision never contains a rollback row in any state combination`() {
        val allowed = setOf(AboutActions.CHECK, AboutActions.INSTALL)
        var count = 0
        for (bits in 0 until 128) {
            val state = AboutState(
                admin = (bits and 1) != 0,
                updateAvailable = (bits and 2) != 0,
                installReady = (bits and 4) != 0,
                checking = (bits and 8) != 0,
                installing = (bits and 16) != 0,
                failed = (bits and 32) != 0,
                upToDate = (bits and 64) != 0,
            )
            val rows = decideAbout(state).rows
            assertTrue("state $bits offers a row other than the check and install rows: $rows", allowed.containsAll(rows))
            count += 1
        }
        assertEquals("the loop must cover all 128 combinations", 128, count)
    }

    @Test
    fun `each sentence appears for its state`() {
        assertEquals(AboutTexts.CHECKING, statusOf(AboutState(admin = true, checking = true)))
        assertEquals(AboutTexts.INSTALLING, statusOf(AboutState(admin = true, installing = true)))
        assertEquals(AboutTexts.FAILED, statusOf(AboutState(admin = true, failed = true)))
        assertEquals(AboutTexts.OFFERED, statusOf(AboutState(admin = true, updateAvailable = true)))
        assertEquals(AboutTexts.UP_TO_DATE, statusOf(AboutState(admin = true, upToDate = true)))
        assertEquals("an admin with no phase has no sentence", null, statusOf(AboutState(admin = true)))
    }

    @Test
    fun `the sentence priority holds`() {
        assertEquals(
            "installing beats everything",
            AboutTexts.INSTALLING,
            statusOf(AboutState(admin = true, installing = true, checking = true, failed = true, updateAvailable = true, upToDate = true)),
        )
        assertEquals(
            "checking beats failed, an available update and up to date",
            AboutTexts.CHECKING,
            statusOf(AboutState(admin = true, checking = true, failed = true, updateAvailable = true, upToDate = true)),
        )
        assertEquals(
            "failed beats an available update and up to date",
            AboutTexts.FAILED,
            statusOf(AboutState(admin = true, failed = true, updateAvailable = true, upToDate = true)),
        )
        assertEquals(
            "an available update beats up to date",
            AboutTexts.OFFERED,
            statusOf(AboutState(admin = true, updateAvailable = true, upToDate = true)),
        )
        assertEquals(
            "an available and ready update beats up to date",
            AboutTexts.OFFERED,
            statusOf(AboutState(admin = true, updateAvailable = true, installReady = true, upToDate = true)),
        )
        assertEquals(AboutTexts.UP_TO_DATE, statusOf(AboutState(admin = true, upToDate = true)))
    }

    @Test
    fun `the status sentence is one label with the status id`() {
        val rendered = screen.render(AboutState(admin = true, checking = true))
        val status = statusLabels(rendered)
        assertEquals(1, status.size)
        assertEquals(AboutNodeIds.STATUS, status.single().id)
        assertEquals(TypeRole.BODY, status.single().role)
        assertTrue("the status label must carry the sentence", status.single().text.isNotEmpty())
    }

    private fun statusLabels(rendered: dev.breaker.dictation.ui.screen.Screen): List<Label> =
        rendered.nodes.filterIsInstance<Label>().filter { it.id == AboutNodeIds.STATUS }

    private fun statusOf(state: AboutState): String? = statusLabels(screen.render(state)).singleOrNull()?.text
}
