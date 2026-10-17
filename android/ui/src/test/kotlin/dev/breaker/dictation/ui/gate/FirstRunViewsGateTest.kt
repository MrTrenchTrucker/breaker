package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The first-run views, checked on their source text.
 *
 * The views are not run by any unit test, so what they must do is pinned as text. Each rule
 * runs first on a made-up sample that must be reported and one that must stay quiet, and only
 * then on the real files, so a rule that nothing can trip does not pass for the right reason.
 */

/** The page views of the first run. */
private const val FR_PAGES = "render/FirstRunPageViews.kt"

/** The host view of the first run. */
private const val FR_HOST = "render/FirstRunHostView.kt"

/** The download page of the first run. */
private const val FR_DOWNLOAD = "render/FirstRunDownloadPage.kt"

/** The entry that builds the first-run view. */
private const val FR_ENTRY = "FirstRunEntry.kt"

/** The sizes of the first run. */
private const val FR_LAYOUT = "screen/firstrun/FirstRunLayout.kt"

/** The stand-in switch of the first run. */
private const val FR_NO_SWITCH = "screen/firstrun/NoSwitch.kt"

/** The render files of the first run. */
private val FR_RENDER_FILES = listOf(FR_HOST, FR_PAGES, FR_DOWNLOAD)

/** The plain files of the first run, which name no framework. */
private val FR_PLAIN_FILES = listOf(FR_LAYOUT, FR_NO_SWITCH)

/** A number written in code, decimals included; a digit inside a name or a member access is not a start. */
private val FR_NUMBER = Regex("""(?<![\w$.])\d+(?:\.\d+)?""")

/** A colour written through the framework colour type, or as a hex number. */
private val FR_COLOUR_CODE = Regex("""\bColor\.|\b0[xX][0-9a-fA-F]+\b""")

/** A hex colour written as a string. */
private val FR_COLOUR_STRING = Regex("""#[0-9a-fA-F]{6,8}\b""")

/** Every palette slot named in code. */
private val FR_SLOT_USE = Regex("""PaletteSlot\.\w+""")

/** Every palette slot read the one permitted way. */
private val FR_SLOT_READ = Regex("""theme\.color\(PaletteSlot\.\w+\)\.argb""")

/** The words that put work on a queue or a thread, or start work outside the history model. */
private val FR_SCHEDULING = Regex(
    """\b(?:postDelayed|removeCallbacks|Handler|Thread|Timer|Looper|runBlocking|launch|Executor|GlobalScope|withContext|async|Dispatchers)\b""",
)

/** A plain button, which the first run never uses. */
private val FR_PLAIN_BUTTON = Regex("""\bButton\(""")

/** A call made on the download bar. */
private val FR_BAR_CALL = Regex("""\bbar\??\.(\w+)""")

/** The only calls the download bar may take from a page. */
private val FR_BAR_CALLS = setOf("setProgress", "setChecking")

/** The download start, which only the controller may ask for. */
private val FR_START_NAME = Regex("""\bonDownloadModel\b""")

/** A resource reached by its generated name, which the first run never uses. */
private val FR_GENERATED_RESOURCE = Regex("""\bR\.drawable\b""")

/** A value declared by name, in code. */
private val FR_VALUE_NAME = Regex("""\b(?:val|var)\s+(\w+)""")

/** Words that name a distance, which a render file may not give to a value. */
private val FR_DISTANCE_WORDS = listOf("dp", "px", "pad", "padding", "margin", "inset", "gap", "spacing", "space")

/** The hard cap on a main file, in lines. */
private const val FR_HARD_CAP_LINES = 500

/** The numbers in the code of [text] that are neither zero nor one. */
private fun frNumbersIn(text: String): List<String> =
    FR_NUMBER.findAll(withoutCommentsAndStrings(text)).map { it.value }.filterNot { it == "0" || it == "1" }.toList()

/** The colour problems in [text]: a framework colour, a hex string or number, or a slot not read through the theme. */
private fun frColourProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val problems = mutableListOf<String>()
    if (FR_COLOUR_CODE.containsMatchIn(code)) problems += "a colour written as code"
    if (FR_COLOUR_STRING.containsMatchIn(withoutComments(text))) problems += "a colour written as a string"
    if (FR_SLOT_USE.findAll(code).count() != FR_SLOT_READ.findAll(code).count()) {
        problems += "a palette slot read other than as theme.color(PaletteSlot.X).argb"
    }
    return problems
}

/** The scheduling words in the code of [text]. */
private fun frSchedulingIn(text: String): List<String> =
    FR_SCHEDULING.findAll(withoutCommentsAndStrings(text)).map { it.value }.distinct().toList()

/** The problems in [text] that name the download start, a plain button or a generated resource. */
private fun frStartProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val problems = mutableListOf<String>()
    if (FR_START_NAME.containsMatchIn(code)) problems += "the download start named"
    if (FR_PLAIN_BUTTON.containsMatchIn(code)) problems += "a plain button"
    if (FR_GENERATED_RESOURCE.containsMatchIn(code)) problems += "a generated resource name"
    return problems
}

/** The calls on the download bar in [text] that are not the two allowed ones. */
private fun frBarProblemsIn(text: String): List<String> =
    FR_BAR_CALL.findAll(withoutCommentsAndStrings(text)).map { it.groupValues[1] }.filterNot { it in FR_BAR_CALLS }.toList()

/** The top level declarations of [text] that are neither internal nor private. */
private fun frOpenDeclarationsIn(text: String): List<String> =
    topLevelDeclarationsIn(text).filterNot { it.startsWith("internal ") || it.startsWith("private ") }

/** The value names in [text] that name a distance. */
private fun frDistanceNamesIn(text: String): List<String> =
    FR_VALUE_NAME.findAll(withoutCommentsAndStrings(text)).map { it.groupValues[1] }
        .filter { name -> FR_DISTANCE_WORDS.any { word -> name.lowercase().contains(word) } }
        .toList()

/** Whether [text] takes a touch, which is a return of true from the touch dispatch. */
private fun frTakesTouch(text: String): Boolean = Regex("""\breturn\s+true\b""").containsMatchIn(withoutCommentsAndStrings(text))

/** The own grant button of a permission page: the statement that draws the page's own missing item. */
private val FR_OWN_BUTTON = Regex("""\bshown\.missing\.firstOrNull\s*\{\s*it\.page == shown\.page\s*\}""")

/** The own grant button when the last-permission test guards it, so it is not drawn on that page. */
private val FR_OWN_BUTTON_GUARDED =
    Regex("""if \(!FirstRunPages\.isLastPermission\(shown\.page\)\)\s*shown\.missing\.firstOrNull\s*\{\s*it\.page == shown\.page\s*\}""")

/** The own grant buttons in [text] that no last-permission guard holds back. */
private fun frOwnButtonProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val unguarded = FR_OWN_BUTTON.findAll(code).count() - FR_OWN_BUTTON_GUARDED.findAll(code).count()
    return if (unguarded > 0) listOf("$unguarded own grant button not guarded by the last permission page") else emptyList()
}

/** The download page's notice line: it must come before the phase branch, so every phase shows it. */
private val FR_NOTICE_DRAWN = Regex("""shown\.notice\?\.let \{ page\.addView\(dangerText\(it\)\) \}""")

/** The problems with the notice on a download page in [text]. */
private fun frNoticeProblemsIn(text: String): List<String> {
    val code = withoutCommentsAndStrings(text)
    val drawnAt = FR_NOTICE_DRAWN.find(code)?.range?.first ?: return listOf("the notice is not drawn on the download page")
    val branchAt = code.indexOf("return when (download.phase)")
    return if (branchAt < 0 || drawnAt > branchAt) listOf("the notice is drawn after the phase branch") else emptyList()
}

/** The first-run source at [path], read from the module. */
private fun frText(path: String): String = mainSourceOf(path)

/** The first-run checks: each rule is shown to fire on a sample, then held on the real files. */
class FirstRunViewsGateTest {
    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("first-run views: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("first-run views: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the render files of the first run hold no number but zero and one`() {
        assertFires("a number", frNumbersIn("val gap = 16\n").map { "number $it" }, "16")
        assertQuiet("zero and one", frNumbersIn("val x = 0 + 1\n").map { "number $it" })
        val found = FR_RENDER_FILES.flatMap { path -> frNumbersIn(frText(path)).map { "$path: $it" } }
        assertEquals("numbers in the first-run render files: $found", emptyList<String>(), found)
    }

    @Test
    fun `no distance-named value is declared in a render file`() {
        assertFires("a gap-named value", frDistanceNamesIn("val rowGap = 1\n"), "rowGap")
        assertQuiet("a density value", frDistanceNamesIn("val density = 1f\n"))
        val found = FR_RENDER_FILES.flatMap { path -> frDistanceNamesIn(frText(path)).map { "$path: $it" } }
        assertEquals("distance-named values in the first-run render files: $found", emptyList<String>(), found)
    }

    @Test
    fun `a colour is written only as a read from the theme`() {
        assertFires("a colour constant", frColourProblemsIn("val c = Color.RED\n"), "colour written as code")
        assertFires("a hex string", frColourProblemsIn("val c = \"#1E7A46\"\n"), "colour written as a string")
        assertFires("a slot passed directly", frColourProblemsIn("view.setTextColor(PaletteSlot.TEXT)\n"), "palette slot")
        assertQuiet("a theme read", frColourProblemsIn("view.setTextColor(theme.color(PaletteSlot.TEXT).argb)\n"))
        val found = FR_RENDER_FILES.flatMap { path -> frColourProblemsIn(frText(path)).map { "$path: $it" } }
        assertEquals("colours written in the first-run render files: $found", emptyList<String>(), found)
    }

    @Test
    fun `no scheduling word is written in the first-run files`() {
        assertFires("a delayed post", frSchedulingIn("view.postDelayed(task, 3000)\n"), "postDelayed")
        assertFires("a thread", frSchedulingIn("val t = Thread { }\n"), "Thread")
        assertQuiet("a word in a string", frSchedulingIn("val note = \"launch\"\n"))
        val found = (FR_RENDER_FILES + FR_PLAIN_FILES).flatMap { path -> frSchedulingIn(frText(path)).map { "$path: $it" } }
        assertEquals("scheduling words in the first-run files: $found", emptyList<String>(), found)
    }

    @Test
    fun `the host asks for the download only through the controller`() {
        assertFires("the start called directly", frStartProblemsIn("onDownloadModel()\n"), "download start")
        assertQuiet("the controller's tap", frStartProblemsIn("controller.tapDownload()\n"))
        val found = FR_RENDER_FILES.flatMap { path -> frStartProblemsIn(frText(path)).map { "$path: $it" } }
        assertEquals("download start or plain button or generated name: $found", emptyList<String>(), found)
    }

    @Test
    fun `the download bar takes only its two calls and no plain button is used`() {
        assertFires("a call that is not allowed", frBarProblemsIn("bar.setDone()\n"), "setDone")
        assertQuiet("the two allowed calls", frBarProblemsIn("bar.setProgress(d, t)\nbar?.setChecking()\n"))
        val found = FR_RENDER_FILES.flatMap { path -> frBarProblemsIn(frText(path)).map { "$path: $it" } }
        assertEquals("calls on the download bar: $found", emptyList<String>(), found)
        assertFires("a plain button", frStartProblemsIn("val b = Button(context)\n"), "plain button")
        assertQuiet("the full-width button", frStartProblemsIn("FullWidthButtonView(context, label, theme)\n"))
    }

    @Test
    fun `a picture is found only by its resource name`() {
        assertFires("a generated name", frStartProblemsIn("val p = R.drawable.logo\n"), "generated resource")
        assertQuiet("a lookup by name", frStartProblemsIn("resources.getIdentifier(name, \"drawable\", pkg)\n"))
    }

    @Test
    fun `the entry declares exactly one public function, with the settled signature, and passes no switch of its own`() {
        val entry = frText(FR_ENTRY)
        val offered = offeredIn(entry)
        assertEquals("public declarations in the entry: $offered", 1, offered.size)
        assertTrue("the entry function is not the settled one: $offered", offered.single().startsWith("fun createFirstRunView("))
        assertTrue("the entry does not pass NoSwitch", withoutCommentsAndStrings(entry).contains("NoSwitch"))
        assertEquals("two public functions are counted as two", 2, offeredIn("fun a() = 1\nfun b() = 2\n").size)
    }

    @Test
    fun `every other top-level declaration of the first run is internal or private`() {
        assertFires("a public class", frOpenDeclarationsIn("class Bare\n"), "class Bare")
        assertQuiet("an internal class", frOpenDeclarationsIn("internal class Kept\n"))
        val entryOthers = frOpenDeclarationsIn(frText(FR_ENTRY)).filterNot { it.startsWith("fun createFirstRunView(") }
        val found = (FR_RENDER_FILES + FR_PLAIN_FILES).flatMap { path -> frOpenDeclarationsIn(frText(path)).map { "$path: $it" } } +
            entryOthers.map { "$FR_ENTRY: $it" }
        assertEquals("public top-level declarations in the first run: $found", emptyList<String>(), found)
    }

    @Test
    fun `the welcome timer is started and cancelled, and the host never takes a touch`() {
        assertTrue("a return of true is not seen as a taken touch", frTakesTouch("override fun dispatchTouchEvent(e: MotionEvent): Boolean {\n return true\n}\n"))
        assertFalse("a passed-on touch is seen as taken", frTakesTouch("return super.dispatchTouchEvent(event)\n"))
        val host = frText(FR_HOST)
        val code = withoutCommentsAndStrings(host)
        assertTrue("the welcome timer is not started on attachment", code.contains("welcome.start()"))
        assertTrue("the welcome timer is not cancelled on detach", code.contains("welcome.cancel()"))
        assertTrue("the touch is not passed on", code.contains("return super.dispatchTouchEvent(event)"))
        assertFalse("the host takes a touch", frTakesTouch(host))
    }

    @Test
    fun `no file of the first run is longer than the hard cap`() {
        val long = List(FR_HARD_CAP_LINES + 1) { "val x = 0" }.joinToString("\n")
        assertTrue("a file over the cap was not counted", long.lines().size > FR_HARD_CAP_LINES)
        val found = (FR_RENDER_FILES + FR_PLAIN_FILES + FR_ENTRY).filter { path -> frText(path).lines().size > FR_HARD_CAP_LINES }
        assertEquals("first-run files over the hard cap: $found", emptyList<String>(), found)
    }

    @Test
    fun `the last permission page does not draw its own grant button`() {
        val unguarded = "shown.missing.firstOrNull { it.page == shown.page }?.let { item ->\n" +
            "    page.addView(fullButton(FirstRunWords.buttonFor(item.action)) { onTap(item.action) })\n}\n"
        val guarded = "if (!FirstRunPages.isLastPermission(shown.page)) shown.missing.firstOrNull { it.page == shown.page }?.let { item ->\n" +
            "    page.addView(fullButton(FirstRunWords.buttonFor(item.action)) { onTap(item.action) })\n}\n"
        assertFires("an own grant button still drawn on the last page", frOwnButtonProblemsIn(unguarded), "not guarded")
        assertQuiet("an own grant button held back on the last page", frOwnButtonProblemsIn(guarded))
        val found = frOwnButtonProblemsIn(frText(FR_PAGES))
        assertEquals("own grant buttons not held back in the first-run page views: $found", emptyList<String>(), found)
    }

    @Test
    fun `the download page draws the notice before its phase branch`() {
        val ignores = "val page = column()\npage.addView(bodyText(FirstRunWords.modelWhy()))\nreturn when (download.phase) {\n}\n"
        val after = "val page = column()\nreturn when (download.phase) {\n}\nshown.notice?.let { page.addView(dangerText(it)) }\n"
        val drawn = "val page = column()\nshown.notice?.let { page.addView(dangerText(it)) }\nreturn when (download.phase) {\n}\n"
        assertFires("a download page that ignores the notice", frNoticeProblemsIn(ignores), "not drawn")
        assertFires("a notice drawn after the phase branch", frNoticeProblemsIn(after), "after the phase branch")
        assertQuiet("a notice drawn before the phase branch", frNoticeProblemsIn(drawn))
        val found = frNoticeProblemsIn(frText(FR_DOWNLOAD))
        assertEquals("notice problems on the download page: $found", emptyList<String>(), found)
    }

    @Test
    fun `the first-run entry file may name the framework and a third file may not`() {
        val control = "import android.view.View\n"
        assertEquals(emptyList<String>(), forbiddenNamesIn(FIRST_RUN_ENTRY_FILE, control))
        assertEquals(listOf("android."), forbiddenNamesIn("Third.kt", control))
    }
}
