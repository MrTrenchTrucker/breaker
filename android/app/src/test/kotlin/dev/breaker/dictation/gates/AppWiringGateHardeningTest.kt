package dev.breaker.dictation.gates

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks added to the wiring checks of AppWiringGateTest: a rule that reads a
 * doubled backslash matches nothing, every broken rule is listed at once, and
 * every file the rules read has a harmless edit that keeps its rules true.
 * The rules, the harmless edits and the code reader are the ones that test
 * holds, so nothing is copied here.
 */
internal class AppWiringGateHardeningTest {

    private val wiring: AppWiringGateTest = AppWiringGateTest()

    /** A harmless edit of the tile host: its one rule keeps holding when the call is wrapped. */
    private val hostQuiet: List<AppWiringGateTest.Quiet> = listOf(
        AppWiringGateTest.Quiet(
            "kotlin/dev/breaker/dictation/host/TileHost.kt",
            "onCloseRequested = { toCoordinator { it.onCloseRequested() } }",
            "onCloseRequested = {\n            toCoordinator { it.onCloseRequested() }\n        }",
        ),
    )

    @Test
    fun `no rule of the wiring checks reads a doubled backslash`() {
        val doubled = wiring.rules.filter { it.pattern.contains("\\\\") }.map { it.name }
        assertTrue("app: rules that read a doubled backslash match nothing: $doubled", doubled.isEmpty())
    }

    @Test
    fun `every broken rule of the wiring checks is listed at once`() {
        val broken = wiring.rules.filter { !it.holds(wiring.codeOf(it.file)) }.map { it.name }
        assertTrue("app: rules broken on the real files: $broken", broken.isEmpty())
    }

    @Test
    fun `every file has a harmless edit and no harmless edit breaks a rule`() {
        val edits = wiring.quiet + hostQuiet
        val covered = edits.map { it.file }.toSet()
        val uncovered = wiring.rules.filter { it.file !in covered }.map { it.name }
        assertTrue("app: rules whose file has no harmless edit: $uncovered", uncovered.isEmpty())
        val broken = edits.flatMap { e ->
            wiring.rules
                .filter { it.file == e.file && !it.holds(wiring.codeOf(e.file, e.old, e.new)) }
                .map { "${it.name} on '${e.old}' => '${e.new}'" }
        }
        assertTrue("app: a harmless edit broke a rule: $broken", broken.isEmpty())
    }
}
