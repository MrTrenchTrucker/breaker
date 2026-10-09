package dev.breaker.dictation.service

import dev.breaker.dictation.service.ServiceGateSource.bodyOf
import dev.breaker.dictation.service.ServiceGateSource.edit
import dev.breaker.dictation.service.ServiceGateSource.read
import dev.breaker.dictation.service.ServiceGateSource.strip
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Reads the app's own files for the service gate tests: the module folder, a file by path (failing by name
 * when it is missing), source without comments and strings (a near copy of the lexer used by the other gate
 * tests), the body of a function, and the edit that builds a firing sample.
 */
internal object ServiceGateSource {
    const val SERVICE_DIR: String = "src/main/kotlin/dev/breaker/dictation/service/"
    const val MANIFEST_FILE: String = "src/main/AndroidManifest.xml"

    private fun moduleRoot(): File {
        var folder: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (folder != null) {
            if (File(folder, MANIFEST_FILE).isFile && File(folder, SERVICE_DIR).isDirectory) return folder
            folder = folder.parentFile
        }
        error("app: the gate found no module folder (manifest and service sources) at or above ${System.getProperty("user.dir")}")
    }

    fun read(path: String): String {
        val file = File(moduleRoot(), path)
        check(file.isFile) { "app: the service gate did not find $path under ${moduleRoot()}" }
        return file.readText()
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go quiet by a typo. */
    fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "app: a gate sample lost its text '$old'" }
        return text.replace(old, new)
    }

    /** Source without comments and without the text of strings and characters; refuses text it cannot read. */
    fun strip(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text.startsWith("//", i)) {
                while (i < text.length && text[i] != '\n') i++
            } else if (text.startsWith("/*", i)) {
                var depth = 0
                do {
                    if (text.startsWith("/*", i)) { depth++; i += 2 }
                    else if (text.startsWith("*/", i)) { depth--; i += 2 }
                    else { if (text[i] == '\n') out.append('\n'); i++ }
                } while (depth > 0 && i < text.length)
                check(depth == 0) { "app: a block comment is never closed" }
                out.append(' ')
            } else if (text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                check(end >= 0) { "app: a raw string is never closed" }
                i = end + 3
                out.append("\"\"")
            } else if (text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    check(text[i] != '\n') { "app: a string literal is never closed" }
                    i += if (text[i] == '\\') 2 else 1
                }
                check(i < text.length) { "app: a string literal is never closed" }
                i++
                out.append("\"\"")
            } else if (text[i] == '\'') {
                val end = text.indexOf('\'', i + if (text.getOrNull(i + 1) == '\\') 3 else 2)
                check(end >= 0) { "app: a character literal is never closed" }
                i = end + 1
                out.append("''")
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** The text between the braces of function [name], or null when it is missing or has no block body. */
    fun bodyOf(code: String, name: String): String? {
        val head = Regex("""\bfun\s+""" + name + """\s*\(""").find(code) ?: return null
        var index = head.range.last + 1
        var depth = 1
        while (index < code.length && depth > 0) {
            if (code[index] == '(') depth++
            if (code[index] == ')') depth--
            index++
        }
        val open = code.indexOf('{', index)
        if (open < 0 || code.substring(index, open).contains('=')) return null
        return blockAfter(code, open)
    }

    /** The text inside the brace that opens at [open], up to its partner; null when it never closes. */
    fun blockAfter(code: String, open: Int): String? {
        var depth = 1
        var index = open + 1
        while (index < code.length) {
            if (code[index] == '{') depth++
            if (code[index] == '}') depth--
            if (depth == 0) return code.substring(open + 1, index)
            index++
        }
        return null
    }

    /** [text] with every run of white space made one space and the ends trimmed. */
    fun squash(text: String): String = text.trim().replace(Regex("""\s+"""), " ")
}

/**
 * The Android adapter files and the manifest are the only service code no JVM test runs, so a text gate holds
 * them to the rules that keep the microphone service honest: the service never asks to be restarted, its end
 * is reported, a refused start is answered not thrown, nothing logs or sleeps, and the manifest declares the
 * service private with the microphone type. What the service does with a start request is gated by
 * [ServiceShapeGateTest]. The gate reads code only (comments and string literals are removed first). Each
 * rule has firing samples and a quiet sample, and every check on the real files fails by name when a file
 * is missing.
 */
internal class ServiceAdapterGateTest {

    private val serviceDir = ServiceGateSource.SERVICE_DIR
    private val serviceFile = serviceDir + "DictationForegroundService.kt"
    private val launcherFile = serviceDir + "AndroidServiceLauncher.kt"
    private val handlerFile = serviceDir + "ServiceStartHandler.kt"
    private val checkedFiles = listOf(serviceFile, launcherFile, handlerFile, serviceDir + "AndroidMicPermission.kt", serviceDir + "DictationNotification.kt")
    private val manifestFile = ServiceGateSource.MANIFEST_FILE

    private val returnValue = Regex("""\breturn\s+([A-Za-z_][A-Za-z0-9_]*)""")
    private val endedCall = Regex("""\bserviceEnded\s*\(\s*\)""")
    private val guardedStart = Regex(
        """\btry\s*\{[^}]*\bstartForegroundService\s*\([\s\S]*?\}\s*catch\s*\(\s*\w+\s*:\s*RuntimeException\s*\)\s*\{[^}]*\bRefused\b""",
    )
    private val bannedName = Regex("""\b(Log|println|print|printStackTrace|Toast|Thread|sleep|Handler)\b""")

    private val rules = setOf("NOT_STICKY", "DESTROY_ENDS", "START_GUARDED", "BANNED_NAME", "MANIFEST_SERVICE")

    private fun serviceProblems(source: String): List<String> {
        val code = strip(source)
        val bad = ArrayList<String>()
        val start = bodyOf(code, "onStartCommand")
        val returns = start?.let { returnValue.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
        if (returns.isEmpty() || returns.any { it != "START_NOT_STICKY" }) bad.add("NOT_STICKY")
        val destroy = bodyOf(code, "onDestroy")
        if (destroy == null || !endedCall.containsMatchIn(destroy)) bad.add("DESTROY_ENDS")
        return bad
    }

    private fun launcherProblems(source: String): List<String> {
        val launch = bodyOf(strip(source), "launch")
        return if (launch != null && guardedStart.containsMatchIn(launch)) emptyList() else listOf("START_GUARDED")
    }

    private fun bannedProblems(source: String): List<String> =
        if (bannedName.containsMatchIn(strip(source))) listOf("BANNED_NAME") else emptyList()

    private fun manifestProblems(xml: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val doc = try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            error("app: the manifest cannot be parsed: ${e.message}")
        }
        val services = doc.getElementsByTagName("service")
        if (services.length != 1) return listOf("MANIFEST_SERVICE")
        val service = services.item(0) as Element
        val right = service.getAttribute("android:name") == ".service.DictationForegroundService" &&
            service.getAttribute("android:exported") == "false" &&
            service.getAttribute("android:foregroundServiceType") == "microphone" &&
            service.getElementsByTagName("intent-filter").length == 0
        return if (right) emptyList() else listOf("MANIFEST_SERVICE")
    }

    private val sampleService = """
        package x
        import android.app.Service
        class DictationForegroundService : Service() {
            override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
                handler.onStart(intent?.action)
                return START_NOT_STICKY
            }
            override fun onDestroy() {
                controller.serviceEnded()
                super.onDestroy()
            }
        }
    """.trimIndent()

    private val sampleLauncher = """
        package x
        class AndroidServiceLauncher(private val context: Context) : ServiceLauncher {
            override fun launch(): LaunchResult {
                try {
                    val started = context.startForegroundService(serviceIntent())
                    return if (started == null) LaunchResult.Refused else LaunchResult.Launched
                } catch (e: RuntimeException) {
                    return LaunchResult.Refused
                }
            }
        }
    """.trimIndent()

    private val sampleManifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application>
                <service android:name=".service.DictationForegroundService" android:exported="false" android:foregroundServiceType="microphone" />
            </application>
        </manifest>
    """.trimIndent()

    private fun assertRealFilesPass(rule: String, found: List<String>) =
        assertTrue("app: the real adapter files break rule $rule: $found", found.none { it == rule })

    @Test
    fun `the service returns START_NOT_STICKY on every path`() {
        assertRealFilesPass("NOT_STICKY", serviceProblems(read(serviceFile)))
    }

    @Test
    fun `the service reports its end in onDestroy`() {
        assertRealFilesPass("DESTROY_ENDS", serviceProblems(read(serviceFile)))
    }

    @Test
    fun `the launcher catches RuntimeException around startForegroundService and answers Refused`() {
        assertRealFilesPass("START_GUARDED", launcherProblems(read(launcherFile)))
    }

    @Test
    fun `no service file logs, prints, shows a toast, makes a thread, sleeps or posts to a handler`() {
        for (path in checkedFiles) {
            val source = read(path)
            assertTrue("app: the adapter gate read no code in $path", strip(source).isNotBlank())
            assertEquals("app: $path names a forbidden call", emptyList<String>(), bannedProblems(source))
        }
    }

    @Test
    fun `the manifest service is private, microphone typed and has no intent filter`() {
        assertEquals("app: the manifest service breaks its rule", emptyList<String>(), manifestProblems(read(manifestFile)))
    }

    @Test
    fun `the unedited samples break no rule`() {
        assertEquals("app: the service sample was reported", emptyList<String>(), serviceProblems(sampleService))
        assertEquals("app: the launcher sample was reported", emptyList<String>(), launcherProblems(sampleLauncher))
        assertEquals("app: the manifest sample was reported", emptyList<String>(), manifestProblems(sampleManifest))
        assertEquals("app: the quiet sample was reported as banned", emptyList<String>(), bannedProblems(sampleService + sampleLauncher))
    }

    @Test
    fun `an edited copy that breaks one thing is reported under the rule that guards it`() {
        val found: Map<String, List<String>> = mapOf(
            "NOT_STICKY" to serviceProblems(edit(sampleService, "return START_NOT_STICKY", "return START_STICKY")),
            "NOT_STICKY (early return)" to serviceProblems(edit(sampleService, "handler.onStart(intent?.action)", "if (intent == null) return START_STICKY\n        handler.onStart(intent?.action)")),
            "DESTROY_ENDS" to serviceProblems(edit(sampleService, "controller.serviceEnded()", "controller.hold()")),
            "DESTROY_ENDS (no member)" to serviceProblems(edit(sampleService, "override fun onDestroy()", "override fun onStop()")),
            "START_GUARDED" to launcherProblems(edit(sampleLauncher, "catch (e: RuntimeException)", "catch (e: Exception)")),
            "START_GUARDED (no try)" to launcherProblems(edit(sampleLauncher, "try {", "run {")),
            "BANNED_NAME" to bannedProblems(edit(sampleService, "super.onDestroy()", "Log.w(TAG)")),
            "BANNED_NAME (sleep)" to bannedProblems(edit(sampleLauncher, "try {", "Thread.sleep(5)\n        try {")),
            "MANIFEST_SERVICE" to manifestProblems(edit(sampleManifest, "android:exported=\"false\"", "android:exported=\"true\"")),
            "MANIFEST_SERVICE (type)" to manifestProblems(edit(sampleManifest, " android:foregroundServiceType=\"microphone\"", "")),
            "MANIFEST_SERVICE (filter)" to manifestProblems(edit(sampleManifest, "/>\n    </application>", "><intent-filter /></service>\n    </application>")),
        )
        for ((label, keys) in found) {
            assertEquals("app: the gate missed the firing sample '$label'", listOf(label.substringBefore(" (")), keys)
        }
        assertEquals("app: a rule has no firing sample", rules, found.keys.map { it.substringBefore(" (") }.toSet())
    }

    @Test
    fun `names in comments and strings are not reported`() {
        val prose = "// Log and Thread.sleep are not used\n" + sampleLauncher + "\nval s = \"Handler Toast println\"\n/* Thread */\n"
        assertEquals("app: prose was reported as a forbidden call", emptyList<String>(), bannedProblems(prose))
    }

    @Test
    fun `a file that is missing or cannot be read as code is refused by name`() {
        val missing = assertThrows("app: a missing file must fail", IllegalStateException::class.java) { read(serviceDir + "Nope.kt") }
        assertTrue("app: the missing-file failure must name the file: ${missing.message}", missing.message.orEmpty().contains("Nope.kt"))
        assertThrows("app: an unterminated string must be refused", IllegalStateException::class.java) { strip("val s = \"never ends\n}") }
        assertThrows("app: an unterminated comment must be refused", IllegalStateException::class.java) { strip("class A /* never closed\n") }
    }
}
