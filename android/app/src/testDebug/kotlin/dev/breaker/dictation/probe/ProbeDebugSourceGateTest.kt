package dev.breaker.dictation.probe

import dev.breaker.dictation.gates.AppSourceFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reads the app's debug sources and holds them to two rules: the size limit of two hundred lines for
 * each Kotlin file, and no network or microphone name in any file. Each rule runs on the real files and
 * on a planted sample that it must refuse.
 */
internal class ProbeDebugSourceGateTest {

    private val plantedPath: String = "kotlin/dev/breaker/dictation/probe/MicProbeState.kt"
    private val networkOrMicrophone: Regex = Regex("""\bjava\.net\b|\bINTERNET\b|\bRECORD_AUDIO\b|\bAudioRecord\b""")
    private val limit: Int = 200

    /** Every file under the app's debug source folder, by path below it, with forward slashes. */
    private fun debugFiles(): Map<String, String> {
        val root = File(AppSourceFiles.moduleRoot, "src/debug")
        check(root.isDirectory) { "probe: no debug source folder at $root" }
        val result: MutableMap<String, String> = LinkedHashMap()
        for (file in root.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
            result[file.relativeTo(root).path.replace(File.separatorChar, '/')] = file.readText(Charsets.UTF_8)
        }
        return result
    }

    private fun lineCount(text: String): Int = text.count { it == '\n' }

    private fun newlines(count: Int): String = "\n".repeat(count)

    /** One problem per Kotlin file at or over the limit of two hundred lines. */
    private fun sizeProblems(files: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in files) {
            if (name.endsWith(".kt") && lineCount(text) >= limit) {
                found.add("probe: $name has ${lineCount(text)} lines, over the limit of $limit")
            }
        }
        return found
    }

    /** One problem per file that names the network or the microphone. */
    private fun networkProblems(files: Map<String, String>): List<String> =
        files.filter { networkOrMicrophone.containsMatchIn(it.value) }.keys.map { "probe: $it names the network or the microphone" }

    @Test
    fun `every debug file is under two hundred lines`() {
        assertEquals("probe: debug files over their limit", emptyList<String>(), sizeProblems(debugFiles()))
        assertEquals(
            "probe: a planted 200 line file must be refused",
            1,
            sizeProblems(mapOf(plantedPath to newlines(200))).size,
        )
        assertEquals(
            "probe: a planted 199 line file must be allowed",
            0,
            sizeProblems(mapOf(plantedPath to newlines(199))).size,
        )
    }

    @Test
    fun `no debug file names the network or the microphone`() {
        assertEquals("probe: debug files naming the network or the microphone", emptyList<String>(), networkProblems(debugFiles()))
        for (planted in listOf(
            "import java.net.URL",
            "<uses-permission android:name=\"android.permission.INTERNET\" />",
            "val needed = RECORD_AUDIO",
            "val recorder = AudioRecord(source, rate, channels, format, size)",
        )) {
            assertEquals("probe: a planted line must be refused: $planted", 1, networkProblems(mapOf("planted.kt" to planted)).size)
        }
    }
}
