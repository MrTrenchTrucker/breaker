package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The three look rules of the History host, read as text because no unit test runs the view:
 * the bleed call in the init block, the Back intent that asks the activity for its back press,
 * and the empty state's mic art that the host paints into its placeholder. Each rule holds on the
 * real file and is broken by two edited samples; a quiet edit leaves it true.
 */

/** The host's code with its comments taken out. */
private val HOST: String = withoutComments(mainSourceOf("render/HistoryHostView.kt"))

/** The init block that turns on the bleed. */
private val BLEED_INIT = Regex("""init \{\s*openForBleed\(\)\s*\}""")

/** The Back intent that asks the activity for its back press. */
private val BACK_HANDLED = Regex("""if \(intent == ScreenIntent\.Back\) \{\s*\(context as\? android\.app\.Activity\)\?\.onBackPressed\(\)\s*\}""")

/** The placeholder lookup and the mic art paint that goes into it. */
private val MIC_PLACED = Regex("""findTagged\(getChildAt\(0\), EMPTY_ART_ID\)""")
private val MIC_PAINTED = Regex("""slot\.background = MicArtDrawable\(context, theme\)""")

class HistoryHostLookRulesTest {
    @Test
    fun `the host turns on the bleed in its init block`() {
        assertTrue("host: init does not call openForBleed() alone", BLEED_INIT.containsMatchIn(HOST))
        assertFalse(
            "host: the bleed call replaced by a plain fill passed",
            BLEED_INIT.containsMatchIn(HOST.replace("openForBleed()", "isFillViewport = true")),
        )
        assertFalse("host: the bleed call removed passed", BLEED_INIT.containsMatchIn(HOST.replace("openForBleed()", "")))
        assertTrue(
            "host: a trailing space after the call changed the rule",
            BLEED_INIT.containsMatchIn(HOST.replace("openForBleed()", "openForBleed() ")),
        )
    }

    @Test
    fun `the host hands the Back intent to the activity`() {
        assertEquals("host: the Back branch is not present once", 1, BACK_HANDLED.findAll(HOST).count())
        assertFalse(
            "host: a Back branch that never asks the activity passed",
            BACK_HANDLED.containsMatchIn(HOST.replace("if (intent == ScreenIntent.Back) {", "if (false) {")),
        )
        assertFalse(
            "host: a Back branch that calls another method passed",
            BACK_HANDLED.containsMatchIn(HOST.replace("onBackPressed()", "finish()")),
        )
    }

    @Test
    fun `the host paints the mic art into the empty state placeholder`() {
        assertEquals("host: the placeholder lookup is not present once", 1, MIC_PLACED.findAll(HOST).count())
        assertEquals("host: the mic paint is not present once", 1, MIC_PAINTED.findAll(HOST).count())
        assertFalse(
            "host: a placeholder lookup under another id passed",
            MIC_PLACED.containsMatchIn(HOST.replace("EMPTY_ART_ID", "\"history.other\"")),
        )
        assertFalse(
            "host: a paint with no mic passed",
            MIC_PAINTED.containsMatchIn(HOST.replace("MicArtDrawable(context, theme)", "null")),
        )
    }
}
