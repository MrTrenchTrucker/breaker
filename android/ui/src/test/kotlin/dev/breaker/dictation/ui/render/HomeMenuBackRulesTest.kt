package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Source-text rules for the Back callback of the drawer in HomeHostView.kt.
 *
 * With the menu open, Back on Android 13 and newer reaches a callback the view registers on its own
 * dispatcher, and that callback calls closeMenu(). closeMenu() shuts the drawer through the same
 * CLOSE_MENU intent the dim sends, and answers whether the menu was open. The callback is taken on
 * from the one function the open state goes through, and taken off when the menu shuts and when the
 * view leaves its window. Each rule reads the text with comments and strings taken out, holds on the
 * real file, and fires on an edited copy.
 */

/** The close answer as the host writes it, compared with whitespace taken out. */
private const val CLOSE_MENU_BODY = "override fun closeMenu(): Boolean { val wasOpen = state.menuOpen " +
    "if (wasOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) return wasOpen }"

/** The one registration of the callback, on the view's own dispatcher at the default priority. */
private const val REGISTER_CALL = "dispatcher.registerOnBackInvokedCallback(" +
    "android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)"

/** The detach override, which takes the callback off with the window. */
private const val DETACH_BODY = "override fun onDetachedFromWindow() { super.onDetachedFromWindow() releaseMenuBack() }"

/** The API 33 guard that opens each of the two functions that touch the dispatcher. */
private const val API_33_GUARD = "if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return"

/** Source-text rules for the menu's Back callback in the Home view. */
class HomeMenuBackRulesTest {
    private val host = mainSourceOf("render/HomeHostView.kt")

    private val whitespace = Regex("""\s+""")

    /** [text] with comments and strings taken out and every run of whitespace removed. */
    private fun flat(text: String): String = withoutCommentsAndStrings(text).replace(whitespace, "")

    /** Whether the code of [text] holds [part], compared with comments, strings and whitespace taken out. */
    private fun holds(text: String, part: String): Boolean = flat(text).contains(flat(part))

    /** How many times [token] occurs in the code of [text], compared with whitespace taken out. */
    private fun occurrences(text: String, token: String): Int =
        Regex(Regex.escape(flat(token))).findAll(flat(text)).count()

    /** How many calls of [name] with empty brackets [text] holds; the declaration of [name] is not a call. */
    private fun calls(text: String, name: String): Int =
        Regex("(?<!fun)" + Regex.escape(name) + "\\(\\)").findAll(flat(text)).count()

    /** The named problems with the menu's Back callback in [text]; an empty list means every rule holds. */
    private fun problemsIn(text: String): List<String> {
        val problems = mutableListOf<String>()
        if (!holds(text, CLOSE_MENU_BODY)) {
            problems += "closeMenu does not close through CLOSE_MENU and answer whether the menu was open"
        }
        if (!holds(text, "val callback = android.window.OnBackInvokedCallback { closeMenu() }")) {
            problems += "the platform callback does not call closeMenu"
        }
        if (occurrences(text, ".registerOnBackInvokedCallback(") != 1 || !holds(text, REGISTER_CALL)) {
            problems += "the callback is not registered once, at the default priority, on the view's dispatcher"
        }
        if (calls(text, "registerMenuBack") != 1 || !holds(text, "if (open) registerMenuBack() else releaseMenuBack()")) {
            problems += "the callback is registered outside the open path of the menu"
        }
        if (calls(text, "releaseMenuBack") != 2 || !holds(text, "else releaseMenuBack()") || !holds(text, DETACH_BODY)) {
            problems += "the callback is not released once when the menu shuts and once on detach"
        }
        if (occurrences(text, "unregisterOnBackInvokedCallback(") != 1) {
            problems += "the callback is not taken off by one unregister call"
        }
        if (occurrences(text, API_33_GUARD) != 2) {
            problems += "the callback is not guarded to API 33 in both functions"
        }
        if (!holds(text, "showOverlay(split.first, screen) menuBack(split.first.isNotEmpty())")) {
            problems += "show does not pass the open state to the callback"
        }
        return problems
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertEquals("ui: menu back: the text to change must be in the source exactly once: $old", 1, Regex(Regex.escape(old)).findAll(text).count())
        return text.replace(old, new)
    }

    /** A failure means the edited copy was not reported with [part]. */
    private fun assertFires(what: String, edited: String, part: String) {
        val problems = problemsIn(edited)
        assertTrue("ui: menu back: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })
    }

    @Test
    fun `the real host holds every menu back rule`() {
        assertEquals("ui: menu back: the real host: ", emptyList<String>(), problemsIn(host))
    }

    @Test
    fun `closeMenu closes through the close intent and answers whether the menu was open`() {
        assertFires("a constant true answer", replaceOnce(host, "        return wasOpen\n", "        return true\n"), "closeMenu does not")
        assertFires(
            "the menu intent in place of the close intent",
            replaceOnce(host, "if (wasOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU))", "if (wasOpen) onIntent(ScreenIntent.Home(HomeActions.MENU))"),
            "closeMenu does not",
        )
        assertEquals(
            "ui: menu back: a comment in closeMenu is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, "        return wasOpen\n", "        // answers whether the menu was open\n        return wasOpen\n")),
        )
    }

    @Test
    fun `the platform callback calls closeMenu and nothing else`() {
        val call = "android.window.OnBackInvokedCallback { closeMenu() }"
        assertFires("an empty callback", replaceOnce(host, call, "android.window.OnBackInvokedCallback { }"), "does not call closeMenu")
        assertFires("a callback that refreshes", replaceOnce(host, call, "android.window.OnBackInvokedCallback { refresh() }"), "does not call closeMenu")
        assertEquals(
            "ui: menu back: a spaced callback is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, call, "android.window.OnBackInvokedCallback {\n            closeMenu()\n        }")),
        )
    }

    @Test
    fun `the callback is registered once on the view's dispatcher at the default priority`() {
        assertFires(
            "the overlay priority",
            replaceOnce(host, "PRIORITY_DEFAULT, callback)", "PRIORITY_OVERLAY, callback)"),
            "not registered once",
        )
        assertFires(
            "a second registration",
            replaceOnce(host, "        backDispatcher = dispatcher\n", "        backDispatcher = dispatcher\n        dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)\n"),
            "not registered once",
        )
        assertEquals(
            "ui: menu back: a split registration is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, REGISTER_CALL, "dispatcher.registerOnBackInvokedCallback(\n            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,\n            callback\n        )")),
        )
    }

    @Test
    fun `the callback is registered only on the open path of the menu`() {
        assertFires(
            "an unconditional registration",
            replaceOnce(host, "if (open) registerMenuBack() else releaseMenuBack()", "registerMenuBack() releaseMenuBack()"),
            "registered outside the open path",
        )
        assertFires(
            "a second registration in show",
            replaceOnce(host, "        menuBack(split.first.isNotEmpty())\n", "        menuBack(split.first.isNotEmpty())\n        registerMenuBack()\n"),
            "registered outside the open path",
        )
        assertEquals(
            "ui: menu back: a comment on the open path is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, "    private fun menuBack(open: Boolean) {\n", "    /** Registers the callback on open and releases it otherwise. */\n    private fun menuBack(open: Boolean) {\n")),
        )
    }

    @Test
    fun `the callback is released when the menu shuts and when the view detaches`() {
        assertFires(
            "no release on close",
            replaceOnce(host, "if (open) registerMenuBack() else releaseMenuBack()", "if (open) registerMenuBack() else Unit"),
            "not released once",
        )
        assertFires(
            "no release on detach",
            replaceOnce(host, "        super.onDetachedFromWindow()\n        releaseMenuBack()\n", "        super.onDetachedFromWindow()\n"),
            "not released once",
        )
        assertEquals(
            "ui: menu back: a comment on detach is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, "        super.onDetachedFromWindow()\n        releaseMenuBack()\n", "        super.onDetachedFromWindow()\n        // the callback goes with the window\n        releaseMenuBack()\n")),
        )
    }

    @Test
    fun `the callback is taken on and off only where the API is 33 or newer`() {
        assertFires(
            "the guard left out of the registration",
            replaceOnce(host, "    private fun registerMenuBack() {\n        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return\n", "    private fun registerMenuBack() {\n"),
            "not guarded to API 33",
        )
        assertFires(
            "the guard widened in the release",
            replaceOnce(host, "    private fun releaseMenuBack() {\n        if (android.os.Build.VERSION.SDK_INT < ", "    private fun releaseMenuBack() {\n        if (android.os.Build.VERSION.SDK_INT <= "),
            "not guarded to API 33",
        )
        assertEquals(
            "ui: menu back: a comment under the registration guard is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, "    private fun registerMenuBack() {\n", "    private fun registerMenuBack() {\n        // the platform callback needs API 33\n")),
        )
    }

    @Test
    fun `show hands the open state of the menu to the callback`() {
        assertFires(
            "the open state replaced by a constant",
            replaceOnce(host, "menuBack(split.first.isNotEmpty())", "menuBack(true)"),
            "does not pass the open state",
        )
        assertFires(
            "the other half of the split",
            replaceOnce(host, "menuBack(split.first.isNotEmpty())", "menuBack(split.second.isNotEmpty())"),
            "does not pass the open state",
        )
        assertEquals(
            "ui: menu back: a blank line before the call is not a change",
            emptyList<String>(),
            problemsIn(replaceOnce(host, "        menuBack(split.first.isNotEmpty())\n", "\n        menuBack(split.first.isNotEmpty())\n")),
        )
    }
}
