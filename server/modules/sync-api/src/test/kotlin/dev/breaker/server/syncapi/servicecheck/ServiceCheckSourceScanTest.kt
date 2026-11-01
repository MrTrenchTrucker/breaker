package dev.breaker.server.syncapi.servicecheck

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural test. The service key must never reach a printed line, a system stream, a logger
// or a printed stack trace. A behaviour test cannot see those, so this scans the shape of the code.
internal class ServiceCheckSourceScanTest {

    private val forbidden = listOf(
        "println(", "System.out", "System.err", "Logger", "slf4j", "printStackTrace",
    )

    // Gradle runs tests with the module folder as the working directory.
    private val sourceDirectory = File("src/main/kotlin/dev/breaker/server/syncapi/servicecheck")

    private fun readSources(): List<Pair<String, String>> {
        assertTrue("sync-api: the source folder must exist: ${sourceDirectory.absolutePath}", sourceDirectory.isDirectory)
        val files = sourceDirectory.listFiles().orEmpty().filter { file -> file.name.endsWith(".kt") }
        assertTrue("sync-api: expected at least 4 Kotlin files in ${sourceDirectory.absolutePath}, found ${files.size}", files.size >= 4)
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
    fun `no servicecheck file prints, writes to a system stream, logs or prints a stack trace`() {
        assertEquals("sync-api: forbidden tokens found", emptyList<String>(), scan(readSources(), forbidden))
    }

    @Test
    fun `the scan reports each forbidden token in a text that holds it and none in a clean one`() {
        for (token in forbidden) {
            val dirty = scan(listOf("fake.kt" to "val x = \"$token\""), forbidden)
            assertTrue("sync-api: $token must be reported: $dirty", dirty.contains("fake.kt contains $token"))
        }
        assertEquals("sync-api: a clean text must report nothing", emptyList<String>(), scan(listOf("clean.kt" to "val x = 1"), forbidden))
    }

    @Test
    fun `the scan reads the key file sources, so an empty scan means absence`() {
        val names = readSources().map { source -> source.first }
        assertTrue("sync-api: ServiceKeyFile.kt must be scanned, found $names", names.contains("ServiceKeyFile.kt"))
        assertTrue("sync-api: KeyLinker.kt must be scanned, found $names", names.contains("KeyLinker.kt"))
    }
}
