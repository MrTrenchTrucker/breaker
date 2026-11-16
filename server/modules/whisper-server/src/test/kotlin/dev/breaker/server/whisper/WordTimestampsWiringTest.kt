package dev.breaker.server.whisper

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

// main() is not reachable from a behaviour test, so the one wiring fact that cannot be observed
// from the outside is read from the source the same way SourceScanTest reads it: the exact text
// must occur in WhisperServer.kt. The forwarder side is read the same way, so a mutant that
// drops the setting or hard codes it cannot pass by tautology.
internal class WordTimestampsWiringTest {

    private val sourceRoot = File("src/main/kotlin/dev/breaker/server/whisper")

    @Test
    fun `main threads the word timestamp setting into the downstream config`() {
        val source = sourceRoot.resolve("WhisperServer.kt")
        assertTrue(
            "whisper-server: WhisperServer.kt must exist: ${source.absolutePath}",
            source.isFile,
        )
        val text = source.readText()
        assertTrue(
            "whisper-server: main() must pass wordTimestamps = config.wordTimestamps into DownstreamConfig",
            text.contains("wordTimestamps = config.wordTimestamps"),
        )
    }

    @Test
    fun `the word timestamp setting is carried on the downstream config and read by the forwarder`() {
        val forwarder = sourceRoot.resolve("worker/HttpTranscriptionForwarder.kt").readText()
        assertTrue(
            "whisper-server: DownstreamConfig must carry a wordTimestamps flag defaulting to false",
            forwarder.contains("wordTimestamps: Boolean = false"),
        )
        assertTrue(
            "whisper-server: the multipart writer must read config.wordTimestamps",
            forwarder.contains("config.wordTimestamps"),
        )
    }

    @Test
    fun `the word timestamp setting is read from the environment once`() {
        val config = sourceRoot.resolve("config/WhisperConfig.kt").readText()
        assertTrue(
            "whisper-server: WhisperConfig must name the environment variable",
            config.contains("BREAKER_WHISPER_WORD_TIMESTAMPS"),
        )
        assertTrue(
            "whisper-server: WhisperConfig must carry the parsed flag",
            config.contains("wordTimestamps: Boolean = false"),
        )
    }
}
