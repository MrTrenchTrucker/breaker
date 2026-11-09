package dev.breaker.dictation.wiring

import dev.breaker.dictation.gates.AppSourceFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two fixed refusal texts the app compares a finish against, pinned against the engine's own source.
 *
 * [SessionRefusalSentences] is a local object (the stt-server object cannot be imported), so this test reads the
 * engine file as SOURCE TEXT and asserts its `KEY_REFUSED` and `NO_KEY` lines carry exactly the two texts the app
 * holds, character for character. A change to either constant, or to either engine line, turns one red.
 */
class SessionRefusalSentencesTest {

    /** The engine file read as raw source text: the repo root is one above the app module (the modules tree). */
    private val engine: String = File(AppSourceFiles.moduleRoot.parentFile,
        "modules/stt-server/src/main/kotlin/dev/breaker/dictation/stt/server/FailureSentences.kt")
        .readText()

    @Test
    fun `the two refusal texts match the engine words exactly`() {
        // The local constants equal the fixed engine sentences, character for character.
        assertEquals(
            "KEY_REFUSED_TEXT must be exactly \"The server refused the key.\"",
            SessionRefusalSentences.KEY_REFUSED_TEXT,
            "The server refused the key.",
        )
        assertEquals(
            "NO_KEY_TEXT must be exactly \"The server key is not set.\"",
            SessionRefusalSentences.NO_KEY_TEXT,
            "The server key is not set.",
        )

        // The engine's own const lines carry those exact texts (source read, no import of the internal object).
        val key = "const val KEY_REFUSED = \"The server refused the key.\""
        val keyNo = "const val NO_KEY = \"The server key is not set.\""
        assertTrue("engine const line KEY_REFUSED must carry its text", engine.contains(key))
        assertTrue("engine const line NO_KEY must carry its text", engine.contains(keyNo))

        // The two local texts are distinct: the guard's equality matches neither by accident.
        assertNotEquals(
            "the two refusal texts must be distinct",
            SessionRefusalSentences.KEY_REFUSED_TEXT,
            SessionRefusalSentences.NO_KEY_TEXT,
        )
    }
}
