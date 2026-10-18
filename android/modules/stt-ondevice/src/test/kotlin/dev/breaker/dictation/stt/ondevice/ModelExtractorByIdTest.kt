package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The entry points that take a model id and a store instead of explicit places and limits. */
class ModelExtractorByIdTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extracting by model id writes into the store directories`() {
        for ((id, files) in listOf("tiny" to TarFixtures.TINY_FILES, "small" to TarFixtures.SMALL_FILES)) {
            val store = LocalModelStore(tmp.newFolder())
            val archive = File(tmp.newFolder(), "a.bin")
            archive.writeBytes(if (id == "tiny") TarFixtures.tinyArchive() else TarFixtures.smallArchive())
            val outcome = ModelExtractor(usableSpace = { Long.MAX_VALUE }).extract(archive, id, store)
            assertTrue("$id: expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
            assertEquals("$id: directory", store.extractedDirectory(id), (outcome as ExtractionOutcome.Extracted).directory)
            assertEquals("$id: files", files.sorted(), store.extractedDirectory(id).list()!!.sorted())
            assertFalse("$id: work directory is gone", store.extractionWorkDirectory(id).exists())
        }
    }

    @Test
    fun `supports is true for the two profiled models and false for any other id`() {
        val extractor = ModelExtractor()
        assertTrue(extractor.supports("tiny"))
        assertTrue(extractor.supports("small"))
        for (other in listOf("base", "medium", "", "Tiny", "tiny ")) {
            assertFalse("'$other' has no profile", extractor.supports(other))
        }
    }

    @Test
    fun `a model id without a profile is rejected and touches nothing`() {
        val root = tmp.newFolder()
        val archive = File(tmp.newFolder(), "a.bin")
        archive.writeBytes(TarFixtures.tinyArchive())
        val outcome = ModelExtractor().extract(archive, "base", LocalModelStore(root))
        assertTrue("expected Rejected but got $outcome", outcome is ExtractionOutcome.Rejected)
        assertEquals(ExtractionFault.ARCHIVE, (outcome as ExtractionOutcome.Rejected).reason.fault)
        assertEquals("the store root stays empty", 0, root.list()!!.size)
        assertArrayEquals(TarFixtures.tinyArchive(), archive.readBytes())
    }
}
