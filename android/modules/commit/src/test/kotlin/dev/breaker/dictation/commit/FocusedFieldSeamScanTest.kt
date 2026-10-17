package dev.breaker.dictation.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public seam a text-insert mechanism built as its own Gradle module
 * reaches across (ADR-022): exactly `FieldCommit`, `FocusedField` and
 * `FocusedFieldHolder.publish(field: FocusedField): AutoCloseable`, and
 * nothing more.
 *
 * This reads the three main source files as text (through [SourceFiles],
 * comments and literals stripped) rather than reflection, so it fails loudly
 * the moment a visibility modifier changes, whether or not the module
 * compiles against any consumer yet.
 */
internal class FocusedFieldSeamScanTest {

    private fun fileOf(name: String): String =
        SourceFiles.mainSources()[name] ?: error("commit: $name is missing from the main folder")

    private fun codeOf(name: String): String = SourceFiles.strip(fileOf(name)).code

    @Test
    fun `FieldCommit is a public enum`() {
        val code = codeOf("FocusedField.kt")
        assertTrue(
            "commit: FieldCommit must be declared as an enum class",
            Regex("""\benum\s+class\s+FieldCommit\b""").containsMatchIn(code),
        )
        assertFalse(
            "commit: FieldCommit must not be internal; a text-insert mechanism built as its own module has to report what a field did with its text",
            Regex("""\binternal\s+enum\s+class\s+FieldCommit\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `FocusedField is a public interface`() {
        val code = codeOf("FocusedField.kt")
        assertTrue(
            "commit: FocusedField must be declared as an interface",
            Regex("""\binterface\s+FocusedField\b""").containsMatchIn(code),
        )
        assertFalse(
            "commit: FocusedField must not be internal; a text-insert mechanism built as its own Gradle module has to implement it",
            Regex("""\binternal\s+interface\s+FocusedField\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `FocusedFieldSource stays internal`() {
        val code = codeOf("FocusedField.kt")
        assertTrue(
            "commit: FocusedFieldSource must stay internal; only the commit service inside this module reads the current field",
            Regex("""\binternal\s+fun\s+interface\s+FocusedFieldSource\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `FocusedFieldRegistry stays internal`() {
        val code = codeOf("FocusedFieldRegistry.kt")
        assertTrue(
            "commit: FocusedFieldRegistry must stay internal; nothing outside this module may reach the registry directly",
            Regex("""\binternal\s+class\s+FocusedFieldRegistry\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `FocusedFieldHolder is a public object`() {
        val code = codeOf("adapter/FocusedFieldHolder.kt")
        assertTrue(
            "commit: FocusedFieldHolder must be declared as an object",
            Regex("""\bobject\s+FocusedFieldHolder\b""").containsMatchIn(code),
        )
        assertFalse(
            "commit: FocusedFieldHolder must not be internal; a text-insert mechanism built as its own Gradle module has to publish into it",
            Regex("""\binternal\s+object\s+FocusedFieldHolder\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `the holder's registry property stays internal`() {
        val code = codeOf("adapter/FocusedFieldHolder.kt")
        assertTrue(
            "commit: FocusedFieldHolder.registry must stay internal; the public seam is publish(field), never the registry itself",
            Regex("""\binternal\s+val\s+registry\s*:""").containsMatchIn(code),
        )
    }

    @Test
    fun `the holder has exactly one public member, publish returning AutoCloseable`() {
        val code = codeOf("adapter/FocusedFieldHolder.kt")
        val members: List<MatchResult> = Regex("""\b(internal\s+)?(val|fun)\s+(\w+)""").findAll(code).toList()
        val publicMembers: List<MatchResult> = members.filter { it.groupValues[1].isBlank() }
        assertEquals(
            "commit: the holder must have exactly one public member; a second one widens the seam past what the text-insert mechanism needs",
            1,
            publicMembers.size,
        )
        assertEquals(
            "commit: the holder's one public member must be named 'publish'",
            "publish",
            publicMembers.single().groupValues[3],
        )
        assertTrue(
            "commit: publish must be exactly fun publish(field: FocusedField): AutoCloseable",
            Regex("""\bfun\s+publish\s*\(\s*field\s*:\s*FocusedField\s*\)\s*:\s*AutoCloseable\b""").containsMatchIn(code),
        )
        assertEquals(
            "commit: the holder must have exactly one internal member (registry); an extra one is a seam nobody reasoned about",
            1,
            members.size - publicMembers.size,
        )
    }

    @Test
    fun `the scan sees a widened seam on a sample that adds a second public member`() {
        val widened = """
            object Sample {
                internal val registry: Any = Any()
                fun publish(field: FocusedField): AutoCloseable = TODO()
                fun current(): FocusedField? = TODO()
            }
        """.trimIndent()
        val members: List<MatchResult> = Regex("""\b(internal\s+)?(val|fun)\s+(\w+)""").findAll(SourceFiles.strip(widened).code).toList()
        val publicMembers: List<MatchResult> = members.filter { it.groupValues[1].isBlank() }
        assertEquals("commit: the sample must be seen to carry two public members", 2, publicMembers.size)
    }

    @Test
    fun `the scan sees an accidentally public registry on a sample`() {
        val sample = "object Sample {\n    val registry: Any = Any()\n}\n"
        val members: List<MatchResult> = Regex("""\b(internal\s+)?(val|fun)\s+(\w+)""").findAll(SourceFiles.strip(sample).code).toList()
        assertTrue("commit: the scan must see the registry member at all", members.isNotEmpty())
        assertTrue(
            "commit: a registry with no internal modifier must be seen as public",
            members.single().groupValues[1].isBlank(),
        )
    }
}
