package dev.breaker.dictation.stt.ondevice

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
 * A model with no profile cannot be unpacked and is refused before any download.
 */
internal object ExtractionProfiles {
    private const val MIB = 1_048_576L

    private val SMALL = ExtractionProfile(
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
        tokens = "tokens.txt",
        limits = ExtractionLimits(
            maxEntries = 32,
            maxEntryBytes = 300L * MIB,
            maxWrittenBytes = 160L * MIB,
            maxStreamBytes = 448L * MIB,
            maxPathLength = 128,
            maxDepth = 4,
        ),
    )

    private val TINY = ExtractionProfile(
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.onnx",
        tokens = "tokens.txt",
        limits = ExtractionLimits(
            maxEntries = 32,
            maxEntryBytes = 128L * MIB,
            maxWrittenBytes = 80L * MIB,
            maxStreamBytes = 160L * MIB,
            maxPathLength = 128,
            maxDepth = 4,
        ),
    )

    /** The profile for [modelId] ("small" or "tiny"), or null for every other id. */
    fun forModel(modelId: String): ExtractionProfile? = when (modelId) {
        "small" -> SMALL
        "tiny" -> TINY
        else -> null
    }
}
