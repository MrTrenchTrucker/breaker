package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.offeredIn
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The history screen's view, its clipboard adapter and the entry that builds them
 * are not run by any unit test, so what they must do is pinned as text. Each rule
 * reads the code with comments and string literals removed, runs on the real files,
 * and is also shown to fire on a planted sample, so a rule that nothing can make
 * fail does not pass for the right reason.
 */

/** The entry file, which builds the history view. */
private const val ENTRY = "SettingsEntry.kt"

/** The view the history screen is shown in. */
private const val HOST = "render/HistoryHostView.kt"

/** The clipboard adapter. */
private const val CLIPBOARD = "render/AndroidClipboard.kt"

/** The only file that puts work off, so the only one allowed to name a queue. */
private const val DELAYED = "render/ViewDelayedWork.kt"

/** The directory that holds the views and their adapters. */
private const val RENDER = "render/"

/** The files this change adds to the renderer. */
private val NEW_RENDER_FILES = listOf(HOST, CLIPBOARD, DELAYED)

/** The words that put work on a queue or a thread, when they are written as code. */
private val SCHEDULING = Regex("""\b(?:postDelayed|Handler|Timer|Looper|runBlocking|launch|Executor|Thread)\b""")

/** Every number written in code. */
private val NUMBER = Regex("""(?<![\w.$])\d+""")

/** A colour written through the framework colour type, or as a hex number. */
private val COLOUR_CODE = Regex("""\bColor\.|\b0[xX][0-9a-fA-F]+\b""")

/** A hex colour written as a string. */
private val COLOUR_STRING = Regex("""#[0-9a-fA-F]{6,8}\b""")

/** Output, which the clipboard adapter must never reach. */
private val LOGGING = Regex("""\b(?:Log|Timber)\.|\bprint(?:ln)?\(|\bSystem\.(?:out|err)\b|\bprintStackTrace\(""")

/** The settled signature of the entry that builds the history view, as its code spells it. */
private val SETTLED_SIGNATURE = Regex(
    """fun createHistoryView\(context: android\.content\.Context, history: dev\.breaker\.dictation\.core\.port\.HistoryStore\): android\.view\.View \{""",
)

/** The code of [relative] in the main tree, with comments and string literals removed. */
private fun codeOf(relative: String): String = withoutCommentsAndStrings(mainSourceOf(relative))

/** The scheduling words used in [code], each named once. */
private fun schedulingIn(code: String): List<String> =
    SCHEDULING.findAll(code).map { it.value }.distinct().toList()

/** Every file in [sources] other than the delayed work file that uses a scheduling word in its code, as path and word. */
private fun schedulingOutsideDelayed(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (path, _) -> path != DELAYED }
        .flatMap { (path, text) -> schedulingIn(withoutCommentsAndStrings(text)).map { "$path: $it" } }

/** The numbers in [code] that are neither zero nor one. */
private fun distancesIn(code: String): List<String> =
    NUMBER.findAll(code).map { it.value }.filterNot { it == "0" || it == "1" }.toList()

/** The top level function whose header is [header], from the header to the closing brace in the first column. */
private fun topLevelBody(code: String, header: String): String? {
    val start = code.indexOf(header)
    if (start < 0) return null
    val end = Regex("""(?m)^}""").find(code, start) ?: return null
    return code.substring(start, end.range.last + 1)
}

/** The member whose header is [header], from the header to the closing brace at the member's own indent. */
private fun memberBody(code: String, header: String): String? {
    val start = code.indexOf(header)
    if (start < 0) return null
    val end = Regex("""(?m)^    }""").find(code, start) ?: return null
    return code.substring(start, end.range.last + 1)
}

/** Whether a detach body closes the handler before it tells the superclass. */
private fun closesBeforeSuper(detach: String): Boolean {
    val close = detach.indexOf("handler.close()")
    val parent = detach.indexOf("super.onDetachedFromWindow()")
    return close >= 0 && parent >= 0 && close < parent
}

/** The history view, its clipboard adapter and the entry that builds them, pinned as text. */
class HistoryRenderCheckTest {
    @Test
    fun `the entry offers the history view with its settled signature and builds it in order`() {
        val entry = codeOf(ENTRY)
        assertTrue("createHistoryView is not offered with the settled signature", SETTLED_SIGNATURE.containsMatchIn(entry))
        val body = topLevelBody(entry, "fun createHistoryView(").orEmpty()
        for (step in listOf(
            "isNightMode(context.resources.configuration.uiMode)",
            "Themes.of(shownMode(StoredThemeMode.SYSTEM, phoneIsDark))",
            "AndroidClipboard(context)",
            "HistoryScreen()",
            "HistoryHostView(context, handler, theme, ScreenRenderer(context))",
            "work.bind(host.scheduler())",
        )) {
            assertTrue("createHistoryView does not contain: $step", body.contains(step))
        }
        assertEquals("the phone mode is read more than once in createHistoryView", 1, Regex("isNightMode\\(").findAll(body).count())
        assertEquals("the view is built more than once in createHistoryView", 1, Regex("HistoryHostView\\(").findAll(body).count())
        val planted = entry.replace("history: dev.breaker.dictation.core.port.HistoryStore", "history: Any")
        assertTrue("the planted signature did not change the file", planted != entry)
        assertFalse("a weakened signature passed", SETTLED_SIGNATURE.containsMatchIn(planted))
    }

    @Test
    fun `the entry file has none of the scheduling words in its code`() {
        assertEquals("scheduling words in $ENTRY", emptyList<String>(), schedulingIn(codeOf(ENTRY)))
    }

    @Test
    fun `the scheduling words appear in code only in the delayed work file across the whole main tree`() {
        assertTrue("the delayed work file was not read from the main tree", MAIN_SOURCES.any { (path, _) -> path == DELAYED })
        assertEquals(
            "scheduling words in code outside $DELAYED",
            emptyList<String>(),
            schedulingOutsideDelayed(MAIN_SOURCES),
        )
    }

    @Test
    fun `a scheduling word planted in another file is reported by that file name`() {
        assertEquals(
            listOf("render/SetupHostView.kt: postDelayed"),
            schedulingOutsideDelayed(listOf("render/SetupHostView.kt" to "            view.postDelayed(task, delay)\n")),
        )
        assertEquals(
            listOf("theme/Theme.kt: Thread"),
            schedulingOutsideDelayed(listOf("theme/Theme.kt" to "val worker = Thread { }\n")),
        )
    }

    @Test
    fun `a scheduling word in prose, in a string or inside a longer name is not a use`() {
        val control = "// nothing here posts to a Handler or a Thread\n" +
            "val note = \"launch\"\n" +
            "val host: SetupIntentHandler? = null\n"
        assertEquals(emptyList<String>(), schedulingOutsideDelayed(listOf("render/SetupHostView.kt" to control)))
    }

    @Test
    fun `the view delayed work posts one runnable and removes exactly that one, and no other render file does either`() {
        val delayed = codeOf(DELAYED)
        assertTrue("the work is not posted through the view queue as the file says", delayed.contains("view.postDelayed(task, delayMs)"))
        assertTrue("the posted work is not removed by the cancellation", delayed.contains("Cancellation { view.removeCallbacks(task) }"))
        assertFalse(
            "a cancellation that removes nothing passed",
            delayed.replace("Cancellation { view.removeCallbacks(task) }", "Cancellation { }").contains("removeCallbacks(task)"),
        )
        val others = MAIN_SOURCES.filter { (path, _) -> path.startsWith(RENDER) && path != DELAYED }
        val found = others.flatMap { (path, text) ->
            val code = withoutCommentsAndStrings(text)
            listOf("postDelayed", "removeCallbacks").filter { code.contains(it) }.map { "$path: $it" }
        }
        assertEquals("render files that post or remove work: $found", emptyList<String>(), found)
    }

    @Test
    fun `the clipboard adapter marks the clip sensitive, keeps the key as a string, and logs and throws nothing`() {
        val code = codeOf(CLIPBOARD)
        assertTrue(
            "the clip is not marked sensitive",
            code.contains("extras.putBoolean(IS_SENSITIVE_KEY, true)") && code.contains("clip.description.setExtras(extras)"),
        )
        assertTrue(
            "the key is not the string the platform reads",
            withoutComments(mainSourceOf(CLIPBOARD)).contains("\"android.content.extra.IS_SENSITIVE\""),
        )
        assertFalse("the clipboard adapter logs", LOGGING.containsMatchIn(code))
        assertTrue("a planted log line was not seen", LOGGING.containsMatchIn(code + "\nLog.d(x)\n"))
        assertFalse("the clipboard adapter throws", Regex("""\bthrow\b""").containsMatchIn(code))
        assertTrue("the clipboard adapter does not answer false on a failure", code.contains("catch (failure: RuntimeException)"))
    }

    @Test
    fun `the history host closes the handler once on detach, before the superclass is told`() {
        val code = codeOf(HOST)
        val detach = memberBody(code, "override fun onDetachedFromWindow()")
        assertTrue("onDetachedFromWindow is not overridden", detach != null)
        assertTrue("the handler is not closed before the superclass on detach", closesBeforeSuper(detach.orEmpty()))
        assertEquals("handler.close() calls in the host", 1, Regex("""handler\.close\(\)""").findAll(code).count())
        assertFalse(
            "a detach that never closes the handler passed",
            closesBeforeSuper(detach.orEmpty().replace("handler.close()", "")),
        )
        assertFalse(
            "a detach that closes after the superclass passed",
            closesBeforeSuper(
                detach.orEmpty().replace("handler.close()\n        super.onDetachedFromWindow()", "super.onDetachedFromWindow()\n        handler.close()"),
            ),
        )
    }

    @Test
    fun `the history host draws on attach and on focus, routes taps, and goes to the top for the notice`() {
        val code = codeOf(HOST)
        assertTrue("attachment does not draw the current screen", memberBody(code, "override fun onAttachedToWindow()").orEmpty().contains("show(handler.current())"))
        val focus = memberBody(code, "override fun onWindowFocusChanged(").orEmpty()
        assertTrue("focus does not draw the current screen when the window gains it", focus.contains("if (hasWindowFocus) {") && focus.contains("show(handler.current())"))
        assertTrue("the first drawing is not made on construction", Regex("""init \{[^}]*show\(handler\.current\(\)\)""").containsMatchIn(code))
        assertTrue("a tap does not draw the result of handling it", code.contains("show(handler.handle(intent))") && code.contains("::onIntent"))
        val noticeLine = "val held = if (screen.nodes.any { it.id == NOTICE_ID }) 0 else scrollY"
        assertTrue("the notice does not send the view to the top", code.contains(noticeLine))
        assertFalse("a host with no way to the top passed", code.replace(noticeLine, "val held = scrollY").contains(noticeLine))
        assertTrue("the scroll position is not put back", code.contains("scrollTo(0, held)"))
        assertTrue("the old child is not replaced", code.contains("removeAllViews()"))
    }

    @Test
    fun `the history host sets the handler's change hook and its scheduler runs only the handler's work`() {
        val code = codeOf(HOST)
        assertTrue("the host does not set the handler's change hook", code.contains("handler.onChange = ::show"))
        val scheduler = memberBody(code, "fun scheduler(): DelayedWork")
        assertTrue("scheduler() is not found in the host", scheduler != null)
        assertFalse("scheduler() redraws with current() itself", scheduler.orEmpty().contains("current()"))
        val planted = code.replace("posted.schedule(delayMs, work)", "posted.schedule(delayMs) { work(); show(handler.current()) }")
        assertTrue(
            "a planted redraw in scheduler() was not seen",
            memberBody(planted, "fun scheduler(): DelayedWork").orEmpty().contains("current()"),
        )
        assertFalse(
            "a host with no change hook passed",
            code.replace("handler.onChange = ::show", "").contains("handler.onChange = ::show"),
        )
    }

    @Test
    fun `the three new render files declare nothing public, name no colour and carry no distance but zero and one`() {
        for (path in NEW_RENDER_FILES) {
            assertEquals("public declarations in $path", emptyList<String>(), offeredIn(mainSourceOf(path)))
            val code = codeOf(path)
            assertFalse("a colour written as code in $path", COLOUR_CODE.containsMatchIn(code))
            assertFalse("a colour written as a string in $path", COLOUR_STRING.containsMatchIn(withoutComments(mainSourceOf(path))))
            assertEquals("numbers other than 0 and 1 in $path", emptyList<String>(), distancesIn(code))
        }
    }

    @Test
    fun `a planted number, colour or public declaration is caught by the same rules`() {
        assertEquals(listOf("48"), distancesIn("val size = 48\n"))
        assertTrue(COLOUR_CODE.containsMatchIn("val c = Color.RED\n"))
        assertTrue(COLOUR_STRING.containsMatchIn(withoutComments("val c = \"#1E7A46\"\n")))
        assertEquals(listOf("class Bare"), offeredIn("class Bare\n"))
    }
}
