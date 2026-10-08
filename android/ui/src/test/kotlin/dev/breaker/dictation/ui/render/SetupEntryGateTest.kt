package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The function that builds the setup view needs a real context and a real window, so no
 * unit test can run it. What it must do is five lines of wiring, and a wrong argument in
 * any of them compiles: the component name left empty makes accessibility look switched
 * off forever, the wrong theme mode draws the screen in the wrong colours, a switch that
 * is not the one the app handed in turns the wrong thing on. So the body is pinned as
 * text. Each rule reads the body of the function with comments and string literals
 * removed, is run on the real file, and is also shown to fire: the failing sample is the
 * real file with one piece of the function changed by editEntry(), which fails the test
 * if the piece it means to change is not there.
 */

private const val ENTRY_SOURCE = "SettingsEntry.kt"
private const val ENTRY_START = "fun createOnboardingView("
private const val MISSING = "the setup entry function is not there"

/** The end of a statement: nothing more on its line. */
private const val LINE_END = """[ \t]*(?:\r?\n|$)"""

/** Every function the body may call, and no other. */
private val EXPECTED_CALLS = setOf(
    "isNightMode", "Themes.of", "shownMode", "AndroidSetupPlatform",
    "SetupIntentHandler", "SetupScreen", "SetupHostView", "ScreenRenderer",
)

private val SIGNATURE = Regex(
    """fun\s+createOnboardingView\(\s*context\s*:\s*[\w.]+\s*,\s*switch\s*:\s*BreakerSwitch\s*,""" +
        """\s*accessibilityServiceComponent\s*:\s*String\s*\)\s*:\s*[\w.]+""",
)

/** The signature and the body of the setup entry function, with comments and strings removed. */
private class Entry(val signature: String, val body: String)

private fun entryOf(text: String): Entry? {
    val code = withoutCommentsAndStrings(text)
    val start = Regex("""\bfun\s+createOnboardingView\s*\(""").find(code) ?: return null
    val open = code.indexOf('{', start.range.last)
    if (open < 0) return null
    var depth = 0
    for (index in open until code.length) {
        when (code[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return Entry(code.substring(start.range.first, open), code.substring(open + 1, index))
            }
        }
    }
    return null
}

/** [text] with the first [old] after the start of the setup entry function replaced by [new]; fails when it is not there. */
private fun editEntry(text: String, old: String, new: String): String {
    val anchor = text.indexOf(ENTRY_START)
    assertTrue("the setup entry function is not in the source", anchor >= 0)
    val from = text.indexOf(old, anchor)
    assertTrue("the text to change is not in the setup entry function: $old", from >= 0)
    return text.substring(0, from) + new + text.substring(from + old.length)
}

private fun times(body: String, token: String): Int = Regex(Regex.escape(token)).findAll(body).count()

private fun phoneVarIn(body: String): String? =
    Regex("""\bval\s+(\w+)\s*=\s*isNightMode\(\s*context\.resources\.configuration\.uiMode\s*\)""" + LINE_END)
        .find(body)?.groupValues?.get(1)

private fun themeVarIn(body: String): String? {
    val phone = phoneVarIn(body) ?: return null
    return Regex(
        """\bval\s+(\w+)\s*=\s*Themes\.of\(\s*shownMode\(\s*StoredThemeMode\.SYSTEM\s*,\s*""" +
            Regex.escape(phone) + """\s*\)\s*\)""" + LINE_END,
    ).find(body)?.groupValues?.get(1)
}

private fun platformVarIn(body: String): String? =
    Regex("""\bval\s+(\w+)\s*=\s*AndroidSetupPlatform\(\s*context\s*,\s*accessibilityServiceComponent\s*\)""" + LINE_END)
        .find(body)?.groupValues?.get(1)

private fun handlerVarIn(body: String): String? {
    val platform = platformVarIn(body) ?: return null
    return Regex(
        """\bval\s+(\w+)\s*=\s*SetupIntentHandler\(\s*""" + Regex.escape(platform) +
            """\s*,\s*switch\s*,\s*SetupScreen\(\)\s*\)""" + LINE_END,
    ).find(body)?.groupValues?.get(1)
}

private fun signatureProblemsIn(text: String): List<String> {
    val entry = entryOf(text) ?: return listOf(MISSING)
    return if (SIGNATURE.matches(entry.signature.trim())) emptyList() else listOf("the function does not take a context, a switch and a component name")
}

private fun phoneProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val plain = times(body, "isNightMode(") == 1 && phoneVarIn(body) != null
    return if (plain) emptyList() else listOf("the phone mode is not read once, as it stands, from the context")
}

private fun themeProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val once = times(body, "Themes.of(") == 1 && times(body, "shownMode(") == 1 && times(body, "StoredThemeMode.") == 1
    return if (once && themeVarIn(body) != null) emptyList() else listOf("the theme is not the system mode with the phone's own reading")
}

private fun platformProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val once = times(body, "AndroidSetupPlatform(") == 1
    return if (once && platformVarIn(body) != null) emptyList() else listOf("the platform is not built from the context and the component name given")
}

private fun handlerProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val once = times(body, "SetupIntentHandler(") == 1 && times(body, "SetupScreen(") == 1
    return if (once && handlerVarIn(body) != null) emptyList() else listOf("the handler is not built from the platform, the switch given and a new screen")
}

private fun hostProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val theme = themeVarIn(body)
    val handler = handlerVarIn(body)
    if (theme == null || handler == null) return listOf("the host has no handler or theme to be built from")
    val shape = Regex(
        """\breturn\s+SetupHostView\(\s*context\s*,\s*""" + Regex.escape(handler) + """\s*,\s*""" + Regex.escape(theme) +
            """\s*,\s*ScreenRenderer\(\s*context\s*\)\s*\)""" + LINE_END,
    )
    val once = times(body, "return") == 1 && times(body, "SetupHostView(") == 1 && times(body, "ScreenRenderer(") == 1
    return if (once && shape.containsMatchIn(body)) emptyList() else listOf("the host is not the returned view built from the context, the handler, the theme and a renderer")
}

private fun reachProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val problems = mutableListOf<String>()
    val calls = Regex("""\b([A-Za-z_][\w.]*)\s*\(""").findAll(body).map { it.groupValues[1] }.toSet()
    if (calls != EXPECTED_CALLS) problems.add("the body calls ${calls - EXPECTED_CALLS} and no longer calls ${EXPECTED_CALLS - calls}")
    val statements = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val plain = statements.size == 5 && statements.all { it.startsWith("val ") || it.startsWith("return ") }
    if (!plain) problems.add("the body is not five plain statements: $statements")
    return problems
}

/** The setup entry function, pinned as text. */
class SetupEntryGateTest {
    private val entry = mainSourceOf(ENTRY_SOURCE)

    private fun assertFires(what: String, problems: List<String>, reason: String) {
        assertTrue("$what: nothing fired, expected '$reason' in $problems", problems.any { it.contains(reason) })
    }

    @Test
    fun `the reader finds the setup entry function and not the settings one`() {
        val body = entryOf(entry)?.body.orEmpty()
        assertTrue("createOnboardingView body is empty or missing", body.isNotBlank())
        assertTrue("the body read is not the one that builds the platform", body.contains("AndroidSetupPlatform("))
        assertTrue("the body read is the settings view's", !body.contains("ThemeController"))
        assertFires("a function name only in a comment", signatureProblemsIn("/* fun createOnboardingView(context: Context) { } */\n"), "not there")
        assertFires("a function name only in a string", signatureProblemsIn("val x = \"fun createOnboardingView(context: Context) { }\"\n"), "not there")
    }

    @Test
    fun `the setup entry function takes a context, the switch and the component name`() {
        assertEquals("createOnboardingView signature: ", emptyList<String>(), signatureProblemsIn(entry))
        assertFires("the function renamed", signatureProblemsIn(editEntry(entry, "fun createOnboardingView(", "fun createOnboardingScreen(")), "not there")
        assertFires("the switch weakened", signatureProblemsIn(editEntry(entry, "switch: BreakerSwitch", "switch: Any")), "does not take")
        assertFires("the component weakened", signatureProblemsIn(editEntry(entry, "accessibilityServiceComponent: String", "accessibilityServiceComponent: Any")), "does not take")
        assertFires("the switch renamed", signatureProblemsIn(editEntry(entry, "switch: BreakerSwitch", "toggle: BreakerSwitch")), "does not take")
    }

    @Test
    fun `the phone mode is read once from the context and is not negated`() {
        assertEquals("createOnboardingView phone mode: ", emptyList<String>(), phoneProblemsIn(entry))
        val read = "isNightMode(context.resources.configuration.uiMode)"
        assertFires("the reading negated", phoneProblemsIn(editEntry(entry, read, "!isNightMode(context.resources.configuration.uiMode)")), "phone mode")
        assertFires("the reading fixed", phoneProblemsIn(editEntry(entry, read, "isNightMode(0)")), "phone mode")
        assertFires("the reading taken from a literal", phoneProblemsIn(editEntry(entry, read, "false")), "phone mode")
        assertFires("the reading made twice", phoneProblemsIn(editEntry(entry, "val platform = AndroidSetupPlatform(", "val again = isNightMode(0)\n    val platform = AndroidSetupPlatform(")), "phone mode")
        assertEquals("a comment in the body is not a reading", emptyList<String>(), phoneProblemsIn(editEntry(entry, "val platform = AndroidSetupPlatform(", "// isNightMode(0)\n    val platform = AndroidSetupPlatform(")))
    }

    @Test
    fun `the theme follows the phone through the system mode`() {
        assertEquals("createOnboardingView theme: ", emptyList<String>(), themeProblemsIn(entry))
        assertFires("a light theme always", themeProblemsIn(editEntry(entry, "StoredThemeMode.SYSTEM", "StoredThemeMode.LIGHT")), "system mode")
        assertFires("a dark theme always", themeProblemsIn(editEntry(entry, "StoredThemeMode.SYSTEM", "StoredThemeMode.DARK")), "system mode")
        assertFires("the phone reading ignored", themeProblemsIn(editEntry(entry, "StoredThemeMode.SYSTEM, phoneIsDark", "StoredThemeMode.SYSTEM, false")), "system mode")
        assertFires("the phone reading inverted", themeProblemsIn(editEntry(entry, "StoredThemeMode.SYSTEM, phoneIsDark", "StoredThemeMode.SYSTEM, !phoneIsDark")), "system mode")
        assertFires("a second theme", themeProblemsIn(editEntry(entry, "Themes.of(shownMode(StoredThemeMode.SYSTEM, phoneIsDark))", "Themes.of(shownMode(StoredThemeMode.SYSTEM, phoneIsDark))\n    val other = Themes.of(shownMode(StoredThemeMode.SYSTEM, phoneIsDark))")), "system mode")
    }

    @Test
    fun `the platform is built from the context and the component name that was given`() {
        assertEquals("createOnboardingView platform: ", emptyList<String>(), platformProblemsIn(entry))
        val build = "AndroidSetupPlatform(context, accessibilityServiceComponent)"
        assertFires("an empty component name", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context, \"\")")), "platform")
        assertFires("a fixed component name", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context, \"a/b\")")), "platform")
        assertFires("a changed component name", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context, accessibilityServiceComponent.trim())")), "platform")
        assertFires("the application context", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context.applicationContext, accessibilityServiceComponent)")), "platform")
        assertFires("a second platform", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context, accessibilityServiceComponent)\n    val other = AndroidSetupPlatform(context, accessibilityServiceComponent)")), "platform")
    }

    @Test
    fun `the switch that was given is handed to the handler with the platform and a new screen`() {
        assertEquals("createOnboardingView handler: ", emptyList<String>(), handlerProblemsIn(entry))
        val build = "SetupIntentHandler(platform, switch, SetupScreen())"
        assertFires("another switch", handlerProblemsIn(editEntry(entry, build, "SetupIntentHandler(platform, noSwitch, SetupScreen())")), "handler")
        assertFires("another platform", handlerProblemsIn(editEntry(entry, build, "SetupIntentHandler(otherPlatform, switch, SetupScreen())")), "handler")
        assertFires("the arguments swapped", handlerProblemsIn(editEntry(entry, build, "SetupIntentHandler(switch, platform, SetupScreen())")), "handler")
        assertFires("a screen that is not new", handlerProblemsIn(editEntry(entry, build, "SetupIntentHandler(platform, switch, sharedScreen)")), "handler")
    }

    @Test
    fun `the view returned is the host built from the context, that handler, that theme and a renderer`() {
        assertEquals("createOnboardingView host: ", emptyList<String>(), hostProblemsIn(entry))
        val build = "return SetupHostView(context, handler, theme, ScreenRenderer(context))"
        assertFires("the application context for the host", hostProblemsIn(editEntry(entry, build, "return SetupHostView(context.applicationContext, handler, theme, ScreenRenderer(context))")), "host")
        assertFires("the application context for the renderer", hostProblemsIn(editEntry(entry, build, "return SetupHostView(context, handler, theme, ScreenRenderer(context.applicationContext))")), "host")
        assertFires("the handler and theme swapped", hostProblemsIn(editEntry(entry, build, "return SetupHostView(context, theme, handler, ScreenRenderer(context))")), "host")
        assertFires("a theme made on the spot", hostProblemsIn(editEntry(entry, build, "return SetupHostView(context, handler, Themes.of(shownMode(StoredThemeMode.LIGHT, false)), ScreenRenderer(context))")), "host")
        assertFires("a host that is not returned", hostProblemsIn(editEntry(entry, build, "SetupHostView(context, handler, theme, ScreenRenderer(context))\n    return null")), "host")
        assertFires("the handler built under another name", hostProblemsIn(editEntry(entry, "val handler = SetupIntentHandler(", "val other = SetupIntentHandler(")), "host")
    }

    @Test
    fun `the setup entry function calls nothing that stores, logs or reaches beyond its five lines`() {
        assertEquals("createOnboardingView reach: ", emptyList<String>(), reachProblemsIn(entry))
        val last = "val platform = AndroidSetupPlatform(context, accessibilityServiceComponent)"
        assertFires("a file written", reachProblemsIn(editEntry(entry, last, last + "\n    context.openFileOutput(\"flag\", 0).close()")), "body calls")
        assertFires("a preference written", reachProblemsIn(editEntry(entry, last, last + "\n    context.getSharedPreferences(\"ui\", 0).edit().putBoolean(\"dark\", phoneIsDark).apply()")), "body calls")
        assertFires("a log line", reachProblemsIn(editEntry(entry, last, last + "\n    Log.d(\"ui\", \"built\")")), "body calls")
        assertFires("a settings controller", reachProblemsIn(editEntry(entry, last, last + "\n    val themes = ThemeController(settings, phoneIsDark)")), "body calls")
        assertFires("a line that is no call", reachProblemsIn(editEntry(entry, last, last + "\n    counter++")), "five plain statements")
        assertFires("a call dropped", reachProblemsIn(editEntry(entry, "ScreenRenderer(context))", "ScreenRenderer)")), "no longer calls")
        assertEquals("a comment in the body is not a call", emptyList<String>(), reachProblemsIn(editEntry(entry, last, last + "\n    // settings.save(theme)\n    /* Log.d(1) */")))
    }
}
