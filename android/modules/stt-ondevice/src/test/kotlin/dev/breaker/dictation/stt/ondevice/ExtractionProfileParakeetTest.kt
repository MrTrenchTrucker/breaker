package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the parakeet unpack profile against the real archive listing.
 *
 * Every figure is a literal read from the file listing of the real archive (entry
 * order, sizes in bytes, directories as 0). The profile only unpacks: nothing here
 * runs a recogniser, and the Kotlin is not compiled where it was written.
 */
class ExtractionProfileParakeetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mib = 1_048_576L
    private val top = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

    /** Entry names of the real listing, in order, relative to the top directory; "" is the top directory. */
    private val listingNames = listOf(
        "", "tokens.txt", "README.md", "joiner.int8.onnx", "decoder.int8.onnx", "encoder.int8.onnx",
        "test_wavs/", "test_wavs/en.wav", "test_wavs/fr.wav", "test_wavs/es.wav", "test_wavs/de.wav",
    )
    private val listingSizes = listOf(
        0L, 93_939L, 4_905L, 6_355_277L, 11_845_274L, 652_282_294L, 0L, 184_608L, 219_180L, 235_052L, 121_388L,
    )
    private val fileNames = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
    private val fileSizes = listOf(652_282_294L, 11_845_274L, 6_355_277L, 93_939L)
    private val realLimits = ExtractionLimits(
        maxEntries = 32,
        maxEntryBytes = 671_088_640L,
        maxWrittenBytes = 738_197_504L,
        maxStreamBytes = 754_974_720L,
        maxPathLength = 128,
        maxDepth = 4,
    )

    private fun parakeet(): ExtractionProfile {
        val profile = ExtractionProfiles.forModel("parakeet")
        assertNotNull("the parakeet model must have a profile", profile)
        return profile!!
    }

    /** Size of a tar stream: a 512-byte header and the body padded to 512 for each entry, then two zero blocks. */
    private fun tarStreamBytes(sizes: List<Long>): Long = sizes.sumOf { 512L + (it + 511L) / 512L * 512L } + 1024L

    private fun paddedStreamBytes(): Long = (tarStreamBytes(listingSizes) + 10_239L) / 10_240L * 10_240L

    /** Which of the bounds a limit set puts at or below the real listing; empty means the real archive fits. */
    private fun violations(l: ExtractionLimits): List<String> {
        val bad = mutableListOf<String>()
        if (l.maxEntryBytes <= listingSizes.max()) bad.add("entry")
        if (l.maxWrittenBytes <= fileSizes.sum()) bad.add("written")
        if (l.maxStreamBytes <= paddedStreamBytes()) bad.add("stream")
        if (l.maxEntries <= listingNames.size) bad.add("entries")
        if (l.maxPathLength <= listingNames.maxOf { top.length + 1 + it.length }) bad.add("name")
        if (l.maxDepth <= 3) bad.add("depth")
        return bad
    }

    // --- names ---

    @Test
    fun `the parakeet profile writes exactly the four int8 names in role order`() {
        val profile = parakeet()
        assertEquals("encoder", "encoder.int8.onnx", profile.encoder)
        assertEquals("decoder", "decoder.int8.onnx", profile.decoder)
        assertEquals("joiner", "joiner.int8.onnx", profile.joiner)
        assertEquals("tokens", "tokens.txt", profile.tokens)
        assertEquals("files in role order", fileNames, profile.files)
        assertEquals("distinct names", 4, profile.files.toSet().size)
        for (skipped in listOf("README.md", "test_wavs", "en.wav", "fr.wav", "es.wav", "de.wav")) {
            assertFalse("$skipped must not be a profile name", profile.files.any { it.contains(skipped) })
        }
    }

    @Test
    fun `the locator chooses the same four names the profile writes`() {
        val profile = parakeet()
        for ((label, listing) in listOf("role order" to profile.files, "reversed" to profile.files.reversed())) {
            val choice = TransducerFileLocator.choose(listing)
            assertNotNull("$label: the locator found no choice among $listing", choice)
            assertEquals("$label: encoder", profile.encoder, choice!!.encoder)
            assertEquals("$label: decoder", profile.decoder, choice.decoder)
            assertEquals("$label: joiner", profile.joiner, choice.joiner)
            assertEquals("$label: tokens", profile.tokens, choice.tokens)
        }
    }

    @Test
    fun `only the exact id parakeet has this profile and it differs from the other speech profiles`() {
        assertNotNull(ExtractionProfiles.forModel("parakeet"))
        for (id in listOf("Parakeet", "PARAKEET", " parakeet", "parakeet ", "parakeet/", "../parakeet", "parakeet-v3")) {
            assertNull("[$id] must have no profile", ExtractionProfiles.forModel(id))
        }
        assertNotEquals(ExtractionProfiles.forModel("small"), parakeet())
        assertNotEquals(ExtractionProfiles.forModel("tiny"), parakeet())
        assertEquals(parakeet(), ExtractionProfiles.forModel("parakeet"))
    }

    // --- limits ---

    @Test
    fun `the parakeet limits equal the stated figures`() {
        assertEquals(realLimits, parakeet().limits)
        assertEquals("entry limit is 640 MiB", 640L * mib, parakeet().limits.maxEntryBytes)
        assertEquals("written limit is 704 MiB", 704L * mib, parakeet().limits.maxWrittenBytes)
        assertEquals("stream limit is 720 MiB", 720L * mib, parakeet().limits.maxStreamBytes)
    }

    @Test
    fun `the parakeet profile is the registry entry data, 640 704 and 720 MiB and 32 entries`() {
        val registry = ModelRegistry.PARAKEET.limits
        assertNotNull("the registry entry carries limits", registry)
        // The registry data itself, pinned: a change to the entry goes red here by name.
        assertEquals("entry bytes 640 MiB", 640L * mib, registry!!.maxEntryBytes)
        assertEquals("written bytes 704 MiB", 704L * mib, registry.maxWrittenBytes)
        assertEquals("stream bytes 720 MiB", 720L * mib, registry.maxStreamBytes)
        assertEquals("entries", 32, registry.maxEntries)
        // The production profile, read from the profile and not from a constant of this test.
        val limits = parakeet().limits
        assertEquals(registry.maxEntryBytes, limits.maxEntryBytes)
        assertEquals(registry.maxWrittenBytes, limits.maxWrittenBytes)
        assertEquals(registry.maxStreamBytes, limits.maxStreamBytes)
        assertEquals(registry.maxEntries, limits.maxEntries)
        assertEquals(registry.maxDepth, limits.maxDepth)
        val names = ModelRegistry.PARAKEET.files
        assertNotNull("the registry entry carries file names", names)
        assertEquals(listOf(names!!.encoder, names.decoder, names.joiner, names.tokens), parakeet().files)
    }

    @Test
    fun `the limits sit above every measured figure`() {
        val limits = parakeet().limits
        assertTrue("entry ${limits.maxEntryBytes} above 652282294", limits.maxEntryBytes > 652_282_294L)
        assertTrue("written ${limits.maxWrittenBytes} above 670576784", limits.maxWrittenBytes > 670_576_784L)
        assertTrue("stream ${limits.maxStreamBytes} above 671354880", limits.maxStreamBytes > 671_354_880L)
        assertTrue("entries ${limits.maxEntries} above 11", limits.maxEntries > 11)
        assertTrue("name length ${limits.maxPathLength} above 60", limits.maxPathLength > 60)
        assertTrue("depth ${limits.maxDepth} above 3", limits.maxDepth > 3)
        assertEquals("nothing the real archive needs is refused", emptyList<String>(), violations(limits))
    }

    @Test
    fun `each byte limit is below twice the real figure`() {
        val limits = parakeet().limits
        assertTrue("entry", limits.maxEntryBytes < 2L * 652_282_294L)
        assertTrue("written", limits.maxWrittenBytes < 2L * 670_576_784L)
        assertTrue("stream", limits.maxStreamBytes < 2L * 671_354_880L)
    }

    @Test
    fun `the small and tiny limits do not fit the parakeet listing`() {
        assertEquals(listOf("entry", "written", "stream"), violations(ExtractionProfiles.forModel("small")!!.limits))
        assertEquals(listOf("entry", "written", "stream"), violations(ExtractionProfiles.forModel("tiny")!!.limits))
    }

    @Test
    fun `the extractor reads the written-bytes bound from the profile`() {
        val extractor = ModelExtractor()
        assertEquals(738_197_504L, extractor.unpackedBytesAllowed("parakeet"))
        assertEquals(167_772_160L, extractor.unpackedBytesAllowed("small"))
        assertNull(extractor.unpackedBytesAllowed("base"))
        assertNull(extractor.unpackedBytesAllowed("unknown"))
    }

    // --- the extractor on a parakeet shaped archive ---

    /** The real entry order with made-up small bodies; sizes by name. */
    private fun shapedEntries(): List<TarEntrySpec> {
        val sizes = mapOf("tokens.txt" to 50, "encoder.int8.onnx" to 300, "decoder.int8.onnx" to 200, "joiner.int8.onnx" to 100)
        return listingNames.map { relative ->
            when {
                relative.isEmpty() -> TarFixtures.dir("$top/")
                relative.endsWith("/") -> TarFixtures.dir("$top/$relative")
                else -> TarFixtures.file("$top/$relative", TarFixtures.contentOf(relative, sizes[relative] ?: 30))
            }
        }
    }

    private fun rigFor(entries: List<TarEntrySpec>, limits: ExtractionLimits = parakeet().limits): ExtractorRig {
        val rig = ExtractorRig(tmp.newFolder(), bz(entries), files = parakeet().files, limits = limits)
        assertEquals("the rig unpacks with the real profile files", parakeet().files, rig.profile.files)
        return rig
    }

    @Test
    fun `a parakeet shaped archive extracts exactly the four files and skips the readme and the recordings`() {
        val rig = rigFor(shapedEntries())
        assertEquals("the rig profile is the real profile", parakeet(), rig.profile)
        val outcome = rig.run()
        assertTrue("expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        val done = outcome as ExtractionOutcome.Extracted
        assertEquals("file count", 4, done.fileCount)
        assertEquals("bytes written", 650L, done.bytes)
        assertEquals("names in the target", fileNames.sorted(), rig.target.list()!!.sorted())
        assertFalse("README.md must not be written", File(rig.target, "README.md").exists())
        assertFalse("test_wavs must not be written", File(rig.target, "test_wavs").exists())
        assertEquals("only the four files were opened for writing", 4, rig.outputs.size)
        assertEquals("one sync per written file", fileNames.sorted(), rig.synced.sorted())
    }

    @Test
    fun `every entry of the listing counts against the entry limit, the skipped ones too`() {
        val atEleven = rigFor(shapedEntries(), parakeet().limits.copy(maxEntries = 11))
        val outcome = atEleven.run()
        assertTrue("11 entries under a limit of 11: $outcome", outcome is ExtractionOutcome.Extracted)
        val atTen = rigFor(shapedEntries(), parakeet().limits.copy(maxEntries = 10))
        atTen.expectRejected(ExtractionReason.TOO_MANY_ENTRIES, "11 entries under a limit of 10")
    }

    @Test
    fun `an encoder entry of the real size passes the entry limit and one byte over it does not`() {
        fun encoderOnly(size: Long) = listOf(TarFixtures.dir("$top/"), TarEntrySpec("$top/encoder.int8.onnx", declaredSize = size))
        val over = rigFor(encoderOnly(parakeet().limits.maxEntryBytes + 1L))
        over.expectRejected(ExtractionReason.ENTRY_TOO_LARGE, "one byte over the entry limit")

        // The body is missing, so the unpack fails later; what matters is that the size rule let it through.
        val real = rigFor(encoderOnly(652_282_294L))
        val outcome = real.run()
        assertTrue("expected a refusal for the missing body but got $outcome", outcome is ExtractionOutcome.Rejected)
        assertNotEquals(
            "the real encoder size is inside the entry limit",
            ExtractionReason.ENTRY_TOO_LARGE,
            (outcome as ExtractionOutcome.Rejected).reason,
        )

        // Control: the small limits refuse that same real size by the entry rule.
        val small = rigFor(encoderOnly(652_282_294L), ExtractionProfiles.forModel("small")!!.limits)
        small.expectRejected(ExtractionReason.ENTRY_TOO_LARGE, "the small limits on the real encoder size")
    }
}
