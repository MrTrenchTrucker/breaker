package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-out wiring that no JVM test can run, because it lives in Android code or behind a lambda nothing calls:
 * the composition root seeds the live flag from the addressed session (the real `sessionLive` lazy), the finish result
 * re-evaluates that flag via `onFinishResultFor(app.compositionRoot.sessionLive, result)`, the createHomeView call wires
 * the notice's liveness (`sessionLive = { app.compositionRoot.sessionLive.liveNow() }`) and routes the sign-in tile back
 * to signing in (`onSignIn = { openSignIn() }`). The same two lines are also passed to the Settings view (the signed-out block lives there now), so each rule counts both calls. The gate reads these two source files with comments removed by the shared
 * scanner. Each rule holds on the real file, is broken by at least one edited sample, and stays quiet on harmless edits; an
 * edit whose target text is missing fails by name, so a sample cannot go quiet by a typo. Mirrors AppWiringGateTest's idiom.
 */
internal class SignedOutWiringGateTest {

    private val root: String = "kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"
    private val activity: String = "kotlin/dev/breaker/dictation/SettingsLauncherActivity.kt"

    /** The pattern must match the code of [file] exactly [times] times. */
    internal class Rule(val name: String, val file: String, val pattern: String, val times: Int = 1) {
        fun holds(code: String): Boolean = Regex(pattern).findAll(code).count() == times
    }

    /** An edit of [file] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val file: String, val old: String, val new: String)

    /** An edit of [file] that leaves everything a rule reads as it was. */
    internal class Quiet(val file: String, val old: String, val new: String)

    private fun read(file: String): String =
        if (file == activity) AppSourceFiles.launcherCode()
        else File(AppSourceFiles.moduleRoot, "src/main/kotlin/" + file.removePrefix("kotlin/")).readText()

    internal val rules: List<Rule> = listOf(
        Rule("SESSIONLIVE_SEEDS_FROM_ADDRESSED_SESSION", root, """\bsessionLive\s*:\s*SessionLiveState\s+by\s+lazy\s*\{\s*sessionLiveState\s*\?\:\s*SessionLiveState\s*\(\s*sessionLiveFrom\s*\(\s*addressedSession\s*\)\s*\)\s*\}"""),
        Rule("FINISH_RESULT_REEVALUATES_LIVE", activity, """\bapp\.compositionRoot\.sessionLive\s*,\s*result\s*\)""" ),
        Rule("HOMEREAD_LIVENESS_ON_EACH_DRAW", activity, """sessionLive\s*=\s*\{\s*app\.compositionRoot\.sessionLive\.liveNow\s*\(\s*\)\s*\}\s*,\s*onSignIn\s*=\s*onSignIn\s*,""", times = 2),
        Rule("SIGN_IN_TILE_REACHES_THE_SIGN_IN_METHOD", activity, """\bonSignIn\s*=\s*\{\s*openSignIn\s*\(\s*\)\s*,?""", times = 2),
    )

    private val samples: List<Sample> = listOf(
        Sample("SESSIONLIVE_SEEDS_FROM_ADDRESSED_SESSION", root,
            "sessionLiveState ?: SessionLiveState(sessionLiveFrom(addressedSession))",
            "live"),
        Sample("FINISH_RESULT_REEVALUATES_LIVE", activity,
            "onFinishResultFor(app.compositionRoot.sessionLive, result) },",
            "Unit },"),
        Sample("HOMEREAD_LIVENESS_ON_EACH_DRAW", activity,
            "{ app.compositionRoot.sessionLive.liveNow() }",
            "{ live }"),
        Sample("SIGN_IN_TILE_REACHES_THE_SIGN_IN_METHOD", activity,
            "{ openSignIn() }",
            "{ signIn() }"),
    )

    internal val quiet: List<Quiet> = listOf(
        Quiet(root, "sessionLiveState ?: SessionLiveState(sessionLiveFrom(addressedSession))",
            "sessionLiveState?:SessionLiveState(sessionLiveFrom(addressedSession))"),
        Quiet(activity, "            sessionLive = { app.compositionRoot.sessionLive.liveNow() },\n            onSignIn = onSignIn,",
            "            sessionLive=\n            { app.compositionRoot.sessionLive.liveNow() },\n            onSignIn =\n                onSignIn,"),
    )

    @Test
    fun everyRuleHoldsOnTheRealFiles() {
        assertEquals(0, rules.count { !it.holds(read(it.file)) })
    }

    @Test
    fun everySampleBreaksItsOwnRuleAndEveryQuietEditStaysQuiet() {
        for (sample in samples) {
            val edited = replaceOnce(sample.file, sample.old, sample.new)
            assertFalse(
                "editing ${sample.rule} must break its rule",
                rules.firstOrNull { it.name == sample.rule }!!.holds(edited)
            )
        }
        for (q in quiet) {
            val edited = replaceOneQuiet(q.file, q.old, q.new)
            for (rule in rules.filter { it.file == q.file }) {
                assertTrue(
                    "a harmless edit to ${q.file} must leave rule ${rule.name} holding",
                    rule.holds(edited)
                )
            }
        }
    }

    private fun replaceOnce(file: String, old: String, new: String): String =
        read(file).replaceFirst(old, new)

    private fun replaceOneQuiet(file: String, old: String, new: String): String =
        read(file).replaceFirst(old, new)
}
