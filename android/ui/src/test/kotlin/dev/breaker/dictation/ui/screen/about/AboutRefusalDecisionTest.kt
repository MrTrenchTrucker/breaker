package dev.breaker.dictation.ui.screen.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * What the decision shows once an install was refused.
 *
 * A refusal draws the refusal sentence and never the failure sentence beside it. It yields to
 * an install in progress and to a check in progress, and it wins over a failure, an available
 * update and up to date. The Install row stays offered, and a person who is not an admin gets
 * nothing. The decision tests do not model the call orders. The host's two orders are pinned by
 * `the real host control and state meet every refusal rule` in AboutRefusalHostRulesTest (reads the host):
 * set before show keeps the held value; set after show is stored and shows at the next draw.
 */
class AboutRefusalDecisionTest {

    private fun sentenceOf(state: AboutState): String? = decideAbout(state).sentence

    /** The host's show, modelled: the facts it is handed, with the refusal it already holds carried over. */
    private fun showWith(held: AboutState, facts: AboutState): AboutState =
        facts.copy(installRefused = held.installRefused)

    @Test
    fun `a refusal shows the refusal sentence`() {
        assertEquals(AboutTexts.REFUSED, sentenceOf(AboutState(admin = true, installRefused = true)))
    }

    @Test
    fun `a refusal is never shown together with the failure sentence`() {
        var count = 0
        for (bits in 0 until 128) {
            val state = AboutState(
                admin = true,
                updateAvailable = (bits and 1) != 0,
                installReady = (bits and 2) != 0,
                checking = (bits and 4) != 0,
                installing = (bits and 8) != 0,
                failed = (bits and 16) != 0,
                upToDate = (bits and 32) != 0,
                installRefused = (bits and 64) != 0,
            )
            if (state.installRefused) {
                assertFalse("state $bits shows the failure sentence beside a refusal", sentenceOf(state) == AboutTexts.FAILED)
            }
            count += 1
        }
        assertEquals("the loop must cover all 128 combinations", 128, count)
    }

    @Test
    fun `installing wins over a refusal`() {
        assertEquals(AboutTexts.INSTALLING, sentenceOf(AboutState(admin = true, installing = true, installRefused = true)))
    }

    @Test
    fun `checking wins over a refusal`() {
        assertEquals(AboutTexts.CHECKING, sentenceOf(AboutState(admin = true, checking = true, installRefused = true)))
    }

    @Test
    fun `a refusal wins over a failure`() {
        assertEquals(AboutTexts.REFUSED, sentenceOf(AboutState(admin = true, failed = true, installRefused = true)))
    }

    @Test
    fun `a refusal beats an available update and up to date`() {
        assertEquals(
            AboutTexts.REFUSED,
            sentenceOf(AboutState(admin = true, updateAvailable = true, upToDate = true, installRefused = true)),
        )
    }

    @Test
    fun `a non-admin gets nothing after a refusal`() {
        val state = AboutState(admin = false, updateAvailable = true, installReady = true, installRefused = true)
        assertEquals(AboutDecision(emptySet(), null), decideAbout(state))
    }

    @Test
    fun `the install row is still offered after a refusal`() {
        val state = AboutState(admin = true, updateAvailable = true, installReady = true, installRefused = true)
        assertEquals(setOf(AboutActions.CHECK, AboutActions.INSTALL), decideAbout(state).rows)
        assertTrue("the install tap must still act after a refusal", AboutIntentHandler.accepts(AboutActions.INSTALL, state))
    }

    @Test
    fun `a default state has no refusal`() {
        assertFalse("the default refusal must be off", AboutState().installRefused)
        assertNull("the default admin state draws no sentence", sentenceOf(AboutState(admin = true)))
    }

    @Test
    fun `clearing the refusal decides back to the old sentence`() {
        val refused = showWith(AboutState().copy(installRefused = true), AboutState(admin = true, failed = true))
        assertEquals(AboutTexts.REFUSED, sentenceOf(refused))
        assertEquals("with the refusal cleared the failure sentence returns", AboutTexts.FAILED, sentenceOf(refused.copy(installRefused = false)))
    }

    @Test
    fun `a cleared refusal shows no refusal line`() {
        val state = AboutState(admin = true, installRefused = true).copy(installRefused = false)
        assertFalse("a copy with the refusal cleared must not show the refusal", sentenceOf(state) == AboutTexts.REFUSED)
        assertNull("a cleared refusal with nothing else draws no sentence", sentenceOf(state))
    }
}
