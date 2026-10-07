package dev.breaker.dictation.stt.ondevice

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Random

/**
 * One entry of a test archive, with every header field a test may want to bend.
 *
 * @property name the entry name, written byte for byte (one byte per character).
 * @property type the tar type flag byte, see the TYPE_ constants of [TarFixtures].
 * @property content the bytes of the body; the header declares this length unless [declaredSize] is set.
 * @property linkName the link target written to the header (used by link entries).
 * @property declaredSize when set, the size the header declares instead of the content length.
 * @property badChecksum when true the header checksum is wrong by one.
 * @property sizeFieldText when set, the 12 bytes written in the size field instead of an octal number.
 */
internal class TarEntrySpec(
    val name: String,
    val type: Byte = TarFixtures.TYPE_FILE,
    val content: ByteArray = ByteArray(0),
    val linkName: String = "",
    val declaredSize: Long? = null,
    val badChecksum: Boolean = false,
    val sizeFieldText: String? = null,
)

/**
 * Archives for the extraction tests, built in memory.
 *
 * The tar headers are written by hand: 512-byte ustar blocks with the
 * checksum computed over the block with its own field read as spaces. A
 * standard tar writer would tidy the name (strip a leading slash, turn a
 * backslash into a slash) and pad the archive to 10240 bytes, so it could not
 * carry the hostile names these tests need or give the exact stream length.
 * A name over 100 bytes is written the way GNU tar does it, as a preceding
 * entry of type 'L' whose body is the full name. The standard
 * bzip2 writer is used only to compress.
 *
 * Nothing here touches the disk, the network or a clock.
 */
internal object TarFixtures {
    const val TYPE_OLD_FILE: Byte = 0
    const val TYPE_FILE: Byte = 48
    const val TYPE_HARD_LINK: Byte = 49
    const val TYPE_SYMLINK: Byte = 50
    const val TYPE_CHAR_DEVICE: Byte = 51
    const val TYPE_BLOCK_DEVICE: Byte = 52
    const val TYPE_DIRECTORY: Byte = 53
    const val TYPE_FIFO: Byte = 54
    const val TYPE_SPARSE: Byte = 83
    const val TYPE_UNKNOWN: Byte = 90

    /** Bytes in one tar block. */
    const val BLOCK = 512

    /** The top directory name of the real small archive. */
    const val SMALL_TOP = "sherpa-onnx-streaming-zipformer-en-2023-06-21-mobile"

    /** The top directory name of the real tiny archive. */
    const val TINY_TOP = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"

    /** The four names the small profile writes, in role order. */
    val SMALL_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt",
    )

    /** The four names the tiny profile writes, in role order. */
    val TINY_FILES = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.onnx",
        "tokens.txt",
    )

    private const val TYPE_LONG_NAME: Byte = 76
    private const val NAME_FIELD = 100
    private const val BZ2_TRAILER_BYTES = 10

    // Entry order of the real listings, names relative to the top directory; "" is the top directory itself.
    private val SMALL_ORDER = listOf(
        "", "notes.md", "test_wavs/", "test_wavs/trans.txt", "test_wavs/8k.wav", "test_wavs/1.wav",
        "test_wavs/0.wav", "joiner-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
        "encoder-epoch-99-avg-1.onnx", "tokens.txt", "encoder-epoch-99-avg-1.int8.onnx", "README.md",
    )
    private val TINY_ORDER = listOf(
        "", "tokens.txt", "encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
        "test_wavs/", "test_wavs/8k.wav", "test_wavs/trans.txt", "test_wavs/0.wav", "test_wavs/1.wav",
        "export-onnx-en-20M.sh", "decoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.onnx", "README.md",
        "joiner-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.onnx",
    )

    // ---- entries ----

    fun file(name: String, content: ByteArray = ByteArray(0)): TarEntrySpec = TarEntrySpec(name, TYPE_FILE, content)

    fun dir(name: String): TarEntrySpec = TarEntrySpec(name, TYPE_DIRECTORY)

    fun symlink(name: String, target: String): TarEntrySpec = TarEntrySpec(name, TYPE_SYMLINK, linkName = target)

    fun hardlink(name: String, target: String): TarEntrySpec = TarEntrySpec(name, TYPE_HARD_LINK, linkName = target)

    fun special(name: String, type: Byte): TarEntrySpec = TarEntrySpec(name, type)

    /** Deterministic bytes that differ for every [label] and every position; the oracle for byte-exact checks. */
    fun contentOf(label: String, size: Int): ByteArray =
        ByteArray(size) { index -> ((label.hashCode() + index * 31) and 0x7f).toByte() }

    /** Deterministic bytes that do not compress, so a bzip2 stream of them has several blocks. */
    fun randomBytes(count: Int, seed: Long = 1L): ByteArray = ByteArray(count).also { Random(seed).nextBytes(it) }

    // ---- tar ----

    /** The tar bytes of [entries] followed by two zero blocks; no padding to a record size. */
    fun archiveOf(vararg entries: TarEntrySpec): ByteArray = archiveOf(entries.toList())

    /** The tar bytes of [entries] followed by [endBlocks] zero blocks (2 is a proper end; 0 leaves it open). */
    fun archiveOf(entries: List<TarEntrySpec>, endBlocks: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            out.write(headerBlocks(entry))
            out.write(entry.content)
            out.write(ByteArray(padding(entry.content.size.toLong())))
        }
        out.write(ByteArray(BLOCK * endBlocks))
        return out.toByteArray()
    }

    /** The entries of the real small listing, in order, with small bodies from [contentFor] (given the relative name). */
    fun smallEntries(contentFor: (String) -> ByteArray = { contentOf(it, 48) }): List<TarEntrySpec> =
        shaped(SMALL_TOP, SMALL_ORDER, contentFor)

    /** The entries of the real tiny listing, in order, with small bodies from [contentFor] (given the relative name). */
    fun tinyEntries(contentFor: (String) -> ByteArray = { contentOf(it, 48) }): List<TarEntrySpec> =
        shaped(TINY_TOP, TINY_ORDER, contentFor)

    /** A bzip2 archive with the real small shape. */
    fun smallArchive(contentFor: (String) -> ByteArray = { contentOf(it, 48) }): ByteArray =
        bz2(archiveOf(smallEntries(contentFor)))

    /** A bzip2 archive with the real tiny shape. */
    fun tinyArchive(contentFor: (String) -> ByteArray = { contentOf(it, 48) }): ByteArray =
        bz2(archiveOf(tinyEntries(contentFor)))

    // ---- bzip2 ----

    /** Compress [raw]; a [blockSize] of 1 means 100 kB blocks, so a few hundred kB of random bytes gives several blocks. */
    fun bz2(raw: ByteArray, blockSize: Int = 9): ByteArray = bz2Of(blockSize) { it.write(raw) }

    /** Compress whatever [write] puts on the stream, without holding it all in memory. */
    fun bz2Of(blockSize: Int = 9, write: (OutputStream) -> Unit): ByteArray {
        val sink = ByteArrayOutputStream()
        BZip2CompressorOutputStream(sink, blockSize).use { write(it) }
        return sink.toByteArray()
    }

    /** Write [count] zero bytes to [out] in 64 KiB chunks. */
    fun writeZeros(out: OutputStream, count: Long) {
        val chunk = ByteArray(64 * 1024)
        var left = count
        while (left > 0L) {
            val n = minOf(left, chunk.size.toLong()).toInt()
            out.write(chunk, 0, n)
            left -= n
        }
    }

    /**
     * A bzip2 archive that is a directory "top/" and then one honest file of [zeroBytes] zero bytes.
     * The zeros are never held in memory, so a large count is cheap to build and compresses to a few hundred bytes.
     */
    fun zerosArchive(zeroBytes: Long, name: String = "top/zeros.bin"): ByteArray = bz2Of(1) { out ->
        out.write(archiveOf(listOf(dir("top/")), endBlocks = 0))
        out.write(headerBlocks(TarEntrySpec(name, declaredSize = zeroBytes)))
        writeZeros(out, zeroBytes + padding(zeroBytes))
        out.write(ByteArray(2 * BLOCK))
    }

    /** Bytes that are not a bzip2 stream: they do not start with the bzip2 signature. */
    fun notBzip2(length: Int = 600): ByteArray {
        val text = "this is plain text and not a compressed stream\n".toByteArray(Charsets.US_ASCII)
        return ByteArray(length) { text[it % text.size] }
    }

    /** The first [keep] bytes of [bytes]. */
    fun cutAt(bytes: ByteArray, keep: Int): ByteArray {
        require(keep in 0..bytes.size) { "cut position $keep is outside 0..${bytes.size}" }
        return bytes.copyOf(keep)
    }

    /** [bz2] cut at half its length, so it ends inside a block. */
    fun cutInMiddle(bz2: ByteArray): ByteArray = cutAt(bz2, bz2.size / 2)

    /**
     * [bz2] without its last 10 bytes. A bzip2 stream ends with an 80-bit end marker and checksum
     * (padded to a byte), so this leaves every block whole and cuts only the end marker.
     */
    fun withoutTrailer(bz2: ByteArray): ByteArray = cutAt(bz2, bz2.size - BZ2_TRAILER_BYTES)

    /** [bz2] with every bit of the byte at [index] flipped; the default lies inside the first block. */
    fun withByteFlipped(bz2: ByteArray, index: Int = 60): ByteArray {
        require(index in 0 until bz2.size - BZ2_TRAILER_BYTES) { "index $index is not inside a block of ${bz2.size} bytes" }
        return bz2.copyOf().also { it[index] = (it[index].toInt() xor 0xff).toByte() }
    }

    /** A valid bzip2 stream holding a tar whose only header has a wrong checksum. */
    fun badChecksumArchive(): ByteArray = bz2(archiveOf(TarEntrySpec("top/a.bin", badChecksum = true)))

    /** A valid bzip2 stream holding a tar whose only header has letters in the size field. */
    fun badSizeFieldArchive(): ByteArray =
        bz2(archiveOf(TarEntrySpec("top/a.bin", sizeFieldText = "zzzzzzzzzzz\u0000")))

    // ---- private ----

    private fun shaped(top: String, order: List<String>, contentFor: (String) -> ByteArray): List<TarEntrySpec> =
        order.map { relative ->
            when {
                relative.isEmpty() -> dir("$top/")
                relative.endsWith("/") -> dir("$top/$relative")
                else -> file("$top/$relative", contentFor(relative))
            }
        }

    private fun padding(length: Long): Int = ((BLOCK - length % BLOCK) % BLOCK).toInt()

    /** The header block of [entry], preceded by a long-name entry when the name is over 100 bytes. */
    private fun headerBlocks(entry: TarEntrySpec): ByteArray {
        val nameBytes = entry.name.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        if (nameBytes.size > NAME_FIELD) {
            val body = nameBytes + byteArrayOf(0)
            out.write(headerBlock("././@LongLink".toByteArray(Charsets.ISO_8859_1), TYPE_LONG_NAME, body.size.toLong(), "", false, null))
            out.write(body)
            out.write(ByteArray(padding(body.size.toLong())))
        }
        val size = entry.declaredSize ?: entry.content.size.toLong()
        val sizeField = entry.sizeFieldText?.toByteArray(Charsets.ISO_8859_1)
        out.write(headerBlock(nameBytes, entry.type, size, entry.linkName, entry.badChecksum, sizeField))
        return out.toByteArray()
    }

    private fun headerBlock(
        name: ByteArray,
        type: Byte,
        size: Long,
        linkName: String,
        badChecksum: Boolean,
        sizeField: ByteArray?,
    ): ByteArray {
        val h = ByteArray(BLOCK)
        put(h, 0, name.copyOf(minOf(name.size, NAME_FIELD)))
        putOctal(h, 100, 8, 420L)
        putOctal(h, 108, 8, 0L)
        putOctal(h, 116, 8, 0L)
        if (sizeField != null) put(h, 124, sizeField.copyOf(12)) else putOctal(h, 124, 12, size)
        putOctal(h, 136, 12, 0L)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type
        put(h, 157, linkName.toByteArray(Charsets.ISO_8859_1).let { it.copyOf(minOf(it.size, NAME_FIELD)) })
        put(h, 257, "ustar".toByteArray(Charsets.US_ASCII))
        put(h, 263, "00".toByteArray(Charsets.US_ASCII))
        var sum = 0
        for (b in h) sum += b.toInt() and 0xff
        if (badChecksum) sum += 1
        put(h, 148, "%06o".format(sum).toByteArray(Charsets.US_ASCII))
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun put(block: ByteArray, offset: Int, bytes: ByteArray) {
        System.arraycopy(bytes, 0, block, offset, bytes.size)
    }

    // An octal number of width-1 digits followed by a NUL, the layout of the numeric header fields.
    private fun putOctal(block: ByteArray, offset: Int, width: Int, value: Long) {
        val digits = value.toString(8).padStart(width - 1, '0')
        require(digits.length == width - 1) { "value $value does not fit an octal field of $width bytes" }
        put(block, offset, digits.toByteArray(Charsets.US_ASCII))
        block[offset + width - 1] = 0
    }
}
