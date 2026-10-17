package dev.breaker.dictation.stt.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A directory entry may be written with or without its closing slash. Once the name is
 * normalised both spellings are one name, so a second entry under the other spelling is
 * a repeat. Every pair below uses an entry type that the tar reader cannot rewrite
 * (a plain file keeps its name as written), so the two spellings really reach the
 * extractor as two different strings.
 */
class ModelExtractorDirectoryNameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = TarFixtures.contentOf("payload", 24)

    private fun refused(entries: List<TarEntrySpec>, label: String) =
        ExtractorRig(tmp.newFolder(), bz(entries)).expectRejected(ExtractionReason.DUPLICATE_NAME, label)

    private fun extractedFrom(entries: List<TarEntrySpec>, label: String) {
        val rig = ExtractorRig(tmp.newFolder(), bz(entries))
        val outcome = rig.run()
        assertTrue("$label: expected Extracted but got $outcome", outcome is ExtractionOutcome.Extracted)
        assertEquals("$label: only the four profile files are written", TarFixtures.TINY_FILES.sorted(), rig.target.list()!!.sorted())
    }

    @Test
    fun `a file named like a directory entry but without its slash is a repeat in either order`() {
        val directory = TarFixtures.dir("top/sub/")
        val plain = TarFixtures.file("top/sub", bytes)
        refused(topWith(extra = listOf(directory, plain)), "directory with its slash, then the plain name")
        refused(topWith(extra = listOf(plain, directory)), "plain name, then the directory with its slash")
    }

    @Test
    fun `a directory entry with a slash named like a profile file is a repeat of that file in either order`() {
        val tokensDirectory = TarFixtures.dir("top/tokens.txt/")
        refused(topWith(extra = listOf(tokensDirectory)), "directory after the profile files")
        val before = listOf(TarFixtures.dir("top/"), tokensDirectory) +
            TarFixtures.TINY_FILES.map { TarFixtures.file("top/$it", TarFixtures.contentOf(it, 40)) }
        refused(before, "directory before the profile files")
    }

    @Test
    fun `the same archives without the repeated name extract or fail for another reason so the repeat is the cause`() {
        extractedFrom(topWith(extra = listOf(TarFixtures.dir("top/sub/"))), "directory with its slash alone")
        extractedFrom(topWith(extra = listOf(TarFixtures.file("top/sub", bytes))), "plain name alone")
        extractedFrom(
            topWith(extra = listOf(TarFixtures.dir("top/sub/"), TarFixtures.file("top/other", bytes))),
            "directory and a plain name that differ",
        )
        val withoutTokens = TarFixtures.TINY_FILES - "tokens.txt"
        ExtractorRig(tmp.newFolder(), bz(topWith(files = withoutTokens, extra = listOf(TarFixtures.dir("top/tokens.txt/")))))
            .expectRejected(ExtractionReason.MISSING_FILE, "the directory alone is not the profile file")
    }
}
