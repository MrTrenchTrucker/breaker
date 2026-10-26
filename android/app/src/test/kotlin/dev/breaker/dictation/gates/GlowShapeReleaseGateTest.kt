package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glow shape selector is debug-only: the overlay declares it, the debug source set is the one
 * place that may set it, and no release source may. Each rule reads the real files through
 * [AppSourceFiles.strip], so a comment or a string containing the text is not counted, and each rule
 * is proven able to fire by a plant sample written beside it.
 */
class GlowShapeReleaseGateTest {

    private val assignment: Regex = Regex("""\bGlowShapes\.screen\s*=(?!=)""")

    private val overlayPath: String = "android/modules/overlay/src/main/kotlin/dev/breaker/dictation/overlay/GlowShapeSwitch.kt"

    private val overlayDeclaration: Regex = Regex("""\bvar\s+screen\s*:\s*GlowShape\s*=\s*GlowShape\.V1""")

    private val debugSwitchPath: String = "kotlin/dev/breaker/dictation/probe/GlowShapeSwitch.kt"

    private val releaseProblem: String = "app: a release source assigns GlowShapes.screen"

    private val declarationProblem: String = "app: the overlay selector must be a var, not a val"

    private val debugProblem: String = "app: the debug source set must hold exactly one GlowShapes.screen assignment"

    /** The repository root: the nearest folder at or above the app module that holds the root settings script. */
    private fun repoRoot(): File =
        generateSequence(AppSourceFiles.moduleRoot) { it.parentFile }.firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("app: no folder with settings.gradle.kts at or above ${AppSourceFiles.moduleRoot}")

    /** The text of every Kotlin file in the main source set of the app and of each module, by path below the repository root. */
    private fun releaseSources(): Map<String, String> {
        val repo: File = repoRoot()
        val found: MutableMap<String, String> = LinkedHashMap()
        for (root in listOf("android/app/src/main", "android/modules")) {
            val start = File(repo, root)
            check(start.isDirectory) { "app: no source folder at $start" }
            val files = start.walkTopDown().onEnter { it.name != "build" }.filter { it.isFile && it.extension == "kt" }
            for (file in files.sortedBy { it.path }) {
                val path: String = file.relativeTo(repo).path.replace(File.separatorChar, '/')
                if (path.contains("/src/main/")) {
                    found[path] = file.readText(Charsets.UTF_8)
                }
            }
        }
        return found
    }

    /** One line per file of [files] whose code assigns GlowShapes.screen. */
    private fun assignmentHits(files: Map<String, String>): List<String> =
        files.filter { assignment.containsMatchIn(AppSourceFiles.strip(it.value).code) }.keys.map { "$it assigns GlowShapes.screen" }

    /** One problem unless the overlay's selector declaration is the mutable `var`. */
    private fun declarationProblems(text: String): List<String> =
        if (overlayDeclaration.containsMatchIn(AppSourceFiles.strip(text).code)) emptyList() else listOf(declarationProblem)

    /** Every Kotlin file under the app's debug source folder, by path below it, with forward slashes. */
    private fun debugFiles(): Map<String, String> {
        val root = File(AppSourceFiles.moduleRoot, "src/debug")
        check(root.isDirectory) { "app: no debug source folder at $root" }
        val result: MutableMap<String, String> = LinkedHashMap()
        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }) {
            result[file.relativeTo(root).path.replace(File.separatorChar, '/')] = file.readText(Charsets.UTF_8)
        }
        return result
    }

    /** The paths of the debug files whose code assigns GlowShapes.screen. */
    private fun debugAssignments(files: Map<String, String>): List<String> =
        files.filter { assignment.containsMatchIn(AppSourceFiles.strip(it.value).code) }.keys.sorted()

    /** How many assignments to GlowShapes.screen [files] hold, counting every match, not every file. */
    private fun debugAssignmentCount(files: Map<String, String>): Int =
        files.values.sumOf { assignment.findAll(AppSourceFiles.strip(it).code).count() }

    /** One problem unless [files] hold exactly one GlowShapes.screen assignment. */
    private fun debugProblems(files: Map<String, String>): List<String> =
        if (debugAssignmentCount(files) == 1) emptyList() else listOf(debugProblem)

    @Test
    fun `the release sources never assign the selector`() {
        val sources: Map<String, String> = releaseSources()
        assertTrue("app: the scan did not read the overlay selector", sources.containsKey(overlayPath))
        assertTrue("app: the scan read no app main file", sources.keys.any { it.startsWith("android/app/src/main/") })
        assertEquals(releaseProblem, emptyList<String>(), assignmentHits(sources))

        // A comment and a string naming the assignment must not be counted as code.
        val quiet = mapOf(
            "overlay/Plant.kt" to "object A {\n    // GlowShapes.screen = GlowShape.V2\n    val s = \"GlowShapes.screen = GlowShape.V2\"\n}",
        )
        assertEquals("app: the strip did not blank a comment and a string", emptyList<String>(), assignmentHits(quiet))

        val withAssignment = sources[overlayPath] ?: error("app: the overlay selector is missing")
        val planted = sources + (overlayPath to "$withAssignment\nfun plant() { GlowShapes.screen = GlowShape.V2 }\n")
        assertEquals("app: a planted assignment in the overlay must be reported", listOf("$overlayPath assigns GlowShapes.screen"), assignmentHits(planted))
    }

    @Test
    fun `the overlay's own declaration is the mutable selector`() {
        val overlay = releaseSources()[overlayPath] ?: error("app: the overlay selector is missing")
        assertEquals(declarationProblem, emptyList<String>(), declarationProblems(overlay))

        val planted = overlay.replace("var screen: GlowShape = GlowShape.V1", "val screen: GlowShape = GlowShape.V1")
        assertTrue("app: the plant's old text is missing from the overlay", planted != overlay)
        assertEquals(declarationProblem, listOf(declarationProblem), declarationProblems(planted))
    }

    @Test
    fun `the debug source set holds exactly one assignment`() {
        val debug: Map<String, String> = debugFiles()
        assertTrue("app: the debug scan did not read the debug switch", debug.containsKey(debugSwitchPath))
        assertEquals(debugProblem, listOf(debugSwitchPath), debugAssignments(debug))
        assertEquals("app: the debug source set must hold exactly one assignment", emptyList<String>(), debugProblems(debug))

        val switch = debug[debugSwitchPath] ?: error("app: the debug switch is missing")
        val twoAssignments = debug + (debugSwitchPath to "$switch\nfun plant() { GlowShapes.screen = GlowShape.V3 }\n")
        assertEquals("app: a planted second assignment must be reported", listOf(debugProblem), debugProblems(twoAssignments))
        assertEquals("app: a planted second assignment in a second file must be reported", listOf(debugProblem), debugProblems(debug + ("kotlin/dev/breaker/dictation/probe/Plant.kt" to "fun p() { GlowShapes.screen = GlowShape.V2 }")))

        val removed = debug + (debugSwitchPath to switch.replace("GlowShapes.screen = shape", "shape"))
        assertTrue("app: the plant's old text is missing from the debug switch", removed[debugSwitchPath] != switch)
        assertEquals("app: removing the one assignment must be reported", listOf(debugProblem), debugProblems(removed))
    }
}
