package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The Next button is the primary button once granted and the neutral one otherwise.
 *
 * Like FirstRunViewsGateTest, this holds no view: it reads real source text, reports problems with
 * words, and asserts on the real render file. It names no type the fix adds, so it compiles on the
 * unchanged base, where its own assertion on the real file is red.
 */
private const val RENDER = "render/FirstRunPageViews.kt"

class FirstRunNextGateTest {

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("first-run views: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("first-run views: $what must not be reported", emptyList<String>(), problems)

    /** Edit [text], asserting [old] was present and replacing its first occurrence with [replaced]. */
    private fun edit(text: String, old: String, replaced: String): String {
        assertTrue("edit anchor absent in: $text", text.contains(old))
        return text.replaceFirst(old, replaced)
    }

    /** The text of the nextButton function, or an empty string when there is none. */
    private fun bodyOf(text: String): String {
        val start = text.indexOf("fun nextButton")
        if (start < 0) return ""
        var depth = 0
        var seenOpen = false
        for (j in start until text.length) {
            when (text[j]) {
                '{' -> {
                    depth++
                    seenOpen = true
                }
                '}' -> if (depth > 0 && --depth == 0) return text.substring(start, j + 1)
            }
        }
        return if (seenOpen) text.substring(start) else ""
    }

    /** The problems a source text has for the Next button, as words. */
    private fun nextProblems(source: String): List<String> {
        val problems = mutableListOf<String>()
        val body = bodyOf(source)
        if (body.isEmpty()) {
            problems += "no nextButton function"
        } else {
            if (!body.contains("FullWidthButtonView")) problems += "no FullWidthButtonView"
            if (!body.contains("NeutralButtonView")) problems += "no NeutralButtonView"
            if (!body.contains("KitLook.RAISED")) problems += "no KitLook.RAISED"
            if (Regex(Regex.escape("layoutParams = rowParams(true)")).findAll(body).count() < 2) problems += "rowParams(true) missing in a branch"
            if (Regex(Regex.escape("setOnClickListener { onTap() }")).findAll(body).count() < 2) problems += "tap not attached in a branch"
        }
        if (!source.contains("nextButton(FirstRunNext.look(shown)")) problems += "call site does not pass FirstRunNext.look(shown)"
        return problems
    }

    @Test
    fun `the next button is the primary button when granted and the neutral one otherwise`() {
        // a quiet sample: the look parameter, both views, the raised branch, and the call site
        val quiet = "fun nextButton(look: KitLook, onTap: () -> Unit): View {\n" +
            "    when (look) {\n" +
            "        KitLook.RAISED -> FullWidthButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() }; layoutParams = rowParams(true) }\n" +
            "        else -> NeutralButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() }; layoutParams = rowParams(true) }\n" +
            "    }\n" +
            "}\n" +
            "page.addView(nextButton(FirstRunNext.look(shown), onNext))\n"
        assertQuiet("quiet", nextProblems(quiet))

        // the function is gone
        assertFires("no function", nextProblems(edit(quiet, "fun nextButton", "fun somethingElse")), "no nextButton function")
        // the body does not build the primary view
        assertFires("no primary", nextProblems(edit(quiet, "FullWidthButtonView", "SomeOtherView")), "no FullWidthButtonView")
        // the body does not build the neutral view
        assertFires("no neutral", nextProblems(edit(quiet, "NeutralButtonView", "SomeOtherView")), "no NeutralButtonView")
        // the body never looks at the raised look
        assertFires("no raised look", nextProblems(edit(quiet, "KitLook.RAISED", "KitLook.DANGER")), "no KitLook.RAISED")
        // the call site does not pass the look
        assertFires("call site", nextProblems(edit(quiet, "nextButton(FirstRunNext.look(shown)", "nextButton(onNext)")), "call site does not pass")

        // row params dropped from the raised branch
        assertFires("no row params", nextProblems(edit(quiet,
            "FullWidthButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() }; layoutParams = rowParams(true) }",
            "FullWidthButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() } }")), "rowParams(true) missing in a branch")
        // the tap dropped from the raised branch: a green Next that does nothing
        assertFires("no tap", nextProblems(edit(quiet,
            "FullWidthButtonView(context, FirstRunTexts.NEXT, theme).apply { setOnClickListener { onTap() }; layoutParams = rowParams(true) }",
            "FullWidthButtonView(context, FirstRunTexts.NEXT, theme).apply { layoutParams = rowParams(true) }")), "tap not attached in a branch")

        // the real render file must be quiet on every word (red on the base: it builds the neutral view without a look)
        val found = nextProblems(mainSourceOf(RENDER))
        assertEquals("next button is the primary button when granted and the neutral one otherwise: $found", emptyList<String>(), found)
    }
}
