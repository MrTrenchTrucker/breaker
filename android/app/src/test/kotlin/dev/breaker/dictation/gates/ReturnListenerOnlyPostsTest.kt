package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the listener the sign-in start view gives the return store, because no JVM test can run an
 * activity. The store calls the listener on the thread that records, which may be a background
 * thread, so the listener may only post to the main thread. Each rule reads host/SignInBrowser.kt with
 * comments and string text removed by the shared scanner: the lambda given to the store holds a post
 * call and nothing else outside the posted block, and the listener is cleared inside the destroy
 * callback. Each rule holds on the real file, is broken by at least two planted edits, and stays quiet
 * on harmless edits.
 */
internal class ReturnListenerOnlyPostsTest {

    private val file: String = "kotlin/dev/breaker/dictation/host/SignInBrowser.kt"

    private class ScanRule(val name: String, val holds: (String) -> Boolean)

    private class Planted(val rule: String, val old: String, val new: String)

    private val listenOpen: Regex = Regex("""\bstore\s*\.\s*listen\s*\{""")
    private val postCall: Regex = Regex("""\.\s*post\s*\{""")
    private val onlyPosts: Regex = Regex("""^\s*\w+\s*\.\s*post\s*\{\s*\}\s*$""")
    private val clearCall: Regex = Regex("""\bstore\s*\.\s*listen\s*\(\s*null\s*\)""")
    private val destroyOpen: Regex = Regex("""\boverride\s+fun\s+onActivityDestroyed\s*\([^)]*\)\s*\{""")

    /** The index of the brace that closes the one opened at [open]. */
    private fun closeOf(code: String, open: Int): Int {
        var depth = 0
        for (at in open until code.length) {
            if (code[at] == '{') depth += 1
            if (code[at] == '}') depth -= 1
            if (depth == 0) return at
        }
        error("app: a brace is never closed")
    }

    /** The text inside the one lambda given to the store, or null when there is not exactly one. */
    private fun listenerBody(code: String): String? {
        val opens: List<MatchResult> = listenOpen.findAll(code).toList()
        if (opens.size != 1) return null
        val open: Int = opens[0].range.last
        return code.substring(open + 1, closeOf(code, open))
    }

    /** [body] with every post block emptied, so only what the listener does itself is left. */
    private fun withoutPostedBlocks(body: String): String {
        val out = StringBuilder()
        var from = 0
        for (found in postCall.findAll(body)) {
            if (found.range.first < from) continue
            val open: Int = found.range.last
            out.append(body, from, open + 1)
            from = closeOf(body, open)
        }
        out.append(body, from, body.length)
        return out.toString()
    }

    private fun listenerHasOnePostCall(code: String): Boolean {
        val body: String = listenerBody(code) ?: return false
        return postCall.findAll(body).count() == 1
    }

    private fun listenerOnlyPosts(code: String): Boolean {
        val body: String = listenerBody(code) ?: return false
        return onlyPosts.containsMatchIn(withoutPostedBlocks(body))
    }

    private fun clearedOnlyInDestroy(code: String): Boolean {
        val opens: List<MatchResult> = destroyOpen.findAll(code).toList()
        if (opens.size != 1) return false
        val open: Int = opens[0].range.last
        val close: Int = closeOf(code, open)
        val clears: List<MatchResult> = clearCall.findAll(code).toList()
        return clears.size == 1 && clears[0].range.first in open..close
    }

    private val rules: List<ScanRule> = listOf(
        ScanRule("ONE_LISTENER_LAMBDA_HOLDS_ONE_POST_CALL") { code -> listenerHasOnePostCall(code) },
        ScanRule("THE_LISTENER_DOES_NOTHING_BUT_POST") { code -> listenerOnlyPosts(code) },
        ScanRule("LISTENER_CLEARED_ONLY_IN_DESTROY") { code -> clearedOnlyInDestroy(code) },
    )

    private val listener: String = "store.listen { main.post { screen.onResume(store.take()) } }"

    private val firing: List<Planted> = listOf(
        Planted(
            "ONE_LISTENER_LAMBDA_HOLDS_ONE_POST_CALL",
            listener,
            "store.listen { screen.onResume(store.take()) }",
        ),
        Planted(
            "ONE_LISTENER_LAMBDA_HOLDS_ONE_POST_CALL",
            listener,
            "store.listen { activity.runOnUiThread { screen.onResume(store.take()) } }",
        ),
        Planted(
            "ONE_LISTENER_LAMBDA_HOLDS_ONE_POST_CALL",
            listener,
            listener + "\n    store.listen { main.post { screen.onResume(null) } }",
        ),
        Planted(
            "THE_LISTENER_DOES_NOTHING_BUT_POST",
            listener,
            "store.listen { screen.onResume(store.take()) }",
        ),
        Planted(
            "THE_LISTENER_DOES_NOTHING_BUT_POST",
            listener,
            "store.listen { main.post { screen.onResume(store.take()) }\n        activity.recreate() }",
        ),
        Planted(
            "THE_LISTENER_DOES_NOTHING_BUT_POST",
            listener,
            "store.listen { view.invalidate()\n        main.post { screen.onResume(store.take()) } }",
        ),
        Planted(
            "LISTENER_CLEARED_ONLY_IN_DESTROY",
            "                store.listen(null)\n",
            "",
        ),
        Planted(
            "LISTENER_CLEARED_ONLY_IN_DESTROY",
            "                store.listen(null)\n",
            "                store.listen(null)\n                store.listen(null)\n",
        ),
        Planted(
            "LISTENER_CLEARED_ONLY_IN_DESTROY",
            "override fun onActivityStopped(unused: android.app.Activity) {}",
            "override fun onActivityStopped(unused: android.app.Activity) { store.listen(null) }",
        ),
    )

    private val quiet: List<Planted> = listOf(
        Planted("", listener, "// the listener only posts to the main thread\n    " + listener),
        Planted(
            "",
            listener,
            "store.listen { /* view.invalidate() is not called here */ " +
                "main.post { screen.onResume(store.take()) } }",
        ),
        Planted(
            "",
            "    val main = MainLooperPost()",
            "    val main = MainLooperPost()\n    val note = \"store.listen(null) and store.listen { }\"",
        ),
    )

    private fun edit(text: String, old: String, new: String): String {
        val at: Int = text.indexOf(old)
        check(at >= 0) { "app: a listener scan sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val raw: String = AppSourceFiles.mainFile(file)
        return AppSourceFiles.strip(if (old == null) raw else edit(raw, old, new)).code
    }

    @Test
    fun `every return listener rule holds on the real file`() {
        assertTrue("app: the sign-in browser file was read as empty", codeOf().isNotBlank())
        val broken: List<String> = rules.filter { !it.holds(codeOf()) }.map { it.name }
        assertTrue("app: the return listener breaks rules $broken", broken.isEmpty())
    }

    @Test
    fun `each return listener rule has at least two planted edits and each one fires`() {
        val names: List<String> = rules.map { it.name }
        assertEquals(
            "app: a firing sample belongs to an unknown rule",
            names.toSet(),
            firing.map { it.rule }.toSet(),
        )
        for (rule in rules) {
            val count: Int = firing.count { it.rule == rule.name }
            assertTrue(
                "app: listener rule ${rule.name} needs at least two planted edits, has $count",
                count >= 2,
            )
        }
        for (sample in firing) {
            val rule: ScanRule = rules.single { it.name == sample.rule }
            assertFalse(
                "app: listener rule ${sample.rule} must fire on '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `a harmless edit leaves every return listener rule true`() {
        for (sample in quiet) {
            for (rule in rules) {
                assertTrue(
                    "app: listener rule ${rule.name} must stay quiet on the harmless edit '${sample.old}'",
                    rule.holds(codeOf(sample.old, sample.new)),
                )
            }
        }
    }

    @Test
    fun `a planted edit whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue(
            "app: the failure must name the lost text, got ${thrown.message}",
            thrown.message!!.contains("no such text"),
        )
    }
}
