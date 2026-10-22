package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The three pin tests for a settled take redrawing the Home screen. The settle order is
 * pinned by [HomeTakeAdapterSettleTest]; these pin the three things that order must keep for the Home
 * to show the new transcript: (a) the draw re-reads the transcript, (b) the save is before any
 * post-settle draw, (c) the settle redraws Home at all. Each test names, in its KDoc, the exact
 * production change (the mutant) that makes it go RED.
 *
 * (b) and (c) are settle-level: they drive [HomeTakeAdapter.finishAndSettle] over the shared
 * [HomeTakeFakes] ports, the same objects [HomeTakeAdapterSettleTest] drives, and read the order off
 * the shared `log` and the `states`/`lines` sinks. [HomeHostView] is an Android View and is NOT
 * instantiated in unit tests, so (a) is a source-gate: it reads `render/HomeHostView.kt` from the ui
 * module on disk and checks that `merged()` re-reads `lastTranscript()` rather than caching it — the
 * same pattern [dev.breaker.dictation.ui.screen.home.HomeHostViewTest] uses. The ui source is found
 * from the test working directory, walking up to whichever ancestor holds `android/ui`.
 */
internal class HomeTakeSettlePinTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val settle = TakeSettle(store, clipboard)
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val lines = ArrayList<String?>()
    private val states = ArrayList<DictationState>()

    /** A fresh adapter over the shared ports; [onLine]/[onState] record each call, in order, into the shared log. */
    private fun adapter(): HomeTakeAdapter {
        lines.clear()
        states.clear()
        return HomeTakeAdapter(
            take = OwnedTakePort(take, owner, TakeOwnerKind.HOME),
            owner = owner,
            settle = settle,
            switch = armed(off = false),
            background = background,
            main = main,
            onLine = { line -> lines.add(line); log.add("line:$line") },
            onState = { state -> states.add(state); log.add("state:$state") },
        )
    }

    /** Begins a Home take (the first tap), then clears the begin's own entries so the settle starts clean. */
    private fun beginThenClear(a: HomeTakeAdapter) {
        a.onTap()
        log.clear()
        lines.clear()
        states.clear()
    }

    /** The switch the Home take shares, over the in-memory off-store; [off] is the user's stored choice. */
    private fun armed(off: Boolean): ArmedSwitch {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return switch
    }

    /**
     * (a) After a settle, the FIRST draw that follows shows the new transcript — because every draw
     * re-reads the transcript. Mutant that goes RED: the draw caches the transcript instead of
     * re-reading — i.e. `merged()` sets `lastTranscript` to a stored field (or a `lastTranscript`
     * without the call) rather than calling `lastTranscript()` each draw. Then `merged()`'s body no
     * longer contains `lastTranscript=lastTranscript()`, and this fails.
     */
    @Test
    fun `a settle's first draw re-reads the new transcript from the store`() {
        val mergedBody = mergedBodyFlattened()
        assertTrue(
            "app: home pin(a): merged() must call lastTranscript() on every draw, not cache a stored transcript",
            mergedBody.contains("lastTranscript=lastTranscript()"),
        )
    }

    /**
     * (b) The save happens before any post-settle draw. Mutant that goes RED: the draw is posted
     * before the save — i.e. the `main.post { onState(IDLE); onLine(line) }` block in
     * `finishAndSettle` runs before `settle.save(finished)`. With the synchronous fakes the posted
     * draw then lands in the log before "save", so `save` no longer precedes `state:IDLE` and the
     * line, and this fails.
     */
    @Test
    fun `a settle saves before any post-settle draw`() {
        val a = adapter()
        beginThenClear(a)
        a.onTap() // the second tap: finishAndSettle
        val saveAt = log.indexOf("save")
        val idleAt = log.indexOf("state:IDLE")
        val lineAt = log.indexOfFirst { it.startsWith("line:") }
        assertTrue("app: home pin(b): the settle must save", saveAt >= 0)
        assertTrue("app: home pin(b): the save must precede the IDLE redraw", saveAt < idleAt)
        assertTrue("app: home pin(b): the save must precede the showLine redraw", saveAt < lineAt)
    }

    /**
     * (c) A settle redraws Home at all. Mutant that goes RED: drop the IDLE redraw (`onState(IDLE)`)
     * and the showLine redraw (`onLine(line)`) from the settle's `main.post` block. Then the settle
     * leaves the Home on the SENDING face with no line — `states` never reaches IDLE and `lines` is
     * empty — and this fails.
     */
    @Test
    fun `a settle redraws home with the idle state and the outcome line`() {
        val a = adapter()
        beginThenClear(a)
        a.onTap() // the second tap: finishAndSettle
        assertEquals("app: home pin(c): the settle ends by redrawing the Home IDLE", DictationState.IDLE, states.last())
        assertEquals("app: home pin(c): the settle redraws the Home outcome line", "Text is in History and copied.", lines.single())
    }

    /**
     * (d) The Home's transcript SUPPLIER is a lambda literal `{ ... }` that re-reads the store on
     * every call — so the redraw after a settle shows the new transcript. Two things are pinned:
     * (1) the argument is a lambda literal itself (`lastTranscript = { ... }`), not a `run { ... }`
     * or `....let { ... }` block or a stored name that computes the value once OUTSIDE the per-draw
     * lambda; (2) the store call `historyStore.list(1)` is INSIDE that lambda's own braces, so it
     * runs each draw. A `run { ...; { once } }` block or a `.let { once -> { once } }` block reads
     * the value once outside the per-draw lambda — the argument no longer starts `lastTranscript={`,
     * so the first assert fails. A `{ null }` lambda literal is a lambda but reads nothing — no store
     * call inside the braces, so the second assert fails.
     */
    @Test
    fun `a settle's redraw reads the transcript from the store on every call`() {
        val flattened = lastTranscriptSupplierBodyFlattened()
        assertTrue(
            "app: home pin(d): lastTranscript must be a lambda literal `{ ... }` — not `run { ... }`, `....let { ... }`, or a stored name — so the transcript is read inside the per-draw lambda, not once outside it",
            flattened.startsWith("lastTranscript={"),
        )
        val lambdaBody = lambdaLiteralBody(flattened)
        assertTrue(
            "app: home pin(d): the lastTranscript lambda literal must call historyStore.list(1) inside its own braces on every call, not return a value read at create",
            lambdaBody.contains("historyStore.list(1)"),
        )
    }

    /**
     * The flattened body of [dev.breaker.dictation.ui.render.HomeHostView]'s `merged()` — the
     * expression body between `private fun merged` and the next `private fun`/`override fun`, with
     * whitespace removed. `merged()`'s body carries no comments or string literals, so a raw flatten
     * is exact: a call written `lastTranscript = lastTranscript()` flattens to `lastTranscript=lastTranscript()`.
     */
    private fun mergedBodyFlattened(): String {
        val source = homeHostViewSource()
        val code = source
        val start = code.indexOf("private fun merged")
        check(start >= 0) { "app: home pin(a): merged() not found in HomeHostView.kt" }
        val nextOverride = code.indexOf("override fun", start + 1)
        val nextPrivate = code.indexOf("private fun", start + 1)
        val end = listOf(nextOverride, nextPrivate).filter { it >= 0 }.minOrNull() ?: code.length
        return code.substring(start, end).replace(Regex("\\s+"), "")
    }

    /**
     * The ui module's [dev.breaker.dictation.ui.render.HomeHostView] source, found from the test
     * working directory. The app test set has no `mainSourceOf` for another module, so the file is
     * read from disk: each ancestor of the working directory is tried for `android/ui/...` and
     * `ui/...`, so it resolves whether the test's working directory is `android/app`, `android`, or the repo root.
     */
    private fun homeHostViewSource(): String {
        val rels = listOf(
            "ui/src/main/kotlin/dev/breaker/dictation/ui/render/HomeHostView.kt",
            "android/ui/src/main/kotlin/dev/breaker/dictation/ui/render/HomeHostView.kt",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in rels) {
                val file = File(dir, rel)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        error("app: home pin(a): HomeHostView.kt not found under ${System.getProperty("user.dir")}")
    }

    /**
     * The flattened body of [dev.breaker.dictation.SettingsLauncherActivity]'s `lastTranscript`
     * supplier — the lambda passed as `lastTranscript = { ... }` to `createHomeView`, with
     * whitespace removed. The supplier body carries no comments or string literals, so a raw flatten
     * is exact: `lastTranscript = { app.historyStore.list(1).firstOrNull()?.text }` flattens to
     * `lastTranscript={app.historyStore.list(1).firstOrNull()?.text}`.
     */
    private fun lastTranscriptSupplierBodyFlattened(): String {
        val code = settingsLauncherActivitySource()
        val start = code.indexOf("lastTranscript =")
        check(start >= 0) { "app: home pin(d): lastTranscript supplier not found in SettingsLauncherActivity.kt" }
        val open = code.indexOf('{', start)
        check(open >= 0) { "app: home pin(d): lastTranscript lambda brace not found in SettingsLauncherActivity.kt" }
        var depth = 0
        var end = code.length
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
            i++
        }
        return code.substring(start, end + 1).replace(Regex("\\s+"), "")
    }

    /**
     * The content inside the lastTranscript lambda literal's own braces — the `{ ... }` immediately
     * after `lastTranscript=` in the flattened supplier argument. Returns empty when the argument is
     * not a lambda literal (no `{` right after `lastTranscript=`), so a `run { ... }` or
     * `....let { ... }` form has no lambda body here; pin (d)'s literal check already failed for it.
     */
    private fun lambdaLiteralBody(flattened: String): String {
        val open = flattened.indexOf('{')
        if (open < 0) return ""
        var depth = 0
        var i = open
        while (i < flattened.length) {
            when (flattened[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return flattened.substring(open + 1, i) }
            }
            i++
        }
        return flattened.substring(open + 1)
    }

    /**
     * The app module's [dev.breaker.dictation.SettingsLauncherActivity] source, found from the test
     * working directory. The app test set shares the app module's `main` source set, so the file is
     * read from disk: each ancestor of the working directory is tried for `src/main/kotlin/...` and
     * `android/app/src/main/kotlin/...`, so it resolves whether the test's working directory is
     * `android/app`, `android`, or the repo root.
     */
    private fun settingsLauncherActivitySource(): String {
        val rels = listOf(
            "src/main/kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt",
            "android/app/src/main/kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in rels) {
                val file = File(dir, rel)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        error("app: home pin(d): SettingsLauncherActivity.kt not found under ${System.getProperty("user.dir")}")
    }
}
