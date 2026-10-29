package dev.breaker.server.whisper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural test. Nothing here can be seen from a behaviour test: a log line that carries
// audio, a message that leaks into a stored error, a write that overwrites a row, a query that
// reads the audio column on every poll, a worker that reads the clock. It guards the shape of
// the code instead, and each rule has a control that shows the scan can report it.
//
// io.ktor is allowed only under the http/ package; every other forbidden token stays forbidden
// everywhere, including under http/. Approval recorded on the work order (2026-10-08).
internal class SourceScanTest {

    private val forbidden = listOf(
        "println(", "System.out", "System.err", "printStackTrace", "Thread.sleep",
        "synchronized", "SELECT *", "OR REPLACE", "REPLACE INTO", "ON CONFLICT",
        "System.currentTimeMillis", "Instant.now(", "dev.breaker.server.syncapi",
    )

    // io.ktor is allowed only under the http/ package; everywhere else it stays forbidden.
    private val httpOnly = listOf("io.ktor")

    private fun isHttpPackage(path: String) = path.startsWith("http/")

    /** Scan for http-only tokens, filtering out http/ files first. */
    private fun scanHttpOnly(sources: List<Pair<String, String>>): List<String> =
        scan(sources.filterNot { (path, _) -> isHttpPackage(path) }, httpOnly)

    // Logger is allowed only in worker/QueueWorker.kt; everywhere else it stays forbidden. A silent
    // store stall is worse than one minimal log line.
    private val queueWorkerOnly = listOf("Logger")

    private fun isQueueWorkerFile(path: String) = path == "worker/QueueWorker.kt"

    /** Scan for queue-worker-only tokens, filtering out worker/QueueWorker.kt first. */
    private fun scanQueueWorkerOnly(sources: List<Pair<String, String>>): List<String> =
        scan(sources.filterNot { (path, _) -> isQueueWorkerFile(path) }, queueWorkerOnly)

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

    // fun main() wires the api; it must go through buildTranscriptionApi, the one place main() and a
    // test build it, and it must pass a monotonic source. extractMainBody keeps only main()'s own
    // braces, so the TranscriptionApi( inside buildTranscriptionApi()'s sibling body is never scanned.
    private data class MainBody(val code: String, val firstLine: Int)

    private fun extractMainBody(text: String): MainBody? {
        val decl = Regex("""(?m)^[ \t]*fun[ \t]+main[ \t]*\([ \t]*\)""").find(text) ?: return null
        val brace = text.indexOf('{', decl.range.last)
        if (brace < 0) return null
        val code = StringBuilder()
        var depth = 0
        var i = brace
        while (i < text.length) {
            when (text[i]) {
                '{' -> if (depth++ > 0) code.append('{')
                '}' -> if (--depth == 0) break else code.append('}')
                else -> if (depth > 0) code.append(text[i])
            }
            i++
        }
        if (depth != 0) return null
        return MainBody(code.toString(), text.substring(0, brace + 1).count { character -> character == '\n' } + 1)
    }

    // 1-based source line numbers of every occurrence of [token] in [code].
    private fun matchLines(code: String, firstLine: Int, token: String): List<Int> {
        val lines = ArrayList<Int>()
        var from = 0
        while (true) {
            val at = code.indexOf(token, from)
            if (at < 0) break
            lines.add(firstLine + code.substring(0, at).count { character -> character == '\n' })
            from = at + token.length
        }
        return lines
    }

    // 1-based source line numbers of a direct TranscriptionApi( construction. buildTranscriptionApi(
    // is the builder call and must not be read as a direct one, so the token must not follow a word.
    private fun directConstructionLines(code: String, firstLine: Int): List<Int> {
        val lines = ArrayList<Int>()
        for (match in Regex("""(?<!\w)TranscriptionApi\(""").findAll(code)) {
            lines.add(firstLine + code.substring(0, match.range.first).count { character -> character == '\n' })
        }
        return lines
    }

    // The wiring defects of a main() body: a missing builder call, a missing monotonic source, or a
    // direct construction. The control below runs it on mutant bodies to show the scan can report.
    private fun mainWiringDefects(code: String, firstLine: Int): List<String> {
        val defects = ArrayList<String>()
        if (!code.contains("buildTranscriptionApi(")) {
            defects.add("main must call buildTranscriptionApi(")
        }
        if (!code.contains("System::nanoTime")) {
            defects.add("main must pass System::nanoTime as the monotonic source")
        }
        val direct = directConstructionLines(code, firstLine)
        if (direct.isNotEmpty()) {
            defects.add("main must not construct TranscriptionApi( directly (lines $direct)")
        }
        return defects
    }

    @Test
    fun `no main file prints, logs, sleeps, locks, overwrites a row, reads the wall clock or uses another module`() {
        val sources = readSources()
        assertEquals("whisper-server: forbidden tokens found", emptyList<String>(), scan(sources, forbidden))
        assertEquals(
            "whisper-server: io.ktor is forbidden outside the http/ package",
            emptyList<String>(),
            scanHttpOnly(sources),
        )
        assertEquals(
            "whisper-server: Logger is forbidden outside worker/QueueWorker.kt",
            emptyList<String>(),
            scanQueueWorkerOnly(sources),
        )
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
    fun `io ktor is reported outside http and allowed inside it`() {
        val nonHttp = listOf("jobs/Late.kt" to "import io.ktor.http.HttpMethod")
        val httpFile = listOf("http/Routes.kt" to "import io.ktor.http.HttpMethod")

        assertEquals(
            "whisper-server: io.ktor in a non-http file must be reported",
            listOf("jobs/Late.kt contains io.ktor"),
            scan(nonHttp, httpOnly),
        )
        assertEquals(
            "whisper-server: io.ktor in an http/ file must not be reported",
            emptyList<String>(),
            scanHttpOnly(httpFile),
        )
        assertEquals(
            "whisper-server: other forbidden tokens in an http/ file are still reported",
            listOf("http/Routes.kt contains println("),
            scan(listOf("http/Routes.kt" to "println(\"x\")"), forbidden),
        )
    }

    @Test
    fun `Logger is reported outside worker and allowed inside it`() {
        val nonWorker = listOf("jobs/JobStore.kt" to "import java.util.logging.Logger")
        val workerFile = listOf("worker/QueueWorker.kt" to "import java.util.logging.Logger")

        assertEquals(
            "whisper-server: Logger in a non-worker file must be reported",
            listOf("jobs/JobStore.kt contains Logger"),
            scanQueueWorkerOnly(nonWorker),
        )
        assertEquals(
            "whisper-server: Logger in worker/QueueWorker.kt must not be reported",
            emptyList<String>(),
            scanQueueWorkerOnly(workerFile),
        )
        assertEquals(
            "whisper-server: other forbidden tokens in worker/QueueWorker.kt are still reported",
            listOf("worker/QueueWorker.kt contains println("),
            scan(listOf("worker/QueueWorker.kt" to "println(hello)"), forbidden),
        )
        assertEquals(
            "whisper-server: Logger in worker/Other.kt must be reported (not a worker/ package allowance)",
            listOf("worker/Other.kt contains Logger"),
            scanQueueWorkerOnly(listOf("worker/Other.kt" to "import java.util.logging.Logger")),
        )
        assertEquals(
            "whisper-server: Logger in worker/sub/QueueWorker.kt must be reported (exact path, not a suffix allowance)",
            listOf("worker/sub/QueueWorker.kt contains Logger"),
            scanQueueWorkerOnly(listOf("worker/sub/QueueWorker.kt" to "import java.util.logging.Logger")),
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

    @Test
    fun `main wires the api through buildTranscriptionApi with a monotonic source and never builds it directly`() {
        val source = sourceRoot.resolve("WhisperServer.kt")
        assertTrue(
            "whisper-server: WhisperServer.kt must exist: ${source.absolutePath}",
            source.isFile,
        )
        val main = extractMainBody(source.readText())
        assertTrue(
            "whisper-server: fun main() with a brace-matched body must exist in WhisperServer.kt",
            main != null,
        )
        val body = main!!.code
        val firstLine = main.firstLine

        // The wiring check finds no defect in the real main(): it routes through the shared builder,
        // passes the monotonic source, and never constructs the api itself.
        assertEquals(
            "whisper-server: main() must call buildTranscriptionApi( with System::nanoTime and never " +
                "construct TranscriptionApi( directly: ${mainWiringDefects(body, firstLine)}",
            emptyList<String>(),
            mainWiringDefects(body, firstLine),
        )

        // Name the exact lines the positive wiring matches, so a mutant that moves either leaves a gap.
        val buildLines = matchLines(body, firstLine, "buildTranscriptionApi(")
        assertTrue("whisper-server: buildTranscriptionApi( must be called in main() on lines $buildLines", buildLines.isNotEmpty())
        val nanoLines = matchLines(body, firstLine, "System::nanoTime")
        assertTrue("whisper-server: System::nanoTime must be passed in main() on lines $nanoLines", nanoLines.isNotEmpty())
        val directLines = directConstructionLines(body, firstLine)
        assertEquals(
            "whisper-server: no direct TranscriptionApi( construction in main() (lines $directLines)",
            emptyList<Int>(),
            directLines,
        )

        // Control: the same wiring check reports each mutant, so it kills H09 (drops System::nanoTime)
        // and H24 (builds the api directly) instead of passing by tautology.
        val nanoMutant = "val api = buildTranscriptionApi(store, worker::wake, config) { 0L }"
        val nanoDefects = mainWiringDefects(nanoMutant, 1)
        assertTrue("whisper-server: dropping System::nanoTime must be reported: $nanoDefects", nanoDefects.any { defect -> defect.contains("System::nanoTime") })
        val directMutant = "val api = TranscriptionApi(store, worker::wake, forwardingTo = \"x\", uptime = { 0L })"
        val directDefects = mainWiringDefects(directMutant, 1)
        assertTrue("whisper-server: a direct TranscriptionApi( must be reported: $directDefects", directDefects.any { defect -> defect.contains("directly") })
    }
}
