package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The wiring of the first-run entry function, pinned as text, because no JVM test can build
 * the view. The function reads the phone's own mode once from the context, builds the platform
 * from the context and the accessibility component name, builds the handler from that platform,
 * the no-switch stand-in, the model providers, a new screen and the settings store, builds the
 * controller from the handler, and returns one host built from the context, the controller, the
 * theme and the phone's mode. A wrong argument in any of these compiles, so each line is pinned
 * as text. Each rule reads the body with comments and string literals removed, and is shown to
 * fire on two edited copies of the real file.
 */

private const val ENTRY_SOURCE = "FirstRunEntry.kt"
private const val ENTRY_START = "fun createFirstRunView("
private const val MISSING = "the first-run entry function is not there"
private const val LINE_END = """[ \t]*(?:\r?\n|$)"""

/** The signature and the body of the first-run entry function, with comments and strings removed. */
private class Entry(val signature: String, val body: String)

private fun entryOf(text: String): Entry? {
    val code = withoutCommentsAndStrings(text)
    val start = Regex("""\bfun\s+createFirstRunView\s*\(""").find(code) ?: return null
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

/** [text] with the first [old] after the start of the entry function replaced by [new]; fails when it is not there. */
private fun editEntry(text: String, old: String, new: String): String {
    val anchor = text.indexOf(ENTRY_START)
    assertTrue("the first-run entry function is not in the source", anchor >= 0)
    val from = text.indexOf(old, anchor)
    assertTrue("the text to change is not in the first-run entry function: $old", from >= 0)
    return text.substring(0, from) + new + text.substring(from + old.length)
}

private fun times(body: String, token: String): Int = Regex(Regex.escape(token)).findAll(body).count()

private fun assertFires(what: String, problems: List<String>, reason: String) {
    assertTrue("$what: nothing fired, expected '$reason' in $problems", problems.any { it.contains(reason) })
}

private fun phoneProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val read = Regex(
        """\bval\s+phoneIsDark\s*=\s*isNightMode\(\s*context\s*\.\s*resources\s*\.\s*configuration\s*\.\s*uiMode\s*\)""" + LINE_END,
    )
    val plain = read.findAll(body).count() == 1 && times(body, "isNightMode(") == 1 && !Regex("""!\s*isNightMode\(""").containsMatchIn(body)
    return if (plain) emptyList() else listOf("the phone mode is not read once, as it stands, from the context")
}

private fun platformProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val build = Regex("""\bval\s+platform\s*=\s*AndroidSetupPlatform\(\s*context\s*,\s*accessibilityServiceComponent\s*\)""" + LINE_END)
    val once = build.findAll(body).count() == 1 && times(body, "AndroidSetupPlatform(") == 1
    return if (once) emptyList() else listOf("the platform is not built from the context and the component name given")
}

private fun handlerProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val build = Regex(
        """\bval\s+handler\s*=\s*SetupIntentHandler\(\s*platform\s*,\s*NoSwitch\s*,\s*modelReady\s*,\s*downloadLine\s*,""" +
            """\s*modelDownloadMb\s*,\s*onDownloadModel\s*,\s*SetupScreen\(\)\s*,\s*settings\s*\)""" + LINE_END,
    )
    val once = build.findAll(body).count() == 1 && times(body, "SetupIntentHandler(") == 1 && times(body, "SetupScreen(") == 1
    return if (once) emptyList() else listOf("the handler is not built from the platform, the no-switch stand-in, the providers, a new screen and the settings store")
}

private fun controllerProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val build = Regex(
        """\bval\s+controller\s*=\s*FirstRunController\(\s*handler\s*,\s*modelReady\s*,\s*downloadLine\s*,\s*onDownloadModel\s*,\s*onFinished\s*\)""" + LINE_END,
    )
    val once = build.findAll(body).count() == 1 && times(body, "FirstRunController(") == 1
    return if (once) emptyList() else listOf("the controller is not built from the handler and the providers")
}

private fun hostProblemsIn(text: String): List<String> {
    val body = entryOf(text)?.body ?: return listOf(MISSING)
    val shape = Regex("""\breturn\s+FirstRunHostView\(\s*context\s*,\s*controller\s*,\s*theme\s*,\s*phoneIsDark\s*\)""" + LINE_END)
    val oneReturn = times(body, "return") == 1 && times(body, "FirstRunHostView(") == 1
    return if (oneReturn && shape.findAll(body).count() == 1) emptyList() else listOf("the host is not the one returned view built from the context, the controller, the theme and the phone's mode")
}

/** The first-run entry function, pinned as text: each line of its wiring, and two edited copies per line that must fail. */
class FirstRunWiringTest {
    private val entry: String = mainSourceOf(ENTRY_SOURCE)

    @Test
    fun `the first-run entry reads the phone mode once from the context and does not negate it`() {
        assertEquals("the phone mode of the real entry", emptyList<String>(), phoneProblemsIn(entry))
        val read = "isNightMode(context.resources.configuration.uiMode)"
        assertFires("the reading negated", phoneProblemsIn(editEntry(entry, read, "!isNightMode(context.resources.configuration.uiMode)")), "phone mode")
        assertFires("the reading taken from a literal", phoneProblemsIn(editEntry(entry, read, "false")), "phone mode")
    }

    @Test
    fun `the first-run entry builds the platform from the context and the component name given`() {
        assertEquals("the platform of the real entry", emptyList<String>(), platformProblemsIn(entry))
        val build = "AndroidSetupPlatform(context, accessibilityServiceComponent)"
        assertFires("the application context", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context.applicationContext, accessibilityServiceComponent)")), "the platform")
        assertFires("an empty component name", platformProblemsIn(editEntry(entry, build, "AndroidSetupPlatform(context, \"\")")), "the platform")
    }

    @Test
    fun `the first-run entry builds the handler from the platform, the no-switch stand-in, the providers, a new screen and the settings store`() {
        assertEquals("the handler of the real entry", emptyList<String>(), handlerProblemsIn(entry))
        val build = "NoSwitch, modelReady"
        assertFires("another switch", handlerProblemsIn(editEntry(entry, build, "Any, modelReady")), "the handler")
        assertFires("the settings store dropped", handlerProblemsIn(editEntry(entry, "SetupScreen(), settings)", "SetupScreen())")), "the handler")
    }

    @Test
    fun `the first-run entry builds the controller from the handler and the providers`() {
        assertEquals("the controller of the real entry", emptyList<String>(), controllerProblemsIn(entry))
        assertFires("another handler", controllerProblemsIn(editEntry(entry, "FirstRunController(handler,", "FirstRunController(platform,")), "the controller")
        assertFires("the finish callback replaced", controllerProblemsIn(editEntry(entry, "onDownloadModel, onFinished)", "onDownloadModel, onDone)")), "the controller")
    }

    @Test
    fun `the first-run entry returns one host built from the context, the controller, the theme and the phone's mode`() {
        assertEquals("the host of the real entry", emptyList<String>(), hostProblemsIn(entry))
        val shape = "FirstRunHostView(context, controller, theme, phoneIsDark)"
        assertFires("the arguments swapped", hostProblemsIn(editEntry(entry, shape, "FirstRunHostView(context, controller, phoneIsDark, theme)")), "the host")
        assertFires("a second return", hostProblemsIn(editEntry(entry, "val controller = FirstRunController(", "return null\n    val controller = FirstRunController(")), "the host")
    }
}
