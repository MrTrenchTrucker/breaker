package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AccountKeys
import dev.breaker.dictation.core.model.BoxPrivateKey
import dev.breaker.dictation.core.model.DataEncryptionKey
import dev.breaker.dictation.core.model.StoredAccountKeys
import dev.breaker.dictation.core.port.CredentialVault
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A [CredentialVault] backed by one file in the app's files directory, sealed by a [SessionSeal]
 * that carries its own purpose text, so this file and the session file cannot be swapped. The plain
 * record is 69 bytes: one format byte, four bytes of key version (big-endian), the 32 byte data key
 * and the 32 byte box key. Writes go to a temporary file beside the target and are renamed over it
 * in one atomic step, so a failed write leaves the old value and no temporary file behind. A read
 * never throws: anything that does not open or does not have the exact shape gives null.
 */
internal class CredentialFileVault(
    private val file: File,
    private val seal: SessionSeal,
) : CredentialVault {

    override fun saveKeys(keys: AccountKeys, keyVersion: Int) {
        val dek: ByteArray = keys.dek.bytes
        require(dek.size == KEY_BYTES) { "the data key has the wrong length" }
        require(keyVersion >= 0) { "the key version is negative" }
        val box: ByteArray = keys.boxPrivateKey.copyBytes()
        val plain: ByteArray = ByteArray(RECORD_BYTES)
        try {
            plain[0] = FORMAT.toByte()
            for (i in 0 until VERSION_BYTES) {
                plain[1 + i] = (keyVersion ushr (BITS_PER_BYTE * (VERSION_BYTES - 1 - i))).toByte()
            }
            System.arraycopy(dek, 0, plain, DEK_START, KEY_BYTES)
            System.arraycopy(box, 0, plain, BOX_START, KEY_BYTES)
            write(seal.seal(plain))
        } finally {
            plain.fill(0)
            box.fill(0)
        }
    }

    override fun readKeys(): StoredAccountKeys? {
        if (!file.isFile) return null
        if (file.length() > SIZE_LIMIT) return null
        var plain: ByteArray? = null
        try {
            plain = seal.unseal(file.readBytes())
            return parse(plain)
        } catch (e: Exception) {
            return null
        } finally {
            plain?.fill(0)
        }
    }

    override fun wipeAll() {
        if (!file.delete() && file.exists()) {
            throw IOException("could not delete " + file.name)
        }
    }

    /** Writes [bytes] to a temporary file beside the target, then moves it over the target. */
    private fun write(bytes: ByteArray) {
        if (bytes.size > SIZE_LIMIT) throw IOException("record too large")
        val folder: File = file.absoluteFile.parentFile ?: throw IOException("no folder")
        Files.createDirectories(folder.toPath())
        val temp: File = File(folder, file.name + TEMP_SUFFIX)
        try {
            Files.write(temp.toPath(), bytes)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            discard(temp)
            throw e
        }
    }

    /** A new holder for the plain record, or null when its shape is not the exact one. */
    private fun parse(plain: ByteArray): StoredAccountKeys? {
        if (plain.size != RECORD_BYTES || plain[0].toInt() != FORMAT) return null
        var version = 0
        for (i in 1..VERSION_BYTES) {
            version = (version shl BITS_PER_BYTE) or (plain[i].toInt() and BYTE_MASK)
        }
        if (version < 0) return null
        val dekBytes: ByteArray = plain.copyOfRange(DEK_START, DEK_START + KEY_BYTES)
        val boxBytes: ByteArray = plain.copyOfRange(BOX_START, BOX_START + KEY_BYTES)
        val built = AccountKeys(DataEncryptionKey(dekBytes), BoxPrivateKey(boxBytes))
        try {
            return StoredAccountKeys(built, version)
        } finally {
            built.wipe()
            dekBytes.fill(0)
            boxBytes.fill(0)
        }
    }

    /** Drops a leftover temporary file; safe to call when it was never created. */
    private fun discard(temp: File) {
        try {
            Files.deleteIfExists(temp.toPath())
        } catch (e: IOException) {
            // A leftover temporary file is overwritten by the next write.
        }
    }

    companion object {
        private const val TEMP_SUFFIX: String = ".tmp"

        /** 64 KiB cap: a file larger than this is unreadable, untouched, never read into memory. */
        private const val SIZE_LIMIT: Long = 65536L

        /** The one format byte this vault writes and reads. */
        private const val FORMAT: Int = 1
        private const val VERSION_BYTES: Int = 4
        private const val KEY_BYTES: Int = 32
        private const val BITS_PER_BYTE: Int = 8
        private const val BYTE_MASK: Int = 0xFF

        /** Where the data key starts in the plain record: after the format byte and the version. */
        private const val DEK_START: Int = 1 + VERSION_BYTES
        private const val BOX_START: Int = DEK_START + KEY_BYTES

        /** The exact length of the plain record: 1 + 4 + 32 + 32. */
        private const val RECORD_BYTES: Int = BOX_START + KEY_BYTES
    }
}
