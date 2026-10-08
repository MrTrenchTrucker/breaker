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

/** The only file that puts work off on the view's own queue, so the only one allowed to name that queue. */
private const val DELAYED = "render/ViewDelayedWork.kt"

/** The one file that launches history work, on the serial queue, so the only one allowed to launch. */
private const val ASYNC = "screen/history/AsyncHistory.kt"

/** The file that names the one serial queue the history model runs on. */
private const val HISTORY_DISPATCHER = "screen/history/HistoryDispatcher.kt"

/** The directory that holds the history screen's model and its queue. */
private const val SCREEN = "screen/"

/** The directory that holds the views and their adapters. */
private const val RENDER = "render/"

/** The files this change adds to the renderer. */
private val NEW_RENDER_FILES = listOf(HOST, CLIPBOARD, DELAYED)

/** The words that put work on a queue or a thread, or that start coroutine work outside the history model, when they are written as code. */
private val SCHEDULING = Regex("""\b(?:postDelayed|Handler|Timer|Looper|runBlocking|launch|Executor|Thread|GlobalScope|withContext|async|Dispatchers\.Main)\b""")

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

/** Every file in [sources] other than the delayed work file that uses a scheduling word in its code, as path and word. The async file may use launch and no other word. */
private fun schedulingOutsideDelayed(sources: List<Pair<String, String>>): List<String> =
    sources
        .filter { (path, _) -> path != DELAYED }
        .flatMap { (path, text) ->
            schedulingIn(withoutCommentsAndStrings(text))
                .filterNot { word -> path == ASYNC && word == "launch" }
                .map { "$path: $it" }
        }

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
    val close = detach.indexOf("asyncHistory.close()")
    val parent = detach.indexOf("super.onDetachedFromWindow()")
    return close >= 0 && parent >= 0 && close < parent
}

/** The one-wide limit the serial queue is built with, written as the queue file writes it. */
private val ONE_WIDE = Regex("""\blimitedParallelism\(1\)""")

/** Whether [code] names the one-wide limit exactly once. */
private fun oneWideIn(code: String): Boolean = ONE_WIDE.findAll(code).count() == 1

/** The host code outside its import lines, which is the text the store rules count. */
private fun hostBodyOf(code: String): String = code.lines().filterNot { it.startsWith("import ") }.joinToString("\n")

/** The ways the host's code breaks the store rules: the store type not named once, the word history not used twice, or a member called through it. */
private fun hostStoreFaults(body: String): List<String> {
    val faults = mutableListOf<String>()
    if (Regex("""\bHistoryStore\b""").findAll(body).count() != 1) faults += "the store type is not named once"
    if (Regex("""\bhistory\b""").findAll(body).count() != 2) faults += "the word history is not used twice"
    if (Regex("""\bhistory\.\w+""").containsMatchIn(body)) faults += "a member is called through history"
    return faults
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
            "HistoryDispatcher.serial",
            "HistoryHostView(context, history, AndroidClipboard(context), format, work, HistoryDispatcher.serial, theme, ScreenRenderer(context))",
            "work.bind(host.scheduler())",
        )) {
            assertTrue("history: createHistoryView does not contain: $step", body.contains(step))
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
        assertTrue("history: the delayed work file was not read from the main tree", MAIN_SOURCES.any { (path, _) -> path == DELAYED })
        assertTrue("history: the async file was not read from the main tree", MAIN_SOURCES.any { (path, _) -> path == ASYNC })
        assertEquals(
            "history: scheduling words in code outside $DELAYED and $ASYNC",
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
    fun `a launch planted in the host file is reported by its name`() {
        assertEquals(
            "history: a launch in the host file was not reported by its name",
            listOf("render/HistoryHostView.kt: launch"),
            schedulingOutsideDelayed(listOf("render/HistoryHostView.kt" to "scope.launch { }\n")),
        )
    }

    @Test
    fun `a launch in the async file is allowed`() {
        assertEquals(
            "history: a launch in the async file was reported",
            emptyList<String>(),
            schedulingOutsideDelayed(listOf(ASYNC to "scope.launch { }\n")),
        )
    }

    @Test
    fun `a Thread planted in the async file is reported`() {
        assertEquals(
            "history: a thread in the async file was not reported",
            listOf("$ASYNC: Thread"),
            schedulingOutsideDelayed(listOf(ASYNC to "val pool = Thread { }\n")),
        )
    }

    @Test
    fun `a withContext planted in another file is reported`() {
        assertEquals(
            "history: a withContext in another file was not reported",
            listOf("render/SetupHostView.kt: withContext"),
            schedulingOutsideDelayed(listOf("render/SetupHostView.kt" to "withContext(Dispatchers.IO) { }\n")),
        )
    }

    @Test
    fun `a GlobalScope planted in another file is reported`() {
        assertEquals(
            "history: a GlobalScope in another file was not reported",
            listOf("render/SetupHostView.kt: GlobalScope"),
            schedulingOutsideDelayed(listOf("render/SetupHostView.kt" to "val scope = GlobalScope\n")),
        )
    }

    @Test
    fun `an async call planted in another file is reported`() {
        assertEquals(
            "history: an async call in another file was not reported",
            listOf("render/SetupHostView.kt: async"),
            schedulingOutsideDelayed(listOf("render/SetupHostView.kt" to "val d = async { }\n")),
        )
    }

    @Test
    fun `a Dispatchers Main use planted in another file is reported`() {
        assertEquals(
            "history: a Dispatchers Main use in another file was not reported",
            listOf("render/SetupHostView.kt: Dispatchers.Main"),
            schedulingOutsideDelayed(listOf("render/SetupHostView.kt" to "val d = Dispatchers.Main\n")),
        )
    }

    @Test
    fun `a call named asyncHistory or AsyncHistory is not reported`() {
        assertEquals(
            "history: a call named asyncHistory or AsyncHistory was reported",
            emptyList<String>(),
            schedulingOutsideDelayed(listOf(HOST to "val a = asyncHistory.load()\nval b = AsyncHistory(x)\n")),
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
        assertEquals("history: asyncHistory.close() calls in the host", 1, Regex("""asyncHistory\.close\(\)""").findAll(code).count())
        assertFalse(
            "history: a detach that never closes the model passed",
            closesBeforeSuper(detach.orEmpty().replace("asyncHistory.close()", "")),
        )
        assertFalse(
            "history: a detach that closes the model after the superclass passed",
            closesBeforeSuper(
                detach.orEmpty().replace("asyncHistory.close()\n        super.onDetachedFromWindow()", "super.onDetachedFromWindow()\n        asyncHistory.close()"),
            ),
        )
    }

    @Test
    fun `the history host draws on attach and on focus, routes taps, and goes to the top for the notice`() {
        val code = codeOf(HOST)
        val attach = memberBody(code, "override fun onAttachedToWindow()").orEmpty()
        assertTrue(
            "history: attachment does not reopen the model and then read it",
            attach.contains("asyncHistory.reopen()") && attach.contains("asyncHistory.load()") &&
                attach.indexOf("asyncHistory.reopen()") < attach.indexOf("asyncHistory.load()"),
        )
        val focus = memberBody(code, "override fun onWindowFocusChanged(").orEmpty()
        assertTrue("history: focus does not read the model when the window gains it", focus.contains("if (hasWindowFocus) {") && focus.contains("asyncHistory.load()"))
        val initBlock = memberBody(code, "init {").orEmpty()
        assertTrue("history: the init block is not found", initBlock.isNotEmpty())
        assertFalse("history: the init block reads the model; the attach read draws", initBlock.contains("asyncHistory.load()"))
        val plantedInit = code.replace("init {", "init {\n        asyncHistory.load()")
        assertTrue("history: a load planted in init was not seen", memberBody(plantedInit, "init {").orEmpty().contains("asyncHistory.load()"))
        assertTrue("history: a tap is not handed to the model", code.contains("asyncHistory.tap(intent)") && code.contains("::onIntent"))
        val noticeLine = "val held = if (screen.nodes.any { it.id == NOTICE_ID }) 0 else scrollY"
        assertTrue("the notice does not send the view to the top", code.contains(noticeLine))
        assertFalse("a host with no way to the top passed", code.replace(noticeLine, "val held = scrollY").contains(noticeLine))
        assertTrue("the scroll position is not put back", code.contains("scrollTo(0, held)"))
        assertTrue("the old child is not replaced", code.contains("removeAllViews()"))
    }

    @Test
    fun `the history host sets the handler's change hook and its scheduler runs only the handler's work`() {
        val code = codeOf(HOST)
        assertFalse("history: the host sets a change hook itself", code.contains("onChange"))
        assertTrue("history: the model does not set the handler's change hook", codeOf(ASYNC).contains("handler.onChange ="))
        val scheduler = memberBody(code, "fun scheduler(): DelayedWork")
        assertTrue("scheduler() is not found in the host", scheduler != null)
        assertFalse("history: scheduler() reads the model itself", scheduler.orEmpty().contains("asyncHistory.load()"))
        val planted = code.replace("posted.schedule(delayMs, work)", "posted.schedule(delayMs) { work(); asyncHistory.load() }")
        assertTrue(
            "history: a planted read in scheduler() was not seen",
            memberBody(planted, "fun scheduler(): DelayedWork").orEmpty().contains("asyncHistory.load()"),
        )
        assertTrue(
            "history: a change hook planted in the host was not seen",
            (code + "\nhandler.onChange = ::show\n").contains("onChange"),
        )
    }

    @Test
    fun `the host calls no store and no handler directly`() {
        val code = codeOf(HOST)
        val faults = hostStoreFaults(hostBodyOf(code))
        assertTrue("history: the host breaks the store rules: $faults", faults.isEmpty())
        assertFalse("history: the host calls current() or handle()", code.contains("current()") || code.contains("handler.handle"))
        assertFalse("history: the host names the handler class", code.contains("HistoryIntentHandler"))
        assertFalse("history: the host names a handler", Regex("""\bhandler\b""").containsMatchIn(code))
    }

    @Test
    fun `a second mention of the store type in the host fails`() {
        val planted = hostBodyOf(codeOf(HOST)) + "\nval h: HistoryStore\n"
        assertTrue("history: a second store type in the host was not seen", hostStoreFaults(planted).isNotEmpty())
    }

    @Test
    fun `an aliased store call in the host fails`() {
        val planted = hostBodyOf(codeOf(HOST)) + "\nval h = history\nh.list(1)\n"
        assertTrue("history: an aliased store call in the host was not seen", hostStoreFaults(planted).isNotEmpty())
    }

    @Test
    fun `a direct store call in the host fails`() {
        val planted = hostBodyOf(codeOf(HOST)) + "\nhistory.list(1)\n"
        assertTrue("history: a direct store call in the host was not seen", hostStoreFaults(planted).isNotEmpty())
    }

    @Test
    fun `the host builds the model with the screen and the shared queue`() {
        val code = codeOf(HOST)
        assertTrue("history: the host does not build the model", code.contains("AsyncHistory("))
        assertTrue("history: the host does not build the screen", code.contains("HistoryScreen()"))
        assertTrue("history: the host does not post results to the main thread", code.contains("postToMain = { task -> post(Runnable { task() }) }"))
    }

    @Test
    fun `only the async file launches work in the history screen code`() {
        val allowed = setOf(ASYNC, HISTORY_DISPATCHER)
        val found = MAIN_SOURCES
            .filter { (path, _) -> (path.startsWith(RENDER) || path.startsWith(SCREEN)) && path !in allowed }
            .flatMap { (path, text) ->
                val code = withoutCommentsAndStrings(text)
                listOf("launch", "Dispatchers").filter { word -> Regex("""\b$word\b""").containsMatchIn(code) }.map { word -> "$path: $word" }
            }
        assertEquals("history: launch or Dispatchers outside the async and dispatcher files: $found", emptyList<String>(), found)
    }

    @Test
    fun `the history view is built on the shared serial queue`() {
        val entry = codeOf(ENTRY)
        assertTrue("history: the entry does not pass the shared queue", entry.contains("HistoryDispatcher.serial"))
        val names = Regex("""\w*Dispatcher\w*(?:\.\w+)?""").findAll(entry.lines().filterNot { it.startsWith("import ") }.joinToString("\n")).map { it.value }.toList()
        assertEquals("history: the entry names another dispatcher", listOf("HistoryDispatcher.serial"), names)
    }

    @Test
    fun `the serial dispatcher is one thread wide`() {
        val code = codeOf(HISTORY_DISPATCHER)
        assertTrue("history: the queue is not limited to one job at a time", oneWideIn(code))
        assertFalse("history: the queue names a thread", code.contains("Thread("))
        assertFalse("history: the queue names a thread local", code.contains("ThreadLocal"))
        assertFalse("history: the queue names an executor", code.contains("Executors"))
    }

    @Test
    fun `a dispatcher limited to sixteen fails the one thread check`() {
        val code = codeOf(HISTORY_DISPATCHER)
        val planted = code.replace("limitedParallelism(1)", "limitedParallelism(16)")
        assertTrue("history: the planted limit did not change the file", planted != code)
        assertFalse("history: a dispatcher limited to sixteen passed", oneWideIn(planted))
    }

    @Test
    fun `a dispatcher limited to ten fails the one thread check`() {
        val code = codeOf(HISTORY_DISPATCHER)
        val planted = code.replace("limitedParallelism(1)", "limitedParallelism(10)")
        assertTrue("history: the planted limit did not change the file", planted != code)
        assertFalse("history: a dispatcher limited to ten passed", oneWideIn(planted))
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
