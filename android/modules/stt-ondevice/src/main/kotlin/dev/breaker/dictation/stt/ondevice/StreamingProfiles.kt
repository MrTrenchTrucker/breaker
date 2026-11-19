package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelRegistry

/**
 * What the engine needs to know about one model beyond its files.
 *
 * @property language the language code the transcript is labelled with. An empty
 *   string means the model decides: the engine wrap then labels the transcript with
 *   the language of the request.
 */
internal class StreamingProfile(val language: String)

/**
 * The models this module can run through its STREAMING engine.
 *
 * A model belongs here when its registry entry is of kind STREAMING and is a supported entry (see
 * [SpeechModels.usable]); the profile is read from the entry, so no model id is written in this module.
 * An entry of kind OFFLINE, an entry that is not supported, and an id the registry does not know have no
 * streaming profile. Compiled by the project's CI build and exercised by the module's unit
 * tests; its behaviour on a device is not measured.
 */
internal object StreamingProfiles {

    /** Every model id of the real registry that has a streaming profile; derived, no id is written here. */
    val ids: Set<String> get() = ModelRegistry.ALL.filter { forModel(it.id) != null }.map { it.id }.toSet()

    /**
     * The profile for [modelId], or null when the model cannot be run by the streaming engine. [lookup] is
     * the registry; the real one is the default.
     */
    fun forModel(modelId: String, lookup: (String) -> ModelEntry? = ModelRegistry::byId): StreamingProfile? =
        SpeechModels.usable(modelId, lookup, ModelKind.STREAMING)?.let { StreamingProfile(it.language.orEmpty()) }
}
