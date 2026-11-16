package dev.breaker.server.whisper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Structural fence (seal scope, section 4). whisper-server seals a copy of an agent-token
// result to the owner's box public key (ADR-018, Phase 19/20); it must never OPEN a box.
// Nothing here can be seen from a behaviour test, and a rule with no control passes forever,
// so the second test shows the scan can report the token it forbids. Same scan style as
// SourceScanTest.kt. This file is separate from SourceScanTest.kt because that file is
// already 385 lines, over the 300-line cap this slice works under.
internal class SealedBoxOpenFenceTest {

    // The server seals (SealedBox.seal) and must never open. Forbidding the whole SealedBox
    // name would ban the sealer too, so the token is the opening call alone.
    private val forbidden = listOf("SealedBox.open")

    // Gradle runs tests with the module folder as the working directory.
    private val sourceRoot = File("src/main/kotlin/dev/breaker/server/whisper")

    /** Pairs of the path below the source root (with forward slashes) and the file text. */
    private fun readSources(): List<Pair<String, String>> {
        assertTrue("whisper-server: the source folder must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val files = sourceRoot.walkTopDown().filter { file -> file.isFile && file.name.endsWith(".kt") }.toList()
        assertTrue("whisper-server: expected at least 12 Kotlin files, found ${files.size}", files.size >= 12)
        return files.map { file -> file.relativeTo(sourceRoot).invariantSeparatorsPath to file.readText() }
    }

    /** One line per (file, token) pair that is present. */
    private fun scan(sources: List<Pair<String, String>>, tokens: List<String>): List<String> {
        val hits = ArrayList<String>()
        for ((path, text) in sources) {
            for (token in tokens) {
                if (text.contains(token)) {
                    hits.add("$path contains $token")
                }
            }
        }
        return hits
    }

    @Test
    fun `no main file opens a sealed box`() {
        assertEquals(
            "whisper-server: SealedBox.open in src/main — the server seals, it never opens",
            emptyList<String>(),
            scan(readSources(), forbidden),
        )
    }

    @Test
    fun `the fence reports SealedBox open and nothing else`() {
        assertEquals(
            "whisper-server: a main file that opens a box must be reported",
            listOf("worker/Box.kt contains SealedBox.open"),
            scan(listOf("worker/Box.kt" to "val plain = SealedBox.open(box, recipientPrivateKey)"), forbidden),
        )
        assertEquals(
            "whisper-server: a clean main file must report nothing",
            emptyList<String>(),
            scan(listOf("worker/QueueWorker.kt" to "val sealed = SealedBox.seal(ownerPublic, plaintext, cap)"), forbidden),
        )
        assertEquals(
            "whisper-server: a bare SealedBox name (the sealer's own import) must not be reported",
            emptyList<String>(),
            scan(listOf("worker/Sealer.kt" to "import dev.breaker.dictation.seal.SealedBox"), forbidden),
        )
    }
}
