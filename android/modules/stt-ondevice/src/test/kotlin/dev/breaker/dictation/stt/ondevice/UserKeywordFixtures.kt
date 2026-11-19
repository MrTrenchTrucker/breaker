package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.PhraseModel
import dev.breaker.dictation.core.model.TrainedPhraseModel
import java.io.File
import java.io.IOException

/** Helpers for the user keyword store tests: keyword-shaped archives, a store over a folder, folder snapshots and scripted failures. */
internal object UserKeywordFixtures {

    /** The four file names of the keyword profile, in role order: encoder, decoder, joiner, tokens. */
    val FILES: List<String> = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt",
    )

    private const val BODY_BYTES = 64

    /** The limits of the keyword profile, read from the module's own profile table. */
    fun keywordLimits(): ExtractionLimits = checkNotNull(ExtractionProfiles.forModel(KEYWORD_MODEL_ID)).limits

    /** The name of the token table inside the archive. */
    const val TOKENS_NAME = "tokens.txt"

    /** The body of one file of the archive made with [label]; a different label gives different bytes. */
    fun contentOf(label: String, name: String): ByteArray = TarFixtures.contentOf("$label$name", BODY_BYTES)

    /** The token table that holds every fixed-phrase token, as bytes. */
    fun tokensBytes(): ByteArray = KeywordsTestTokens.FILE_TEXT.toByteArray(Charsets.UTF_8)

    /** A keyword-shaped archive: the top folder, the four files of [label] (a good token table), then [extra]; compressed as the shipped archive is. */
    fun archive(label: String, extra: List<TarEntrySpec> = emptyList()): ByteArray =
        archiveWithTokens(label, tokensBytes(), extra)

    /** A keyword-shaped archive whose token table is [tokens]; the other three files are those of [label]. */
    fun archiveWithTokens(label: String, tokens: ByteArray, extra: List<TarEntrySpec> = emptyList()): ByteArray =
        bz(listOf(TarFixtures.dir("top/")) + FILES.map { name ->
            TarFixtures.file("top/$name", if (name == TOKENS_NAME) tokens else contentOf(label, name))
        } + extra)

    /** A trained model of [kind] whose digest is the independent SHA-256 of [bytes], unless [digest] is given. */
    fun trained(kind: PhraseKind, bytes: ByteArray, digest: String = Fixtures.independentSha256(bytes)): TrainedPhraseModel =
        TrainedPhraseModel(kind, PhraseModel(kind, bytes, digest), 10)

    /** A store over [root] with the real digest function and, unless the test scripts them, the real files and extractor. */
    fun store(
        root: File,
        sizeCap: Long = keywordLimits().maxStreamBytes,
        files: UserStoreFiles = RealUserStoreFiles,
        stage: (UserStoreStage) -> Unit = {},
        extractor: ModelExtractor = ModelExtractor(),
        digestOf: (File) -> String? = ModelIntegrity::sha256,
        phrases: List<KeywordLine> = FixedPhrases.all(),
    ): UserKeywordStore = UserKeywordStore(root, extractor, digestOf, sizeCap, files, stage, phrases)

    /**
     * The bytes of every file under [dir], keyed by the path relative to [dir]; empty when [dir] is not a folder.
     * The bytes are a List of Byte, because two ByteArrays with equal content are not equal under assertEquals.
     */
    fun snapshot(dir: File): Map<String, List<Byte>> {
        if (!dir.isDirectory) return emptyMap()
        return dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }
    }

    /** A stage hook that throws an IOException when the store reaches [stage]. */
    fun failAt(stage: UserStoreStage): (UserStoreStage) -> Unit = { reached ->
        if (reached == stage) throw IOException("injected failure at ${reached.name}")
    }

    /** Writes [names] into [dir] with the given [text], creating the folder. */
    fun fill(dir: File, names: List<String>, text: String) {
        dir.mkdirs()
        for (name in names) File(dir, name).writeText(text)
    }

    /** The real files, except that deleting a folder whose name is in [refuse] reports failure. */
    class FailingDeletes(private val inner: UserStoreFiles, private val refuse: Set<String>) : UserStoreFiles {
        override fun writeBytes(file: File, bytes: ByteArray) = inner.writeBytes(file, bytes)

        override fun writeText(file: File, text: String) = inner.writeText(file, text)

        override fun move(from: File, to: File): Boolean = inner.move(from, to)

        override fun deleteTree(file: File): Boolean = if (file.name in refuse) false else inner.deleteTree(file)
    }

    /** The real files, except that the put-back (a move from the aside folder to the installed folder) reports failure. */
    class FailingPutBack(private val inner: UserStoreFiles) : UserStoreFiles {
        override fun writeBytes(file: File, bytes: ByteArray) = inner.writeBytes(file, bytes)

        override fun writeText(file: File, text: String) = inner.writeText(file, text)

        override fun move(from: File, to: File): Boolean =
            if (from.name == UserKeywordStore.ASIDE && to.name == UserKeywordStore.CURRENT) false else inner.move(from, to)

        override fun deleteTree(file: File): Boolean = inner.deleteTree(file)
    }
}
