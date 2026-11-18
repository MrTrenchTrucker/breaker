package dev.breaker.dictation.gates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the app build file names the owner's update key file: the updater dependency, the two
 * names, the one fixed sentence for a named key file that cannot be used, the one fixed warning for a
 * release build with no key file named, the copy task that owns the release asset folder with its
 * wiring through the variant API, and no key value.
 *
 * The name, sentence and warning pins read the raw build file text, not the stripped code: they are
 * string literals, and a stripped read would not see them. Each name and the sentence are counted over
 * the whole file, so a second copy fails as well as a missing one. The rules about what the build does
 * read the stripped code, so a comment or a literal cannot change the outcome.
 */
internal class UpdateKeysBuildGateTest {

    private val updaterLine: String = "implementation(project(\":android:modules:updater\"))"
    private val propertyName: String = "breakerUpdateKeysFile"
    private val variableName: String = "BREAKER_UPDATE_KEYS_FILE"
    private val sentenceFragment: String = "the release build needs the owner's update key file"
    // The name the copy task gives the key file in the release assets; the app reads that exact name.
    private val assetName: String = "\"update_keys.txt\""
    private val keyLikeRun: Regex = Regex("[A-Za-z0-9+/]{43,}")
    private val propertyLine: String = "val updateKeysProperty = \"breakerUpdateKeysFile\""
    private val variableLine: String = "val updateKeysVariable = \"BREAKER_UPDATE_KEYS_FILE\""
    private val sentenceLine: String = "val updateKeysSentence = \"the release build needs the owner's update key file: set the Gradle property \$updateKeysProperty or the environment variable \$updateKeysVariable to a file with one base64 public key on a line\""
    private val warningLine: String = "val updateKeysUnsetWarning = \"no update key file is set, so this release build pins no update key and the app will refuse every update\""
    private val checkLine: String = "tasks.matching { it.name == \"assembleRelease\" || it.name == \"bundleRelease\" }.configureEach { dependsOn(checkUpdateKeys) }"
    private val warnWord: Regex = Regex("\\.\\s*warn\\b")
    private val warnCall: Regex = Regex("\\blogger\\s*\\.\\s*warn\\s*\\(\\s*updateKeysUnsetWarning\\s*\\)")
    private val unsetBranch: Regex = Regex("\\bif\\s*\\(\\s*source\\s*==\\s*null\\s*\\)\\s*\\{\\s*logger\\s*\\.\\s*warn\\s*\\(\\s*updateKeysUnsetWarning\\s*\\)\\s*\\}\\s*else\\s*\\{")
    private val throwWord: Regex = Regex("\\bthrow\\b")
    private val copyKeyPath: Regex = Regex("\\bupdateKeysSource\\s*\\?\\.\\s*let\\s*\\{\\s*file\\s*->\\s*keyFilePath\\s*\\.\\s*set\\s*\\(\\s*file\\s*\\.\\s*absolutePath\\s*\\)\\s*\\}")
    private val copyTask: Regex = Regex("\\bval\\s+copyUpdateKeys\\s+by\\s+tasks\\s*\\.\\s*registering\\s*\\(\\s*CopyUpdateKeysTask\\s*::\\s*class\\s*\\)\\s*\\{")
    private val dependsCheck: Regex = Regex("\\bdependsOn\\s*\\(\\s*checkUpdateKeys\\s*\\)")
    private val dependsCopy: Regex = Regex("\\bdependsOn\\s*\\(\\s*copyUpdateKeys\\s*\\)")
    private val outputRef: Regex = Regex("\\boutputDir\\b")
    private val textRead: Regex = Regex("\\bval\\s+text\\s*:\\s*String\\?\\s*=\\s*if\\s*\\(\\s*(?:java\\s*\\.\\s*nio\\s*\\.\\s*file\\s*\\.\\s*)?Files\\s*\\.\\s*isSymbolicLink\\s*\\(\\s*source\\s*\\.\\s*toPath\\s*\\(\\s*\\)\\s*\\)\\s*\\|\\|\\s*!\\s*source\\s*\\.\\s*isFile\\s*\\)\\s*null\\s*else\\s*source\\s*\\.\\s*readText\\s*\\(")
    private val nameBinding: Regex = Regex("\\bval\\s+updateKeysSource\\s*:\\s*File\\?\\s*=\\s*\\(\\s*updatePropertyValue\\s*\\?:\\s*updateVariableValue\\s*\\)\\s*\\?\\.\\s*let\\s*\\{\\s*File\\s*\\(\\s*it\\s*\\)\\s*\\}[ \\t]*\\r?\\n")
    private val blankTest: Regex = Regex("\\.takeIf\\s*\\{\\s*it\\s*\\.\\s*isNotEmpty\\s*\\(\\s*\\)\\s*\\}")
    private val emptyTest: Regex = Regex("\\blines\\s*\\.\\s*isNotEmpty\\s*\\(\\s*\\)\\s*&&")
    private val allLines: Regex = Regex("\\blines\\s*\\.\\s*all\\s*\\{\\s*line\\s*->")
    private val throwRule: Regex = Regex("\\bif\\s*\\(\\s*text\\s*==\\s*null\\s*\\|\\|\\s*!\\s*good\\s*\\)\\s*throw\\s+GradleException\\s*\\(\\s*updateKeysSentence\\s*\\)")
    private val sizeRule: Regex = Regex("\\bbytes\\s*\\.\\s*size\\s*==\\s*32\\b")
    private val reencode: Regex = Regex("\\bencodeToString\\s*\\(\\s*bytes\\s*\\)\\s*==\\s*line\\b")
    private val skipRule: Regex = Regex("\\.filter\\s*\\{\\s*it\\.isNotEmpty\\(\\)\\s*&&\\s*!it\\.startsWith\\(\"#\"\\)\\s*\\}")
    private val onVariantsAny: Regex = Regex("\\bonVariants\\b")
    private val selectorAny: Regex = Regex("\\bselector\\s*\\(")
    private val releaseOnly: Regex = Regex("\\bonVariants\\s*\\(\\s*selector\\s*\\(\\s*\\)\\s*\\.\\s*withBuildType\\s*\\(\\s*\"\"\\s*\\)\\s*\\)\\s*\\{")
    private val releaseText: Regex = Regex("\\bwithBuildType\\s*\\(\\s*\"release\"\\s*\\)")
    private val wiring: Regex = Regex("\\bsources\\s*\\.\\s*assets\\s*\\?\\.\\s*addGeneratedSourceDirectory\\s*\\(\\s*copyUpdateKeys\\s*,\\s*CopyUpdateKeysTask\\s*::\\s*outputDir\\s*\\)")
    private val wiringAny: Regex = Regex("\\baddGeneratedSourceDirectory\\b")
    private val srcDirAny: Regex = Regex("\\bsrcDir\\s*\\(")
    private val taskClass: Regex = Regex("\\babstract\\s+class\\s+CopyUpdateKeysTask\\s*:\\s*DefaultTask\\s*\\(\\s*\\)\\s*\\{")
    private val outputProp: Regex = Regex("@get\\s*:\\s*OutputDirectory\\s+abstract\\s+val\\s+outputDir\\s*:\\s*DirectoryProperty\\b")
    private val outputAny: Regex = Regex("@get\\s*:\\s*OutputDirectory\\b")
    private val keyProp: Regex = Regex("@get\\s*:\\s*Input\\s+@get\\s*:\\s*Optional\\s+abstract\\s+val\\s+keyFilePath\\s*:\\s*Property\\s*<\\s*String\\s*>")
    private val inputAny: Regex = Regex("@get\\s*:\\s*Input\\b")
    private val copyAction: Regex = Regex("\\bfun\\s+copyKey\\s*\\(\\s*\\)\\s*\\{")
    private val deleteCall: Regex = Regex("\\.\\s*deleteRecursively\\s*\\(\\s*\\)")
    private val copyCall: Regex = Regex("\\bcopyTo\\s*\\(\\s*File\\s*\\(\\s*folder\\s*,\\s*\"\"\\s*\\)\\s*,\\s*overwrite\\s*=\\s*true\\s*\\)")
    private val regularCall: Regex = Regex("\\bFiles\\s*\\.\\s*isRegularFile\\s*\\(\\s*source\\s*\\.\\s*toPath\\s*\\(\\s*\\)\\s*,\\s*LinkOption\\s*\\.\\s*NOFOLLOW_LINKS\\s*\\)")
    private val pathIf: Regex = Regex("\\bif\\s*\\(\\s*path\\s*!=\\s*null\\s*\\)\\s*\\{")
    private val upToDate: Regex = Regex("\\bupToDateWhen\\s*\\{\\s*false\\s*\\}")
    private val upToDateAny: Regex = Regex("\\bupToDateWhen\\b")
    private val projectOrLayout: Regex = Regex("\\b(project|layout)\\b")
    private val qualified: Regex = Regex("\\b(java\\s*\\.\\s*(nio|util)|org\\s*\\.\\s*gradle)\\b")
    private val newImports: List<String> = listOf(
        "import java.nio.file.LinkOption", "import org.gradle.api.DefaultTask", "import org.gradle.api.file.DirectoryProperty",
        "import org.gradle.api.provider.Property", "import org.gradle.api.tasks.Input", "import org.gradle.api.tasks.Optional",
        "import org.gradle.api.tasks.OutputDirectory", "import org.gradle.api.tasks.TaskAction",
    )

    /** A planted edit: [old] must occur once in the real file, and [replacement] takes its place. */
    private class Plant(val label: String, val old: String, val replacement: String, val fragment: String)

    private val firing: List<Plant> = listOf(
        Plant("unset arm throws again", "logger.warn(updateKeysUnsetWarning)", "throw GradleException(updateKeysSentence)", "does not only warn"), Plant("warning missing", "logger.warn(updateKeysUnsetWarning)", "println(updateKeysUnsetWarning)", "warning"),
        Plant("warning carries a path", "logger.warn(updateKeysUnsetWarning)", "logger.warn(updateKeysUnsetWarning + project.rootDir)", "logger.warn"), Plant("warning text carries a path", "and the app will refuse every update\"", "and the app will refuse every update at \${project.rootDir}\"", "warning text"),
        Plant("key path set when unset", "updateKeysSource?.let { file -> keyFilePath.set(file.absolutePath) }", "keyFilePath.set((updateKeysSource ?: File(\".\")).absolutePath)", "outside the set branch"), Plant("copy task is another class", "registering(CopyUpdateKeysTask::class)", "registering(OtherCopyTask::class)", "declared task class"),
        Plant("symbolic link check removed", "Files.isSymbolicLink(source.toPath()) || ", "", "symbolic link"), Plant("regular file check removed", " || !source.isFile) null", ") null", "not a regular file"),
        Plant("missing named file counted as unset", "?.let { File(it) }", "?.let { File(it) }?.takeIf { it.isFile }", "counted as unset"), Plant("blank value counted as set", "gradleProperty(updateKeysProperty).orNull?.trim()?.takeIf { it.isNotEmpty() }", "gradleProperty(updateKeysProperty).orNull?.trim()", "counted as set"),
        Plant("empty file passes", "lines.isNotEmpty() && lines.all", "lines.all", "no key line passes"), Plant("bad key line passes", "lines.all { line ->", "lines.any { line ->", "canonical 32 byte"),
        Plant("set arm does not throw", "if (text == null || !good) throw GradleException(updateKeysSentence)", "if (text == null || !good) logger.warn(updateKeysSentence)", "does not throw"), Plant("key size changed to 31", "bytes.size == 32", "bytes.size == 31", "32 bytes"),
        Plant("re-encode check removed", " && Base64.getEncoder().encodeToString(bytes) == line", "", "re-encode"), Plant("a fully qualified java.nio name", "Files.isSymbolicLink(source.toPath()) || ", "java.nio.file.Files.isSymbolicLink(source.toPath()) || ", "java extension"),
        Plant("a fully qualified java.util name", "Base64.getDecoder().decode(line)", "java.util.Base64.getDecoder().decode(line)", "java extension"), Plant("a fully qualified org.gradle name", "        folder.mkdirs()", "        val probe: Any = org.gradle.api.DefaultTask::class", "fully qualified"),
        Plant("the Files import removed", "import java.nio.file.Files\n", "", "imported"), Plant("the Base64 import removed", "import java.util.Base64\n", "", "imported"),
        Plant("a new import removed", "import org.gradle.api.tasks.TaskAction\n", "", "imports for the task class"), Plant("the Copy import added back", "import org.gradle.api.tasks.TaskAction\n", "import org.gradle.api.tasks.TaskAction\nimport org.gradle.api.tasks.Copy\n", "Copy import"),
        Plant("comment skip removed", " && !it.startsWith(\"#\")", "", "number sign"), Plant("fixed sentence changed", "to a file with one base64 public key on a line\"", "to a file with one key on a line\"", "fixed sentence"),
        Plant("property name changed", "val updateKeysProperty = \"breakerUpdateKeysFile\"", "val updateKeysProperty = \"breakerUpdateKeys\"", "property name"), Plant("release selector removed", "onVariants(selector().withBuildType(\"release\"))", "onVariants(selector())", "release build type only"),
        Plant("release selector set to debug", "withBuildType(\"release\")", "withBuildType(\"debug\")", "release build type only"), Plant("wiring to a wrong property", "CopyUpdateKeysTask::outputDir)", "CopyUpdateKeysTask::keyFilePath)", "through the variant API"),
        Plant("wiring added twice", "variant.sources.assets?.addGeneratedSourceDirectory(copyUpdateKeys, CopyUpdateKeysTask::outputDir)", "variant.sources.assets?.addGeneratedSourceDirectory(copyUpdateKeys, CopyUpdateKeysTask::outputDir)\n        variant.sources.assets?.addGeneratedSourceDirectory(copyUpdateKeys, CopyUpdateKeysTask::outputDir)", "through the variant API"),
        Plant("srcDir back", "    testOptions {", "    sourceSets.getByName(\"release\").assets.srcDir(\"x\")\n    testOptions {", "srcDir"), Plant("merge assets depends on the copy", "tasks.matching { it.name == \"assembleRelease\" || it.name == \"bundleRelease\" }.configureEach { dependsOn(checkUpdateKeys) }", "tasks.matching { it.name == \"assembleRelease\" || it.name == \"bundleRelease\" }.configureEach { dependsOn(checkUpdateKeys) }\ntasks.named(\"mergeReleaseAssets\") { dependsOn(copyUpdateKeys) }", "mergeReleaseAssets"),
        Plant("copy does not depend on the check", "    dependsOn(checkUpdateKeys)\n", "", "depend on the check"), Plant("outputDir set in the registration", "    dependsOn(checkUpdateKeys)\n", "    dependsOn(checkUpdateKeys)\n    outputDir.set(layout.buildDirectory.dir(\"x\"))\n", "outputDir"),
        Plant("copy does not empty the folder", "folder.listFiles()?.forEach { child -> child.deleteRecursively() }", "folder.listFiles()", "empty the folder"), Plant("copy without the regular file test", "Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)", "true", "regular file"),
        Plant("link following on the copy", ", LinkOption.NOFOLLOW_LINKS)", ")", "regular file"), Plant("task is not never up to date", "outputs.upToDateWhen { false }", "outputs.upToDateWhen { true }", "never up to date"),
        Plant("task up to date by a test", "outputs.upToDateWhen { false }", "outputs.upToDateWhen { it.name.isEmpty() }", "never up to date"), Plant("project read in the action", "        folder.mkdirs()", "        project.mkdir(folder)", "project or layout"),
        Plant("output annotation changed", "@get:OutputDirectory", "@get:Internal", "OutputDirectory"), Plant("key input annotation changed", "@get:Input\n", "@get:Internal\n", "keyFilePath"),
        Plant("task class not abstract", "abstract class CopyUpdateKeysTask", "class CopyUpdateKeysTask", "abstract DefaultTask"), Plant("assemble line runs the copy", "configureEach { dependsOn(checkUpdateKeys) }", "configureEach { dependsOn(copyUpdateKeys) }", "assemble and bundle"),
        Plant("srcDir back in a debug set", "    testOptions {", "    sourceSets.getByName(\"debug\").assets.srcDir(\"y\")\n    testOptions {", "srcDir"), Plant("merge named in a comment", "    testOptions {", "    // mergeReleaseAssets is not used here\n    testOptions {", "mergeReleaseAssets"),
        Plant("copy depends on the check twice", "    dependsOn(checkUpdateKeys)\n", "    dependsOn(checkUpdateKeys)\n    dependsOn(checkUpdateKeys)\n", "depend on the check"), Plant("outputDir read in the registration", "    dependsOn(checkUpdateKeys)\n", "    dependsOn(checkUpdateKeys)\n    val target = outputDir\n", "outputDir"),
        Plant("empty step after the copy", "source.copyTo(File(folder, \"update_keys.txt\"), overwrite = true)", "source.copyTo(File(folder, \"update_keys.txt\"), overwrite = true)\n                folder.listFiles()?.forEach { child -> child.deleteRecursively() }", "empty the folder"), Plant("layout read in the task", "val folder: File = outputDir.get().asFile", "val folder: File = layout.buildDirectory.get().asFile", "project or layout"),
        Plant("org.gradle name in the key path", "keyFilePath.set(file.absolutePath)", "keyFilePath.set(org.gradle.api.Project.DEFAULT_BUILD_FILE)", "fully qualified"), Plant("Copy import after Files", "import java.nio.file.Files\n", "import java.nio.file.Files\nimport org.gradle.api.tasks.Copy\n", "Copy import"),
        Plant("assemble line names the debug build", "it.name == \"bundleRelease\"", "it.name == \"bundleDebug\"", "assemble and bundle"),
    )

    private val quiet: List<Plant> = listOf(
        Plant("spacing in the warning call", "logger.warn(updateKeysUnsetWarning)", "logger.warn( updateKeysUnsetWarning )", ""), Plant("spacing in the key path", "keyFilePath.set(file.absolutePath)", "keyFilePath.set( file.absolutePath )", ""),
        Plant("spacing in the link test", "Files.isSymbolicLink(source.toPath()) || !source.isFile", "Files.isSymbolicLink( source.toPath() ) || !source.isFile", ""), Plant("comment before the text read", "val text: String? = if", "// refuses a link first\n            val text: String? = if", ""),
        Plant("trailing comment on the throw", "if (text == null || !good) throw GradleException(updateKeysSentence)", "if (text == null || !good) throw GradleException(updateKeysSentence) // the fixed sentence", ""), Plant("spacing in the name binding", "?.let { File(it) }", "?.let { File( it ) }", ""),
        Plant("spacing in the size check", "bytes.size == 32", "bytes.size==32", ""), Plant("comment before the key check", "val good: Boolean = lines.isNotEmpty() && lines.all { line ->", "// no key line fails\n            val good: Boolean = lines.isNotEmpty() && lines.all { line ->", ""),
        Plant("spacing in the copy registration", "registering(CopyUpdateKeysTask::class) {", "registering( CopyUpdateKeysTask::class ) {", ""), Plant("spacing in the wiring", "addGeneratedSourceDirectory(copyUpdateKeys, CopyUpdateKeysTask::outputDir)", "addGeneratedSourceDirectory( copyUpdateKeys , CopyUpdateKeysTask::outputDir )", ""),
        Plant("a comment naming another build type", "// The key path is set only when a file is named", "// withBuildType(\"debug\") is not used here\n// The key path is set only when a file is named", ""), Plant("a comment naming project and layout in the task", "        // The key content is not an input", "        // project and layout words in a comment are not code\n        // The key content is not an input", ""),
        Plant("spacing in the regular file test", "Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)", "Files.isRegularFile( source.toPath() , LinkOption.NOFOLLOW_LINKS )", ""), Plant("spacing in the never up to date", "outputs.upToDateWhen { false }", "outputs.upToDateWhen {false}", ""),
        Plant("spacing in the release selector", "onVariants(selector().withBuildType(\"release\"))", "onVariants( selector().withBuildType( \"release\" ) )", ""), Plant("a comment before the assemble line", "tasks.matching { it.name == \"assembleRelease\"", "// the check runs before assemble and bundle\ntasks.matching { it.name == \"assembleRelease\"", ""),
        Plant("a comment before the file import", "import java.io.File\n", "// file access\nimport java.io.File\n", ""), Plant("spacing in the check call of the copy", "    dependsOn(checkUpdateKeys)\n", "    dependsOn( checkUpdateKeys )\n", ""),
        Plant("spacing in the output type", "abstract val outputDir: DirectoryProperty", "abstract val outputDir:   DirectoryProperty", ""),
        Plant("a comment in the variant block", "    testOptions {", "    // the release assets come from the variant API\n    testOptions {", ""),
    )

    private fun buildText(): String {
        val script = File(AppSourceFiles.moduleRoot, "build.gradle.kts")
        check(script.isFile) { "app: build.gradle.kts is missing under ${AppSourceFiles.moduleRoot}" }
        return script.readText(Charsets.UTF_8)
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

    private fun planted(real: String, plant: Plant): String {
        val hits: Int = occurrences(real, plant.old)
        check(hits == 1) { "app: the planted edit ${plant.label} needs its old text once, found $hits" }
        return real.replace(plant.old, plant.replacement)
    }

    /** One line per key rule the build file breaks; none means the key block matches the pinned rules. */
    private fun problems(text: String): List<String> {
        val code: String = AppSourceFiles.strip(text).code
        val rawLines: List<String> = text.lines().map { it.trim() }
        val found: MutableList<String> = ArrayList()
        if (rawLines.count { it == sentenceLine } != 1) found.add("the fixed sentence line changed")
        if (rawLines.count { it == propertyLine } != 1) found.add("the property name line changed")
        if (rawLines.count { it == variableLine } != 1) found.add("the variable name line changed")
        if (rawLines.count { it == warningLine } != 1) found.add("the fixed warning text changed")
        if (rawLines.count { it == "import java.nio.file.Files" } != 1 || rawLines.count { it == "import java.util.Base64" } != 1) found.add("Files and Base64 are not each imported exactly once, so the key check would not compile")
        val missing: List<String> = newImports.filter { line -> rawLines.count { it == line } != 1 }
        if (missing.isNotEmpty()) found.add("the imports for the task class are not each present once: $missing")
        if (rawLines.count { it == "import org.gradle.api.tasks.Copy" } != 0) found.add("the Copy import is still present, though no line uses Copy")
        if (code.lines().filterNot { it.trimStart().startsWith("import ") }.any { qualified.containsMatchIn(it) }) found.add("a fully qualified java.nio, java.util or org.gradle name outside the import lines does not compile, since the bare name java is the java extension")
        if (rawLines.count { it == checkLine } != 1) found.add("the assemble and bundle line is not the check line, byte for byte")
        if (text.lines().any { it.contains("mergeReleaseAssets") }) found.add("mergeReleaseAssets is named, but the copy is ordered by its declared output, not by a dependsOn")
        if (count(code, dependsCopy) != 0) found.add("copyUpdateKeys is a dependsOn target, but the release wiring is the variant API")
        if (rawLines.count { it.contains("logger.warn") } != 1 || count(code, warnWord) != 1 || count(code, warnCall) != 1) found.add("the warning is not one logger.warn with the fixed text alone")
        val branches: List<MatchResult> = unsetBranch.findAll(code).toList()
        val throws: List<MatchResult> = throwWord.findAll(code).toList()
        if (branches.size != 1 || throws.size != 1 || throws[0].range.first < branches[0].range.last) found.add("the unset arm does not only warn: a throw is outside the set arm or the warning is missing")
        if (count(code, textRead) != 1) found.add("a symbolic link or a file that is not a regular file is read")
        if (count(code, nameBinding) != 1) found.add("a named file that is missing is counted as unset")
        if (count(code, blankTest) != 2) found.add("a blank value is counted as set")
        if (count(code, emptyTest) != 1) found.add("a file with no key line passes")
        if (count(code, allLines) != 1) found.add("a key line that is not a canonical 32 byte key passes")
        if (count(code, throwRule) != 1) found.add("the set arm does not throw on a bad file")
        if (count(code, sizeRule) != 1) found.add("a key line is not checked for 32 bytes")
        if (count(code, reencode) != 1) found.add("a key line is not checked by a canonical re-encode")
        if (count(text, skipRule) != 1) found.add("blank and number sign lines are not skipped")
        copyProblems(code, found)
        variantProblems(text, code, found)
        taskProblems(code, found)
        return found
    }

    private fun copyProblems(code: String, found: MutableList<String>) {
        if (count(code, copyTask) != 1) found.add("the copy is not the declared task class, so its output is not declared")
        val copyBody: String? = bodyOf(code, copyTask)
        if (copyBody == null || count(copyBody, dependsCheck) != 1) found.add("the copy task does not depend on the check, so the sentence would not come first")
        if (copyBody == null || count(copyBody, copyKeyPath) != 1) found.add("the key path is set outside the set branch, so an unset build has a path")
        if (copyBody == null || count(copyBody, outputRef) != 0) found.add("outputDir is set in the copy registration; AGP wires it")
    }

    private fun variantProblems(text: String, code: String, found: MutableList<String>) {
        if (count(code, onVariantsAny) != 1) found.add("onVariants is not called exactly once")
        if (count(code, selectorAny) != 1) found.add("the variant selector is not written once")
        val releaseBody: String? = bodyOf(code, releaseOnly)
        if (releaseBody == null || count(text, releaseText) != 1) found.add("the variant wiring is not for the release build type only")
        if (releaseBody == null || count(releaseBody, wiring) != 1 || count(code, wiringAny) != 1) found.add("the generated asset folder is not added once, through the variant API, with the copy task and its output")
        if (count(code, srcDirAny) != 0) found.add("srcDir is used: the assets come through the variant API, not a plain source folder")
    }

    private fun taskProblems(code: String, found: MutableList<String>) {
        if (count(code, taskClass) != 1) found.add("CopyUpdateKeysTask is not an abstract DefaultTask declared once")
        val taskBody: String? = bodyOf(code, taskClass)
        if (taskBody == null || count(taskBody, outputProp) != 1 || count(code, outputAny) != 1) found.add("the task does not declare one OutputDirectory on a DirectoryProperty named outputDir")
        if (taskBody == null || count(taskBody, keyProp) != 1 || count(code, inputAny) != 1) found.add("the task does not declare one Input on keyFilePath, an optional Property of String")
        if (taskBody == null || projectOrLayout.containsMatchIn(taskBody)) found.add("the task class reads project or layout")
        val action: String? = bodyOf(code, copyAction)
        if (action == null) {
            found.add("the copy action is missing or not declared once")
        } else {
            val deleteAt: Int = firstAt(action, deleteCall)
            val copyAt: Int = firstAt(action, copyCall)
            if (count(action, deleteCall) != 1 || count(action, copyCall) != 1 || deleteAt >= copyAt) found.add("the copy does not empty the folder first, so a stale key copy could survive")
            val testAt: Int = firstAt(action, regularCall)
            val guardAt: Int = firstAt(action, pathIf)
            if (count(action, regularCall) != 1 || count(action, pathIf) != 1 || !(guardAt < testAt && testAt < copyAt)) found.add("the copy does not test for a regular file that is not a link, inside the named file branch")
        }
        if (count(code, upToDate) != 1 || count(code, upToDateAny) != 1) found.add("the task may be up to date: it must be never up to date, with upToDateWhen { false } once")
    }

    @Test
    fun `the build file puts the updater module on the dependency list once`() {
        val lines: Int = buildText().lines().count { it.trim() == updaterLine }
        assertEquals("app: the build file does not hold the updater project line exactly once", 1, lines)
    }

    @Test
    fun `the property name is written once in the build file`() {
        val count: Int = occurrences(buildText(), propertyName)
        assertEquals("app: the property name $propertyName is not written exactly once", 1, count)
    }

    @Test
    fun `the variable name is written once in the build file`() {
        val count: Int = occurrences(buildText(), variableName)
        assertEquals("app: the variable name $variableName is not written exactly once", 1, count)
    }

    @Test
    fun `the one sentence for a named key file that cannot be used is written once in the build file`() {
        val count: Int = occurrences(buildText(), sentenceFragment)
        assertEquals("app: the fixed sentence for a named key file that cannot be used is not written exactly once", 1, count)
    }

    @Test
    fun `the copied key file takes the one asset name in the build file once`() {
        val count: Int = occurrences(buildText(), assetName)
        assertEquals("app: the asset name $assetName is not written exactly once", 1, count)
    }

    @Test
    fun `the build file holds no run of key looking base64 characters`() {
        val runs: List<String> = keyLikeRun.findAll(buildText()).map { it.value }.toList()
        val keyLike: List<String> = runs.filter { run -> run.any { it.isDigit() || it == '+' } }
        assertTrue("app: the build file holds a run of 43 or more key characters", keyLike.isEmpty())
    }

    @Test
    fun `the key block matches the pinned rules for both arms`() {
        val found: List<String> = problems(buildText())
        assertEquals("app: the key block differs from the pinned rules: $found", emptyList<String>(), found)
    }

    @Test
    fun `the key rules reject a planted break in either arm`() {
        val real: String = buildText()
        for (plant in firing) {
            val found: List<String> = problems(planted(real, plant))
            assertTrue("app: the key rules accepted ${plant.label}", found.isNotEmpty())
            assertTrue("app: the key rules did not say \"${plant.fragment}\" for ${plant.label}, it said $found", found.any { it.contains(plant.fragment) })
        }
    }

    @Test
    fun `comments and spacing do not change the key outcome`() {
        val real: String = buildText()
        for (plant in quiet) {
            assertEquals("app: the key rules flagged ${plant.label}", emptyList<String>(), problems(planted(real, plant)))
        }
    }
}
