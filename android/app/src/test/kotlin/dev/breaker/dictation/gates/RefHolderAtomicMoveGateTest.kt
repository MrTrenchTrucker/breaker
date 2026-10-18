package dev.breaker.dictation.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the credential reference holder's move in source text, because no JVM test can tell an atomic
 * rename from a plain replace in one folder. The holder moves its temp file over the live file with
 * exactly one `Files.move`, and that move passes `StandardCopyOption.ATOMIC_MOVE`. There is no
 * fallback: no `REPLACE_EXISTING` and no catch of `AtomicMoveNotSupportedException`, so a refused move
 * throws. The write and the move run once inside `synchronized(lock)`, where `lock` is the
 * companion's private val, so two holders never interleave. The rules read the real file with
 * comments and literal text removed by the shared scanner; each rule holds on the real file, is
 * broken by at least two edited samples, and stays quiet on harmless edits; an edit whose target
 * text is missing fails by name.
 */
internal class RefHolderAtomicMoveGateTest {

    private val holder: String = "kotlin/dev/breaker/dictation/FileCredentialRefHolder.kt"

    private class Rule(val name: String, val holds: (String) -> Boolean)
    private class Sample(val rule: String, val old: String, val new: String)
    private class Quiet(val old: String, val new: String)

    private val atomicMove: String = "Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)"

    private val rules: List<Rule> = listOf(
        Rule("THE_MOVE_PASSES_ATOMIC_MOVE") { code ->
            Regex(
                """\bFiles\s*\.\s*move\s*\(\s*from\s*\.\s*toPath\s*\(\s*\)\s*,\s*to\s*\.\s*toPath\s*\(\s*\)\s*,\s*StandardCopyOption\s*\.\s*ATOMIC_MOVE\s*\)""",
            ).findAll(code).count() == 1
        },
        Rule("THERE_IS_ONE_MOVE") { code ->
            Regex("""\bFiles\s*\.\s*move\s*\(""").findAll(code).count() == 1
        },
        Rule("THERE_IS_NO_NON_ATOMIC_FALLBACK") { code ->
            Regex("""\bREPLACE_EXISTING\b""").findAll(code).count() == 0 &&
                Regex("""\bAtomicMoveNotSupportedException\b""").findAll(code).count() == 0
        },
        Rule("THE_WRITE_AND_MOVE_RUN_UNDER_THE_COMPANION_LOCK") { code ->
            Regex("""\bsynchronized\s*\(""").findAll(code).count() == 1 &&
                Regex(
                    """\bsynchronized\s*\(\s*lock\s*\)\s*\{[\s\S]*?\bwriteLine\s*\(\s*temp\s*,\s*ref\s*\)\s*moveOver\s*\(\s*temp\s*,\s*file\s*\)""",
                ).findAll(code).count() == 1 &&
                Regex("""\bcompanion\s+object\s*\{[\s\S]*\bprivate\s+val\s+lock\s*:\s*Any\s*=\s*Any\s*\(\s*\)""").findAll(code).count() == 1
        },
    )

    private val firing: List<Sample> = listOf(
        Sample("THE_MOVE_PASSES_ATOMIC_MOVE", "StandardCopyOption.ATOMIC_MOVE)", "StandardCopyOption.REPLACE_EXISTING)"),
        Sample("THE_MOVE_PASSES_ATOMIC_MOVE", atomicMove, "Files.move(from.toPath(), to.toPath())"),
        Sample("THE_MOVE_PASSES_ATOMIC_MOVE", atomicMove, "Files.copy(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)"),
        Sample("THERE_IS_ONE_MOVE", atomicMove, "Files.move(from.toPath(), to.toPath()); $atomicMove"),
        Sample("THERE_IS_ONE_MOVE", atomicMove, "Files.copy(from.toPath(), to.toPath())"),
        Sample(
            "THERE_IS_NO_NON_ATOMIC_FALLBACK", atomicMove,
            "try { $atomicMove } catch (e: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }",
        ),
        Sample("THERE_IS_NO_NON_ATOMIC_FALLBACK", "StandardCopyOption.ATOMIC_MOVE)", "StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)"),
        Sample("THE_WRITE_AND_MOVE_RUN_UNDER_THE_COMPANION_LOCK", "synchronized(lock) {", "run {"),
        Sample("THE_WRITE_AND_MOVE_RUN_UNDER_THE_COMPANION_LOCK", "synchronized(lock)", "synchronized(this)"),
        Sample("THE_WRITE_AND_MOVE_RUN_UNDER_THE_COMPANION_LOCK", "private val lock: Any = Any()", "val lock: Any = Any()"),
    )

    private val quiet: List<Quiet> = listOf(
        Quiet(atomicMove, "Files.move( from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE )"),
        Quiet(atomicMove, "Files\n                .move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)"),
        Quiet("synchronized(lock) {", "synchronized( lock ) {"),
        Quiet("private const val TEMP_SUFFIX = \".tmp\"", "private const val TEMP_SUFFIX = \".partial\""),
    )

    /** [old] replaced by [new] at its first place; fails by name when it is not there. */
    private fun edit(text: String, old: String, new: String): String {
        val at = text.indexOf(old)
        check(at >= 0) { "app: a ref holder move gate sample lost its text '$old'" }
        return text.substring(0, at) + new + text.substring(at + old.length)
    }

    private fun codeOf(old: String? = null, new: String = ""): String {
        val text: String = AppSourceFiles.mainFile(holder)
        return AppSourceFiles.strip(if (old == null) text else edit(text, old, new)).code
    }

    @Test
    fun `every rule holds on the real ref holder file`() {
        assertTrue("app: the ref holder was read as empty", codeOf().isNotBlank())
        for (rule in rules) {
            assertTrue("app: the ref holder breaks rule ${rule.name}", rule.holds(codeOf()))
        }
    }

    @Test
    fun `every rule fires on each of its edited samples`() {
        val byName = rules.associateBy { it.name }
        for (sample in firing) {
            val rule = byName.getValue(sample.rule)
            assertFalse(
                "app: ref holder move rule ${sample.rule} must fire on '${sample.old}' => '${sample.new}'",
                rule.holds(codeOf(sample.old, sample.new)),
            )
        }
    }

    @Test
    fun `each rule has at least two firing samples and no sample names an unknown rule`() {
        val names = rules.map { it.name }
        assertEquals("app: a ref holder move rule name is used twice", names.toSet().size, names.size)
        assertEquals("app: a firing sample belongs to an unknown rule or a rule has none", names.toSet(), firing.map { it.rule }.toSet())
        for (rule in rules) {
            val count = firing.count { it.rule == rule.name }
            assertTrue("app: ref holder move rule ${rule.name} needs at least two firing samples, has $count", count >= 2)
        }
    }

    @Test
    fun `no rule fires on a harmless edit`() {
        for (q in quiet) {
            for (rule in rules) {
                assertTrue("app: ref holder move rule ${rule.name} fired on the harmless edit '${q.old}'", rule.holds(codeOf(q.old, q.new)))
            }
        }
    }

    @Test
    fun `an edit whose target text is missing fails by name`() {
        val error = assertThrows(IllegalStateException::class.java) { codeOf("no such text in the holder", "") }
        assertTrue("app: the missing-text failure must name the text", error.message.orEmpty().contains("no such text in the holder"))
    }
}
