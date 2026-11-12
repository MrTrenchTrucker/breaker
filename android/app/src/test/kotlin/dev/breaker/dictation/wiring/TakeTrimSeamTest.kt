package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.usecase.SendResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The trimmed finish: the port member that carries where a spoken send word began, and every wrapper
 * on the real chain that must pass it on. Each test says in its KDoc what would break it.
 */
internal class TakeTrimSeamTest {

    /** Fails if the adapter drops the trim: the engine then hears all 3200 spoken samples, not the 1600 before the word. */
    @Test
    fun `the adapter hands the trim to the runner so the engine hears only the audio before it`() {
        val engine = PcmSeenEngine()
        val rig = Rig(localEngine = engine)
        val take: TakePort = TakePortAdapter(rig.runner)
        take.begin()
        rig.speak()
        take.finishTrimmed(100L)
        assertEquals("app: the trim must reach the runner, so the engine should hear 1600 samples", 1_600, engine.pcmSize)
    }

    /** Fails if the owner port drops the trim before the port it wraps, because the wrapped port then sees a plain finish. */
    @Test
    fun `the owner port passes the trim on to the port it wraps`() {
        val owner = TakeOwner()
        val probe = TrimProbePort(owner)
        OwnedTakePort(probe, owner, TakeOwnerKind.HOME).finishTrimmed(100L)
        assertEquals("app: the owner port must forward the trim to the wrapped port", listOf<Long?>(100L), probe.trims)
    }

    /** Fails if the owner port clears the holder after the trimmed finish: the wrapped port must see no holder. */
    @Test
    fun `the owner port clears the holder before the trimmed finish`() {
        val owner = TakeOwner()
        val probe = TrimProbePort(owner)
        owner.begun(TakeOwnerKind.HOME)
        OwnedTakePort(probe, owner, TakeOwnerKind.HOME).finishTrimmed(100L)
        assertEquals("app: the holder must be cleared before the trimmed finish", listOf<TakeOwnerKind?>(null), probe.holders)
    }

    /** Fails if the Home wrapper drops the trim, because the port it wraps then never sees it. */
    @Test
    fun `the Home wrapper passes the trim on to the port it wraps`() {
        val probe = TrimProbePort(TakeOwner())
        val wrapper: TakePort = lazyHomeWrapper { probe }
        wrapper.finishTrimmed(100L)
        assertEquals("app: the Home wrapper must forward the trim to the wrapped port", listOf<Long?>(100L), probe.trims)
    }

    /** Fails if the default member stops calling finish: a port that does not override it must still finish once. */
    @Test
    fun `a port that does not override the trimmed finish still finishes through finish`() {
        val port = PlainFinishPort()
        val answer: FinishResult = port.finishTrimmed(100L)
        assertEquals("app: the default must finish through finish once", 1, port.finishes)
        assertEquals("app: the default must answer what finish answers", FinishResult.Failed(RunnerSentences.NOT_LISTENING), answer)
    }

    /** The Home wrapper is private to its file, so the test reaches its one constructor by the class name. */
    private fun lazyHomeWrapper(supplier: () -> TakePort): TakePort {
        val type: Class<*> = Class.forName("dev.breaker.dictation.wiring.LazyTakePort")
        val constructor = type.declaredConstructors.single()
        constructor.setAccessible(true)
        return constructor.newInstance(supplier) as TakePort
    }
}

/** Records the trim and the holder each trimmed finish sees, and answers as a finish that found nothing. */
private class TrimProbePort(private val owner: TakeOwner) : TakePort {
    val trims: MutableList<Long?> = ArrayList()
    val holders: MutableList<TakeOwnerKind?> = ArrayList()

    override val sessionState: DictationState
        get() = DictationState.RECORDING

    override fun begin(): BeginResult = BeginResult.Recording

    override fun finish(): FinishResult = FinishResult.Failed(RunnerSentences.NOT_LISTENING)

    override fun finishTrimmed(trimBeforeMs: Long?): FinishResult {
        holders.add(owner.current())
        trims.add(trimBeforeMs)
        return FinishResult.Failed(RunnerSentences.NOT_LISTENING)
    }

    override fun send(): SendResult? = null

    override fun cancel() {}

    override fun resetAfterTaken() {}
}

/** A port that overrides only the plain finish, so the default member is the one that runs. */
private class PlainFinishPort : TakePort {
    var finishes: Int = 0
        private set

    override val sessionState: DictationState
        get() = DictationState.RECORDING

    override fun begin(): BeginResult = BeginResult.Recording

    override fun finish(): FinishResult {
        finishes += 1
        return FinishResult.Failed(RunnerSentences.NOT_LISTENING)
    }

    override fun send(): SendResult? = null

    override fun cancel() {}

    override fun resetAfterTaken() {}
}

/** A speech engine that keeps the size of the audio it was given and answers a fixed text. */
private class PcmSeenEngine : SttEngine {
    var pcmSize: Int = -1
        private set

    override fun transcribe(request: SttRequest): SttResult {
        pcmSize = request.pcm.size
        return SttResult.Success("hello world")
    }
}
