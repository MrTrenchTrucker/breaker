package dev.breaker.dictation.stt.ondevice

import java.io.File

/**
 * Who is at fault when an unpack is refused.
 *
 * The installer maps this to the sentence the user sees: a bad archive asks
 * for a new download, a local fault does not, and a lack of space says so.
 */
enum class ExtractionFault {
    /** The archive itself is unsafe, damaged or not what the profile expects. */
    ARCHIVE,

    /** The phone could not do the work (write, rename, existing target). */
    LOCAL,

    /** There is not enough free space for the files. */
    SPACE,
}

/**
 * Why an unpack was refused. The order is the declaration order that the tests
 * pin; it is not the order in which the checks run.
 *
 * @property fault who is at fault, see [ExtractionFault].
 */
enum class ExtractionReason(val fault: ExtractionFault) {
    /** The entry name starts with a slash. */
    ABSOLUTE_PATH(ExtractionFault.ARCHIVE),

    /** The entry name has a parent-directory segment. */
    PARENT_SEGMENT(ExtractionFault.ARCHIVE),

    /** The entry name has a character, an empty segment or a dot segment that is not allowed. */
    BAD_NAME(ExtractionFault.ARCHIVE),

    /** The entry name is longer than the profile allows. */
    PATH_TOO_LONG(ExtractionFault.ARCHIVE),

    /** The entry name has more path segments than the profile allows. */
    TOO_DEEP(ExtractionFault.ARCHIVE),

    /** The entry is a symbolic link. */
    SYMLINK(ExtractionFault.ARCHIVE),

    /** The entry is a hard link. */
    HARDLINK(ExtractionFault.ARCHIVE),

    /** The entry is neither a plain file nor a directory (device, fifo, sparse, unknown type). */
    NOT_REGULAR(ExtractionFault.ARCHIVE),

    /** The archive has more entries than the profile allows. */
    TOO_MANY_ENTRIES(ExtractionFault.ARCHIVE),

    /** One entry declares a size over the profile's per-entry limit. */
    ENTRY_TOO_LARGE(ExtractionFault.ARCHIVE),

    /** The files to write add up to more than the profile's written limit. */
    WRITTEN_TOO_LARGE(ExtractionFault.ARCHIVE),

    /** The decompressed stream is longer than the profile's stream limit. */
    STREAM_TOO_LARGE(ExtractionFault.ARCHIVE),

    /** The compressed stream or a tar header could not be read. */
    STREAM_ERROR(ExtractionFault.ARCHIVE),

    /** The archive ended early. */
    TRUNCATED(ExtractionFault.ARCHIVE),

    /** Two entries have the same name once normalised. */
    DUPLICATE_NAME(ExtractionFault.ARCHIVE),

    /** An entry lies outside the archive's single top directory. */
    OUTSIDE_TOP(ExtractionFault.ARCHIVE),

    /** A file the profile needs is missing or empty. */
    MISSING_FILE(ExtractionFault.ARCHIVE),

    /** There is not enough free space for the files. */
    NO_SPACE(ExtractionFault.SPACE),

    /** Writing a file failed. */
    WRITE_ERROR(ExtractionFault.LOCAL),

    /** Moving the finished directory into place failed. */
    COMMIT_FAILED(ExtractionFault.LOCAL),

    /** The target directory already exists. */
    TARGET_EXISTS(ExtractionFault.LOCAL),
}

/**
 * The result of one unpack.
 */
sealed class ExtractionOutcome {
    /**
     * The files were written and moved into place.
     *
     * @property directory the directory that now holds the files.
     * @property fileCount how many files were written.
     * @property bytes how many bytes were written in all.
     */
    data class Extracted(val directory: File, val fileCount: Int, val bytes: Long) : ExtractionOutcome()

    /**
     * The unpack was refused and nothing was left behind.
     *
     * @property reason why it was refused.
     * @property detail a short explanation for the log, never shown to the user.
     */
    data class Rejected(val reason: ExtractionReason, val detail: String) : ExtractionOutcome()
}
