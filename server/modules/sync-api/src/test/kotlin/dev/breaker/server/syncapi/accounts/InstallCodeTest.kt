package dev.breaker.server.syncapi.accounts

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The install code file: how it is made, and what [InstallCode.read] accepts and refuses (never with the text). */
internal class InstallCodeTest {

    private val code43 = "Q".repeat(43)

    private fun codePath(): Path = Files.createTempDirectory("install-code-test").resolve("install-code")

    // A file with [text] and the given mode (default owner only): the mode is set AFTER the write, so a
    // refusal in a test is the rule under test and never the default umask of the temp file.
    private fun fileWith(text: String, mode: String = "rw-------"): Path {
        val path = codePath()
        Files.writeString(path, text)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        return path
    }

    private fun refusal(path: Path): IllegalStateException =
        assertThrows(IllegalStateException::class.java) { InstallCode.read(path) }

    private fun assertNoText(claim: String, error: IllegalStateException, secret: String) {
        assertFalse("sync-api: $claim: the refusal must not hold the text", error.message.orEmpty().contains(secret.take(5)))
    }

    @Test
    fun `a new file is made owner only with a 43 character url safe code`() {
        val path = codePath()
        val made = InstallCode.loadOrCreate(path, SecureRandom())
        assertEquals("sync-api: 43 characters", 43, made.length)
        assertTrue("sync-api: url safe alphabet", made.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' })
        assertEquals(
            "sync-api: mode 0600",
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
        assertEquals("sync-api: the file holds the code", made, Files.readString(path))
    }

    @Test
    fun `a second load returns the same code and two new files differ`() {
        val path = codePath()
        val first = InstallCode.loadOrCreate(path, SecureRandom())
        assertEquals("sync-api: the same code the second time", first, InstallCode.loadOrCreate(path, SecureRandom()))
        assertFalse("sync-api: another file gets another code", first == InstallCode.loadOrCreate(codePath(), SecureRandom()))
    }

    @Test
    fun `read of a missing file is null`() {
        assertNull("sync-api: no file", InstallCode.read(codePath()))
    }

    @Test
    fun `read accepts exactly 43 characters and one trailing newline`() {
        assertEquals("sync-api: plain", code43, InstallCode.read(fileWith(code43)))
        assertEquals("sync-api: with one newline", code43, InstallCode.read(fileWith(code43 + "\n")))
    }

    @Test
    fun `read refuses a wrong length with no text in the message`() {
        for (text in listOf("", "Q".repeat(42), "Q".repeat(44), code43 + "\n\n", "Q".repeat(45))) {
            assertNoText("length ${text.length}", refusal(fileWith(text)), code43)
        }
    }

    @Test
    fun `read refuses a character outside the url safe alphabet`() {
        for (bad in listOf("!", " ", "+", "/", "=", "~", ".")) {
            val text = "Q".repeat(42) + bad
            assertNoText("character $bad", refusal(fileWith(text)), code43)
        }
        assertEquals("sync-api: underscore and hyphen are allowed", "Q".repeat(41) + "_-", InstallCode.read(fileWith("Q".repeat(41) + "_-")))
    }

    @Test
    fun `read refuses a file that group or others can touch even with a good code`() {
        for (mode in listOf("rw-r-----", "rw-rw----", "rw------x", "rw----r--", "rw-----w-")) {
            assertNoText("mode $mode", refusal(fileWith(code43, mode)), code43)
        }
    }

    @Test
    fun `loadOrCreate refuses an existing bad file and does not replace it`() {
        val path = fileWith("short")
        assertThrows(IllegalStateException::class.java) { InstallCode.loadOrCreate(path, SecureRandom()) }
        assertEquals("sync-api: the file is left as it was", "short", Files.readString(path))
    }
}
