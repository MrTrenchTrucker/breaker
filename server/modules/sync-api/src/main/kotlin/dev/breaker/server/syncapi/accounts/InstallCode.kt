package dev.breaker.server.syncapi.accounts

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

// The install code that the first registration must present. The first start makes
// the code and keeps it in a file; every later start reads that file while zero
// accounts exist. Once an account exists the code is moot and the file is ignored.
// The code text never appears in a message, a toString or a log line.
internal object InstallCode {
    private const val CODE_LENGTH = 43
    private const val CODE_RANDOM_BYTES = 32
    private const val MAX_FILE_BYTES = CODE_LENGTH + 1
    private const val NEWLINE = 0x0A

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

    // Reads an existing install-code file, or null when it does not exist.
    // Refuses (IllegalStateException) when the file exists but is unreadable,
    // too large, not owner-only, or not a well-formed code.
    fun read(path: Path): String? {
        if (!Files.exists(path)) return null
        return readExisting(path)
    }

    // Reads an existing file, or creates it with owner-only mode set AT creation
    // (createFile with the permission attribute, never create-then-chmod), then writes
    // the code. If the file appears first from another start, that file is read instead.
    fun loadOrCreate(path: Path, random: SecureRandom): String {
        if (Files.exists(path)) {
            return readExisting(path)
        }
        return createNew(path, random)
    }

    private fun readExisting(path: Path): String {
        val size = try {
            Files.size(path)
        } catch (_: IOException) {
            refuse(path, "could not be read")
        }
        if (size > MAX_FILE_BYTES) {
            refuse(path, "is larger than an install code and was not read")
        }
        checkOwnerOnly(path)
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: IOException) {
            refuse(path, "could not be read")
        }
        return parseCode(path, bytes)
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
    private fun parseCode(path: Path, bytes: ByteArray): String {
        val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1].toInt() == NEWLINE) bytes.size - 1 else bytes.size
        if (end != CODE_LENGTH) {
            refuse(path, "is not a well-formed install code")
        }
        for (index in 0 until end) {
            if (!isAlphabet(bytes[index].toInt() and 0xFF)) {
                refuse(path, "is not a well-formed install code")
            }
        }
        return String(bytes.copyOf(end), Charsets.US_ASCII)
    }

    private fun isAlphabet(code: Int): Boolean =
        code in 0x41..0x5A || code in 0x61..0x7A || code in 0x30..0x39 || code == 0x5F || code == 0x2D

    private fun createNew(path: Path, random: SecureRandom): String {
        val code = newCode(random)
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            return readExisting(path)
        } catch (_: IOException) {
            refuse(path, "could not be created")
        }
        try {
            Files.writeString(path, code)
            return code
        } catch (_: IOException) {
            try { Files.deleteIfExists(path) } catch (_: IOException) {}
            refuse(path, "could not be created")
        }
    }

    private fun newCode(random: SecureRandom): String {
        val bytes = ByteArray(CODE_RANDOM_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun refuse(path: Path, reason: String): Nothing =
        throw IllegalStateException("sync-api: the install code file $path $reason")
}
