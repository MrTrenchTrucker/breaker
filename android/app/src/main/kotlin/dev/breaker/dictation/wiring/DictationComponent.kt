package dev.breaker.dictation.wiring

import dev.breaker.dictation.audio.MicCapture
import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.ConnectivityProbe
import dev.breaker.dictation.core.port.Formatter
import dev.breaker.dictation.core.port.HistoryStore
import dev.breaker.dictation.core.port.IdSource
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.core.port.SttEngine
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.core.port.WavEncoder
import dev.breaker.dictation.core.usecase.DictateUseCase
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.service.ReportingMicSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job

/**
 * The dictation parts built from their ports: the capture over [micSource], the two core use cases
 * and the [runner] that drives them.
 *
 * The microphone is wrapped so the runner hears when it stops by itself. The wrapper is created
 * before the runner, so it reaches the runner through a holder that is filled right after the
 * runner is built; an end reported before then is ignored.
 *
 * It also tells the runner when the microphone service ends on its own, so a capture never goes on
 * without the service. Whoever owns the component closes it, or binds it to a scope with [bindTo].
 * Closing drops any dictation under way, switches the microphone service off and stops that listening.
 */
class DictationComponent(
    settings: SettingsStore,
    history: HistoryStore,
    clock: Clock,
    ids: IdSource,
    probe: ConnectivityProbe,
    localEngine: SttEngine,
    serverEngine: SttEngine,
    localFormatter: Formatter,
    serverFormatter: Formatter,
    wavEncoder: WavEncoder,
    committer: TextCommitter,
    micSource: MicSource,
    private val controller: DictationServiceController,
) : AutoCloseable {

    private val runnerHolder = AtomicReference<DictationRunner?>(null)
    private val closed = AtomicBoolean(false)

    /** The runner that drives the dictation. */
    val runner: DictationRunner

    init {
        val reporting = ReportingMicSource(micSource) { runnerHolder.get()?.onCaptureEnded() }
        val dictate = DictateUseCase(
            settings = settings,
            probe = probe,
            localEngine = localEngine,
            serverEngine = serverEngine,
            serverFormatter = serverFormatter,
            wavEncoder = wavEncoder,
            clock = clock,
            ids = ids,
            localFormatter = localFormatter,
        )
        val built = DictationRunner(dictate, SendUseCase(committer, history), MicCapture(reporting), controller, reporting)
        runnerHolder.set(built)
        runner = built
        controller.setEndedListener { runnerHolder.get()?.onServiceEnded() }
    }

    /**
     * Drops any dictation under way and switches the microphone service off. Safe to call again: only
     * the first call does anything.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            runner.cancel()
        } catch (e: Exception) {
            // The service is switched off below whatever happened to the dictation.
        }
        controller.disarm(DisarmReason.OWNER_CLOSED)
        controller.setEndedListener(null)
    }

    /**
     * Closes this component when [scope] ends. A scope without a job never ends, so it is refused
     * by name. The handle removes the binding.
     */
    fun bindTo(scope: CoroutineScope): DisposableHandle {
        val job = scope.coroutineContext[Job]
        require(job != null) { "bindTo needs a scope that has a job, or it can never end" }
        return job.invokeOnCompletion { close() }
    }
}
