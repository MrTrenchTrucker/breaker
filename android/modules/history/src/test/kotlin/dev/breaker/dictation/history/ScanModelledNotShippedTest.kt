package dev.breaker.dictation.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Five names are modelled and tested in this module and used by no shipped code: `RetentionPolicy.isExpired`,
 * `RetentionBoundary.isExpired`, `AppPrivateStorage.resolve`, `AppPrivateStorage.isInside` and
 * `AppPrivateStorage.DATABASES_DIR`. The card, the README and four KDocs say so.
 *
 * Nothing pinned it: a shipped caller would have left every test green. This reads the comment-stripped,
 * literal-masked code of every file under `src/main` ([AdapterStatements.wordUses]) and lists each use of
 * those names that is not their own declaration and not one of the two calls the model itself makes
 * (`RetentionPolicy.isExpired` calling `RetentionBoundary.isExpired`, and `resolve` calling `isInside` and
 * reading `DATABASES_DIR`). The scan goes by the member name whatever the receiver is written as, so a call
 * through an instance is found too. A use of `resolve` by any other code, even on another type, is a finding:
 * a false alarm there is the safe direction.
 *
 * The other modules under `android/` are read for the three internal type names. The compiler already keeps
 * `internal` names out of them, so that half is belt and braces.
 *
 * It is text: a call through reflection, or through a name built from pieces, is not seen. A new caller that is
 * meant to ship is a finding by design: the change adds it to the allowed list below, in the open.
 */
class ScanModelledNotShippedTest {

    /** A use that is allowed: the file, the method it sits in, the name, and what must stand just before it. */
    private class Allowed(val file: String, val owner: String, val name: String, val qualifier: String = "") {
        /** [before] is the code just before the use with its whitespace removed. */
        fun permits(file: String, owner: String, name: String, before: String): Boolean =
            this.file == file && this.owner == owner && this.name == name && before.endsWith(qualifier)
    }

    private val allowed = listOf(
        Allowed("RetentionPolicy.kt", owner = "isExpired", name = "isExpired", qualifier = "RetentionBoundary."),
        Allowed("AppPrivateStorage.kt", owner = "resolve", name = "isInside"),
        Allowed("AppPrivateStorage.kt", owner = "resolve", name = "DATABASES_DIR"),
    )

    private val names = listOf("isExpired", "isInside", "resolve", "DATABASES_DIR")

    /** The declarations that must be found, so a scan of nothing cannot pass. */
    private val declared = listOf(
        "AppPrivateStorage.kt: DATABASES_DIR",
        "AppPrivateStorage.kt: isInside",
        "AppPrivateStorage.kt: resolve",
        "RetentionBoundary.kt: isExpired",
        "RetentionPolicy.kt: isExpired",
    )

    private val internalTypes = listOf("RetentionPolicy", "RetentionBoundary", "AppPrivateStorage")

    // ── the real sources ─────────────────────────────────────────────────

    @Test
    fun `nothing under src main uses the five modelled names outside their definitions and the calls they make`() {
        val scan = scan(mainSources())
        assertEquals("the declarations the scan found", declared, scan.declared)
        assertEquals("uses of the modelled names outside their definitions", emptyList<String>(), scan.uses)
    }

    @Test
    fun `no other module under android names the internal retention and storage types`() {
        val others = otherModuleSources()
        assertTrue(
            "the scan read only ${others.keys}, expected the core module among them",
            others.keys.any { it.startsWith("android/modules/core/src/main/") },
        )
        val found = others.mapValues { (_, text) -> typeUses(text) }.filterValues { it.isNotEmpty() }
        assertEquals("uses of the internal types in other modules, by file", emptyMap<String, List<String>>(), found)
    }

    // ── made-up sources ──────────────────────────────────────────────────

    @Test
    fun `the model as it is written is accepted, and so is the same code reformatted`() {
        val variants = mapOf(
            "as written" to model(),
            "with a call on the next line" to model(
                policy = "fun isExpired(createdAt: Instant, now: Instant): Boolean =\n        RetentionBoundary\n" +
                    "            .isExpired(createdAt.toEpochMilli(), 0)",
            ),
            "with a space around the dot" to
                model(policy = "fun isExpired(a: Instant): Boolean = RetentionBoundary . isExpired(1, 2)"),
            "with a comment naming a caller" to
                model(extra = "// AppPrivateStorage.resolve(dir) is called by open()\n"),
            "with a string naming a caller" to
                model(extra = "val note = \"DATABASES_DIR resolve isInside isExpired\"\n"),
            "with longer names" to model(extra = "val x = resolved + isInsideOf + isExpiredAt + DATABASES_DIR_2\n"),
        )
        for ((label, files) in variants) {
            val scan = scan(files)
            assertEquals("declarations $label", declared, scan.declared)
            assertEquals("a use was reported $label", emptyList<String>(), scan.uses)
        }
    }

    @Test
    fun `a call to isExpired through the policy instance is found`() {
        val uses = scan(model(extra = "fun purge() { retention.isExpired(a, b) }\n")).uses
        assertEquals(listOf("Extra.kt: isExpired in purge"), uses)
    }

    @Test
    fun `a call to RetentionBoundary isExpired outside the policy is found`() {
        val uses = scan(model(extra = "fun purge() { RetentionBoundary.isExpired(0L, cutoff) }\n")).uses
        assertEquals(listOf("Extra.kt: isExpired in purge"), uses)
    }

    @Test
    fun `a call to isExpired in the policy that is not the boundary's is found`() {
        val files = model(policy = "fun isExpired(a: Instant, b: Instant): Boolean = other.isExpired(a, b)")
        assertEquals(listOf("RetentionPolicy.kt: isExpired in isExpired"), scan(files).uses)
    }

    @Test
    fun `a use of resolve, of isInside or of DATABASES_DIR outside the storage object's own resolve is found`() {
        val files = model(
            extra = "val dir = AppPrivateStorage.DATABASES_DIR\n" +
                "fun open(dir: File) = AppPrivateStorage.resolve(dir)\n" +
                "fun check(f: File) = AppPrivateStorage.isInside(f, f)\n",
        )
        assertEquals(
            setOf(
                "Extra.kt: DATABASES_DIR in (outside any method)",
                "Extra.kt: resolve in open",
                "Extra.kt: isInside in check",
            ),
            scan(files).uses.toSet(),
        )
    }

    @Test
    fun `a use of DATABASES_DIR inside another method of the storage object is found`() {
        val files = model(storage = "fun other(): String = DATABASES_DIR")
        assertEquals(listOf("AppPrivateStorage.kt: DATABASES_DIR in other"), scan(files).uses)
    }

    @Test
    fun `a declaration that is gone shows in the declarations found, so a rename cannot hide a caller`() {
        val files = model().filterKeys { it != "RetentionBoundary.kt" }
        assertEquals(declared - "RetentionBoundary.kt: isExpired", scan(files).declared)
    }

    @Test
    fun `the three internal type names in another module are found, in code only`() {
        assertEquals(listOf("RetentionPolicy"), typeUses("val p = RetentionPolicy()"))
        assertEquals(listOf("AppPrivateStorage"), typeUses("import dev.breaker.dictation.history.AppPrivateStorage\n"))
        assertEquals(listOf("RetentionBoundary"), typeUses("val b = RetentionBoundary\n    .isExpired(1, 2)"))
        val fine = "// RetentionPolicy\nval s = \"AppPrivateStorage\"\nval RetentionPolicyX = 1"
        assertEquals(emptyList<String>(), typeUses(fine))
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private class Scan(val declared: List<String>, val uses: List<String>)

    /** The declarations found and the uses that are neither a declaration nor allowed, over [files] by name. */
    private fun scan(files: Map<String, String>): Scan {
        val declarations = sortedSetOf<String>()
        val uses = mutableListOf<String>()
        for ((file, text) in files.toSortedMap()) {
            for (name in names) {
                for (use in AdapterStatements.wordUses(text, name)) {
                    val before = use.before
                    val plain = before.filterNot(Char::isWhitespace)
                    val isDeclaration = before.endsWith("fun") || before.endsWith("val")
                    when {
                        isDeclaration -> declarations += "$file: $name"
                        allowed.any { it.permits(file, use.owner, name, plain) } -> Unit
                        else -> uses += "$file: $name in ${use.owner}"
                    }
                }
            }
        }
        return Scan(declarations.toList(), uses)
    }

    private fun typeUses(text: String): List<String> =
        internalTypes.filter { type ->
            Regex("""(?<![\p{L}\p{N}_])$type(?![\p{L}\p{N}_])""").containsMatchIn(AdapterStatements.maskedCode(text))
        }

    private fun mainSources(): Map<String, String> = ModuleFiles.mainSources().associate { it.name to it.readText() }

    /** Every `.kt` file under a `src/main` of the phone's code outside this module, by path from the repo root. */
    private fun otherModuleSources(): Map<String, String> {
        val root = ModuleFiles.repoRoot
        val history = ModuleFiles.moduleRoot.canonicalFile
        return File(root, "android").walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && it.canonicalFile != history }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    /** A made-up module holding the five definitions, each replaceable, and an extra file to plant a caller in. */
    private fun model(
        policy: String = "fun isExpired(createdAt: Instant, now: Instant): Boolean =\n" +
            "        RetentionBoundary.isExpired(createdAt.toEpochMilli(), cutoff(now).toEpochMilli())",
        storage: String = "",
        extra: String = "",
    ): Map<String, String> = buildMap {
        put(
            "RetentionBoundary.kt",
            "internal object RetentionBoundary {\n" +
                "    fun isExpired(createdAt: Long, cutoff: Long) = createdAt < cutoff\n}\n",
        )
        put("RetentionPolicy.kt", "internal class RetentionPolicy {\n    $policy\n}\n")
        put(
            "AppPrivateStorage.kt",
            "internal object AppPrivateStorage {\n" +
                "    fun databaseFileName(name: String): String = name\n" +
                "    fun resolve(dir: File, name: String): File {\n" +
                "        val file = File(File(dir, DATABASES_DIR), databaseFileName(name))\n" +
                "        require(isInside(file, dir))\n" +
                "        return file\n" +
                "    }\n" +
                "    fun isInside(candidate: File, root: File): Boolean = candidate.path.startsWith(root.path)\n" +
                "    $storage\n" +
                "    const val DATABASES_DIR: String = \"databases\"\n" +
                "}\n",
        )
        if (extra.isNotEmpty()) put("Extra.kt", "internal class Extra {\n$extra}\n")
    }
}
