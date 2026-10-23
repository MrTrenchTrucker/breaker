package dev.breaker.dictation.ui

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Home view's delivery of its own resolved mode, pinned on its source text.
 *
 * The Home view resolves its theme once, through the shared reader, and hands the mode it draws with
 * out to the app, which paints the mic with it. This gate reads HomeEntry.kt with comments and literal
 * text removed and pins that the delivery is declared, that it carries the page's own resolved mode
 * rather than the stored choice or the phone's own reading, and that it stands on its own line at the
 * top level of the body, so every theme the view draws is delivered and none is skipped behind a guard.
 *
 * RED today: the delivery does not exist yet. The fix is asked to declare `onShownMode` on
 * createHomeView and to call it with the page's own `theme.mode`. The mutations that make a rule here
 * fail are the ones the fix must not ship: the call removed, the call moved behind a guard, and the
 * stored choice delivered instead of the resolved mode.
 */

private const val ENTRY = "HomeEntry.kt"

/** The delivery callback the Home view is asked to declare, flattened. */
private const val DELIVERY_PARAMETER = "onShownMode:(dev.breaker.shared.tokens.ThemeMode)->Unit"

/** The delivery call carrying the page's own resolved mode. */
private const val DELIVERY_OF_THE_DRAWN_MODE = "onShownMode(theme.mode)"

/** [text] with comments and literal text removed, then every run of whitespace cut to nothing. */
private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(Regex("""\s+"""), "")

/** The body of [fn] in [code], from the brace that opens it to its own closing brace. */
private fun bodyOf(code: String, fn: String): String {
    val start = code.indexOf("fun $fn(")
    assertTrue("the $fn entry function is not in the source", start >= 0)
    var parens = 0
    var open = -1
    var close = -1
    for (index in start until code.length) {
        when (code[index]) {
            '(' -> {
                parens++
                if (parens == 1) open = index
            }
            ')' -> {
                parens--
                if (parens == 0) {
                    close = index
                    break
                }
            }
        }
    }
    assertTrue("the $fn entry function has no parameter list", open >= 0)
    assertTrue("the $fn entry function has no parameter list close", close > open)
    val brace = code.indexOf('{', close)
    assertTrue("the $fn entry function has no body", brace >= 0)
    var braces = 0
    for (index in brace until code.length) {
        when (code[index]) {
            '{' -> braces++
            '}' -> {
                braces--
                if (braces == 0) return code.substring(brace + 1, index)
            }
        }
    }
    throw IllegalArgumentException("the $fn body never closes")
}

/** The brace depth of [code] at [index]: 0 is the top level of the body the text was cut from. */
private fun depthAt(code: String, index: Int): Int {
    var depth = 0
    for (position in 0 until index) {
        when (code[position]) {
            '{' -> depth++
            '}' -> depth--
        }
    }
    return depth
}

/** The line of [body] holding the delivery call, trimmed. */
private fun deliveryLine(body: String, call: String): String {
    val at = body.indexOf(call)
    assertTrue("the home view does not deliver $call", at >= 0)
    val lineStart = body.lastIndexOf('\n', at - 1) + 1
    val lineEnd = body.indexOf('\n', at).let { if (it < 0) body.length else it }
    return body.substring(lineStart, lineEnd).trim()
}

/**
 * Today's home entry, as it is written now.
 *
 * The delivery is already in the source: `onShownMode` is declared on `createHomeView` and it is called
 * with the page's own resolved mode. This returns the real text, so the rules below and the mutants of
 * tests 5-7 read the same shape the fix ships. A simulated edit would re-add the parameter the
 * signature already has, which is the defect this function used to carry.
 */
private fun fixedEntry(): String = mainSourceOf(ENTRY)

class HomeModeDeliveryGateTest {

    private val entry: String = mainSourceOf(ENTRY)

    private fun body(): String = bodyOf(withoutCommentsAndStrings(entry), "createHomeView")

    @Test
    fun `the home view declares a delivery for the mode it draws with`() {
        assertTrue(
            "the home view does not declare $DELIVERY_PARAMETER",
            flat(entry).contains(DELIVERY_PARAMETER),
        )
    }

    @Test
    fun `the home view delivers the mode it draws with and not the stored choice`() {
        val body = body()
        assertTrue(
            "the home view does not deliver $DELIVERY_OF_THE_DRAWN_MODE",
            body.contains(DELIVERY_OF_THE_DRAWN_MODE),
        )
        assertTrue(
            "the home view delivers the raw store rather than the resolved mode",
            !body.contains("onShownMode(settings") && !body.contains("onShownMode(stored"),
        )
    }

    @Test
    fun `the delivery stands on its own line at the top level of the body`() {
        val body = body()
        val at = body.indexOf(DELIVERY_OF_THE_DRAWN_MODE)
        assertTrue("the home view does not deliver $DELIVERY_OF_THE_DRAWN_MODE", at >= 0)
        assertTrue(
            "the delivery does not stand on its own line",
            deliveryLine(body, DELIVERY_OF_THE_DRAWN_MODE).startsWith(DELIVERY_OF_THE_DRAWN_MODE),
        )
        assertTrue(
            "the delivery sits at depth ${depthAt(body, at)} inside the body, so a theme change may skip it",
            depthAt(body, at) == 0,
        )
    }

    @Test
    fun `the rules hold on the fixed shape`() {
        val body = bodyOf(withoutCommentsAndStrings(fixedEntry()), "createHomeView")
        val at = body.indexOf(DELIVERY_OF_THE_DRAWN_MODE)
        assertTrue("the fixed shape does not declare the delivery", flat(fixedEntry()).contains(DELIVERY_PARAMETER))
        assertTrue("the fixed shape does not deliver the drawn mode", at >= 0)
        assertTrue(
            "the fixed shape's delivery does not stand on its own line",
            deliveryLine(body, DELIVERY_OF_THE_DRAWN_MODE).startsWith(DELIVERY_OF_THE_DRAWN_MODE),
        )
        assertTrue("the fixed shape's delivery is not at the top level", depthAt(body, at) == 0)
    }

    @Test
    fun `a removed delivery fails the delivery rules`() {
        val mutant = bodyOf(withoutCommentsAndStrings(fixedEntry()), "createHomeView")
            .replace(DELIVERY_OF_THE_DRAWN_MODE, "")
        assertTrue("a removed delivery still reads as a delivery", !mutant.contains(DELIVERY_OF_THE_DRAWN_MODE))
    }

    @Test
    fun `a delivery behind a guard fails the top-level rule`() {
        val mutant = bodyOf(withoutCommentsAndStrings(fixedEntry()), "createHomeView")
            .replace(DELIVERY_OF_THE_DRAWN_MODE, "if (true) {\n        $DELIVERY_OF_THE_DRAWN_MODE\n    }")
        val at = mutant.indexOf(DELIVERY_OF_THE_DRAWN_MODE)
        assertTrue("a guarded delivery still sits at the top level of the body", at >= 0 && depthAt(mutant, at) != 0)
    }

    @Test
    fun `a stored choice delivered instead of the drawn mode fails the drawn-mode rule`() {
        val mutant = bodyOf(withoutCommentsAndStrings(fixedEntry()), "createHomeView")
            .replace(DELIVERY_OF_THE_DRAWN_MODE, "onShownMode(settings.load().themeMode)")
        assertTrue(
            "a stored choice delivered instead of the resolved mode still reads as the drawn mode",
            !mutant.contains(DELIVERY_OF_THE_DRAWN_MODE),
        )
        assertTrue(
            "the stored choice is not what the mutant delivers",
            mutant.contains("onShownMode(settings.load().themeMode)"),
        )
    }
}
