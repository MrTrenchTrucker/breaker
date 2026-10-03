package dev.breaker.dictation.history

import android.content.Context
import dev.breaker.dictation.core.port.Clock
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module card, the registry and the retention docs say what this module
 * exposes and what rule it follows, so they have to keep saying it.
 *
 * The repository's own consistency checks look at the card's structure (its
 * sections, its dependency list, the name of its contract test). Nothing there
 * compares what the registry's `public` line or the card says with the code, so
 * they stay green over a card whose content has drifted from it. These tests
 * compare the card and the registry with the code itself, and each claim is
 * looked for in the section of the card that makes it.
 */
class CardMatchesCodeTest {

    private val card = ModuleFiles.moduleRoot.resolve("AGENTS.md").readText()
    private val registry = ModuleFiles.repoFile("modules.toml").readText()

    // A top-level declaration is read from the source text, because Kotlin's
    // `internal` on a class is invisible in bytecode. It starts at the start of a
    // line: annotations on the same line, then the modifiers in any order, then the
    // keyword. It is public unless a modifier says `internal` or `private`. An
    // indented line is a member or a nested type, and is not read.
    private val annotations = """(?:@[\w.:]+(?:\((?:[^()\n]|\([^()\n]*\))*\))?[ \t]+)*"""
    private val modifierWords = "public|private|internal|inline|suspend|tailrec|operator|infix|const|external|" +
        "data|open|abstract|sealed|final|enum|annotation|fun|value|lateinit|expect|actual"
    private val declaration = Regex(
        """^$annotations((?:(?:$modifierWords)\s+)*)(class|interface|object|fun|val|var|typealias)\b(?:[ \t]+(\w+))?""",
        RegexOption.MULTILINE,
    )

    private class Declaration(val modifiers: Set<String>, val keyword: String, val name: String) {
        val isType: Boolean get() = keyword == "class" || keyword == "interface" || keyword == "object"
        val isPublic: Boolean get() = "internal" !in modifiers && "private" !in modifiers
    }

    private fun declarationsIn(source: String): List<Declaration> = declaration.findAll(source).map { match ->
        Declaration(
            modifiers = match.groupValues[1].split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet(),
            keyword = match.groupValues[2],
            name = match.groupValues[3],
        )
    }.toList()

    private fun typesIn(source: String, publicOnly: Boolean): List<String> =
        declarationsIn(source).filter { it.isType && (it.isPublic || !publicOnly) }.map { it.name }

    private fun membersIn(source: String, publicOnly: Boolean): List<String> =
        declarationsIn(source)
            .filter { !it.isType && (it.isPublic || !publicOnly) }
            .map { "${it.keyword} ${it.name}".trim() }

    private fun declaredTypes(publicOnly: Boolean): List<String> =
        ModuleFiles.mainSources().flatMap { typesIn(it.readText(), publicOnly) }.sorted()

    /** [text] with every run of whitespace, line breaks included, as one space, so wrapping cannot change a match. */
    private fun collapsed(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

    /** The card text from the line starting with [start] up to the line starting with [end]. */
    private fun cardSection(start: String, end: String?): String {
        val from = card.indexOf("\n$start")
        assertTrue("the card has no section starting with $start", from >= 0)
        if (end == null) return card.substring(from)
        val to = card.indexOf("\n$end", from + 1)
        assertTrue("the card has no section starting with $end after $start", to > from)
        return card.substring(from, to)
    }

    private fun bullets(section: String): List<String> =
        section.split(Regex("""\n(?=- )""")).map { it.trim() }.filter { it.startsWith("- ") }

    private fun mentions(text: String, word: String): Boolean =
        Regex("""\b${Regex.escape(word)}\b""").containsMatchIn(text)

    /** The `public = "..."` value in this module's registry entry. */
    private fun registryPublic(): String {
        val start = registry.indexOf("[module.android_history]")
        assertTrue("modules.toml has no [module.android_history] entry", start >= 0)
        val end = registry.indexOf("\n[", start + 1).let { if (it < 0) registry.length else it }
        val line = registry.substring(start, end).lines().first { it.startsWith("public") }
        return line.substringAfter("\"").substringBeforeLast("\"")
    }

    // ── what the module exposes ──────────────────────────────────────────

    @Test
    fun `the public types are exactly the two the card and the registry list`() {
        val everything = declaredTypes(publicOnly = false)
        // A scan that finds nothing would let this pass over an empty module.
        for (internalType in listOf("RetentionPolicy", "HistorySql", "SqliteHistoryDatabase", "Tombstone")) {
            assertTrue("the scan did not see $internalType", internalType in everything)
        }

        val shipped = declaredTypes(publicOnly = true)
        assertEquals(listOf("PurgeReport", "SqliteHistoryStore"), shipped)
        assertEquals(
            "no public top-level function, property or alias",
            emptyList<String>(),
            ModuleFiles.mainSources().flatMap { membersIn(it.readText(), publicOnly = true) },
        )

        val interfaceSection = cardSection("## Public Interface", "**Internal (not public):**")
        val entries = bullets(interfaceSection)
        assertEquals("one line per public type", shipped.size, entries.size)
        for (type in shipped) {
            assertEquals(
                "the Public Interface section gives $type one line",
                1,
                entries.count { it.contains("`$type`") },
            )
        }
        assertTrue(
            "the store's line says how it is built",
            entries.single { it.contains("`SqliteHistoryStore`") }
                .contains("SqliteHistoryStore.create(context, clock)"),
        )

        val registered = registryPublic()
        for (type in shipped) {
            assertTrue("modules.toml public does not name $type: $registered", mentions(registered, type))
        }
        val leaked = everything.filterNot { it in shipped }.filter { mentions(registered, it) }
        assertEquals("modules.toml public names internal types", emptyList<String>(), leaked)
    }

    @Test
    fun `the card names every type the module declares`() {
        val missing = declaredTypes(publicOnly = false).filterNot { mentions(card, it) }
        assertTrue("the card does not name these types: $missing", missing.isEmpty())
    }

    @Test
    fun `the store has no public constructor route, and a small public surface`() {
        val store = SqliteHistoryStore::class.java
        val publicMethods = store.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
        assertEquals(
            sortedSetOf("delete", "list", "purgeExpired", "save"),
            publicMethods.map { it.name }.toSortedSet(),
        )
        val purges = publicMethods.filter { it.name == "purgeExpired" }
        assertEquals("exactly one public purgeExpired", 1, purges.size)
        val purge = purges.single()
        assertEquals("purgeExpired() takes nothing: the clock decides when", 0, purge.parameterCount)
        assertEquals(PurgeReport::class.java, purge.returnType)

        val create = SqliteHistoryStore.Companion::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name == "create" }
        assertEquals("one public way to build a store", 1, create.size)
        assertEquals(
            "create takes the Android context and the clock, in that order",
            listOf(Context::class.java, Clock::class.java),
            create.single().parameterTypes.toList(),
        )

        val source = ModuleFiles.mainSources().single { it.name == "SqliteHistoryStore.kt" }.readText()
        assertTrue(
            "the constructor must be internal",
            source.contains("class SqliteHistoryStore internal constructor("),
        )
    }

    // ── the surface scan can go red: planted mistakes ────────────────────

    @Test
    fun `the surface scan flags every way of writing a public top-level type`() {
        val mistakes = mapOf(
            "no modifier at all" to "class Leak",
            "an explicit public" to "public class Leak",
            "an explicit public object" to "public object Leak",
            "an annotation on the same line" to "@Keep class Leak",
            "an annotation with an argument" to "@Suppress(\"unused\") class Leak",
            "two annotations and public" to "@Keep @Suppress(\"unused\") public class Leak",
            "a data class" to "data class Leak(val a: Int)",
            "a public data class" to "public data class Leak(val a: Int)",
            "an open class" to "open class Leak",
            "an abstract class" to "abstract class Leak",
            "a sealed interface" to "sealed interface Leak",
            "an enum" to "enum class Leak { A }",
            "a functional interface" to "fun interface Leak { fun run() }",
            "a value class" to "@JvmInline value class Leak(val v: Int)",
            "an annotation class" to "annotation class Leak",
            "a modifier on its own line" to "public\nclass Leak",
        )
        for ((label, source) in mistakes) {
            assertEquals("the scan let a public type through as $label: $source", listOf("Leak"), typesIn(source, true))
        }
    }

    @Test
    fun `the surface scan flags every way of writing a public top-level function, property or alias`() {
        val mistakes = mapOf(
            "no modifier at all" to "fun leak() {}",
            "an explicit public" to "public fun leak() {}",
            "inline" to "inline fun leak() {}",
            "suspend" to "suspend fun leak() {}",
            "tailrec" to "tailrec fun leak() {}",
            "operator" to "operator fun String.unaryPlus() = this",
            "infix" to "infix fun Int.leak(other: Int) = this",
            "external" to "external fun leak()",
            "a generic function" to "fun <T> leak(value: T) {}",
            "an extension function" to "fun String.leak() {}",
            "a public val" to "public val leak = 1",
            "a val" to "val leak = 1",
            "a var" to "var leak = 1",
            "a const val" to "const val LEAK = 1",
            "a public const val" to "public const val LEAK = 1",
            "a lateinit var" to "lateinit var leak: String",
            "a type alias" to "typealias Leak = String",
            "a public type alias" to "public typealias Leak = String",
            "an annotation on the same line" to "@Suppress(\"unused\") fun leak() {}",
            "an annotation and public inline" to "@JvmName(\"x\") public inline fun leak() {}",
        )
        for ((label, source) in mistakes) {
            assertEquals(
                "the scan let a public member through as $label: $source",
                1,
                membersIn(source, publicOnly = true).size,
            )
        }
    }

    @Test
    fun `the surface scan leaves internal and private declarations, comments and members alone`() {
        val types = mapOf(
            "internal" to "internal class Kept",
            "private" to "private class Kept",
            "an internal object" to "internal object Kept",
            "an internal data class" to "internal data class Kept(val a: Int)",
            "an internal interface" to "internal interface Kept",
            "an internal functional interface" to "internal fun interface Kept { fun run() }",
            "an internal sealed class" to "internal sealed class Kept",
            "an annotated internal class" to "@Keep internal class Kept",
            "an annotated private class" to "@Suppress(\"unused\") private class Kept",
            "modifiers in the other order" to "data internal class Kept(val a: Int)",
            "a modifier on its own line" to "internal\nclass Kept",
            "words in a comment" to
                "/**\n * public class Leak and fun leak() are words here.\n */\ninternal class Kept",
            "words in a line comment" to "// public class Leak\ninternal class Kept",
            "a nested declaration" to
                "internal class Kept {\n    class Nested\n    fun member() {}\n    val leak = 1\n}",
        )
        for ((label, source) in types) {
            // The scan must see the declaration, or "not flagged" would only mean "not seen".
            assertEquals("the scan did not see the type in $label: $source", listOf("Kept"), typesIn(source, false))
            assertEquals("the scan flagged $label: $source", emptyList<String>(), typesIn(source, true))
            assertEquals("the scan flagged a member in $label: $source", emptyList<String>(), membersIn(source, true))
        }

        val members = mapOf(
            "internal" to "internal fun kept() {}",
            "private" to "private fun kept() {}",
            "an internal inline function" to "internal inline fun kept() {}",
            "modifiers in the other order" to "inline internal fun kept() {}",
            "an internal suspend function" to "internal suspend fun kept() {}",
            "a private extension function" to "private fun String.kept() {}",
            "an internal val" to "internal val kept = 1",
            "a private val" to "private val kept = 1",
            "a private const val" to "private const val KEPT = 1",
            "an internal lateinit var" to "internal lateinit var kept: String",
            "an internal type alias" to "internal typealias Kept = String",
            "an annotated internal function" to "@Suppress(\"unused\") internal fun kept() {}",
        )
        for ((label, source) in members) {
            assertEquals("the scan did not see the member in $label: $source", 1, membersIn(source, false).size)
            assertEquals("the scan flagged $label: $source", emptyList<String>(), membersIn(source, true))
        }
    }

    // ── the tests find their files, or say where they looked ─────────────

    @Test
    fun `a root that cannot be found is reported by the folder the search started from`() {
        val from = File("no-such-folder/below/here")
        val failure = assertThrows(IllegalStateException::class.java) {
            ModuleFiles.findUp(from, "a marker that is nowhere") { false }
        }
        assertTrue(
            "the message must name the folder: ${failure.message}",
            failure.message.orEmpty().contains(from.path),
        )
        assertTrue(
            "the message must say what was looked for: ${failure.message}",
            failure.message.orEmpty().contains("a marker that is nowhere"),
        )
        assertEquals("the nearest match wins", from, ModuleFiles.findUp(from, "anything") { true })
        assertEquals(from.parentFile, ModuleFiles.findUp(from, "the parent") { it == from.parentFile })
    }

    // ── what the card says about how it is built ─────────────────────────

    @Test
    fun `the card says SQLite through the platform API and no Room, and that is what the code does`() {
        assertTrue(
            "the card must say the storage is the Android platform API",
            card.contains("SQLite via the Android platform API (android.database.sqlite), no Room dependency"),
        )
        assertFalse(
            "the card names Room other than to rule it out",
            Regex("""Room""").findAll(card).any {
                !card.substring(maxOf(0, it.range.first - 3), it.range.last + 1).startsWith("no ")
            },
        )
        val adapter = ModuleFiles.mainSources().single { it.name == "SqliteHistoryDatabase.kt" }.readText()
        assertTrue(adapter.contains("import android.database.sqlite.SQLiteOpenHelper"))
        assertFalse("androidx.room is used", ModuleFiles.mainSources().any { it.readText().contains("androidx.room") })
        val script = ModuleFiles.moduleRoot.resolve("build.gradle.kts").readText()
        assertFalse("the build script pulls in Room", Regex("""(?i)\broom\b|androidx\.room""").containsMatchIn(script))
    }

    @Test
    fun `the card's Test Locations name only paths that exist`() {
        val section = cardSection("## Test Locations", "## Test Requirement")
        val paths = Regex("""`([^`]+)`""").findAll(section).map { it.groupValues[1] }.toList()
        assertTrue("the section names the contract test", "tests/contract/test_history_contract.py" in paths)
        assertTrue(
            "the section names the module's own test folder",
            paths.any { it.startsWith("android/modules/history/src/test") },
        )
        val missing = paths.filterNot { ModuleFiles.repoFile(it).exists() }
        assertTrue("the card names paths that do not exist: $missing", missing.isEmpty())
    }

    // ── the retention rule is written down, and the docs point at it ─────

    @Test
    fun `the card, the ADR and the server card all carry the one retention rule`() {
        val invariants = cardSection("## Invariants", "## Depends On")
        val gotchas = cardSection("## Known Gotchas", null)
        assertTrue("Invariants must point at the rule", invariants.contains("ADR-010 precise rule"))
        assertTrue("Known Gotchas must point at the rule", gotchas.contains("ADR-010 precise rule"))
        assertTrue(
            "the scheduler gotcha must be on the card",
            gotchas.contains(
                "`purgeExpired()` is to be called by the app's scheduler (android/app); " +
                    "nothing calls it until the app shell wires it.",
            ),
        )
        assertFalse("the card still reads as a calendar rule", card.contains("calendar month"))
        // The strings the card carried before it was corrected, looked for with the
        // line wrapping taken out so a re-wrapped paragraph cannot hide them.
        val flatCard = collapsed(card)
        for (old in listOf("older than 3 months", "older than **3 months**", "**3 months** (TTL)")) {
            assertFalse("the card still says '$old'", flatCard.contains(old))
        }

        val adr = ModuleFiles.repoFile("decisions/ADR-010-retention.md").readText()
        val decisionStart = adr.indexOf("## Decision")
        assertTrue("the ADR has no '## Decision' heading", decisionStart >= 0)
        val decisionEnd = adr.indexOf("## Reasons", decisionStart)
        assertTrue("the ADR has no '## Reasons' heading after its Decision", decisionEnd > decisionStart)
        val decision = collapsed(adr.substring(decisionStart, decisionEnd))
        val preciseRule = listOf(
            "**Precise rule (phone and server identical):** a transcription expires when its `created_at` instant " +
                "is more than 90 days (90 x 24 h) before the purge's current instant. A row exactly at the cutoff " +
                "is kept.",
            "Both sides compute on UTC instants, with no time zone and no calendar months,",
            "\"3 months\" in this ADR and in F28 means this rule.",
        )
        for (part in preciseRule) {
            assertTrue("the ADR's Decision must carry the precise rule: $part", decision.contains(collapsed(part)))
        }

        val syncApi = ModuleFiles.repoFile("server/modules/sync-api/AGENTS.md").readText()
        val retentionLine = syncApi.lines().single { it.startsWith("- Retention:") }
        assertTrue(
            "the server's retention line must point at the rule: $retentionLine",
            retentionLine.contains("ADR-010 precise rule"),
        )
    }
}
