package dev.breaker.dictation.stt.ondevice

/**
 * The rules one archive entry must pass that need no archive and no counters.
 *
 * The rules look only at what the entry header says: its name, its type flag,
 * whether it is a directory and its declared size. Counting entries, spotting
 * a repeated name, the top-directory rule and the list of files to write are
 * the extractor's job.
 *
 * The type flag is the tar header byte. A name that looks like a file never
 * makes an entry a file: a link is a link whatever it is called.
 */
internal object EntryRules {
    /** Type flag NUL: a plain file in the oldest tar format. */
    private const val FLAG_OLD_FILE: Byte = 0

    /** Type flag '0': a plain file. */
    private const val FLAG_FILE: Byte = 48

    /** Type flag '1': a hard link. */
    private const val FLAG_HARD_LINK: Byte = 49

    /** Type flag '2': a symbolic link. */
    private const val FLAG_SYMBOLIC_LINK: Byte = 50

    /** Type flag '5': a directory. */
    private const val FLAG_DIRECTORY: Byte = 53

    private const val FIRST_PRINTABLE = 0x20
    private const val LAST_PRINTABLE = 0x7e

    /**
     * Check one entry; null means the entry passes.
     *
     * The checks run in this order and the first failure is returned: the name
     * (absolute, parent segment, characters and empty or dot segments, length,
     * depth), then the type (symbolic link, hard link, anything that is not a
     * plain file or a directory), then the declared size.
     *
     * One leading "./" is accepted and is not counted as a segment. A directory
     * may end in one "/". The length limit applies to the name exactly as it
     * stands in the header, "./" and trailing "/" included.
     *
     * @param name the entry name as the header gives it.
     * @param linkFlag the tar type flag byte.
     * @param isDirectory whether the entry is a directory.
     * @param size the size the header declares.
     * @param limits the bounds for the model being unpacked.
     */
    fun check(
        name: String,
        linkFlag: Byte,
        isDirectory: Boolean,
        size: Long,
        limits: ExtractionLimits,
    ): ExtractionReason? {
        val nameProblem = checkName(name, isDirectory, limits)
        if (nameProblem != null) return nameProblem
        val typeProblem = checkType(linkFlag, isDirectory, size)
        if (typeProblem != null) return typeProblem
        if (size > limits.maxEntryBytes) return ExtractionReason.ENTRY_TOO_LARGE
        return null
    }

    private fun checkName(name: String, isDirectory: Boolean, limits: ExtractionLimits): ExtractionReason? {
        if (name.startsWith("/")) return ExtractionReason.ABSOLUTE_PATH
        var trimmed = if (name.startsWith("./")) name.substring(2) else name
        if (isDirectory && trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length - 1)
        val segments = trimmed.split('/')
        if (segments.any { it == ".." }) return ExtractionReason.PARENT_SEGMENT
        if (trimmed.any { it == '\\' || it.code < FIRST_PRINTABLE || it.code > LAST_PRINTABLE }) {
            return ExtractionReason.BAD_NAME
        }
        if (segments.any { it.isEmpty() || it == "." }) return ExtractionReason.BAD_NAME
        if (name.length > limits.maxPathLength) return ExtractionReason.PATH_TOO_LONG
        if (segments.size > limits.maxDepth) return ExtractionReason.TOO_DEEP
        return null
    }

    private fun checkType(linkFlag: Byte, isDirectory: Boolean, size: Long): ExtractionReason? {
        if (linkFlag == FLAG_SYMBOLIC_LINK) return ExtractionReason.SYMLINK
        if (linkFlag == FLAG_HARD_LINK) return ExtractionReason.HARDLINK
        val plain = linkFlag == FLAG_OLD_FILE || linkFlag == FLAG_FILE || linkFlag == FLAG_DIRECTORY
        if (!plain) return ExtractionReason.NOT_REGULAR
        if (linkFlag == FLAG_DIRECTORY && !isDirectory) return ExtractionReason.NOT_REGULAR
        if (isDirectory && size != 0L) return ExtractionReason.NOT_REGULAR
        if (size < 0L) return ExtractionReason.NOT_REGULAR
        return null
    }
}
