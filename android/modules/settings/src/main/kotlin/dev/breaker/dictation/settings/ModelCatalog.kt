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
 * [ModelEntry.family], [ModelEntry.licence], [ModelEntry.hosted], [ModelEntry.kind],
 * [ModelEntry.sizeBytes] and [ModelEntry.assetId] are dropped on purpose: they are
 * release and decoding facts a download/verify step reads, not settings, and a
 * smaller surface is the conservative one.
 *
 * [label], [rating] and [offered] are the words and the offer the first run and
 * the settings section show: [label] is the short name of a model, [rating] is its
 * count of trucks from 0 to 3, and [offered] says the model is one the two screens
 * list. A model with no label carries null and is not offered.
 *
 * [notice] is the attribution the model's licence asks the app to show, or null
 * when the licence asks for none. The text is the registry's, unchanged.
 */
data class ModelInfo(
    val id: String,
    val sizeMb: Int,
    val url: String,
    val sha256: String,
    val label: String? = null,
    val rating: Int? = null,
    val offered: Boolean = false,
    val notice: String? = null,
)

/**
 * The model list, read-only, as this module reads it out of the shared registry.
 *
 * Every model the registry describes, in the registry's own order: [all]
 * projects the registry's entries one for one, and [byId] answers a lookup
 * exactly as the registry does — EXACT-CASE, no folding, null when the id is
 * not one the registry names. [offered] is the subset the two screens list, in
 * the same order. `SettingsModelIdTest` and `ModelCatalogTest` pin both halves
 * against the registry's own constants.
 *
 * No download, no verify, no cache: the list is the only thing this object
 * answers with. Fetching a model and checking its SHA-256 pin is the
 * `stt-ondevice` module's work, and it reads this same registry.
 */
object ModelCatalog {

    /** Every model the registry describes, in the registry's order. */
    fun all(): List<ModelInfo> = ModelRegistry.ALL.map(ModelCatalog::toInfo)

    /** The models the first run and the settings section offer, in the registry's order. */
    fun offered(): List<ModelInfo> = ModelRegistry.ALL.filter { it.offered }.map(ModelCatalog::toInfo)

    /** The model [id] names, or null when the registry has no such id. */
    fun byId(id: String): ModelInfo? = ModelRegistry.byId(id)?.let(ModelCatalog::toInfo)

    /** The registry's default model: the one entry flagged as the default. */
    fun default(): ModelInfo = toInfo(ModelRegistry.ALL.single { it.isDefault })

    /** The model [id] names, or the registry's default model when the registry has no such id. */
    fun byIdOrDefault(id: String): ModelInfo = byId(id) ?: default()

    private fun toInfo(entry: ModelEntry): ModelInfo = ModelInfo(
        id = entry.id,
        sizeMb = entry.sizeMb,
        url = entry.url,
        sha256 = entry.sha256,
        label = entry.label,
        rating = entry.rating,
        offered = entry.offered,
        notice = entry.notice,
    )
}
