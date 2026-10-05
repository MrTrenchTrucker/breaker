package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * What every gate reads, and the two checks that keep the set of gates itself
 * honest.
 *
 * The gates in this package are scans: they read files and compare them with
 * rules. A scan needs three things to be worth anything, and each of them is a
 * way for a scan to be quietly worthless. It needs files to look at, so an empty
 * result is a failure rather than a pass. It needs to find the module the same
 * way whatever directory the test was started in, so the answer does not depend
 * on the machine. And it needs a control, a piece of text the rule must catch,
 * which each gate file carries for its own rule.
 *
 * This file holds the first two, once, and checks that the gate list has not lost
 * a member: five rules, five files, each of them a test class of its own.
 */

/** The marker that identifies this module: the package's own source directory. */
private const val MODULE_MARKER = "src/main/kotlin/dev/breaker/dictation/ui"

/** The compiled classes of the renderer, whose class file exists in every variant. */
private const val KNOWN_CLASS = "ScreenRenderer.class"

/**
 * This module's own package directory, as the path a compiled class carries it.
 *
 * Every variant root starts here, so a class path is trimmed of it before a carve-out
 * name is compared with it.
 */
private const val PACKAGE_PATH = "dev/breaker/dictation/ui"

/** The names of the class files a gate test is expected to find. */
private val GATE_FILES = listOf(
    "AndroidConfinementGateTest.kt",
    "NoColourGateTest.kt",
    "NoPrivateSpacingGateTest.kt",
    "PublicSurfaceGateTest.kt",
    "PersistenceGateTest.kt",
)

/**
 * The unit test module directory, found by walking up from wherever the test was
 * started.
 *
 * The working directory of a unit test is the module, but a test that relies on
 * that alone passes on one machine only. At every level both the module itself and
 * the `android/ui` module inside a checkout are tried, so the same test gives the
 * same answer whether it was started in the module or at the top of the tree.
 */
internal val MODULE_DIR: File = findModuleDir()

/** The main source tree of this module, by path relative to the package. */
internal val MAIN_SOURCES: List<Pair<String, String>> = sourcesUnder("main")

/** The test source tree of this module, by path relative to the package. */
internal val TEST_SOURCES: List<Pair<String, String>> = sourcesUnder("test")

/**
 * The compiled classes of whichever variant was built.
 *
 * A gate must work on a build of one variant, so the variants are taken one at a
 * time and whichever is there is used. Both are named, neither is required, and a
 * run with neither is a failure: a gate that passes without reading a class file
 * is a gate that has checked nothing.
 */
internal val CLASS_ROOTS: List<File> = builtVariantRoots()

/** A main source file by its path relative to the package, with its text. */
internal fun mainSourceOf(relative: String): String {
    val file = File(MODULE_DIR, "src/main/kotlin/dev/breaker/dictation/ui/$relative")
    assertTrue("the source was not found at ${file.absolutePath}", file.isFile)
    return file.readText()
}

/**
 * The class files under a variant root, each with its path relative to this module's
 * own package directory, and its bytes mapped to characters.
 *
 * The pair is (path, text): the gates need the name to decide which carve-out a class
 * falls under, and the bytes to look for framework references inside it.
 *
 * Package-relative, not root-relative, because a carve-out is written in terms of the
 * module's own layout: `render/ScreenRenderer.class` names the same class whichever
 * package root the build happened to emit it under. Comparing a root-relative path
 * against such a name never matches, so a carved-out class is reported as a breach and
 * a gate that cannot pass is a gate nobody trusts.
 */
internal fun classFilesOf(root: File): List<Pair<String, String>> =
    root.walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .map { file ->
            val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
            relative.removePrefix("$PACKAGE_PATH/") to String(file.readBytes(), Charsets.ISO_8859_1)
        }
        .toList()

/** The module directory, or a failure naming where the search gave up. */
private fun findModuleDir(): File {
    var level: File? = File("").absoluteFile
    while (level != null) {
        if (File(level, MODULE_MARKER).isDirectory) return level
        val nested = File(level, "android/ui/$MODULE_MARKER")
        if (nested.isDirectory) return File(level, "android/ui")
        level = level.parentFile
    }
    error("the ui module was not found at or above ${File("").absolutePath}")
}

/** The Kotlin sources of the given tree, each paired with its package-relative path. */
private fun sourcesUnder(tree: String): List<Pair<String, String>> {
    val root = File(MODULE_DIR, "src/$tree/kotlin/dev/breaker/dictation/ui")
    assertTrue("the $tree source tree was not found at ${root.absolutePath}", root.isDirectory)
    val found = root.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { file ->
            file.relativeTo(root).path.replace(File.separatorChar, '/') to file.readText()
        }
        .toList()
    assertTrue("the $tree source tree holds no Kotlin source", found.isNotEmpty())
    return found
}

/** The variant roots that hold a compiled class, whichever variants were built. */
private fun builtVariantRoots(): List<File> {
    // The built-in Kotlin toolchain writes a variant's classes under
    // build/intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes;
    // the legacy build/tmp/kotlin-classes/<variant> layout no longer exists, so the
    // roots are taken from the intermediates layout and named after the variant that
    // owns them.
    val base = File(MODULE_DIR, "build/intermediates/built_in_kotlinc")
    val roots = listOf("debug", "release")
        .map { variant ->
            File(base, "$variant/compile${variant.replaceFirstChar { it.uppercase() }}Kotlin/classes")
        }
        .filter { root -> root.isDirectory && root.walkTopDown().any { it.name == KNOWN_CLASS } }
    assertTrue(
        "no compiled class was found under ${base.absolutePath}; the gates need a build to read",
        roots.isNotEmpty(),
    )
    return roots
}

/**
 * The gate files and the shape of the gate set.
 *
 * A gate dropped by a later change would leave the rest of this package green,
 * so the list is checked against the directory rather than against a constant
 * that a change could edit at the same time.
 */
class GateSelfTest {
    @Test
    fun `every gate named by the plan is present as a file of its own`() {
        val present = File(MODULE_DIR, "src/test/kotlin/dev/breaker/dictation/ui/gate")
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith("Test.kt") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        for (gate in GATE_FILES) {
            assertTrue("the gate file $gate is missing from ${present}", gate in present)
        }
        assertEquals(
            "the gate package holds files beyond the five gates and this one: $present",
            (GATE_FILES + "GateSelfTest.kt").sorted(),
            present,
        )
    }

    @Test
    fun `every gate file reads as a test class and stays inside the size cap`() {
        val root = File(MODULE_DIR, "src/test/kotlin/dev/breaker/dictation/ui/gate")
        for (gate in GATE_FILES) {
            val file = File(root, gate)
            assertTrue("the gate file $gate is missing", file.isFile)
            val lines = file.readLines()
            assertTrue(
                "$gate is ${lines.size} lines, over the 500 line ceiling",
                lines.size <= 500,
            )
            assertTrue("$gate is under 300 lines, as the gate plan asks", lines.size < 300)
            assertTrue(
                "$gate declares no class, so it runs no tests",
                lines.any { it.trimStart().startsWith("class ") },
            )
        }
    }

    @Test
    fun `the shared helpers find files, so a scan over them reads something`() {
        assertTrue("the main tree holds fewer sources than the module it describes", MAIN_SOURCES.size >= 10)
        assertTrue("the test tree holds fewer sources than the main tree", TEST_SOURCES.size >= MAIN_SOURCES.size)
        assertTrue(
            "the screen model was not found among the main sources",
            MAIN_SOURCES.any { (path, _) -> path == "screen/Screen.kt" },
        )
    }

    @Test
    fun `a class path is named from the package, so a carve-out name can match it`() {
        // The carve-out names are written package-relative, so a path that kept the
        // build's own package root could never equal one, and every carved-out class
        // would be reported as a breach.
        val paths = CLASS_ROOTS.flatMap { root -> classFilesOf(root).map { it.first } }
        assertTrue("no class file was read", paths.isNotEmpty())
        assertTrue(
            "a class path still carries the build's package root: ${paths.take(5)}",
            paths.none { it.startsWith("$PACKAGE_PATH/") },
        )
        assertTrue(
            "the renderer class was not named package-relative: ${paths.filter { it.endsWith("ScreenRenderer.class") }}",
            paths.contains("render/ScreenRenderer.class"),
        )
    }

    @Test
    fun `a comment and a string are removed, and code is kept`() {
        val control = """
            package dev.breaker.dictation.ui.theme
            // android.util.Log is named here in prose
            /* and android.view.View in a block comment */
            val note = "android.graphics.Color"
            val kept = 48
        """.trimIndent()
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("a comment survived the strip: $stripped", !stripped.contains("Log"))
        assertTrue("a string literal survived the strip: $stripped", !stripped.contains("Color"))
        assertTrue("code was stripped away: $stripped", stripped.contains("val kept = 48"))
    }

    @Test
    fun `an interpolation hole survives the strip and the text around it does not`() {
        // A hole is code inside a literal, so blanking it with the rest of the
        // literal hides a reference from every rule that reads references.
        val control = "val line = \"Key: ${'$'}{settings.apiKeyRef}\"\n"
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("the hole was blanked with the literal: $stripped", stripped.contains("apiKeyRef"))
        assertTrue("the literal text was kept: $stripped", !stripped.contains("Key: "))
        assertEquals(
            "a raw literal is blanked differently from a quoted one",
            stripped,
            withoutCommentsAndStrings("val line = \"\"\"Key: ${'$'}{settings.apiKeyRef}\"\"\"\n"),
        )
        assertTrue(
            "a string with no hole was not blanked: $stripped",
            !withoutCommentsAndStrings("val note = \"apiKeyRef\"\n").contains("apiKeyRef"),
        )
    }

    @Test
    fun `a quoted quote or a doubled slash does not end the scan early`() {
        val control = "val a = \"// not a comment\"\nval b = 2\n"
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("the line after the literal was lost: $stripped", stripped.contains("val b = 2"))
    }

    @Test
    fun `a hex colour after a literal holding a url is still read`() {
        // The same line, not the next one, is where a `//` inside a literal hides
        // code, and a settings screen carrying a help URL is not an exotic shape.
        val control = "val note = \"https://x\"; val shade = 0xFF1E7A46.toInt()\n"
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("the hex colour after the literal was lost: $stripped", stripped.contains("0xFF1E7A46"))
    }

    @Test
    fun `a reference to a secret after a literal holding a url is still read`() {
        val control = "val note = \"see http://x\"; val ref = settings.apiKeyRef\n"
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("the reference after the literal was lost: $stripped", stripped.contains("apiKeyRef"))
    }

    /**
     * A hole is closed by counting braces, so a nested open brace has to be
     * counted or the hole ends early and everything after it is read as literal
     * text. The reference is named after that brace precisely so the counting is
     * what is being checked: a lambda taking no parameters and returning a
     * constant is ordinary code, and a hole that ends inside one hides whatever
     * the expression names.
     */
    @Test
    fun `a reference named after a nested brace in a hole is still read`() {
        val control = "val ref = \"note: ${'$'}{run({ 1 }.apiKeyRef)}\"\n"
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("the hole closed inside the nested brace: $stripped", stripped.contains("apiKeyRef"))
    }

    @Test
    fun `a framework call after a literal holding a url is still read`() {
        val control = "val u = \"http://x\"; val v = android.util.Log.d(\"\", \"\")\n"
        val stripped = withoutCommentsAndStrings(control)
        assertTrue("the framework reference after the literal was lost: $stripped", stripped.contains("android."))
    }
}
