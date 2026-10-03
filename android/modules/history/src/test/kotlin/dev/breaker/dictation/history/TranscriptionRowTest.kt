package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored row and the domain value it stands for.
 *
 * A round trip that quietly swaps two columns still produces a row that looks
 * plausible in history — a server transcription shown with the local model's
 * name, say — and the user has no way to tell. So the mapping is pinned from
 * both directions.
 */
class TranscriptionRowTest {

    private fun transcription(
        id: String = "t-1",
        text: String = "load the hay",
        source: TranscriptionSource = TranscriptionSource.LOCAL,
        model: String = "small.en",
        durationMs: Long = 1_200,
        createdAt: Long = 1_700_000_000_000,
    ) = Transcription(
        id = id,
        text = text,
        source = source,
        model = model,
        durationMs = durationMs,
        createdAt = createdAt,
    )

    private fun row(
        id: String = "t-1",
        text: String = "load the hay",
        source: String = TranscriptionRow.SOURCE_LOCAL,
        model: String = "small.en",
        durationMs: Long = 1_200,
        createdAt: Long = 1_700_000_000_000,
        audioPath: String? = null,
    ) = TranscriptionRow(
        id = id,
        text = text,
        source = source,
        model = model,
        durationMs = durationMs,
        createdAt = createdAt,
        audioPath = audioPath,
    )

    @Test
    fun `a local transcription becomes a row with the local word`() {
        assertEquals(TranscriptionRow.SOURCE_LOCAL, TranscriptionRow.of(transcription()).source)
    }

    @Test
    fun `a server transcription becomes a row with the server word`() {
        val row = TranscriptionRow.of(transcription(source = TranscriptionSource.SERVER))
        assertEquals(TranscriptionRow.SOURCE_SERVER, row.source)
    }

    @Test
    fun `every field survives the round trip`() {
        val original = transcription(
            id = "t-42",
            text = "deliver to dock four",
            source = TranscriptionSource.SERVER,
            model = "large.en",
            durationMs = 4_321,
            createdAt = 1_690_000_000_000,
        )
        assertEquals(original, TranscriptionRow.of(original).toTranscription())
    }

    @Test
    fun `a local row reads back as a local transcription`() {
        assertEquals(TranscriptionSource.LOCAL, row().toTranscription().source)
    }

    @Test
    fun `a server row reads back as a server transcription`() {
        val read = row(source = TranscriptionRow.SOURCE_SERVER).toTranscription()
        assertEquals(TranscriptionSource.SERVER, read.source)
    }

    @Test
    fun `neither source is treated as more special than the other`() {
        val local = TranscriptionRow.of(transcription(source = TranscriptionSource.LOCAL))
        val server = TranscriptionRow.of(transcription(source = TranscriptionSource.SERVER))
        assertEquals(
            "A server transcription must be stored in exactly the same shape as a local one",
            local.copy(source = TranscriptionRow.SOURCE_SERVER),
            server,
        )
    }

    @Test
    fun `an unknown source is refused rather than read as a local one`() {
        // Silently mapping an unrecognised word to LOCAL would show a server
        // dictation in history as if the phone had produced it. The row is
        // still on file and can be fixed; a lie cannot.
        val failure = runCatching { row(source = "cloud").toTranscription() }.exceptionOrNull()
        assertTrue(failure is MappingFailure)
    }

    @Test
    fun `an unknown source can be inspected without being mapped`() {
        assertNull(row(source = "cloud").toSourceOrNull())
    }

    @Test
    fun `both known sources are recognised`() {
        assertEquals(TranscriptionSource.LOCAL, row(source = "local").toSourceOrNull())
        assertEquals(TranscriptionSource.SERVER, row(source = "server").toSourceOrNull())
    }

    @Test
    fun `a mapping failure names the row and the value it found`() {
        // A failure that does not say which row is broken is a failure that
        // gets logged and never fixed.
        val message = runCatching { row(id = "t-7", source = "cloud").toTranscription() }
            .exceptionOrNull()
            ?.message
            ?: ""
        assertTrue(message.contains("t-7"))
        assertTrue(message.contains("cloud"))
    }

    @Test
    fun `audio is off by default, so a row points at no file`() {
        assertNull(TranscriptionRow.of(transcription()).audioPath)
    }

    @Test
    fun `text is stored exactly, including whitespace and newlines`() {
        val text = "  line one\nline two\ttabbed  "
        assertEquals(text, TranscriptionRow.of(transcription(text = text)).text)
        assertEquals(text, TranscriptionRow.of(transcription(text = text)).toTranscription().text)
    }

    @Test
    fun `blank text is a legal row`() {
        assertEquals("", TranscriptionRow.of(transcription(text = "")).text)
    }

    @Test
    fun `a row with no id is refused`() {
        val failure = runCatching { row(id = "  ") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a row with no model is refused`() {
        val failure = runCatching { row(model = "") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a row with negative numbers is refused`() {
        assertTrue(runCatching { row(durationMs = -1) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { row(createdAt = -1) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a transcription with a zero duration is legal`() {
        // A dictation that produced no audio still produced text.
        assertEquals(0, TranscriptionRow.of(transcription(durationMs = 0)).durationMs)
    }
}
