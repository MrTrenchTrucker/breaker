package dev.breaker.server.whisper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural test. Nothing here can be seen from a behaviour test: a log line that carries
// audio, a message that leaks into a stored error, a write that overwrites a row, a query that
// reads the audio column on every poll, a worker that reads the clock. It guards the shape of
// the code instead, and each rule has a control that shows the scan can report it.
internal class SourceScanTest {

    private val forbidden = listOf(
        "println(", "System.out", "System.err", "Logger", "printStackTrace", "Thread.sleep",
        "synchronized", "SELECT *", "OR REPLACE", "REPLACE INTO", "ON CONFLICT",
        "System.currentTimeMillis", "Instant.now(", "dev.breaker.server.syncapi", "io.ktor",
    )

    // The message of a forwarder exception can hold a URL or a key and must never be stored.
    private val messageAccess = listOf(".message", ".localizedMessage", "printStackTrace")

    // Gradle runs tests with the module folder as the working directory.
    private val sourceRoot = File("src/main/kotlin/dev/breaker/server/whisper")

    /** Pairs of the path below the source root (with forward slashes) and the file text. */
    private fun readSources(): List<Pair<String, String>> {
        assertTrue("whisper-server: the source folder must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val files = sourceRoot.walkTopDown().filter { file -> file.isFile && file.name.endsWith(".kt") }.toList()
        assertTrue("whisper-server: expected at least 12 Kotlin files, found ${files.size}", files.size >= 12)
        return files.map { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath to file.readText() }
    }

    /** One line per (file, token) pair that is present, for the files [include] accepts. */
    private fun scan(
        sources: List<Pair<String, String>>,
        tokens: List<String>,
        include: (String) -> Boolean = { true },
    ): List<String> {
        val hits = ArrayList<String>()
        for ((path, text) in sources) {
            if (!include(path)) {
                continue
            }
            for (token in tokens) {
                if (text.contains(token)) {
                    hits.add("$path contains $token")
                }
            }
        }
        return hits
    }

    /** Constants whose code (comment lines left out) holds both a SELECT and the audio column. */
    private fun audioSelectConstants(text: String): Int {
        var count = 0
        for (chunk in text.split("const val ").drop(1)) {
            val code = chunk.lines().filter { line -> !line.trim().startsWith("//") }.joinToString("\n")
            if (code.contains("SELECT") && code.contains("audio")) {
                count += 1
            }
        }
        return count
    }

    /** Code lines (comment lines left out) that hold both a SELECT and the audio column. */
    private fun audioSelectLines(text: String): List<String> =
        text.lines().filter { line ->
            !line.trim().startsWith("//") && line.contains("SELECT") && line.contains("audio")
        }

    private fun nonAsciiFiles(sources: List<Pair<String, String>>): List<String> =
        sources.filter { (_, text) -> text.any { character -> character.code > 127 } }.map { (path, _) -> path }

    @Test
    fun `no main file prints, logs, sleeps, locks, overwrites a row, reads the wall clock or uses another module`() {
        assertEquals("whisper-server: forbidden tokens found", emptyList<String>(), scan(readSources(), forbidden))
    }

    @Test
    fun `the queue worker never reads or stores the message of an exception`() {
        val sources = readSources()
        assertTrue(
            "whisper-server: worker/QueueWorker.kt must be among the scanned files",
            sources.any { (path, _) -> path == "worker/QueueWorker.kt" },
        )
        assertEquals(
            "whisper-server: exception message access in the worker",
            emptyList<String>(),
            scan(sources, messageAccess) { path -> path == "worker/QueueWorker.kt" },
        )
    }

    @Test
    fun `the worker package never mentions a clock`() {
        val sources = readSources()
        assertTrue(
            "whisper-server: the worker package must have files to scan",
            sources.count { (path, _) -> path.startsWith("worker/") } >= 3,
        )
        assertEquals(
            "whisper-server: the worker must wait only through its pause",
            emptyList<String>(),
            scan(sources, listOf("Clock")) { path -> path.startsWith("worker/") },
        )
    }

    @Test
    fun `exactly one sql constant in the jobs package selects the audio column`() {
        val sources = readSources()
        val jobSql = sources.first { (path, _) -> path == "jobs/JobSql.kt" }.second
        assertEquals("whisper-server: one SELECT of the audio column in JobSql.kt", 1, audioSelectConstants(jobSql))

        val elsewhere = ArrayList<String>()
        for ((path, text) in sources) {
            if (path.startsWith("jobs/") && path != "jobs/JobSql.kt") {
                for (line in audioSelectLines(text)) {
                    elsewhere.add("$path: ${line.trim()}")
                }
            }
        }
        assertEquals("whisper-server: no query outside JobSql.kt may read the audio", emptyList<String>(), elsewhere)
    }

    @Test
    fun `every main file is plain ascii`() {
        assertEquals("whisper-server: files with non-ASCII characters", emptyList<String>(), nonAsciiFiles(readSources()))
    }

    @Test
    fun `the scan reports each forbidden token in a text that holds it and nothing in a clean one`() {
        for (token in forbidden) {
            val dirty = scan(listOf("fake.kt" to "val x = \"$token\""), forbidden)
            assertTrue("whisper-server: $token must be reported: $dirty", dirty.contains("fake.kt contains $token"))
        }
        for (token in messageAccess) {
            val dirty = scan(listOf("worker/QueueWorker.kt" to "val x = failure$token"), messageAccess)
            assertTrue(
                "whisper-server: $token must be reported: $dirty",
                dirty.contains("worker/QueueWorker.kt contains $token"),
            )
        }
        assertEquals(
            "whisper-server: a clean text must report nothing",
            emptyList<String>(),
            scan(listOf("clean.kt" to "val x = 1"), forbidden),
        )
        assertEquals(
            "whisper-server: a file the filter leaves out must not be reported",
            emptyList<String>(),
            scan(listOf("jobs/Other.kt" to "val x = failure.message"), messageAccess) { path ->
                path == "worker/QueueWorker.kt"
            },
        )
        assertEquals(
            "whisper-server: a clock in the worker package must be reported",
            listOf("worker/Late.kt contains Clock"),
            scan(listOf("worker/Late.kt" to "val c: Clock"), listOf("Clock")) { path -> path.startsWith("worker/") },
        )
    }

    @Test
    fun `the audio select count sees a select of the audio column and ignores everything else`() {
        val oneSelect = "const val A = \"SELECT audio FROM jobs WHERE id = ?\"\nconst val B = \"SELECT id FROM jobs\""
        val twoSelects = oneSelect + "\nconst val C = \"SELECT id, audio FROM jobs\""
        val onlyComment = "// SELECT audio is only read by one constant\nconst val A = \"SELECT id FROM jobs\""
        val onlyUpdate = "const val A = \"UPDATE jobs SET audio = NULL WHERE id = ?\""

        assertEquals("whisper-server: one audio select expected", 1, audioSelectConstants(oneSelect))
        assertEquals("whisper-server: two audio selects expected", 2, audioSelectConstants(twoSelects))
        assertEquals("whisper-server: a comment is not a query", 0, audioSelectConstants(onlyComment))
        assertEquals("whisper-server: an update is not a select", 0, audioSelectConstants(onlyUpdate))
        assertEquals(
            "whisper-server: a code line with SELECT and audio must be reported",
            1,
            audioSelectLines("val q = \"SELECT audio FROM jobs\"\n// SELECT audio in a comment").size,
        )
    }

    @Test
    fun `the ascii check reports a file with a character above 127 and none for plain text`() {
        val dirty = listOf("fake.kt" to "val name = \"caf\u00e9\"")
        val clean = listOf("clean.kt" to "val name = \"cafe\"")

        assertEquals("whisper-server: the non-ASCII file must be named", listOf("fake.kt"), nonAsciiFiles(dirty))
        assertEquals("whisper-server: plain text must report nothing", emptyList<String>(), nonAsciiFiles(clean))
    }
}
