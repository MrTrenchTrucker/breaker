package dev.breaker.server.syncapi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// main() has no unit test (it needs a socket), so this pins the one call that decides which git tag the
// admin health route answers: the tag comes from the configuration and from nowhere else.
class SyncApiMainWiringTest {

    // Gradle runs tests with the module folder as the working directory.
    private val mainFile = File("src/main/kotlin/dev/breaker/server/syncapi/SyncApiMain.kt")

    @Test
    fun `main hands the configured git tag to the application module`() {
        assertTrue("the source file must exist: ${mainFile.absolutePath}", mainFile.isFile)
        val text = mainFile.readText()
        val call = "syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore, config.manifestFile, config.gitTag, config.whisperHealthUrl)"
        assertEquals("calls that pass the configured git tag", 1, Regex(Regex.escape(call)).findAll(text).count())
        assertEquals("calls of the application module", 1, Regex("syncApiModule\\(").findAll(text).count())
    }

    // A source pin, not a behaviour test: the sweep itself is tested on its own; this only keeps main from dropping the launch.
    @Test
    fun `main launches the rate limit counter sweep`() {
        assertTrue("the source file must exist: ${mainFile.absolutePath}", mainFile.isFile)
        val text = mainFile.readText()
        assertEquals(
            "the sweep is launched once with the counter store",
            1,
            Regex("launch \\{ runRateLimitCounterSweep\\(counterStore\\) \\}").findAll(text).count(),
        )
    }
}
