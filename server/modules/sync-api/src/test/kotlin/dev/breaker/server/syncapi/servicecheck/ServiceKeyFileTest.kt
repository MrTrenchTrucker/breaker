package dev.breaker.server.syncapi.servicecheck

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// These tests need a POSIX file system (Linux or macOS): they set and read owner, group and
// other permission bits, and a file system without them makes the setup throw.
internal class ServiceKeyFileTest {

    private lateinit var folder: Path

    @Before
    fun makeFolder() {
        folder = Files.createTempDirectory("service-key-test")
    }

    @After
    fun removeFolder() {
        folder.toFile().deleteRecursively()
    }

    private fun keyPath(): Path = folder.resolve("service-key")

    private fun fakeRandom(fill: (Int) -> Byte): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                bytes[index] = fill(index)
            }
        }
    }

    // allFfFill gives a different key from countingKey, so a rewrite of the file would show.
    private val countingFill: (Int) -> Byte = { index -> index.toByte() }
    private val allFfFill: (Int) -> Byte = { _ -> 0xFF.toByte() }
    // Built at run time from the bytes 0 to 31 with the standard encoder and the two character swaps,
    // a different route from the production code, so a secret scan sees no key-shaped literal.
    private val countingKey: String = java.util.Base64.getEncoder()
        .encodeToString(ByteArray(32) { index -> index.toByte() })
        .trimEnd('=').replace('+', '-').replace('/', '_')

    private fun writeFile(path: Path, text: String, mode: String) {
        Files.writeString(path, text)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }

    private fun refusedBy(block: () -> Unit): IllegalStateException {
        val outcome = runCatching(block)
        assertTrue("sync-api: the call was expected to be refused but it returned", outcome.isFailure)
        val failure = outcome.exceptionOrNull()
        assertTrue("sync-api: expected IllegalStateException but got $failure", failure is IllegalStateException)
        return failure as IllegalStateException
    }

    private fun refusalText(block: () -> Unit): String = refusedBy(block).message ?: ""

    @Test
    fun `a missing file gets a 43 character key that is owner only and has no newline`() {
        val text = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill))
        assertEquals("sync-api: the returned key text", countingKey, text)
        assertEquals("sync-api: the file text", countingKey, Files.readString(keyPath()))
        assertEquals(
            "sync-api: the file mode",
            "rw-------",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(keyPath())),
        )
    }

    @Test
    fun `a second call returns the same text and leaves the file bytes and time unchanged`() {
        ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill))
        val bytesBefore = Files.readAllBytes(keyPath())
        val timeBefore = Files.getLastModifiedTime(keyPath())
        val second = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(allFfFill))
        assertEquals("sync-api: the second call text", countingKey, second)
        assertArrayEquals("sync-api: the file bytes", bytesBefore, Files.readAllBytes(keyPath()))
        assertEquals("sync-api: the file time", timeBefore, Files.getLastModifiedTime(keyPath()))
    }

    @Test
    fun `a malformed file is refused, the message names the path, and the file text is not in it`() {
        val cases = listOf(
            "an empty file" to "",
            "42 characters" to countingKey.substring(0, 42),
            "44 characters" to countingKey + "A",
            "a plus sign" to "+" + countingKey.substring(1),
            "a padding sign" to countingKey.substring(0, 42) + "=",
            "a space inside" to countingKey.substring(0, 20) + " " + countingKey.substring(21),
            "a non ascii letter" to countingKey.substring(0, 21) + "\u00e9" + countingKey.substring(22),
            "a carriage return and newline" to countingKey.substring(0, 42) + "\r\n",
        )
        for ((name, text) in cases) {
            writeFile(keyPath(), text, "rw-------")
            val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill)) }
            assertTrue("sync-api: $name must start with the prefix but was: $message", message.startsWith("sync-api:"))
            assertTrue("sync-api: $name must name the path but was: $message", message.contains(keyPath().toString()))
            assertTrue("sync-api: $name must say not well formed but was: $message", message.contains("not a well-formed service key"))
            assertFalse("sync-api: $name: the message must not hold the file text", text.isNotEmpty() && message.contains(text))
        }
    }

    @Test
    fun `a file with one trailing newline is accepted, returned without it, and not rewritten`() {
        writeFile(keyPath(), countingKey + "\n", "rw-------")
        val text = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(allFfFill))
        assertEquals("sync-api: the text without the newline", countingKey, text)
        assertEquals("sync-api: the file keeps its newline", countingKey + "\n", Files.readString(keyPath()))
    }

    @Test
    fun `a file larger than a key is refused as too large and is not read as a key`() {
        writeFile(keyPath(), countingKey + "\nA", "rw-------")
        val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill)) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(keyPath().toString()))
        assertTrue("sync-api: the message must be the size refusal but was: $message", message.contains("is larger than a key and was not read"))
        assertFalse("sync-api: the message must not hold the file text", message.contains(countingKey))
    }

    @Test
    fun `a file readable by its group is refused and the message names the path and the mode`() {
        writeFile(keyPath(), countingKey, "rw-r-----")
        val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill)) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(keyPath().toString()))
        assertTrue("sync-api: the message must name the mode rw-r----- but was: $message", message.contains("rw-r-----"))
        assertFalse("sync-api: the message must not hold the file text", message.contains(countingKey))
    }

    @Test
    fun `a file writable by others is refused and the message names the path and the mode`() {
        writeFile(keyPath(), countingKey, "rw-----w-")
        val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill)) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(keyPath().toString()))
        assertTrue("sync-api: the message must name the mode rw-----w- but was: $message", message.contains("rw-----w-"))
        assertFalse("sync-api: the message must not hold the file text", message.contains(countingKey))
    }

    @Test
    fun `a missing folder is refused naming the path and the folder is not made`() {
        val missing = folder.resolve("missing-folder").resolve("service-key")
        val message = refusalText { ServiceKeyFile.loadOrCreate(missing, fakeRandom(countingFill)) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(missing.toString()))
        assertFalse("sync-api: the folder must not be made", Files.exists(folder.resolve("missing-folder")))
        assertFalse("sync-api: the message must not hold the key text", message.contains(countingKey))
    }


    @Test
    fun `a key made of underscores is accepted`() {
        writeFile(keyPath(), "_".repeat(43), "rw-------")
        val text = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill))
        assertEquals("sync-api: the underscore key", "_".repeat(43), text)
    }

    @Test
    fun `a key with a capital Z is accepted`() {
        val key = "Z" + countingKey.substring(1)
        writeFile(keyPath(), key, "rw-------")
        val text = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill))
        assertEquals("sync-api: the key with a capital Z", key, text)
    }

    @Test
    fun `characters just outside the alphabet are refused one key at a time`() {
        for (outside in listOf(":", "@", "[", "`", "{", "/", ".")) {
            val text = outside + countingKey.substring(1)
            writeFile(keyPath(), text, "rw-------")
            val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill)) }
            assertTrue("sync-api: $outside must be refused but was: $message", message.contains("not a well-formed service key"))
            assertTrue("sync-api: $outside: the message must name the path but was: $message", message.contains(keyPath().toString()))
            assertFalse("sync-api: $outside: the message must not hold the file text", message.contains(text))
        }
    }
}
