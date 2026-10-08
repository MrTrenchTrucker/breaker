package dev.breaker.dictation.stt.ondevice

import java.io.File
import java.security.MessageDigest

/**
 * Shared test fixtures for the stt-ondevice module tests.
 *
 * These helpers are NOT test classes - they provide deterministic values
 * and independent computations so each test can focus on one behaviour.
 */
internal object Fixtures {

    /** A valid 64-character lowercase hex pin. */
    val VALID_PIN: String = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"

    /** A second valid pin, distinct from [VALID_PIN]. */
    val OTHER_PIN: String = "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3"

    /** SHA-256 of the empty byte array - published known-answer vector. */
    const val EMPTY_SHA256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** SHA-256 of the ASCII bytes of "abc" - published known-answer vector. */
    const val ABC_SHA256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    /**
     * Build a [UpstreamChecksums.Checksums] from name/digest pairs.
     * Digests are lowercased to match production behaviour.
     */
    fun checksumsOf(vararg pairs: Pair<String, String>): UpstreamChecksums.Checksums =
        UpstreamChecksums.Checksums(pairs.associate { (name, digest) -> name to digest.lowercase() })

    /**
     * Compute SHA-256 of [bytes] independently of [ModelIntegrity],
     * using [java.security.MessageDigest] directly.
     *
     * This is the independent oracle for the large-file digest test -
     * the expected value must never come from the code under test.
     */
    fun independentSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Put all four files of the unpack profile of [id] (each with content) in the
     * unpacked-files directory, so the model counts as unpacked. An id with no
     * profile can never count as unpacked; it gets the single file "tokens.txt".
     * Returns that directory.
     */
    fun seedExtracted(store: LocalModelStore, id: String): File {
        val dir = store.extractedDirectory(id)
        val names = ExtractionProfiles.forModel(id)?.files ?: listOf("tokens.txt")
        for (name in names) Fixtures.writeText(File(dir, name), "seeded $name")
        return dir
    }

    /**
     * Write [bytes] to [file], creating parent directories as needed.
     */
    fun writeBytes(file: File, bytes: ByteArray): File {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    /**
     * Write [text] to [file], creating parent directories as needed.
     */
    fun writeText(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    /**
     * Create a file of exactly [size] bytes filled with a deterministic
     * repeating pattern.
     */
    fun createFileOfSize(file: File, size: Long): File {
        file.parentFile?.mkdirs()
        val pattern = ByteArray(1024) { (it % 251).toByte() }
        file.outputStream().use { out ->
            var remaining = size
            while (remaining > 0) {
                val chunk = minOf(remaining, pattern.size.toLong()).toInt()
                out.write(pattern, 0, chunk)
                remaining -= chunk
            }
        }
        return file
    }
}
