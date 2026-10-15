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

/** The check, hash, refresh, cover and measure rules of the unpacked-file stamp. No hash is taken by check. */
class UnpackedStampCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val names = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "tokens.txt",
    )
    private val sizes = listOf(3, 4, 5, 6)
    private val times = listOf(1_700_000_000_000L, 1_700_000_001_000L, 1_700_000_002_000L, 1_700_000_003_000L)

    /** A 64-character lower case digest made of one repeated hex letter. */
    private fun hex(letter: Char): String = letter.toString().repeat(64)

    private fun writeSized(dir: File, index: Int, size: Int) {
        val file = Fixtures.writeBytes(File(dir, names[index]), ByteArray(size) { 1.toByte() })
        file.setLastModified(times[index])
    }

    /** A folder holding the four files at the sizes and modified times above. */
    private fun unpackedDir(): File {
        val dir = tmp.newFolder()
        for (i in names.indices) writeSized(dir, i, sizes[i])
        return dir
    }

    /** The stamp that matches [unpackedDir], every file digested as [digest]. */
    private fun stamp(digest: String = hex('b')): UnpackedStamp = UnpackedStamp(
        hex('a'),
        names.indices.map { i -> StampedFile(names[i], sizes[i].toLong(), times[i], digest) },
    )

    private fun mismatchName(check: UnpackedStamp.Check): String? =
        (check as? UnpackedStamp.Check.Mismatch)?.name

    @Test
    fun `check matches when every size and modified time equals the stamp`() {
        assertEquals(UnpackedStamp.Check.Match, stamp().check(unpackedDir()))
    }

    @Test
    fun `check names the first file with another size`() {
        val dir = unpackedDir()
        writeSized(dir, 1, sizes[1] + 1)
        writeSized(dir, 2, sizes[2] + 1)
        assertEquals(names[1], mismatchName(stamp().check(dir)))
    }

    @Test
    fun `check names a file that is missing or is a folder`() {
        val missing = unpackedDir()
        assertTrue(File(missing, names[2]).delete())
        assertEquals("a missing file", names[2], mismatchName(stamp().check(missing)))

        val folder = unpackedDir()
        assertTrue(File(folder, names[3]).delete())
        assertTrue(File(folder, names[3]).mkdir())
        assertEquals("a folder in place of a file", names[3], mismatchName(stamp().check(folder)))
    }

    @Test
    fun `check asks for a hash when only a modified time differs`() {
        val dir = unpackedDir()
        assertTrue(File(dir, names[2]).setLastModified(times[2] + 5_000L))
        assertEquals(UnpackedStamp.Check.NeedsHash, stamp().check(dir))
    }

    @Test
    fun `check reports a size mismatch even when another file has a different modified time`() {
        val dir = unpackedDir()
        assertTrue(File(dir, names[0]).setLastModified(times[0] + 5_000L))
        writeSized(dir, 3, sizes[3] + 1)
        assertEquals(names[3], mismatchName(stamp().check(dir)))
    }

    @Test
    fun `hashesMatch returns null when every digest equals the stamp, ignoring case`() {
        assertNull(stamp(hex('b')).hashesMatch(unpackedDir()) { hex('b').uppercase() })
    }

    @Test
    fun `hashesMatch names the first file whose digest differs and stops there`() {
        var calls = 0
        val result = stamp(hex('b')).hashesMatch(unpackedDir()) { file ->
            calls++
            if (file.name == names[1] || file.name == names[3]) hex('c') else hex('b')
        }
        assertEquals(names[1], result)
        assertEquals("digestOf is called up to and including the first difference", 2, calls)
    }

    @Test
    fun `hashesMatch names a file whose digest cannot be read`() {
        val result = stamp(hex('b')).hashesMatch(unpackedDir()) { file ->
            if (file.name == names[0]) null else hex('b')
        }
        assertEquals(names[0], result)
    }

    @Test
    fun `refreshed keeps sizes and digests and takes the modified times from the disk`() {
        val dir = unpackedDir()
        val stale = UnpackedStamp(hex('a'), names.indices.map { i -> StampedFile(names[i], sizes[i].toLong(), 1L, hex('b')) })
        val fresh = stale.refreshed(dir)
        assertNotNull("every file can be read", fresh)
        assertEquals(hex('a'), fresh!!.archiveDigest)
        assertEquals(names, fresh.files.map { it.name })
        assertEquals(sizes.map { it.toLong() }, fresh.files.map { it.size })
        assertEquals(List(names.size) { hex('b') }, fresh.files.map { it.sha256 })
        assertEquals(times, fresh.files.map { it.modifiedMillis })
    }

    @Test
    fun `refreshed is null when a file is missing`() {
        val dir = unpackedDir()
        assertNotNull("control: all four files present", stamp().refreshed(dir))
        assertTrue(File(dir, names[1]).delete())
        assertNull(stamp().refreshed(dir))
    }

    @Test
    fun `covers compares the names as a set`() {
        val s = stamp()
        assertTrue("the same names in another order", s.covers(names.reversed()))
        assertFalse("one name missing", s.covers(names.take(3)))
        assertFalse("one name extra", s.covers(names + "extra.bin"))
        assertFalse("a repeated name in place of another", s.covers(names.take(3) + names[0]))
    }

    @Test
    fun `measure records size, modified time and the digest of each file in the order given`() {
        val dir = unpackedDir()
        val digests = names.zip(listOf(hex('c'), hex('d'), hex('e'), hex('f'))).toMap()
        val order = names.reversed()
        val measured = UnpackedStamp.measure(dir, order, hex('a')) { file -> digests.getValue(file.name) }
        assertNotNull("every file is a regular file with a digest", measured)
        assertEquals(hex('a'), measured!!.archiveDigest)
        assertEquals(order, measured.files.map { it.name })
        assertEquals(order.map { n -> sizes[names.indexOf(n)].toLong() }, measured.files.map { it.size })
        assertEquals(order.map { n -> times[names.indexOf(n)] }, measured.files.map { it.modifiedMillis })
        assertEquals(order.map { digests.getValue(it) }, measured.files.map { it.sha256 })
    }

    @Test
    fun `measure is null for a missing file, for a digest that cannot be read and for a name it could not store`() {
        assertNotNull("control: a complete folder measures", UnpackedStamp.measure(unpackedDir(), names, hex('a')) { hex('b') })

        val missing = unpackedDir()
        assertTrue(File(missing, names[2]).delete())
        assertNull("a missing file", UnpackedStamp.measure(missing, names, hex('a')) { hex('b') })

        val unreadable = unpackedDir()
        assertNull(
            "a digest that cannot be read",
            UnpackedStamp.measure(unreadable, names, hex('a')) { file -> if (file.name == names[0]) null else hex('b') },
        )

        val hidden = unpackedDir()
        File(hidden, ".tokens.txt").writeText("x")
        assertNull(
            "a name with a leading dot that is never stored",
            UnpackedStamp.measure(hidden, listOf(".tokens.txt"), hex('a')) { hex('b') },
        )
    }
}
