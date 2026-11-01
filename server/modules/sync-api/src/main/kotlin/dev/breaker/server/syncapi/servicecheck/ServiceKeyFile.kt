package dev.breaker.server.syncapi.servicecheck

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

// The service key that the internal token-check routes expect in the X-Service-Key header.
// The first start makes the key and keeps it in a file; every later start reads that file.
// Rotation: delete the file and start again; the next start makes a new key.
// The key text never appears in a message, a toString or a log line.
internal object ServiceKeyFile {
    private const val KEY_LENGTH = 43
    private const val KEY_RANDOM_BYTES = 32
    private const val MAX_FILE_BYTES = KEY_LENGTH + 1
    private const val NEWLINE = 0x0A
    private const val TEMP_PREFIX = "service-key-"
    private const val TEMP_SUFFIX = ".tmp"

    private val OWNER_ONLY: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    private val GROUP_OR_OTHER: Set<PosixFilePermission> = setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )

    fun loadOrCreate(path: Path, random: SecureRandom, linker: KeyLinker = KeyLinker.FILES): String {
        if (Files.exists(path)) {
            return readExisting(path)
        }
        return createNew(path, random, linker)
    }

    // Checks in this order: size (the file is not read when it is too large), mode, then shape.
    private fun readExisting(path: Path): String {
        val size = try {
            Files.size(path)
        } catch (_: IOException) {
            refuse(path, "could not be read")
        }
        if (size > MAX_FILE_BYTES) {
            refuse(path, "is larger than a key and was not read")
        }
        checkOwnerOnly(path)
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: IOException) {
            refuse(path, "could not be read")
        }
        return parseKey(path, bytes)
    }

    private fun checkOwnerOnly(path: Path) {
        val permissions = try {
            Files.getPosixFilePermissions(path)
        } catch (_: UnsupportedOperationException) {
            refuse(path, "cannot be checked because the file system has no POSIX permissions")
        } catch (_: IOException) {
            refuse(path, "could not be read")
        }
        if (permissions.any { it in GROUP_OR_OTHER }) {
            refuse(path, "must be readable and writable by its owner only, but its mode is ${PosixFilePermissions.toString(permissions)}")
        }
    }

    // One trailing newline is allowed and removed; anything else must be exactly 43 characters
    // of the URL-safe alphabet. The refusal never includes the text.
    private fun parseKey(path: Path, bytes: ByteArray): String {
        val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1].toInt() == NEWLINE) bytes.size - 1 else bytes.size
        if (end != KEY_LENGTH) {
            refuse(path, "is not a well-formed service key")
        }
        for (index in 0 until end) {
            if (!isAlphabet(bytes[index].toInt() and 0xFF)) {
                refuse(path, "is not a well-formed service key")
            }
        }
        return String(bytes.copyOf(end), Charsets.US_ASCII)
    }

    private fun isAlphabet(code: Int): Boolean =
        code in 0x41..0x5A || code in 0x61..0x7A || code in 0x30..0x39 || code == 0x5F || code == 0x2D

    // The temp file is made in the same folder, owner-only at creation, written, then linked to the
    // key path. The link fails when the key path exists, so the loser reads the winner's file.
    private fun createNew(path: Path, random: SecureRandom, linker: KeyLinker): String {
        val key = newKey(random)
        val folder = path.toAbsolutePath().parent ?: refuse(path, "could not be created")
        val temp = makeTemp(path, folder)
        try {
            writeKey(path, temp, key)
            return try {
                linker.link(path, temp)
                key
            } catch (_: FileAlreadyExistsException) {
                readExisting(path)
            } catch (_: IOException) {
                refuse(path, "could not be created")
            } catch (_: UnsupportedOperationException) {
                refuse(path, "could not be created")
            }
        } finally {
            removeTemp(path, temp)
        }
    }

    private fun newKey(random: SecureRandom): String {
        val bytes = ByteArray(KEY_RANDOM_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun makeTemp(path: Path, folder: Path): Path =
        try {
            Files.createTempFile(folder, TEMP_PREFIX, TEMP_SUFFIX, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } catch (_: IOException) {
            refuse(path, "could not be created")
        } catch (_: UnsupportedOperationException) {
            refuse(path, "could not be created because the file system has no POSIX permissions")
        }

    private fun writeKey(path: Path, temp: Path, key: String) {
        try {
            Files.writeString(temp, key)
        } catch (_: IOException) {
            refuse(path, "could not be created")
        } catch (_: UnsupportedOperationException) {
            refuse(path, "could not be created")
        }
    }

    private fun removeTemp(path: Path, temp: Path) {
        try {
            Files.deleteIfExists(temp)
        } catch (_: IOException) {
            refuse(path, "could not be created because its temporary file could not be removed")
        }
    }

    private fun refuse(path: Path, reason: String): Nothing =
        throw IllegalStateException("sync-api: the service key file $path $reason")
}
