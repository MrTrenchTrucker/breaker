package dev.breaker.dictation.ui.screen.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Which row taps may act, checked on the handler itself.
 *
 * AboutHostViewRulesTest asserts that the host ASKS this object before it calls the app. These
 * tests pin the answer, so the rule has a behavioural guard and not only a source-scan one. The
 * refusal cases are the ones that matter: a tap while a check or an install is in progress, a tap
 * from a person who is not an admin, an install with no offer behind it, and an action that is not
 * a row at all.
 */
class AboutIntentHandlerTest {

    @Test
    fun `a tap while a check is in progress is refused`() {
        val busy = AboutState(admin = true, checking = true, updateAvailable = true, installReady = true)
        assertFalse("the check row must not restart a check", AboutIntentHandler.accepts(AboutActions.CHECK, busy))
        assertFalse("the install row must wait for the check", AboutIntentHandler.accepts(AboutActions.INSTALL, busy))
    }

    @Test
    fun `a tap while an install is in progress is refused`() {
        val busy = AboutState(admin = true, installing = true, updateAvailable = true, installReady = true)
        assertFalse("the check row must wait for the install", AboutIntentHandler.accepts(AboutActions.CHECK, busy))
        assertFalse("a second install must not start", AboutIntentHandler.accepts(AboutActions.INSTALL, busy))
    }

    @Test
    fun `a person who is not an admin cannot act on any row`() {
        val notAdmin = AboutState(updateAvailable = true, installReady = true)
        assertFalse("a non-admin must not start a check", AboutIntentHandler.accepts(AboutActions.CHECK, notAdmin))
        assertFalse("a non-admin must not install", AboutIntentHandler.accepts(AboutActions.INSTALL, notAdmin))
    }

    @Test
    fun `the install row is refused while nothing is offered`() {
        assertFalse(
            "an install with no offer behind it must be refused",
            AboutIntentHandler.accepts(AboutActions.INSTALL, AboutState(admin = true)),
        )
        assertFalse(
            "an install whose offer lapsed must be refused",
            AboutIntentHandler.accepts(AboutActions.INSTALL, AboutState(admin = true, upToDate = true)),
        )
    }

    @Test
    fun `the install row is refused unless it is offered`() {
        assertFalse(
            "a ready build with no update available must not install",
            AboutIntentHandler.accepts(AboutActions.INSTALL, AboutState(admin = true, installReady = true)),
        )
        assertFalse(
            "an update that is not ready must not install",
            AboutIntentHandler.accepts(AboutActions.INSTALL, AboutState(admin = true, updateAvailable = true)),
        )
    }

    @Test
    fun `an action that is not one of the rows is refused`() {
        assertFalse(AboutIntentHandler.accepts("SOMETHING_ELSE", AboutState(admin = true, updateAvailable = true, installReady = true)))
        assertFalse("an empty action is not a row", AboutIntentHandler.accepts("", AboutState(admin = true)))
        assertFalse(
            "the old rollback name is no longer an action",
            AboutIntentHandler.accepts("ROLLBACK", AboutState(admin = true, updateAvailable = true, installReady = true, failed = true, upToDate = true)),
        )
        assertFalse(
            "the old rollback name is refused with every flag set",
            AboutIntentHandler.accepts("ROLLBACK", AboutState(admin = true, updateAvailable = true, installReady = true, checking = true, installing = true, failed = true, upToDate = true)),
        )
    }

    @Test
    fun `the rows the decision offers are the taps that may act`() {
        assertTrue(AboutIntentHandler.accepts(AboutActions.CHECK, AboutState(admin = true)))
        assertTrue(
            "an available and ready update must let the install row act",
            AboutIntentHandler.accepts(AboutActions.INSTALL, AboutState(admin = true, updateAvailable = true, installReady = true)),
        )
    }
}