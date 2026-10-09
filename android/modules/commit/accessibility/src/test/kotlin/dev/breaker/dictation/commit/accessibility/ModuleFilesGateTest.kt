package dev.breaker.dictation.commit.accessibility

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module folder holds exactly the files listed in [pinned], and nothing else; the one exception is the card candidate AGENTS.md.proposed, which may be present or absent.
 *
 * The other gates read the Kotlin files of the two package folders and the one manifest and
 * the one service config. A second manifest for a build variant, a source folder for another
 * language, a second resource file or a stray file at the top is merged into the build or
 * shipped, and no other test would see it. Here every regular file below the folder that
 * holds the build script is listed, build output is the only thing skipped, and the list is
 * compared with the pinned one. A new file must be argued for and added to the list on
 * purpose.
 *
 * Paths are relative to the module folder, written with forward slashes and sorted. The
 * comparison, the path conversion and the listing are plain functions of strings, so the
 * samples below prove each of them without touching the disk.
 */
internal class ModuleFilesGateTest {

    /** Every file the module folder may hold, sorted. */
    private val pinned: List<String> = listOf(
        "AGENTS.md",
        "README.md",
        "build.gradle.kts",
        "src/main/AndroidManifest.xml",
        "src/main/kotlin/dev/breaker/dictation/commit/accessibility/FieldNode.kt",
        "src/main/kotlin/dev/breaker/dictation/commit/accessibility/InsertPlan.kt",
        "src/main/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedField.kt",
        "src/main/kotlin/dev/breaker/dictation/commit/accessibility/adapter/AndroidFieldNode.kt",
        "src/main/kotlin/dev/breaker/dictation/commit/accessibility/adapter/BreakerAccessibilityService.kt",
        "src/main/res/values/strings.xml",
        "src/main/res/xml/commit_accessibility_service_config.xml",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/AdapterGateSamples.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/AdapterGateTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/AdapterMappingGateTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/AdapterMappingSamples.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/ConfigGateTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/FakeFieldNode.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/FakeFieldNodeSwitchesTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/FakeFieldNodeTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/HintTextNotReadTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/InsertPlanMergeTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/InsertPlanRedactionTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/InsertPlanRefusalTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/InsertPlanSelectionTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/InsertPlanSurrogateTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/ManifestGateTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/ModuleFilesGateTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedFieldErrorTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedFieldInsertTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedFieldPrivacyTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedFieldRefusalTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedFieldReleaseTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/NodeFocusedFieldSetTextRefusalTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/PrivacyScanTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/PureFilesScanTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/SourceFiles.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/TestRulesScanTest.kt",
        "src/test/kotlin/dev/breaker/dictation/commit/accessibility/XmlFiles.kt",
    )

    /** Paths that may be there or not at the module root, spelled exactly: a candidate for the module card, applied by renaming it over the card. */
    private val optionalPaths: Set<String> = setOf("AGENTS.md.proposed")

    /** [absolute] below [root] as a relative path with forward slashes; a path outside [root] is marked, so it shows up as an extra. */
    private fun relativePath(root: String, absolute: String): String {
        val base: String = root.replace('\\', '/').trimEnd('/')
        val path: String = absolute.replace('\\', '/')
        return if (path.startsWith(base + "/")) path.substring(base.length + 1) else "outside-root:" + path
    }

    /** True for a path in the top level `build` folder; a folder named `build` deeper down is source and counts. */
    private fun isBuildOutput(relative: String): Boolean = relative == "build" || relative.startsWith("build/")

    /** The sorted relative paths of [absolutes] below [root], without build output. */
    private fun listRelative(root: String, absolutes: List<String>): List<String> =
        absolutes.map { relativePath(root, it) }.filter { !isBuildOutput(it) }.sorted()

    /** One sorted line per difference: "extra: path" for a file nobody pinned, "missing: path" for a pinned file that is not there. */
    private fun fileSetProblems(actual: List<String>, pinned: List<String>): List<String> {
        val have: Set<String> = actual.toSet() - optionalPaths
        val want: Set<String> = pinned.toSet()
        val lines: MutableList<String> = ArrayList()
        for (path in have - want) {
            lines.add("extra: " + path)
        }
        for (path in want - have) {
            lines.add("missing: " + path)
        }
        return lines.sorted()
    }

    private fun assertProblems(label: String, expected: List<String>, actual: List<String>) {
        assertEquals(
            "commit/accessibility: the file set comparison misjudged " + label,
            expected,
            fileSetProblems(actual, pinned),
        )
    }

    @Test
    fun `the module folder holds exactly the pinned files`() {
        val root: File = SourceFiles.moduleRoot
        val files: List<String> = root.walkTopDown().filter { it.isFile }.map { it.path }.toList()
        assertTrue("commit/accessibility: the file set gate found no file below " + root.path, files.isNotEmpty())
        val listing: List<String> = listRelative(root.path, files)
        assertTrue(
            "commit/accessibility: the listing of the module folder lost its build script, so it did not start at the module folder",
            "build.gradle.kts" in listing,
        )
        assertEquals(
            "commit/accessibility: the module folder differs from the pinned file set. An extra line is a file nobody pinned," +
                " a missing line is a pinned file that is not there. A new file must be argued for and pinned in ModuleFilesGateTest",
            emptyList<String>(),
            fileSetProblems(listing, pinned),
        )
    }

    @Test
    fun `the pinned list is sorted and has no duplicate or blank entry`() {
        assertEquals("commit/accessibility: the pinned file list is not sorted", pinned.sorted(), pinned)
        assertEquals("commit/accessibility: the pinned file list names a file twice", pinned.size, pinned.toSet().size)
        assertTrue("commit/accessibility: the pinned file list has a blank entry", pinned.none { it.isBlank() })
        assertTrue("commit/accessibility: the pinned file list names a path that is not relative", pinned.none { it.startsWith("/") })
    }

    @Test
    fun `the comparison is quiet on the exact set in any order`() {
        assertProblems("the exact set", emptyList(), pinned)
        assertProblems("the exact set in reverse order", emptyList(), pinned.reversed())
    }

    @Test
    fun `the comparison fires on an extra build variant manifest`() {
        assertProblems(
            "an extra build variant manifest",
            listOf("extra: src/debug/AndroidManifest.xml"),
            pinned + "src/debug/AndroidManifest.xml",
        )
    }

    @Test
    fun `the comparison fires on an extra source file of another language`() {
        assertProblems(
            "an extra java file",
            listOf("extra: src/main/java/Other.java"),
            pinned + "src/main/java/Other.java",
        )
    }

    @Test
    fun `the comparison fires on an extra resource file`() {
        assertProblems(
            "an extra resource file",
            listOf("extra: src/main/res/values/more.xml"),
            pinned + "src/main/res/values/more.xml",
        )
    }

    @Test
    fun `the comparison fires on a stray file at the top and on a hidden one`() {
        assertProblems("a stray top level file", listOf("extra: notes.txt"), pinned + "notes.txt")
        assertProblems("a hidden file", listOf("extra: .gitignore"), pinned + ".gitignore")
    }

    @Test
    fun `the comparison is quiet when the card candidate is present beside the pinned set`() {
        assertProblems("the card candidate beside the pinned set", emptyList(), pinned + "AGENTS.md.proposed")
    }

    @Test
    fun `the comparison is quiet when the card candidate is absent`() {
        assertProblems("the pinned set without the card candidate", emptyList(), pinned)
    }

    @Test
    fun `the comparison fires on any other name for the card candidate`() {
        assertProblems("a backup of the card candidate", listOf("extra: AGENTS.md.proposed.bak"), pinned + "AGENTS.md.proposed.bak")
        assertProblems("a card candidate below src", listOf("extra: src/AGENTS.md.proposed"), pinned + "src/AGENTS.md.proposed")
        assertProblems("a card candidate in another case", listOf("extra: AGENTS.MD.proposed"), pinned + "AGENTS.MD.proposed")
    }

    @Test
    fun `the comparison still lists a missing file when the card candidate is present`() {
        assertProblems(
            "a missing readme beside the card candidate",
            listOf("missing: README.md"),
            pinned.filter { it != "README.md" } + "AGENTS.md.proposed",
        )
    }

    @Test
    fun `the comparison fires on a missing pinned file`() {
        assertProblems(
            "a missing manifest",
            listOf("missing: src/main/AndroidManifest.xml"),
            pinned.filter { it != "src/main/AndroidManifest.xml" },
        )
    }

    @Test
    fun `the comparison fires on a file whose name differs only in case`() {
        assertProblems(
            "a manifest named in another case",
            listOf("extra: src/main/AndroidManifest.XML", "missing: src/main/AndroidManifest.xml"),
            pinned.filter { it != "src/main/AndroidManifest.xml" } + "src/main/AndroidManifest.XML",
        )
    }

    @Test
    fun `the comparison lists an extra and a missing file together and sorted`() {
        assertProblems(
            "an extra and a missing file together",
            listOf(
                "extra: src/debug/AndroidManifest.xml",
                "extra: src/main/res/values/more.xml",
                "missing: README.md",
            ),
            pinned.filter { it != "README.md" } + "src/main/res/values/more.xml" + "src/debug/AndroidManifest.xml",
        )
    }

    @Test
    fun `a path becomes a relative path with forward slashes`() {
        assertEquals(
            "commit/accessibility: a path with forward slashes was not made relative",
            "src/main/AndroidManifest.xml",
            relativePath("/work/mod", "/work/mod/src/main/AndroidManifest.xml"),
        )
        assertEquals(
            "commit/accessibility: a path with backslashes was not made relative with forward slashes",
            "src/main/res/values/strings.xml",
            relativePath("C:\\work\\mod", "C:\\work\\mod\\src\\main\\res\\values\\strings.xml"),
        )
        assertEquals(
            "commit/accessibility: a folder with a trailing slash was not handled",
            "build.gradle.kts",
            relativePath("/work/mod/", "/work/mod/build.gradle.kts"),
        )
        assertEquals(
            "commit/accessibility: a folder whose name only starts like the module folder was taken as inside it",
            "outside-root:/work/module2/Other.kt",
            relativePath("/work/mod", "/work/module2/Other.kt"),
        )
    }

    @Test
    fun `the listing sorts and keeps nested files`() {
        assertEquals(
            "commit/accessibility: the listing did not sort or lost a nested file",
            listOf("AGENTS.md", "src/main/kotlin/dev/breaker/Deep.kt", "src/main/res/xml/a.xml"),
            listRelative(
                "/r",
                listOf("/r/src/main/res/xml/a.xml", "/r/src/main/kotlin/dev/breaker/Deep.kt", "/r/AGENTS.md"),
            ),
        )
        assertEquals(
            "commit/accessibility: the listing did not handle backslash separators",
            listOf("src/main/AndroidManifest.xml"),
            listRelative("C:\\r", listOf("C:\\r\\src\\main\\AndroidManifest.xml")),
        )
    }

    @Test
    fun `the listing skips only the top level build folder`() {
        assertEquals(
            "commit/accessibility: the listing skipped a source file or kept build output",
            listOf("build.gradle.kts", "buildSrc/b.kts", "src/build/Generated.kt", "src/main/build/y.xml"),
            listRelative(
                "/r",
                listOf(
                    "/r/build/outputs/a.apk",
                    "/r/build/x",
                    "/r/src/build/Generated.kt",
                    "/r/buildSrc/b.kts",
                    "/r/build.gradle.kts",
                    "/r/src/main/build/y.xml",
                ),
            ),
        )
        assertEquals(
            "commit/accessibility: the listing kept build output written with backslashes",
            emptyList<String>(),
            listRelative("C:\\r", listOf("C:\\r\\build\\x.class")),
        )
    }
}
