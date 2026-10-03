package dev.breaker.dictation.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * No build script in the phone or shared code names `sqlite` outside a test configuration.
 *
 * [SqliteNeverShipsTest] reads the history module's script, the app's and the catalog. A `sqlite-jdbc` added
 * to the `ui` module or to the `core` module would reach the app through its own dependencies, and neither
 * of those scripts would show it. This reads every `build.gradle.kts` under `android/` and `shared/`, and the
 * root one, with [BuildScriptScan], and requires the set it read to hold the modules that matter, so a scan
 * that found no scripts cannot pass.
 *
 * It is the same text scan, with the same limit: a plugin, a convention script or a dependency the text does
 * not show gets past it. `server/` is not read: it does not ship in the phone.
 */
class ScanNeverShipsEverywhereTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val mustBeRead = listOf(
        "build.gradle.kts",
        "android/app/build.gradle.kts",
        "android/ui/build.gradle.kts",
        "android/modules/core/build.gradle.kts",
        "android/modules/history/build.gradle.kts",
    )

    // ── the real scripts ─────────────────────────────────────────────────

    @Test
    fun `the scan reads the root script and the scripts of the app, the ui, core and history modules`() {
        val read = scripts(ModuleFiles.repoRoot).keys
        assertTrue("the scripts read were $read, missing ${mustBeRead - read}", read.containsAll(mustBeRead))
    }

    @Test
    fun `no build script under android or shared has sqlite outside a test configuration`() {
        val scripts = scripts(ModuleFiles.repoRoot)
        assertTrue("the scan read only ${scripts.keys}", scripts.keys.containsAll(mustBeRead))
        val found = scripts.mapValues { BuildScriptScan.nonTestSqliteReferences(it.value.readText()) }
            .filterValues { it.isNotEmpty() }
        assertEquals("sqlite outside a test configuration, by script", emptyMap<String, List<String>>(), found)
    }

    // ── the walk ─────────────────────────────────────────────────────────

    @Test
    fun `the walk finds the scripts under android, under shared and at the root, and reads no other folder`() {
        val root = folder.root
        for (path in listOf(
            "build.gradle.kts",
            "android/app/build.gradle.kts",
            "android/modules/a/b/build.gradle.kts",
            "shared/modules/x/build.gradle.kts",
            "server/modules/s/build.gradle.kts",
            "docs/build.gradle.kts",
            "android/other.gradle.kts",
            "android/app/build.gradle",
        )) {
            write(root, path, "// $path\n")
        }
        assertEquals(
            listOf(
                "android/app/build.gradle.kts",
                "android/modules/a/b/build.gradle.kts",
                "build.gradle.kts",
                "shared/modules/x/build.gradle.kts",
            ),
            scripts(root).keys.sorted(),
        )
    }

    @Test
    fun `the walk skips build output and hidden folders`() {
        val root = folder.root
        for (path in listOf(
            "android/app/build.gradle.kts",
            "android/app/build/tmp/build.gradle.kts",
            "android/.gradle/x/build.gradle.kts",
            "shared/.kotlin/build.gradle.kts",
        )) {
            write(root, path, "// $path\n")
        }
        assertEquals(listOf("android/app/build.gradle.kts"), scripts(root).keys.sorted())
    }

    @Test
    fun `a sqlite dependency planted in any one walked script is found by its path`() {
        val root = folder.root
        val planted = "android/modules/core/build.gradle.kts"
        for (path in mustBeRead) {
            val text = if (path == planted) {
                "dependencies {\n    implementation(libs.sqlite.jdbc)\n}\n"
            } else {
                "plugins {}\n"
            }
            write(root, path, text)
        }
        val found = scripts(root).mapValues { BuildScriptScan.nonTestSqliteReferences(it.value.readText()) }
            .filterValues { it.isNotEmpty() }
        assertEquals(mapOf(planted to listOf("implementation(libs.sqlite.jdbc)")), found)
    }

    @Test
    fun `a planted sqlite in a test configuration of any walked script is not a finding`() {
        val root = folder.root
        write(root, "android/ui/build.gradle.kts", "dependencies {\n    testImplementation(libs.sqlite.jdbc)\n}\n")
        val found = scripts(root).mapValues { BuildScriptScan.nonTestSqliteReferences(it.value.readText()) }
        assertEquals(mapOf("android/ui/build.gradle.kts" to emptyList<String>()), found)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun write(root: File, path: String, text: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    /** Every `build.gradle.kts` in [root] and under its `android/` and `shared/` folders, by path from [root]. */
    private fun scripts(root: File): Map<String, File> {
        val found = sortedMapOf<String, File>()
        fun visit(folder: File) {
            for (entry in folder.listFiles().orEmpty().sortedBy { it.name }) {
                when {
                    entry.isDirectory && entry.name != "build" && !entry.name.startsWith(".") -> visit(entry)
                    entry.isFile && entry.name == "build.gradle.kts" ->
                        found[entry.relativeTo(root).invariantSeparatorsPath] = entry
                }
            }
        }
        File(root, "build.gradle.kts").takeIf { it.isFile }?.let { found["build.gradle.kts"] = it }
        for (top in listOf("android", "shared")) File(root, top).takeIf { it.isDirectory }?.let { visit(it) }
        return found
    }
}
