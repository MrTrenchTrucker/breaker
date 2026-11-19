package dev.breaker.dictation.stt.ondevice

import dev.breaker.shared.models.ModelKind
import dev.breaker.shared.models.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The profile table's rows for the speech models. Every mutant named here is hand traced, not run. */
class StreamingProfilesParakeetTest {

    @Test
    fun `parakeet is an offline model and has no streaming profile`() {
        // Killer: adding the row `"parakeet" to StreamingProfile(...)` back (any language) makes forModel non-null
        // (assertNull fires) and puts the id in `ids` (assertFalse fires).
        val entry = ModelRegistry.byId("parakeet")
        assertNotNull("parakeet is in the registry", entry)
        assertEquals(ModelKind.OFFLINE, entry!!.kind)
        assertNull(StreamingProfiles.forModel("parakeet"))
        assertFalse("parakeet" in StreamingProfiles.ids)
    }

    @Test
    fun `parakeet still has an unpack profile so it can be installed without a streaming profile`() {
        // Killer: dropping the unpack profile for parakeet makes the first assertion fail; keeping the row fails the second.
        assertNotNull(ExtractionProfiles.forModel("parakeet"))
        assertNull(StreamingProfiles.forModel("parakeet"))
    }

    @Test
    fun `the English models keep their language and the whisper entries still have no profile`() {
        assertEquals("en", StreamingProfiles.forModel("small")!!.language)
        assertEquals("en", StreamingProfiles.forModel("tiny")!!.language)
        assertNull(StreamingProfiles.forModel("base"))
        assertNull(StreamingProfiles.forModel("medium"))
    }
}
