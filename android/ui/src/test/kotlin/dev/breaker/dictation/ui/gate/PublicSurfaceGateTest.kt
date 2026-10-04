package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * One function, offered on purpose.
 *
 * This module hands an app a view and nothing else: the app owns the activity, the
 * manifest entry and the settings store, so everything else here is an
 * implementation detail that a later change is free to rename. Every top level
 * declaration therefore carries `internal` or `private`, with one exception, and
 * the exception is a fixed shape rather than a name alone: the function that builds
 * the view, in one named file, taking a context and a store and returning a view.
 *
 * A declaration is read as being at the top level when it starts in the first
 * column. That is what the language requires of one, so a name inside a class body
 * or a function body is not offered to anybody and is not counted. How a
 * declaration is read out of a file at all, meaning where it ends and what its
 * text is once spacing stops mattering, is settled once in GateScan.kt, because a
 * gate that settled those for itself would settle them differently from this one.
 */

/** The one file allowed to offer something. */
private const val ENTRY_FILE = "SettingsEntry.kt"

/** The one declaration allowed to be offered. */
private const val ENTRY_FUNCTION = "createSettingsView"

/**
 * The shape of the entry declaration.
 *
 * The parameter types are written out in full in that file, so the settled text
 * carries them in full too; comparing the whole declaration rather than the name
 * alone is what catches an entry that takes `Any` or returns something that is not
 * a view. The trailing brace is left off, because the shape is the signature and
 * not the body that follows it.
 */
private const val SETTLED_ENTRY =
    "fun createSettingsView(context: android.content.Context, " +
        "settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View"

/** The one declaration this module offers to an app. */
private val OFFERED: List<Pair<String, String>> = MAIN_SOURCES.flatMap { (path, text) ->
    offeredIn(text).map { declaration -> path to declaration }
}

/**
 * Nothing is offered but the entry function, and the entry function is the shape
 * the app was promised.
 */
class PublicSurfaceGateTest {
    @Test
    fun `the entry function is the only declaration offered`() {
        assertEquals(
            "declarations offered outside $ENTRY_FILE: $OFFERED",
            listOf(ENTRY_FILE),
            OFFERED.map { it.first }.distinct(),
        )
        assertEquals("declarations offered: $OFFERED", 1, OFFERED.size)
    }

    @Test
    fun `the entry function has the settled shape, with its parameter types in full`() {
        assertEquals(SETTLED_ENTRY, shaped(OFFERED.single().second))
    }

    @Test
    fun `the module really does declare a top level function in the entry file`() {
        val entry = mainSourceOf(ENTRY_FILE)
        assertTrue(
            "the entry file does not declare $ENTRY_FUNCTION at all",
            entry.lines().any { it.startsWith("fun $ENTRY_FUNCTION(") },
        )
        assertTrue(
            "the entry function is not offered, so this gate would pass on an empty module",
            offeredIn(entry).isNotEmpty(),
        )
    }

    @Test
    fun `every other file marks its declarations internal or private`() {
        val marked = MAIN_SOURCES
            .filter { (path, _) -> path != ENTRY_FILE }
            .flatMap { (path, text) -> topLevelDeclarationsIn(text).map { path to it } }
        assertTrue("no other file in the module declares anything at the top level", marked.isNotEmpty())
        val unmarked = marked.filterNot { (_, declaration) ->
            declaration.startsWith("internal ") || declaration.startsWith("private ")
        }
        assertEquals("top level declarations with no visibility: $unmarked", emptyList<Pair<String, String>>(), unmarked)
    }

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

    @Test
    fun `the offered list is read from the module, not from a list written here`() {
        assertTrue("no source was read, so the gate checked nothing", MAIN_SOURCES.size >= 10)
        assertTrue(
            "the entry file is not among the sources this gate read",
            MAIN_SOURCES.any { (path, _) -> path == ENTRY_FILE },
        )
        assertEquals(
            "the gate found the same declaration twice",
            OFFERED.size,
            OFFERED.distinct().size,
        )
    }

    /**
     * A body brace does not continue a signature.
     *
     * A rule that treats a line ending in `{` as unfinished reads the whole body
     * as part of the declaration, so the declaration stops being the shape the app
     * was promised and the gate fires on correct code. The control here is the
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
