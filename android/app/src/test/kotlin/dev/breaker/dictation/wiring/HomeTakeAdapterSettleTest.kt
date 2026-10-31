package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home take adapter over the shared port: the second tap finishes the take and the shared
 * settle runs SENDING, then finish, save, resetAfterTaken, IDLE, then the line, with the committer never
 * reached. Each test says in its KDoc what would break it.
 *
 * The ports are the shared [HomeTakeFakes] ones, the same objects the first-tap test drives.
 */
internal class HomeTakeAdapterSettleTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val settle = TakeSettle(store, clipboard)
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val lines = ArrayList<String?>()
    private val states = ArrayList<DictationState>()

    private fun adapter(switch: ArmedSwitch, bg: Background = background): HomeTakeAdapter {
        lines.clear()
        states.clear()
        return HomeTakeAdapter(
            take = OwnedTakePort(take, owner, TakeOwnerKind.HOME),
            owner = owner,
            settle = settle,
            switch = switch,
            background = bg,
            main = main,
            onLine = { line -> lines.add(line); log.add("line:$line") },
            onState = { state -> states.add(state); log.add("state:$state") },
        )
    }

    /** The switch the Home take shares, over the in-memory off-store; [off] is the user's stored choice. */
    private fun homeSwitch(off: Boolean): HomeSwitch {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return HomeSwitch(switch, launcher)
    }

    /** Fails if resetAfterTaken is dropped from the settle order. */
    @Test
    fun `the settle runs finish, save and reset, then goes idle, then shows the line`() {
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        log.clear()
        lines.clear()
        states.clear()
        a.onTap()
        assertEquals(
            "app: home: the order is SENDING, then finish, save, resetAfterTaken, IDLE, then the line",
            listOf(
                "state:SENDING",
                "finish",
                "save",
                "resetAfterTaken",
                "state:IDLE",
                "line:Text is in History and copied.",
            ),
            log,
        )
        assertEquals("app: home: a settled take shows SENDING then ends IDLE", listOf(DictationState.SENDING, DictationState.IDLE), states)
    }

    /** Fails if a settled take sends, reaches the committer, or does not end IDLE with the Home line. */
    @Test
    fun `a settled take shows SENDING then ends IDLE, never sends and never commits`() {
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals("app: home: the take must never send", 0, take.sendCount)
        assertEquals("app: home: no committer may be reached", 0, committer.commits)
        assertTrue("app: home: the transcription shows the SENDING face", states.any { it == DictationState.SENDING })
        assertEquals("app: home: the settle ends IDLE", DictationState.IDLE, states.last())
        assertEquals("app: home: the line is the outcome, not a commit", "Text is in History and copied.", lines.last())
    }

    /**
     * Fails if the refuse-catch in finishAndSettle pushes SENDING instead of IDLE: with a background
     * whose submit throws, the finish block never runs, so the catch's IDLE is the only thing that
     * clears the amber face. The mutant that pushes SENDING there leaves the face stuck forever.
     */
    @Test
    fun `a refused finish shows SENDING then settles IDLE, never stuck on SENDING`() {
        take.listeningElsewhere()          // recording, with no begin from the adapter (the begin would throw)
        owner.begun(TakeOwnerKind.HOME)     // the Home holds it
        adapter(homeSwitch(off = false).switch, bg = SendingThrowingBackground()).onSend()
        assertEquals("app: home: a refused finish still clears the amber face to IDLE",
            listOf(DictationState.SENDING, DictationState.IDLE), states)
        assertEquals("app: home: a refused submit settles as not-converted",
            "The speech could not be converted.", lines.single())
    }

    /** Fails if the Home taken line is replaced by TakenSentences.STILL_TAKEN. */
    @Test
    fun `a taken begin answers the Home line and settles nothing`() {
        take.beginResult = BeginResult.Taken
        adapter(homeSwitch(off = false).switch).onTap()
        assertEquals("app: home: a taken begin answers the Home line", HomeSentences.MIC_TAKEN, lines.single())
        assertEquals("app: home: a taken begin finishes nothing", 0, take.finishCount)
        assertEquals("app: home: a taken begin saves nothing", 0, store.calls.size)
        assertEquals("app: home: a taken begin copies nothing", 0, clipboard.copies)
        assertEquals("app: home: a taken begin pushes no state", emptyList<DictationState>(), states)
    }

    /** Fails if the settle calls send() instead of the clipboard copy. */
    @Test
    fun `a full take leaves the committer count at zero`() {
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals("app: home: the take must never send", 0, take.sendCount)
        assertEquals("app: home: the committer count must be 0", 0, committer.commits)
        assertEquals("app: home: the copy goes through the clipboard", 1, clipboard.copies)
        assertEquals("app: home: the text reaches History once", listOf("save"), store.calls)
    }

    /** Fails if the save is dropped from the settle, so the text never reaches History. */
    @Test
    fun `the second tap finishes once, then saves and answers the line`() {
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals("app: home: one finish", 1, take.finishCount)
        assertEquals("app: home: the text is saved once", listOf("save"), store.calls)
        assertEquals("app: home: the text is copied once", 1, clipboard.copies)
        assertEquals("app: home: the saved row carries the text", listOf(SAMPLE), store.saved)
        assertEquals(
            "app: home: the line is the saved-and-copied ending",
            listOf<String?>(null, "Text is in History and copied."),
            lines,
        )
    }

    /** Fails if a blank text is copied, putting nothing on the clipboard. */
    @Test
    fun `a blank take saves nothing, copies nothing and answers the nothing-heard line`() {
        take.finishResult = FinishResult.ReadyToSend(BLANK)
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals("app: home: a blank text saves nothing", emptyList<String>(), store.calls)
        assertEquals("app: home: a blank text copies nothing", 0, clipboard.copies)
        assertEquals("app: home: the line is the runner's own", RunnerSentences.NOTHING_HEARD, lines.last())
    }

    /** Fails if an empty take is answered with the not-converted line. */
    @Test
    fun `an empty take answers the nothing-heard line too`() {
        take.finishResult = FinishResult.Failed(EMPTY_TAKE_SENTENCE)
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals("app: home: an empty take saves nothing", emptyList<String>(), store.calls)
        assertEquals("app: home: an empty take copies nothing", 0, clipboard.copies)
        assertEquals("app: home: the line is the runner's own", RunnerSentences.NOTHING_HEARD, lines.last())
    }

    @Test
    fun `a Home take that fails with an engine sentence settles with exactly that line`() {
        take.finishResult = FinishResult.Failed("The server key is not set.")
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals("app: home: an engine failure settles with the engine sentence", "The server key is not set.", lines.last())
    }

    /** The switch the adapter is given, with the launcher it must never have to call. */
    private class HomeSwitch(val switch: ArmedSwitch, val launcher: FakeLauncher)
}

/** A [Background] whose submit throws: the finish block never runs, so the refuse-catch in
 * finishAndSettle is the only thing that can settle IDLE. Fails if that catch pushes SENDING (the
 * amber face stuck forever on a refused submit). */
private class SendingThrowingBackground : Background {
    override fun submit(block: () -> Unit) { throw IllegalStateException("background refused to run") }
}
