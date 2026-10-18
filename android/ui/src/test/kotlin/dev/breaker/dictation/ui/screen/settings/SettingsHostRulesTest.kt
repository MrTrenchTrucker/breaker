package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the settings host: the focus answer and its scroll, the back intent, the
 * download intent, the theme handed to the download bar, and the stock widgets the host must not draw.
 * Each rule holds on the real file, and each fails on an edited sample of its own.
 */
class SettingsHostRulesTest {
    private val host = mainSourceOf("render/SettingsHostView.kt")

    private val whitespace = Regex("""\s+""")

    private val returnsTrue = Regex("""\breturn true\b""")

    /** The text from [start] up to [end], or to the end of [text] when [end] is null; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String?): String {
        val after = text.substringAfter(start, "")
        return if (end == null) after else after.substringBefore(end, after)
    }

    /** [text] with its comments taken out, then every run of whitespace made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun editOnce(text: String, old: String, new: String): String {
        assertEquals(
            "screen: settings host: the text to change is not in the host exactly once: $old",
            1,
            Regex(Regex.escape(old)).findAll(text).count(),
        )
        return text.replace(old, new)
    }

    private fun focusBody(text: String): String =
        flat(between(text, "override fun focusDownload(): Boolean {", "override fun onAttachedToWindow()")).trim()

    private fun focusProblems(text: String): List<String> {
        val body = focusBody(text)
        return listOfNotNull(
            "the focus call does not answer false when the button is not drawn".takeUnless {
                "if (findTagged(this, SettingsIds.DOWNLOAD_ID) == null) return false" in body
            },
            "the focus call does not end on its true answer, or answers true more than once".takeUnless {
                body.endsWith("return true }") && returnsTrue.findAll(body).count() == 1
            },
        )
    }

    private fun scrollProblems(text: String): List<String> {
        val body = focusBody(text)
        return listOfNotNull(
            "the focus call does not scroll at once when laid out, and wait for the layout otherwise".takeUnless {
                "if (isLaidOut) { revealDownload() } else {" in body &&
                    "observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {" in body
            },
            "the focus call does not take its layout listener away once it has run".takeUnless {
                "observer.removeOnGlobalLayoutListener(this)" in body
            },
        )
    }

    private fun barProblems(text: String): List<String> {
        val body = flat(between(text, "internal fun show(rendered: Rendered) {", "/** The speech model row"))
        return listOfNotNull(
            "the redrawn theme is not handed to the download bar before the bar is placed".takeUnless {
                "downloadBar.setTheme(rendered.theme) if (running) placeBar(column)" in body
            },
        )
    }

    private fun backProblems(text: String): List<String> {
        val body = flat(between(text, "private fun onIntent(intent: ScreenIntent) {", "/** Shows download progress"))
        return listOfNotNull(
            "the back intent does not go to the activity's back handling".takeUnless {
                "intent == ScreenIntent.Back -> (context as? android.app.Activity)?.onBackPressed()" in body
            },
            "the download intent does not ask the app to download".takeUnless {
                "intent.action == SettingsIds.DOWNLOAD_ACTION -> onDownloadModel()" in body
            },
        )
    }

    private fun textProblems(text: String): List<String> {
        val code = flat(text)
        return listOfNotNull(
            "a stock text view is drawn by the host".takeIf { "TextView(" in code },
            "a stock button is drawn by the host".takeIf { "Button(" in code },
            "a stock image view is drawn by the host".takeIf { "ImageView(" in code },
            "a stock frame layout is drawn by the host".takeIf { "FrameLayout(" in code },
            "the idle download text is still named".takeIf { "downloadText" in code },
        )
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("screen: settings host: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    @Test
    fun `the focus call answers true only when the download button shows`() {
        assertEquals(emptyList<String>(), focusProblems(host))
        assertFires(
            "the focus answered true with no button",
            focusProblems(editOnce(host, "== null) return false", "== null) return true")),
            "does not answer false",
        )
        assertFires(
            "the focus answer moved off the last statement",
            focusProblems(editOnce(host, "        return true\n", "        return isLaidOut\n")),
            "does not end on its true answer",
        )
        assertFires(
            "a second true answer added before the scroll",
            focusProblems(editOnce(host, "        if (isLaidOut) {\n", "        if (isLaidOut) {\n            return true\n")),
            "does not end on its true answer",
        )
    }

    @Test
    fun `the focus call scrolls once the view is laid out and takes its listener away`() {
        assertEquals(emptyList<String>(), scrollProblems(host))
        assertFires(
            "a scroll at once without waiting for the layout",
            scrollProblems(editOnce(host, "        if (isLaidOut) {\n", "        if (true) {\n")),
            "does not scroll at once",
        )
        assertFires(
            "a listener that is not the layout listener",
            scrollProblems(editOnce(host, "observer.addOnGlobalLayoutListener(object", "observer.addOnPreDrawListener(object")),
            "does not scroll at once",
        )
        assertFires(
            "a listener that is never taken away",
            scrollProblems(editOnce(host, "observer.removeOnGlobalLayoutListener(this)", "Unit")),
            "does not take its layout listener away",
        )
    }

    @Test
    fun `the host hands the redrawn theme to the download bar`() {
        assertEquals(emptyList<String>(), barProblems(host))
        assertFires(
            "the redrawn theme left out of the bar",
            barProblems(editOnce(host, "        downloadBar.setTheme(rendered.theme)\n", "")),
            "not handed to the download bar",
        )
        assertFires(
            "the bar given a value other than the redrawn theme",
            barProblems(editOnce(host, "downloadBar.setTheme(rendered.theme)", "downloadBar.setTheme(themes.state.shown)")),
            "not handed to the download bar",
        )
    }

    @Test
    fun `the host sends back to the activity and the download intent to the app`() {
        assertEquals(emptyList<String>(), backProblems(host))
        assertFires(
            "the back intent handled by the host itself",
            backProblems(host.replace("?.onBackPressed()", "?.finish()")),
            "back handling",
        )
        assertFires(
            "the download intent left out",
            backProblems(host.replace("intent.action == SettingsIds.DOWNLOAD_ACTION -> onDownloadModel()", "intent.action == SettingsIds.DOWNLOAD_ACTION -> Unit")),
            "ask the app to download",
        )
    }

    @Test
    fun `the host draws no stock text view, button, image view or frame layout`() {
        assertEquals(emptyList<String>(), textProblems(host))
        assertFires("a text view drawn by the host", textProblems(host + "\nval extra = TextView(context)\n"), "text view")
        assertFires("a button drawn by the host", textProblems(host + "\nval extra = Button(context)\n"), "stock button")
        assertFires("an image view drawn by the host", textProblems(host + "\nval extra = ImageView(context)\n"), "image view")
        assertFires("a frame layout drawn by the host", textProblems(host + "\nval extra = FrameLayout(context)\n"), "frame layout")
        assertFires("the idle download text named", textProblems(host + "\nval line = downloadText\n"), "idle download text")
    }
}
