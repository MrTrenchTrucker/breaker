package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the app reaches its text committer, with the parts no JVM test can run: the swap point that
 * returns the real committer, the one place in the app's main sources that builds it, the composition
 * root that takes it as a required parameter, the application object that makes it once from the
 * application context, the build edges to the commit module and to its accessibility adapter, the
 * manifest that does not declare the adapter a second time, the component name the onboarding reads,
 * and the settings script that leaves the input method module out of the build.
 *
 * The gate reads the real files (the Kotlin sources of the app, the app manifest and build script, the
 * adapter's manifest and build script, and the root settings script) with comments and literal text
 * removed by the shared scanner. Each rule holds on the real files, is broken by at least two edited
 * samples, and stays quiet on harmless edits; an edit whose target text is missing fails by name.
 */
internal class AppCommitGateTest {

    private val swaps: String = "android/app/src/main/kotlin/dev/breaker/dictation/wiring/Swaps.kt"
    private val androidSwaps: String = "android/app/src/main/kotlin/dev/breaker/dictation/wiring/AndroidSwaps.kt"
    private val app: String = "android/app/src/main/kotlin/dev/breaker/dictation/BreakerApp.kt"
    private val root: String = "android/app/src/main/kotlin/dev/breaker/dictation/BreakerCompositionRoot.kt"
    private val build: String = "android/app/build.gradle.kts"
    private val manifest: String = "android/app/src/main/AndroidManifest.xml"
    private val adapterBuild: String = "android/modules/commit/accessibility/build.gradle.kts"
    private val adapterManifest: String = "android/modules/commit/accessibility/src/main/AndroidManifest.xml"
    private val settings: String = "settings.gradle.kts"
    private val mainKotlinPrefix: String = "android/app/src/main/kotlin/"
    private val imeModule: String = ":android:modules:commit:ime"

    private val quotes: Regex = Regex("\"\"")
    private val emptyLiteral: String = "\"\""
    private val projectCall: Regex = Regex("""(\w+)\s*\(\s*project\s*\(\s*""\s*\)\s*\)""")
    private val serviceName: Regex = Regex("<service\\b[^>]*?\\bandroid:name\\s*=\\s*\"([^\"]*)\"")
    private val serviceTag: Regex = Regex("<service\\b[^>]*?\\bandroid:name\\s*=\\s*\"[^\"]*BreakerAccessibilityService\"")

    /** One rule: [holds] reads the texts of the files by their paths below the repository root. */
    private class Rule(val name: String, val holds: (Map<String, String>) -> Boolean)

    /** An edit of the file at [path] (old text to new text) that must break the rule named [rule]. */
    private class Sample(val rule: String, val path: String, val old: String, val new: String)

    /** An edit of the file at [path] that leaves every rule as it is. */
    private class Quiet(val rule: String, val path: String, val old: String, val new: String)

    private val rules: List<Rule> = listOf(
        Rule("SWAP_POINT_IS_THE_REAL_COMMITTER") {
            countIn(it, androidSwaps, """\bfun\s+appTextCommitter\s*\(\s*context\s*:\s*Context\s*\)\s*:\s*TextCommitter\s*=\s*CommitServices\s*\.\s*create\s*\(\s*context\s*\)(?!\s*[.(])""") == 1
        },
        Rule("EXACTLY_ONE_CREATE_IN_APP_MAIN") { texts ->
            texts.keys.filter { it.startsWith(mainKotlinPrefix) }
                .map { countIn(texts, it, """\bCommitServices\s*\.\s*create\s*\(""") }
                .sum() == 1
        },
        Rule("ROOT_TAKES_ITS_COMMITTER") {
            countIn(it, root, """\bcommitter\s*:\s*TextCommitter\s*(?=[,)])""") >= 1 &&
                countIn(it, root, """\bcommitter\s*:\s*TextCommitter\s*=""") == 0 &&
                countIn(it, root, """\bcommitter\s*=\s*committer\s*(?=[,)])""") == 1 &&
                countIn(it, root, """\b(?:appTextCommitter|UnavailableTextCommitter)\b""") == 0
        },
        Rule("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT") {
            countIn(it, app, """\bappTextCommitter\s*\(""") == 1 &&
                countIn(it, app, """\bval\s+textCommitter\s*:\s*TextCommitter\s+by\s+lazy\s*\{\s*appTextCommitter\s*\(\s*applicationContext\s*\)\s*\}""") == 1 &&
                countIn(it, app, """(?m)^[ \t]*committer\s*=\s*textCommitter[ \t]*,[ \t]*\n""") == 1
        },
        Rule("BUILD_HAS_THE_COMMIT_EDGES") {
            val edges = projectEdges(text(it, build))
            edges.count { edge -> edge == ("implementation" to ":android:modules:commit") } == 1 &&
                edges.count { edge ->
                    edge.second == ":android:modules:commit:accessibility" &&
                        (edge.first == "implementation" || edge.first == "runtimeOnly")
                } == 1
        },
        Rule("BUILD_HAS_NO_IME_EDGE") { literalsIn(it, build).none { lit -> lit.contains(imeModule) } },
        Rule("SETTINGS_HAS_NO_IME_INCLUDE") { literalsIn(it, settings).none { lit -> lit.contains(imeModule) } },
        Rule("NO_DUPLICATE_SERVICE_IN_APP_MANIFEST") {
            serviceTag.findAll(xmlCode(text(it, manifest))).count() == 0
        },
        Rule("SERVICE_NAME_MATCHES_THE_CONSTANT") { texts ->
            val appId = literalsOf(texts, build, """\bapplicationId\s*=\s*""" + emptyLiteral)
            val namespace = literalsOf(texts, adapterBuild, """\bnamespace\s*=\s*""" + emptyLiteral)
            val constant = literalsOf(texts, swaps, """\bACCESSIBILITY_SERVICE_COMPONENT\s*:\s*String\s*=\s*""" + emptyLiteral)
            val names: List<String> = serviceName.findAll(xmlCode(text(texts, adapterManifest))).map { it.groupValues[1] }.toList()
            appId.size == 1 && namespace.size == 1 && constant.size == 1 && names.size == 1 &&
                names[0].startsWith(".") &&
                "${appId[0]}/${namespace[0]}${names[0]}" == constant[0]
        },
        Rule("NO_SECOND_COMMITTER_SWAP") {
            countIn(it, swaps, """\bfun\s+appTextCommitter\b""") == 0
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("SWAP_POINT_IS_THE_REAL_COMMITTER", androidSwaps, "= CommitServices.create(context)", "= FailingCommitter()"),
        Sample("SWAP_POINT_IS_THE_REAL_COMMITTER", androidSwaps, "= CommitServices.create(context)", "= CommitServices.create(context).also { }"),
        Sample("SWAP_POINT_IS_THE_REAL_COMMITTER", androidSwaps, "= CommitServices.create(context)", "= CommitServices.create(applicationContext)"),
        Sample("SWAP_POINT_IS_THE_REAL_COMMITTER", androidSwaps, "appTextCommitter(context: Context)", "appTextCommitter(context: Context?)"),
        Sample("EXACTLY_ONE_CREATE_IN_APP_MAIN", androidSwaps, "= CommitServices.create(context)", "= FailingCommitter()"),
        Sample("EXACTLY_ONE_CREATE_IN_APP_MAIN", app, "val textCommitter: TextCommitter by lazy", "val spare = CommitServices.create(applicationContext)\n    val textCommitter: TextCommitter by lazy"),
        Sample("ROOT_TAKES_ITS_COMMITTER", root, "private val committer: TextCommitter,", "private val committer: TextCommitter = appTextCommitter(),"),
        Sample("ROOT_TAKES_ITS_COMMITTER", root, "committer = committer,", "committer = appTextCommitter(),"),
        Sample("ROOT_TAKES_ITS_COMMITTER", root, "committer = committer,", "committer = committer, spare = appTextCommitter(),"),
        Sample("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT", app, "appTextCommitter(applicationContext) }", "appTextCommitter(this) }"),
        Sample("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT", app, "by lazy { appTextCommitter(applicationContext) }", "= appTextCommitter(applicationContext)"),
        Sample("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT", app, "committer = textCommitter,", "committer = textCommitter, micSource = appMicSource(),"),
        Sample("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT", app, "val textCommitter: TextCommitter by lazy", "val spare = appTextCommitter(applicationContext)\n    val textCommitter: TextCommitter by lazy"),
        Sample("BUILD_HAS_THE_COMMIT_EDGES", build, "implementation(project(\":android:modules:commit\"))", ""),
        Sample("BUILD_HAS_THE_COMMIT_EDGES", build, "runtimeOnly(project(\":android:modules:commit:accessibility\"))", ""),
        Sample("BUILD_HAS_THE_COMMIT_EDGES", build, "runtimeOnly(project(\":android:modules:commit:accessibility\"))", "api(project(\":android:modules:commit:accessibility\"))"),
        Sample("BUILD_HAS_NO_IME_EDGE", build, "implementation(project(\":android:modules:commit\"))", "implementation(project(\":android:modules:commit\"))\n    implementation(project(\":android:modules:commit:ime\"))"),
        Sample("BUILD_HAS_NO_IME_EDGE", build, "runtimeOnly(project(\":android:modules:commit:accessibility\"))", "runtimeOnly(project(\":android:modules:commit:accessibility\"))\n    runtimeOnly(project(\":android:modules:commit:ime\"))"),
        Sample("SETTINGS_HAS_NO_IME_INCLUDE", settings, "include(\":android:modules:commit:accessibility\")", "include(\":android:modules:commit:accessibility\")\ninclude(\":android:modules:commit:ime\")"),
        Sample("SETTINGS_HAS_NO_IME_INCLUDE", settings, "include(\":android:modules:commit\")", "include(\":android:modules:commit:ime\")"),
        Sample("NO_DUPLICATE_SERVICE_IN_APP_MANIFEST", manifest, "</application>", "    <service android:name=\"dev.breaker.dictation.commit.accessibility.adapter.BreakerAccessibilityService\" />\n    </application>"),
        Sample("NO_DUPLICATE_SERVICE_IN_APP_MANIFEST", manifest, "</application>", "<service android:name=\".x.BreakerAccessibilityService\"/></application>"),
        Sample("SERVICE_NAME_MATCHES_THE_CONSTANT", swaps, "commit.accessibility.adapter.BreakerAccessibilityService\"", "commit.accessibility.BreakerAccessibilityService\""),
        Sample("SERVICE_NAME_MATCHES_THE_CONSTANT", adapterManifest, "android:name=\".adapter.BreakerAccessibilityService\"", "android:name=\".service.BreakerAccessibilityService\""),
        Sample("SERVICE_NAME_MATCHES_THE_CONSTANT", adapterBuild, "namespace = \"dev.breaker.dictation.commit.accessibility\"", "namespace = \"dev.breaker.dictation.commit.access\""),
        Sample("SERVICE_NAME_MATCHES_THE_CONSTANT", build, "applicationId = \"dev.breaker.dictation\"", "applicationId = \"dev.breaker.app\""),
        Sample("NO_SECOND_COMMITTER_SWAP", swaps, "fun appMicSource(): MicSource = UnavailableMicSource()", "fun appMicSource(): MicSource = UnavailableMicSource()\n\nfun appTextCommitter(): TextCommitter = FailingCommitter()"),
        Sample("NO_SECOND_COMMITTER_SWAP", swaps, "fun appMicSource(): MicSource = UnavailableMicSource()", "fun appMicSource(): MicSource = UnavailableMicSource()\n\nfun appTextCommitter(context: Context): TextCommitter = CommitServices.create(context)"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet("SWAP_POINT_IS_THE_REAL_COMMITTER", androidSwaps, "= CommitServices.create(context)", "=\n    CommitServices.create(context)"),
        Quiet("EXACTLY_ONE_CREATE_IN_APP_MAIN", androidSwaps, "The committer that puts dictated text into the focused field,", "The committer that puts dictated text into the focused field (made by CommitServices.create( once),"),
        Quiet("ROOT_TAKES_ITS_COMMITTER", root, "committer = committer,", "committer = committer, // not appTextCommitter() or FailingCommitter()"),
        Quiet("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT", app, "/** The dictated-text committer, built once from the application context. */", "/** The dictated-text committer, built once from the application context; not appTextCommitter(this). */"),
        Quiet("APP_MAKES_IT_ONCE_FROM_APP_CONTEXT", app, "by lazy { appTextCommitter(applicationContext) }", "by lazy {\n        appTextCommitter(applicationContext)\n    }"),
        Quiet("BUILD_HAS_THE_COMMIT_EDGES", build, "implementation(project(\":android:modules:commit\"))", "implementation( project( \":android:modules:commit\" ) ) // runtimeOnly(project(\":x\"))"),
        Quiet("BUILD_HAS_NO_IME_EDGE", build, "implementation(project(\":android:modules:commit\"))", "implementation(project(\":android:modules:commit\")) // project(\":android:modules:commit:ime\")"),
        Quiet("SETTINGS_HAS_NO_IME_INCLUDE", settings, "include(\":android:modules:commit\")", "include(\":android:modules:commit\") // include(\":android:modules:commit:ime\")"),
        Quiet("NO_DUPLICATE_SERVICE_IN_APP_MANIFEST", manifest, "</application>", "<!-- <service android:name=\"x.BreakerAccessibilityService\" /> -->\n    </application>"),
        Quiet("SERVICE_NAME_MATCHES_THE_CONSTANT", swaps, "const val ACCESSIBILITY_SERVICE_COMPONENT: String =", "const val ACCESSIBILITY_SERVICE_COMPONENT: String = // the full name\n   "),
        Quiet("SERVICE_NAME_MATCHES_THE_CONSTANT", adapterBuild, "namespace = \"", "namespace=\""),
        Quiet("SERVICE_NAME_MATCHES_THE_CONSTANT", build, "applicationId = \"dev.breaker.dictation\"", "applicationId=\"dev.breaker.dictation\""),
        Quiet("NO_SECOND_COMMITTER_SWAP", swaps, "The microphone the capture reads.", "The microphone the capture reads, not fun appTextCommitter() here."),
    )

    /** The repository root: the nearest folder at or above the app module that holds the root settings script. */
    private fun repoRoot(): File {
        var folder: File? = AppSourceFiles.moduleRoot
        while (folder != null) {
            if (File(folder, "settings.gradle.kts").isFile) {
                return folder
            }
            folder = folder.parentFile
        }
        error("app: no folder with settings.gradle.kts at or above ${AppSourceFiles.moduleRoot}")
    }

    /** The real text of every file the rules read, keyed by its path below the repository root. */
    private fun realTexts(): Map<String, String> {
        val repo: File = repoRoot()
        val texts: MutableMap<String, String> = LinkedHashMap()
        val sources = File(repo, "android/app/src/main/kotlin")
        check(sources.isDirectory) { "app: no Kotlin source folder at $sources" }
        for (file in sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }) {
            texts[file.relativeTo(repo).path.replace(File.separatorChar, '/')] = file.readText(Charsets.UTF_8)
        }
        for (path in listOf(build, manifest, adapterBuild, adapterManifest, settings)) {
            val file = File(repo, path)
            check(file.isFile) { "app: the file $path is missing under $repo" }
            texts[path] = file.readText(Charsets.UTF_8)
        }
        return texts
    }

    private fun text(texts: Map<String, String>, path: String): String =
        texts[path] ?: error("app: the file $path is not among the files the gate reads")

    private fun countIn(texts: Map<String, String>, path: String, pattern: String): Int =
        Regex(pattern).findAll(AppSourceFiles.strip(text(texts, path)).code).count()

    private fun literalsIn(texts: Map<String, String>, path: String): List<String> =
        AppSourceFiles.strip(text(texts, path)).literals

    /** The text of the first string literal of each match of [pattern] in the code of the file at [path]. */
    private fun literalsOf(texts: Map<String, String>, path: String, pattern: String): List<String> {
        val stripped = AppSourceFiles.strip(text(texts, path))
        return Regex(pattern).findAll(stripped.code).map { match ->
            stripped.literals[quotes.findAll(stripped.code.substring(0, match.range.first)).count()]
        }.toList()
    }

    /** The project dependencies of a build script as (configuration, project path) pairs, in the order written. */
    private fun projectEdges(text: String): List<Pair<String, String>> {
        val stripped = AppSourceFiles.strip(text)
        return projectCall.findAll(stripped.code).map { match ->
            val index: Int = quotes.findAll(stripped.code.substring(0, match.range.first)).count()
            match.groupValues[1] to stripped.literals[index]
        }.toList()
    }

    /** The XML text with its comments removed, so a commented-out declaration is not a declaration. */
    private fun xmlCode(text: String): String = text.replace(Regex("<!--[\\s\\S]*?-->"), "")

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun edited(texts: Map<String, String>, path: String, old: String, new: String): Map<String, String> {
        val result: MutableMap<String, String> = LinkedHashMap(texts)
        result[path] = edit(text(texts, path), old, new)
        return result
    }

    private fun failingRules(texts: Map<String, String>): List<String> =
        rules.filterNot { it.holds(texts) }.map { it.name }

    @Test
    fun `every rule holds on the real files`() {
        val texts: Map<String, String> = realTexts()
        assertTrue("app: the Kotlin sources were read as empty", texts.keys.any { it.startsWith(mainKotlinPrefix) })
        val broken: List<String> = failingRules(texts)
        assertEquals("app: the real files break these commit rules", emptyList<String>(), broken)
    }

    @Test
    fun `the firing samples cover each rule at least twice and name only known rules`() {
        val names: List<String> = rules.map { it.name }
        assertEquals("app: a rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in names) {
            val count: Int = firing.count { it.rule == rule }
            assertTrue("app: rule $rule needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val texts: Map<String, String> = realTexts()
        val byName: Map<String, Rule> = rules.associateBy { it.name }
        for (sample in firing) {
            val changed: Map<String, String> = edited(texts, sample.path, sample.old, sample.new)
            assertFalse(
                "app: rule ${sample.rule} must fire on the edit of ${sample.path}: '${sample.old}' => '${sample.new}'",
                byName.getValue(sample.rule).holds(changed),
            )
        }
    }

    @Test
    fun `every rule has a harmless edit and no rule fires on a harmless edit`() {
        val texts: Map<String, String> = realTexts()
        for (rule in rules) {
            assertTrue("app: rule ${rule.name} needs at least one harmless edit", quiet.any { it.rule == rule.name })
        }
        for (sample in quiet) {
            val changed: Map<String, String> = edited(texts, sample.path, sample.old, sample.new)
            val broken: List<String> = failingRules(changed)
            assertEquals(
                "app: a harmless edit of ${sample.path} ('${sample.old}' => '${sample.new}') broke rules for ${sample.rule}",
                emptyList<String>(),
                broken,
            )
        }
    }

    @Test
    fun `a commit edit whose target text is missing fails by name`() {
        val thrown = assertThrows("app: a missing target must fail", IllegalStateException::class.java) {
            edit("val a = 1", "no such text", "x")
        }
        assertTrue("app: the failure must name the lost text, got ${thrown.message}", thrown.message!!.contains("no such text"))
    }
}
