package dev.breaker.shared.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compiled tokens and tokens.css carry identical values.
 *
 * tokens.css is packaged as a resource of this module, so it is read here off the
 * classpath: the file a consumer would actually receive, not a path on disk.
 *
 * The reader is strict about the file's shape. tokens.css is exactly three rules,
 * the light set in `:root` and the dark set twice, and every statement in them is a
 * custom property. Anything else (another selector, a nested at-rule, a stray
 * declaration, a repeated block) is a failure, not something to skip over, because a
 * lenient reader would compare the wrong block and still pass.
 */
class TokensCssParityTest {
    private class Sheet(val root: Map<String, String>, val darkMedia: Map<String, String>, val darkAttr: Map<String, String>)

    private val rootSelector = Regex(":root")
    private val mediaSelector = Regex("@media\\s*\\(\\s*prefers-color-scheme\\s*:\\s*dark\\s*\\)")
    private val attrSelector = Regex(":root\\[data-theme=\"dark\"\\]")
    private val notLightSelector = Regex(":root:not\\(\\[data-theme=\"light\"\\]\\)")
    private val statement = Regex("--[a-z0-9-]+\\s*:\\s*[^;{}]+")

    private fun fail(message: String): Nothing = throw AssertionError("ui-tokens: tokens.css $message")

    private fun stripComments(text: String) = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    /** The top-level `selector { body }` rules of [text], in order; text outside a rule is refused. */
    private fun rules(text: String, where: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var i = 0
        while (true) {
            val open = text.indexOf('{', i)
            if (open < 0) {
                if (text.substring(i).isNotBlank()) fail("has text outside any rule in $where: '${text.substring(i).trim()}'")
                return out
            }
            var depth = 1
            var j = open + 1
            while (j < text.length && depth > 0) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            if (depth != 0) fail("has an unclosed '{' in $where")
            out += text.substring(i, open).trim() to text.substring(open + 1, j - 1)
            i = j
        }
    }

    /** The custom properties of a leaf block; anything that is not `--name: value;` is refused. */
    private fun properties(body: String, where: String): Map<String, String> {
        val parts = body.split(';')
        if (parts.last().isNotBlank()) fail("has a last statement without a ';' in $where: '${parts.last().trim()}'")
        val out = linkedMapOf<String, String>()
        for (part in parts.dropLast(1)) {
            val s = part.trim()
            if (!statement.matches(s)) fail("has a statement that is not a custom property in $where: '$s'")
            val name = s.substringBefore(':').trim().removePrefix("--")
            if (name in out) fail("declares --$name twice in $where")
            out[name] = s.substringAfter(':').trim()
        }
        return out
    }

    private fun parse(rawText: String): Sheet {
        val top = rules(stripComments(rawText), "the file")
        var root: Map<String, String>? = null
        var media: Map<String, String>? = null
        var attr: Map<String, String>? = null
        for ((selector, body) in top) {
            when {
                rootSelector.matches(selector) -> {
                    if (root != null) fail("has more than one ':root' rule")
                    root = properties(body, "':root'")
                }
                mediaSelector.matches(selector) -> {
                    if (media != null) fail("has more than one prefers-color-scheme rule")
                    val inner = rules(body, "the prefers-color-scheme rule")
                    if (inner.size != 1 || !notLightSelector.matches(inner[0].first)) {
                        fail("prefers-color-scheme rule must hold exactly one ':root:not([data-theme=\"light\"])' rule, found ${inner.map { it.first }}")
                    }
                    media = properties(inner[0].second, "the prefers-color-scheme rule")
                }
                attrSelector.matches(selector) -> {
                    if (attr != null) fail("has more than one ':root[data-theme=\"dark\"]' rule")
                    attr = properties(body, "':root[data-theme=\"dark\"]'")
                }
                else -> fail("has a rule for '$selector'; it holds only ':root', the prefers-color-scheme rule and ':root[data-theme=\"dark\"]'")
            }
        }
        return Sheet(
            root ?: fail("has no ':root' rule"),
            media ?: fail("has no prefers-color-scheme dark rule"),
            attr ?: fail("has no ':root[data-theme=\"dark\"]' rule"),
        )
    }

    private fun sheet(): Sheet {
        val stream = TokensCssParityTest::class.java.getResourceAsStream("/tokens.css")
            ?: throw AssertionError("ui-tokens: tokens.css is not on the classpath of this module")
        return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun colors(palette: TruckingPalette): Map<String, String> = mapOf(
        "color-bg" to palette.bg.hex,
        "color-surface" to palette.surface.hex,
        "color-text" to palette.text.hex,
        "color-text-muted" to palette.textMuted.hex,
        "color-primary" to palette.primary.hex,
        "color-primary-hover" to palette.primaryHover.hex,
        "color-accent" to palette.accent.hex,
        "color-danger" to palette.danger.hex,
        "color-trim" to palette.trim.hex,
        "color-sent" to palette.sent.hex,
        "color-warning" to palette.warning.hex,
    )

    private fun upper(block: Map<String, String>) = block.mapValues { it.value.uppercase() }

    @Test
    fun lightColorsMatchTheRootBlock() {
        val root = sheet().root
        assertEquals(colors(TruckingTokens.LIGHT), upper(root).filterKeys { it.startsWith("color-") })
    }

    @Test
    fun darkColorsMatchTheSystemPreferenceBlock() {
        // The dark blocks hold the colors and nothing else, so the whole block is compared.
        assertEquals(colors(TruckingTokens.DARK), upper(sheet().darkMedia))
    }

    @Test
    fun darkColorsMatchTheExplicitThemeBlock() {
        assertEquals(colors(TruckingTokens.DARK), upper(sheet().darkAttr))
    }

    @Test
    fun metricsMatch() {
        val root = sheet().root
        val m = TruckingTokens.metrics
        assertEquals("${m.cornerRadiusDp}px", root["radius"])
        assertEquals("${m.minTouchTargetDp}px", root["touch-target-min"])
        assertEquals("${m.mobileBreakpointDp}px", root["breakpoint-mobile"])
        assertEquals("${m.tabletBreakpointDp}px", root["breakpoint-tablet"])
    }

    @Test
    fun ledSegmentRangeMatches() {
        val root = sheet().root
        assertEquals(TruckingTokens.ledBar.minSegments.toString(), root["led-segments-min"])
        assertEquals(TruckingTokens.ledBar.maxSegments.toString(), root["led-segments-max"])
    }

    @Test
    fun fontFamiliesMatch() {
        val root = sheet().root
        val type = TruckingTokens.type
        assertEquals("\"${type.displayFamily}\", \"${type.displayAlternateFamily}\", sans-serif", root["font-display"])
        assertEquals("\"${type.bodyFamily}\", sans-serif", root["font-body"])
        assertEquals("\"${type.monoFamily}\", monospace", root["font-mono"])
    }

    @Test
    fun cssHasNoTokenTheCompiledTokensLack() {
        val expected = colors(TruckingTokens.LIGHT).keys +
            setOf(
                "font-display", "font-body", "font-mono", "radius", "touch-target-min",
                "breakpoint-mobile", "breakpoint-tablet", "led-segments-min", "led-segments-max",
            )
        assertEquals(expected, sheet().root.keys)
    }

    private val wellFormed = """
        :root { --a: 1; }
        @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --a: 2; } }
        :root[data-theme="dark"] { --a: 2; }
    """.trimIndent()

    /** [wellFormed] with [old] replaced by [new]; fails if [old] is not there, so an edit that
     * matched nothing cannot hand the reader a good sheet to "refuse". */
    private fun edit(old: String, new: String): String {
        assertTrue("the edit target '$old' is not in the well-formed sheet", wellFormed.contains(old))
        return wellFormed.replaceFirst(old, new)
    }

    /** The reader refuses [css] with the ui-tokens prefix and a message that matches [reason]. */
    private fun refused(css: String, reason: String) {
        val e = assertThrows(AssertionError::class.java) { parse(css) }
        val message = e.message.orEmpty()
        assertTrue(message, message.startsWith("ui-tokens: tokens.css"))
        assertTrue("refused for another reason: $message", Regex(reason).containsMatchIn(message))
    }

    @Test
    fun theReaderAcceptsAWellFormedSheet() {
        val s = parse(wellFormed)
        assertEquals(mapOf("a" to "1"), s.root)
        assertEquals(mapOf("a" to "2"), s.darkMedia)
        assertEquals(mapOf("a" to "2"), s.darkAttr)
    }

    @Test
    fun theReaderRefusesASheetWithNoRules() {
        refused("body { color: red; }", "has a rule for 'body'")
    }

    @Test
    fun theReaderRefusesAMissingBlock() {
        refused(edit(":root[data-theme=\"dark\"] { --a: 2; }", ""), "has no ':root\\[data-theme")
    }

    @Test
    fun theReaderRefusesADuplicateRootRule() {
        refused(wellFormed + "\n:root { --b: 3; }", "more than one ':root' rule")
    }

    @Test
    fun theReaderRefusesAQualifiedRootSelector() {
        refused(edit(":root { --a: 1; }", "html :root { --a: 1; }"), "has a rule for 'html :root'")
        refused(edit(":root { --a: 1; }", "html:root { --a: 1; }"), "has a rule for 'html:root'")
    }

    @Test
    fun theReaderRefusesAtRuleNestedInsideRoot() {
        refused(
            edit(":root { --a: 1; }", ":root { --a: 1; @media (min-width: 1px) { --b: 1; } }"),
            "without a ';'|not a custom property",
        )
    }

    @Test
    fun theReaderRefusesADeclarationOutsideAnyRule() {
        refused(wellFormed + "\n--stray: 1;", "text outside any rule")
        refused("--stray: 1;\n" + wellFormed, "has a rule for '--stray")
    }

    @Test
    fun theReaderRefusesAStatementThatIsNotACustomProperty() {
        refused(edit("--a: 1;", "--a: 1; border-radius: 12px;"), "not a custom property.*border-radius")
    }

    @Test
    fun theReaderRefusesAnUppercaseCustomPropertyName() {
        refused(edit("--a: 1;", "--A: 1;"), "not a custom property.*--A")
    }

    @Test
    fun theReaderRefusesADuplicateDeclarationInABlock() {
        refused(edit("--a: 1;", "--a: 1; --a: 9;"), "declares --a twice")
    }

    @Test
    fun theReaderRefusesAWrongInnerSelectorInTheMediaRule() {
        refused(edit(":root:not([data-theme=\"light\"])", ":root"), "exactly one")
    }

    @Test
    fun theReaderRefusesALastStatementWithoutASemicolon() {
        refused(edit("--a: 1;", "--a: 1"), "without a ';'")
    }

    @Test
    fun theReaderIgnoresACommentedOutDeclaration() {
        val s = parse(edit("--a: 1;", "--a: 1; /* --b: 9; */"))
        assertEquals(setOf("a"), s.root.keys)
    }
}
