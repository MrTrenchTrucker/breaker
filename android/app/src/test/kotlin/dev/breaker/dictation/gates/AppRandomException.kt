package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one narrow exception to the random rule of [AppPrivacyScanTest]: the install nonce file may name
 * java.security.SecureRandom, by exactly the import line `import java.security.SecureRandom`, and no other random type or spelling.
 * Its offence line is lifted only when the text of that file passes the exact-spelling check. Every other
 * offence line of the file is still reported, and the same name in any other folder gets no exception.
 */
internal object AppRandomException {

    /** The one file the exception applies to, by its path below the main package folder. */
    internal const val file: String = "wiring/InstallNonce.kt"

    /** Why the file may draw random bytes: its per-session nonce comes from the platform secure generator. */
    internal const val reason: String = "the install nonce draws its bytes from java.security.SecureRandom, the one allowed random source"

    private const val secureImport: String = "import java.security.SecureRandom"
    private val bareRandom: Regex = Regex("""\bRandom\b""")
    private val threadLocal: Regex = Regex("""\bThreadLocalRandom\b""")
    private val kotlinRandom: Regex = Regex("""\bkotlin\s*\.\s*random\b""")
    private val secureName: Regex = Regex("""[\w.]*SecureRandom""")

    /** True when [line], one offence line of the scan, is the Random label of [file] and that file names SecureRandom exactly. */
    fun lifts(line: String, sources: Map<String, String>): Boolean {
        val prefix: String = "$file: Random x"
        if (!line.startsWith(prefix)) return false
        val digits: String = line.removePrefix(prefix)
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return false
        val text: String = sources[file] ?: return false
        return exactSpelling(text)
    }

    /** True when the code of [source] names java.security.SecureRandom and no other random spelling. */
    internal fun exactSpelling(source: String): Boolean {
        val code: String = AppSourceFiles.strip(source).code
        if (bareRandom.containsMatchIn(code) || threadLocal.containsMatchIn(code) || kotlinRandom.containsMatchIn(code)) {
            return false
        }
        val imports: List<String> = code.lines().map { it.trim() }.filter { it.startsWith("import ") }
        if (imports.any { it.contains("*") }) return false
        if (imports.filter { it.contains("SecureRandom") } != listOf(secureImport)) return false
        return secureName.findAll(code).all { it.value == "SecureRandom" || it.value == "java.security.SecureRandom" }
    }
}

/** The exception, its exact-spelling check and the scan's filter for it. The scan's rules are read from [AppPrivacyScanTest]. */
internal class AppRandomExceptionTest {

    private val scan: AppPrivacyScanTest = AppPrivacyScanTest()
    private val path: String = AppRandomException.file
    private val randomLine: String = "    private val random: SecureRandom = SecureRandom()"

    private fun real(): String = scan.mainKotlin().getValue(path)

    /** The Random offence lines that the scan reports for the file [name] holding [text]. */
    private fun randomOffences(name: String, text: String): List<String> =
        scan.codeOffences(mapOf(name to text)).filter { it.startsWith("$name: Random x") }

    /** The real text with one old text replaced by one new text; fails by name when the old text is missing. */
    private fun oneChange(old: String, new: String): String {
        val text: String = real()
        check(text.contains(old)) { "app: the install nonce file has no text \"$old\" to change" }
        return text.replace(old, new)
    }

    @Test
    fun `the real install nonce file is quiet through the code offences`() {
        assertEquals("app: the install nonce file is reported for its SecureRandom", emptyList<String>(), scan.codeOffences(mapOf(path to real())))
    }

    @Test
    fun `the real install nonce file names exactly java security SecureRandom and no other random spelling`() {
        val raw: List<String> = AppSourceFiles.offences(mapOf(path to real()), scan.ruleMap).filter { it.startsWith("$path: Random x") }
        assertEquals("app: the real file has no Random offence line to lift", 1, raw.size)
        assertTrue("app: the exact-spelling check refused the real file", AppRandomException.lifts(raw.single(), mapOf(path to real())))
        assertTrue("app: the real file was not read as the SecureRandom file", AppRandomException.exactSpelling(real()))
    }

    @Test
    fun `each single change that names another random type is reported in the install nonce file`() {
        val samples: Map<String, String> = mapOf(
            "java.util.Random" to oneChange(randomLine, "    private val random: java.util.Random = java.util.Random()"),
            "kotlin.random.Random" to oneChange(randomLine, "    private val random: kotlin.random.Random = kotlin.random.Random.Default"),
            "ThreadLocalRandom" to oneChange(randomLine, "    private val random: java.util.concurrent.ThreadLocalRandom = java.util.concurrent.ThreadLocalRandom.current()"),
            "a bare Random call" to oneChange(randomLine, "    private val random: SecureRandom = Random()"),
            "a SecureRandom from another import" to oneChange("import java.security.SecureRandom", "import dev.example.crypto.SecureRandom"),
            "an alias of the import" to oneChange("import java.security.SecureRandom", "import java.security.SecureRandom as Entropy"),
            "a wildcard import" to oneChange("import java.security.SecureRandom", "import java.security.*"),
        )
        for ((label, text) in samples) {
            assertTrue("app: the install nonce file let off $label", randomOffences(path, text).isNotEmpty())
        }
    }

    @Test
    fun `another wiring host or service file that uses java security SecureRandom is reported`() {
        val text: String = real()
        for (other in listOf("wiring/OtherNonce.kt", "host/OtherNonce.kt", "service/OtherNonce.kt")) {
            assertEquals("app: $other was let off its SecureRandom", 1, randomOffences(other, text).size)
        }
    }

    @Test
    fun `a file with the same name in another folder gets no exception`() {
        val text: String = real()
        for (other in listOf("host/InstallNonce.kt", "wiring/deep/InstallNonce.kt")) {
            assertEquals("app: $other was let off its SecureRandom", 1, randomOffences(other, text).size)
            assertFalse("app: $other was lifted by the install nonce exception", AppRandomException.lifts("$other: Random x3", mapOf(other to text)))
        }
    }

    @Test
    fun `the install nonce file is still held to every other rule`() {
        val planted = "\nobject Planted { fun f() { Log.d(t, m); sleep(1); val x = Timer(); println(1); Toast.makeText(c, m, 0); val n = System.currentTimeMillis() } }"
        val expected: Set<String> = setOf("Log", "sleep call", "Timer", "println", "Toast", "clock read").map { "$path: $it x1" }.toSet()
        assertEquals("app: the install nonce file let off a planted rule", expected, scan.codeOffences(mapOf(path to real() + planted)).toSet())
    }

    @Test
    fun `the exception names the one install nonce path and a reason that is not blank`() {
        assertEquals("app: the exception names another file", "wiring/InstallNonce.kt", AppRandomException.file)
        assertTrue("app: the exception has no reason", AppRandomException.reason.isNotBlank())
    }

    @Test
    fun `the thread exception table does not lift the install nonce file`() {
        assertFalse("app: the install nonce file was added to the thread table", AppThreadExceptions.lifted.containsKey(path))
        assertFalse("app: the thread table lifts the random label of the install nonce file", AppThreadExceptions.lifts("$path: Random x3"))
    }
}
