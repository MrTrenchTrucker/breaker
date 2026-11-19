package dev.breaker.dictation.stt.ondevice

import dev.breaker.dictation.core.model.TrainedPhraseModel
import java.io.File

/**
 * The user keyword model of this phone: a keyword model made from a verified server archive, kept in an app-private
 * folder of its own under [root]. It is never the shipped store and never an entry of the model registry.
 *
 * [install] checks the archive first, so a refused install writes nothing, and swaps the new model in only when every
 * step has worked; a refused or failed install leaves the previous model as it was. [remove] deletes the model and any
 * leftover of an install. The store does not log, does not read a clock and does not throw for a bad archive.
 *
 * [phrases] is the list the token table must cover. The equal-digest no-op and the install both check against it, so a
 * store that asks for a phrase the installed table lacks never reports an unchanged install. It is the fixed phrases
 * unless a test passes another list.
 *
 * Layout under [root], with fixed names: the installed model in [CURRENT] (see [directory]), and the work folders
 * [NEXT], [ASIDE], [WORK] and the staged archive [INCOMING]. A kill can leave the work folders behind with no installed
 * model; [directory] then does not exist, the keyword model loader answers absent, and a later swap or remove sweeps them.
 */
class UserKeywordStore internal constructor(
    private val root: File,
    private val extractor: ModelExtractor,
    private val digestOf: (File) -> String?,
    private val sizeCap: Long,
    private val files: UserStoreFiles,
    private val hook: (UserStoreStage) -> Unit,
    private val phrases: List<KeywordLine> = FixedPhrases.all(),
) {

    /** The store over [root], with the real extractor, digest function and file operations and the keyword stream limit. */
    constructor(root: File) : this(
        root,
        ModelExtractor(),
        ModelIntegrity::sha256,
        checkNotNull(ExtractionProfiles.forModel(KEYWORD_MODEL_ID)).limits.maxStreamBytes,
        RealUserStoreFiles,
        {},
    )

    companion object {
        /** The installed model: the four keyword files and the stamp. */
        internal const val CURRENT = "current"

        /** The folder the new model is extracted and stamped in, before the swap. */
        internal const val NEXT = "next"

        /** The folder the previous model is moved to during a swap. */
        internal const val ASIDE = "aside"

        /** The extractor's work folder. */
        internal const val WORK = "work"

        /** The staged archive bytes. */
        internal const val INCOMING = "incoming.archive"
    }

    /** The folder of the installed model. It may not exist. */
    fun directory(): File = File(root, CURRENT)

    /** The archive digest the installed model was made from, or null when there is no readable stamp. */
    fun installedDigest(): String? = UnpackedStamp.read(directory())?.archiveDigest

    /**
     * Installs [trained] as the user keyword model. The size is checked against the cap, then the bytes' SHA-256 against
     * the digest the caller verified, both before anything is written. When the same digest is installed, its stamp
     * still checks and its token table covers [phrases], nothing is written and the result is unchanged; that no-op does not sweep a leftover aside, a later swap or remove does. Otherwise the swap runs.
     * A refusal leaves the previous model untouched. A failed move into place puts it back; if that put-back also fails,
     * the previous model is left in the aside folder, nothing is installed, and the next install removes it.
     */
    fun install(trained: TrainedPhraseModel): UserInstall {
        val bytes = trained.model.bytes
        val digest = trained.model.sha256.lowercase()
        if (bytes.size.toLong() > sizeCap) return UserInstall.Refused(UserKeywordReason.TOO_LARGE)
        if (!ModelIntegrity.sha256(bytes).equals(digest, ignoreCase = true)) {
            return UserInstall.Refused(UserKeywordReason.DIGEST_MISMATCH)
        }
        if (isInstalledWith(digest)) return UserInstall.Installed(digest, true)
        return UserKeywordSwap(root, extractor, digestOf, files, hook, phrases).run(bytes, digest)
    }

    /**
     * Deletes the installed model and every leftover of an install (the aside, work, next and incoming folders), whether or
     * not a model is installed. The result is true when all of them are gone afterwards. With a model installed it is false
     * only when a delete fails. With no model and only leftovers, the leftovers are removed and the result is true. With
     * nothing at all, the result is true.
     */
    fun remove(): Boolean = listOf(CURRENT, ASIDE, NEXT, WORK, INCOMING).map { files.deleteTree(File(root, it)) }.all { it }

    /**
     * True when the installed folder checks ready against [phrases] and was made from [digest]. The verdict reads the
     * stamp and then the token table, so a token table that lacks a phrase of [phrases] is not a no-op.
     */
    private fun isInstalledWith(digest: String): Boolean {
        val verdict = UserKeywordCheck.verdict(directory(), phrases)
        return verdict is UserKeywordVerdict.Ready && verdict.digest.equals(digest, ignoreCase = true)
    }
}
