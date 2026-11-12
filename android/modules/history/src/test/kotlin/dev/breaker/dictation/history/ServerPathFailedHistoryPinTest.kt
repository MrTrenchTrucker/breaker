package dev.breaker.dictation.history

import dev.breaker.dictation.core.model.Transcription
import dev.breaker.dictation.core.model.TranscriptionSource
import dev.breaker.dictation.core.port.Clock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transient `serverPathFailed` fact never reaches history. The store maps a
 * transcription to a row and a row back to a transcription through the six
 * stored columns, and the row holds no seventh one, so a fallback flag set in
 * memory comes back cleared. The mapping is pinned from both directions, and a
 * source pin says the mapper's file never names the field.
 */
class ServerPathFailedHistoryPinTest {

    private val clock = Clock { 1_700_000_000_000L }
    private val database = InMemoryHistoryDatabase()
    private val store = SqliteHistoryStore(database, clock)

    private fun aTranscription(serverPathFailed: Boolean = false): Transcription = Transcription(
        id = "t-1",
        text = "load the hay",
        source = TranscriptionSource.LOCAL,
        model = "small.en",
        durationMs = 1_200L,
        createdAt = 1_700_000_000_000L,
        serverPathFailed = serverPathFailed,
    )

    @Test
    fun `a stored fallback transcription comes back with the flag cleared`() {
        store.save(aTranscription(serverPathFailed = true))
        val back: List<Transcription> = store.list(10)
        assertEquals("history: the store must keep the row", 1, back.size)
        assertEquals("history: the id must round trip", "t-1", back[0].id)
        assertEquals("history: the text must round trip", "load the hay", back[0].text)
        assertFalse(
            "history: the fallback flag is transient and must not survive a round trip",
            back[0].serverPathFailed,
        )
    }

    @Test
    fun `the row mapping drops the flag in both directions`() {
        val row = TranscriptionRow.of(aTranscription(serverPathFailed = true))
        // The row has exactly the six stored columns and no flag column.
        val back = row.toTranscription()
        assertFalse(
            "history: a row built from a fallback transcription must read the flag back as false",
            back.serverPathFailed,
        )
        assertEquals("history: the source must survive the row mapping", TranscriptionSource.LOCAL, back.source)
    }

    @Test
    fun `the row mapper never names the field`() {
        val text = sourceFile("TranscriptionRow.kt")
        assertFalse(
            "history: the row mapper must never mention the transient field",
            text.contains("serverPathFailed"),
        )
    }

    /** The module's [name] under src/main/kotlin, found from the test working directory. */
    private fun sourceFile(name: String): String {
        val root = findModuleRoot()
        val dir = File(root, "src/main/kotlin/dev/breaker/dictation/history")
        check(dir.isDirectory) { "history: no source folder at $dir" }
        val file = File(dir, name)
        check(file.isFile) { "history: no source file $name at $dir" }
        return file.readText()
    }

    private fun findModuleRoot(): File {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null) {
            if (File(folder, "build.gradle.kts").isFile &&
                File(folder, "src/main/kotlin/dev/breaker/dictation/history").isDirectory
            ) {
                return folder
            }
            folder = folder.parentFile
        }
        error("history: no module root at or above ${System.getProperty("user.dir")}")
    }
}
