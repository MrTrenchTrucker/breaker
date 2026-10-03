package dev.breaker.dictation.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SQLite test dependency never reaches a release build.
 *
 * `sqlite-jdbc` is on the test classpath only: it gives the JVM tests a real
 * SQLite to run [HistorySql] on. If it were ever added to a configuration that
 * reaches an installed app, the app would carry a desktop database driver and its
 * native libraries for nothing. These tests go red the moment `sqlite` shows up
 * in the history module's build script, or the app's, outside a test
 * configuration, or in a bundle of the version catalog.
 *
 * **The limit.** This is a check on text, not on the resolved dependency graph.
 * It catches the ordinary mistake in the scripts it reads. It does not see
 * another module's build script, a plugin, or anything only the resolved graph
 * shows; [BuildScriptScan] says what gets past it. The resolved release
 * classpaths of the history module and of the app can be printed at any time:
 *
 *     ./gradlew :android:modules:history:dependencies --configuration releaseRuntimeClasspath
 *     ./gradlew :android:app:dependencies --configuration releaseRuntimeClasspath
 *
 * The app has no dependency on the history module yet, so the check on the app's
 * script is true but has nothing to see today: it starts to matter when the app
 * takes this module.
 *
 * The scripts and the catalog are read, never written.
 */
class SqliteNeverShipsTest {

    private val historyScript = File(ModuleFiles.moduleRoot, "build.gradle.kts").readText()
    private val appScript = ModuleFiles.repoFile("android/app/build.gradle.kts").readText()
    private val catalog = ModuleFiles.repoFile("gradle/libs.versions.toml").readText()

    // ── the scan can go red: planted mistakes ────────────────────────────

    @Test
    fun `the scan flags sqlite in every configuration that reaches an installed app`() {
        val mistakes = mapOf(
            "implementation" to "implementation(libs.sqlite.jdbc)",
            "api" to "api(\"org.xerial:sqlite-jdbc:3.53.4.0\")",
            "releaseImplementation" to "releaseImplementation(libs.sqlite.jdbc)",
            "debugImplementation" to "debugImplementation(libs.sqlite.jdbc)",
            "runtimeOnly" to "runtimeOnly(libs.sqlite.jdbc)",
            "compileOnly" to "compileOnly(libs.sqlite.jdbc)",
            "over several lines" to "implementation(\n        libs.sqlite.jdbc\n    )",
            "a string-invoked configuration" to "\"implementation\"(libs.sqlite.jdbc)",
            "add()" to "add(\"implementation\", libs.sqlite.jdbc)",
            "a variable" to "val driver = libs.sqlite.jdbc",
        )
        for ((label, line) in mistakes) {
            val script = "dependencies {\n    testImplementation(libs.junit)\n    $line\n}\n"
            assertFalse(
                "the scan let sqlite through as $label: $line",
                BuildScriptScan.nonTestSqliteReferences(script).isEmpty(),
            )
        }
    }

    @Test
    fun `the scan flags a non-test sqlite line even when a test line carries it too`() {
        val script = """
            dependencies {
                testImplementation(libs.sqlite.jdbc)
                implementation(libs.sqlite.jdbc)
            }
        """.trimIndent()
        assertEquals(listOf("implementation(libs.sqlite.jdbc)"), BuildScriptScan.nonTestSqliteReferences(script))
    }

    @Test
    fun `the scan allows sqlite in the test configurations and in comments`() {
        val allowed = """
            // Owns: SQLite transcription history.
            /* the desktop SQLite, for tests */
            dependencies {
                testImplementation(libs.sqlite.jdbc)
                testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
                testRuntimeOnly(libs.sqlite.jdbc)
                androidTestImplementation(libs.sqlite.jdbc)
                testImplementation(
                    libs.sqlite.jdbc
                )
                testImplementation(files("libs/a.jar"), libs.sqlite.jdbc)
                testImplementation(libs.junit) // sqlite is not here
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), BuildScriptScan.nonTestSqliteReferences(allowed))
        assertTrue("the scan must still see the test lines", BuildScriptScan.mentionsSqlite(allowed))
    }

    @Test
    fun `the catalog scan flags a bundle that names sqlite`() {
        val root = "bundles.db = [\"sqlite-jdbc\"]\n\n"
        val mistakes = mapOf(
            "a bundle naming the library" to catalogWith("[bundles]\ndb = [\"junit\", \"sqlite-jdbc\"]"),
            "a bundle over several lines" to catalogWith("[bundles]\ndb = [\n    \"junit\",\n    \"sqlite-jdbc\",\n]"),
            "a dotted alias" to catalogWith("[bundles]\ndb = [\"sqlite.jdbc\"]"),
            "capital letters" to catalogWith("[bundles]\ndb = [\"SQLite-JDBC\"]"),
            "another alias for the same library" to catalogWith("[bundles]\ndb = [\"jdbc-driver\"]"),
            "the alias spelled with underscores" to catalogWith("[bundles]\ndb = [\"jdbc_driver\"]"),
            "a bundle followed by other tables" to
                catalogWith("[bundles]\ndb = [\"sqlite-jdbc\"]\n\n[plugins]\np = { id = \"a\" }"),
            "a bundle written as a root key" to root + catalogWith(""),
            "a bundle written inline" to "bundles = { db = [\"sqlite-jdbc\"] }\n\n" + catalogWith(""),
            "a bundle before the libraries are defined" to
                "[bundles]\ndb = [\"jdbc-driver\"]\n\n" + catalogWith(""),
        )
        for ((label, planted) in mistakes) {
            assertFalse(
                "the scan let sqlite through as $label",
                BuildScriptScan.bundlesNamingSqlite(planted).isEmpty(),
            )
        }
    }

    @Test
    fun `the catalog scan leaves a catalog without a sqlite bundle alone`() {
        val fine = mapOf(
            "no bundles at all" to catalogWith(""),
            "a bundle of other libraries" to catalogWith("[bundles]\ntests = [\"junit\"]"),
            "sqlite in a comment after a bundle" to
                catalogWith("[bundles]\ntests = [\"junit\"]\n# sqlite-jdbc stays out"),
            "sqlite in a comment at the end of a bundle line" to
                catalogWith("[bundles]\ntests = [\"junit\"] # sqlite-jdbc stays out"),
            "the word bundles in a comment" to catalogWith("# [bundles] db = [\"sqlite-jdbc\"]"),
            "sqlite in a table after the bundles" to
                catalogWith("[bundles]\ntests = [\"junit\"]\n\n[plugins]\nsqlite-plugin = { id = \"a.sqlite\" }"),
        )
        for ((label, text) in fine) {
            // The scan must know the library, or "not flagged" would only mean "no alias to look for".
            assertTrue(
                "the scan did not find the sqlite library in $label",
                "jdbc-driver" in BuildScriptScan.sqliteLibraryAliases(text),
            )
            assertEquals("the scan flagged $label", emptyList<String>(), BuildScriptScan.bundlesNamingSqlite(text))
        }
    }

    /** A catalog with a sqlite library under two aliases, then [tables] (bundles and the like) after it. */
    private fun catalogWith(tables: String): String =
        "[versions]\njunit = \"4.13.2\"\nsqlite-jdbc = \"3.53.4.0\"\n\n" +
            "[libraries]\njunit = { module = \"junit:junit\", version.ref = \"junit\" }\n" +
            "sqlite-jdbc = { module = \"org.xerial:sqlite-jdbc\", version.ref = \"sqlite-jdbc\" }\n" +
            "jdbc-driver = { module = \"org.xerial:sqlite-jdbc\", version.ref = \"sqlite-jdbc\" }\n\n" +
            tables + "\n"

    // ── the real scripts ─────────────────────────────────────────────────

    @Test
    fun `the history module's build script has sqlite only in a test configuration`() {
        assertTrue(
            "The script was read but names no sqlite at all, so the check below would " +
                "pass on any script: the test dependency should be there",
            BuildScriptScan.mentionsSqlite(historyScript),
        )
        assertEquals(
            "sqlite outside a test configuration in android/modules/history/build.gradle.kts",
            emptyList<String>(),
            BuildScriptScan.nonTestSqliteReferences(historyScript),
        )
    }

    @Test
    fun `the app's build script has no sqlite outside a test configuration`() {
        assertTrue(
            "The app's build script was not read",
            appScript.contains("com.android.application") || appScript.contains("android.application"),
        )
        assertEquals(
            "sqlite outside a test configuration in android/app/build.gradle.kts",
            emptyList<String>(),
            BuildScriptScan.nonTestSqliteReferences(appScript),
        )
    }

    @Test
    fun `the version catalog has no bundle that names sqlite`() {
        assertTrue(
            "The catalog was read but defines no sqlite library, so the check below would pass on any catalog",
            "sqlite-jdbc" in BuildScriptScan.sqliteLibraryAliases(catalog),
        )
        assertEquals(
            "sqlite in a bundle of gradle/libs.versions.toml",
            emptyList<String>(),
            BuildScriptScan.bundlesNamingSqlite(catalog),
        )
    }

    // ── the pin ──────────────────────────────────────────────────────────

    @Test
    fun `sqlite-jdbc is pinned to one exact version in the catalog and used from there`() {
        assertEquals(
            "the catalog must pin sqlite-jdbc to exactly 3.53.4.0, not a range",
            1,
            Regex("""(?m)^sqlite-jdbc\s*=\s*"3\.53\.4\.0"\s*$""").findAll(catalog).count(),
        )
        assertEquals(
            1,
            Regex(
                """(?m)^sqlite-jdbc\s*=\s*\{\s*module\s*=\s*"org\.xerial:sqlite-jdbc",""" +
                    """\s*version\.ref\s*=\s*"sqlite-jdbc"\s*\}\s*$""",
            ).findAll(catalog).count(),
        )
        assertTrue(
            "the history module takes it from the catalog, as a test dependency",
            historyScript.contains("testImplementation(libs.sqlite.jdbc)"),
        )
    }
}
