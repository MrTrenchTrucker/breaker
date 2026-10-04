package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Paths

/*
 * The framework stays on one side of a line.
 *
 * Everything that describes a screen, holds the theme choice and writes a setting
 * is plain Kotlin, so the framework may be named in the package that turns a
 * described tree into views and in the single function the app calls. A reference
 * anywhere else is either a mistake or the first step of a dependency the module
 * was built not to have, and on the JVM it is also the first step of a class that
 * cannot be loaded without a device.
 *
 * The rule is read twice over: once in the module's text, where a fully qualified
 * name counts as much as an import, and once in the classes the build produces,
 * where a reference survives even if it is written in a way no import would
 * suggest.
 */

/** The renderer package, which may name the framework. */
private const val RENDER_DIRECTORY = "render"

/** The one file whose job is to hand a view to the app, and may name the framework. */
private const val ENTRY_FILE = "SettingsEntry.kt"

/** The compiled form of [ENTRY_FILE]. */
private const val ENTRY_CLASS = "SettingsEntryKt.class"

/** The renderer class, which names the framework and so proves its carve-out is real. */
private const val RENDERER_CLASS = "render/ScreenRenderer.class"

/**
 * A framework package named as a whole word.
 *
 * The word boundary keeps a name that merely ends in `android` out of the count,
 * and the dot keeps a package whose own name contains the word out.
 */
private val FRAMEWORK_PACKAGE = Regex("""\bandroidx?\.""")

/**
 * Whether a source file is allowed to name the framework.
 *
 * The carve-out is one directory and one file name, not a pattern on the path, so
 * a second file that reaches for the framework is caught rather than excused by
 * sharing a prefix with one that may. The path is normalised before the directory
 * is read off it, because a name that only reaches the renderer by climbing back
 * out of it is not inside the renderer, and a name that merely contains the
 * directory is not in it either.
 */
private fun mayNameFramework(relative: String): Boolean =
    mayName(relative, RENDER_DIRECTORY, ENTRY_FILE)

/**
 * Whether [relative] is the single file [entryFile], or a file inside [directory].
 *
 * The directory is compared as one whole path segment rather than as a prefix of
 * the text, so `renderer/Control.kt` is not `render` and `render/../theme/Control.kt`
 * is not `render` once the `..` is resolved.
 */
private fun mayName(relative: String, directory: String, entryFile: String): Boolean {
    val path = Paths.get(relative).normalize()
    return (path.nameCount > 0 && path.getName(0).toString() == directory) || relative == entryFile
}

/** The framework packages named in the code of [text]. */
private fun frameworkNamesIn(text: String): List<String> =
    FRAMEWORK_PACKAGE.findAll(withoutCommentsAndStrings(text)).map { it.value }.distinct().toList()

/** The framework packages named in [text], unless [relative] may name them. */
private fun forbiddenNamesIn(relative: String, text: String): List<String> =
    if (mayNameFramework(relative)) emptyList() else frameworkNamesIn(text)

/**
 * The framework packages named in a compiled class, read as bytes.
 *
 * A class file is not text, so it is read as bytes mapped one to one onto
 * characters; decoding it as text would replace every byte above the ASCII range
 * with a question mark and lose the constant pool. The two package names are then
 * looked for as plain substrings, which is safe here because this module's own
 * package is `dev.breaker.dictation.ui` and contains neither.
 */
private fun frameworkPackagesIn(bytes: String): List<String> =
    listOf("android/", "androidx/").filter { bytes.contains(it) }

/** The framework packages in a class that may not name them, or nothing. */
private fun forbiddenPackagesIn(relative: String, bytes: String): List<String> =
    if (mayName(relative, RENDER_DIRECTORY, ENTRY_CLASS)) {
        emptyList()
    } else {
        frameworkPackagesIn(bytes)
    }

/**
 * Every framework reference the trees keep out of themselves.
 *
 * The file lists are checked before the scan, so a run that found no source to
 * read fails here instead of reporting an empty result as a pass.
 */
class AndroidConfinementGateTest {
    @Test
    fun `the main tree names the framework only in the renderer and the entry`() {
        val found = MAIN_SOURCES.flatMap { (path, text) -> forbiddenNamesIn(path, text) }
        assertEquals("framework references outside $RENDER_DIRECTORY/ and $ENTRY_FILE", emptyList<String>(), found)
    }

    @Test
    fun `the test tree names the framework nowhere`() {
        val found = TEST_SOURCES.flatMap { (path, text) -> frameworkNamesIn(text) }
        assertEquals("framework references in the test tree: $found", emptyList<String>(), found)
    }

    @Test
    fun `an import of a framework class is caught`() {
        val control = "package dev.breaker.dictation.ui.theme\n\nimport android.util.Log\n"
        assertEquals(listOf("android."), frameworkNamesIn(control))
    }

    @Test
    fun `a framework class named in full is caught with no import anywhere`() {
        val control = "package dev.breaker.dictation.ui.theme\n\nval shade = android.graphics.Color.RED\n"
        assertEquals(listOf("android."), frameworkNamesIn(control))
    }

    @Test
    fun `a support library reference is caught on the same rule`() {
        val control = "package dev.breaker.dictation.ui.render\n\nimport androidx.core.view.ViewCompat\n"
        assertEquals(listOf("androidx."), frameworkNamesIn(control))
    }

    @Test
    fun `the same reference inside the renderer or the entry is left alone`() {
        val control = "package dev.breaker.dictation.ui.render\n\nimport android.view.View\n"
        assertEquals(emptyList<String>(), forbiddenNamesIn("$RENDER_DIRECTORY/Control.kt", control))
        assertEquals(emptyList<String>(), forbiddenNamesIn(ENTRY_FILE, control))
        assertEquals(emptyList<String>(), forbiddenNamesIn("$RENDER_DIRECTORY/nested/Deep.kt", control))
    }

    @Test
    fun `the same reference one directory out is caught, whatever the path looks like`() {
        val control = "package dev.breaker.dictation.ui.theme\n\nimport android.view.View\n"
        assertEquals(listOf("android."), forbiddenNamesIn("theme/Control.kt", control))
        assertEquals(
            "a name that merely contains the renderer directory is not inside it",
            listOf("android."),
            forbiddenNamesIn("theme/rendererHelper.kt", control),
        )
        assertEquals(
            "a traversal out of the renderer is not the renderer",
            listOf("android."),
            forbiddenNamesIn("$RENDER_DIRECTORY/../theme/Control.kt", control),
        )
    }

    @Test
    fun `a framework name in a comment or a string is prose or data, not a reference`() {
        val control = """
            package dev.breaker.dictation.ui.theme

            // the renderer imports android.view.View; nothing here does
            val note = "android.util.Log"
        """.trimIndent()
        assertEquals(emptyList<String>(), frameworkNamesIn(control))
    }

    @Test
    fun `the renderer really does name the framework, so its carve-out is not empty`() {
        val renderer = mainSourceOf("$RENDER_DIRECTORY/ScreenRenderer.kt")
        assertTrue(
            "the renderer is expected to import android.view.View and does not",
            renderer.contains("import android.view.View"),
        )
        assertEquals(
            listOf("android."),
            frameworkNamesIn(renderer).distinct().sorted(),
        )
    }

    @Test
    fun `the entry really does name the framework, so its carve-out is not empty`() {
        val entry = mainSourceOf(ENTRY_FILE)
        assertTrue(
            "the entry is expected to name android.content.Context and does not",
            entry.contains("android.content.Context"),
        )
        assertEquals(listOf("android."), frameworkNamesIn(entry))
    }

    @Test
    fun `the compiled classes name the framework only in the renderer and the entry`() {
        val found = CLASS_ROOTS.flatMap { root ->
            val classes = classFilesOf(root)
            assertTrue("no class file was found under ${root.absolutePath}", classes.isNotEmpty())
            classes.flatMap { (path, bytes) -> forbiddenPackagesIn(path, bytes) }
        }
        assertEquals("framework references in the compiled classes", emptyList<String>(), found)
    }

    @Test
    fun `a compiled renderer class names the framework, so its carve-out is not empty`() {
        val renderer = CLASS_ROOTS.flatMap { root ->
            classFilesOf(root).filter { (path, _) -> path == RENDERER_CLASS }
        }
        assertTrue("the renderer class was not found in any variant built", renderer.isNotEmpty())
        assertTrue(
            "the renderer class was expected to hold android/view/ and does not",
            frameworkPackagesIn(renderer.first().second).contains("android/"),
        )
    }

    @Test
    fun `a framework package in a class file is caught`() {
        val control = " android/view/View "
        assertEquals(listOf("android/"), frameworkPackagesIn(control))
    }

    @Test
    fun `a class of this module's own package is not mistaken for a framework class`() {
        val control = "dev/breaker/dictation/ui/render/ScreenRenderer"
        assertEquals(emptyList<String>(), frameworkPackagesIn(control))
    }

    @Test
    fun `a class outside the render package is caught when it names the framework`() {
        assertEquals(listOf("android/"), forbiddenPackagesIn("theme/Themes.class", " android/content/Context "))
        assertEquals(
            emptyList<String>(),
            forbiddenPackagesIn("$RENDER_DIRECTORY/ScreenRenderer.class", " android/view/View "),
        )
        assertEquals(emptyList<String>(), forbiddenPackagesIn(ENTRY_CLASS, " android/view/View "))
    }

    @Test
    fun `the variants are read one at a time, so a build of one is enough`() {
        for (root in CLASS_ROOTS) {
            assertTrue("an unexpected variant root was taken: ${root.absolutePath}", root.isDirectory)
        }
        assertEquals(
            "the same variant was taken twice",
            CLASS_ROOTS.size,
            CLASS_ROOTS.map { it.name }.distinct().size,
        )
        assertTrue(
            "the variant roots are named as the build names them",
            CLASS_ROOTS.all { it.name == "debug" || it.name == "release" },
        )
        assertTrue(
            "the search did not start at the module it belongs to",
            CLASS_ROOTS.all { it.parentFile.name == "kotlin-classes" && it.parentFile.parentFile.name == "tmp" },
        )
    }

    @Test
    fun `a control is caught for every carve-out boundary the gate draws`() {
        val control = "import android.view.View\n"
        assertEquals(emptyList<String>(), forbiddenNamesIn("$RENDER_DIRECTORY/Control.kt", control))
        assertEquals(emptyList<String>(), forbiddenNamesIn("$RENDER_DIRECTORY/nested/Deep.kt", control))
        assertEquals(emptyList<String>(), forbiddenNamesIn(ENTRY_FILE, control))
        assertEquals(listOf("android."), forbiddenNamesIn("Control.kt", control))
        // A sibling directory whose name merely contains the renderer is outside it. A `.txt` name
        // would not draw this boundary either way: the scan reads only `.kt` files, so no other
        // extension can ever be a source and the file here has to be one the scan can carry.
        assertEquals(
            "a name that merely contains the renderer directory is not inside it",
            listOf("android."),
            forbiddenNamesIn("renderer/Control.kt", control),
        )
    }

    @Test
    fun `the gate reads the module it belongs to, not a path written down`() {
        for ((path, text) in MAIN_SOURCES) {
            assertTrue("a main source carries no path: $path", path.endsWith(".kt"))
            assertTrue("a main source was read as empty: $path", text.isNotEmpty())
        }
        assertTrue(
            "the sources are not the ones of this module",
            MAIN_SOURCES.any { (path, _) -> path == ENTRY_FILE },
        )
        assertTrue(
            "the variant roots are not under the module directory",
            CLASS_ROOTS.all { root -> root.absolutePath.startsWith(File(MODULE_DIR, "build").absolutePath) },
        )
    }
}
