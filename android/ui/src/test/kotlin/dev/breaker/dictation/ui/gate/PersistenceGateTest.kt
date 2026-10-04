package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * This module keeps nothing.
 *
 * The app owns the settings store and hands it in; every read and every write goes
 * through it, and there is no second store, no file and no preference of this
 * module's own. The reason is not tidiness: a second copy of the settings is a
 * second answer to the question of what the user chose, and the two would drift.
 *
 * The API key reference is the sharper half of the rule. Whether a key is held is
 * something the screen may say, because that is a fact about the settings; which
 * key it is, is not, because a reference names a secret. So every appearance of
 * the field in the main tree has to be a test of it against null, and nothing
 * else. A line that interpolated the reference would satisfy "no persistence" and
 * still put the reference on the display.
 */

/** The ways a module could keep something, named as the shapes a scan looks for. */
private val STORAGE_SHAPES = mapOf(
    "java.io." to "a file or stream",
    "SharedPreferences" to "a preference store",
    "DataStore" to "a data store",
    "ThemePreferenceStore" to "a preference store of this module's own",
    "File(" to "a file",
    "FileWriter" to "a file writer",
    "FileOutputStream" to "a file stream",
    "getSharedPreferences" to "a preference store",
)

/** The only way the API key reference may be used: compared against nothing. */
private val REFERENCE_TEST = Regex("""apiKeyRef\s*(?:!=|==)\s*null""")

/**
 * Where the API key reference appears in the code of [text], as character offsets.
 *
 * Offsets rather than line numbers, because the only question asked of this list is how
 * many there are: one use per line, or two on one line, is a count, not a location.
 */
private fun referenceUsesIn(text: String): List<Int> =
    Regex("""apiKeyRef""").findAll(withoutCommentsAndStrings(text))
        .map { it.range.first }
        .toList()

/** The line numbers of [text] where the reference is used in some other way. */
private fun bareReferenceLinesIn(text: String): List<Int> {
    val code = withoutCommentsAndStrings(text).lines()
    return code.mapIndexedNotNull { number, line ->
        val uses = REFERENCE_TEST.findAll(line).count()
        val mentions = Regex("""apiKeyRef""").findAll(line).count()
        if (mentions > uses) number + 1 else null
    }
}

/** What [text] breaks about storage, as one entry per line so a message points at the line. */
private fun storageBreaksIn(text: String): List<String> =
    text.lines().mapIndexedNotNull { number, line ->
        val code = withoutCommentsAndStrings(line)
        STORAGE_SHAPES.entries
            .firstOrNull { (shape, _) -> code.contains(shape) }
            ?.let { (_, what) -> "${number + 1}: $what" }
    }

/**
 * No storage of this module's own, and no use of the API key reference beyond
 * asking whether it is there.
 */
class PersistenceGateTest {
    @Test
    fun `no main source reaches for storage`() {
        val found = MAIN_SOURCES.flatMap { (path, text) ->
            storageBreaksIn(text).map { line -> "$path:$line" }
        }
        assertEquals("storage reached for in the main tree: $found", emptyList<String>(), found)
    }

    @Test
    fun `every use of the API key reference asks only whether it is there`() {
        val found = MAIN_SOURCES.flatMap { (path, text) ->
            bareReferenceLinesIn(text).map { line -> "$path:$line" }
        }
        assertEquals("uses of the API key reference that read it: $found", emptyList<String>(), found)
    }

    @Test
    fun `the screen really does report whether a key is held`() {
        // Without this the reference rule would pass on a module that never looked
        // at the field at all, which is a different module from this one.
        val users = MAIN_SOURCES.filter { (_, text) -> referenceUsesIn(text).isNotEmpty() }
        assertTrue("no main source mentions the API key reference", users.isNotEmpty())
        assertEquals(
            "the reference was mentioned on a line that does not test it",
            emptyList<String>(),
            users.flatMap { (path, text) -> bareReferenceLinesIn(text).map { "$path:$it" } },
        )
    }

    @Test
    fun `the module really does write through the store it is given`() {
        // The other half of "keeps nothing": something has to be doing the saving.
        // A module that persisted nowhere and wrote nowhere would satisfy the rule
        // above while not being a settings screen.
        val writers = MAIN_SOURCES.filter { (_, text) -> withoutCommentsAndStrings(text).contains(".save(") }
        assertTrue("no main source calls save on the store", writers.isNotEmpty())
    }

    @Test
    fun `a preference store is caught in a main source`() {
        val control = """
            package dev.breaker.dictation.ui.theme

            private val prefs = context.getSharedPreferences("settings", 0)
        """.trimIndent()
        // trimIndent() drops the blank first and last lines of the raw string, so
        // the call the scan finds is on the third line of the text it reads.
        assertEquals(listOf("3: a preference store"), storageBreaksIn(control))
    }

    @Test
    fun `a data store is caught`() {
        val control = "import androidx.datastore.core.DataStore\n"
        assertEquals(listOf("1: a data store"), storageBreaksIn(control))
    }

    @Test
    fun `a file of this module's own is caught`() {
        val control = """
            package dev.breaker.dictation.ui.write

            private val path = File(context.filesDir, "settings.json")
        """.trimIndent()
        // The scan reads the line the name sits on. No import appears in this text,
        // so the shape reported is the one that names the call, not the package
        // prefix that would have gone with a qualified `java.io.File`.
        assertEquals(listOf("3: a file"), storageBreaksIn(control))
    }

    @Test
    fun `every storage shape is caught by its own name`() {
        // Each name the scan knows is fed to the scan on its own, so a shape added
        // to the table without a matching rule cannot sit there passing.
        for ((shape, what) in STORAGE_SHAPES) {
            val control = "private val held = $shape\n"
            assertEquals(
                "the shape $shape was not caught in \"$control\"",
                listOf("1: $what"),
                storageBreaksIn(control),
            )
        }
    }

    @Test
    fun `a string naming a storage shape is data, not storage`() {
        // A test or a message may talk about these names; what matters is that the
        // module does not open one.
        val control = """
            val note = "SharedPreferences is not used here"
        """.trimIndent()
        assertEquals(emptyList<String>(), storageBreaksIn(control))
    }

    @Test
    fun `a storage shape in a comment is prose`() {
        val control = "// settings go through the store, never through SharedPreferences\n"
        assertEquals(emptyList<String>(), storageBreaksIn(control))
    }

    @Test
    fun `the call that writes the settings is not mistaken for a file`() {
        val control = "settings.save(next)\n"
        assertEquals(emptyList<String>(), storageBreaksIn(control))
    }

    @Test
    fun `reading the API key reference into a string is caught`() {
        val control = "val line = \"API key: ${'$'}{settings.apiKeyRef}\"\n"
        assertEquals(listOf(1), bareReferenceLinesIn(control))
    }

    @Test
    fun `copying the reference into new settings is caught`() {
        val control = "val edited = settings.copy(apiKeyRef = fresh)\n"
        assertEquals(listOf(1), bareReferenceLinesIn(control))
    }

    @Test
    fun `a reference compared against null is allowed, either way round`() {
        assertEquals(
            emptyList<Int>(),
            bareReferenceLinesIn("if (settings.apiKeyRef != null) HELD else NOT_HELD\n"),
        )
        assertEquals(
            emptyList<Int>(),
            bareReferenceLinesIn("val held = settings.apiKeyRef == null\n"),
        )
    }

    @Test
    fun `a reference sent anywhere at all is caught`() {
        val control = "report(settings.apiKeyRef)\n"
        assertEquals(listOf(1), bareReferenceLinesIn(control))
        assertEquals(
            "the reference was not seen at all, so nothing was checked",
            1,
            referenceUsesIn(control).size,
        )
    }

    @Test
    fun `an interpolation hole is read even though the literal around it is not`() {
        // The shape the rule's own KDoc names: the literal is data and is blanked,
        // and the hole inside it is code, so reading the reference through one is
        // still putting the reference on the display.
        val quoted = "val banner = \"Key: ${'$'}{settings.apiKeyRef}\"\n"
        assertEquals("the hole in a quoted literal was blanked with the literal", listOf(1), bareReferenceLinesIn(quoted))
        val raw = "val banner = \"\"\"Key: ${'$'}{settings.apiKeyRef}\"\"\"\n"
        assertEquals("the hole in a raw literal was blanked with the literal", listOf(1), bareReferenceLinesIn(raw))
    }

    @Test
    fun `the plain text of a literal is still not a use`() {
        // The other half of the same rule: a hole is kept because it is code, and
        // literal text is kept invisible because it is data. Keeping either half
        // alone would make the other half pass for the wrong reason.
        val control = "val note = \"apiKeyRef\"\n"
        assertEquals(emptyList<Int>(), bareReferenceLinesIn(control))
        assertEquals(emptyList<Int>(), referenceUsesIn(control))
        val both = "val banner = \"${'$'}{settings.apiKeyRef}\"\nval note = \"apiKeyRef\"\n"
        assertEquals("only the line carrying the hole is a use", listOf(1), bareReferenceLinesIn(both))
    }

    @Test
    fun `a reference named in a comment or a string is not a use`() {
        val control = """
            // the reference itself is never drawn
            val note = "apiKeyRef"
        """.trimIndent()
        assertEquals(emptyList<Int>(), bareReferenceLinesIn(control))
        assertEquals(emptyList<Int>(), referenceUsesIn(control))
    }

    @Test
    fun `two uses on one line are judged together`() {
        // One allowed test and one read on the same line is still a read, and a
        // rule that stopped at the first match would call the line clean.
        val control = "val held = settings.apiKeyRef != null && settings.apiKeyRef.isNotEmpty()\n"
        assertEquals(2, referenceUsesIn(control).size)
        assertEquals(listOf(1), bareReferenceLinesIn(control))
    }

    @Test
    fun `the gate reads the whole main tree, so a breach placed anywhere is seen`() {
        assertTrue("no main source was read", MAIN_SOURCES.size >= 10)
        assertTrue(
            "a main source was read as empty, so a breach in it could not be seen",
            MAIN_SOURCES.all { (_, text) -> text.isNotEmpty() },
        )
        assertTrue(
            "the screen model, which is where a screen would be built, was not read",
            MAIN_SOURCES.any { (path, _) -> path == "screen/Screen.kt" },
        )
    }
}
