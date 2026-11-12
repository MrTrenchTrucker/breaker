package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source text checks for ScreenMicProbeKit.route: which callback each branch of its when runs. route takes the
 * Android view, so it cannot be built on a plain JVM; the gate reads the source text instead. Each branch must write
 * its own line first, then call its own callback and no other. Each rule runs first on a made-up body that obeys it,
 * which must stay quiet, then on a mutant built from the real branch text with one replace, which must be reported;
 * only then is the real source asserted quiet. Comments are blanked and string literals are kept. The bare callback
 * word is not a safe anchor (the host action holds an onTap call too), so every mutant is cut from the route body.
 */
class ScreenMicProbeRouteCallbackGateTest {

    private val kitFile = "ScreenMicProbeKit.kt"

    private val lineCall = "line(\"0:\$ts:\${action.ordinal}\")"
    private val lineStart = "line(\"0:\$ts:"
    private val callbacks = mapOf(
        "TAP" to "view.onTap()",
        "BEGIN" to "view.onBegin?.invoke()",
        "CANCEL" to "view.onCancel?.invoke()",
        "SEND" to "view.onSend?.invoke()",
    )

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails unless [old] is in [text] exactly once, so a mutant can never be a no-op. */
    private fun replaceOnce(text: String, old: String, new: String): String {
        assertTrue("overlay: control: the text to change is not in the source exactly once: $old", text.split(old).size == 2)
        return text.replace(old, new)
    }

    /** The real ScreenMicProbeKit.kt from the main sources, or a failure naming the file when it is not there. */
    private fun kit(): String =
        ModuleFiles.mainTexts()[kitFile] ?: throw AssertionError("android_overlay: $kitFile is missing from the main sources")

    /** The route body, comments blanked. */
    private fun routeBody(source: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), "route")

    /** One branch of the when: from its TileAction case to the next case or the end of the body. */
    private fun branch(body: String, action: String): String? {
        val start = body.indexOf("TileAction.$action ->")
        if (start < 0) return null
        val next = body.indexOf("TileAction.", start + 1)
        return body.substring(start, if (next < 0) body.length else next)
    }

    /** The branch of the real route, or a failure naming it. */
    private fun realBranch(action: String): String {
        val body = routeBody(kit()) ?: throw AssertionError("android_overlay: no route function with a block body in $kitFile")
        return branch(body, action) ?: throw AssertionError("android_overlay: no $action branch in route of $kitFile")
    }

    /** The problems with one branch: its own line first, then its own callback, and no other callback. */
    private fun branchProblems(action: String, text: String): List<String> {
        val own = callbacks.getValue(action)
        val extra = callbacks.filterKeys { it != action }.values.filter { text.contains(it) }
        val lineAt = text.indexOf(lineStart)
        val ownAt = text.indexOf(own)
        return listOfNotNull(
            "the $action branch has no line call".takeIf { lineAt < 0 },
            "the $action branch does not call $own".takeIf { ownAt < 0 },
            "the $action branch calls two callbacks: ${extra.joinToString(", ")}".takeIf { extra.isNotEmpty() },
            "the $action branch must log before it calls $own".takeIf { lineAt >= 0 && ownAt >= 0 && lineAt > ownAt },
        )
    }

    /** The problems with the else branch of route: it runs nothing. */
    private fun elseProblems(body: String): List<String> {
        val at = body.indexOf("else ->")
        if (at < 0) return listOf("the route body has no else branch")
        return if (body.substring(at).contains("else -> Unit")) emptyList() else listOf("the else branch of route must run Unit")
    }

    /** A route that obeys every rule, for the quiet controls. */
    private val goodRoute = """fun route(view: ScreenMicView, ts: Long) {
    when (val action = TileRouting.action(view.model.state, zone)) {
        TileAction.TAP -> {
            line("0:${'$'}ts:${'$'}{action.ordinal}")
            view.onTap()
        }
        TileAction.BEGIN -> {
            line("0:${'$'}ts:${'$'}{action.ordinal}")
            view.onBegin?.invoke()
        }
        TileAction.CANCEL -> {
            line("0:${'$'}ts:${'$'}{action.ordinal}")
            view.onCancel?.invoke()
        }
        TileAction.SEND -> {
            line("0:${'$'}ts:${'$'}{action.ordinal}")
            view.onSend?.invoke()
        }
        else -> Unit
    }
}"""

    /** Run every rule for one branch: quiet on the obeying body, reported on four mutants, quiet on the real branch. */
    private fun checkBranch(action: String) {
        val own = callbacks.getValue(action)
        val other = callbacks.filterKeys { it != action }.values.first()
        val real = realBranch(action)
        val good = branch(goodRoute, action) ?: throw AssertionError("overlay: control: the obeying route has no $action branch")
        assertQuiet("the obeying $action branch", branchProblems(action, good))
        assertFires("a $action branch that calls another callback", branchProblems(action, replaceOnce(real, own, other)), "does not call $own")
        assertFires("a $action branch that calls two callbacks", branchProblems(action, replaceOnce(real, own, "$own\n                $other")), "calls two callbacks")
        assertFires("a $action branch with its line deleted", branchProblems(action, replaceOnce(real, lineCall, "")), "has no line call")
        assertFires("a $action branch that calls first and logs after", branchProblems(action, replaceOnce(replaceOnce(real, lineCall, ""), own, "$own\n                $lineCall")), "must log before")
        assertEquals("overlay: ScreenMicProbeKit.kt route $action branch must log, then call $own and no other callback", emptyList<String>(), branchProblems(action, real))
    }

    /** A failure here means the tap branch of route no longer calls the tap callback, or calls another one. */
    @Test
    fun `the TAP branch of route calls the tap callback after its line`() = checkBranch("TAP")

    /** A failure here means the begin branch of route no longer calls the begin callback, or calls another one. */
    @Test
    fun `the BEGIN branch of route calls the begin callback after its line`() = checkBranch("BEGIN")

    /** A failure here means the cancel branch of route no longer calls the cancel callback, or calls another one. */
    @Test
    fun `the CANCEL branch of route calls the cancel callback after its line`() = checkBranch("CANCEL")

    /** A failure here means the send branch of route no longer calls the send callback, or calls another one (the old gap). */
    @Test
    fun `the SEND branch of route calls the send callback after its line`() = checkBranch("SEND")

    /** A failure here means the else branch of route runs something, or is gone. */
    @Test
    fun `the else branch of route runs nothing`() {
        val body = routeBody(kit()) ?: throw AssertionError("android_overlay: no route function with a block body in $kitFile")
        assertQuiet("the obeying route else branch", elseProblems(routeBody(goodRoute) ?: ""))
        assertFires("an else branch that runs a callback", elseProblems(replaceOnce(body, "else -> Unit", "else -> view.onTap()")), "must run Unit")
        assertFires("a route with no else branch", elseProblems(replaceOnce(body, "else -> Unit", "")), "no else branch")
        assertEquals("overlay: ScreenMicProbeKit.kt route else branch must run Unit", emptyList<String>(), elseProblems(body))
    }
}
