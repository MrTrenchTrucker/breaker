package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log text of a spoken send that cannot start is fixed: the reason constant in the composition root is one
 * plain literal line, the hook on the root is called once in the main sources and only with that constant, and
 * the application class passes its warn function nothing but a plain literal or the hook argument. Each rule
 * holds on the real files and has firing samples that show it fires and quiet samples that show it holds.
 *
 * The rules read raw text where a template name would be blanked by [AppSourceFiles.strip], and code where a
 * name must not be read from a comment or a string. Kept apart from the privacy scan to stay under the line cap.
 */
internal class PhraseReasonGateTest {

    /** The one spelling of the constant: a plain literal with no template, no escape and no concatenation, on its own line. */
    private val constant: Regex =
        Regex("""(?m)^internal\s+const\s+val\s+PHRASE_UNAVAILABLE_REASON\s*:\s*String\s*=\s*"[^"\\$]*"[ \t]*$""")

    /** Any call of the hook: the safe call, the non-null call, or the call written without a dot. */
    private val hookAny: Regex =
        Regex("""\bphraseWarn\b\s*(?:\?\.|!!\.|\.)?\s*invoke\b|\bphraseWarn\s*\(""")

    /** The one call that is allowed: the safe call with the constant as its only argument. */
    private val hookExact: Regex =
        Regex("""\bphraseWarn\s*\?\.\s*invoke\s*\(\s*PHRASE_UNAVAILABLE_REASON\s*\)""")

    /** A warn call in raw text, with its first argument; one level of parentheses is allowed inside the argument. */
    private val warnCall: Regex = Regex("""\bwarn\s*\(\s*((?:[^,()]|\([^()]*\))*)""")

    /** The only arguments a warn call may take: one plain literal with no template, or the hook argument. */
    private val allowedWarnArgument: Regex = Regex("""(?:"[^"\\$]*"|reason)""")

    private fun constantCount(raw: String): Int = constant.findAll(raw).count()

    /** Every hook call across [sources], and the calls that are exactly the safe call with the constant. */
    private fun hookCounts(sources: Map<String, String>): Pair<Int, Int> {
        var calls = 0
        var exact = 0
        for (text in sources.values) {
            val code: String = AppSourceFiles.strip(text).code
            calls += hookAny.findAll(code).count()
            exact += hookExact.findAll(code).count()
        }
        return Pair(calls, exact)
    }

    /** The argument text of each warn call in [raw], in order; the definition of warn is not a call. */
    private fun warnArguments(raw: String): List<String> {
        val found: MutableList<String> = ArrayList()
        for (match in warnCall.findAll(raw)) {
            if (raw.substring(0, match.range.first).trimEnd().endsWith("fun")) continue
            found.add(match.groupValues[1].trim())
        }
        return found
    }

    private fun notAllowed(arguments: List<String>): List<String> =
        arguments.filterNot { allowedWarnArgument.matches(it) }

    private val constantFiring: Map<String, String> = mapOf(
        "a template in the constant" to "internal const val PHRASE_UNAVAILABLE_REASON: String = \"spoken \$word is off\"",
        "a concatenation in the constant" to "internal const val PHRASE_UNAVAILABLE_REASON: String = \"spoken \" + \"off\"",
        "a constant that is not const" to "internal val PHRASE_UNAVAILABLE_REASON: String = \"spoken send is off\"",
        "a computed constant" to "internal const val PHRASE_UNAVAILABLE_REASON: String = listOf(\"a\").first()",
        "an escape in the constant" to "internal const val PHRASE_UNAVAILABLE_REASON: String = \"spoken\\nsend is off\"",
    )

    private val constantQuiet: Map<String, String> = mapOf(
        "the real line" to "internal const val PHRASE_UNAVAILABLE_REASON: String = \"spoken send is off for this take\"",
        "spaces and a line break" to "internal const val PHRASE_UNAVAILABLE_REASON:   String  =\n    \"spoken send is off for this take\"   ",
    )

    private val hookFiring: Map<String, Map<String, String>> = mapOf(
        "a variable argument" to mapOf("TileHost.kt" to "object A { fun f(root: R, m: String) { root.phraseWarn?.invoke(m) } }"),
        "the exception message" to mapOf("TileHost.kt" to "object A { fun f(root: R, e: Exception) { root.phraseWarn?.invoke(e.message) } }"),
        "a second call site" to mapOf(
            "TileHost.kt" to "object A { fun f(root: R) { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) } }",
            "BreakerApp.kt" to "object B { fun g(root: R) { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) } }",
        ),
        "a non-null call" to mapOf("TileHost.kt" to "object A { fun f(root: R) { root.phraseWarn!!.invoke(PHRASE_UNAVAILABLE_REASON) } }"),
        "a direct call" to mapOf("TileHost.kt" to "object A { fun f(root: R) { root.phraseWarn(PHRASE_UNAVAILABLE_REASON) } }"),
        "a literal argument" to mapOf("TileHost.kt" to "object A { fun f(root: R) { root.phraseWarn?.invoke(\"spoken send is off\") } }"),
    )

    private val hookQuiet: Map<String, Map<String, String>> = mapOf(
        "the real call" to mapOf("TileHost.kt" to "object A { fun f(root: R) { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) } }"),
        "spaces and line breaks" to mapOf("TileHost.kt" to "object A { fun f(root: R) {\n root.phraseWarn ?.\n invoke(\n PHRASE_UNAVAILABLE_REASON\n ) } }"),
        "an assignment in the application class" to mapOf(
            "TileHost.kt" to "object A { fun f(root: R) { root.phraseWarn?.invoke(PHRASE_UNAVAILABLE_REASON) } }",
            "BreakerApp.kt" to "object B { val r = R().also { root -> root.phraseWarn = { reason -> warn(reason) } } }",
        ),
    )

    private val warnFiring: Map<String, String> = mapOf(
        "a template in the message" to "object A { fun f(e: Exception) { warn(\"x \$name\", e) } }",
        "a computed message" to "object A { fun f(e: Exception) { warn(e.message, e) } }",
        "a call in the argument" to "object A { fun f(x: Any) { warn(x.toString()) } }",
        "a concatenation" to "object A { fun f(b: String) { warn(\"a\" + b) } }",
    )

    private val warnQuiet: Map<String, String> = mapOf(
        "the fixed message and the exception" to "object A { fun f(e: Exception) { warn(\"start-up purge failed\", e) } }",
        "the hook argument" to "object A { fun f(root: R) { root.phraseWarn = { reason -> warn(reason) } } }",
        "the definition" to "object A { private fun warn(reason: String, e: Throwable? = null) { Log.w(\"a\", reason, e) } }",
        "spaces and line breaks" to "object A { fun f(e: Exception) { warn(\n    \"start-up purge failed\",\n    e,\n) } }",
    )

    @Test
    fun `the reason constant is one plain literal, the hook is called once with it, and warn gets only a literal or the hook argument`() {
        val main: Map<String, String> = AppSourceFiles.mainKotlinSources()
        assertTrue("app: the reason rule found no main file to read", main.isNotEmpty())
        assertEquals("app: the reason constant is not one plain literal line", 1, constantCount(main.getValue("BreakerCompositionRoot.kt")))
        assertEquals("app: the hook is not called once, with the constant", Pair(1, 1), hookCounts(main))
        val app: String = main.getValue("BreakerApp.kt")
        assertEquals("app: BreakerApp.kt passes warn a value other than a literal or the hook argument", emptyList<String>(), notAllowed(warnArguments(app)))
        assertEquals("app: the warn calls in BreakerApp.kt changed", listOf("reason", "\"start-up purge failed\""), warnArguments(app))
    }

    @Test
    fun `each reason rule fails on its firing samples`() {
        for ((label, source) in constantFiring) {
            assertTrue("app: the constant rule accepted $label", constantCount(source) != 1)
        }
        for ((label, sources) in hookFiring) {
            assertTrue("app: the hook rule accepted $label", hookCounts(sources) != Pair(1, 1))
        }
        for ((label, source) in warnFiring) {
            assertTrue("app: the warn rule accepted $label", notAllowed(warnArguments(source)).isNotEmpty())
        }
    }

    @Test
    fun `each reason rule holds on its quiet samples`() {
        for ((label, source) in constantQuiet) {
            assertEquals("app: the constant rule flagged $label", 1, constantCount(source))
        }
        for ((label, sources) in hookQuiet) {
            assertEquals("app: the hook rule flagged $label", Pair(1, 1), hookCounts(sources))
        }
        for ((label, source) in warnQuiet) {
            assertEquals("app: the warn rule flagged $label", emptyList<String>(), notAllowed(warnArguments(source)))
        }
    }
}
