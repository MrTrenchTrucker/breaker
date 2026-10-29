package dev.breaker.dictation.updater

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rules of the platform shells as source text. Only the four shell files name the framework, only the log
 * shell names the platform log class, and only the facade exposes the platform context or the commit sender in a
 * public signature. Each rule reads the code with comments and documentation removed, and each has a sample that must
 * be flagged and a sample that must pass.
 */
internal class ShellHygieneTest {
    private val shells = setOf("PackageInstallerAdapter.kt", "PackageInfoInstalledVersion.kt", "UpdateInstallFacade.kt", "LogcatDropLog.kt")

    private val mainRoot = File("src/main/kotlin")

    /** The text with block comments, documentation and line comments removed, so that only code is compared. */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ").replace(Regex("//[^\\n]*"), " ")

    /** The main sources as code text, by file name; fails when the folder holds no facade, so the scan cannot run blind. */
    private fun mainCode(): Map<String, String> {
        val texts = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to stripComments(it.readText()) }
        assertTrue("the shell scan found no facade source under the main folder", texts.containsKey("UpdateInstallFacade.kt"))
        return texts
    }

    /** The names of the files whose code names a class of the framework. */
    private fun namesFramework(sources: Map<String, String>): Set<String> =
        sources.filterValues { stripComments(it).contains("android.") }.keys

    /** The names of the files whose code names the platform log class. */
    private fun namesLog(sources: Map<String, String>): Set<String> {
        val log = Regex("android\\.util\\.Log|\\bLog\\.[vdiwe]\\(")
        return sources.filterValues { log.containsMatchIn(stripComments(it)) }.keys
    }

    /** The names of the files with a public line that names the platform context or the commit sender. */
    private fun namesPublicPlatform(sources: Map<String, String>): Set<String> =
        sources.filterValues { text ->
            stripComments(text).lines().any { line ->
                line.contains("public ") && (line.contains("Context") || line.contains("IntentSender"))
            }
        }.keys

    @Test
    fun `only the four shell files name the framework`() {
        assertEquals(
            "the scan must flag an android name outside the shells",
            setOf("DropMessage.kt"),
            namesFramework(mapOf("DropMessage.kt" to "import android.util.Log\n")),
        )
        assertEquals(
            "an android name inside a comment must pass",
            emptySet<String>(),
            namesFramework(mapOf("DropMessage.kt" to "// android.util.Log is only mentioned here\nval a = 1\n")),
        )
        assertEquals(
            "the Android word without a dot must pass",
            emptySet<String>(),
            namesFramework(mapOf("DropMessage.kt" to "val platform = \"the Android platform\"\n")),
        )
        assertEquals("the framework names are the four shells", shells, namesFramework(mainCode()))
    }

    @Test
    fun `android util log is named in one file only`() {
        assertEquals(
            "the scan must flag a log call in a shell other than the log file",
            setOf("PackageInstallerAdapter.kt"),
            namesLog(mapOf("PackageInstallerAdapter.kt" to "Log.i(\"x\", \"y\")\n")),
        )
        assertEquals(
            "a name that ends in Log must pass",
            emptySet<String>(),
            namesLog(mapOf("A.kt" to "val c = MyLog.w(1)\n")),
        )
        assertEquals(
            "a log call inside a comment must pass",
            emptySet<String>(),
            namesLog(mapOf("A.kt" to "// Log.w(\"x\", \"y\") is not called here\nval a = 1\n")),
        )
        assertEquals("the log class is named in the log file only", setOf("LogcatDropLog.kt"), namesLog(mainCode()))
    }

    @Test
    fun `context and intent sender are public only in the facade`() {
        assertEquals(
            "the scan must flag a public context in another file",
            setOf("PackageInfoInstalledVersion.kt"),
            namesPublicPlatform(mapOf("PackageInfoInstalledVersion.kt" to "public fun probe(context: Context) = 1\n")),
        )
        assertEquals(
            "a context that is not public must pass",
            emptySet<String>(),
            namesPublicPlatform(mapOf("A.kt" to "internal class X(private val context: Context)\n")),
        )
        assertEquals(
            "a documentation line that says public next to Context must pass",
            emptySet<String>(),
            namesPublicPlatform(mapOf("A.kt" to "/** The public Context of the app. */\nval a = 1\n")),
        )
        assertEquals(
            "the public platform names are in the facade only",
            setOf("UpdateInstallFacade.kt"),
            namesPublicPlatform(mainCode()),
        )
    }

    @Test
    fun `the drop log line is the fixed tag and the drop text only`() {
        val pinned = "Log.w(DROP_LOG_TAG, dropMessage(platformSessionId, recordedSessionId))"
        val logLines = mainCode()["LogcatDropLog.kt"].orEmpty().lines().map { it.trim() }
        assertEquals("the log call appears once, exactly as pinned", 1, logLines.count { it == pinned })
        val extended = "Log.w(DROP_LOG_TAG, dropMessage(platformSessionId, recordedSessionId) + url)"
        assertEquals("a log call with extra text must not match the pinned line", 0, listOf(extended).count { it == pinned })
        val text = mainCode()["DropMessage.kt"].orEmpty()
        for (literal in listOf("\"final status for session \"", "\" dropped; no session recorded\"", "\" dropped; recorded session \"")) {
            assertEquals("the drop text holds the literal once: $literal", 1, text.split(literal).size - 1)
        }
    }
}
