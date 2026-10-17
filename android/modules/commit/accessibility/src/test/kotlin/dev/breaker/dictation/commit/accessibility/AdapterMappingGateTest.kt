package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No JVM test runs `adapter/AndroidFieldNode.kt`, so the line that feeds each [FieldNode]
 * member is held by a text gate: "never into a password field" rests on the one line that
 * reads the platform's password flag, and a swapped selection value or a swapped action would
 * pass every behaviour test.
 *
 * The gate reads code only (comments and literal text are removed, layout is ignored) and
 * requires, for every member of the node class, exactly one declaration equal to the pinned
 * text in [MappingExpected]; the finder's one function is pinned the same way. A missing,
 * repeated, extra or different member is reported by its name. The service file is pinned the
 * same way, whole; its imports are left to the adapter gate.
 */
internal object MappingGate {

    /** A class or interface found in squashed code: its header, its body and where it sits. */
    class Part(val header: String, val body: String, val from: Int, val to: Int)

    private val modifiers: Set<String> = setOf("override", "private", "internal", "public", "protected", "open", "abstract", "lateinit", "const")
    private val starts: Set<String> = modifiers + setOf("val", "var", "fun", "init", "constructor", "companion", "object", "class", "interface", "typealias")
    private val nameOf = Regex("""\b(?:val|var|fun) ([A-Za-z_]\w*)""")
    private val declaration = Regex("""\b(fun|val|var|class|object|interface|init|typealias)\b""")

    private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** [code] without layout: a single space survives only between two word characters. */
    fun squash(code: String): String = buildString {
        var gap = false
        for (c in code) {
            if (c.isWhitespace()) {
                gap = true
            } else {
                if (gap && isNotEmpty() && isWord(this[length - 1]) && isWord(c)) {
                    append(' ')
                }
                gap = false
                append(c)
            }
        }
    }

    /** The declaration that starts at [marker] in squashed [code], or null when there is none. */
    fun partOf(code: String, marker: String): Part? {
        val at: Int = code.indexOf(marker)
        if (at < 0) {
            return null
        }
        val open: Int = code.indexOf('{', at)
        if (open < 0) {
            return null
        }
        var depth = 0
        var close = -1
        var i: Int = open
        while (i < code.length && close < 0) {
            if (code[i] == '{') {
                depth += 1
            } else if (code[i] == '}') {
                depth -= 1
                if (depth == 0) {
                    close = i
                }
            }
            i += 1
        }
        if (close < 0) {
            return null
        }
        var from: Int = at
        while (true) {
            val word: String = modifiers.firstOrNull { from > it.length && code.startsWith(it + " ", from - it.length - 1) } ?: break
            from -= word.length + 1
        }
        return Part(code.substring(from, open), code.substring(open + 1, close), from, close + 1)
    }

    private fun hasContent(prefix: String): Boolean = prefix.split(' ').any { it.isNotEmpty() && it !in modifiers }

    /** The members of a squashed class body: a new one starts at each top-level modifier or keyword that is not just a modifier of the previous word. */
    fun members(body: String): List<String> {
        val result: MutableList<String> = ArrayList()
        var depth = 0
        var start = 0
        var i = 0
        while (i < body.length) {
            val c: Char = body[i]
            if (c == '{' || c == '(') {
                depth += 1
            } else if (c == '}' || c == ')') {
                depth -= 1
            } else if (depth == 0 && isWord(c) && (i == 0 || !isWord(body[i - 1]))) {
                var end: Int = i
                while (end < body.length && isWord(body[end])) {
                    end += 1
                }
                if (body.substring(i, end) in starts && hasContent(body.substring(start, i))) {
                    result.add(body.substring(start, i).trim())
                    start = i
                }
                i = end
                continue
            }
            i += 1
        }
        if (start < body.length) {
            result.add(body.substring(start).trim())
        }
        return result
    }

    private fun checkPart(part: Part?, header: String, table: Map<String, String>, label: String, bad: MutableList<String>) {
        if (part == null) {
            bad.add(label + " (missing)")
            return
        }
        if (part.header != squash(header)) {
            bad.add(label + " header")
        }
        val seen: MutableList<String> = ArrayList()
        for (chunk in members(part.body)) {
            val name: String = nameOf.find(chunk)?.groupValues?.get(1) ?: "(unnamed)"
            seen.add(name)
            val want: String? = table[name]
            if (want == null || chunk != squash(want)) {
                bad.add(name)
            }
        }
        for (name in table.keys) {
            val count: Int = seen.count { it == name }
            if (count == 0) {
                bad.add(name + " (missing)")
            } else if (count > 1) {
                bad.add(name + " (repeated)")
            }
        }
    }

    /** What is wrong with the node file's [source], by member name; empty when every mapping is the pinned one. */
    fun problems(source: String): List<String> {
        val code: String = squash(SourceFiles.strip(source).code)
        val bad: MutableList<String> = ArrayList()
        val node: Part? = partOf(code, "class AndroidFieldNode(")
        val finder: Part? = partOf(code, "class AndroidNodeFinder(")
        checkPart(node, MappingExpected.NODE_HEADER, MappingExpected.node, "AndroidFieldNode", bad)
        checkPart(finder, MappingExpected.FINDER_HEADER, MappingExpected.finder, "AndroidNodeFinder", bad)
        var rest = ""
        var at = 0
        for (part in listOfNotNull(node, finder).sortedBy { it.from }) {
            if (part.from >= at) {
                rest += code.substring(at, part.from)
                at = part.to
            }
        }
        rest += code.substring(at)
        if (declaration.containsMatchIn(rest)) {
            bad.add("top level")
        }
        return bad
    }

    /** What is wrong with the service file's [source], by member name; empty when the whole class is the pinned one. */
    fun serviceProblems(source: String): List<String> {
        val code: String = squash(SourceFiles.strip(source).code)
        val bad: MutableList<String> = ArrayList()
        val service: Part? = partOf(code, "class BreakerAccessibilityService")
        checkPart(service, MappingExpected.SERVICE_HEADER, MappingExpected.service, "BreakerAccessibilityService", bad)
        if (service != null && declaration.containsMatchIn(code.substring(0, service.from) + code.substring(service.to))) {
            bad.add("top level")
        }
        return bad
    }

    /** The sorted member names of the node seam, read from the seam file's [source]; null when the interface is not there. */
    fun seamMembers(source: String): List<String>? {
        val part: Part = partOf(squash(SourceFiles.strip(source).code), "interface FieldNode{") ?: return null
        return Regex("""\b(?:val|fun) ([A-Za-z_]\w*)""").findAll(part.body).map { it.groupValues[1] }.sorted().toList()
    }
}

internal class AdapterMappingGateTest {

    private val nodeFile: String = "adapter/AndroidFieldNode.kt"
    private val seamFile: String = "FieldNode.kt"
    private val serviceFile: String = "adapter/BreakerAccessibilityService.kt"

    private fun real(path: String): String {
        val text: String? = SourceFiles.mainSources()[path]
        assertNotNull("commit/accessibility: the mapping gate did not find " + path, text)
        val source: String = text ?: ""
        assertTrue("commit/accessibility: the mapping gate found an empty " + path, source.isNotEmpty())
        return source
    }

    @Test
    fun `every member of the real node file maps to its one pinned framework call`() {
        val found: List<String> = MappingGate.problems(real(nodeFile))
        assertEquals("commit/accessibility: AndroidFieldNode.kt differs from the pinned mapping at: " + found, emptyList<String>(), found)
    }

    @Test
    fun `the pinned table holds exactly the members of the node seam`() {
        val seam: List<String>? = MappingGate.seamMembers(real(seamFile))
        assertNotNull("commit/accessibility: the node seam interface was not found in FieldNode.kt", seam)
        assertEquals(
            "commit/accessibility: the pinned device mappings and the members of FieldNode differ",
            seam,
            MappingExpected.node.keys.sorted(),
        )
        assertEquals("commit/accessibility: the seam should have thirteen members", 13, seam?.size)
        assertEquals(
            "commit/accessibility: the finder table should pin exactly findInputFocus",
            listOf("findInputFocus"),
            MappingExpected.finder.keys.toList(),
        )
    }

    @Test
    fun `an edited copy that breaks one mapping is reported by the name of that member`() {
        for (variant in MappingSamples.firing(real(nodeFile))) {
            val found: Set<String> = MappingGate.problems(variant.text).toSet()
            assertEquals(
                "commit/accessibility: the mapping gate reported the wrong members for: " + variant.name,
                variant.expect,
                found,
            )
        }
    }

    @Test
    fun `every device mapping and the finder has a copy that breaks it`() {
        val covered: Set<String> = MappingSamples.firing(real(nodeFile)).flatMap { it.expect }.toSet()
        for (name in MappingExpected.node.keys + MappingExpected.finder.keys) {
            assertTrue("commit/accessibility: no edited copy breaks the mapping of " + name, name in covered)
        }
    }

    @Test
    fun `the real file and a reformatted copy of it report nothing`() {
        for (variant in MappingSamples.quiet(real(nodeFile))) {
            val found: List<String> = MappingGate.problems(variant.text)
            assertEquals("commit/accessibility: the mapping gate was not quiet for: " + variant.name + " at " + found, emptyList<String>(), found)
        }
    }

    @Test
    fun `source that holds neither class reports both as missing`() {
        val found: List<String> = MappingGate.problems("package dev.breaker.dictation.commit.accessibility.adapter\n")
        assertEquals(
            "commit/accessibility: a file without the two device classes must not pass the mapping gate",
            listOf("AndroidFieldNode (missing)", "AndroidNodeFinder (missing)"),
            found,
        )
    }

    @Test
    fun `an edited copy of the service file is reported by the name of the member it breaks`() {
        for (variant in ServiceSamples.firing(real(serviceFile))) {
            val found: Set<String> = MappingGate.serviceProblems(variant.text).toSet()
            assertEquals("commit/accessibility: the service gate reported the wrong members for: " + variant.name, variant.expect, found)
        }
    }

    @Test
    fun `every pinned service member has a copy that breaks it`() {
        val covered: Set<String> = ServiceSamples.firing(real(serviceFile)).flatMap { it.expect }.toSet()
        val uncovered: Set<String> = MappingExpected.service.keys - covered
        assertTrue("commit/accessibility: no edited copy breaks the pinned service members " + uncovered, uncovered.isEmpty())
    }

    @Test
    fun `the real service file and a copy with comments and line breaks report nothing`() {
        for (variant in ServiceSamples.quiet(real(serviceFile))) {
            val found: List<String> = MappingGate.serviceProblems(variant.text)
            assertEquals("commit/accessibility: the service gate was not quiet for: " + variant.name + " at " + found, emptyList<String>(), found)
        }
    }

    @Test
    fun `source without the service class reports it as missing`() {
        val found: List<String> = MappingGate.serviceProblems("package dev.breaker.dictation.commit.accessibility.adapter\n")
        assertEquals("commit/accessibility: a file without the service class must not pass the pin", listOf("BreakerAccessibilityService (missing)"), found)
    }

    @Test
    fun `a gate sample edit with a target that is not there is refused`() {
        val refused: Boolean = try {
            MappingSamples.edit("abc", "xyz", "q")
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("commit/accessibility: a mapping sample edit without a target must fail", refused)
    }
}
