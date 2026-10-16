package dev.breaker.dictation.ui.screen.firstrun

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The picture files that the first-run pages look up by name. The folder must hold exactly
 * the names FirstRunPictures lists, each a PNG of the size the art pack gives it.
 */
class FirstRunResourcesTest {
    /** The folder of the picture files, found from wherever the test was started. */
    private val folder = File(moduleDirectory(), "src/main/res/drawable-nodpi")

    @Test
    fun `the folder holds exactly the picture files the first run names, and nothing else`() {
        assertTrue("the picture folder was not found at ${folder.absolutePath}", folder.isDirectory)
        val present = folder.listFiles()?.map { it.name }?.sorted().orEmpty()
        val expected = FirstRunPictures.ALL.map { "$it.png" }.sorted()
        assertEquals("files in ${folder.path}", expected, present)
    }

    @Test
    fun `each picture file starts with the eight PNG signature bytes`() {
        for (name in FirstRunPictures.ALL) {
            val bytes = bytesOf(name)
            assertTrue("$name is too short to be a PNG", bytes.size >= 8)
            assertTrue("$name does not start with the PNG signature", bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))
        }
    }

    @Test
    fun `the permission pictures are 960 by 640 and the logos 1536 by 640`() {
        for (name in FirstRunPictures.ALL) {
            val expected = if (name.startsWith("breaker_logo_")) LOGO_SIZE else PERMISSION_SIZE
            val (width, height) = sizeOf(name)
            assertEquals("$name width", expected.first, width)
            assertEquals("$name height", expected.second, height)
        }
    }

    @Test
    fun `a picture that is missing fails with its own name`() {
        val missing = "breaker_perm_no_such_picture"
        val failure = runCatching { bytesOf(missing) }.exceptionOrNull()
        assertTrue("a missing picture did not fail", failure is AssertionError)
        assertTrue("the failure does not name the picture: ${failure?.message}", failure?.message?.contains(missing) == true)
    }

    private fun bytesOf(name: String): ByteArray {
        val file = File(folder, "$name.png")
        assertTrue("$name.png is missing from ${folder.path}", file.isFile)
        return file.readBytes()
    }

    /** The width and height from the IHDR chunk: big-endian integers at bytes 16 and 20. */
    private fun sizeOf(name: String): Pair<Int, Int> {
        val bytes = bytesOf(name)
        assertTrue("$name is too short to hold a PNG header", bytes.size >= 24)
        return bigEndian(bytes, 16) to bigEndian(bytes, 20)
    }

    private fun bigEndian(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or
            ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val PERMISSION_SIZE = 960 to 640
        val LOGO_SIZE = 1536 to 640

        /**
         * The module folder: the working directory or the folder above it that holds the
         * module's own source tree, the same walk up from the start point the module's other
         * scans use.
         */
        fun moduleDirectory(): File {
            var level: File? = File("").absoluteFile
            while (level != null) {
                if (File(level, "src/main/kotlin/dev/breaker/dictation/ui").isDirectory) return level
                val nested = File(level, "android/ui/src/main/kotlin/dev/breaker/dictation/ui")
                if (nested.isDirectory) return File(level, "android/ui")
                level = level.parentFile
            }
            error("the ui module was not found at or above ${File("").absolutePath}")
        }
    }
}
