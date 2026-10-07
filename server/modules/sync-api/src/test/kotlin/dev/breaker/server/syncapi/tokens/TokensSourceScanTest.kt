package dev.breaker.server.syncapi.tokens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural test. A token must never reach a log, a printed line or a hand-written
// comparison, and the store must never see the raw text of a secret. None of that is
// observable from a behaviour test, so this guards the shape of the code instead.
internal class TokensSourceScanTest {

    private val forbidden = listOf(
        "println(", "System.out", "System.err", "Logger", "slf4j", "printStackTrace",
        "contentEquals(", "Arrays.equals(", "MessageDigest.isEqual", "Arrays.compare",
        "Thread", "synchronized", "OR REPLACE", "ON CONFLICT", "REPLACE INTO",
    )

    // Only the secret type may produce the raw text; any other use is a way to store or print it.
    private val rawTextAccess = listOf(".reveal(", "::reveal")

    // Gradle runs tests with the module folder as the working directory.
    private val sourceDirectory = File("src/main/kotlin/dev/breaker/server/syncapi/tokens")

    private fun readSources(): List<Pair<String, String>> {
        assertTrue("sync-api: the source folder must exist: ${sourceDirectory.absolutePath}", sourceDirectory.isDirectory)
        val files = sourceDirectory.listFiles().orEmpty().filter { file -> file.name.endsWith(".kt") }
        assertTrue("sync-api: expected at least 4 Kotlin files in ${sourceDirectory.absolutePath}, found ${files.size}", files.size >= 4)
        return files.map { file -> file.name to file.readText() }
    }

    /** One line per (file, token) pair that is present. */
    private fun scan(sources: List<Pair<String, String>>, tokens: List<String>, skipFile: String? = null): List<String> {
        val hits = ArrayList<String>()
        for ((name, text) in sources) {
            if (name == skipFile) {
                continue
            }
            for (token in tokens) {
                if (text.contains(token)) {
                    hits.add("$name contains $token")
                }
            }
        }
        return hits
    }

    @Test
    fun `no token file prints, logs, compares by hand, shares state or overwrites a row`() {
        assertEquals("sync-api: forbidden tokens found", emptyList<String>(), scan(readSources(), forbidden))
    }

    @Test
    fun `no file other than the secret type reads the raw token text`() {
        assertEquals(
            "sync-api: raw text access outside TokenSecret.kt",
            emptyList<String>(),
            scan(readSources(), rawTextAccess, skipFile = "TokenSecret.kt"),
        )
    }

    @Test
    fun `the store looks a token up by comparing hash to hash in the database`() {
        val store = readSources().first { (name, _) -> name == "TokenStore.kt" }.second
        assertTrue("sync-api: TokenStore.kt must select with token_hash = ?", store.contains("token_hash = ?"))
    }

    @Test
    fun `the scan reports forbidden tokens in a text that holds them and none in a clean one`() {
        for (token in forbidden) {
            val dirty = scan(listOf("fake.kt" to "val x = \"$token\""), forbidden)
            assertTrue("sync-api: $token must be reported: $dirty", dirty.contains("fake.kt contains $token"))
        }
        for (token in rawTextAccess) {
            val dirty = scan(listOf("fake.kt" to "val x = a$token"), rawTextAccess)
            assertTrue("sync-api: $token must be reported: $dirty", dirty.contains("fake.kt contains $token"))
        }
        assertEquals("sync-api: a clean text must report nothing", emptyList<String>(), scan(listOf("clean.kt" to "val x = 1"), forbidden))
        assertEquals(
            "sync-api: the skipped file must not be reported",
            emptyList<String>(),
            scan(listOf("TokenSecret.kt" to "x.reveal()"), rawTextAccess, skipFile = "TokenSecret.kt"),
        )
    }
}
