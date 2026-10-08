package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No main file of this module can send the dictated text or the field's text
 * anywhere: not to a log, a toast, the clipboard, storage, the network, an
 * exception message or a string template.
 *
 * Those are the limits the text-insert decision states plainly: the service never
 * stores, logs or sends screen content, and the text it handles stays in memory
 * for the one insert. The scan reads code only, over every main file, pure and
 * device alike. It is text: a path to the outside that uses none of these names is
 * not seen here, so the pure files also stay free of the framework (see
 * `PureFilesScanTest`) and review reads the device files.
 */
internal class PrivacyScanTest {

    private val rules: Map<String, Regex> = mapOf(
        // logging and printing
        "Log" to Regex("""\bLog\b"""),
        "println" to Regex("""\bprintln\b"""),
        "print call" to Regex("""\bprint\s*\("""),
        "printStackTrace" to Regex("""printStackTrace"""),
        "Timber" to Regex("""Timber"""),
        "Logger" to Regex("""\bLogger\b"""),
        "System out or err" to Regex("""System\s*\.\s*(out|err)\b"""),
        "Toast" to Regex("""Toast"""),
        // clipboard
        "ClipboardManager" to Regex("""ClipboardManager"""),
        "ClipData" to Regex("""ClipData"""),
        "ClipboardWriter" to Regex("""ClipboardWriter"""),
        // storage
        "SharedPreferences" to Regex("""SharedPreferences"""),
        "DataStore" to Regex("""DataStore"""),
        "a File type" to Regex("""\bFile[A-Za-z]*\b"""),
        "SQLite" to Regex("""SQLite"""),
        "openFileOutput" to Regex("""openFileOutput"""),
        "Room" to Regex("""\bRoom\b"""),
        // network
        "java.net" to Regex("""java\s*\.\s*net"""),
        "URLConnection" to Regex("""URLConnection"""),
        "URL" to Regex("""\bURL\b"""),
        "Socket" to Regex("""Socket"""),
        "OkHttp" to Regex("""OkHttp"""),
        "HttpClient" to Regex("""HttpClient"""),
        "WebSocket" to Regex("""\bWebSocket\b"""),
        // throwing: a message could carry the text
        "throw" to Regex("""\bthrow\b"""),
        "error call" to Regex("""\berror\s*\("""),
        "require call" to Regex("""\brequire\s*\("""),
        "check call" to Regex("""\bcheck\s*\("""),
        "requireNotNull" to Regex("""requireNotNull"""),
        "checkNotNull" to Regex("""checkNotNull"""),
        "TODO call" to Regex("""TODO\s*\("""),
        "not-null assertion" to Regex("""!!"""),
        // reading what an exception holds: a message, a cause or a trace could carry the text
        "exception message" to Regex("""\.\s*message\b"""),
        "exception localizedMessage" to Regex("""\.\s*localizedMessage\b"""),
        "exception cause" to Regex("""\.\s*cause\b"""),
        "exception stackTrace" to Regex("""\.\s*stackTrace\b"""),
        "stackTraceToString" to Regex("""\bstackTraceToString\b"""),
        "exception suppressed" to Regex("""\.\s*(suppressed|suppressedExceptions)\b"""),
        "exception getter" to Regex("""\.\s*(getMessage|getLocalizedMessage|getCause|getStackTrace|getSuppressed)\s*\("""),
        // a data class prints all its fields
        "data class" to Regex("""\bdata\s+class\b"""),
    )

    private val samples: Map<String, String> = mapOf(
        "Log" to "object A { fun f() { Log.d(t, m) } }",
        "println" to "object A { fun f() { println(1) } }",
        "print call" to "object A { fun f() { print(1) } }",
        "printStackTrace" to "object A { fun f(e: Exception) { e.printStackTrace() } }",
        "Timber" to "object A { fun f() { Timber.d(m) } }",
        "Logger" to "object A { val l: Logger? = null }",
        "System out or err" to "object A { fun f() { System.err.write(1) } }",
        "Toast" to "object A { fun f() { Toast.makeText(c, m, 0) } }",
        "ClipboardManager" to "object A { val m: ClipboardManager? = null }",
        "ClipData" to "object A { val d = ClipData.newPlainText(a, b) }",
        "ClipboardWriter" to "object A { val w: ClipboardWriter? = null }",
        "SharedPreferences" to "object A { val p: SharedPreferences? = null }",
        "DataStore" to "object A { val s: DataStore<Int>? = null }",
        "a File type" to "object A { val f = FileOutputStream(p) }",
        "SQLite" to "object A { val d: SQLiteDatabase? = null }",
        "openFileOutput" to "object A { fun f() = openFileOutput(n, 0) }",
        "Room" to "object A { val r = Room.openDatabase(c) }",
        "java.net" to "import java.net.Proxy\nobject A",
        "URLConnection" to "object A { val c: URLConnection? = null }",
        "URL" to "object A { val u = URL(s) }",
        "Socket" to "object A { val s = Socket(h, 1) }",
        "OkHttp" to "object A { val c = OkHttpClient() }",
        "HttpClient" to "object A { val c = HttpClient() }",
        "WebSocket" to "object A { val w: WebSocket? = null }",
        "throw" to "object A { fun f() { throw X() } }",
        "error call" to "object A { fun f() { error(m) } }",
        "require call" to "object A { fun f() { require(x) } }",
        "check call" to "object A { fun f() { check(x) } }",
        "requireNotNull" to "object A { fun f() = requireNotNull(x) }",
        "checkNotNull" to "object A { fun f() = checkNotNull(x) }",
        "TODO call" to "object A { fun f() { TODO(\"later\") } }",
        "not-null assertion" to "object A { fun f(s: String?) = s!!.length }",
        "exception message" to "object A { fun f(e: Exception) = e.message }",
        "exception localizedMessage" to "object A { fun f(e: Exception) = e?.localizedMessage }",
        "exception cause" to "object A { fun f(e: Exception) = e.cause }",
        "exception stackTrace" to "object A { fun f(e: Exception) = e.stackTrace }",
        "stackTraceToString" to "object A { fun f(e: Exception) = e.stackTraceToString() }",
        "exception suppressed" to "object A { fun f(e: Exception) = e.suppressed }",
        "exception getter" to "object A { fun f(e: Exception) = e.getMessage() }",
        "data class" to "data class A(val t: String)",
    )

    private val quiet: Map<String, String> = mapOf(
        "a line comment" to "// Log.d println(x) throw error(1) data class\nobject A",
        "a KDoc" to "/**\n * Never calls Toast, ClipboardManager, java.net.URL or e.printStackTrace().\n */\nobject A",
        "a block comment" to "/* ClipData SharedPreferences !! requireNotNull(x) */ object A",
        "a string" to "object A { const val S = \"Log println Toast File SQLite !! throw check( error(\" }",
        "a raw string" to "object A { const val S = \"\"\"Socket OkHttp java.net HttpClient data class\"\"\" }",
        "a name in backticks" to "class T { @Test fun `a test that mentions Log and throw and File`() {} }",
        "longer words" to "object A { val Logistics = 1; val blog = 2; val printer = 3; val profile = 4; val Profile = 5; " +
            "val Rooms = 6; val URLs = 7; val Loggers = 8; val errorCode = 9; val rethrow = 10; val throwable = 11; " +
            "val database = 12; val todo = 13; val file = 14; val a = x != y; val b = x !== y; " +
            "fun checkpoint(i: Int) = i; fun recheck(i: Int) = i; fun requireAll(i: Int) = i; fun errorOf(i: Int) = i; " +
            "val printlnCount = 15; fun blueprint(i: Int) = i; val MyFile = 16; val ProfileFiles = 17; " +
            "fun suberror(i: Int) = i; fun prerequire(i: Int) = i }",
        "a word that ends in data before a class" to "import foo.metadata\nclass B",
        "names like an exception member that are not one" to "object A { val message = 1; val cause = 2; val stackTrace = 3; " +
            "fun f(message: Int, cause: Int) = message + cause; val a = b.messageCount; val c = d.causes; " +
            "val e = f.suppressedBy; val g = h.stackTraceDepth; val i = j.messages; fun getMessageText() = 1; val k = l.getCauseOf(1) }",
        "an exception member named in a comment or a string" to "// e.message e.cause e.stackTrace\n" +
            "object A { const val S = \"e.localizedMessage e.suppressed stackTraceToString e.getMessage(\" }",
        "System members that are not out or err" to "object A { val a = System.lineSeparator(); val b = System.outline }",
    )

    /** One line per main file that holds string template holes, with how many; none means no template anywhere. */
    private fun templateOffences(sources: Map<String, String>): List<String> {
        val found: MutableList<String> = ArrayList()
        for ((name, text) in sources) {
            val count: Int = SourceFiles.strip(text).templates.size
            if (count > 0) {
                found.add("$name: $count string template hole(s)")
            }
        }
        return found
    }

    private fun scannedMain(): Map<String, String> {
        val main: Map<String, String> = SourceFiles.mainSources()
        assertTrue("commit/accessibility: the privacy scan found no main file to read", main.isNotEmpty())
        assertTrue(
            "commit/accessibility: the privacy scan did not reach the two pure files, found ${main.keys}",
            main.keys.containsAll(setOf("InsertPlan.kt", "FieldNode.kt")),
        )
        return main
    }

    @Test
    fun `no main file names a log, a toast, the clipboard, storage, the network or a throw`() {
        assertEquals(
            "commit/accessibility: a main file could send the dictated text or the field's text out, or throw with a message",
            emptyList<String>(),
            SourceFiles.offences(scannedMain(), rules),
        )
    }

    @Test
    fun `no main file holds a string template hole`() {
        assertEquals(
            "commit/accessibility: a main file builds a string with a template, which could carry the text",
            emptyList<String>(),
            templateOffences(scannedMain()),
        )
    }

    @Test
    fun `each forbidden shape is reported on its own sample`() {
        assertEquals("commit/accessibility: a privacy rule has no firing sample, or a sample has no rule", rules.keys, samples.keys)
        for ((label, sample) in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to sample), rules)
            assertTrue(
                "commit/accessibility: the privacy scan did not report $label, found $found",
                found.any { it.startsWith("Sample.kt: $label x") },
            )
        }
    }

    @Test
    fun `the other spellings of reading an exception are reported under their rule`() {
        val more: Map<String, Pair<String, String>> = mapOf(
            "a safe-call message" to Pair("exception message", "object A { fun f(e: Exception?) = e?.message }"),
            "a message over a line break" to Pair("exception message", "object A { fun f(e: Exception) = e\n        .message }"),
            "a message after a dot at a line end" to Pair("exception message", "object A { fun f(e: Exception) = e.\n        message }"),
            "a cause over a line break" to Pair("exception cause", "object A { fun f(e: Exception) = e\n        .cause }"),
            "the suppressed list" to Pair("exception suppressed", "object A { fun f(e: Exception) = e.suppressedExceptions }"),
            "the message getter" to Pair("exception getter", "object A { fun f(e: Exception) = e.getLocalizedMessage() }"),
            "the cause getter" to Pair("exception getter", "object A { fun f(e: Exception) = e.getCause() }"),
            "the trace getter" to Pair("exception getter", "object A { fun f(e: Exception) = e.getStackTrace() }"),
            "the suppressed getter" to Pair("exception getter", "object A { fun f(e: Exception) = e.getSuppressed() }"),
            "the plain message getter" to Pair("exception getter", "object A { fun f(e: Exception) = e . getMessage ( ) }"),
        )
        for ((label, pair) in more) {
            val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to pair.second), rules)
            assertTrue(
                "commit/accessibility: the privacy scan did not report " + label + " under " + pair.first + ", found " + found,
                found.any { it.startsWith("Sample.kt: " + pair.first + " x") },
            )
        }
    }

    @Test
    fun `a forbidden name inside a template hole is code and is reported`() {
        val source: String = "object A { fun f() = \"x \${Log.d(t, m)}\" }"
        val found: List<String> = SourceFiles.offences(mapOf("Sample.kt" to source), rules)
        assertEquals("commit/accessibility: a Log call inside a template hole was not reported", listOf("Sample.kt: Log x1"), found)
    }

    @Test
    fun `the same names in comments, strings, backticks or longer words are not reported`() {
        for ((label, source) in quiet) {
            assertEquals(
                "commit/accessibility: the privacy scan flagged $label",
                emptyList<String>(),
                SourceFiles.offences(mapOf("Sample.kt" to source), rules),
            )
        }
    }

    @Test
    fun `a string template is reported with the file that holds it`() {
        val shapes: Map<String, String> = mapOf(
            "a simple name" to "object A { val s = \"x \$name\" }",
            "a braced expression" to "object A { val s = \"x \${a + b}\" }",
            "a name in a raw string" to "object A { val s = \"\"\"x \$name\"\"\" }",
            "a string inside a hole" to "object A { val s = \"x \${f(\"y\")}\" }",
        )
        for ((label, source) in shapes) {
            assertEquals(
                "commit/accessibility: the template scan missed $label",
                listOf("Sample.kt: 1 string template hole(s)"),
                templateOffences(mapOf("Sample.kt" to source)),
            )
        }
        val two: String = "object A { val s = \"\$a and \$b\" }"
        assertEquals(
            "commit/accessibility: the template scan did not count two holes",
            listOf("Sample.kt: 2 string template hole(s)"),
            templateOffences(mapOf("Sample.kt" to two)),
        )
    }

    @Test
    fun `a dollar sign that starts no template is not reported`() {
        val fine: Map<String, String> = mapOf(
            "an escaped dollar" to "object A { val s = \"cost \\\$5\" }",
            "a dollar before a digit or a space in a raw string" to "object A { val s = \"\"\"price \$5 and \$ x\"\"\" }",
            "a dollar as a character literal" to "object A { val c = '\$' }",
            "a dollar at the end of a string" to "object A { val s = \"cost\$\" }",
            "a template only in a comment" to "// \"x \$name\"\nobject A",
        )
        for ((label, source) in fine) {
            assertEquals(
                "commit/accessibility: the template scan flagged $label",
                emptyList<String>(),
                templateOffences(mapOf("Sample.kt" to source)),
            )
        }
    }
}
