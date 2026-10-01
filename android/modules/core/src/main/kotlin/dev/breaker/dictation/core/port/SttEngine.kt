package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.SttRequest
import dev.breaker.dictation.core.model.SttResult

/**
 * Transcribes audio to text.
 *
 * Implemented twice: by the phone for local dictation, and by the Local Server
 * client for server dictation. The domain cannot tell them apart, which is the
 * point — routing picks one, and the use case drives it the same way.
 *
 * Implementations block; the caller decides which thread to call on.
 * Implementations report problems as [SttResult.failure] rather than throwing
 * or hanging: a transcription that never returns is the failure mode users
 * notice most.
 */
interface SttEngine {
    /**
     * Transcribe [request].
     *
     * Returns [SttResult.Success] with the raw transcript, or
     * [SttResult.failure] with the reason. When the phone is in local mode and
     * holds no verified model, the failure is
     * [dev.breaker.dictation.core.model.SttError.LOCAL_MODEL_MISSING] — the
     * engine must not fall back to any other path on its own.
     */
    fun transcribe(request: SttRequest): SttResult
}
