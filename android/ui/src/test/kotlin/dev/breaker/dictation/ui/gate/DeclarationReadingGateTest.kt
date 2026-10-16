package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * How a declaration is read out of a file, guarded on its own.
 *
 * The reading is settled once, in GateScan.kt, and the surface rule depends on
 * it: a declaration read as top level when it is not, or a signature whose shape
 * changes under the shaping, would let a module offer something it does not mean
 * to, or hide something it does. These tests are the controls for that reading,
 * each a piece of text the rule must read one way and not another. They are
 * split from PublicSurfaceGateTest, which holds the settlement of this module's
 * own surface, so that the check stays inside its size cap.
 */

/** The entry function the reading controls test against. */
private const val ENTRY_FUNCTION = "createSettingsView"

/** The settled shape of that entry function, parameter types in full. */
private const val SETTLED_ENTRY =
    "fun createSettingsView(context: android.content.Context, " +
        "settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View"

class DeclarationReadingGateTest {

    @Test
    fun `every kind of declaration is caught, not only functions`() {
        // Each shape is listed because a rule written for one keyword passes
        // quietly on the others; the declarations this module actually uses are
        // the ones that matter most.
        val control = """
            package dev.breaker.dictation.ui.gate

            class Bare
            data class BareData(val x: Int)
            sealed class BareSealed
            enum class BareEnum { ONE }
            object BareObject
            interface BareInterface
            fun bareFun() = Unit
            val bareVal = 1
            var bareVar = 1
            typealias BareAlias = String
        """.trimIndent()
        assertEquals(10, offeredIn(control).size)
    }

    @Test
    fun `a declaration marked internal or private is left alone`() {
        val control = """
            package dev.breaker.dictation.ui.gate

            internal class Kept
            private const val KEPT = 1
            private fun keptFun() = Unit
        """.trimIndent()
        assertEquals(emptyList<String>(), offeredIn(control))
    }

    @Test
    fun `a declaration inside a body is not offered to anybody`() {
        // A line in the first column inside a raw string or a block comment is prose;
        // a line indented inside a class is a member. Neither is top level, and
        // trimming before counting would make every local value look offered.
        val control = """
            package dev.breaker.dictation.ui.gate

            internal class Holder {
                private fun member() = Unit
                fun offeredMember() = Unit
            }

            internal fun outer() {
                val local = 1
                fun inner() = Unit
            }
        """.trimIndent()
        assertEquals(
            listOf("internal class Holder", "internal fun outer()"),
            topLevelDeclarationsIn(control),
        )
        assertEquals(emptyList<String>(), offeredIn(control))
    }

    @Test
    fun `a second offered function in the entry file is caught`() {
        val control = """
            package dev.breaker.dictation.ui

            fun $ENTRY_FUNCTION(context: android.content.Context, settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View {
                return View(context)
            }

            fun createOtherView(context: android.content.Context): android.view.View {
                return View(context)
            }
        """.trimIndent()
        val offered = offeredIn(control)
        assertEquals(2, offered.size)
        assertTrue(
            "the second offered declaration was not found: $offered",
            offered.any { it.startsWith("fun createOtherView") },
        )
    }

    @Test
    fun `an entry function that takes any object is caught`() {
        val control = "fun $ENTRY_FUNCTION(context: android.content.Context, settings: Any): android.view.View"
        assertEquals(control, shaped(offeredIn(control).single()))
        assertTrue(
            "a weakened entry declaration compared equal to the settled one",
            shaped(offeredIn(control).single()) != SETTLED_ENTRY,
        )
    }

    @Test
    fun `an entry function whose return type is not a view is caught`() {
        val control = "fun $ENTRY_FUNCTION(context: android.content.Context, " +
            "settings: dev.breaker.dictation.core.port.SettingsStore): Int {"
        assertTrue(
            "a changed return type compared equal to the settled one",
            shaped(offeredIn(control).single()) != SETTLED_ENTRY,
        )
    }

    @Test
    fun `whitespace and line breaks do not decide whether a declaration matches`() {
        val spread = """
            fun $ENTRY_FUNCTION(
                context: android.content.Context,
                settings: dev.breaker.dictation.core.port.SettingsStore
            ): android.view.View {
        """.trimIndent()
        assertEquals(SETTLED_ENTRY, shaped(offeredIn(spread).single()))
        val extraSpace = "fun $ENTRY_FUNCTION( context: android.content.Context,  settings: " +
            "dev.breaker.dictation.core.port.SettingsStore ): android.view.View"
        assertEquals(SETTLED_ENTRY, shaped(offeredIn(extraSpace).single()))
        assertTrue(
            "two parameters with no comma between them compared equal to the settled shape",
            shaped(offeredIn("fun $ENTRY_FUNCTION(context: android.content.Context settings: " +
                "dev.breaker.dictation.core.port.SettingsStore): android.view.View").single()) != SETTLED_ENTRY,
        )
    }

    @Test
    fun `a visibility marker dropped from a declaration is caught`() {
        // The mutation that matters most is one character: a file where `internal`
        // was removed offers its type to every caller in the program.
        val control = "package dev.breaker.dictation.ui.gate\n\nclass Screen\n"
        assertEquals(listOf("class Screen"), offeredIn(control))
    }

    /**
     * A body brace does not continue a signature.
     *
     * A rule that treats a line ending in `{` as unfinished reads the whole body
     * as part of the declaration, so the declaration stops being the shape the app
     * was promised and the rule fires on correct code. The control here is the
     * real shape: a signature, its body on the following lines, and nothing after.
     */
    @Test
    fun `a body is not read as part of the signature that opens it`() {
        val control = """
            package dev.breaker.dictation.ui

            fun $ENTRY_FUNCTION(context: android.content.Context, settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View {
                val built = build(context)
                return SettingsHostView(context, built)
            }
        """.trimIndent()
        assertEquals(SETTLED_ENTRY, shaped(offeredIn(control).single()))
        val withBrace = "package dev.breaker.dictation.ui.gate\n\ninternal class Holder {\n    private fun member() = Unit\n}\n"
        assertEquals(listOf("internal class Holder"), topLevelDeclarationsIn(withBrace))
    }

    /**
     * A space inside a bracket pair goes; the bracket does not.
     *
     * Two parameters separated by nothing but a comma and a space are a different
     * signature from one that lost its brackets, so a rule that dropped the
     * bracket with the whitespace would let a rewritten signature compare equal.
     */
    @Test
    fun `a bracket survives the shaping that removes the space beside it`() {
        assertEquals("fun f(a: Int)", shaped("fun f( a: Int )"))
        assertTrue(
            "a signature that lost its brackets compared equal to one that kept them",
            shaped("fun f( a: Int )") != "fun fa: Int",
        )
    }
}
