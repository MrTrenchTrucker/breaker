package dev.breaker.dictation.stt.ondevice

import java.io.File

/** The registry id of the keyword spotting model. Nothing downloads it yet; it is only a name here. */
const val KEYWORD_MODEL_ID: String = "kws-gigaspeech-3m"

/**
 * A verified keyword spotting model on disk.
 *
 * [modelId] is the registry id, [directory] holds the four unpacked files, and
 * [digest] is the SHA-256 hex string that pins the verified archive. Holding one
 * opens no engine; no engine for it is built yet.
 */
class KeywordModel(val modelId: String, val directory: File, val digest: String)

/** The outcome of [KeywordModelLoader.load]. */
sealed class KeywordLoad {
    /** The model passed every check and its files are in place. */
    data class Ready(val model: KeywordModel) : KeywordLoad()

    /** The load was refused; [refusal] says why and [detail] is a fixed sentence for the user. */
    data class Refused(val refusal: ModelLoader.Refusal, val detail: String) : KeywordLoad()
}
