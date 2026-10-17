package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelFamily
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the profiles against the real archive listings.
 *
 * Every figure below is written out as a literal, read from the file listings
 * of the two real archives (entry order, sizes in bytes, directories as 0).
 * It also pins the table of refusal reasons and who is at fault for each.
 */
class ExtractionProfilesTest {

    private val mib = 1_048_576L

    private val smallListingSizes = listOf(
        0L, 209L, 0L, 449L, 77_244L, 534_924L, 212_044L, 259_335L, 2_092_566L, 276_577_303L, 5_048L, 111_205_459L, 231L,
    )
    private val tinyListingSizes = listOf(
        0L, 5_048L, 42_845_182L, 539_499L, 0L, 77_244L, 449L, 212_044L, 534_924L, 998L, 2_092_272L, 88_804_590L, 304L,
        259_572L, 1_026_462L,
    )

    private val smallFileSizes = mapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to 111_205_459L,
        "decoder-epoch-99-avg-1.onnx" to 2_092_566L,
        "joiner-epoch-99-avg-1.int8.onnx" to 259_335L,
        "tokens.txt" to 5_048L,
    )
    private val tinyFileSizes = mapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to 42_845_182L,
        "decoder-epoch-99-avg-1.onnx" to 2_092_272L,
        "joiner-epoch-99-avg-1.onnx" to 1_026_462L,
        "tokens.txt" to 5_048L,
    )

    private fun small(): ExtractionProfile {
        val profile = ExtractionProfiles.forModel("small")
        assertNotNull("the small model must have a profile", profile)
        return profile!!
    }

    private fun tiny(): ExtractionProfile {
        val profile = ExtractionProfiles.forModel("tiny")
        assertNotNull("the tiny model must have a profile", profile)
        return profile!!
    }

    /** Size of a tar stream: a 512-byte header and the body padded to 512 for each entry, then two zero blocks. */
    private fun tarStreamBytes(sizes: List<Long>): Long =
        sizes.sumOf { 512L + (it + 511L) / 512L * 512L } + 1024L

    // --- file names ---

    @Test
    fun `the small profile writes exactly four distinct files in role order`() {
        val profile = small()
        assertEquals("encoder", "encoder-epoch-99-avg-1.int8.onnx", profile.encoder)
        assertEquals("decoder", "decoder-epoch-99-avg-1.onnx", profile.decoder)
        assertEquals("joiner", "joiner-epoch-99-avg-1.int8.onnx", profile.joiner)
        assertEquals("tokens", "tokens.txt", profile.tokens)
        assertEquals(
            "files in role order",
            listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"),
            profile.files,
        )
        assertEquals("distinct names", 4, profile.files.toSet().size)
    }

    @Test
    fun `the tiny profile writes exactly four distinct files in role order`() {
        val profile = tiny()
        assertEquals("encoder", "encoder-epoch-99-avg-1.int8.onnx", profile.encoder)
        assertEquals("decoder", "decoder-epoch-99-avg-1.onnx", profile.decoder)
        assertEquals("joiner", "joiner-epoch-99-avg-1.onnx", profile.joiner)
        assertEquals("tokens", "tokens.txt", profile.tokens)
        assertEquals(
            "files in role order",
            listOf("encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.onnx", "tokens.txt"),
            profile.files,
        )
        assertEquals("distinct names", 4, profile.files.toSet().size)
    }

    @Test
    fun `every file name is a plain name with no separator`() {
        val plain = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        for (profile in listOf(small(), tiny())) {
            for (name in profile.files) {
                assertTrue("[$name] must be a plain file name", plain.matches(name))
            }
        }
    }

    // --- limits ---

    @Test
    fun `the small limits equal the stated figures`() {
        assertEquals(
            ExtractionLimits(
                maxEntries = 32,
                maxEntryBytes = 314_572_800L,
                maxWrittenBytes = 167_772_160L,
                maxStreamBytes = 469_762_048L,
                maxPathLength = 128,
                maxDepth = 4,
            ),
            small().limits,
        )
        assertEquals("entry limit is 300 MiB", 300L * mib, small().limits.maxEntryBytes)
        assertEquals("written limit is 160 MiB", 160L * mib, small().limits.maxWrittenBytes)
        assertEquals("stream limit is 448 MiB", 448L * mib, small().limits.maxStreamBytes)
    }

    @Test
    fun `the tiny limits equal the stated figures`() {
        assertEquals(
            ExtractionLimits(
                maxEntries = 32,
                maxEntryBytes = 134_217_728L,
                maxWrittenBytes = 83_886_080L,
                maxStreamBytes = 167_772_160L,
                maxPathLength = 128,
                maxDepth = 4,
            ),
            tiny().limits,
        )
        assertEquals("entry limit is 128 MiB", 128L * mib, tiny().limits.maxEntryBytes)
        assertEquals("written limit is 80 MiB", 80L * mib, tiny().limits.maxWrittenBytes)
        assertEquals("stream limit is 160 MiB", 160L * mib, tiny().limits.maxStreamBytes)
    }

    // --- limits against the real archives ---

    @Test
    fun `the listing figures used here match the real listings`() {
        assertEquals("small entry count", 13, smallListingSizes.size)
        assertEquals("tiny entry count", 15, tinyListingSizes.size)
        assertEquals("small content", 390_964_812L, smallListingSizes.sum())
        assertEquals("tiny content", 136_398_588L, tinyListingSizes.sum())
        assertEquals("small written total", 113_562_408L, smallFileSizes.values.sum())
        assertEquals("tiny written total", 45_968_964L, tinyFileSizes.values.sum())
        assertEquals("small tar stream", 390_975_488L, tarStreamBytes(smallListingSizes))
        assertEquals("tiny tar stream", 136_409_088L, tarStreamBytes(tinyListingSizes))
    }

    @Test
    fun `the real written total and every real file are inside the small limits`() {
        val limits = small().limits
        assertTrue("written total 113562408 must be below ${limits.maxWrittenBytes}", 113_562_408L < limits.maxWrittenBytes)
        for ((name, size) in smallFileSizes) {
            assertTrue("$name ($size bytes) must be below ${limits.maxEntryBytes}", size < limits.maxEntryBytes)
        }
    }

    @Test
    fun `the real written total and every real file are inside the tiny limits`() {
        val limits = tiny().limits
        assertTrue("written total 45968964 must be below ${limits.maxWrittenBytes}", 45_968_964L < limits.maxWrittenBytes)
        for ((name, size) in tinyFileSizes) {
            assertTrue("$name ($size bytes) must be below ${limits.maxEntryBytes}", size < limits.maxEntryBytes)
        }
    }

    @Test
    fun `every real entry including the skipped ones is under the entry limit`() {
        assertTrue("small: largest entry 276577303", 276_577_303L < small().limits.maxEntryBytes)
        assertTrue("tiny: largest entry 88804590", 88_804_590L < tiny().limits.maxEntryBytes)
        assertEquals("small largest entry", 276_577_303L, smallListingSizes.max())
        assertEquals("tiny largest entry", 88_804_590L, tinyListingSizes.max())
    }

    @Test
    fun `the real tar stream is inside the stream limit even padded to 10240 byte records`() {
        val smallPadded = (tarStreamBytes(smallListingSizes) + 10_239L) / 10_240L * 10_240L
        val tinyPadded = (tarStreamBytes(tinyListingSizes) + 10_239L) / 10_240L * 10_240L
        assertEquals("small padded stream", 390_983_680L, smallPadded)
        assertEquals("tiny padded stream", 136_417_280L, tinyPadded)
        assertTrue("small stream $smallPadded", smallPadded < small().limits.maxStreamBytes)
        assertTrue("tiny stream $tinyPadded", tinyPadded < tiny().limits.maxStreamBytes)
    }

    @Test
    fun `each byte limit is below twice the real figure`() {
        assertTrue("small entry", small().limits.maxEntryBytes < 2L * 276_577_303L)
        assertTrue("small written", small().limits.maxWrittenBytes < 2L * 113_562_408L)
        assertTrue("small stream", small().limits.maxStreamBytes < 2L * 390_983_680L)
        assertTrue("tiny entry", tiny().limits.maxEntryBytes < 2L * 88_804_590L)
        assertTrue("tiny written", tiny().limits.maxWrittenBytes < 2L * 45_968_964L)
        assertTrue("tiny stream", tiny().limits.maxStreamBytes < 2L * 136_417_280L)
    }

    @Test
    fun `the entry count name length and depth limits are above the real figures`() {
        // small: 13 entries, longest name 85 characters, depth 3; tiny: 15 entries, 82 characters, depth 3.
        for (limits in listOf(small().limits, tiny().limits)) {
            assertTrue("entries", 15 < limits.maxEntries)
            assertTrue("name length", 85 < limits.maxPathLength)
            assertTrue("depth", 3 < limits.maxDepth)
        }
    }

    // --- which ids have a profile ---

    @Test
    fun `only small and tiny have a profile`() {
        assertNotNull(ExtractionProfiles.forModel("small"))
        assertNotNull(ExtractionProfiles.forModel("tiny"))
        for (id in listOf("base", "medium", "", " ", "Small", "SMALL", "Tiny", "small ", " tiny", "small.en", "../small", "small/", "whisper", "x")) {
            assertNull("[$id] must have no profile", ExtractionProfiles.forModel(id))
        }
    }

    @Test
    fun `a model has a profile exactly when the registry says it is a sherpa-onnx model`() {
        for (entry in ModelRegistry.ALL) {
            val has = ExtractionProfiles.forModel(entry.id) != null
            assertEquals("profile of ${entry.id} (${entry.family})", entry.family == ModelFamily.SHERPA_ONNX, has)
        }
        assertNotNull("small is in the registry", ModelRegistry.byId("small"))
        assertNotNull("tiny is in the registry", ModelRegistry.byId("tiny"))
    }

    // --- the refusal table ---

    @Test
    fun `the refusal reasons are the stated table in order with the stated fault`() {
        val table = listOf(
            "ABSOLUTE_PATH" to "ARCHIVE", "PARENT_SEGMENT" to "ARCHIVE", "BAD_NAME" to "ARCHIVE",
            "PATH_TOO_LONG" to "ARCHIVE", "TOO_DEEP" to "ARCHIVE", "SYMLINK" to "ARCHIVE",
            "HARDLINK" to "ARCHIVE", "NOT_REGULAR" to "ARCHIVE", "TOO_MANY_ENTRIES" to "ARCHIVE",
            "ENTRY_TOO_LARGE" to "ARCHIVE", "WRITTEN_TOO_LARGE" to "ARCHIVE", "STREAM_TOO_LARGE" to "ARCHIVE",
            "STREAM_ERROR" to "ARCHIVE", "TRUNCATED" to "ARCHIVE", "DUPLICATE_NAME" to "ARCHIVE",
            "OUTSIDE_TOP" to "ARCHIVE", "MISSING_FILE" to "ARCHIVE", "NO_SPACE" to "SPACE",
            "WRITE_ERROR" to "LOCAL", "COMMIT_FAILED" to "LOCAL", "TARGET_EXISTS" to "LOCAL",
        )
        val actual = ExtractionReason.values().map { it.name to it.fault.name }
        assertEquals("reasons and faults in declaration order", table, actual)
        assertEquals("fault values in order", listOf("ARCHIVE", "LOCAL", "SPACE"), ExtractionFault.values().map { it.name })
    }

    @Test
    fun `asking twice gives equal profiles`() {
        assertEquals(ExtractionProfiles.forModel("small"), ExtractionProfiles.forModel("small"))
        assertEquals(ExtractionProfiles.forModel("tiny"), ExtractionProfiles.forModel("tiny"))
    }
}
