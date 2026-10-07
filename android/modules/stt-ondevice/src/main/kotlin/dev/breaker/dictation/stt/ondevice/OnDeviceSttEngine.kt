package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.AudioFormat
import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult
import dev.breaker.dictation.core.port.SttEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/** The one-decode-at-a-time dispatcher the engine runs on: a single-slot view over [base]. */
internal fun singleSlot(base: CoroutineDispatcher): CoroutineDispatcher = base.limitedParallelism(1)

/**
 * On-device speech-to-text engine backed by a local Sherpa recognizer.
 *
 * Threading model:
 *
 * The decode is a BLOCKING native call, so it runs on IO: a blocked Default
 * worker would starve unrelated work. The engine runs one decode at a time on
 * a single-slot coroutine dispatcher (Dispatchers.IO.limitedParallelism(1));
 * successive decodes may run on different IO threads.
 *
 * [transcribe] is the port's own blocking method, so it bridges with
 * [runBlocking] onto the inference dispatcher. The bridge is needed because
 * the port's signature blocks and is not a suspend function.
 *
 * [transcribeAsync] runs the decode on the inference dispatcher DIRECTLY
 * and MUST NOT call [transcribe]. If it did, the inner [runBlocking] would
 * wait for the single slot its own caller holds, and the engine would hang
 * forever, silently. This is structural, not discipline.
 *
 * Reentrancy guard: a [ThreadLocal] marks a thread already inside the
 * serialized decode. If [transcribe] is called on a thread whose marker is
 * already true (i.e. from inside a decode on this dispatcher), REFUSE AT ONCE
 * with [ErrorMapping.reentrantDecode]; never wait, never block.
 *
 * A coroutine primitive cannot do this: the port's blocking entry point is
 * non-suspend, the marker must be visible to non-suspend code, and the
 * question being asked is "is this thread already inside the one slot",
 * which is a thread-scoped fact. That is the reason a ThreadLocal is used here.
 *
 * Only [closed] is @Volatile: [close] is non-suspend and callable from any
 * thread, so the flag needs visibility without a lock. The remaining state
 * (lastModelId, loadedModelId, counters) is read and written only INSIDE the
 * serialized section, so no lock or volatile is needed. [diagnostics]
 * marshals onto the dispatcher with the same reentrancy guard. [close] only
 * sets the closed flag: it does not interrupt a running decode or cancel
 * queued calls; each queued call returns the engine-closed failure when its
 * turn comes.
 *
 * The ThreadLocal is RESET (set back to false) in a [finally] after each
 * decode, or a pooled IO thread would stay marked and refuse later
 * legitimate calls.
 */
class OnDeviceSttEngine(
    private val loader: ModelLoaderPort,
    private val inferenceDispatcher: CoroutineDispatcher = singleSlot(Dispatchers.IO),
) : SttEngine, AutoCloseable {

    /** Snapshot of engine state for diagnostics. */
    data class Diagnostics(
        val lastModelId: String?,
        val loadedModelId: String?,
        val verificationCount: Int,
        val decodeCount: Int,
    )

    /** Outcome classification for internal use. */
    enum class Outcome {
        TRANSCRIBED, NO_MODEL, MODEL_REFUSED, AUDIO_REJECTED,
        ENGINE_FAILED, ENGINE_CLOSED, REFUSED_REENTRANT
    }

    // A ThreadLocal because "is this thread already inside the slot" is a thread-scoped fact that non-suspend code must be able to read.
    private val decodingHere = ThreadLocal.withInitial { false }

    // close() is non-suspend and callable from any thread, so the flag needs
    // visibility without a lock.
    @Volatile
    private var closed = false

    private var lastModelId: String? = null

    private var loadedModelId: String? = null

    private var verificationCount = 0

    private var decodeCount = 0

    private val scope = CoroutineScope(inferenceDispatcher + SupervisorJob())

    /**
     * Blocking entry point required by [SttEngine]. Bridges onto the
     * inference dispatcher with [runBlocking] because the port's signature
     * is non-suspend.
     */
    override fun transcribe(request: SttRequest): SttResult {
        if (closed) return ErrorMapping.engineClosed()

        // Audio shape is checked FIRST, before any model work.
        if (request.sampleRateHz != AudioFormat.SAMPLE_RATE_HZ) {
            return ErrorMapping.audioWrongRate(request.sampleRateHz)
        }

        // Reentrancy guard: if this thread is already inside the decode,
        // refuse at once; never wait, never block.
        if (decodingHere.get()) {
            return ErrorMapping.reentrantDecode()
        }

        return try {
            runBlocking(inferenceDispatcher) {
                if (closed) return@runBlocking ErrorMapping.engineClosed()
                if (decodingHere.get()) return@runBlocking ErrorMapping.reentrantDecode()

                decodingHere.set(true)
                try {
                    performTranscribe(request)
                } finally {
                    decodingHere.set(false)
                }
            }
        } catch (_: CancellationException) {
            ErrorMapping.decodeFailed()
        }
    }

    /**
     * Async entry point. Runs the decode on the inference dispatcher
     * DIRECTLY. It must NOT call [transcribe], or the inner [runBlocking]
     * would deadlock on the single slot.
     */
    fun transcribeAsync(request: SttRequest): Deferred<SttResult> {
        return scope.async {
            if (closed) return@async ErrorMapping.engineClosed()

            // Audio shape is checked FIRST, before any model work.
            if (request.sampleRateHz != AudioFormat.SAMPLE_RATE_HZ) {
                return@async ErrorMapping.audioWrongRate(request.sampleRateHz)
            }

            if (decodingHere.get()) {
                return@async ErrorMapping.reentrantDecode()
            }

            decodingHere.set(true)
            try {
                performTranscribe(request)
            } finally {
                decodingHere.set(false)
            }
        }
    }

    /**
     * Loads the model, releases the recognizer it created, and holds nothing.
     * It lets a settings screen report a bad model early; it does not shorten
     * the next transcribe, which loads and verifies again. Returns the
     * failure, or null when the model loaded.
     */
    fun preload(modelId: String): SttResult.Failure? {
        if (closed) return ErrorMapping.engineClosed()
        if (decodingHere.get()) return ErrorMapping.reentrantDecode()

        return try {
            runBlocking(inferenceDispatcher) {
                if (closed) return@runBlocking ErrorMapping.engineClosed()
                if (decodingHere.get()) return@runBlocking ErrorMapping.reentrantDecode()

                decodingHere.set(true)
                try {
                    lastModelId = modelId
                    val result = try {
                        loader.load(modelId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@runBlocking ErrorMapping.modelUnreadable()
                    }
                    when (result) {
                        is ModelLoader.LoadResult.Ready -> {
                            try {
                                loadedModelId = modelId
                                verificationCount++
                                null
                            } finally {
                                result.recognizer.release()
                            }
                        }
                        is ModelLoader.LoadResult.Refused -> {
                            mapRefusal(modelId, result)
                        }
                    }
                } finally {
                    decodingHere.set(false)
                }
            }
        } catch (_: CancellationException) {
            ErrorMapping.decodeFailed()
        }
    }

    /**
     * Returns a snapshot of engine state. Marshals onto the dispatcher
     * with the same reentrancy guard.
     */
    fun diagnostics(): Diagnostics {
        if (closed) return Diagnostics(lastModelId, loadedModelId, verificationCount, decodeCount)
        if (decodingHere.get()) return Diagnostics(lastModelId, loadedModelId, verificationCount, decodeCount)

        return runBlocking(inferenceDispatcher) {
            if (decodingHere.get()) return@runBlocking Diagnostics(lastModelId, loadedModelId, verificationCount, decodeCount)

            decodingHere.set(true)
            try {
                Diagnostics(lastModelId, loadedModelId, verificationCount, decodeCount)
            } finally {
                decodingHere.set(false)
            }
        }
    }

    /**
     * Marks the engine closed. Further transcribes return
     * [ErrorMapping.engineClosed].
     */
    override fun close() {
        closed = true
    }

    // Private helpers

    private fun performTranscribe(request: SttRequest): SttResult {
        lastModelId = request.model

        val loadResult = try {
            loader.load(request.model)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ErrorMapping.modelUnreadable()
        }
        when (loadResult) {
            is ModelLoader.LoadResult.Refused -> return mapRefusal(request.model, loadResult)
            is ModelLoader.LoadResult.Ready -> {
                loadedModelId = request.model
                verificationCount++

                val recognizer = loadResult.recognizer
                return try {
                    decodeCount++
                    val transcript = recognizer.decode(request.pcm, request.sampleRateHz)
                    SttResult.Success(
                        text = transcript.text,
                        segments = transcript.segments,
                        language = transcript.language.ifBlank { request.language },
                    )
                } catch (_: SherpaTranscriptionException) {
                    ErrorMapping.decodeFailed()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    ErrorMapping.decodeFailed()
                } catch (_: StackOverflowError) {
                    // Known upstream decoder bug reachable through a tampered model.
                    ErrorMapping.decodeFailed()
                } catch (_: OutOfMemoryError) {
                    ErrorMapping.decodeFailed()
                } finally {
                    recognizer.release()
                }
            }
        }
    }

    private fun mapRefusal(modelId: String, result: ModelLoader.LoadResult.Refused): SttResult.Failure {
        return when (result.refusal) {
            ModelLoader.Refusal.UNKNOWN_MODEL -> ErrorMapping.unknownModel(modelId)
            ModelLoader.Refusal.WRONG_FAMILY -> ErrorMapping.wrongFamily(modelId, result.family?.name ?: "unknown")
            ModelLoader.Refusal.NOT_INSTALLED -> ErrorMapping.noModelInstalled(modelId)
            ModelLoader.Refusal.CHECKSUMS_UNREADABLE -> ErrorMapping.checksumsUnreadable(modelId)
            ModelLoader.Refusal.VERIFICATION_REFUSED -> ErrorMapping.tampered(modelId, result.leftOnDisk)
            ModelLoader.Refusal.ENGINE_UNUSABLE -> ErrorMapping.decodeFailed()
        }
    }
}
