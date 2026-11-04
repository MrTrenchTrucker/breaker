package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Protects the rule that reads the data string of a return intent: only the view action with a
 * non-empty data string is read, and the string comes back exactly as it arrived.
 */
internal class HandoffIntentRuleTest {

    private val view = "android.intent.action.VIEW"
    private val returnLink = "breaker://handoff?code=" + "Q".repeat(43)

    @Test
    fun `read returns the data string unchanged for the view action`() {
        val inputs = listOf(returnLink, "  spaced out  ", "   ", "x")
        for (text in inputs) {
            assertEquals("wiring: read must not change the data string", text, HandoffIntentRule.read(view, text))
        }
    }

    @Test
    fun `read returns null for every action other than the view action`() {
        val actions: List<String?> = listOf(
            null,
            "android.intent.action.MAIN",
            "android.intent.action.main",
            "android.intent.action.SEND",
            "android.intent.action.VIEW ",
        )
        for (action in actions) {
            assertNull("wiring: read must refuse a non view action", HandoffIntentRule.read(action, returnLink))
        }
    }

    @Test
    fun `read returns null for a null or an empty data string`() {
        assertNull("wiring: null data must be refused", HandoffIntentRule.read(view, null))
        assertNull("wiring: empty data must be refused", HandoffIntentRule.read(view, ""))
    }

    @Test
    fun `read refuses a null action even with a good link`() {
        assertNull("wiring: a null action must be refused", HandoffIntentRule.read(null, returnLink))
    }

    @Test
    fun `the view action constant holds the platform name`() {
        assertEquals("wiring: the view action name is wrong", "android.intent.action.VIEW", HandoffIntentRule.ACTION_VIEW)
    }
}
