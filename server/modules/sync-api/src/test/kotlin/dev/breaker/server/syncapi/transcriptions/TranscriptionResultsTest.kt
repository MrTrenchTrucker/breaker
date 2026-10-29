package dev.breaker.server.syncapi.transcriptions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TranscriptionResultsTest {

    @Test
    fun `a stored transcription prints its client id and key version and never its text`() {
        val text = "ZGlzdGluY3QtdGV4dC1uZXZlci1wcmludGVk"
        val clientId = TranscriptionFixtures.clientId(7)
        val printed = StoredTranscription(clientId, 0, text, 5L).toString()
        assertTrue("sync-api: the client id must be printed", printed.contains(clientId))
        assertTrue("sync-api: the key version must be printed", printed.contains("keyVersion=0"))
        assertFalse("sync-api: the stored text must not be printed", printed.contains(text))
    }
}
