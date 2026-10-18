package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home take wiring over the shared fakes: the tap, the line, the recording look, and the lazy
 * ports. The stored-off answer is not repeated here: [HomeTakeAdapterTest] pins it on the adapter.
 */
internal class HomeTakeWiringTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val home = FakeHomeControl()
    private var takeBuilds: Int = 0
    private var historyBuilds: Int = 0
    private var modelPresent: Boolean = true

    /**
     * The wiring with the screen attached. The ports are counted when they are first used. [off] is the
     * user's stored choice. The model is read at each tap, from [modelPresent]. Every wiring built here
     * shares [owner], as the tile host shares it across screens.
     */
    private fun built(off: Boolean = false): HomeTakeWiring {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val wiring = HomeTakeWiring(
            take = { takeBuilds += 1; take },
            history = { historyBuilds += 1; store },
            clipboard = clipboard,
            switch = switch,
            owner = owner,
            background = background,
            main = main,
            modelReady = { modelPresent },
        )
        wiring.attach(home)
        return wiring
    }

    /** One tap on the wiring begins the take once, and the first tap finishes nothing. */
    @Test
    fun `one tap on the wiring begins the take once and finishes nothing`() {
        built().onTap()
        assertEquals("app: one tap must begin the take once", 1, take.beginCount)
        assertEquals("app: the first tap must finish nothing", 0, take.finishCount)
    }

    /** A second tap while listening finishes the take once and begins no second one. */
    @Test
    fun `a second tap on the wiring finishes the take once and begins no second one`() {
        val wiring = built()
        wiring.onTap()
        wiring.onTap()
        assertEquals("app: the second tap must begin nothing more", 1, take.beginCount)
        assertEquals("app: the second tap must finish once", 1, take.finishCount)
    }

    /** The line the take answers reaches the screen once: the plain line of the first tap, then the saved line. */
    @Test
    fun `the line the take answers reaches the home screen once`() {
        val wiring = built()
        wiring.onTap()
        wiring.onTap()
        assertEquals(
            "app: the screen must get the plain line, then the saved line, once each",
            listOf<String?>(null, EndingTexts.SAVED_AND_COPIED),
            home.lines,
        )
    }

    /** RECORDING shows the recording look and IDLE takes it away. */
    @Test
    fun `recording shows the recording look and idle takes it away`() {
        val wiring = built()
        wiring.onTap()
        assertEquals("app: RECORDING must show the recording look", listOf(true), home.recordings)
        wiring.onTap()
        assertEquals("app: IDLE must take the recording look away", listOf(true, false), home.recordings)
    }

    /** A tap that the take refuses, as it is while another take runs, shows the runner's sentence and starts nothing. */
    @Test
    fun `a tap that the take refuses shows the runner sentence and starts no recording`() {
        take.beginResult = BeginResult.Refused(RunnerSentences.BUSY)
        built().onTap()
        assertEquals("app: the refusal must be the runner's own sentence", listOf<String?>(RunnerSentences.BUSY), home.lines)
        assertEquals("app: a refused begin must not show the recording look", emptyList<Boolean>(), home.recordings)
        assertEquals("app: a refused begin must not finish anything", 0, take.finishCount)
    }

    /** A failed start answers the runner's own sentence. The Home take has no model check of its own. */
    @Test
    fun `a failed start shows the runner sentence and starts no recording`() {
        take.beginResult = BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD)
        built().onTap()
        assertEquals("app: the failure must be the runner's own sentence", listOf<String?>(RunnerSentences.COULD_NOT_RECORD), home.lines)
        assertEquals("app: a failed start must not show the recording look", emptyList<Boolean>(), home.recordings)
    }

    /** The take port and the history store are built on first use, not when the wiring is built. */
    @Test
    fun `the take port and the history store are built on first use and not before`() {
        val wiring = built()
        assertEquals("app: no take port before the first tap", 0, takeBuilds)
        assertEquals("app: no history store before a save", 0, historyBuilds)
        wiring.onTap()
        assertEquals("app: the first tap builds the take port", 1, takeBuilds)
        assertEquals("app: a begin does not open the history store", 0, historyBuilds)
        wiring.onTap()
        assertEquals("app: the take port is built once", 1, takeBuilds)
        assertEquals("app: the save opens the history store once", 1, historyBuilds)
    }

    /** Fails if the stored-off check comes after the port is read: a stored-off tap must build nothing. */
    @Test
    fun `a tap with the switch stored off builds nothing and answers the off line`() {
        built(off = true).onTap()
        assertEquals("app: stored off: the take port must not be built", 0, takeBuilds)
        assertEquals("app: stored off: the tap answers the off line", listOf<String?>(HomeSentences.SWITCHED_OFF), home.lines)
        assertEquals("app: stored off: no begin", 0, take.beginCount)
    }

    /** Fails if the take a screen began is not finishable by a screen built after it: a rebuilt screen shares the owner. */
    @Test
    fun `a rebuilt home screen finishes the take the earlier one began`() {
        built().onTap()
        built().onTap()
        assertEquals("app: the rebuilt screen must finish the take the earlier one began", 1, take.finishCount)
        assertEquals("app: the rebuilt screen must begin nothing more", 1, take.beginCount)
    }

    /** Fails if a screen attached while the Home holds a recording take does not get the recording look (the rotation case). */
    @Test
    fun `a screen attached while the home holds a recording take shows the recording look`() {
        val wiring = built()
        wiring.onTap()
        val rotated = FakeHomeControl()
        wiring.attach(rotated)
        assertEquals("app: the attached screen must show the recording look", listOf(true), rotated.recordings)
    }

    /** Fails if a screen attached with no take held shows a recording look. */
    @Test
    fun `a screen attached with no take held shows no recording look`() {
        val wiring = built()
        val fresh = FakeHomeControl()
        wiring.attach(fresh)
        assertEquals("app: no take held must mean no recording look", emptyList<Boolean>(), fresh.recordings)
    }

    /** Fails if a screen attached during a take the tile holds shows the Home's recording look: only the Home's own take counts. */
    @Test
    fun `a screen attached during a take the tile holds shows no recording look`() {
        take.listeningElsewhere()
        owner.begun(TakeOwnerKind.TILE)
        val wiring = built()
        val screen = FakeHomeControl()
        wiring.attach(screen)
        assertEquals("app: a take the Home did not begin must not show the Home's recording look", emptyList<Boolean>(), screen.recordings)
    }

    /**
     * Fails if the model check runs on every tap: a finishing tap with the model gone must still finish the
     * Home's own take, and must not answer the model sentence.
     */
    @Test
    fun `a finishing tap with the model gone still finishes the take and shows no model sentence`() {
        val wiring = built()
        wiring.onTap()
        modelPresent = false
        wiring.onTap()
        assertEquals("app: the finishing tap must finish the take once", 1, take.finishCount)
        assertEquals(
            "app: the finishing tap must answer the saved line, not the model sentence",
            listOf<String?>(null, EndingTexts.SAVED_AND_COPIED),
            home.lines,
        )
    }

    /** With no model the tap answers the existing model sentence, begins nothing and never builds the take port. */
    @Test
    fun `a tap with no model answers the model sentence and starts no take`() {
        modelPresent = false
        built().onTap()
        assertEquals("app: no model must answer the existing model sentence", listOf<String?>(ModelSentences.NO_MODEL), home.lines)
        assertEquals("app: no model must begin nothing", 0, take.beginCount)
        assertEquals("app: no model must not build the take port", 0, takeBuilds)
        assertEquals("app: no model must show no recording look", emptyList<Boolean>(), home.recordings)
    }

    /** Fails if the model check is skipped whenever the owner is HOME: a stale holder over an idle port is a beginning tap. */
    @Test
    fun `a stale home holder with the port idle and no model answers the model sentence and starts no take`() {
        owner.begun(TakeOwnerKind.HOME)
        modelPresent = false
        built().onTap()
        assertEquals("app: a stale holder over an idle port must answer the model sentence", listOf<String?>(ModelSentences.NO_MODEL), home.lines)
        assertEquals("app: a stale holder over an idle port must begin nothing", 0, take.beginCount)
        assertEquals("app: a stale holder over an idle port must finish nothing", 0, take.finishCount)
    }
}

/** A [HomeControl] that records every line and every recording change it is given. */
private class FakeHomeControl : HomeControl {
    val lines: MutableList<String?> = ArrayList()
    val recordings: MutableList<Boolean> = ArrayList()

    override fun showLine(line: String?) {
        lines.add(line)
    }

    override fun showRecording(recording: Boolean) {
        recordings.add(recording)
    }

    override fun refresh() {}
}
