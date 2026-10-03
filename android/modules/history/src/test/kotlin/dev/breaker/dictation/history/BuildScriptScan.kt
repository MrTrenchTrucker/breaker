package dev.breaker.dictation.history

/**
 * A text scan of a Gradle Kotlin build script for `sqlite` outside the test
 * configurations, and of the version catalog for a bundle that names it.
 *
 * **What it is, and what it is not.** It reads text. It is not the resolved
 * dependency graph, and a build that pulls SQLite in some way the text does not
 * show (a plugin, a convention script, a variable built from pieces, a script
 * nobody pointed the scan at) gets past it. The resolved graph is what a build
 * ships, and it can be printed at any time:
 *
 *     ./gradlew :android:modules:history:dependencies --configuration releaseRuntimeClasspath
 *     ./gradlew :android:app:dependencies --configuration releaseRuntimeClasspath
 *
 * What the scan does catch is the ordinary mistake: SQLite added under
 * `implementation`, `api`, `releaseImplementation` or any other configuration
 * that reaches an app a user installs.
 *
 * **The rule.** Comments are removed, then every call to a test configuration
 * ([TEST_CONFIGURATIONS]) is removed, and whatever text is left must not contain
 * `sqlite`. So `sqlite` is allowed inside `testImplementation(...)` and nowhere
 * else, including in a variable, an `add("implementation", ...)` call or a
 * string-invoked configuration. A false alarm is possible; that is the safe
 * direction, and a reviewer can read the report.
 *
 * **Bundles.** A build script that takes `libs.bundles.<name>` never shows the
 * word `sqlite`, so the rule above cannot see inside the bundle. The catalog can:
 * a bundle is a list of library aliases, and [bundlesNamingSqlite] reads it.
 */
internal object BuildScriptScan {

    /** The configurations whose dependencies are not part of a release build. */
    private val TEST_CONFIGURATIONS =
        Regex("""^(test|androidTest)(Debug|Release)?(Implementation|CompileOnly|RuntimeOnly)$""")

    private val CALL_START = Regex("""(?<![\w.])"?([A-Za-z_][A-Za-z0-9_]*)"?\s*\(""")

    private val TABLE_HEADER = Regex("""^\[+\s*([^\]\s]+)\s*]+$""")
    private val KEY = Regex("""^"?([A-Za-z0-9_.\-]+)"?\s*=""")
    private val QUOTED = Regex("\"([^\"]*)\"")

    /** Every place `sqlite` appears in [script] outside a test configuration, as short excerpts. */
    fun nonTestSqliteReferences(script: String): List<String> {
        val remaining = withoutTestConfigurations(withoutComments(script))
        return remaining.lines()
            .map { it.trim() }
            .filter { it.contains("sqlite", ignoreCase = true) }
    }

    /** True when [script] mentions `sqlite` at all, in code or in a test configuration (comments ignored). */
    fun mentionsSqlite(script: String): Boolean = withoutComments(script).contains("sqlite", ignoreCase = true)

    /**
     * Every line of the version catalog [catalog] that puts SQLite in a bundle, as
     * short excerpts. A line counts when it contains `sqlite`, or names the alias
     * of a library whose own definition does (see [sqliteLibraryAliases]), so
     * renaming the alias does not hide it. The `[bundles]` table is read, and the
     * two other ways TOML can spell it: a root key `bundles.x = ...` and an inline
     * `bundles = { ... }`. A catalog with no bundles gives an empty list.
     */
    fun bundlesNamingSqlite(catalog: String): List<String> {
        val aliases = sqliteLibraryAliases(catalog).map { normalisedAlias(it) }.toSet()
        val found = mutableListOf<String>()
        var table = ""
        var inBundles = false
        for (line in catalogLines(catalog)) {
            val header = TABLE_HEADER.matchEntire(line)
            if (header != null) {
                table = header.groupValues[1]
                continue
            }
            // A line that starts a key decides whether what follows, up to the next
            // key, is in the bundles; a line inside a multi-line array keeps that.
            val key = KEY.find(line)?.groupValues?.get(1)
            if (key != null) inBundles = isBundles(table) || (table.isEmpty() && isBundles(key))
            val namesAlias = QUOTED.findAll(line).any { normalisedAlias(it.groupValues[1]) in aliases }
            if (inBundles && (line.contains("sqlite", ignoreCase = true) || namesAlias)) found += line
        }
        return found
    }

    /** The aliases the `[libraries]` table gives to libraries whose definition mentions `sqlite`. */
    fun sqliteLibraryAliases(catalog: String): List<String> {
        var table = ""
        val aliases = mutableListOf<String>()
        for (line in catalogLines(catalog)) {
            val header = TABLE_HEADER.matchEntire(line)
            if (header != null) {
                table = header.groupValues[1]
                continue
            }
            val key = KEY.find(line)?.groupValues?.get(1)
            if (table == "libraries" && key != null && line.contains("sqlite", ignoreCase = true)) aliases += key
        }
        return aliases
    }

    /** Gradle treats `-`, `_` and `.` in an alias as the same separator. */
    private fun normalisedAlias(alias: String): String = alias.lowercase().replace('-', '.').replace('_', '.')

    private fun isBundles(name: String): Boolean = name == "bundles" || name.startsWith("bundles.")

    /** The catalog's lines, trimmed, without comments or blank lines. */
    private fun catalogLines(catalog: String): List<String> =
        catalog.lines().map { it.replace(Regex("""(^|\s)#.*$"""), "$1").trim() }.filter { it.isNotEmpty() }

    private fun withoutComments(script: String): String =
        script
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            // A line comment starts at the line's start or after whitespace, so the
            // "//" in a URL inside a string is not taken for one.
            .replace(Regex("""(?m)(^|\s)//.*$"""), "$1")

    private fun withoutTestConfigurations(script: String): String {
        val out = StringBuilder()
        var cursor = 0
        while (true) {
            val match = CALL_START.find(script, cursor)
            if (match == null) {
                out.append(script, cursor, script.length)
                return out.toString()
            }
            val configuration = match.groupValues[1]
            if (!TEST_CONFIGURATIONS.matches(configuration)) {
                // Keep the text and move on; a nested call inside it is scanned in turn.
                out.append(script, cursor, match.range.last + 1)
                cursor = match.range.last + 1
                continue
            }
            out.append(script, cursor, match.range.first)
            cursor = closingParenthesis(script, match.range.last) + 1
        }
    }

    /** The index of the parenthesis that closes the one at [open]; the end of the text if it never closes. */
    private fun closingParenthesis(script: String, open: Int): Int {
        var depth = 0
        for (index in open until script.length) {
            when (script[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return script.length - 1
    }
}
