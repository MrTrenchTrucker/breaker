package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import dev.breaker.dictation.ui.HomeControl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sticky sending flag on the Home take wiring: it is set on onState(SENDING) and cleared by any
 * other state, and read by the derived face so a busy flip during transcription re-pushes SENDING
 * instead of painting ARMED over it. The face clause itself is pinned by [HomeMicFaceTest]; here the
 * flag's set/keep/clear is watched directly on [HomeTakeWiring.sending].
 *
 * The flag is set on onState(SENDING) then cleared by the settle's onState(IDLE) in the SAME finish
 * call when the background runs synchronously, so it is never observable after onTap() returns. A
 * [SendingQueuedBackground] holds each submitted block until [SendingQueuedBackground.runAll], so after the finish's
 * onTap() the flag is set while the settle is still queued — the one window the assertions watch.
 */
internal class HomeTakeSendingTest {

    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(ArrayList<String>())
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, ArrayList<String>())
    private val main = FakeMainPost()
    private val home = SendingHomeControl()

    /** The wiring over [busy] with [home] attached and a [SendingQueuedBackground]; the switch is stored on. */
    private fun built(busy: FakeMicBusyPort): Built {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = false
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        val bg = SendingQueuedBackground()
        val wiring = HomeTakeWiring(
            take = { take },
            history = { store },
            clipboard = clipboard,
            switch = switch,
            owner = TakeOwner(),
            busy = busy,
            background = bg,
            main = main,
            modelReady = { true },
        )
        wiring.attach(home)
        return Built(wiring, bg)
    }

    private class Built(val wiring: HomeTakeWiring, val bg: SendingQueuedBackground)

    /** The take end sets the flag, and a busy flip re-pushes without clearing it (the flag is sticky). */
    @Test
    fun `the take end sets the sending flag and a busy flip keeps it`() {
        val busy = FakeMicBusyPort()
        val built = built(busy)
        built.wiring.onTap()
        built.bg.runAll() // the begin runs -> onState(RECORDING) -> sending is false
        assertEquals("app: sending: no flag while recording", false, built.wiring.sending)
        built.wiring.onTap() // the finish -> onState(SENDING) sets the flag; the settle is still queued
        assertEquals("app: sending: the take end sets the sending flag", true, built.wiring.sending)
        busy.flipTo(true)
        busy.flipTo(false) // a busy flip re-pushes but must not clear the flag
        assertEquals("app: sending: a busy flip keeps the sending flag (sticky)", true, built.wiring.sending)
    }

    /** The settle's onState(IDLE) clears the flag after the take end set it. */
    @Test
    fun `the settle clears the sending flag`() {
        val busy = FakeMicBusyPort()
        val built = built(busy)
        built.wiring.onTap()
        built.bg.runAll() // the begin -> recording, sending is false
        built.wiring.onTap() // the finish -> sending is true, the settle is still queued
        assertEquals("app: sending: the take end sets the flag first", true, built.wiring.sending)
        built.bg.runAll() // the settle runs -> onState(IDLE) clears the flag
        assertEquals("app: sending: the settle clears the flag", false, built.wiring.sending)
    }
}

/** Holds the blocks it is given until [runAll], so a tap can arrive while a finish is still queued. */
private class SendingQueuedBackground : Background {
    private val queue: MutableList<() -> Unit> = ArrayList()

    override fun submit(block: () -> Unit) {
        queue.add(block)
    }

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeAt(0)()
    }
}

/** A [HomeControl] that records every recording look it is given, attached so rePushState has a home. */
private class SendingHomeControl : HomeControl {
    val recordings: MutableList<Boolean> = ArrayList()
    override fun showLine(line: String?) {}
    override fun showRecording(recording: Boolean) { recordings.add(recording) }
    override fun refresh() {}
}
