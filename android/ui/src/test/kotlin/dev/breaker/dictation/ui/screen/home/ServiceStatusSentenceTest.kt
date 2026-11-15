package dev.breaker.dictation.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The service-status decision, one row per input (ADR-044):
 * a reply with every service up, whisper-server down, sync-api down, both down,
 * an unknown service name, an empty list, a failure, and not asked.
 */
class ServiceStatusSentenceTest {

    private fun service(name: String, up: Boolean) = ServiceStatusEntry(name, up)

    @Test
    fun `a reply with every service up shows the all-up line`() {
        val reply = listOf(service("sync-api", true), service("whisper-server", true))
        assertEquals(ServiceStatusSentence.ALL_UP, decideServiceStatus(reply, failed = false, asked = true))
    }

    @Test
    fun `whisper-server down shows the transcription line`() {
        val reply = listOf(service("sync-api", true), service("whisper-server", false))
        assertEquals(ServiceStatusSentence.TRANSCRIPTION_DOWN, decideServiceStatus(reply, failed = false, asked = true))
    }

    @Test
    fun `sync-api down shows the server line`() {
        val reply = listOf(service("sync-api", false), service("whisper-server", true))
        assertEquals(ServiceStatusSentence.SERVER_UNREACHABLE, decideServiceStatus(reply, failed = false, asked = true))
    }

    @Test
    fun `both down shows the server line, the worse one wins`() {
        val reply = listOf(service("sync-api", false), service("whisper-server", false))
        assertEquals(ServiceStatusSentence.SERVER_UNREACHABLE, decideServiceStatus(reply, failed = false, asked = true))
    }

    @Test
    fun `a service name that is neither sync-api nor whisper-server is ignored`() {
        val reply = listOf(service("sync-api", true), service("whisper-server", true), service("stt-server", false))
        assertEquals(ServiceStatusSentence.ALL_UP, decideServiceStatus(reply, failed = false, asked = true))
    }

    @Test
    fun `an empty reply shows no line`() {
        assertEquals(ServiceStatusSentence.NONE, decideServiceStatus(emptyList(), failed = false, asked = true))
    }

    @Test
    fun `a failure shows the server line`() {
        assertEquals(ServiceStatusSentence.SERVER_UNREACHABLE, decideServiceStatus(null, failed = true, asked = true))
    }

    @Test
    fun `not asked shows no line, for sync off, signed out and no address alike`() {
        assertEquals(ServiceStatusSentence.NONE, decideServiceStatus(null, failed = false, asked = false))
        assertEquals(ServiceStatusSentence.NONE, decideServiceStatus(null, failed = true, asked = false))
    }
}
