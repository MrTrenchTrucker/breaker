package dev.breaker.dictation.wiring

import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The keywords file writer over a real folder: private, atomic, replaced on a second write, no temporary file left on failure. */
class PrivateKeywordsFileWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun folder(): File = File(tmp.root, "keywords")

    private fun names(): List<String> = (folder().list() ?: emptyArray()).sorted()

    private fun posix(): Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private val realMove: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    @Test
    fun `a write makes the folder and returns the keywords file holding the text`() {
        val file: File = PrivateKeywordsFileWriter(folder()).write("a b\n")
        assertEquals("app: the file is not named keywords.txt in the folder", File(folder().absoluteFile, "keywords.txt"), file)
        assertEquals("app: the keywords file holds other text", "a b\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `the text is written as UTF-8 bytes without a change`() {
        val text = "▁B RE\n▁AND ' M\n"
        val file: File = PrivateKeywordsFileWriter(folder()).write(text)
        assertArrayEquals("app: the bytes of the keywords file differ from the UTF-8 text", text.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test
    fun `a second write replaces the content and a shorter text leaves no tail`() {
        val writer = PrivateKeywordsFileWriter(folder())
        writer.write("first line that is long\nsecond line that is long\n")
        val file: File = writer.write("short\n")
        assertEquals("app: the second write did not replace the content", "short\n", file.readText(Charsets.UTF_8))
    }

    @Test
    fun `after a good write only the keywords file is in the folder`() {
        val writer = PrivateKeywordsFileWriter(folder())
        writer.write("one\n")
        writer.write("two\n")
        assertEquals("app: a temporary file was left behind", listOf("keywords.txt"), names())
    }

    @Test
    fun `the file and the folder are not readable writable or searchable by the group or others`() {
        assumeTrue("this file system has no POSIX permissions", posix())
        val file: File = PrivateKeywordsFileWriter(folder()).write("one\n")
        val others: Set<PosixFilePermission> = setOf(
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
        )
        val fileRights: Set<PosixFilePermission> = Files.getPosixFilePermissions(file.toPath())
        val folderRights: Set<PosixFilePermission> = Files.getPosixFilePermissions(folder().toPath())
        assertEquals("app: the keywords file gives a right to the group or others", emptySet<PosixFilePermission>(), fileRights.intersect(others))
        assertEquals("app: the keywords folder gives a right to the group or others", emptySet<PosixFilePermission>(), folderRights.intersect(others))
        assertTrue("app: the owner cannot read the keywords file", PosixFilePermission.OWNER_READ in fileRights)
        assertTrue("app: the owner cannot search the keywords folder", PosixFilePermission.OWNER_EXECUTE in folderRights)
    }

    @Test
    fun `a file that was open to others is closed by the next write`() {
        assumeTrue("this file system has no POSIX permissions", posix())
        val writer = PrivateKeywordsFileWriter(folder())
        writer.write("one\n")
        val open: Set<PosixFilePermission> = PosixFilePermission.values().toSet()
        Files.setPosixFilePermissions(folder().toPath(), open)
        val file: File = writer.write("two\n")
        val rights: Set<PosixFilePermission> = Files.getPosixFilePermissions(file.toPath())
        assertFalse("app: a write left the file readable by others", PosixFilePermission.OTHERS_READ in rights)
        assertFalse("app: a write left the folder open to others", PosixFilePermission.OTHERS_READ in Files.getPosixFilePermissions(folder().toPath()))
    }

    @Test
    fun `the temporary file has the fixed name and sits in the folder of the target when it is moved`() {
        val seen: MutableList<Pair<File, File>> = ArrayList()
        val writer = PrivateKeywordsFileWriter(folder()) { from, to ->
            seen.add(from to to)
            realMove(from, to)
        }
        writer.write("one\n")
        assertEquals("app: the move was not called once", 1, seen.size)
        assertEquals("app: the temporary name is not fixed", "keywords.txt.tmp", seen.single().first.name)
        assertEquals("app: the target is not keywords.txt", "keywords.txt", seen.single().second.name)
        assertEquals("app: the temporary file is not beside the target", seen.single().second.parentFile, seen.single().first.parentFile)
    }

    @Test
    fun `a stale temporary file from an earlier crash does not leak into the new text`() {
        folder().mkdirs()
        File(folder(), "keywords.txt.tmp").writeText("stale stale stale stale stale stale\n")
        val file: File = PrivateKeywordsFileWriter(folder()).write("new\n")
        assertEquals("app: the stale temporary text reached the keywords file", "new\n", file.readText(Charsets.UTF_8))
        assertEquals("app: the stale temporary file is still there", listOf("keywords.txt"), names())
    }

    @Test
    fun `a refused move throws and leaves no temporary file and no keywords file`() {
        val writer = PrivateKeywordsFileWriter(folder()) { _, _ -> throw IOException("move refused") }
        assertThrows("app: a refused move did not reach the caller", IOException::class.java) { writer.write("one\n") }
        assertEquals("app: a refused move left a file behind", emptyList<String>(), names())
    }

    @Test
    fun `a refused move leaves the old content of the keywords file as it was`() {
        PrivateKeywordsFileWriter(folder()).write("old\n")
        val failing = PrivateKeywordsFileWriter(folder()) { _, _ -> throw IOException("move refused") }
        assertThrows("app: a refused move did not reach the caller", IOException::class.java) { failing.write("new\n") }
        assertEquals("app: a refused move changed the old content", "old\n", File(folder(), "keywords.txt").readText(Charsets.UTF_8))
        assertEquals("app: a refused move left a temporary file", listOf("keywords.txt"), names())
    }

    @Test
    fun `a move that fails with a runtime exception also leaves no temporary file`() {
        val writer = PrivateKeywordsFileWriter(folder()) { _, _ -> throw IllegalStateException("move broke") }
        assertThrows("app: a runtime failure did not reach the caller", IllegalStateException::class.java) { writer.write("one\n") }
        assertEquals("app: a runtime failure left a file behind", emptyList<String>(), names())
    }

    @Test
    fun `a folder path that is a plain file fails with an IOException and writes nothing`() {
        val blocker: File = tmp.newFile("keywords")
        assertThrows("app: a blocked folder did not fail with an IOException", IOException::class.java) {
            PrivateKeywordsFileWriter(File(tmp.root, "keywords")).write("one\n")
        }
        assertTrue("app: the blocking file was changed", blocker.isFile && blocker.length() == 0L)
    }
}
