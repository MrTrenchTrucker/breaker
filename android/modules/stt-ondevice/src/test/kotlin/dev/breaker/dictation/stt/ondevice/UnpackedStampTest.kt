package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The text form of the unpacked-file stamp, its strict parser and its one reader. The check rules are in UnpackedStampCheckTest. */
class UnpackedStampTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "tokens.txt",
    )

    /** A 64-character lower case digest made of one repeated hex letter. */
    private fun hex(letter: Char): String = letter.toString().repeat(64)

    private fun stamp(count: Int = names.size): UnpackedStamp = UnpackedStamp(
        hex('a'),
        names.take(count).mapIndexed { i, name -> StampedFile(name, 100L + i, 1_700_000_000_000L + i * 1000L, hex('b')) },
    )

    private fun fieldsOf(stamp: UnpackedStamp): List<List<Any>> =
        stamp.files.map { listOf(it.name, it.size, it.modifiedMillis, it.sha256) }

    /** The encoded stamp with line [index] replaced by [line]; line 2 is the first file line. */
    private fun parseWithLine(index: Int, line: String): UnpackedStamp? {
        val lines = stamp().encode().split("\n").toMutableList()
        lines[index] = line
        return UnpackedStamp.parse(lines.joinToString("\n"))
    }

    @Test
    fun `a stamp survives encode and parse unchanged`() {
        for (count in listOf(2, 4)) {
            val original = stamp(count)
            val back = UnpackedStamp.parse(original.encode())
            assertNotNull("a $count-file stamp must parse", back)
            assertEquals(original.archiveDigest, back!!.archiveDigest)
            assertEquals(fieldsOf(original), fieldsOf(back))
            assertEquals(original.encode(), back.encode())
        }
    }

    @Test
    fun `text without the end line reads as no stamp`() {
        val torn = stamp().encode().removeSuffix("end\n")
        assertNull("a torn stamp with no end line", UnpackedStamp.parse(torn))
    }

    @Test
    fun `text with an extra line, a wrong header or a missing final line break reads as no stamp`() {
        val text = stamp().encode()
        assertNotNull("control: the untouched text parses", UnpackedStamp.parse(text))
        assertNull("an extra line before end", UnpackedStamp.parse(text.replace("\nend\n", "\njunk\nend\n")))
        assertNull("a wrong header", UnpackedStamp.parse(text.replace("unpacked-stamp 1", "unpacked-stamp 2")))
        assertNull("no final line break", UnpackedStamp.parse(text.removeSuffix("\n")))
    }

    @Test
    fun `a file line with the wrong number of fields, a negative number or a non-numeric number reads as no stamp`() {
        val file = "file ${names[0]}"
        val digest = hex('b')
        assertNotNull("control: a valid file line parses", parseWithLine(2, "$file 100 1700000000000 $digest"))
        assertNull("four fields", parseWithLine(2, "$file 100 1700000000000"))
        assertNull("six fields", parseWithLine(2, "$file 100 1700000000000 $digest extra"))
        assertNull("a negative size", parseWithLine(2, "$file -100 1700000000000 $digest"))
        assertNull("a non-numeric modified time", parseWithLine(2, "$file 100 17x0000000000 $digest"))
    }

    @Test
    fun `a digest of the wrong length or with a non-hex character reads as no stamp`() {
        val good = hex('a')
        assertNotNull("control: a 64-character archive digest parses", parseWithLine(1, "archive $good"))
        assertNull("an archive digest of 63 characters", parseWithLine(1, "archive ${good.dropLast(1)}"))
        assertNull("an archive digest with a g", parseWithLine(1, "archive g${good.drop(1)}"))
        assertNull("a file digest of 65 characters", parseWithLine(2, "file ${names[0]} 100 1700000000000 ${hex('b')}0"))
        assertNull("a file digest with a z", parseWithLine(2, "file ${names[0]} 100 1700000000000 z${hex('b').drop(1)}"))
    }

    @Test
    fun `a file name with a slash, a backslash or a leading dot reads as no stamp`() {
        val tail = " 100 1700000000000 ${hex('b')}"
        assertNotNull("control: a plain name parses", parseWithLine(2, "file tokens.txt$tail"))
        assertNull("a slash in the name", parseWithLine(2, "file sub/tokens.txt$tail"))
        assertNull("a backslash in the name", parseWithLine(2, "file sub\\tokens.txt$tail"))
        assertNull("a leading dot in the name", parseWithLine(2, "file .tokens.txt$tail"))
    }

    @Test
    fun `a stamp with no file line reads as no stamp`() {
        assertNull("no file line", UnpackedStamp.parse("unpacked-stamp 1\narchive ${hex('a')}\nend\n"))
        assertNotNull("control: one file line parses", UnpackedStamp.parse(stamp(1).encode()))
    }

    @Test
    fun `isFor accepts the same pin in any case and refuses another pin or other names or another order`() {
        val s = stamp()
        assertTrue("the same pin in upper case", s.isFor(hex('a').uppercase(), names))
        assertFalse("another pin", s.isFor(hex('c'), names))
        assertFalse("other names", s.isFor(hex('a'), names.take(3)))
        assertFalse("another order", s.isFor(hex('a'), names.reversed()))
    }

    @Test
    fun `read returns null for an absent file and for text that does not parse, and the stamp for a valid file`() {
        val dir = tmp.newFolder()
        assertNull("no file", UnpackedStamp.read(dir))
        File(dir, UnpackedStamp.FILE_NAME).writeText("not a stamp\n")
        assertNull("text that does not parse", UnpackedStamp.read(dir))
        File(dir, UnpackedStamp.FILE_NAME).writeText(stamp().encode())
        assertEquals("a valid file", stamp().encode(), UnpackedStamp.read(dir)?.encode())
    }

    @Test
    fun `the stamp file name is the dot name the card documents`() {
        assertEquals(".unpacked-stamp", UnpackedStamp.FILE_NAME)
    }
}
