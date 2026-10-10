package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationResult
import dev.breaker.dictation.core.model.DictationSession
import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import dev.breaker.dictation.core.usecase.DictateUseCase
import dev.breaker.dictation.core.usecase.SendUseCase
import dev.breaker.dictation.core.usecase.SendResult
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.DisarmReason
import dev.breaker.dictation.audio.MicSourceException
import dev.breaker.dictation.service.ReportingMicSource
import dev.breaker.dictation.service.StartResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The plain sentences the runner answers with. */
object RunnerSentences {
    const val BUSY: String = "Breaker is already listening."
    const val COULD_NOT_RECORD: String = "Breaker could not start recording."
    const val NOTHING_HEARD: String = "Nothing was heard."
    const val COULD_NOT_FINISH: String = "Breaker could not finish listening."
    const val NOT_LISTENING: String = "Breaker is not listening."
}

/** The answer to a request to start listening. */
sealed class BeginResult {
    /** Capture is running. */
    object Recording : BeginResult()

    /** Nothing was started; [sentence] says why in plain words. */
    data class Refused(val sentence: String) : BeginResult()

    /** Starting the capture failed; the runner is ready for another try. */
    data class Failed(val sentence: String) : BeginResult()

    /** The microphone was taken by another app or call; the tile shows the busy face. */
    object Taken : BeginResult()
}

/** The answer to a request to stop listening and transcribe. */
sealed class FinishResult {
    /** The text is ready; [send] puts it where the user is typing. */
    data class ReadyToSend(val transcription: Transcription) : FinishResult() {
        /** Prints the id only, never the text. */
        override fun toString(): String = "ReadyToSend(transcriptionId=${transcription.id})"
    }

    /** There is no text; [sentence] says why in plain words. */
    data class Failed(val sentence: String) : FinishResult()
}

/**
 * Drives one dictation at a time over the core use cases: begin listening, finish and transcribe,
 * send, or cancel.
 *
 * The runner stops the CAPTURE, never the microphone service, except in [disarm], which is the
 * user's word to switch dictation off. The service stays armed between dictations.
 *
 * The session lives in an [AtomicReference] because two threads reach it: the caller's thread and
 * the capture thread, which calls [onCaptureEnded] when the microphone stops by itself. Methods
 * return values and do not throw for adapter failures; a call that breaks the core state rules
 * (a wiring bug) fails the way core does.
 *
 * [capture] is the reporting wrapper around the microphone, when there is one. The runner tells
 * it when the app itself asks for the end, so only an end the app did not ask for is reported.
 */
class DictationRunner(
    private val dictate: DictateUseCase,
    private val send: SendUseCase,
    private val audio: AudioSource,
    private val controller: DictationServiceController,
    private val capture: ReportingMicSource?,
) {
    private val session = AtomicReference(DictationSession())
    private val lastTranscription = AtomicReference<Transcription?>(null)

    /** True when a capture ended by itself and the audio source has not been stopped since. */
    private val stopOwed = AtomicBoolean(false)

    /** Where the current dictation is. */
    val sessionState: DictationState
        get() = session.get().state

    /**
     * Starts listening.
     *
     * Refused while a dictation is under way. When the service is not armed this tries to switch it
     * on once; if that fails nothing else happens and the answer carries the sentence to show.
     */
    fun begin(): BeginResult {
        val idle = session.get()
        if (idle.state != DictationState.IDLE) return BeginResult.Refused(RunnerSentences.BUSY)
        if (!controller.isArmed) {
            val started = controller.coldStart()
            if (started is StartResult.NotStarted) return BeginResult.Refused(started.sentence)
        }
        val recording = idle.arm().startRecording()
        if (!session.compareAndSet(idle, recording)) return BeginResult.Refused(RunnerSentences.BUSY)
        payOwedStop()
        capture?.rearm()
        try {
            dictate.startCapture(recording, audio)
        } catch (e: Exception) {
            stopQuietly()
            session.set(DictationSession())
            if ((e as? MicSourceException)?.reason == MicSourceException.Reason.MICROPHONE_TAKEN) {
                return BeginResult.Taken
            }
            return BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD)
        }
        return BeginResult.Recording
    }

    /**
     * Stops listening and turns the audio into text. [trimBeforeMs] is where a spoken send word
     * began, when there is one. Without a listening session this answers [FinishResult.Failed]
     * and touches nothing. A capture that cannot be stopped also answers [FinishResult.Failed], and
     * the dictation is dropped, so the next [begin] starts clean. When the use case gives up before it
     * reached the audio stop, the audio is stopped here, so the microphone is never left running.
     */
    fun finish(trimBeforeMs: Long? = null): FinishResult {
        val current = session.get()
        if (current.state != DictationState.RECORDING) return FinishResult.Failed(RunnerSentences.NOT_LISTENING)
        capture?.markStopRequested()
        val stopTracker = StopTrackingAudio(audio)
        val result = try {
            dictate.stopCapture(current, stopTracker, trimBeforeMs)
        } catch (e: Exception) {
            // Nothing was transcribed, so the dictation is over. A use case that gave up before it
            // reached the audio stop left the microphone running, so it is stopped here, once.
            if (!stopTracker.reached) stopQuietly()
            session.set(DictationSession())
            lastTranscription.set(null)
            stopOwed.set(false)
            return FinishResult.Failed(RunnerSentences.COULD_NOT_FINISH)
        }
        return when (result) {
            is DictationResult.Success -> {
                session.set(result.session)
                lastTranscription.set(result.transcription)
                FinishResult.ReadyToSend(result.transcription)
            }
            is DictationResult.Failure -> {
                session.set(result.session.cancel())
                lastTranscription.set(null)
                FinishResult.Failed(result.detail ?: RunnerSentences.NOTHING_HEARD)
            }
        }
    }

    /**
     * Puts the text from [finish] where the user is typing and saves it. Null when there is
     * nothing to send. The dictation is over afterwards, and the service is left as it is.
     */
    fun send(): SendResult? {
        val current = session.get()
        val transcription = lastTranscription.get() ?: return null
        if (current.state != DictationState.SENDING) return null
        val result = send.send(current, transcription)
        val next = result.session
        session.set(if (next.state == DictationState.ERROR) next.cancel() else next)
        lastTranscription.set(null)
        return result
    }

    /** Drops the dictation: stops the capture, throws the audio and the text away. The service stays armed. */
    fun cancel() {
        capture?.markStopRequested()
        val current = session.get()
        if (current.state != DictationState.IDLE) {
            try {
                dictate.cancel(current, audio)
            } catch (e: Exception) {
                // The dictation is dropped below either way.
            }
        } else {
            payOwedStop()
        }
        session.set(DictationSession())
        lastTranscription.set(null)
    }

    /**
     * The microphone stopped by itself. While listening, the session goes back to idle (so a second
     * report or a send cannot act on a dead capture) and the audio stop is recorded as owed; the
     * service stays armed. At any other time this does nothing.
     *
     * This runs on the capture thread, so it must not stop the audio: stopping waits for the capture
     * thread to finish, and a thread cannot wait for itself. The owed stop is paid later on the
     * caller's thread, by the next [begin], [cancel] or [disarm].
     */
    fun onCaptureEnded() {
        val current = session.get()
        if (current.state != DictationState.RECORDING) return
        // Recorded before the session moves, so a begin that sees the idle session also sees the debt.
        stopOwed.set(true)
        if (!session.compareAndSet(current, current.cancel())) stopOwed.set(false)
    }

    /**
     * Resets a take cut by the microphone back to idle without stopping the audio again: it moves the
     * session from SENDING to IDLE and drops the pending transcription (a [send] now answers null). It
     * does not touch the capture wrapper or stop the audio source, so a taken take closes its device at
     * most once. Exposed on the port for the coordinator's settle.
     */
    fun resetAfterTaken() {
        val current = session.get()
        session.set(current.cancel())
        lastTranscription.set(null)
    }

    /**
     * The microphone service ended on its own (the platform refused it, the user switched it off from
     * its notification, or it was killed). A capture must not go on without the service, so the
     * dictation is dropped exactly as [cancel] does. The controller is not touched: it already knows
     * the service is gone.
     */
    fun onServiceEnded() {
        cancel()
    }

    /** The user's word to switch dictation off: drops the dictation, then stops the service. */
    fun disarm() {
        cancel()
        controller.disarm(DisarmReason.USER_WORD)
    }

    /** Stops the audio source if a capture ended by itself since the last stop. Never throws. */
    private fun payOwedStop() {
        if (!stopOwed.getAndSet(false)) return
        capture?.markStopRequested()
        stopQuietly()
    }

    private fun stopQuietly() {
        try {
            audio.stop()
        } catch (e: Exception) {
            // The capture is being dropped; a stop that fails has nothing more to give.
        }
    }
}

/** Passes every call to [inner] and notes that [stop] was called, even when that stop threw. */
private class StopTrackingAudio(private val inner: AudioSource) : AudioSource {
    private val stopCalled = AtomicBoolean(false)

    /** True once [stop] has been called. */
    val reached: Boolean
        get() = stopCalled.get()

    override fun start(listener: AudioListener) = inner.start(listener)

    override fun stop() {
        try {
            inner.stop()
        } finally {
            stopCalled.set(true)
        }
    }
}
