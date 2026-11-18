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

/**
 * Pins the unpack profile of the keyword spotting model against the measured archive
 * listing, and the file choice over names of the same shape. No engine for this model
 * is built yet; these tests only cover what is unpacked and which files are picked.
 */
class KeywordProfileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val top = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile"
    private val encoder = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private val encoderPlain = "encoder-epoch-12-avg-2-chunk-16-left-64.onnx"
    private val decoder = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
    private val joiner = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"

    private val sizes = mapOf(encoder to 4_000_908L, encoderPlain to 11_525_206L, decoder to 1_063_189L, joiner to 163_380L)

    private fun profile(): ExtractionProfile {
        val found = ExtractionProfiles.forModel(KEYWORD_MODEL_ID)
        assertNotNull("stt-ondevice keyword profile: the keyword id must have a profile", found)
        return found!!
    }

    @Test
    fun `the keyword id is the stated registry id`() {
        assertEquals("kws-gigaspeech-3m", KEYWORD_MODEL_ID)
        assertTrue(LocalModelStore.isSafeName(KEYWORD_MODEL_ID))
    }

    @Test
    fun `the profile writes exactly the int8 encoder the plain decoder the int8 joiner and the token table`() {
        assertEquals(listOf(encoder, decoder, joiner, "tokens.txt"), profile().files)
    }

    @Test
    fun `the allow list leaves out the plain encoder the sentencepiece model and the sample recordings`() {
        val files = profile().files
        assertFalse("stt-ondevice keyword profile: the plain encoder must not be written", encoderPlain in files)
        assertEquals(4, files.toSet().size)
        for (name in files) {
            assertFalse("stt-ondevice keyword profile: $name is not an allowed name", name.contains("bpe") || name.contains("wav") || name.contains("test_wavs"))
        }
    }

    @Test
    fun `an unpack of a listing of the real shape writes only the four files`() {
        val entries = listOf(
            TarFixtures.dir("$top/"),
            TarFixtures.file("$top/$encoder", TarFixtures.contentOf("e", 48)),
            TarFixtures.file("$top/$encoderPlain", TarFixtures.contentOf("p", 48)),
            TarFixtures.file("$top/$decoder", TarFixtures.contentOf("d", 48)),
            TarFixtures.file("$top/$joiner", TarFixtures.contentOf("j", 48)),
            TarFixtures.file("$top/tokens.txt", TarFixtures.contentOf("t", 48)),
            TarFixtures.file("$top/bpe.model", TarFixtures.contentOf("b", 48)),
            TarFixtures.file("$top/keywords.txt", TarFixtures.contentOf("k", 48)),
            TarFixtures.file("$top/README.md", TarFixtures.contentOf("r", 48)),
            TarFixtures.dir("$top/test_wavs/"),
            TarFixtures.file("$top/test_wavs/0.wav", TarFixtures.contentOf("w", 48)),
        )
        val archive = File(tmp.newFolder(), "a.bin")
        archive.writeBytes(TarFixtures.bz2(TarFixtures.archiveOf(entries)))
        val store = LocalModelStore(tmp.newFolder())
        val outcome = ModelExtractor(usableSpace = { Long.MAX_VALUE }).extract(archive, KEYWORD_MODEL_ID, store)
        assertTrue("stt-ondevice keyword profile: expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        assertEquals(profile().files.sorted(), store.extractedDirectory(KEYWORD_MODEL_ID).list()!!.sorted())
    }

    @Test
    fun `an unpack of a listing without the int8 encoder is rejected as missing`() {
        val entries = listOf(
            TarFixtures.dir("$top/"),
            TarFixtures.file("$top/$encoderPlain", TarFixtures.contentOf("p", 48)),
            TarFixtures.file("$top/$decoder", TarFixtures.contentOf("d", 48)),
            TarFixtures.file("$top/$joiner", TarFixtures.contentOf("j", 48)),
            TarFixtures.file("$top/tokens.txt", TarFixtures.contentOf("t", 48)),
        )
        val archive = File(tmp.newFolder(), "a.bin")
        archive.writeBytes(TarFixtures.bz2(TarFixtures.archiveOf(entries)))
        val outcome = ModelExtractor(usableSpace = { Long.MAX_VALUE }).extract(archive, KEYWORD_MODEL_ID, LocalModelStore(tmp.newFolder()))
        assertTrue("stt-ondevice keyword profile: expected Rejected but got $outcome", outcome is ExtractionOutcome.Rejected)
        assertEquals(ExtractionReason.MISSING_FILE, (outcome as ExtractionOutcome.Rejected).reason)
    }

    @Test
    fun `the limits are stated figures above the measured sizes and below twice them`() {
        val limits = profile().limits
        assertEquals(
            ExtractionLimits(
                maxEntries = 32,
                maxEntryBytes = 16_777_216L,
                maxWrittenBytes = 8_388_608L,
                maxStreamBytes = 33_554_432L,
                maxPathLength = 128,
                maxDepth = 4,
            ),
            limits,
        )
        val written = sizes.filterKeys { it != encoderPlain }.values.sum() + 5_006L
        assertEquals("written total of the four files", 5_232_483L, written)
        assertTrue(written < limits.maxWrittenBytes && limits.maxWrittenBytes < 2L * written)
        val largest = sizes.values.max()
        assertEquals("largest entry is the plain encoder", 11_525_206L, largest)
        assertTrue(largest < limits.maxEntryBytes && limits.maxEntryBytes < 2L * largest)
        val listed = 17_003_754L
        assertTrue(listed < limits.maxStreamBytes && limits.maxStreamBytes < 2L * listed)
        assertTrue("longest name is 109 characters", "$top/$encoder".length < limits.maxPathLength)
        assertEquals(109, "$top/$encoder".length)
        assertTrue("depth of a recording is 3", 3 < limits.maxDepth)
    }

    @Test
    fun `the locator picks the int8 encoder the plain decoder and the int8 joiner over made up names of the real shape`() {
        val made = listOf(
            "joiner-epoch-7-avg-3-chunk-8-left-32.int8.onnx",
            "tokens.txt",
            "encoder-epoch-7-avg-3-chunk-8-left-32.onnx",
            "decoder-epoch-7-avg-3-chunk-8-left-32.onnx",
            "encoder-epoch-7-avg-3-chunk-8-left-32.int8.onnx",
            "bpe.model",
        )
        for ((label, listing) in listOf("listed" to made, "reversed" to made.reversed(), "sorted" to made.sorted())) {
            val choice = TransducerFileLocator.choose(listing)
            assertNotNull("$label: a choice was expected", choice)
            assertEquals("$label: encoder", "encoder-epoch-7-avg-3-chunk-8-left-32.int8.onnx", choice!!.encoder)
            assertEquals("$label: decoder", "decoder-epoch-7-avg-3-chunk-8-left-32.onnx", choice.decoder)
            assertEquals("$label: joiner", "joiner-epoch-7-avg-3-chunk-8-left-32.int8.onnx", choice.joiner)
            assertEquals("$label: tokens", "tokens.txt", choice.tokens)
        }
    }

    @Test
    fun `the locator picks exactly the files the keyword profile writes`() {
        val written = profile().files
        for ((label, listing) in listOf("role order" to written, "reversed" to written.reversed(), "sorted" to written.sorted())) {
            val choice = TransducerFileLocator.choose(listing)
            assertNotNull("$label: a choice was expected among $listing", choice)
            assertEquals("$label: encoder", profile().encoder, choice!!.encoder)
            assertEquals("$label: decoder", profile().decoder, choice.decoder)
            assertEquals("$label: joiner", profile().joiner, choice.joiner)
            assertEquals("$label: tokens", profile().tokens, choice.tokens)
        }
    }

    @Test
    fun `a directory with the plain encoder and the int8 encoder picks the int8 one`() {
        val dir = tmp.newFolder()
        for (name in listOf(encoder, encoderPlain, decoder, joiner, "tokens.txt")) File(dir, name).writeText("x")
        val files = TransducerFileLocator.locate(dir)
        assertNotNull("stt-ondevice keyword profile: the directory must be located", files)
        assertEquals(encoder, files!!.encoder.name)
        assertEquals(decoder, files.decoder.name)
        assertEquals(joiner, files.joiner.name)
    }

    @Test
    fun `the streaming table still holds only the two speech ids while the unpack table also knows the keyword id`() {
        assertEquals(setOf("small", "tiny"), StreamingProfiles.ids)
        assertNull("stt-ondevice keyword profile: no streaming profile for the keyword id", StreamingProfiles.forModel(KEYWORD_MODEL_ID))
        assertNotNull(ExtractionProfiles.forModel("small"))
        assertNotNull(ExtractionProfiles.forModel("tiny"))
    }

    @Test
    fun `speech profiles are unchanged and ids near the keyword id have no profile`() {
        assertEquals(
            listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
            ExtractionProfiles.forModel("small")!!.files,
        )
        assertEquals(
            listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt"),
            ExtractionProfiles.forModel("tiny")!!.files,
        )
        for (id in listOf("KWS-GIGASPEECH-3M", "kws-gigaspeech-3m ", " kws-gigaspeech-3m", "kws-gigaspeech", "kws-gigaspeech-3", "")) {
            assertNull("stt-ondevice keyword profile: [$id] must have no profile", ExtractionProfiles.forModel(id))
        }
    }
}
