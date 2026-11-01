package dev.breaker.server.syncapi.servicecheck

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The race, the link failures and the temp file rules. These tests need a POSIX file system
// (Linux or macOS): they set and read permission bits.
internal class ServiceKeyFileRaceTest {

    private lateinit var folder: Path

    @Before
    fun makeFolder() {
        folder = Files.createTempDirectory("service-key-race-test")
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

    // WINNER is another valid 43 character key, the text the other start wrote first.
    private val countingFill: (Int) -> Byte = { index -> index.toByte() }
    // Built at run time from the bytes 0 to 31 with the standard encoder and the two character swaps,
    // a different route from the production code, so a secret scan sees no key-shaped literal.
    private val countingKey: String = java.util.Base64.getEncoder()
        .encodeToString(ByteArray(32) { index -> index.toByte() })
        .trimEnd('=').replace('+', '-').replace('/', '_')
    private val winner = "WinnerKey-0123456789abcdefghijklmnopqrstuvw"

    private fun writeFile(path: Path, text: String, mode: String) {
        Files.writeString(path, text)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
    }

    private fun names(): List<String> =
        Files.list(folder).use { entries -> entries.toList().map { entry -> entry.fileName.toString() }.sorted() }

    private fun refusalText(block: () -> Unit): String {
        val outcome = runCatching(block)
        assertTrue("sync-api: the call was expected to be refused but it returned", outcome.isFailure)
        val failure = outcome.exceptionOrNull()
        assertTrue("sync-api: expected IllegalStateException but got $failure", failure is IllegalStateException)
        return failure?.message ?: ""
    }

    @Test
    fun `a start that loses the race returns the winner's text and not its own key`() {
        val linker = KeyLinker { target, _ ->
            writeFile(target, winner, "rw-------")
            throw FileAlreadyExistsException(target.toString())
        }
        val text = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill), linker)
        assertEquals("sync-api: the winner's text", winner, text)
        assertFalse("sync-api: our own key must not be returned", text == countingKey)
        assertEquals("sync-api: the winner file is the only file left", listOf("service-key"), names())
    }

    @Test
    fun `a successful start leaves exactly the key file in the folder`() {
        ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill))
        assertEquals("sync-api: the folder after a successful start", listOf("service-key"), names())
    }

    @Test
    fun `a leftover temp file from an earlier start does not stop a start and is left alone`() {
        val leftover = folder.resolve("service-key-leftover.tmp")
        writeFile(leftover, "partial", "rw-------")
        val text = ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill))
        assertEquals("sync-api: the key text", countingKey, text)
        assertEquals("sync-api: the leftover keeps its text", "partial", Files.readString(leftover))
        assertEquals("sync-api: the folder", listOf("service-key", "service-key-leftover.tmp"), names())
    }

    @Test
    fun `a generic link failure is refused naming the path, with no key text and no temp file left`() {
        val linker = KeyLinker { _, _ -> throw IOException("the link failed") }
        val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill), linker) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(keyPath().toString()))
        assertTrue("sync-api: the message must say it could not be created but was: $message", message.contains("could not be created"))
        assertFalse("sync-api: the message must not hold the key text", message.contains(countingKey))
        assertEquals("sync-api: no file may be left", emptyList<String>(), names())
    }

    @Test
    fun `a link that is not supported is refused naming the path, with no key text and no temp file left`() {
        val linker = KeyLinker { _, _ -> throw UnsupportedOperationException("links are not supported") }
        val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill), linker) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(keyPath().toString()))
        assertFalse("sync-api: the message must not hold the key text", message.contains(countingKey))
        assertEquals("sync-api: no file may be left", emptyList<String>(), names())
    }

    private class Scenario(val name: String, val setup: (Path) -> Unit, val linker: KeyLinker)

    @Test
    fun `the key text is in no refusal message on any refusal path`() {
        val failingLink = KeyLinker { _, _ -> throw IOException("the link failed") }
        val unsupportedLink = KeyLinker { _, _ -> throw UnsupportedOperationException("links are not supported") }
        val scenarios = listOf(
            Scenario("a failed link", { dir -> Files.createDirectory(dir) }, failingLink),
            Scenario("an unsupported link", { dir -> Files.createDirectory(dir) }, unsupportedLink),
            Scenario("a missing folder", { _ -> }, KeyLinker.FILES),
            Scenario("a group readable file that holds the key", { dir ->
                Files.createDirectory(dir)
                writeFile(dir.resolve("service-key"), countingKey, "rw-r-----")
            }, KeyLinker.FILES),
            Scenario("an oversized file that starts with the key", { dir ->
                Files.createDirectory(dir)
                writeFile(dir.resolve("service-key"), countingKey + "\nA", "rw-------")
            }, KeyLinker.FILES),
            Scenario("a malformed file", { dir ->
                Files.createDirectory(dir)
                writeFile(dir.resolve("service-key"), "+" + countingKey.substring(1), "rw-------")
            }, KeyLinker.FILES),
        )
        for ((index, scenario) in scenarios.withIndex()) {
            val dir = folder.resolve("case-$index")
            scenario.setup(dir)
            val path = dir.resolve("service-key")
            val message = refusalText { ServiceKeyFile.loadOrCreate(path, fakeRandom(countingFill), scenario.linker) }
            assertTrue("sync-api: ${scenario.name} must start with the prefix but was: $message", message.startsWith("sync-api:"))
            assertFalse("sync-api: ${scenario.name} must not hold the key text but was: $message", message.contains(countingKey))
        }
    }


    @Test
    fun `a winner file readable by its group is refused and its text is never returned`() {
        val linker = KeyLinker { target, _ ->
            writeFile(target, winner, "rw-r-----")
            throw FileAlreadyExistsException(target.toString())
        }
        val message = refusalText { ServiceKeyFile.loadOrCreate(keyPath(), fakeRandom(countingFill), linker) }
        assertTrue("sync-api: the message must name the path but was: $message", message.contains(keyPath().toString()))
        assertTrue("sync-api: the message must name the mode but was: $message", message.contains("rw-r-----"))
        assertFalse("sync-api: the winner's text must not be in the message", message.contains(winner))
    }
}
