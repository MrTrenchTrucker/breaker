package dev.breaker.dictation.audio

/*
 * The text rules behind AudioConfinementGateTest, kept apart so that file stays
 * short: the comment and string stripper, the public declaration pattern, the
 * per-field volatile check and the fixed texts the self-tests read.
 */

/** The fields of the adapter that a platform callback writes and the capture thread reads. */
internal val CALLBACK_FIELDS = listOf("lost", "watchedId")

/**
 * A declaration at column 0 with no `private`, `internal` or `protected` marker,
 * which Kotlin treats as public: any annotations (`@a`, `@get:a`, `@pkg.a`, with
 * or without arguments), then a modifier run, then a declaration keyword. A
 * visibility marker anywhere in the modifier run makes it not public.
 */
internal val TOP_LEVEL_PUBLIC = Regex(
    """^(?:@[\w.:]+(?:\([^)]*\))?\s+)*""" +
        """(?!(?:(?:public|final|data|enum|sealed|open|abstract|const|inline|value|annotation|lateinit|""" +
        """tailrec|operator|infix|external|expect|actual|suspend)\s+)*(?:private|internal|protected)\b)""" +
        """(?:(?:public|final|data|enum|sealed|open|abstract|const|inline|value|annotation|lateinit|""" +
        """tailrec|operator|infix|external|expect|actual|suspend|fun(?=\s+interface))\s+)*""" +
        """(?:class|object|interface|typealias|fun|val|var)\b""",
)

/** The public top level declaration lines of [text], comments and strings removed, trimmed. */
internal fun publicTopLevelIn(text: String): List<String> =
    codeOf(text).lines().filter { TOP_LEVEL_PUBLIC.containsMatchIn(it) }.map { it.trim() }

/** The names in [fields] that are not annotated `@Volatile` directly above their own declaration in [text]. */
internal fun unmarkedFields(text: String, fields: List<String>): List<String> {
    val code = codeOf(text)
    return fields.filterNot { Regex("""@Volatile\s+private\s+var\s+${Regex.escape(it)}\b""").containsMatchIn(code) }
}

/** An adapter shaped as expected: the factory is the only public type and both fields are marked. */
internal val GOOD_ADAPTER_TEXT = listOf(
    "package a",
    "",
    "import android.media.AudioRecord",
    "",
    "/** The factory. */",
    "object AndroidMicSource {",
    "    fun create(): Int = 1",
    "}",
    "",
    "private const val API_31 = 31",
    "",
    "private fun helper(): Int = 2",
    "",
    "internal class AudioRecordMicPort {",
    "    @Volatile",
    "    private var watchedId = -1",
    "",
    "    @Volatile",
    "    private var lost = false",
    "}",
    "",
).joinToString("\n")

/** One public top level declaration of every form the rule must see, each on its own line. */
internal val PUBLIC_FORMS = listOf(
    "const val LIMIT = 1",
    "val shared = 1",
    "var counter = 0",
    "fun helper() {}",
    "class Plain",
    "object Single",
    "interface Contract",
    "typealias Alias = Int",
    "enum class Mode { A }",
    "data class Pair2(val a: Int)",
    "inline fun quick() {}",
    "public fun explicit() {}",
    "fun interface Callback { fun run() }",
    "sealed class Shape",
    "@get:JvmSynthetic val x = 1",
    "@kotlin.jvm.JvmStatic fun f() {}",
    "final class Plain",
    "@JvmName(foo) fun annotated() {}",
    "open class Base",
    "abstract class Template",
    "annotation class Marker",
    "value class Id(val v: Int)",
    "lateinit var late: String",
)

/** The same shapes behind a visibility marker, which are not public. */
internal val HIDDEN_FORMS = listOf(
    "private const val LIMIT = 1",
    "private val shared = 1",
    "internal var counter = 0",
    "private fun helper() {}",
    "internal class Plain",
    "private object Single",
    "internal interface Contract",
    "private typealias Alias = Int",
    "internal enum class Mode { A }",
    "internal fun interface Callback { fun run() }",
    "@Suppress(\"x\") private fun f() {}",
    "@JvmStatic internal val y = 2",
    "@get:JvmSynthetic internal val z = 3",
    "@kotlin.jvm.JvmStatic private fun g() {}",
    "inline internal fun h() {}",
    "protected fun p() {}",
)

/** [text] with comments (nested blocks included) and string literals removed; template holes are kept. */
internal fun codeOf(text: String): String = StringBuilder(text.length).also { scan(text, 0, it, false) }.toString()

/** Copies code from [from] into [out]; in a template hole stops after the closing brace. */
private fun scan(t: String, from: Int, out: StringBuilder, hole: Boolean): Int {
    var i = from
    var depth = 0
    while (i < t.length) {
        val c = t[i]
        when {
            t.startsWith("//", i) -> i = t.indexOf('\n', i).let { if (it < 0) t.length else it }
            t.startsWith("/*", i) -> i = endOfBlockComment(t, i)
            t.startsWith("\"\"\"", i) -> i = literal(t, i + 3, "\"\"\"", out)
            c == '"' -> i = literal(t, i + 1, "\"", out)
            c == '\'' -> i = endOfCharLiteral(t, i)
            hole && c == '{' -> { depth++; out.append(c); i++ }
            hole && c == '}' -> {
                if (depth == 0) return i + 1
                depth--; out.append(c); i++
            }
            else -> { out.append(c); i++ }
        }
    }
    return i
}

/** Skips a literal body from [from]; template holes are copied into [out]. Returns the index past its end. */
private fun literal(t: String, from: Int, close: String, out: StringBuilder): Int {
    val raw = close.length == 3
    var i = from
    while (i < t.length) {
        when {
            !raw && t[i] == '\\' -> i += 2
            !raw && t[i] == '\n' -> return i
            t.startsWith(close, i) -> return i + close.length
            t.startsWith("\${", i) -> { out.append(' '); i = scan(t, i + 2, out, true); out.append(' ') }
            else -> i++
        }
    }
    return t.length
}

/** The index past a block comment opening at [from]; blocks nest in Kotlin. */
private fun endOfBlockComment(t: String, from: Int): Int {
    var depth = 0
    var i = from
    while (i < t.length) {
        when {
            t.startsWith("/*", i) -> { depth++; i += 2 }
            t.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return i }
            else -> i++
        }
    }
    return t.length
}

/** The index past a character literal opening at [from]. */
private fun endOfCharLiteral(t: String, from: Int): Int {
    val end = t.indexOf('\'', if (t.getOrNull(from + 1) == '\\') from + 3 else from + 2)
    return if (end < 0) from + 1 else end + 1
}
