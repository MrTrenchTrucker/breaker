package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Protects the rule that tells a launcher-icon start from every other start of the launcher activity:
 * only the main action with the launcher category and no route is the icon.
 */
internal class LaunchRuleTest {

    private val main = "android.intent.action.MAIN"
    private val launcher = "android.intent.category.LAUNCHER"

    private class Case(
        val name: String,
        val action: String?,
        val categories: Set<String>,
        val route: String?,
        val expected: Boolean,
    )

    private val cases: List<Case> = listOf(
        Case("icon", main, setOf(launcher), null, true),
        Case("icon with more categories", main, setOf("android.intent.category.DEFAULT", launcher), null, true),
        Case("icon with a blank route", main, setOf(launcher), "  ", true),
        Case("icon with an empty route", main, setOf(launcher), "", true),
        Case("icon with the model route", main, setOf(launcher), "model", false),
        Case("tile intent without an action", null, emptySet(), null, false),
        Case("tile intent with the launcher category only", null, setOf(launcher), null, false),
        Case("notification with the model route", null, emptySet(), "model", false),
        Case("notification without a route and without the main action", null, emptySet(), null, false),
        Case("main action without the launcher category", main, emptySet(), null, false),
        Case("main action with another category", main, setOf("android.intent.category.DEFAULT"), null, false),
        Case("another action with the launcher category", "android.intent.action.VIEW", setOf(launcher), null, false),
        Case("the action in another case", "android.intent.action.main", setOf(launcher), null, false),
        Case("the category in another case", main, setOf("android.intent.category.launcher"), null, false),
        Case("null everything", null, emptySet(), null, false),
    )

    @Test
    fun `only the main action with the launcher category and no route is an icon launch`() {
        for (case in cases) {
            assertEquals(
                "app: the launch rule is wrong for the case '${case.name}'",
                case.expected,
                isIconLaunch(case.action, case.categories, case.route),
            )
        }
    }

    @Test
    fun `the table holds both answers and at least eight cases`() {
        assertEquals("app: the table needs an icon case", true, cases.any { it.expected })
        assertEquals("app: the table needs a not-icon case", true, cases.any { !it.expected })
        assertEquals("app: the table needs eight or more cases", true, cases.size >= 8)
    }
}
