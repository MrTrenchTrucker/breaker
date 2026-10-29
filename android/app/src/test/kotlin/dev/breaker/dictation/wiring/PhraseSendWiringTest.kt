package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spoken Send through the real parts: the phrase detector of the component, the send handler, the take owner
 * and its link, the tile coordinator and the Home adapter. The take is a fake that keeps the trim each finish is
 * given; the stream is the shared fake, whose update is fired by the test. Every step runs on the test thread
 * over the manual queues.
 */
internal class PhraseSendWiringTest {

    /** A take that answers a text and keeps the trim of each finish it is given. */
    private class TrimRecordingTake : TakePort {
        val trims: MutableList<Long?> = ArrayList()

        override val sessionState: DictationState
            get() = DictationState.RECORDING

        override fun begin(): BeginResult = BeginResult.Recording

        override fun finish(trimBeforeMs: Long?): FinishResult {
            trims.add(trimBeforeMs)
            return FinishResult.ReadyToSend(tileTranscription("hello there"))
        }

        override fun send(): SendResult? = sentResult(CommitOutcome.COMMITTED, null)

        override fun cancel() {}

        override fun resetAfterTaken() {}
    }

    /** One wiring of the parts, over one stream that the component's detector reads, with a warning counter. */
    private inner class Rig(withStream: Boolean) {
        val stream = PhraseTestStream()
        val main = ManualMain()
        val background = ManualBackground()
        val take = TrimRecordingTake()
        val owner = TakeOwner()
        val tile = RecordingTile()
        var warnings: Int = 0
        private val controller = DictationServiceController(SwitchPermission(true), RecordingLauncher())
        val component: DictationComponent = phraseComponent(
            words = { if (withStream) stream else null },
            mic = ScriptedMic(),
            controller = controller,
        )
        val homeAdapter: HomeTakeAdapter = HomeTakeAdapter(
            take = take,
            owner = owner,
            settle = TakeSettle(null, FakeTakenClipboard()),
            switch = ArmedSwitch(DictationServiceController(FakePermission(), FakeLauncher()), MemoryOffStore()),
            background = background,
            main = main,
            onLine = {},
            onState = {},
        )
        private val handler: PhraseSendHandler = PhraseSendHandler(
            trigger = component.phrases,
            owner = owner,
            main = main,
            homeSend = { trim -> owner.sendToHome(trim) },
            tileSend = { trim -> coordinator.onSend(trim) },
            onUnavailable = { warnings += 1 },
        )
        val link: PhraseTakeLink = PhraseTakeLink(
            worker = background,
            owner = owner,
            prepare = {},
            start = { handler.start() },
            stop = { handler.stop() },
        )
        lateinit var coordinator: TileCoordinator

        init {
            owner.setHomeSend { trim -> homeAdapter.onSend(trim) }
            coordinator = TileCoordinator(
                tile, main, background,
                OwnedTakePort(take, owner, TakeOwnerKind.TILE),
                FakeModelReady(true), RecordingModelNotice(), RecordingOpener(),
                RecordingClipboard(), {}, RecordingHistory(),
            )
        }

        /** Runs the posted and submitted blocks until both queues are empty, with a bound that fails by name. */
        fun settle() {
            var passes = 0
            while (background.pending > 0 || main.pending > 0) {
                passes += 1
                check(passes <= 1_000) { "app: the spoken send test did not settle after 1000 passes" }
                background.runAll()
                main.drain()
            }
        }

        /** The owner listens: a begin starts the handler through the link, a settle stops it. */
        fun attach() {
            owner.setListener(link)
        }
    }

    /** The update a person saying "and I'm gone" produces, with "and" heard at one second. */
    private fun sendUpdate(): WordUpdate =
        WordUpdate(listOf(HeardWord("and", 1_000L), HeardWord("i'm", 1_200L), HeardWord("gone", 1_500L)), false)

    @Test
    fun `a spoken send on the tile reaches the take finish with its trim unchanged`() {
        val rig = Rig(withStream = true)
        rig.attach()
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: the listening run must have started once for the take", 1, rig.stream.starts)
        rig.stream.emit(sendUpdate())
        rig.settle()
        assertEquals("app: the send's time must reach the take finish unchanged", listOf<Long?>(1_000L), rig.take.trims)
        assertEquals("app: a stream that is ready must give no warning", 0, rig.warnings)
    }

    @Test
    fun `a spoken send on the home reaches the take finish with its trim unchanged`() {
        val rig = Rig(withStream = true)
        rig.attach()
        rig.owner.begun(TakeOwnerKind.HOME)
        rig.settle()
        assertEquals("app: the listening run must have started once for the home take", 1, rig.stream.starts)
        rig.stream.emit(sendUpdate())
        rig.settle()
        assertEquals("app: the home send's time must reach the take finish unchanged", listOf<Long?>(1_000L), rig.take.trims)
    }

    @Test
    fun `a take that begins with no stream ready records, finishes, and warns once`() {
        val rig = Rig(withStream = false)
        rig.attach()
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: the take must not be left listening without a stream", false, rig.component.phrases.isListening)
        assertEquals("app: one warning for the take that began without spoken send", 1, rig.warnings)
        assertEquals("app: the take must have no user wording on the tile", emptyList<String>(), rig.tile.notices)
        rig.coordinator.onSend()
        rig.settle()
        assertEquals("app: the take must still finish normally, with no trim", listOf<Long?>(null), rig.take.trims)
    }

    @Test
    fun `a second take with no stream ready warns once more`() {
        val rig = Rig(withStream = false)
        rig.attach()
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        rig.coordinator.onSend()
        rig.settle()
        rig.coordinator.onBegin()
        rig.settle()
        assertEquals("app: each take that began without spoken send warns once", 2, rig.warnings)
    }

    @Test
    fun `every end of a take stops the listening run exactly once`() {
        val ends: List<Pair<String, (Rig) -> Unit>> = listOf(
            "a cancel" to { rig -> rig.coordinator.onCancel() },
            "a finish" to { rig -> rig.coordinator.onSend() },
            "a microphone taken" to { rig ->
                val m = rig.owner.mark()
                rig.owner.settledIf(m)
                rig.coordinator.onTakeMicTaken()
            },
            "a take ended by itself" to { rig ->
                val m = rig.owner.mark()
                rig.owner.settledIf(m)
                rig.coordinator.onTakeEnded()
            },
            "a device failure" to { rig ->
                val m = rig.owner.mark()
                rig.owner.settledIf(m)
                assertTrue("app: the tile must take the device failure, so no fallback end runs", rig.coordinator.onTakeFailed())
            },
            "a switch off" to { rig -> rig.coordinator.onArmedChanged(false) },
        )
        for ((name, end) in ends) {
            val rig = Rig(withStream = true)
            rig.attach()
            rig.coordinator.onArmedChanged(true)
            rig.coordinator.onBegin()
            rig.settle()
            assertEquals("app: $name: the run must start once", 1, rig.stream.starts)
            end(rig)
            rig.settle()
            assertEquals("app: $name must stop the listening run exactly once", 1, rig.stream.stops)
        }
    }

    @Test
    fun `stopping twice through the link stops the stream once`() {
        val rig = Rig(withStream = true)
        rig.attach()
        rig.coordinator.onArmedChanged(true)
        rig.coordinator.onBegin()
        rig.settle()
        rig.link.cleared()
        rig.link.cleared()
        rig.settle()
        assertEquals("app: a second stop through the link must find nothing to stop", 1, rig.stream.stops)
        assertFalse("app: the detector must not be listening after the stop", rig.component.phrases.isListening)
    }
}
