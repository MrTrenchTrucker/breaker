package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app's Gradle build file to the values it holds today.
 *
 * Nothing else in the module's tests reads the build file, so a changed application id, a
 * swapped platform level, a moved or extra dependency, a new repository or a different
 * processor architecture would pass every other test. The rules read the code of the file
 * (comments and string text are blanked first, so a word in a comment is not a use) and read
 * a text value from the list of literals the scanner keeps. They pin: the application id;
 * compileSdk, minSdk and targetSdk each assigned once, from the version
 * catalog entry of the same name; the dependency list is exactly the eleven project
 * dependencies, the coroutines library, the unit test library and the speech engine release file as a
 * run-time only dependency (the value it names is pinned by BuildFileSherpaGateTest), in any order, and nothing
 * is exposed with `api`; no repository is declared; unit tests include Android resources;
 * the processor architecture is the one filter the file holds; and the build states a version
 * code (a whole number of 1 or more) and a version name (a plain, non-blank text) once each. The module registry and
 * the card list the same dependencies and are compared elsewhere; this gate pins the build
 * side to the literal list.
 */
internal class BuildFileGateTest {

    private val applicationId: String = "dev.breaker.dictation"

    private val expectedDependencies: List<String> = listOf(
        "implementation :android:modules:core",
        "implementation :android:modules:settings",
        "implementation :android:modules:history",
        "implementation :android:modules:audio",
        "implementation :android:modules:format",
        "implementation :android:modules:transport",
        "implementation :android:ui",
        "implementation :android:modules:overlay",
        "implementation :android:modules:stt-ondevice",
        "implementation :shared:modules:ui-tokens",
        "implementation :shared:modules:model-registry",
        "implementation libs.kotlinx.coroutines.core",
        "runtimeOnly sherpaCoordinate",
        "testImplementation libs.junit",
    ).sorted()

    private val emptyLiteral: Regex = Regex("\"\"")
    private val projectLine: Regex = Regex("(\\w+)\\(project\\(\"\"\\)\\)")
    private val libraryLine: Regex = Regex("(\\w+)\\((libs(?:\\.\\w+)+|sherpaCoordinate)\\)")

    private fun buildFileText(): String {
        val script = File(AppSourceFiles.moduleRoot, "build.gradle.kts")
        check(script.isFile) { "app: build.gradle.kts is missing under ${AppSourceFiles.moduleRoot}" }
        return script.readText(Charsets.UTF_8)
    }

    /** How many string literals start in [code] before [offset]; the scanner leaves one pair of quotes per literal. */
    private fun literalIndex(code: String, offset: Int): Int = emptyLiteral.findAll(code.substring(0, offset)).count()

    private fun without(all: List<String>, taken: List<String>): List<String> {
        val rest: MutableList<String> = all.toMutableList()
        for (item in taken) rest.remove(item)
        return rest
    }

    /** One line per difference between the build file text and the pinned values; none means it matches. */
    private fun problems(text: String): List<String> {
        val stripped: Stripped = AppSourceFiles.strip(text)
        val code: String = stripped.code
        val found: MutableList<String> = ArrayList()
        idProblems(stripped, found)
        for (name in listOf("compileSdk", "minSdk", "targetSdk")) sdkProblems(code, name, found)
        dependencyProblems(stripped, found)
        if (Regex("\\bapi\\s*\\(").containsMatchIn(code)) found.add("api( is used; the app exposes no dependency")
        val repository = Regex("\\b(?:repositories\\s*\\{|mavenCentral\\s*\\(|mavenLocal\\s*\\(|google\\s*\\(|maven\\s*[({]|flatDir\\b)")
        if (repository.containsMatchIn(code)) found.add("repositories: the build file declares a repository")
        val resources = Regex("\\bisIncludeAndroidResources\\s*=\\s*(\\w+)").findAll(code).toList()
        if (resources.size != 1 || resources[0].groupValues[1] != "true") {
            found.add("isIncludeAndroidResources is not assigned true exactly once")
        }
        abiProblems(stripped, found)
        versionProblems(stripped, found)
        return found
    }

    private fun versionProblems(stripped: Stripped, found: MutableList<String>) {
        val code: String = stripped.code
        val codes = Regex("\\bversionCode\\b").findAll(code).count()
        val numbers = Regex("\\bversionCode\\s*=\\s*(\\d+)\\s*$", RegexOption.MULTILINE).findAll(code).toList()
        if (codes != 1 || numbers.size != 1 || numbers[0].groupValues[1].toLong() < 1L) {
            found.add("versionCode is not assigned a whole number of 1 or more exactly once")
        }
        val names = Regex("\\bversionName\\b").findAll(code).count()
        val plain = Regex("\\bversionName\\s*=\\s*\"\"").findAll(code).toList()
        if (names != 1 || plain.size != 1 || stripped.literals[literalIndex(code, plain[0].range.first)].isBlank()) {
            found.add("versionName is not assigned a plain, non-blank text exactly once")
        }
    }

    private fun idProblems(stripped: Stripped, found: MutableList<String>) {
        val code: String = stripped.code
        val uses: Int = Regex("\\bapplicationId\\b").findAll(code).count()
        val plain = Regex("\\bapplicationId\\s*=\\s*\"\"").findAll(code).toList()
        if (uses != 1 || plain.size != 1) {
            found.add("applicationId is not assigned a plain text exactly once")
            return
        }
        val value: String = stripped.literals[literalIndex(code, plain[0].range.first)]
        if (value != applicationId) found.add("applicationId is \"$value\", expected \"$applicationId\"")
    }

    private fun sdkProblems(code: String, name: String, found: MutableList<String>) {
        val sets = Regex("\\b$name\\b\\s*=([^\\n;]*)").findAll(code).toList()
        if (sets.size != 1) {
            found.add("$name is assigned ${sets.size} times, expected once")
            return
        }
        val value: String = sets[0].groupValues[1].filter { !it.isWhitespace() }
        val expected = "libs.versions.$name.get().toInt()"
        if (value != expected) found.add("$name is $value, expected $expected")
    }

    private fun abiProblems(stripped: Stripped, found: MutableList<String>) {
        val code: String = stripped.code
        val blocks: Int = Regex("\\bndk\\s*\\{").findAll(code).count()
        val uses: Int = Regex("\\babiFilters\\b").findAll(code).count()
        val pins = Regex("\\babiFilters\\s*\\+=\\s*\"\"").findAll(code).toList()
        if (blocks != 1 || uses != 1 || pins.size != 1) {
            found.add("abiFilters: expected one ndk block with one abiFilters entry, found $blocks ndk and $uses abiFilters")
            return
        }
        val value: String = stripped.literals[literalIndex(code, pins[0].range.first)]
        if (value != "arm64-v8a") found.add("abiFilters is \"$value\", expected \"arm64-v8a\"")
    }

    private fun dependencyProblems(stripped: Stripped, found: MutableList<String>) {
        val code: String = stripped.code
        val openers = Regex("\\bdependencies\\s*\\{").findAll(code).toList()
        if (openers.size != 1) {
            found.add("dependencies: ${openers.size} blocks, expected 1")
            return
        }
        val start: Int = openers[0].range.last + 1
        var depth = 1
        var end: Int = start
        while (end < code.length && depth > 0) {
            if (code[end] == '{') depth += 1 else if (code[end] == '}') depth -= 1
            end += 1
        }
        if (depth != 0) {
            found.add("dependencies: the block is never closed")
            return
        }
        val actual: MutableList<String> = ArrayList()
        var offset: Int = start
        for (line in code.substring(start, end - 1).split("\n")) {
            val squeezed: String = line.filter { !it.isWhitespace() }
            val project = projectLine.matchEntire(squeezed)
            val library = libraryLine.matchEntire(squeezed)
            if (project != null) {
                actual.add(project.groupValues[1] + " " + stripped.literals[literalIndex(code, offset)])
            } else if (library != null) {
                actual.add(library.groupValues[1] + " " + library.groupValues[2])
            } else if (squeezed.isNotEmpty()) {
                found.add("dependencies: the line \"${line.trim()}\" is not a project or catalog dependency")
            }
            offset += line.length + 1
        }
        for (item in without(expectedDependencies, actual)) found.add("dependency missing: $item")
        for (item in without(actual, expectedDependencies)) found.add("dependency not expected: $item")
    }

    private val firing: List<XmlSample> = listOf(
        XmlSample("application id changed", "applicationId = \"dev.breaker.dictation\"", "applicationId = \"dev.breaker.dictation.app\"", "applicationId is \"dev.breaker.dictation.app\""),
        XmlSample("application id removed", "        applicationId = \"dev.breaker.dictation\"\n", "", "applicationId is not assigned"),
        XmlSample("minSdk from the target entry", "minSdk = libs.versions.minSdk.get().toInt()", "minSdk = libs.versions.targetSdk.get().toInt()", "minSdk is libs.versions.targetSdk"),
        XmlSample("targetSdk from the min entry", "targetSdk = libs.versions.targetSdk.get().toInt()", "targetSdk = libs.versions.minSdk.get().toInt()", "targetSdk is libs.versions.minSdk"),
        XmlSample("compileSdk from the min entry", "compileSdk = libs.versions.compileSdk.get().toInt()", "compileSdk = libs.versions.minSdk.get().toInt()", "compileSdk is libs.versions.minSdk"),
        XmlSample("targetSdk as a number", "targetSdk = libs.versions.targetSdk.get().toInt()", "targetSdk = 36", "targetSdk is 36"),
        XmlSample("minSdk assigned twice", "minSdk = libs.versions.minSdk.get().toInt()", "minSdk = libs.versions.minSdk.get().toInt()\n        minSdk = 21", "minSdk is assigned 2 times"),
        XmlSample("android resources off", "isIncludeAndroidResources = true", "isIncludeAndroidResources = false", "isIncludeAndroidResources"),
        XmlSample("android resources removed", "unitTests { isIncludeAndroidResources = true }", "unitTests { }", "isIncludeAndroidResources"),
        XmlSample("another architecture", "abiFilters += \"arm64-v8a\"", "abiFilters += \"x86_64\"", "abiFilters is \"x86_64\""),
        XmlSample("a second architecture", "ndk { abiFilters += \"arm64-v8a\" }", "ndk { abiFilters += \"arm64-v8a\"; abiFilters += \"x86_64\" }", "abiFilters:"),
        XmlSample("architecture filter removed", "ndk { abiFilters += \"arm64-v8a\" }", "", "abiFilters:"),
        XmlSample("unit test library moved", "testImplementation(libs.junit)", "implementation(libs.junit)", "dependency missing: testImplementation libs.junit", "dependency not expected: implementation libs.junit"),
        XmlSample("speech engine file removed", "    runtimeOnly(sherpaCoordinate)\n", "", "dependency missing: runtimeOnly sherpaCoordinate"),
        XmlSample("speech engine file in the compile path", "runtimeOnly(sherpaCoordinate)", "implementation(sherpaCoordinate)", "dependency missing: runtimeOnly sherpaCoordinate", "dependency not expected: implementation sherpaCoordinate"),
        XmlSample("ui dependency removed", "    implementation(project(\":android:ui\"))\n", "", "dependency missing: implementation :android:ui"),
        XmlSample("overlay dependency removed", "    implementation(project(\":android:modules:overlay\"))\n", "", "dependency missing: implementation :android:modules:overlay"),
        XmlSample("model registry dependency exposed", "implementation(project(\":shared:modules:model-registry\"))", "api(project(\":shared:modules:model-registry\"))", "api( is used", "dependency missing: implementation :shared:modules:model-registry", "dependency not expected: api :shared:modules:model-registry"),
        XmlSample("version code removed", "        versionCode = 1\n", "", "versionCode is not assigned"),
        XmlSample("version code zero", "versionCode = 1", "versionCode = 0", "versionCode is not assigned"),
        XmlSample("version name removed", "        versionName = \"0.1.0-debug\"\n", "", "versionName is not assigned"),
        XmlSample("version name blank", "versionName = \"0.1.0-debug\"", "versionName = \"\"", "versionName is not assigned"),
        XmlSample("extra project dependency", "implementation(project(\":android:ui\"))", "implementation(project(\":android:ui\"))\n    implementation(project(\":android:modules:phrases\"))", "dependency not expected: implementation :android:modules:phrases"),
        XmlSample("project dependency exposed", "implementation(project(\":android:ui\"))", "api(project(\":android:ui\"))", "api( is used", "dependency missing: implementation :android:ui", "dependency not expected: api :android:ui"),
        XmlSample("extra test library", "testImplementation(libs.junit)", "testImplementation(libs.junit)\n    testImplementation(libs.kotlinx.coroutines.test)", "dependency not expected: testImplementation libs.kotlinx.coroutines.test"),
        XmlSample("a file dependency", "testImplementation(libs.junit)", "testImplementation(libs.junit)\n    implementation(files(\"local.jar\"))", "is not a project or catalog dependency"),
        XmlSample("a second dependencies block", "\nkotlin {", "\ndependencies {\n    implementation(libs.junit)\n}\n\nkotlin {", "dependencies: 2 blocks"),
        XmlSample("a repository block", "\nkotlin {", "\nrepositories {\n    mavenCentral()\n}\n\nkotlin {", "repositories:"),
        XmlSample("a repository by name", "\nkotlin {", "\nrepositories { google() }\n\nkotlin {", "repositories:"),
    )

    private val quiet: List<XmlSample> = listOf(
        XmlSample("a comment that names forbidden things", "// Toolchain versions come", "// api(project(\":x\")) repositories { mavenCentral() } abiFilters += \"x86_64\" applicationId = \"z\" minSdk = 1\n// Toolchain versions come"),
        XmlSample("a block comment before the dependencies", "dependencies {", "/* testImplementation(libs.other) \"quoted\" */\ndependencies {"),
        XmlSample("no spaces beside the equals sign", "minSdk = libs.versions.minSdk.get().toInt()", "minSdk=libs.versions.minSdk.get().toInt()"),
        XmlSample("spaces inside the dependency call", "implementation(project(\":android:ui\"))", "implementation( project( \":android:ui\" ) )   "),
        XmlSample("two dependency lines swapped", "    implementation(project(\":android:modules:core\"))\n    implementation(project(\":android:modules:settings\"))\n", "    implementation(project(\":android:modules:settings\"))\n    implementation(project(\":android:modules:core\"))\n"),
        XmlSample("a trailing comment on a dependency", "testImplementation(libs.junit)", "testImplementation(libs.junit) // unit tests only"),
    )

    @Test
    fun `the real build file passes every rule`() {
        val text: String = buildFileText()
        assertTrue("app: build.gradle.kts is empty", text.isNotBlank())
        val found: List<String> = problems(text)
        assertEquals("app: the build file differs from its pinned values: $found", emptyList<String>(), found)
    }

    @Test
    fun `a build file with another id, level, dependency, repository or architecture is rejected with what is wrong`() {
        val real: String = buildFileText()
        for (sample in firing) {
            val found: List<String> = problems(XmlSamples.apply("build file", real, sample))
            assertTrue("app: the build file gate accepted ${sample.label}", found.isNotEmpty())
            for (fragment in sample.fragments) {
                assertTrue("app: the build file gate did not say \"$fragment\" for ${sample.label}, it said $found", found.any { it.contains(fragment) })
            }
        }
    }

    @Test
    fun `comments, spacing and the order of dependency lines do not change the verdict`() {
        val real: String = buildFileText()
        for (sample in quiet) {
            val found: List<String> = problems(XmlSamples.apply("build file", real, sample))
            assertEquals("app: the build file gate flagged ${sample.label}", emptyList<String>(), found)
        }
    }

    @Test
    fun `every rule is fired by at least one sample`() {
        val rules: List<String> = listOf(
            "applicationId is", "applicationId is not assigned", "compileSdk is", "minSdk is", "targetSdk is", "is assigned 2 times",
            "isIncludeAndroidResources", "abiFilters is", "abiFilters:", "dependency missing", "dependency not expected", "api( is used",
            "is not a project or catalog dependency", "dependencies: 2 blocks", "repositories:", "versionCode is not", "versionName is not",
        )
        assertEquals("app: the number of firing samples changed", 29, firing.size)
        for (rule in rules) {
            assertTrue("app: no firing sample names the rule \"$rule\"", firing.any { sample -> sample.fragments.any { it.contains(rule) } })
        }
    }
}
