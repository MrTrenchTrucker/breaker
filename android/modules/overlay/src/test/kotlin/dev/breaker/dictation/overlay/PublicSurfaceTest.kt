package dev.breaker.dictation.overlay

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the module shows to the app: the four results, the five tile states, the functions of the
 * tile, and nothing else public.
 *
 * The first two are read by reflection against literal expected values. The last is read as
 * text, with a control that must fire on wrong samples and stay quiet on right ones before the
 * real files are scanned.
 */
class PublicSurfaceTest {

    /** Stands in for a class with a mix of public and private functions, to prove the reflection filter. */
    private class ControlSurface {
        fun shown() {}
        fun other() {}
        private fun hidden() {}
    }

    /** The sorted names of the public, non-synthetic functions declared by [type] itself. */
    private fun publicMethodNames(type: Class<*>): List<String> =
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { it.name }.sorted()

    private val publicFiles = setOf("FloatingTile.kt", "ShowResult.kt", "TileState.kt")

    private val declaration = Regex(
        "^((?:@\\w+(?:\\([^)]*\\))?\\s+)*)" +
            "((?:(?:public|internal|private|protected|open|abstract|final|sealed|data|enum|annotation|value|const|inline|lateinit|tailrec|suspend|operator|infix|external)\\s+)*)" +
            "(?:class|object|interface|fun|val|var|typealias)\\b",
        RegexOption.MULTILINE,
    )

    /** The top-level declarations (first column) of the files outside [publicFiles] that are neither internal nor private. */
    private fun surfaceProblems(files: Map<String, String>): List<String> {
        val checked = files.filterKeys { it !in publicFiles }
        if (checked.isEmpty()) return listOf("no main source other than $publicFiles was found")
        return checked.flatMap { (name, source) ->
            val found = declaration.findAll(SourceText.code(source)).toList()
            if (found.isEmpty()) {
                listOf("$name has no top-level declaration")
            } else {
                found.filterNot { Regex("\\b(internal|private)\\b").containsMatchIn(it.groupValues[2]) }
                    .map { "$name has a declaration that is not internal: ${it.value.trim()}" }
            }
        }
    }

    private fun oneFile(source: String) = mapOf("Control.kt" to source)

    /** A failure here means a result was added, dropped or reordered, and the app's `when` over the results no longer fits. */
    @Test
    fun `ShowResult has exactly the four results in order`() {
        assertEquals(
            "android_overlay: expected the four results in this order",
            listOf("SHOWN", "ALREADY_SHOWN", "PERMISSION_MISSING", "FAILED"),
            ShowResult.values().map { it.name },
        )
        assertTrue("android_overlay: ShowResult must be public", Modifier.isPublic(ShowResult::class.java.modifiers))
    }

    /**
     * A failure here means a state was dropped or reordered, or a state was added in a place other than the end. The
     * five existing states must come first and in order, then the two finished outcomes SENT and SENT_LOCAL.
     */
    @Test
    fun `TileState has the five existing states first and in order then SENT and SENT_LOCAL`() {
        assertEquals(
            "overlay: expected the tile states in this order: the five existing ones, then SENT and SENT_LOCAL",
            listOf("IDLE", "ARMED", "RECORDING", "SENDING", "FAILED", "SENT", "SENT_LOCAL"),
            TileState.values().map { it.name },
        )
        assertTrue("overlay: TileState must be public", Modifier.isPublic(TileState::class.java.modifiers))
    }

    /** A failure here means the tile's public functions changed, so the app's calls no longer match the documented surface. */
    @Test
    fun `FloatingTile exposes exactly the documented public functions`() {
        val expected = listOf(
            "clearNotice", "getState", "hide", "isShown", "onDisplayChanged", "setDescription", "setLevel", "setState", "setTheme", "show", "showNotice",
        )
        assertEquals("android_overlay: control: only the public functions of a class must be listed", listOf("other", "shown"), publicMethodNames(ControlSurface::class.java))
        assertNotEquals("android_overlay: control: a class with other functions must not match the documented ones", expected, publicMethodNames(ControlSurface::class.java))

        assertEquals("android_overlay: expected exactly the documented public functions of FloatingTile", expected, publicMethodNames(FloatingTile::class.java))
        assertTrue("android_overlay: FloatingTile must be public", Modifier.isPublic(FloatingTile::class.java.modifiers))
    }

    /** A failure here means a declaration in the module is visible to the app that was meant to stay inside the module. */
    @Test
    fun `every other top-level declaration is internal`() {
        val wrong = listOf(
            "class A", "object A", "interface A", "enum class A { X }", "data class A(val x: Int)", "sealed interface A", "fun a() {}", "val a = 1",
            "var a = 1", "public fun a() {}", "@JvmInline value class A(val x: Int)", "const val A = 1", "typealias A = Int", "fun interface A { fun f() }",
            "internal class B\nclass A",
        )
        wrong.forEach { sample ->
            assertEquals("android_overlay: control: '$sample' must be reported once", 1, surfaceProblems(oneFile(sample)).size)
        }
        val right = listOf(
            "internal class A", "private fun a() {}", "internal data class A(val x: Int)", "@Suppress(\"X\") internal fun a() {}", "internal enum class A { X }",
            "internal sealed interface A", "internal object A", "private val a = 1", "internal const val A = 1", "internal fun interface A { fun f() }",
            "internal class A {\n    fun f() {}\n    class N\n    val x = 1\n}",
            "package p\nimport q.R\n// class A\n/* fun a() {} */\ninternal class B",
        )
        right.forEach { sample ->
            assertEquals("android_overlay: control: '$sample' must not be reported", emptyList<String>(), surfaceProblems(oneFile(sample)))
        }
        assertEquals("android_overlay: control: a file with no declaration must be reported", 1, surfaceProblems(oneFile("// nothing here\n")).size)
        assertEquals("android_overlay: control: only the public files means nothing was checked", 1, surfaceProblems(mapOf("FloatingTile.kt" to "class A")).size)
        assertEquals("android_overlay: control: the three public files are left alone", emptyList<String>(), surfaceProblems(mapOf("FloatingTile.kt" to "class A", "ShowResult.kt" to "enum class B { X }", "TileState.kt" to "enum class D { X }", "C.kt" to "internal class C")))

        val texts = ModuleFiles.mainTexts()
        assertTrue("android_overlay: the public files were not found among ${texts.keys}", texts.keys.containsAll(publicFiles))
        assertEquals("android_overlay: every top-level declaration outside $publicFiles must be internal", emptyList<String>(), surfaceProblems(texts))
    }
}
