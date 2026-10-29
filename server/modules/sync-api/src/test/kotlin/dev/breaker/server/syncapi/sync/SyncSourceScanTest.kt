package dev.breaker.server.syncapi.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text scans of the new production files: no print or log call, no interpolation of the encrypted text,
 * and no binding of the decoded body. Each rule has a control line it must flag, so an empty scan cannot pass.
 */
internal class SyncSourceScanTest {

    private val syncDir = File("src/main/kotlin/dev/breaker/server/syncapi/sync")
    private val sessionFile = File("src/main/kotlin/dev/breaker/server/syncapi/auth/SessionBearer.kt")

    private val logPropertyPattern = Regex("\\.log\\.")
    private val logMethodPattern = Regex("\\.(info|debug|warn|error|trace)\\(")
    private val loggingPatterns = listOf(
        Regex("\\bprint(ln)?\\("),
        Regex("System\\.(out|err)"),
        Regex("\\bLogger\\b|LoggerFactory"),
        Regex("printStackTrace"),
        Regex("\\.log\\("),
        logPropertyPattern,
        logMethodPattern,
    )
    private val interpolationPattern = Regex("\\$\\{?[A-Za-z_.]*encrypted")
    private val decodedBindingPatterns = listOf(
        Regex("=\\s*SyncBodyCodec\\.decodePlain"),
        Regex("SyncBodyCodec\\.decodePlain\\([^)]*\\)\\s*\\."),
    )

    private fun scannedFiles(): List<File> {
        val syncFiles = syncDir.listFiles { file -> file.name.endsWith(".kt") }?.toList().orEmpty()
        return syncFiles + listOf(sessionFile).filter { it.isFile }
    }

    private fun lines(files: List<File>): List<String> =
        files.flatMap { file -> file.readLines().map { line -> "${file.name}: $line" } }

    @Test
    fun `the sync folder and the session bearer file hold the expected files`() {
        val names = scannedFiles().map { it.name }.toSet()
        val expected = setOf(
            "SyncPushRoute.kt",
            "TranscriptionsPullRoute.kt",
            "PushRequestReader.kt",
            "SyncLimits.kt",
            "JsonText.kt",
            "SessionBearer.kt",
        )
        assertTrue("expected $expected, found $names", names.containsAll(expected))
    }

    @Test
    fun `the sync folder and the session bearer file contain no print, log or stack trace call`() {
        val files = scannedFiles()
        assertTrue("no files were scanned", files.isNotEmpty())
        val found = lines(files).filter { line -> loggingPatterns.any { it.containsMatchIn(line) } }
        assertEquals("print or log calls", emptyList<String>(), found)
    }

    @Test
    fun `the print and log rule flags a line that prints`() {
        val control = "        println(\"x\")"
        assertTrue("the rule fires", loggingPatterns.any { it.containsMatchIn(control) })
    }

    @Test
    fun `no sync file interpolates the encrypted text into a string`() {
        val files = scannedFiles()
        assertTrue("no files were scanned", files.isNotEmpty())
        val found = lines(files).filter { interpolationPattern.containsMatchIn(it) }
        assertEquals("interpolations of the encrypted text", emptyList<String>(), found)
        assertTrue("the rule fires on an interpolated value", interpolationPattern.containsMatchIn("\"x \${request.encrypted}\""))
    }

    @Test
    fun `the push route keeps the decoded value out of any binding`() {
        val files = scannedFiles()
        assertTrue("no files were scanned", files.isNotEmpty())
        val found = lines(files).filter { line -> decodedBindingPatterns.any { it.containsMatchIn(line) } }
        assertEquals("bindings of the decoded value", emptyList<String>(), found)
        val control = "        val decoded = SyncBodyCodec.decodePlain(request.encrypted)"
        assertTrue("the rule fires on a bound decode", decodedBindingPatterns.any { it.containsMatchIn(control) })
    }

    @Test
    fun `the log property rule flags a read of the application log`() {
        val sample = "        val sink = call.application.log.encrypted"
        assertTrue("the rule is in the scanned list", loggingPatterns.contains(logPropertyPattern))
        assertTrue("the rule fires", logPropertyPattern.containsMatchIn(sample))
    }

    @Test
    fun `the logger method rule flags a call such as logger dot info`() {
        assertTrue("the rule is in the scanned list", loggingPatterns.contains(logMethodPattern))
        val samples = listOf("logger.info(text)", "logger.debug(text)", "logger.warn(text)", "logger.error(text)", "logger.trace(text)")
        for (sample in samples) {
            assertTrue("the rule fires on $sample", logMethodPattern.containsMatchIn(sample))
        }
    }
}
