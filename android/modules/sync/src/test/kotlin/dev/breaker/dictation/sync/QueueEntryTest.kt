package dev.breaker.dictation.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a queued row prints as. A printed row can reach a log line or a crash report, so the text the user
 * said must never be part of it.
 */
class QueueEntryTest {

    /** A failure here means a log line or a crash report could carry the text the user said. */
    @Test
    fun `a queue entry prints its ids and the text length and never the text`() {
        val entry = QueueEntry(
            seq = 1L,
            clientId = clientId(1),
            text = "secret words here",
            source = "local",
            model = "small.en",
            durationMs = 1_200L,
            createdAtMs = 5L,
        )
        val printed = entry.toString()
        assertTrue("android_sync: the print must carry the seq, got $printed", printed.contains("seq=1"))
        assertTrue(
            "android_sync: the print must carry the client id, got $printed",
            printed.contains("clientId=00000001-0000-4000-8000-000000000001"),
        )
        assertTrue("android_sync: the print must carry the text length, got $printed", printed.contains("17 chars"))
        assertFalse("android_sync: the print must not carry the text, got $printed", printed.contains("secret"))
    }
}
