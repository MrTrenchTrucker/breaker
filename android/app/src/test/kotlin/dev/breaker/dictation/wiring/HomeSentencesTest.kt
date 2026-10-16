package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.service.StartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home lines are the APPROVED ledger rows, verbatim, and the outcome lines reuse the existing
 * words: the shared [EndingTexts] endings and the runner's own nothing-heard line. The test also
 * pins the stored-off check the Home take shares with the launcher's start: the new
 * [ArmedSwitch.isUserSwitchedOff] answers the very same check [ArmedSwitch.armAtStart] makes.
 */
internal class HomeSentencesTest {

    @Test
    fun `the approved lines are verbatim`() {
        assertEquals("Breaker is off. Turn it on to talk.", HomeSentences.SWITCHED_OFF)
        assertEquals("Another app still has the microphone. Try again when it is free.", HomeSentences.MIC_TAKEN)
    }

    @Test
    fun `the outcome lines reuse the shared endings and the runner's blank`() {
        val lines: OutcomeLines = HomeSentences.lines
        assertEquals(EndingTexts.SAVED_AND_COPIED, lines.savedAndCopied)
        assertEquals(EndingTexts.COPIED_NOT_SAVED, lines.copiedNotSaved)
        assertEquals(RunnerSentences.NOTHING_HEARD, lines.blank)
        assertEquals(RunnerSentences.NOTHING_HEARD, lines.noAudio)
        assertEquals(EndingTexts.NOT_CONVERTED, lines.notConverted)
    }

    @Test
    fun `the shared stored-off check answers the same as armAtStart`() {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val store = MemoryOffStore()
        val switch = ArmedSwitch(controller, store)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        store.off = true
        assertTrue("app: home: a stored off must block the tap", switch.isUserSwitchedOff())
        assertNull("app: the stored-off check armAtStart uses must not arm either", switch.armAtStart())
        store.off = false
        assertFalse("app: home: no off means the tap is not blocked", switch.isUserSwitchedOff())
        assertSame("app: the stored-off check armAtStart uses must arm", StartResult.Started, switch.armAtStart())
    }
}
