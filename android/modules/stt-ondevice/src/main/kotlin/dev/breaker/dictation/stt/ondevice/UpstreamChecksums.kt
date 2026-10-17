package dev.breaker.dictation.stt.ondevice

/**
 * Parses and holds the upstream checksum file that pins model digests.
 *
 * The upstream checksum file is the trust anchor for model integrity: it
 * lists the digests of known-good model archives as published by the model
 * distributor. A downloaded archive is trusted only when its computed digest
 * equals our compiled pin AND that pin appears as a digest in this file.
 *
 * This object is responsible for parsing the file format and answering
 * membership questions. It never interprets names as paths and never
 * refuses a file based on name shape - the only decision the module makes
 * is whether a digest is present.
 */
object UpstreamChecksums {

    /** The conventional name of the checksum file shipped alongside a model. */
    const val CHECKSUM_FILE_NAME: String = "checksum.txt"

    /**
     * Thrown when the checksum file cannot be parsed at all.
     *
     * A damaged or empty checksum file must never be treated as "no digests
     * listed" - that would make every pin fail with PIN_NOT_IN_UPSTREAM and
     * blame the model instead of the unreadable file.
     */
    class ChecksumFormatException(message: String) : IllegalStateException(message)

    /**
     * The parsed contents of a checksum file.
     *
     * @property byName maps each recorded name to its digest (lowercase).
     *   A name listed more than once maps to the digest of its last line.
     *   Names are kept for diagnostics only; they are never used as paths.
     * @param listed every digest that appears anywhere in the file, including
     *   digests that a later line for the same name replaced in [byName].
     *   Defaults to the digests in [byName], which is complete for a file
     *   that lists each name once.
     */
    data class Checksums(
        val byName: Map<String, String>,
        private val listed: Set<String> = byName.values.toSet(),
    ) {

        /**
         * Every distinct digest listed anywhere in the file, lowercased for
         * case-insensitive comparison. A digest counts as listed even when a
         * later line gave the same name a different digest.
         */
        val digests: Set<String> = listed.map { it.lowercase() }.toSet()

        /** All recorded names. */
        val names: Set<String> = byName.keys

        /**
         * Returns true when [digest] matches any digest in this file,
         * ignoring case.
         *
         * This is the single decision the module makes about a pin: is it
         * listed upstream? A name is never consulted.
         */
        fun containsDigest(digest: String): Boolean =
            digests.contains(digest.lowercase())

        /**
         * Returns the digest recorded for [name], or null if absent. A name
         * listed more than once returns the digest of its last line.
         *
         * Names are recorded for diagnostics only and are never used as
         * paths. A null return means the name was not listed - it does not
         * imply the digest is absent from the file.
         */
        operator fun get(name: String): String? = byName[name]

        /**
         * Returns true when [name] appears in the file.
         *
         * This is a diagnostic helper; the trust decision is [containsDigest].
         */
        operator fun contains(name: String): Boolean = name in byName
    }

    /**
     * Parses [text] as a checksum file and returns the resulting [Checksums].
     *
     * The upstream checksum file is the trust anchor for model integrity. Its
     * data lines are TAB-separated `filename<TAB>sha256`, but the sha256sum
     * convention is `digest  name`. This parser accepts either field order and
     * any whitespace separator, because the module only cares about the digest.
     *
     * Format rules:
     * - Lines are split on `\n`; a trailing `\r` is stripped; each line is trimmed.
     * - Blank lines and lines starting with `#` are skipped.
     * - Every other line must contain EXACTLY TWO whitespace-separated fields.
     *   One field is the 64-character hex digest; the other is the file name.
     *   EITHER ORDER is accepted.
     *
     * RULE 1 - an empty file (no entries at all) is refused with
     *   [ChecksumFormatException] naming the file. An empty digest set would
     *   make the membership test always false, and the refusal would then
     *   blame the model instead of the unreadable checksum file.
     *
     * RULE 2 - a non-skipped line with FEWER than two fields is refused,
     *   naming `file:lineNumber`. A line we cannot read may be the line that
     *   would have matched our pin; skipping it would let a damaged file
     *   masquerade as 'not listed'.
     *
     * RULE 3 - a non-skipped line with MORE than two fields is refused,
     *   naming `file:lineNumber`. With three or more fields it is not knowable
     *   which token is the digest and which is the name, and guessing would
     *   mean trusting the guess.
     *
     * RULE 4 - neither field is exactly 64 hex characters is refused,
     *   naming `file:lineNumber`. A truncated or wrong-algorithm token can
     *   never equal our pin; refusing tells the caller the checksum file is
     *   damaged rather than the model being absent. If BOTH fields are 64 hex
     *   characters, treat the FIRST as the digest and the second as the name;
     *   do not refuse - a name that happens to look like a digest is harmless
     *   because the name is never consulted.
     *
     * The digest is stored lowercased. The name is stored as-is, for
     * diagnostics only.
     *
     * Duplicate names are NOT refused and path-shaped names are NOT refused.
     * A name listed twice keeps the digest of its last line for lookups by
     * name, and every digest of every line counts as listed.
     * The only decision the module makes is whether a digest is present;
     * a name is never consulted and never becomes a path, so such a rule
     * could only reject a file whose digest information is perfectly good.
     *
     * @param text the raw contents of the checksum file.
     * @param fileName the name of the file, used in error messages.
     * @throws ChecksumFormatException if the file is empty or any non-skipped
     *   line is malformed.
     */
    fun parse(text: String, fileName: String = CHECKSUM_FILE_NAME): Checksums {
        val lines = text.split('\n')
        val entries = mutableMapOf<String, String>()
        val listed = LinkedHashSet<String>()

        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine.removeSuffix("\r").trim()
            if (line.isEmpty() || line.startsWith("#")) continue

            val lineNumber = index + 1
            val fields = line.split(Regex("\\s+")).filter { it.isNotEmpty() }

            if (fields.size < 2) {
                throw ChecksumFormatException(
                    "$fileName:$lineNumber: expected two whitespace-separated fields (name and digest), found ${fields.size}"
                )
            }
            if (fields.size > 2) {
                throw ChecksumFormatException(
                    "$fileName:$lineNumber: expected exactly two whitespace-separated fields, found ${fields.size}"
                )
            }

            val first = fields[0]
            val second = fields[1]
            val firstIsDigest = HEX_64.matches(first)
            val secondIsDigest = HEX_64.matches(second)

            if (!firstIsDigest && !secondIsDigest) {
                throw ChecksumFormatException(
                    "$fileName:$lineNumber: neither field is a 64-character hex digest"
                )
            }

            val digest = if (firstIsDigest) first else second
            val name = if (firstIsDigest) second else first

            entries[name] = digest.lowercase()
            listed.add(digest.lowercase())
        }

        if (entries.isEmpty()) {
            throw ChecksumFormatException(
                "$fileName: no checksum entries found"
            )
        }

        return Checksums(entries, listed)
    }

    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
}
