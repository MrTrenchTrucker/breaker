package dev.breaker.dictation.stt.ondevice

/**
 * What the streaming engine needs to know about one model beyond its files.
 *
 * @property language the language code the transcript is labelled with.
 */
internal class StreamingProfile(val language: String)

/**
 * The models this module can run through the streaming engine.
 *
 * The ids here are the ids the unpack table knows (`ExtractionProfiles`): a model
 * that can be unpacked can be run, and the reverse. A test keeps the two
 * lists equal. Both listed models are English only.
 */
internal object StreamingProfiles {

    private val TABLE: Map<String, StreamingProfile> = mapOf(
        "small" to StreamingProfile("en"),
        "tiny" to StreamingProfile("en"),
    )

    /** Every model id that has a streaming profile. */
    val ids: Set<String> get() = TABLE.keys

    /** The profile for [modelId], or null when the model cannot be run by the streaming engine. */
    fun forModel(modelId: String): StreamingProfile? = TABLE[modelId]
}
