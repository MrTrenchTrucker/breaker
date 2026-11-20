package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the app build file decides that a release artifact was requested BY NAME, and what the
 * unset arm does about it: one configuration-time Boolean read from the requested task names, the
 * exact last-segment comparison, the one fixed sentence for that case, and a check task that reads
 * neither gradle nor the requested names when it runs.
 *
 * The two name lines are pinned on the RAW build file text, not the stripped code: they are string
 * literals, and the stripper empties them, so a stripped read would pin nothing. The rules about what
 * the build does read the stripped code, so a comment or a literal cannot change the outcome.
 *
 * This file owns its helpers and its plant lists. It does not share them with UpdateKeysBuildGateTest
 * and it does not add to that file's lists.
 */
internal class UpdateKeysByNameGateTest {

    private val requiredFragment: String = "the release artifact was requested by name"
    private val releaseNameLine: String = "lastSegment == \"assembleRelease\" || lastSegment == \"bundleRelease\""
    private val requiredSentenceLine: String = "val updateKeysRequiredSentence = \"the release artifact was requested by name but no update key file is set: set the Gradle property \$updateKeysProperty or the environment variable \$updateKeysVariable to a file with one base64 public key on a line, or build through the lifecycle task instead\""

    private val requestedBoolean: Regex = Regex("\\bval\\s+releaseArtifactRequested\\s*:\\s*Boolean\\s*=\\s*gradle\\s*\\.\\s*startParameter\\s*\\.\\s*taskNames\\s*\\.\\s*any\\s*\\{")
    private val checkRegister: Regex = Regex("\\bval\\s+checkUpdateKeys\\s+by\\s+tasks\\s*\\.\\s*registering\\s*\\{")
    private val forbiddenInBody: Regex = Regex("\\b(gradle|startParameter|taskNames)\\b")
    private val projectOrLayout: Regex = Regex("\\b(project|layout)\\b")
    private val unsetArm: Regex = Regex("\\bif\\s*\\(\\s*source\\s*==\\s*null\\s*\\)\\s*\\{\\s*if\\s*\\(\\s*releaseArtifactRequested\\s*\\)\\s*\\{\\s*throw\\s+GradleException\\s*\\(\\s*updateKeysRequiredSentence\\s*\\)\\s*\\}\\s*else\\s*\\{\\s*logger\\s*\\.\\s*warn\\s*\\(\\s*updateKeysUnsetWarning\\s*\\)\\s*\\}\\s*\\}\\s*else\\s*\\{")
    private val warnWord: Regex = Regex("\\.\\s*warn\\b")
    private val warnCall: Regex = Regex("\\blogger\\s*\\.\\s*warn\\s*\\(\\s*updateKeysUnsetWarning\\s*\\)")
    private val throwRule: Regex = Regex("\\bif\\s*\\(\\s*text\\s*==\\s*null\\s*\\|\\|\\s*!\\s*good\\s*\\)\\s*throw\\s+GradleException\\s*\\(\\s*updateKeysSentence\\s*\\)")

    /** A planted edit: [old] must occur once in the real file, and [replacement] takes its place. */
    private class ByNamePlant(val label: String, val old: String, val replacement: String, val fragment: String)

    private val byNameFiring: List<ByNamePlant> = listOf(
        ByNamePlant("by-name condition removed", "if (releaseArtifactRequested) {\n                throw GradleException(updateKeysRequiredSentence)\n            } else {\n                logger.warn(updateKeysUnsetWarning)\n            }", "throw GradleException(updateKeysRequiredSentence)", "warn otherwise"),
        ByNamePlant("Boolean stops reading the requested names", "gradle.startParameter.taskNames.any", "listOf<String>().any", "read once"),
        ByNamePlant("by-name match widened to the lifecycle", "lastSegment == \"assembleRelease\" || lastSegment == \"bundleRelease\"", "lastSegment == \"assembleRelease\" || lastSegment == \"bundleRelease\" || lastSegment == \"build\"", "last-segment comparison"),
        ByNamePlant("named-bad-file arm throws the by-name sentence", "if (text == null || !good) throw GradleException(updateKeysSentence)", "if (text == null || !good) throw GradleException(updateKeysRequiredSentence)", "existing sentence"),
        ByNamePlant("by-name throw uses the old sentence", "throw GradleException(updateKeysRequiredSentence)", "throw GradleException(updateKeysSentence)", "warn otherwise"),
        ByNamePlant("check task reads gradle when it runs", "doLast {\n        val source: File? = updateKeysSource", "doLast {\n        val late: List<String> = gradle.startParameter.taskNames\n        val source: File? = updateKeysSource", "execution time"),
    )

    private val byNameQuiet: List<ByNamePlant> = listOf(
        ByNamePlant("spacing in the by-name throw", "if (releaseArtifactRequested) {", "if ( releaseArtifactRequested ) {", ""),
        ByNamePlant("a comment before the by-name Boolean", "val releaseArtifactRequested: Boolean =", "// read once at configuration time\nval releaseArtifactRequested: Boolean =", ""),
    )

    private fun buildText(): String {
        val script = File(AppSourceFiles.moduleRoot, "build.gradle.kts")
        check(script.isFile) { "app: build.gradle.kts is missing under ${AppSourceFiles.moduleRoot}" }
        // Spacing inside parentheses belongs to no rule, so it is collapsed once here: a build file
        // respaced that way still holds every plant's old text exactly once. Newlines are untouched,
        // and the plants are applied after this, so the rules still read the respaced text.
        return script.readText(Charsets.UTF_8)
            .replace(Regex("\\([ \\t]+"), "(")
            .replace(Regex("[ \\t]+\\)"), ")")
    }

    private fun occurrences(text: String, part: String): Int = text.split(part).size - 1

    private fun count(text: String, rule: Regex): Int = rule.findAll(text).count()

    private fun firstAt(text: String, rule: Regex): Int = rule.find(text)?.range?.first ?: -1

    /** The text between the braces that open at the end of the one match of [rule], or null when there is not exactly one match. */
    private fun bodyOf(code: String, rule: Regex): String? {
        val hits: List<MatchResult> = rule.findAll(code).toList()
        if (hits.size != 1) return null
        val open: Int = hits[0].range.last
        var depth = 0
        for (index in open until code.length) {
            if (code[index] == '{') depth += 1
            if (code[index] == '}') depth -= 1
            if (depth == 0) return code.substring(open + 1, index)
        }
        return null
    }

    private fun planted(real: String, plant: ByNamePlant): String {
        val hits: Int = occurrences(real, plant.old)
        check(hits == 1) { "app: the planted edit ${plant.label} needs its old text once, found $hits" }
        return real.replace(plant.old, plant.replacement)
    }

    /** One line per by-name rule the build file breaks; none means the by-name block matches the pinned rules. */
    private fun byNameProblems(text: String): List<String> {
        val code: String = AppSourceFiles.strip(text).code
        val rawLines: List<String> = text.lines().map { it.trim() }
        val found: MutableList<String> = mutableListOf()
        if (count(code, requestedBoolean) != 1) found.add("the by-name Boolean is not read once from the requested task names")
        val booleanAt: Int = firstAt(code, requestedBoolean)
        val registerAt: Int = firstAt(code, checkRegister)
        if (booleanAt < 0 || registerAt < 0 || booleanAt >= registerAt) found.add("the by-name Boolean is not computed at configuration time before the check task is registered")
        val checkBody: String? = bodyOf(code, checkRegister)
        if (checkBody == null) found.add("the check task body is not found once")
        if (checkBody != null && (count(checkBody, forbiddenInBody) != 0 || projectOrLayout.containsMatchIn(checkBody))) found.add("the check task reads gradle or the requested names at execution time")
        if (rawLines.count { it == releaseNameLine } != 1) found.add("the by-name match is not the exact last-segment comparison once")
        if (rawLines.count { it == requiredSentenceLine } != 1) found.add("the by-name sentence is not the fixed line once")
        if (occurrences(text, requiredFragment) != 1) found.add("the by-name sentence fragment is not written once")
        if (count(code, unsetArm) != 1) found.add("the by-name unset arm does not throw by name and warn otherwise")
        if (rawLines.count { it.contains("logger.warn") } != 1 || count(code, warnWord) != 1 || count(code, warnCall) != 1) found.add("the warning is not one logger.warn with the fixed text alone")
        if (count(code, throwRule) != 1) found.add("the named-bad-file arm does not throw the existing sentence")
        return found
    }

    @Test
    fun `the by-name block matches the pinned rules`() {
        val found: List<String> = byNameProblems(buildText())
        assertEquals("app: the by-name block differs from the pinned rules: $found", emptyList<String>(), found)
    }

    @Test
    fun `the by-name rules reject a planted break`() {
        val real: String = buildText()
        for (plant in byNameFiring) {
            val found: List<String> = byNameProblems(planted(real, plant))
            assertTrue("app: the by-name rules accepted ${plant.label}", found.isNotEmpty())
            assertTrue("app: the by-name rules did not say \"${plant.fragment}\" for ${plant.label}, it said $found", found.any { it.contains(plant.fragment) })
        }
    }

    @Test
    fun `comments and spacing do not change the by-name outcome`() {
        val real: String = buildText()
        for (plant in byNameQuiet) {
            assertEquals("app: the by-name rules flagged ${plant.label}", emptyList<String>(), byNameProblems(planted(real, plant)))
        }
    }

    @Test
    fun `the check body pin does not match the exception name`() {
        val code: String = AppSourceFiles.strip(buildText()).code
        val checkBody: String = bodyOf(code, checkRegister) ?: ""
        assertTrue("app: the check task body is empty", checkBody.isNotEmpty())
        assertTrue("app: the check body throws GradleException", checkBody.contains("GradleException"))
        assertEquals("app: the forbidden words matched the exception name: ${forbiddenInBody.findAll(checkBody).map { it.value }.toList()}", emptyList<String>(), forbiddenInBody.findAll(checkBody).map { it.value }.toList())
    }
}