package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.screen.home.ServiceStatusTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one service-status line: the app's plain facts in, this module's words out.
 *
 * Null when there is no line - not asked, or a reply that lists nothing to report - and never an
 * empty string or a placeholder.
 */
class ServiceStatusLineTest {

    @Test
    fun `a reply shows the one line for its state`() {
        assertEquals(
            ServiceStatusTexts.ALL_UP,
            serviceStatusLine(listOf("sync-api" to true, "whisper-server" to true), failed = false, asked = true),
        )
        assertEquals(
            ServiceStatusTexts.TRANSCRIPTION_DOWN,
            serviceStatusLine(listOf("sync-api" to true, "whisper-server" to false), failed = false, asked = true),
        )
        assertEquals(
            ServiceStatusTexts.SERVER_UNREACHABLE,
            serviceStatusLine(null, failed = true, asked = true),
        )
    }

    @Test
    fun `not asked is no line, whatever the reply says`() {
        assertNull(serviceStatusLine(null, failed = false, asked = false))
        assertNull(serviceStatusLine(listOf("sync-api" to true), failed = false, asked = false))
        assertNull(serviceStatusLine(null, failed = true, asked = false))
    }

    @Test
    fun `a reply that lists nothing to report is no line`() {
        assertNull(serviceStatusLine(emptyList(), failed = false, asked = true))
    }

    @Test
    fun `the line is read from ServiceStatusTexts, never written by the caller`() {
        val lines = listOf(
            serviceStatusLine(listOf("sync-api" to true, "whisper-server" to true), failed = false, asked = true),
            serviceStatusLine(listOf("sync-api" to true, "whisper-server" to false), failed = false, asked = true),
            serviceStatusLine(null, failed = true, asked = true),
        )
        assertEquals(
            listOf(ServiceStatusTexts.ALL_UP, ServiceStatusTexts.TRANSCRIPTION_DOWN, ServiceStatusTexts.SERVER_UNREACHABLE),
            lines,
        )
    }
}
