package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Protects the rule for a start request to the service (all three actions, switched on and off) and
 * the exact reading of the action text, including the restart-with-nothing-on guard.
 */
internal class ServiceStartDecisionTest {

    private fun expectDecision(action: ServiceAction, armed: Boolean, expected: StartDecision) {
        assertEquals(
            "app: decide($action, armed=$armed) gave the wrong decision",
            expected,
            ServiceStartDecisions.decide(action, armed),
        )
    }

    @Test
    fun `arm while on keeps`() = expectDecision(ServiceAction.ARM, true, StartDecision.KEEP)

    @Test
    fun `arm while off adopts and keeps`() = expectDecision(ServiceAction.ARM, false, StartDecision.ADOPT_AND_KEEP)

    @Test
    fun `disarm while on stops at once`() = expectDecision(ServiceAction.DISARM, true, StartDecision.STOP_AT_ONCE)

    @Test
    fun `disarm while off stops at once`() = expectDecision(ServiceAction.DISARM, false, StartDecision.STOP_AT_ONCE)

    @Test
    fun `unknown while on keeps`() = expectDecision(ServiceAction.UNKNOWN, true, StartDecision.KEEP)

    @Test
    fun `unknown while off stops at once so a restart with nothing on ends`() =
        expectDecision(ServiceAction.UNKNOWN, false, StartDecision.STOP_AT_ONCE)

    @Test
    fun `the action texts are the exact strings`() {
        assertEquals("app: the arm action text changed", "dev.breaker.dictation.action.ARM", ACTION_ARM)
        assertEquals("app: the disarm action text changed", "dev.breaker.dictation.action.DISARM", ACTION_DISARM)
    }

    @Test
    fun `serviceActionOf reads the two exact texts`() {
        assertEquals("app: the arm text must read as ARM", ServiceAction.ARM, serviceActionOf(ACTION_ARM))
        assertEquals("app: the disarm text must read as DISARM", ServiceAction.DISARM, serviceActionOf(ACTION_DISARM))
    }

    @Test
    fun `serviceActionOf reads null, other text and case differences as UNKNOWN`() {
        val others: List<String?> = listOf(
            null,
            "",
            "ARM",
            "dev.breaker.dictation.action.arm",
            "DEV.BREAKER.DICTATION.ACTION.DISARM",
            ACTION_ARM + " ",
            " " + ACTION_DISARM,
            "android.intent.action.MAIN",
        )
        for (text in others) {
            assertEquals("app: '$text' must read as UNKNOWN", ServiceAction.UNKNOWN, serviceActionOf(text))
        }
    }
}
