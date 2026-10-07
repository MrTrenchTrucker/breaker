package dev.breaker.dictation.settings

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry

/**
 * One model from the shared registry, as this module's public surface shows it.
 *
 * A settings-owned projection, deliberately not the registry's [ModelEntry]:
 * the ids, sizes, URLs and SHA-256 pins are what a settings reader and a
 * later download need, and the projection is what keeps the registry's types
 * out of this module's public signatures — the Gradle edge that makes the
 * registry available stays an implementation detail for exactly that reason.
 *
 * [ModelEntry.family], [ModelEntry.licence] and [ModelEntry.hosted] are dropped on
 * purpose: they are release facts a download/verify step reads, not settings,
 * and a smaller surface is the conservative one.
 */
data class ModelInfo(
    val id: String,
    val sizeMb: Int,
    val url: String,
    val sha256: String,
)

/**
 * The model list, read-only, as this module reads it out of the shared registry.
 *
 * Every model the registry describes, in the registry's own order: [all]
 * projects the registry's entries one for one, and [byId] answers a lookup
 * exactly as the registry does — EXACT-CASE, no folding, null when the id is
 * not one the registry names. `SettingsModelIdTest` and `ModelCatalogTest`
 * pin both halves against the registry's own constants.
 *
 * No download, no verify, no cache: the list is the only thing this object
 * answers with. Fetching a model and checking its SHA-256 pin is the
 * `stt-ondevice` module's work, and it reads this same registry.
 */
object ModelCatalog {

    /** Every model the registry describes, in the registry's order. */
    fun all(): List<ModelInfo> = ModelRegistry.ALL.map(ModelCatalog::toInfo)

    /** The model [id] names, or null when the registry has no such id. */
    fun byId(id: String): ModelInfo? = ModelRegistry.byId(id)?.let(ModelCatalog::toInfo)

    private fun toInfo(entry: ModelEntry): ModelInfo = ModelInfo(
        id = entry.id,
        sizeMb = entry.sizeMb,
        url = entry.url,
        sha256 = entry.sha256,
    )
}
