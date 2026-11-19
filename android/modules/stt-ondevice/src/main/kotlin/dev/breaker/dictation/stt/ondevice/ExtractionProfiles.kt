package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelRegistry

/**
 * The bounds one unpack works within.
 *
 * Every figure is above what the real archive measures, and each byte figure
 * is below twice that, so a damaged or hostile archive is stopped early and a
 * real one never is.
 *
 * @property maxEntries most entries the archive may have (pseudo headers are not counted).
 * @property maxEntryBytes largest size any one entry may declare, written or skipped.
 * @property maxWrittenBytes most bytes that may be written to disk in all.
 * @property maxStreamBytes most bytes the decompressor may produce in all, skipped entries included.
 * @property maxPathLength longest entry name, in characters, as it appears in the header.
 * @property maxDepth most path segments in a name, the top directory included.
 */
internal data class ExtractionLimits(
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxWrittenBytes: Long,
    val maxStreamBytes: Long,
    val maxPathLength: Int,
    val maxDepth: Int,
)

/**
 * The exact files an unpack writes for one model.
 *
 * The four names are the only names ever written, and they are written under
 * these names whatever the matching archive entry is called.
 *
 * @property encoder the encoder network file.
 * @property decoder the decoder network file.
 * @property joiner the joiner network file.
 * @property tokens the token table file.
 * @property limits the bounds for this model's archive.
 */
internal data class ExtractionProfile(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val limits: ExtractionLimits,
) {
    /** The four file names, in role order: encoder, decoder, joiner, tokens. */
    val files: List<String>
        get() = listOf(encoder, decoder, joiner, tokens)
}

/**
 * The unpack profile of each model the module can install.
 *
 * A speech model's profile is built from its registry entry (its four file names and its limits); no model id is
 * written here. The keyword model keeps its own profile below. A model with no profile cannot be unpacked and is
 * refused before any download. Compiled by the project's CI build and exercised by the
 * module's unit tests; its behaviour on a device is not measured.
 */
internal object ExtractionProfiles {
    private const val MIB = 1_048_576L

    /** The longest entry name allowed, in characters; the registry holds no path length, so one figure serves every speech model. */
    private const val MAX_PATH_LENGTH = 128

    /**
     * The keyword spotting model. Only the int8 encoder, the plain decoder, the int8
     * joiner and the token table are written: the plain encoder, the sentencepiece
     * model and the sample recordings in the archive are never extracted.
     *
     * The measured archive is 17,626,723 bytes; its largest entry is the plain encoder
     * at 12,174,219 bytes, the four written files total 6,038,734 bytes, and the
     * entries that are listed add up to 20,126,636 bytes over 17 members. The limits
     * are 16 MiB per entry, 8 MiB written
     * and 32 MiB of stream, each under twice the measured figure it guards.
     */
    private val KEYWORD = ExtractionProfile(
        encoder = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        decoder = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
        joiner = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        tokens = "tokens.txt",
        limits = ExtractionLimits(
            maxEntries = 32,
            maxEntryBytes = 16L * MIB,
            maxWrittenBytes = 8L * MIB,
            maxStreamBytes = 32L * MIB,
            maxPathLength = MAX_PATH_LENGTH,
            maxDepth = 4,
        ),
    )

    /**
     * The profile for [entry]: the keyword profile for the keyword model, the profile its own file names and
     * limits give for a supported speech entry, or null for an entry that is not supported or lacks either.
     */
    fun forEntry(entry: ModelEntry): ExtractionProfile? {
        if (entry.id == KEYWORD_MODEL_ID) return KEYWORD
        val files = entry.files ?: return null
        val limits = entry.limits ?: return null
        if (!entry.isSupported) return null
        return ExtractionProfile(
            encoder = files.encoder,
            decoder = files.decoder,
            joiner = files.joiner,
            tokens = files.tokens,
            limits = ExtractionLimits(
                maxEntries = limits.maxEntries,
                maxEntryBytes = limits.maxEntryBytes,
                maxWrittenBytes = limits.maxWrittenBytes,
                maxStreamBytes = limits.maxStreamBytes,
                maxPathLength = MAX_PATH_LENGTH,
                maxDepth = limits.maxDepth,
            ),
        )
    }

    /**
     * The profile for [modelId]: [KEYWORD_MODEL_ID] gets the keyword profile; every other id is looked up in
     * [lookup] (the real registry by default) and gets the profile of its entry, or null when it has none.
     */
    fun forModel(modelId: String, lookup: (String) -> ModelEntry? = ModelRegistry::byId): ExtractionProfile? =
        if (modelId == KEYWORD_MODEL_ID) KEYWORD else lookup(modelId)?.let(::forEntry)
}
