package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.io.IOException

/**
 * One unpacked file as the stamp records it: its name, its size in bytes, its
 * modified time in milliseconds and its SHA-256 (lower case hex).
 */
internal class StampedFile(val name: String, val size: Long, val modifiedMillis: Long, val sha256: String)

/**
 * The record written beside the four unpacked files once a model has unpacked.
 *
 * It holds the digest of the archive the files came from and, for each file, its
 * size, modified time and SHA-256. The loader and the word stream factory use it as
 * the quick check that stands in for hashing the whole archive on every load: sizes
 * and modified times are compared first, and the files are hashed only when a
 * modified time differs. It is written only after the archive verified and the
 * unpack succeeded, so a refused install never leaves one behind.
 */
internal class UnpackedStamp(val archiveDigest: String, val files: List<StampedFile>) {

    /** The verdict of [check]: the files match, one file is wrong, or the times differ and the files need a hash. */
    sealed class Check {
        /** Every size and every modified time equals the stamp. */
        object Match : Check()

        /** The file named [name] is missing, is not a regular file, or has another size. */
        class Mismatch(val name: String) : Check()

        /** The sizes match but a modified time differs: the files must be hashed before they are trusted. */
        object NeedsHash : Check()
    }

    /** Returns the text form of this stamp: one record per line, ending with a line break. */
    fun encode(): String {
        val lines = ArrayList<String>()
        lines.add(HEADER)
        lines.add(ARCHIVE_PREFIX + archiveDigest.lowercase())
        for (file in files) {
            val fields = "${file.name} ${file.size} ${file.modifiedMillis} ${file.sha256.lowercase()}"
            lines.add(FILE_PREFIX + fields)
        }
        lines.add(END_LINE)
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * True when the stamp was written for the archive with digest [pin] (ignoring case)
     * and its files are named [names], in the same order.
     */
    fun isFor(pin: String, names: List<String>): Boolean =
        archiveDigest.equals(pin, ignoreCase = true) && files.map { it.name } == names

    /**
     * Compares the files in [directory] with the stamp, without hashing them. Every size
     * is checked before any modified time. The first file that is not a regular file, or
     * that has another size, is named in [Check.Mismatch].
     */
    fun check(directory: File): Check {
        for (file in files) {
            val actual = File(directory, file.name)
            if (!actual.isFile || actual.length() != file.size) return Check.Mismatch(file.name)
        }
        for (file in files) {
            if (File(directory, file.name).lastModified() != file.modifiedMillis) return Check.NeedsHash
        }
        return Check.Match
    }

    /**
     * Hashes the files in order with [digestOf] and returns the name of the first one
     * whose digest is missing or differs from the stamp (ignoring case). Returns null
     * when every file matches. It stops at the first difference.
     */
    fun hashesMatch(directory: File, digestOf: (File) -> String?): String? {
        for (file in files) {
            val digest = digestOf(File(directory, file.name))
            if (digest == null || !digest.equals(file.sha256, ignoreCase = true)) return file.name
        }
        return null
    }

    /**
     * Returns a copy of this stamp with the modified times read again from [directory].
     * The sizes and digests are kept. Returns null when a file cannot be read.
     */
    fun refreshed(directory: File): UnpackedStamp? {
        val copies = files.map { file ->
            val actual = File(directory, file.name)
            if (!actual.isFile || !actual.canRead()) return null
            StampedFile(file.name, file.size, actual.lastModified(), file.sha256)
        }
        return UnpackedStamp(archiveDigest, copies)
    }

    /** True when the stamp's file names, as a set, equal [names] as a set. */
    fun covers(names: List<String>): Boolean = files.map { it.name }.toSet() == names.toSet()

    companion object {
        /** Name of the stamp file: a dot-name inside the unpacked directory. */
        const val FILE_NAME: String = ".unpacked-stamp"

        private const val HEADER = "unpacked-stamp 1"
        private const val ARCHIVE_PREFIX = "archive "
        private const val FILE_PREFIX = "file "
        private const val END_LINE = "end"
        private const val FILE_FIELDS = 5

        /** Header, archive, at least one file, end, and the empty piece after the final line break. */
        private const val MIN_PIECES = 5

        /**
         * Reads a stamp from its text form. Returns null for any text that is not exactly
         * what [encode] writes: a missing or extra line, a wrong number of fields, a negative
         * or non-numeric number, a bad name or digest, or no final line break.
         */
        fun parse(text: String): UnpackedStamp? {
            val pieces = text.split("\n")
            if (pieces.size < MIN_PIECES || pieces.last().isNotEmpty()) return null
            val body = pieces.subList(0, pieces.size - 1)
            if (body.first() != HEADER || body.last() != END_LINE) return null
            if (!body[1].startsWith(ARCHIVE_PREFIX)) return null
            val digest = body[1].substring(ARCHIVE_PREFIX.length)
            if (!isDigest(digest)) return null
            val files = body.subList(2, body.size - 1).map { parseFile(it) ?: return null }
            return UnpackedStamp(digest.lowercase(), files)
        }

        /**
         * The one reader of the stamp: reads the file [FILE_NAME] in [directory] and parses
         * it. Returns null when the file is absent, cannot be read, or does not parse.
         */
        fun read(directory: File): UnpackedStamp? {
            return try {
                val file = File(directory, FILE_NAME)
                if (file.isFile) parse(file.readText()) else null
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

        /**
         * Measures the files [names] in [directory], in the order given: each file's size,
         * modified time and digest from [digestOf]. Returns null when a file is not a regular
         * file, when a name cannot be stored, or when [digestOf] returns null for a file.
         */
        fun measure(
            directory: File,
            names: List<String>,
            archiveDigest: String,
            digestOf: (File) -> String?,
        ): UnpackedStamp? {
            val measured = names.map { name ->
                val file = File(directory, name)
                if (!isStampName(name) || !file.isFile) return null
                val digest = digestOf(file) ?: return null
                StampedFile(name, file.length(), file.lastModified(), digest)
            }
            return UnpackedStamp(archiveDigest, measured)
        }

        private fun parseFile(line: String): StampedFile? {
            val fields = line.split(" ")
            if (fields.size != FILE_FIELDS || fields[0] != "file") return null
            val name = fields[1]
            if (!isStampName(name)) return null
            val size = parseNumber(fields[2]) ?: return null
            val modified = parseNumber(fields[3]) ?: return null
            if (!isDigest(fields[4])) return null
            return StampedFile(name, size, modified, fields[4].lowercase())
        }

        private fun isStampName(name: String): Boolean =
            name.isNotEmpty() && !name.startsWith(".") && !name.contains('/') && !name.contains('\\')

        private fun parseNumber(text: String): Long? =
            if (text.isNotEmpty() && text.all { it in '0'..'9' }) text.toLongOrNull() else null

        private fun isDigest(text: String): Boolean =
            text.length == ModelIntegrity.HEX_LENGTH &&
                text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
