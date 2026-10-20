package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four screens the menu opens are opened from the menu and, for two of them, from a notification
 * route, and from nowhere else. The launcher activity is read with its comments and literal text removed,
 * the way the other rules of this folder read it.
 *
 * The table names each opener, the one menu line that wires it to the Home view, the route check that
 * also opens it (or null), and the reason for each call. A route check holds exactly one call of its own
 * opener. No stock button or text view is built anywhere in the activity.
 */
class NavigationOnlyFromMenuTest {
    private val activityPath: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** One screen opener: its menu line, its route check (or null), and why each of its calls is allowed. */
    private data class Opener(val function: String, val menuLine: String, val routeMarker: String?, val reason: String)

    private val table: List<Opener> = listOf(
        Opener(
            "openHistory",
            "onHistory = { openHistory() },",
            "NotificationRoute.opensHistory(",
            "the History menu item, and the history notification route",
        ),
        Opener(
            "openSettings",
            "onSettings = { openSettings() },",
            "NotificationRoute.ROUTE_MODEL) {",
            "the Settings menu item, and the model notification route that then asks for the download focus",
        ),
        Opener("openHowItWorks", "onHowItWorks = { openHowItWorks() },", null, "the How it works menu item"),
        Opener("openAbout", "onAbout = { openAbout() },", null, "the About menu item"),
    )

    private val stockWidget = Regex("""\b(Button|TextView)\s*\(|android\.widget\.(Button|TextView)\b""")

    private fun codeOf(source: String): String = AppSourceFiles.strip(source).code

    private fun activitySource(): String = AppSourceFiles.mainFile(activityPath)

    /** The calls of [function] in [code]: the name and empty brackets, not its declaration and not a member of another object. */
    private fun callsOf(code: String, function: String): Int =
        Regex("""(?<!fun )(?<![\w.])${function}\(\)""").findAll(code).count()

    /** The text from the first brace after [marker] to the brace that closes it, or null when there is none. */
    private fun blockAfter(code: String, marker: String): String? {
        val start = code.indexOf(marker)
        if (start < 0) return null
        val open = code.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
        }
        return null
    }

    private fun countProblems(code: String): List<String> = table.mapNotNull { opener ->
        val expected = if (opener.routeMarker == null) 1 else 2
        val found = callsOf(code, opener.function)
        if (found == expected) null else "${opener.function} is called $found times, expected $expected (${opener.reason})"
    }

    private fun menuProblems(code: String): List<String> {
        val lines = code.lines().map { it.trim() }
        return table.mapNotNull { opener ->
            val found = lines.count { it == opener.menuLine }
            if (found == 1) null else "the menu line ${opener.menuLine} is found $found times, expected 1"
        }
    }

    private fun routeProblems(code: String): List<String> = table.mapNotNull { opener ->
        val marker = opener.routeMarker ?: return@mapNotNull null
        val block = blockAfter(code, marker) ?: return@mapNotNull "the route check for ${opener.function} is not found"
        val found = callsOf(block, opener.function)
        if (found == 1) null else "the route check for ${opener.function} holds $found calls, expected 1"
    }

    private fun widgetProblems(code: String): List<String> =
        stockWidget.findAll(code).map { "the activity builds a stock widget: ${it.value}" }.toList()

    /** [source] with the text [from] replaced by [to]; fails when [from] is not there, so a sample cannot go quiet by a typo. */
    private fun edit(label: String, source: String, from: String, to: String): String {
        check(source.contains(from)) { "the sample \"$label\" has no text \"$from\" to change" }
        return source.replace(from, to)
    }

    @Test
    fun `each screen opener is called only from its own sites`() {
        val source = activitySource()
        assertEquals("the openers are called from their own sites only", emptyList<String>(), countProblems(codeOf(source)))
        val onResume = edit("onResume", source, "super.onResume()", "super.onResume(); openSettings()")
        assertTrue("a fifth openSettings is reported", countProblems(codeOf(onResume)).any { it.startsWith("openSettings is called 3 times") })
        val onPause = edit("onPause", source, "super.onPause()", "super.onPause(); openAbout()")
        assertTrue("an extra openAbout is reported", countProblems(codeOf(onPause)).any { it.startsWith("openAbout is called 2 times") })
        val quiet = edit("comment", source, "override fun onResume() {", "// openSettings() is called from the menu only\n    override fun onResume() {")
        assertEquals("a comment naming an opener is not a call", emptyList<String>(), countProblems(codeOf(quiet)))
    }

    @Test
    fun `each menu item is wired to the opener of its own screen`() {
        val source = activitySource()
        assertEquals("each menu item is wired once", emptyList<String>(), menuProblems(codeOf(source)))
        val aboutToSettings = edit("onAbout", source, "onAbout = { openAbout() },", "onAbout = { openSettings() },")
        assertTrue("the About line is missing", menuProblems(codeOf(aboutToSettings)).any { it.contains("onAbout = { openAbout() },") })
        val doubled = edit("onHistory", source, "onHistory = { openHistory() },", "onHistory = { openHistory() },\n            onHistory = { openHistory() },")
        assertTrue("a doubled History line is reported", menuProblems(codeOf(doubled)).any { it.contains("onHistory = { openHistory() },") })
        val quiet = edit("comment", source, "onAbout = { openAbout() },", "// onAbout = { openAbout() },\n            onAbout = { openAbout() },")
        assertEquals("a commented menu line is not a wire", emptyList<String>(), menuProblems(codeOf(quiet)))
    }

    @Test
    fun `each notification route opens its screen once inside its own check`() {
        val source = activitySource()
        assertEquals("each route holds one call", emptyList<String>(), routeProblems(codeOf(source)))
        val moved = edit(
            "history call moved out",
            source,
            "            openHistory()\n        }",
            "        }; openHistory(); if (false) {\n        }",
        )
        assertTrue("the history route with no call of its own is reported", routeProblems(codeOf(moved)).any { it.contains("openHistory holds 0 calls") })
        val twice = edit(
            "second Settings call",
            source,
            "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload()",
            "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload()\n            openSettings()",
        )
        assertTrue("the model route with two calls is reported", routeProblems(codeOf(twice)).any { it.contains("openSettings holds 2 calls") })
        val quiet = edit(
            "comment in the route",
            source,
            "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload()",
            "(settingsView as? dev.breaker.dictation.ui.DownloadControl)?.focusDownload() // openSettings() opens the menu item",
        )
        assertEquals("a comment in a route is not a call", emptyList<String>(), routeProblems(codeOf(quiet)))
    }

    @Test
    fun `the launcher builds no stock button or text view`() {
        val source = activitySource()
        assertEquals("no stock widget is built", emptyList<String>(), widgetProblems(codeOf(source)))
        val button = edit("button", source, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val probe = Button(this)")
        assertTrue("a stock Button is reported", widgetProblems(codeOf(button)).any { it.contains("Button(") })
        val text = edit("text view", source, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val probe = TextView(this)")
        assertTrue("a stock TextView is reported", widgetProblems(codeOf(text)).any { it.contains("TextView(") })
        val qualifiedButton = edit("qualified button", source, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val probe = android.widget.Button(this)")
        assertTrue("a qualified stock Button is reported", widgetProblems(codeOf(qualifiedButton)).any { it.contains("android.widget.Button") })
        val qualifiedText = edit("qualified text view", source, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val probe = android.widget.TextView(this)")
        assertTrue("a qualified stock TextView is reported", widgetProblems(codeOf(qualifiedText)).any { it.contains("android.widget.TextView") })
        val quiet = edit("string", source, "super.onCreate(savedInstanceState)", "super.onCreate(savedInstanceState)\n        val label = \"Button(\"")
        assertEquals("a word in a string is not a widget", emptyList<String>(), widgetProblems(codeOf(quiet)))
    }
}
