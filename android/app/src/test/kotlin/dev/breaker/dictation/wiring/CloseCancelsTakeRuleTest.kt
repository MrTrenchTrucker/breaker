package dev.breaker.dictation.wiring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The truth table of the rule that decides whether a user close cancels the take. The rule cancels only when
 * all six terms hold; each case below flips one term alone and names it in its failure message.
 */
internal class CloseCancelsTakeRuleTest {

    /** The rule with every term clear unless a case names one term to change. */
    private fun rule(
        armed: Boolean = true,
        failed: Boolean = false,
        yielding: Boolean = false,
        sending: Boolean = false,
        inFlight: Boolean = true,
        heldElsewhere: Boolean = false,
    ): Boolean = closeCancelsTake(armed, failed, yielding, sending, inFlight, heldElsewhere)

    @Test
    fun `all six terms clear make a user close cancel the take`() {
        assertTrue("closeCancelsTake: all six terms clear must cancel the take", rule())
    }

    @Test
    fun `a switch that is off stops a user close from cancelling`() {
        assertFalse("closeCancelsTake: the armed term must stop the cancel when the switch is off", rule(armed = false))
    }

    @Test
    fun `a close during a device failure does not cancel the take`() {
        assertFalse(
            "closeCancelsTake: the failed term must stop the cancel while a device failure runs",
            rule(failed = true),
        )
    }

    @Test
    fun `a close during a microphone yield does not cancel the take`() {
        assertFalse("closeCancelsTake: the yielding term must stop the cancel during the yield", rule(yielding = true))
    }

    @Test
    fun `a close while the text is on its way does not cancel the take`() {
        assertFalse(
            "closeCancelsTake: the sending term must stop the cancel while the text is sent",
            rule(sending = true),
        )
    }

    @Test
    fun `a close with no take in flight does not cancel the take`() {
        assertFalse(
            "closeCancelsTake: the inFlight term must stop the cancel with no take in flight",
            rule(inFlight = false),
        )
    }

    @Test
    fun `a close while the Home holds the take does not cancel the take`() {
        assertFalse(
            "closeCancelsTake: the heldElsewhere term must stop the cancel while the Home holds",
            rule(heldElsewhere = true),
        )
    }
}
