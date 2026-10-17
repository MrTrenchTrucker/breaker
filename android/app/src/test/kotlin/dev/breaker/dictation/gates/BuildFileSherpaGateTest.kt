package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the app's Gradle build file brings in the speech engine release file.
 *
 * The on-device module only compiles against that file; the app is what packages it, so a build
 * file that lost the dependency, took it from another place, named another version or skipped the
 * hash check would still assemble and then be unable to transcribe, or would ship an unchecked
 * file. The rules read the code of the file (comments and string text blanked, literals kept in
 * order). They pin: the coordinate is built once from the group and the `@aar` ending around the
 * catalog's engine version and is used once, by the run-time only dependency; the hash check task
 * is named by one path value; the pre-build step depends on it; and the by-name hook that makes
 * every compile, lint, test, bundle, extract, merge, package, check, assemble and build step
 * depend on it exists; those two are the only places the file says `dependsOn`. The dependency
 * line itself is pinned by BuildFileGateTest.
 */
internal class BuildFileSherpaGateTest {

    private val q: String = "\"\""
    private val groupText: String = "external.github.k2-fsa:sherpa-onnx:"
    private val endingText: String = "@aar"
    private val taskPath: String = ":android:modules:stt-ondevice:verifySherpaAar"
    private val namePattern: String = "(compile|lint|test|bundle|extract|merge|package|check|assemble|build).*"

    private val coordinateDef: Regex = Regex(
        "\\bval\\s+sherpaCoordinate\\s*=\\s*" + q + "\\s*\\+\\s*libs\\s*\\.\\s*versions\\s*\\.\\s*sherpa\\s*\\.\\s*onnx\\s*\\.\\s*get\\s*\\(\\s*\\)\\s*\\+\\s*" + q,
    )
    private val pathDef: Regex = Regex("\\bval\\s+verifySherpaAarPath\\s*=\\s*" + q)
    private val preBuildHook: Regex = Regex(
        "\\btasks\\s*\\.\\s*named\\s*\\(\\s*" + q + "\\s*\\)\\s*\\{\\s*dependsOn\\s*\\(\\s*verifySherpaAarPath\\s*\\)\\s*\\}",
    )
    private val nameHook: Regex = Regex(
        "\\btasks\\s*\\.\\s*configureEach\\s*\\{\\s*if\\s*\\(\\s*Regex\\s*\\(\\s*" + q +
            "\\s*\\)\\s*\\.\\s*matches\\s*\\(\\s*name\\s*\\)\\s*\\)\\s*\\{\\s*dependsOn\\s*\\(\\s*verifySherpaAarPath\\s*\\)\\s*\\}\\s*\\}",
    )
    private val anyDependsOn: Regex = Regex("\\bdependsOn\\s*\\(([^)]*)\\)")

    private fun buildFileText(): String {
        val script = File(AppSourceFiles.moduleRoot, "build.gradle.kts")
        check(script.isFile) { "app: build.gradle.kts is missing under ${AppSourceFiles.moduleRoot}" }
        return script.readText(Charsets.UTF_8)
    }

    /** How many string literals start in [code] before [offset]; the scanner leaves one pair of quotes per literal. */
    private fun literalIndex(code: String, offset: Int): Int = Regex(q).findAll(code.substring(0, offset)).count()

    /** The literals of the single match of [rule] in [code], or null when it is not there exactly once. */
    private fun literalsOf(stripped: Stripped, rule: Regex, count: Int): List<String>? {
        val hits = rule.findAll(stripped.code).toList()
        if (hits.size != 1) return null
        val first = literalIndex(stripped.code, hits[0].range.first)
        return stripped.literals.subList(first, first + count)
    }

    /** One line per difference between the build file text and the pinned wiring; none means it matches. */
    private fun problems(text: String): List<String> {
        val stripped: Stripped = AppSourceFiles.strip(text)
        val code: String = stripped.code
        val found: MutableList<String> = ArrayList()
        val coordinate = literalsOf(stripped, coordinateDef, 2)
        if (coordinate != listOf(groupText, endingText)) {
            found.add("sherpaCoordinate is not built once as \"$groupText\" + the catalog engine version + \"$endingText\": $coordinate")
        }
        val uses = Regex("\\bsherpaCoordinate\\b").findAll(code).count()
        if (uses != 2) found.add("sherpaCoordinate is named $uses times, expected 2 (its definition and the dependency)")
        val path = literalsOf(stripped, pathDef, 1)
        if (path != listOf(taskPath)) found.add("verifySherpaAarPath is not defined once as \"$taskPath\": $path")
        val named = literalsOf(stripped, preBuildHook, 1)
        if (named != listOf("preBuild")) found.add("preBuild does not depend on the hash check exactly once: $named")
        val pattern = literalsOf(stripped, nameHook, 1)
        if (pattern != listOf(namePattern)) found.add("the by-name hook is not exactly once over \"$namePattern\": $pattern")
        val args = anyDependsOn.findAll(code).map { it.groupValues[1].trim() }.toList()
        if (args != listOf("verifySherpaAarPath", "verifySherpaAarPath")) found.add("dependsOn is used with $args, expected two uses of verifySherpaAarPath")
        return found
    }

    private val hookLine = "tasks.named(\"preBuild\") { dependsOn(verifySherpaAarPath) }"
    private val inner = "        dependsOn(verifySherpaAarPath)\n"
    private val versionRef = "libs.versions.sherpa.onnx.get()"

    private val firing: List<XmlSample> = listOf(
        XmlSample("coordinate group changed", "\"$groupText\"", "\"org.example:sherpa-onnx:\"", "sherpaCoordinate is not built once"),
        XmlSample("coordinate ending changed", "\"$endingText\"", "\"@jar\"", "sherpaCoordinate is not built once"),
        XmlSample("version typed in", versionRef, "\"1.13.8\"", "sherpaCoordinate is not built once"),
        XmlSample("version from another entry", versionRef, "libs.versions.minSdk.get()", "sherpaCoordinate is not built once"),
        XmlSample("coordinate removed", "val sherpaCoordinate = \"$groupText\" + $versionRef + \"$endingText\"\n", "", "sherpaCoordinate is not built once", "named 1 times"),
        XmlSample("dependency names another value", "runtimeOnly(sherpaCoordinate)", "runtimeOnly(otherCoordinate)", "named 1 times"),
        XmlSample("coordinate used twice", "runtimeOnly(sherpaCoordinate)", "runtimeOnly(sherpaCoordinate)\n    testImplementation(sherpaCoordinate)", "named 3 times"),
        XmlSample("check task path changed", "\"$taskPath\"", "\":android:modules:stt-ondevice:otherTask\"", "verifySherpaAarPath is not defined"),
        XmlSample("check task path removed", "val verifySherpaAarPath = \"$taskPath\"\n", "", "verifySherpaAarPath is not defined"),
        XmlSample("pre-build hook removed", "$hookLine\n", "", "preBuild does not depend", "dependsOn is used with"),
        XmlSample("pre-build hook on another task", "tasks.named(\"preBuild\")", "tasks.named(\"clean\")", "preBuild does not depend"),
        XmlSample("pre-build hook only orders", hookLine, "tasks.named(\"preBuild\") { mustRunAfter(verifySherpaAarPath) }", "preBuild does not depend", "dependsOn is used with"),
        XmlSample("pre-build hook adds a task", hookLine, "tasks.named(\"preBuild\") { dependsOn(verifySherpaAarPath, \"x\") }", "preBuild does not depend", "dependsOn is used with"),
        XmlSample("by-name hook loses its dependency", inner, "", "the by-name hook is not exactly once", "dependsOn is used with"),
        XmlSample("by-name hook narrowed", "\"$namePattern\"", "\"(compile).*\"", "the by-name hook is not exactly once"),
        XmlSample("by-name hook removed", "tasks.configureEach {", "tasks.whenTaskAdded {", "the by-name hook is not exactly once"),
    )

    private val quiet: List<XmlSample> = listOf(
        XmlSample("a comment that names other values", "// The speech engine library", "// val sherpaCoordinate = \"x\" dependsOn(other) tasks.named(\"clean\")\n// The speech engine library"),
        XmlSample("spacing in the definition", "val sherpaCoordinate = \"$groupText\" + $versionRef", "val sherpaCoordinate =\n    \"$groupText\" +\n    libs.versions.sherpa.onnx.get() "),
        XmlSample("spacing in the hook", hookLine, "tasks.named( \"preBuild\" ) {\n    dependsOn( verifySherpaAarPath )\n}"),
        XmlSample("spacing at the dependency", "runtimeOnly(sherpaCoordinate)", "runtimeOnly( sherpaCoordinate )  // the engine"),
    )

    @Test
    fun `the real build file wires the speech engine file in`() {
        val text: String = buildFileText()
        assertTrue("app: build.gradle.kts is empty", text.isNotBlank())
        val found: List<String> = problems(text)
        assertEquals("app: the build file differs from the pinned speech engine wiring: $found", emptyList<String>(), found)
    }

    @Test
    fun `a build file with another coordinate, a lost check or a narrowed hook is rejected with what is wrong`() {
        val real: String = buildFileText()
        for (sample in firing) {
            val found: List<String> = problems(XmlSamples.apply("build file", real, sample))
            assertTrue("app: the speech engine gate accepted ${sample.label}", found.isNotEmpty())
            for (fragment in sample.fragments) {
                assertTrue("app: the speech engine gate did not say \"$fragment\" for ${sample.label}, it said $found", found.any { it.contains(fragment) })
            }
        }
    }

    @Test
    fun `comments and spacing do not change the verdict`() {
        val real: String = buildFileText()
        for (sample in quiet) {
            val found: List<String> = problems(XmlSamples.apply("build file", real, sample))
            assertEquals("app: the speech engine gate flagged ${sample.label}", emptyList<String>(), found)
        }
    }

    @Test
    fun `every rule is fired by at least one sample`() {
        val rules: List<String> = listOf(
            "sherpaCoordinate is not built once", "named 1 times", "named 3 times", "verifySherpaAarPath is not defined",
            "preBuild does not depend", "the by-name hook is not exactly once", "dependsOn is used with",
        )
        assertEquals("app: the number of firing samples changed", 16, firing.size)
        for (rule in rules) {
            assertTrue("app: no firing sample names the rule \"$rule\"", firing.any { sample -> sample.fragments.any { it.contains(rule) } })
        }
    }
}
