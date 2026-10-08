package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the unpack table and the streaming table in step: what the unpack writes
 * is exactly what the locator picks, and the same models appear in both.
 */
class StreamingProfilesConsistencyTest {

    /** The ids to probe: every registry id plus a few that no table should know. */
    private fun candidateIds(): List<String> =
        (ModelRegistry.ALL.map { it.id } + listOf("small", "tiny", "base", "medium", "", "unknown")).distinct()

    /** The ids the unpack table has a profile for, among the candidates. */
    private fun unpackIds(): Set<String> =
        candidateIds().filter { ExtractionProfiles.forModel(it) != null }.toSet()

    @Test
    fun `the locator picks exactly the files the unpack profile writes for every profile`() {
        val ids = unpackIds()
        assertTrue("the unpack table must have profiles to check, got $ids", ids.size >= 2)
        for (id in ids) {
            val profile = ExtractionProfiles.forModel(id)
            assertNotNull("profile of $id", profile)
            val written = profile!!.files
            for ((label, listing) in listOf("role order" to written, "reversed" to written.reversed(), "sorted" to written.sorted())) {
                val choice = TransducerFileLocator.choose(listing)
                assertNotNull("$id ($label): the locator found no choice among $listing", choice)
                assertEquals("$id ($label): encoder", profile.encoder, choice!!.encoder)
                assertEquals("$id ($label): decoder", profile.decoder, choice.decoder)
                assertEquals("$id ($label): joiner", profile.joiner, choice.joiner)
                assertEquals("$id ($label): tokens", profile.tokens, choice.tokens)
            }
        }
    }

    @Test
    fun `the unpack profile ids and the streaming profile ids are the same set`() {
        val unpack = unpackIds()
        assertTrue("the unpack table must have profiles to compare, got $unpack", unpack.isNotEmpty())
        assertEquals("ids with a streaming profile", unpack, StreamingProfiles.ids)
        for (id in candidateIds()) {
            assertEquals(
                "id [$id]: has an unpack profile exactly when it has a streaming profile",
                ExtractionProfiles.forModel(id) != null,
                StreamingProfiles.forModel(id) != null,
            )
        }
        for (id in unpack) assertEquals("language of $id", "en", StreamingProfiles.forModel(id)!!.language)
    }
}
