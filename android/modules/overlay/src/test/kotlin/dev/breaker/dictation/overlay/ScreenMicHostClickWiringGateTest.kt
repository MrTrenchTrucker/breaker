package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source text checks for the TalkBack host-click wiring, read across BOTH files that carry it: the
 * call site in ScreenMic.kt's performAction host branch, and the decision in ScreenMicProbeKit.kt's
 * hostAction. The host node's click must run the state-only hostAction(view), never the stored
 * gesture route(view): route reads view.gesture.zone and belongs to the real-finger touch handler,
 * not the accessibility provider. The first gate reads only the performAction body, so a route() in
 * a sibling function (the touch handler's) does not trip it; the second reads only the hostAction
 * body, so it holds the decision to state alone. Each rule runs first on a made-up body that obeys
 * it, which must stay quiet, and then reports a made-up body that breaks it; only then is the real
 * main source asserted quiet. Comments are blanked and string literals are kept, as in the other
 * source checks, and every mutant below is built from the real source with a single .replace.
 */
class ScreenMicHostClickWiringGateTest {

    private val fileA = "ScreenMic.kt"
    private val fileB = "ScreenMicProbeKit.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    // --- Gate A: the performAction host branch in ScreenMic.kt ------------------------------------

    /** The performAction body, comments blanked, as the call-site rules read it. */
    private fun hostBody(source: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), "performAction")

    /** A failure here means the host node's click guard is not `action != ACTION_CLICK`, so a non-click would fall through to the host decision. */
    private fun guardProblems(source: String): List<String> {
        val body = hostBody(source) ?: return listOf("no performAction function with a block body was found")
        return if (Regex("""action\s*!=\s*AccessibilityNodeInfo\s*\.\s*ACTION_CLICK""").containsMatchIn(body)) emptyList()
        else listOf("the host click guard is not `action != AccessibilityNodeInfo.ACTION_CLICK`")
    }

    /** A failure here means the host node's click does not run the state-only hostAction, so it dispatches nothing from the state. */
    private fun dispatchProblems(source: String): List<String> {
        val body = hostBody(source) ?: return listOf("no performAction function with a block body was found")
        return if (body.contains("ScreenMicProbeKit.hostAction(")) emptyList()
        else listOf("the host click does not dispatch ScreenMicProbeKit.hostAction(")
    }

    /** A failure here means the host node's click reads the stored gesture: any route( call in the body takes the real-finger path. */
    private fun gestureProblems(source: String): List<String> {
        val body = hostBody(source) ?: return listOf("no performAction function with a block body was found")
        return if (body.contains("route(")) listOf("the host click reads the stored gesture with a route( call")
        else emptyList()
    }

    /** A failure here means the host click guard no longer comes before the hostAction dispatch: with hostAction above the guard, every host-node action (TalkBack focus, clear-focus, scroll) taps from state, not just a click. */
    private fun orderProblems(source: String): List<String> {
        val body = hostBody(source) ?: return listOf("no performAction function with a block body was found")
        val guard = Regex("""action\s*!=\s*AccessibilityNodeInfo\s*\.\s*ACTION_CLICK""").find(body)
        if (guard == null) return listOf("the host click guard is not `action != ACTION_CLICK`")
        val hostAt = body.indexOf("ScreenMicProbeKit.hostAction(")
        if (hostAt < 0) return listOf("the host click does not dispatch ScreenMicProbeKit.hostAction(")
        return if (guard.range.first < hostAt) emptyList()
               else listOf("the host click guard must come before ScreenMicProbeKit.hostAction(view)")
    }

    // --- Gate B: the hostAction decision in ScreenMicProbeKit.kt ----------------------------------

    /** The hostAction body, comments blanked, as the decision rules read it. */
    private fun hostActBody(source: String): String? = AdapterRules.blockBodyOf(SourceText.code(source), "hostAction")

    /** A failure here means the host action does not decide from state alone: it no longer calls hostClick(view.model.state). */
    private fun stateProblems(source: String): List<String> {
        val body = hostActBody(source) ?: return listOf("no hostAction function with a block body was found")
        return if (body.contains("hostClick(view.model.state)")) emptyList()
        else listOf("the host action does not decide from hostClick(view.model.state)")
    }

    /** A failure here means the host action reads the stored gesture: it reads view.gesture.zone instead of deciding from state alone. */
    private fun gestureReadProblems(source: String): List<String> {
        val body = hostActBody(source) ?: return listOf("no hostAction function with a block body was found")
        return if (body.contains("view.gesture.zone")) listOf("the host action reads the stored gesture with view.gesture.zone")
        else emptyList()
    }

    /** A failure here means the host action calls route, so a TalkBack click takes the real-finger path. */
    private fun routeCallProblems(source: String): List<String> {
        val body = hostActBody(source) ?: return listOf("no hostAction function with a block body was found")
        return if (body.contains("route(")) listOf("the host action calls route(")
        else emptyList()
    }

    // --- Bodies that obey the rules, for the quiet controls ---------------------------------------

    /** A host branch that obeys every call-site rule: click-guarded, dispatches hostAction, reads no stored gesture. */
    private val hostBranch = """override fun performAction(virtualId: Int, action: Int, arguments: Bundle?): Boolean {
    if (virtualId == HOST_VIEW_ID) {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return view.performAccessibilityAction(action, arguments)
        ScreenMicProbeKit.hostAction(view)
        return true
    }
    return false
}"""

    /** A hostAction that obeys every decision rule: it decides from hostClick(view.model.state) and reads no stored gesture. */
    private val hostActionBody = """fun hostAction(view: ScreenMicView) {
    dispatch(hostClick(view.model.state), view.onSend, view.onCancel, view.onBegin) { view.onTap() }
}"""

    /** The real ScreenMic.kt from the main sources, or a failure naming the file when it is not there. */
    private fun inFileA(): String =
        ModuleFiles.mainTexts()[fileA] ?: throw AssertionError("android_overlay: $fileA is missing from the main sources")

    /** The real ScreenMicProbeKit.kt from the main sources, or a failure naming the file when it is not there. */
    private fun inFileB(): String =
        ModuleFiles.mainTexts()[fileB] ?: throw AssertionError("android_overlay: $fileB is missing from the main sources")

    // --- Gate A tests -----------------------------------------------------------------------------

    /** A failure here means the host node's click guard is not the negated `action != ACTION_CLICK` check. */
    @Test
    fun `the host click guard is action not equals ACTION_CLICK`() {
        assertQuiet("the guarded host branch", guardProblems(hostBranch))
        assertFires("an inverted guard", guardProblems(inFileA().replace("if (action != AccessibilityNodeInfo.ACTION_CLICK)", "if (action == AccessibilityNodeInfo.ACTION_CLICK)")), "the host click guard is not")

        assertEquals("overlay: ScreenMic.kt host click guard must be `action != AccessibilityNodeInfo.ACTION_CLICK`", emptyList<String>(), guardProblems(inFileA()))
    }

    /** A failure here means the host node's click does not dispatch the state-only hostAction. */
    @Test
    fun `the host click dispatches hostAction`() {
        assertQuiet("the dispatching host branch", dispatchProblems(hostBranch))
        assertFires("a host branch with no dispatch", dispatchProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "")), "does not dispatch ScreenMicProbeKit.hostAction(")
        assertFires("a dispatch swapped for ScreenMicProbeKit.route(view)", dispatchProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "ScreenMicProbeKit.route(view)")), "does not dispatch ScreenMicProbeKit.hostAction(")
        assertFires("a dispatch swapped for view.route()", dispatchProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "view.route()")), "does not dispatch ScreenMicProbeKit.hostAction(")

        assertEquals("overlay: ScreenMic.kt host click must dispatch hostAction", emptyList<String>(), dispatchProblems(inFileA()))
    }

    /** A failure here means the host node's click reads the stored gesture with a route( call. */
    @Test
    fun `the host click never reads the stored gesture`() {
        assertQuiet("the state-only host branch", gestureProblems(hostBranch))
        assertFires("a host branch that routes via ScreenMicProbeKit.route(view)", gestureProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "ScreenMicProbeKit.route(view)")), "reads the stored gesture with a route(")
        assertFires("a route(view) added in the host branch", gestureProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "ScreenMicProbeKit.hostAction(view)\n                ScreenMicProbeKit.route(view)")), "reads the stored gesture with a route(")
        assertFires("a host branch that calls view.route()", gestureProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "view.route()")), "reads the stored gesture with a route(")
        assertFires("a view.route() added in the host branch", gestureProblems(inFileA().replace("ScreenMicProbeKit.hostAction(view)", "ScreenMicProbeKit.hostAction(view)\n                view.route()")), "reads the stored gesture with a route(")

        assertEquals("overlay: ScreenMic.kt host click must not read the stored gesture", emptyList<String>(), gestureProblems(inFileA()))
    }

    /** A failure here means the host click guard no longer precedes the hostAction dispatch, so every host-node action taps from state, not just a click. */
    @Test
    fun `the host click guard comes before hostAction`() {
        assertQuiet("the correctly-ordered host branch", orderProblems(hostBranch))
        assertFires("hostAction placed above the click guard", orderProblems(inFileA().replace("if (action != AccessibilityNodeInfo.ACTION_CLICK) return view.performAccessibilityAction(action, arguments)\n                ScreenMicProbeKit.hostAction(view)", "ScreenMicProbeKit.hostAction(view)\n                if (action != AccessibilityNodeInfo.ACTION_CLICK) return view.performAccessibilityAction(action, arguments)")), "the host click guard must come before")

        assertEquals("overlay: ScreenMic.kt host click guard must come before ScreenMicProbeKit.hostAction(view)", emptyList<String>(), orderProblems(inFileA()))
    }

    // --- Gate B tests -----------------------------------------------------------------------------

    /** A failure here means the host action does not decide from state alone. */
    @Test
    fun `the host action decides from hostClick of the view model state`() {
        assertQuiet("the state-only hostAction", stateProblems(hostActionBody))
        assertFires("a hostAction body of route(view)", stateProblems(inFileB().replace("dispatch(hostClick(view.model.state), view.onSend, view.onCancel, view.onBegin) { view.onTap() }", "route(view)")), "does not decide from hostClick")
        assertFires("a hostAction dispatching a gesture-zone action", stateProblems(inFileB().replace("dispatch(hostClick(view.model.state), view.onSend, view.onCancel, view.onBegin) { view.onTap() }", "dispatch(TileRouting.action(view.model.state, view.gesture.zone(PixelPoint(0, 0), view.height)), view.onSend, view.onCancel, view.onBegin) { view.onTap() }")), "does not decide from hostClick")

        assertEquals("overlay: ScreenMicProbeKit.kt hostAction must decide from hostClick(view.model.state)", emptyList<String>(), stateProblems(inFileB()))
    }

    /** A failure here means the host action reads the stored gesture zone. */
    @Test
    fun `the host action never reads the stored gesture zone`() {
        assertQuiet("the state-only hostAction", gestureReadProblems(hostActionBody))
        assertFires("a hostAction dispatching a gesture-zone action", gestureReadProblems(inFileB().replace("dispatch(hostClick(view.model.state), view.onSend, view.onCancel, view.onBegin) { view.onTap() }", "dispatch(TileRouting.action(view.model.state, view.gesture.zone(PixelPoint(0, 0), view.height)), view.onSend, view.onCancel, view.onBegin) { view.onTap() }")), "reads the stored gesture with view.gesture.zone")

        assertEquals("overlay: ScreenMicProbeKit.kt hostAction must not read the stored gesture zone", emptyList<String>(), gestureReadProblems(inFileB()))
    }

    /** A failure here means the host action calls route. */
    @Test
    fun `the host action never calls route`() {
        assertQuiet("the state-only hostAction", routeCallProblems(hostActionBody))
        assertFires("a hostAction body of route(view)", routeCallProblems(inFileB().replace("dispatch(hostClick(view.model.state), view.onSend, view.onCancel, view.onBegin) { view.onTap() }", "route(view)")), "calls route(")

        assertEquals("overlay: ScreenMicProbeKit.kt hostAction must not call route", emptyList<String>(), routeCallProblems(inFileB()))
    }
}
