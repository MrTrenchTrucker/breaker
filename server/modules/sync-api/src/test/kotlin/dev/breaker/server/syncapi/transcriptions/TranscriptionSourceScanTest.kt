package dev.breaker.server.syncapi.transcriptions

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural test. The transcription text must never reach a printed line, a log, a
// hand-written comparison or an overwrite of a stored row. None of that is observable
// from a behaviour test, so this guards the shape of the source instead.
internal class TranscriptionSourceScanTest {

    private val forbidden: List<String> = listOf(
        "println(", "System.out", "System.err", "Logger", "slf4j", "printStackTrace",
        "Thread", "synchronized", "CountDownLatch", "Thread.sleep",
        "OR REPLACE", "ON CONFLICT", "REPLACE INTO",
    )

    // Two source patterns: a logger member access, and a call to a level method.
    private val forbiddenPatterns: List<Pair<String, Regex>> = listOf(
        "log member" to Regex("""\.log\."""),
        "level call" to Regex("""\.(info|debug|warn|error|trace)\("""),
    )

    /** One line per (file, pattern) pair that matches. */
    private fun scanPatterns(sources: List<Pair<String, String>>): List<String> {
        val hits = ArrayList<String>()
        for ((name, text) in sources) {
            for ((label, pattern) in forbiddenPatterns) {
                if (pattern.containsMatchIn(text)) {
                    hits.add("$name matches $label")
                }
            }
        }
        return hits
    }

    // Gradle runs tests with the module folder as the working directory.
    private val sourceDirectory = File("src/main/kotlin/dev/breaker/server/syncapi/transcriptions")

    private fun readSources(): List<Pair<String, String>> {
        assertTrue(
            "sync-api: the source folder must exist: ${sourceDirectory.absolutePath}",
            sourceDirectory.isDirectory,
        )
        val files = sourceDirectory.listFiles().orEmpty().filter { file -> file.name.endsWith(".kt") }
        assertTrue(
            "sync-api: expected at least 2 Kotlin files in ${sourceDirectory.absolutePath}, found ${files.size}",
            files.size >= 2,
        )
        return files.map { file -> file.name to file.readText() }
    }

    /** One line per (file, token) pair that is present. */
    private fun scan(sources: List<Pair<String, String>>, tokens: List<String>): List<String> {
        val hits = ArrayList<String>()
        for ((name, text) in sources) {
            for (token in tokens) {
                if (text.contains(token)) {
                    hits.add("$name contains $token")
                }
            }
        }
        return hits
    }

    @Test
    fun `no transcription file prints logs compares or overwrites a row`() {
        // The scan must report a planted print first, so a scan that finds nothing cannot pass.
        assertEquals(
            "sync-api: the scan must report a planted print",
            listOf("fake.kt contains println("),
            scan(listOf("fake.kt" to "fun leak() = println(1)"), forbidden),
        )
        assertEquals(
            "sync-api: forbidden words in the transcription sources",
            emptyList<String>(),
            scan(readSources(), forbidden),
        )
        // The patterns must fire: the log member and the level call, and each level word on its own.
        assertEquals(
            "sync-api: the pattern scan must report the log member and the level call",
            listOf("fake.kt matches log member", "fake.kt matches level call"),
            scanPatterns(listOf("fake.kt" to "call.application.log.info(request.encrypted)")),
        )
        for (word in listOf("info", "debug", "warn", "error", "trace")) {
            assertEquals(
                "sync-api: the level call $word must be caught",
                listOf("fake.kt matches level call"),
                scanPatterns(listOf("fake.kt" to "logger.$word(text)")),
            )
        }
        assertEquals(
            "sync-api: the transcription sources must not match the new patterns",
            emptyList<String>(),
            scanPatterns(readSources()),
        )
    }

    @Test
    fun `every SQL statement in the transcription store names the account column`() {
        val store = readSources().first { (name, _) -> name == "TranscriptionStore.kt" }.second
        val statements = store.split("const val ").drop(1)
            .map { block -> block.substringBefore("\n\n") }
            .filter { block -> block.substringBefore(' ').endsWith("_SQL") }
        assertTrue(
            "sync-api: expected at least 3 SQL statements in the store, found ${statements.size}",
            statements.size >= 3,
        )
        for (statement in statements) {
            assertTrue(
                "sync-api: a statement without the account column: ${statement.substringBefore(' ')}",
                statement.contains("account_id"),
            )
        }
    }
}
