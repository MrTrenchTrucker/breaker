package dev.breaker.dictation.stt.ondevice

import java.io.File

/**
 * The verdict on the user keyword folder: no folder, a refusal with a typed reason, or a ready model with the
 * archive digest its stamp records.
 */
internal sealed class UserKeywordVerdict {

    /** The folder does not exist, so no user model is installed. */
    object Absent : UserKeywordVerdict()

    /** The folder holds a model that checks out; [digest] is the archive digest the stamp records (lower case hex). */
    class Ready(val digest: String) : UserKeywordVerdict()

    /** The folder holds a model that does not check out; [reason] says which check failed. */
    class Bad(val reason: UserKeywordReason) : UserKeywordVerdict()
}

/**
 * Judges the user keyword folder by its install stamp. It reads only and never changes the folder, and it never
 * hashes a file: a hash of the four files on every take would cost too much.
 *
 * The checks run in this order. A folder that does not exist is [UserKeywordVerdict.Absent]. A folder with no
 * readable stamp is [UserKeywordReason.STAMP_MISSING]. A folder where the keyword profile does not choose the
 * four files, or where the stamp does not cover the four chosen names, is [UserKeywordReason.FILES_MISSING].
 * The stamp then compares sizes: a size that differs is [UserKeywordReason.STAMP_MISMATCH]. A modified time that
 * differs is [UserKeywordReason.STAMP_MISMATCH] too, whatever the content. When the stamp matches, the token table (tokens.txt) is read once and must hold every
 * token that the phrase list renders to ([UserKeywordTokens.fits]); a table that does not fit is
 * [UserKeywordReason.TOKENS_MISSING]. The verdict is [UserKeywordVerdict.Ready] only when the stamp matches and
 * the token table fits.
 *
 * A user model whose files were copied or restored with new modified times is therefore ignored until it is
 * installed again; the typed reason lets a later settings step say so.
 */
internal object UserKeywordCheck {

    /**
     * Judges [directory] from its stamp, its sizes, its modified times and its token table. After the stamp matches
     * and the files are found, the token table must hold every token that [phrases] render to
     * ([UserKeywordTokens.fits]); otherwise the verdict is [UserKeywordReason.TOKENS_MISSING]. The cheap token read
     * covers an app update that changes the fixed phrases after the model was installed.
     */
    fun verdict(directory: File, phrases: List<KeywordLine> = FixedPhrases.all()): UserKeywordVerdict {
        if (!directory.exists()) return UserKeywordVerdict.Absent
        val stamp = UnpackedStamp.read(directory) ?: return UserKeywordVerdict.Bad(UserKeywordReason.STAMP_MISSING)
        val files = TransducerFileLocator.locate(directory)
            ?: return UserKeywordVerdict.Bad(UserKeywordReason.FILES_MISSING)
        val names = listOf(files.encoder.name, files.decoder.name, files.joiner.name, files.tokens.name)
        if (!stamp.covers(names)) return UserKeywordVerdict.Bad(UserKeywordReason.FILES_MISSING)
        return when (stamp.check(directory)) {
            is UnpackedStamp.Check.Mismatch -> UserKeywordVerdict.Bad(UserKeywordReason.STAMP_MISMATCH)
            UnpackedStamp.Check.Match ->
                if (UserKeywordTokens.fits(files.tokens, phrases)) UserKeywordVerdict.Ready(stamp.archiveDigest)
                else UserKeywordVerdict.Bad(UserKeywordReason.TOKENS_MISSING)
            UnpackedStamp.Check.NeedsHash -> UserKeywordVerdict.Bad(UserKeywordReason.STAMP_MISMATCH)
        }
    }
}
