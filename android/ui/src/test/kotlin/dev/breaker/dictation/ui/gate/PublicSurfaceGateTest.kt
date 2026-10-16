package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Seven declarations, offered on purpose.
 *
 * This module hands an app five views, a switch to implement and a control for the
 * model step, and nothing else: the app owns the activity, the manifest entry, the
 * settings store and what turns Breaker on, so everything else is an implementation
 * detail a later change may rename. Every top level declaration carries `internal` or
 * `private`, except the five functions that build a view, the switch interface and
 * the model-step control, each in a fixed shape.
 *
 * A declaration is read as top level when it starts in the first column, so a
 * name inside a body is not offered. How a declaration is read out of a file
 * is settled once, in GateScan.kt.
 */

/** The first file allowed to offer the view builders. */
private const val ENTRY_FILE = "SettingsEntry.kt"

/** The second file allowed to offer a view builder. */
private const val HOME_ENTRY_FILE = "HomeEntry.kt"

/** The file of the switch interface, which is offered too. */
private const val SWITCH_FILE = "BreakerSwitch.kt"

/** The file of the model-step control, which is offered too. */
private const val MODEL_CONTROL_FILE = "ModelStepControl.kt"

/** The first declaration allowed to be offered. */
private const val ENTRY_FUNCTION = "createSettingsView"

/**
 * The entry shape, parameter types in full: an entry that takes `Any` or
 * returns something other than a view must compare unequal. The brace is
 * left off.
 */
private const val SETTLED_ENTRY =
    "fun createSettingsView(context: android.content.Context, " +
        "settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View"

/** The setup entry and the switch interface, as written in their files. */
private const val SETTLED_ONBOARDING = "fun createOnboardingView(context: android.content.Context, " +
    "switch: BreakerSwitch, accessibilityServiceComponent: String, " +
    "modelReady: () -> Boolean, downloadLine: () -> String?, modelDownloadMb: () -> Int, onDownloadModel: () -> Unit, settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View"
private const val SETTLED_HISTORY = "fun createHistoryView(context: android.content.Context, " +
    "history: dev.breaker.dictation.core.port.HistoryStore): android.view.View"
private const val SETTLED_SWITCH = "interface BreakerSwitch"
private const val SETTLED_MODEL_CONTROL = "interface ModelStepControl"

/** The home entry, as written in its file. */
private const val SETTLED_HOME = "fun createHomeView(context: android.content.Context, " +
    "switch: BreakerSwitch, " +
    "modelReady: () -> Boolean, " +
    "lastDictation: () -> String?, " +
    "lastTranscript: () -> String?, " +
    "transcriptTime: () -> String?, " +
    "onCopy: (String) -> Unit, " +
    "onHistory: () -> Unit, " +
    "onSettings: () -> Unit): android.view.View"

/** Each offered file with the one shape it offers: nothing more, nothing less, nothing elsewhere. */
private val SETTLED_SURFACE = setOf(
    ENTRY_FILE to SETTLED_ENTRY, ENTRY_FILE to SETTLED_ONBOARDING, ENTRY_FILE to SETTLED_HISTORY,
    HOME_ENTRY_FILE to SETTLED_HOME,
    SWITCH_FILE to SETTLED_SWITCH, MODEL_CONTROL_FILE to SETTLED_MODEL_CONTROL, FIRST_RUN_FILE to SETTLED_FIRST_RUN,
)

private fun isSettled(offered: List<Pair<String, String>>): Boolean =
    offered.size == SETTLED_SURFACE.size && offered.map { (path, text) -> path to shaped(text) }.toSet() == SETTLED_SURFACE

/** The declarations this module offers to an app. */
private val OFFERED: List<Pair<String, String>> = MAIN_SOURCES.flatMap { (path, text) ->
    offeredIn(text).map { declaration -> path to declaration }
}

/**
 * Nothing is offered but the five entry functions and the switch interface, and
 * each is the shape the app was promised.
 */
class PublicSurfaceGateTest {
    @Test
    fun `the entry functions, the switch and the model control are the only declarations offered`() {
        assertEquals(
            "declarations offered outside $ENTRY_FILE, $FIRST_RUN_FILE, $HOME_ENTRY_FILE, $SWITCH_FILE and $MODEL_CONTROL_FILE: $OFFERED",
            setOf(ENTRY_FILE, FIRST_RUN_FILE, HOME_ENTRY_FILE, SWITCH_FILE, MODEL_CONTROL_FILE),
            OFFERED.map { it.first }.toSet(),
        )
        assertEquals("declarations offered: $OFFERED", 7, OFFERED.size)
    }

    @Test
    fun `the offered declarations have the settled shapes, with their parameter types in full`() {
        assertEquals("offered: $OFFERED", SETTLED_SURFACE, OFFERED.map { (path, text) -> path to shaped(text) }.toSet())
    }

    @Test
    fun `an eighth declaration, a changed shape and a declaration in the wrong file are caught`() {
        assertTrue("the real surface must pass its own check", isSettled(OFFERED))
        assertTrue("an eighth offered declaration passed", !isSettled(OFFERED + (ENTRY_FILE to "fun createThirdView(): android.view.View")))
        assertTrue("a missing declaration passed", !isSettled(OFFERED.drop(1)))
        assertTrue("a weakened parameter passed", !isSettled(OFFERED.map { (p, d) -> p to d.replace("switch: BreakerSwitch", "switch: Any") }))
        assertTrue("a renamed interface passed", !isSettled(OFFERED.map { (p, d) -> p to d.replace("interface BreakerSwitch", "interface BreakerToggle") }))
        assertTrue("the interface offered from the entry file passed", !isSettled(OFFERED.map { (p, d) -> (if (p == SWITCH_FILE) ENTRY_FILE else p) to d }))
    }

    @Test
    fun `the module really does declare a top level function in each entry file`() {
        val entry = mainSourceOf(ENTRY_FILE)
        assertTrue(
            "the entry file does not declare $ENTRY_FUNCTION at all",
            entry.lines().any { it.startsWith("fun $ENTRY_FUNCTION(") },
        )
        assertTrue(
            "the entry functions or the switch interface are not declared and offered, so this gate would pass on an empty module",
            offeredIn(entry).isNotEmpty() && entry.lines().any { it.startsWith("fun createOnboardingView(") } &&
                mainSourceOf(SWITCH_FILE).lines().any { it.startsWith("interface BreakerSwitch") },
        )
        val homeEntry = mainSourceOf(HOME_ENTRY_FILE)
        assertTrue(
            "the home entry file does not declare createHomeView at all",
            homeEntry.lines().any { it.startsWith("fun createHomeView(") },
        )
        assertTrue(
            "the home entry does not offer createHomeView, so this rule would pass on an empty home module",
            offeredIn(homeEntry).any { it.startsWith("fun createHomeView(") },
        )
    }

    @Test
    fun `every other file marks its declarations internal or private`() {
        val marked = MAIN_SOURCES
            .filter { (path, _) -> path != ENTRY_FILE && path != HOME_ENTRY_FILE && path != FIRST_RUN_FILE && path != SWITCH_FILE && path != MODEL_CONTROL_FILE }
            .flatMap { (path, text) -> topLevelDeclarationsIn(text).map { path to it } }
        assertTrue("no other file in the module declares anything at the top level", marked.isNotEmpty())
        val unmarked = marked.filterNot { (_, declaration) ->
            declaration.startsWith("internal ") || declaration.startsWith("private ")
        }
        assertEquals("top level declarations with no visibility: $unmarked", emptyList<Pair<String, String>>(), unmarked)
    }

    @Test
    fun `the offered list is read from the module, not from a list written here`() {
        assertTrue("no source was read, so the gate checked nothing", MAIN_SOURCES.size >= 10)
        assertTrue(
            "the entry file is not among the sources this gate read",
            MAIN_SOURCES.any { (path, _) -> path == ENTRY_FILE },
        )
        assertTrue(
            "the home entry file is not among the sources this rule read",
            MAIN_SOURCES.any { (path, _) -> path == HOME_ENTRY_FILE },
        )
        assertEquals(
            "the gate found the same declaration twice",
            OFFERED.size,
            OFFERED.distinct().size,
        )
    }
}
