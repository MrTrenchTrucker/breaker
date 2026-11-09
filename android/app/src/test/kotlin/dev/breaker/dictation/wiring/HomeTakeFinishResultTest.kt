package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The finished take reaches the app's finish-result answer: the Home wiring hands it to the adapter, and the
 * adapter calls it once, after the settle, with exactly what the finish answered (null when the finish threw).
 * Each test drives two taps over the shared fakes and reads what the answer was given.
 */
internal class HomeTakeFinishResultTest {

    private val log = ArrayList<String>()
    private val take = FakeHomeTake(CountingCommitter(), log)
    private val results = ArrayList<FinishResult?>()

    /** The wiring with the screen attached and the finish-result answer recording what it is given. */
    private fun built(): HomeTakeWiring {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val wiring = HomeTakeWiring(
            take = { take },
            history = { InMemoryHistoryStore(log) },
            clipboard = FakeTakenClipboard(),
            switch = switch,
            owner = TakeOwner(),
            background = FakeBackground(),
            main = FakeMainPost(),
            modelReady = { true },
            onFinishResult = { result -> results.add(result) },
        )
        wiring.attach(QuietHome())
        return wiring
    }

    @Test
    fun `a finished take hands its result to the finish answer once`() {
        val wiring = built()
        wiring.onTap()
        assertEquals("app: the first tap finishes nothing, so no result yet", 0, results.size)
        wiring.onTap()
        assertEquals("app: the second tap hands the finish answer once", 1, results.size)
        assertEquals("app: the result is what the finish answered", take.finishResult, results.single())
    }

    @Test
    fun `a refused finish reaches the finish answer as the refusal itself`() {
        val refusal = FinishResult.Failed(SessionRefusalSentences.KEY_REFUSED_TEXT)
        take.finishResult = refusal
        val wiring = built()
        wiring.onTap()
        wiring.onTap()
        assertEquals("app: the refusal reaches the answer unchanged", listOf<FinishResult?>(refusal), results)
    }

    @Test
    fun `a finish that throws reaches the finish answer as null`() {
        take.finishThrows = true
        val wiring = built()
        wiring.onTap()
        wiring.onTap()
        assertEquals("app: a thrown finish reaches the answer as null", listOf<FinishResult?>(null), results)
    }

    /** A [HomeControl] that shows nothing. */
    private class QuietHome : HomeControl {
        override fun showLine(line: String?) {}

        override fun showRecording(recording: Boolean) {}

        override fun refresh() {}
    }
}
