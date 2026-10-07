package dev.breaker.server.syncapi.accounts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural test. A timing property (a comparison that takes the same time wherever
// the first difference sits) cannot be tested without sleeps or clocks, which would be
// flaky, so this guards the shape of the code instead: the one safe comparison is
// used, and nothing in the package can print, log or share state across callers.
internal class AccountsSourceScanTest {

    private val forbidden = listOf(
        "contentEquals(", "Arrays.equals(", "println(", "System.out", "System.err",
        "Logger", "slf4j", "Thread", "synchronized",
    )

    // Gradle runs tests with the module folder as the working directory.
    private val sourceDirectory = File("src/main/kotlin/dev/breaker/server/syncapi/accounts")

    private fun readSources(): List<Pair<String, String>> {
        assertTrue("the source folder must exist: ${sourceDirectory.absolutePath}", sourceDirectory.isDirectory)
        val files = sourceDirectory.listFiles().orEmpty().filter { file -> file.name.endsWith(".kt") }
        assertTrue("expected at least 8 Kotlin files in ${sourceDirectory.absolutePath}, found ${files.size}", files.size >= 8)
        return files.map { file -> file.name to file.readText() }
    }

    /** One line per (file, forbidden token) pair that is present. */
    private fun scan(sources: List<Pair<String, String>>): List<String> {
        val hits = ArrayList<String>()
        for ((name, text) in sources) {
            for (token in forbidden) {
                if (text.contains(token)) {
                    hits.add("$name contains $token")
                }
            }
        }
        return hits
    }

    @Test
    fun `no accounts file uses an unsafe comparison, output, logging or shared-state primitive`() {
        assertEquals("forbidden tokens found", emptyList<String>(), scan(readSources()))
    }

    @Test
    fun `the verifier hasher compares hashes with MessageDigest isEqual`() {
        val hasher = readSources().first { (name, _) -> name == "VerifierHasher.kt" }.second
        assertTrue("VerifierHasher.kt must call MessageDigest.isEqual", hasher.contains("MessageDigest.isEqual"))
    }

    @Test
    fun `the scan reports forbidden tokens in a text that holds them and none in a clean one`() {
        val dirty = scan(listOf("fake.kt" to "println(x); a.contentEquals(b)"))
        assertTrue("println( must be reported: $dirty", dirty.contains("fake.kt contains println("))
        assertTrue("contentEquals( must be reported: $dirty", dirty.contains("fake.kt contains contentEquals("))
        assertEquals("a clean text must report nothing", emptyList<String>(), scan(listOf("clean.kt" to "val x = 1")))
    }
}
