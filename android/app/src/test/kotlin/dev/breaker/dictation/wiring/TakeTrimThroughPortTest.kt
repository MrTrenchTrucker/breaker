package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trim point travels from each send entry to the take's finish, and through the real port chain it
 * decides how much audio the engine is given. Every test runs on the test thread over the manual queues.
 */
internal class TakeTrimThroughPortTest {

    /** A take that keeps the trim each finish was given and answers a text. */
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

    /** An engine that keeps the sample count of each request it is given. */
    private class SizeRecordingEngine : SttEngine {
        val sizes: MutableList<Int> = ArrayList()

        override fun transcribe(request: SttRequest): SttResult {
            sizes.add(request.pcm.size)
            return SttResult.Success("hello world")
        }
    }

    /** Runs posted and submitted blocks until both queues are empty, with a bound that fails by name. */
    private fun settle(main: ManualMain, background: ManualBackground) {
        var passes = 0
        while (background.pending > 0 || main.pending > 0) {
            passes += 1
            check(passes <= 1_000) { "app: the trim test did not settle after 1000 passes" }
            background.runAll()
            main.drain()
        }
    }

    /** The switch the Home take shares, armed and not stored off. */
    private fun armedSwitch(): ArmedSwitch {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        val switch = ArmedSwitch(controller, MemoryOffStore())
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return switch
    }

    /** The Home adapter over the given take, with the manual queues and no-op line and state sinks. */
    private fun homeAdapter(take: TakePort, owner: TakeOwner, background: ManualBackground, main: ManualMain): HomeTakeAdapter =
        HomeTakeAdapter(
            take = take,
            owner = owner,
            settle = TakeSettle(null, FakeTakenClipboard()),
            switch = armedSwitch(),
            background = background,
            main = main,
            onLine = {},
            onState = {},
        )

    /** Holds the take at the Home, runs [send] on the Home adapter, settles, and answers the trims the take saw. */
    private fun homeSend(send: (HomeTakeAdapter) -> Unit): List<Long?> {
        val owner = TakeOwner()
        owner.begun(TakeOwnerKind.HOME)
        val take = TrimRecordingTake()
        val background = ManualBackground()
        val main = ManualMain()
        send(homeAdapter(take, owner, background, main))
        settle(main, background)
        return take.trims
    }

    /** Builds the coordinator over the manual fakes, begins a take, settles, runs [send] and answers the trims seen. */
    private fun tileSend(send: (TileCoordinator) -> Unit): List<Long?> {
        val take = TrimRecordingTake()
        val main = ManualMain()
        val background = ManualBackground()
        val coordinator = TileCoordinator(
            RecordingTile(), main, background, take, FakeModelReady(true), RecordingModelNotice(),
            RecordingOpener(), RecordingClipboard(), {}, RecordingHistory(),
        )
        coordinator.onArmedChanged(true)
        coordinator.onBegin()
        settle(main, background)
        send(coordinator)
        settle(main, background)
        return take.trims
    }

    /**
     * Two seconds of speech through the real chain (lazy port, owned port, adapter, runner); answers the
     * sample counts the engine was given for the finish with [trim].
     */
    private fun chainSamples(trim: Long?): List<Int> {
        val engine = SizeRecordingEngine()
        val rig = Rig(localEngine = engine)
        val owner = TakeOwner()
        val port: TakePort = LazyTakePort { OwnedTakePort(TakePortAdapter(rig.runner), owner, TakeOwnerKind.HOME) }
        assertEquals("app: the chain should begin a take", BeginResult.Recording, port.begin())
        rig.audio.deliver(FloatArray(32_000) { 0.2f })
        val result: FinishResult = port.finish(trim)
        assertTrue("app: the chain should answer the text, got $result", result is FinishResult.ReadyToSend)
        return engine.sizes
    }

    @Test
    fun `a home send hands its trim of 1500 milliseconds to the take finish`() {
        assertEquals(listOf<Long?>(1_500L), homeSend { it.onSend(1_500L) })
    }

    @Test
    fun `a home send hands its trim of 250 milliseconds to the take finish`() {
        assertEquals(listOf<Long?>(250L), homeSend { it.onSend(250L) })
    }

    @Test
    fun `a home send with no trim hands null to the take finish`() {
        assertEquals(listOf<Long?>(null), homeSend { it.onSend() })
    }

    @Test
    fun `a home tap on a held take still finishes with no trim`() {
        assertEquals(listOf<Long?>(null), homeSend { it.onTap() })
    }

    @Test
    fun `a tile send hands its trim of 1500 milliseconds through the finisher to the take finish`() {
        assertEquals(listOf<Long?>(1_500L), tileSend { it.onSend(1_500L) })
    }

    @Test
    fun `a tile send with no trim hands null to the take finish`() {
        assertEquals(listOf<Long?>(null), tileSend { it.onSend() })
    }

    @Test
    fun `a trim of 1500 milliseconds keeps 24000 samples of the two seconds through the chain`() {
        assertEquals(listOf(24_000), chainSamples(1_500L))
    }

    @Test
    fun `a trim of 250 milliseconds keeps 4000 samples of the two seconds through the chain`() {
        assertEquals(listOf(4_000), chainSamples(250L))
    }

    @Test
    fun `a finish with no trim keeps all 32000 samples through the chain`() {
        assertEquals(listOf(32_000), chainSamples(null))
    }

    @Test
    fun `a finish outside recording answers not listening and never stops the audio`() {
        val rig = Rig()
        val port: TakePort = LazyTakePort { OwnedTakePort(TakePortAdapter(rig.runner), TakeOwner(), TakeOwnerKind.HOME) }
        assertEquals(
            "app: a finish with no listening session should answer the runner's failure",
            FinishResult.Failed(RunnerSentences.NOT_LISTENING),
            port.finish(1_500L),
        )
        assertEquals("app: a finish with no listening session must not stop the audio", 0, rig.audio.stops)
    }
}
