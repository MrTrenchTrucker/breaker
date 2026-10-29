package dev.breaker.dictation.wiring

import dev.breaker.dictation.stt.ondevice.FedWordStream
import dev.breaker.dictation.stt.ondevice.ModelLoader
import dev.breaker.dictation.stt.ondevice.SherpaModel

/**
 * Makes the phrase word stream for [modelId] from one load. A load that gives a recognizer releases it once,
 * before the stream is made, because the stream runs on its own afterwards. A refused load gives null without
 * calling [create]. A load that throws an [Exception] gives null, and so does a stream that [create] cannot make.
 * A [VirtualMachineError] is not caught here.
 */
internal fun buildPhraseStream(
    modelId: String,
    load: (String) -> ModelLoader.LoadResult,
    create: (SherpaModel) -> FedWordStream,
): FedWordStream? {
    val loaded: ModelLoader.LoadResult = try { load(modelId) } catch (e: Exception) { return null }
    val model: SherpaModel = when (loaded) {
        is ModelLoader.LoadResult.Ready -> try { loaded.model } finally { loaded.recognizer.release() }
        is ModelLoader.LoadResult.Refused -> return null
    }
    return try { create(model) } catch (e: Exception) { null }
}
