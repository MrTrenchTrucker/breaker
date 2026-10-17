package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryRulesTest {

    // Tar type flags written as their own literals, not read back from the code under test.
    private val oldFile: Byte = 0
    private val file: Byte = '0'.code.toByte()
    private val hardLink: Byte = '1'.code.toByte()
    private val symlink: Byte = '2'.code.toByte()
    private val directory: Byte = '5'.code.toByte()

    private val limits = ExtractionLimits(
        maxEntries = 32,
        maxEntryBytes = 100L,
        maxWrittenBytes = 1_000L,
        maxStreamBytes = 10_000L,
        maxPathLength = 20,
        maxDepth = 3,
    )

    private fun check(
        name: String,
        flag: Byte = file,
        dir: Boolean = false,
        size: Long = 0L,
        with: ExtractionLimits = limits,
    ): ExtractionReason? = EntryRules.check(name, flag, dir, size, with)

    private fun expect(expected: ExtractionReason?, claim: String, name: String, flag: Byte = file, dir: Boolean = false, size: Long = 0L, with: ExtractionLimits = limits) {
        assertEquals("$claim: name=[${name.replace("\u0000", "<NUL>")}] flag=$flag dir=$dir size=$size", expected, check(name, flag, dir, size, with))
    }

    // --- accepted shapes ---

    @Test
    fun `plain file and directory names of the real archives are accepted`() {
        val real = limits.copy(maxPathLength = 128, maxDepth = 4)
        val top = "sherpa-onnx-streaming-zipformer-en-2023-06-21-mobile"
        expect(null, "the top directory", "$top/", dir = true, with = real)
        expect(null, "a nested directory", "$top/test_wavs/", dir = true, with = real)
        expect(null, "a model file", "$top/encoder-epoch-99-avg-1.int8.onnx", size = 100L, with = real)
        expect(null, "a file two levels down", "$top/test_wavs/0.wav", size = 100L, with = real)
        expect(null, "a short name", "top/tokens.txt")
    }

    @Test
    fun `both plain file flags are accepted`() {
        expect(null, "the NUL flag", "top/a", flag = oldFile)
        expect(null, "the zero flag", "top/a", flag = file)
    }

    @Test
    fun `names that merely contain dots are accepted`() {
        for (name in listOf("top/a..b", "top/..a", "top/a..", "top/...", "top/.hidden", "top/a.b.c")) {
            expect(null, "a dotted name is not a dot segment", name)
        }
    }

    // --- ABSOLUTE_PATH ---

    @Test
    fun `an absolute path is refused as ABSOLUTE_PATH`() {
        for (name in listOf("/etc/passwd", "/", "/top/file", "//x", "/..", "/./a")) {
            expect(ExtractionReason.ABSOLUTE_PATH, "a name starting with a slash", name)
        }
        expect(ExtractionReason.ABSOLUTE_PATH, "an absolute directory", "/top/", dir = true)
    }

    // --- PARENT_SEGMENT ---

    @Test
    fun `a parent segment is refused wherever the segment sits`() {
        for (name in listOf("..", "../x", "a/../b", "a/..", "a/b/..", "../..", "top/../../etc", "./../x", "a//../b", "a\u0001/../b")) {
            expect(ExtractionReason.PARENT_SEGMENT, "a parent segment", name)
        }
        expect(ExtractionReason.PARENT_SEGMENT, "a parent segment in a directory name", "a/../", dir = true)
        expect(ExtractionReason.PARENT_SEGMENT, "a parent segment as the whole directory name", "../", dir = true)
    }

    // --- BAD_NAME ---

    @Test
    fun `every control character and the delete character are refused as BAD_NAME`() {
        for (code in 0x00..0x1f) {
            expect(ExtractionReason.BAD_NAME, "control character 0x${code.toString(16)}", "a${code.toChar()}b")
        }
        expect(ExtractionReason.BAD_NAME, "the delete character", "a\u007fb")
    }

    @Test
    fun `every printable ASCII character except the backslash is accepted inside a name`() {
        for (code in 0x20..0x7e) {
            val c = code.toChar()
            if (c == '\\') continue
            expect(null, "printable character 0x${code.toString(16)}", "a${c}b")
        }
    }

    @Test
    fun `a backslash is refused as BAD_NAME`() {
        for (name in listOf("a\\b", "\\", "a\\", "top\\..\\x", "..\\x")) {
            expect(ExtractionReason.BAD_NAME, "a backslash anywhere", name)
        }
    }

    @Test
    fun `a character outside ASCII is refused as BAD_NAME`() {
        for (name in listOf("a\u0080b", "a\u00a0b", "a\u00e9b", "a\u00ffb", "a\u4e2db", "a\ud83d\ude00b")) {
            expect(ExtractionReason.BAD_NAME, "a non-ASCII character", name)
        }
    }

    @Test
    fun `an empty name an empty segment or a dot segment is refused as BAD_NAME`() {
        for (name in listOf("", "a//b", "a/./b", ".", "top/.", "./.", "./", "a/b//", ".//a", "././a")) {
            expect(ExtractionReason.BAD_NAME, "an empty or dot segment", name)
        }
        expect(ExtractionReason.BAD_NAME, "a file name ending in a slash", "top/a/", dir = false)
        expect(ExtractionReason.BAD_NAME, "a directory name ending in two slashes", "top//", dir = true)
    }

    @Test
    fun `one leading dot-slash is accepted and a second one is not`() {
        expect(null, "one leading dot-slash on a file", "./top/a")
        expect(null, "one leading dot-slash on a directory", "./top/", dir = true)
        expect(ExtractionReason.BAD_NAME, "two leading dot-slashes", "././top/a")
    }

    @Test
    fun `one trailing slash is accepted on a directory and refused on a file`() {
        expect(null, "a directory with a trailing slash", "top/", dir = true)
        expect(null, "a directory without a trailing slash", "top", dir = true)
        expect(ExtractionReason.BAD_NAME, "a file with a trailing slash", "top/", dir = false)
    }

    // --- PATH_TOO_LONG ---

    @Test
    fun `a name of exactly the maximum length is accepted and one more is refused as PATH_TOO_LONG`() {
        val exact = "a/" + "b".repeat(18)
        assertEquals(20, exact.length)
        expect(null, "a file name at the maximum length", exact)
        expect(ExtractionReason.PATH_TOO_LONG, "a file name one over the maximum length", exact + "b")
        val exactDir = "d".repeat(19) + "/"
        expect(null, "a directory name at the maximum length", exactDir, dir = true)
        expect(ExtractionReason.PATH_TOO_LONG, "a directory name one over the maximum length", "d" + exactDir, dir = true)
    }

    @Test
    fun `a leading dot-slash and a trailing slash count toward the path length`() {
        expect(null, "dot-slash plus 18 characters", "./" + "c".repeat(18))
        expect(ExtractionReason.PATH_TOO_LONG, "dot-slash plus 19 characters", "./" + "c".repeat(19))
    }

    @Test
    fun `the real maximum of 128 characters is the boundary with the real limits`() {
        val real = limits.copy(maxPathLength = 128, maxDepth = 4)
        val exact = "a/" + "b".repeat(126)
        assertEquals(128, exact.length)
        expect(null, "128 characters", exact, with = real)
        expect(ExtractionReason.PATH_TOO_LONG, "129 characters", exact + "b", with = real)
    }

    // --- TOO_DEEP ---

    @Test
    fun `a name with exactly the maximum depth is accepted and one more segment is refused as TOO_DEEP`() {
        expect(null, "three segments", "a/b/c")
        expect(ExtractionReason.TOO_DEEP, "four segments", "a/b/c/d")
        expect(null, "a directory of three segments", "a/b/c/", dir = true)
        expect(ExtractionReason.TOO_DEEP, "a directory of four segments", "a/b/c/d/", dir = true)
    }

    @Test
    fun `a leading dot-slash is not a path segment`() {
        expect(null, "dot-slash plus three segments", "./a/b/c")
        expect(ExtractionReason.TOO_DEEP, "dot-slash plus four segments", "./a/b/c/d")
    }

    @Test
    fun `the real depth of 4 is the boundary with the real limits`() {
        val real = limits.copy(maxPathLength = 128, maxDepth = 4)
        expect(null, "top, test_wavs and a file", "top/test_wavs/0.wav", with = real)
        expect(null, "four segments", "a/b/c/d", with = real)
        expect(ExtractionReason.TOO_DEEP, "five segments", "a/b/c/d/e", with = real)
    }

    // --- SYMLINK, HARDLINK ---

    @Test
    fun `a symbolic link is refused even when its name looks like a file`() {
        expect(ExtractionReason.SYMLINK, "a link named like a model file", "top/model.onnx", flag = symlink)
        expect(ExtractionReason.SYMLINK, "a link named like a directory", "top/", flag = symlink, dir = true)
        expect(ExtractionReason.SYMLINK, "a link with a size", "top/a", flag = symlink, size = 5L)
    }

    @Test
    fun `a hard link is refused as HARDLINK`() {
        expect(ExtractionReason.HARDLINK, "a hard link named like a model file", "top/model.onnx", flag = hardLink)
        expect(ExtractionReason.HARDLINK, "a hard link with a size", "top/a", flag = hardLink, size = 5L)
    }

    // --- NOT_REGULAR ---

    @Test
    fun `devices fifos sparse files and unknown type flags are refused as NOT_REGULAR`() {
        val flags = listOf('3', '4', '6', '7', 'S', 'M', 'L', 'K', 'x', 'g', 'V', 'D', 'N', 'Z', 'a', 'z')
        for (c in flags) {
            expect(ExtractionReason.NOT_REGULAR, "type flag '$c'", "top/a", flag = c.code.toByte())
        }
        for (raw in listOf(1, 2, 31, 127, 128, 255)) {
            expect(ExtractionReason.NOT_REGULAR, "raw type flag byte $raw", "top/a", flag = raw.toByte())
        }
    }

    @Test
    fun `a directory flag is accepted for a directory`() {
        expect(null, "a directory flag on a directory", "top/", flag = directory, dir = true)
    }

    @Test
    fun `a directory with a size other than zero is refused as NOT_REGULAR`() {
        expect(ExtractionReason.NOT_REGULAR, "a directory of 1 byte", "top/", flag = directory, dir = true, size = 1L)
        expect(ExtractionReason.NOT_REGULAR, "a slash-named file entry of 1 byte", "top/", flag = file, dir = true, size = 1L)
        expect(null, "a directory of 0 bytes", "top/", flag = directory, dir = true, size = 0L)
    }

    @Test
    fun `a directory flag on an entry that is not a directory is refused as NOT_REGULAR`() {
        expect(ExtractionReason.NOT_REGULAR, "directory flag, not a directory", "top/a", flag = directory, dir = false)
    }

    @Test
    fun `a negative size is refused as NOT_REGULAR and an empty file is accepted`() {
        expect(ExtractionReason.NOT_REGULAR, "minus one", "top/a", size = -1L)
        expect(ExtractionReason.NOT_REGULAR, "the smallest long", "top/a", size = Long.MIN_VALUE)
        expect(null, "zero bytes", "top/a", size = 0L)
    }

    // --- ENTRY_TOO_LARGE ---

    @Test
    fun `a size of exactly the entry maximum is accepted and one more is refused as ENTRY_TOO_LARGE`() {
        expect(null, "100 bytes", "top/a", size = 100L)
        expect(ExtractionReason.ENTRY_TOO_LARGE, "101 bytes", "top/a", size = 101L)
        expect(ExtractionReason.ENTRY_TOO_LARGE, "the largest long", "top/a", size = Long.MAX_VALUE)
    }

    @Test
    fun `type problems are reported before the size bound`() {
        expect(ExtractionReason.SYMLINK, "a link over the size bound", "top/a", flag = symlink, size = 101L)
        expect(ExtractionReason.HARDLINK, "a hard link over the size bound", "top/a", flag = hardLink, size = 101L)
        expect(ExtractionReason.NOT_REGULAR, "a device over the size bound", "top/a", flag = '3'.code.toByte(), size = 101L)
        expect(ExtractionReason.NOT_REGULAR, "a directory over the size bound", "top/", flag = directory, dir = true, size = 101L)
    }

    @Test
    fun `name problems are reported in table order`() {
        expect(ExtractionReason.ABSOLUTE_PATH, "absolute before parent", "/..")
        expect(ExtractionReason.PARENT_SEGMENT, "parent before bad name", "a//../b")
        expect(ExtractionReason.BAD_NAME, "bad name before length", "a\\" + "b".repeat(30))
        expect(ExtractionReason.PATH_TOO_LONG, "length before depth", "a/b/c/d/e/f/g/h/i/j/k")
    }

    // --- the whole set ---

    @Test
    fun `the rules return only the nine reasons that need no archive and every one of them occurs`() {
        val names = listOf(
            "top/a", "top/", "/x", "a/../b", "a\\b", "a//b", "", "a/b/c/d", "d".repeat(25), "./top/a", "top/..",
        )
        val flags = listOf(oldFile, file, hardLink, symlink, directory, '3'.code.toByte(), 'S'.code.toByte())
        val sizes = listOf(-1L, 0L, 100L, 101L)
        val seen = HashSet<ExtractionReason>()
        for (name in names) for (flag in flags) for (dir in listOf(false, true)) for (size in sizes) {
            val reason = check(name, flag, dir, size) ?: continue
            assertEquals("the reason $reason must be an archive fault", ExtractionFault.ARCHIVE, reason.fault)
            seen.add(reason)
        }
        val expected = setOf(
            ExtractionReason.ABSOLUTE_PATH, ExtractionReason.PARENT_SEGMENT, ExtractionReason.BAD_NAME,
            ExtractionReason.PATH_TOO_LONG, ExtractionReason.TOO_DEEP, ExtractionReason.SYMLINK,
            ExtractionReason.HARDLINK, ExtractionReason.NOT_REGULAR, ExtractionReason.ENTRY_TOO_LARGE,
        )
        assertEquals("the reasons the rules can return", expected, seen)
        assertTrue("the table must reach every one of the nine reasons", seen.size == 9)
    }
}
