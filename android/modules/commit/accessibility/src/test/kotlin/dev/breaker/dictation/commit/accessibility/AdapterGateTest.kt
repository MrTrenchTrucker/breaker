package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two device files, `adapter/AndroidFieldNode.kt` and `adapter/BreakerAccessibilityService.kt`,
 * are the only code no JVM test runs, so a text gate holds them to what the text-insert decision allows.
 *
 * The service never reads an event, performs only the two text actions, looks only at the
 * active window's input-focused node, publishes once and clears on unbind and destroy, gives
 * every node back, and is a plain public final class. The gate reads code only: comments and
 * string literals are removed first. It is text, so it cannot see a path that uses none of
 * these names; the allow-lists of imports and members narrow that, and review reads the files.
 *
 * Each rule has firing samples (edited copies of the sample files in [AdapterGateSamples]) and a
 * quiet sample; every check on the real files first requires that both files were found.
 */
internal class AdapterGateTest {

    private val nodeFile: String = "adapter/AndroidFieldNode.kt"
    private val serviceFile: String = "adapter/BreakerAccessibilityService.kt"

    /** Rule key to what it guards; the keys are what a failure reports. */
    private val rules: Map<String, String> = mapOf(
        "EVENT" to "the onAccessibilityEvent body is not empty",
        "INTERRUPT" to "the onInterrupt body is not empty",
        "ACTIONS" to "an ACTION_ token other than the two text actions and the three argument keys",
        "PERFORM" to "performAction is not called with exactly the two text actions, or another action API is used",
        "KEYS" to "a bundle value is not one of the three argument constants",
        "FOCUS" to "findFocus is not called once with FOCUS_INPUT",
        "ROOT" to "rootInActiveWindow is not used exactly once by the finder",
        "WALK" to "a window list, a child, a parent or a search by text is used",
        "MEMBERS" to "a node, root or service member (or a node constant) outside the allow-list is used",
        "PUBLISH" to "the service does not publish exactly once, inside onServiceConnected",
        "CLOSE" to "onUnbind or onDestroy does not close the published handle",
        "HELPER" to "the close helper does not drop the handle first, close it and catch only Exception",
        "ORDER" to "a lifecycle callback does things in the wrong order",
        "RECYCLE" to "recycle or its narrow suppression is not exactly the two release paths",
        "EVENT_READ" to "an event member is read",
        "CLASS" to "the service is not exactly one public final class with the right name",
        "INTERNAL" to "the node classes are not exactly the two internal ones",
        "IMPORTS" to "an import or a qualified name outside the allow-list",
    )

    private val actionToken = Regex("""ACTION_[A-Z0-9_]+""")
    private val performCall = Regex("""\bperformAction\s*\(\s*([^,()]*?)\s*,""")
    private val otherActionApis = Regex("""\b(performGlobalAction|dispatchGesture|takeScreenshot|GestureDescription|GLOBAL_ACTION[A-Z_]*)\b""")
    private val putCall = Regex("""\.\s*(put[A-Za-z]*)\s*\(\s*([^,()]*?)\s*,""")
    private val focusCall = Regex("""\bfindFocus\s*\(\s*([^()]*?)\s*\)""")
    private val rootUse = Regex("""\brootInActiveWindow\b""")
    private val walk = Regex(
        """\b(windows|getWindows|getChild|getChildCount|childCount|getParent|focusSearch|findAccessibilityNodeInfosBy[A-Za-z]*)\b|\.\s*parent\b""",
    )
    private val constantUse = Regex("""\bAccessibilityNodeInfo\s*\.\s*([A-Za-z_]\w*)""")
    private val publishCall = Regex("""\bpublish\s*\(""")
    private val holderPublish = Regex("""\bFocusedFieldHolder\s*\.\s*publish\s*\(""")
    private val closing = Regex("""\bclosePublished\s*\(|\.\s*close\s*\(""")
    private val closeCall = Regex("""\.\s*close\s*\(""")
    private val clearsField = Regex("""\bpublished\s*=\s*null\b""")
    private val catchesException = Regex("""\bcatch\s*\(\s*\w+\s*:\s*Exception\s*\)""")
    private val throwable = Regex("""\bThrowable\b""")
    private val superConnected = Regex("""\bsuper\s*\.\s*onServiceConnected\s*\(\s*\)""")
    private val returnSuperUnbind = Regex("""\breturn\s+super\s*\.\s*onUnbind\s*\(\s*intent\s*\)""")
    private val superDestroy = Regex("""\bsuper\s*\.\s*onDestroy\s*\(\s*\)""")
    private val recycleCall = Regex("""\brecycle\s*\(""")
    private val suppressWord = Regex("""\bSuppress\b""")
    private val suppressedRecyclePattern = """@Suppress\s*\(\s*\x22\x22\s*\)\s*[A-Za-z_]\w*\s*\.\s*recycle\s*\(\s*\)"""
    private val suppressedRecycle = Regex(suppressedRecyclePattern)
    private val finallyRecycle = Regex("""\bfinally\s*\{\s*""" + suppressedRecyclePattern + """\s*\}""")
    private val rawSuppress = Regex("""@Suppress\s*\(\s*\x22DEPRECATION\x22\s*\)""")
    private val eventMembers = Regex(
        """\b(getSource|source|getText|text|contentDescription|getContentDescription|eventType|getEventType|recordCount|getRecordCount|getRecord|parcelableData|getParcelableData|className|getClassName)\b""",
    )
    private val eventWord = Regex("""\bevent\b""")
    private val classDecl = Regex("""\bclass\s+([A-Za-z_]\w*)""")
    private val publicClass = Regex("""(?m)^class\s+BreakerAccessibilityService\s*:\s*AccessibilityService\s*\(\s*\)\s*\{""")
    private val classModifier = Regex("""\b(open|abstract|sealed)\b|\b(internal|private|protected)\s+class\b""")
    private val internalClass = Regex("""(?m)^internal\s+class\s+([A-Za-z_]\w*)""")
    private val importLine = Regex("""(?m)^\s*import\s+([A-Za-z_][A-Za-z0-9_.*]*)""")
    private val headerLine = Regex("""(?m)^\s*(package|import)\s.*""")
    private val qualifiedName = Regex("""\b(android|androidx|java|javax|dev)\s*\.\s*[a-z]""")

    private val emptyCallbacks: Set<String> = setOf("EVENT", "INTERRUPT", "EVENT_READ")
    private val twoActions: Set<String> = setOf("ACTIONS", "PERFORM", "KEYS")
    private val inputFocus: Set<String> = setOf("FOCUS", "ROOT", "WALK", "MEMBERS")
    private val publishAndClose: Set<String> = setOf("PUBLISH", "CLOSE", "HELPER", "ORDER")
    private val releaseRule: Set<String> = setOf("RECYCLE")
    private val classRule: Set<String> = setOf("CLASS", "INTERNAL")
    private val importRule: Set<String> = setOf("IMPORTS")

    private fun squash(text: String): String = text.replace(Regex("""\s+"""), "")
    private fun qualified(names: Collection<String>): List<String> = names.map { "AccessibilityNodeInfo." + it }.sorted()

    /** The text between the braces of the function [name], or null when it is missing or has no block body. */
    private fun bodyOf(code: String, name: String): String? {
        val head: MatchResult = Regex("""\bfun\s+""" + name + """\s*\(""").find(code) ?: return null
        var index: Int = head.range.last + 1
        var depth = 1
        while (index < code.length && depth > 0) {
            if (code[index] == '(') depth += 1
            if (code[index] == ')') depth -= 1
            index += 1
        }
        val opening = Regex("""\s*(:\s*[\w.]+\??\s*)?\{""").toPattern().matcher(code)
        opening.region(index, code.length)
        if (!opening.lookingAt()) return null
        val start: Int = opening.end()
        depth = 1
        index = start
        while (index < code.length) {
            if (code[index] == '{') depth += 1
            if (code[index] == '}') depth -= 1
            if (depth == 0) return code.substring(start, index)
            index += 1
        }
        return null
    }

    private fun inOrder(body: String?, first: Regex, second: Regex): Boolean {
        if (body == null) return false
        val a: MatchResult? = first.find(body)
        val b: MatchResult? = second.find(body)
        return a != null && b != null && a.range.first < b.range.first
    }

    /** The keys of the rules that the two files break; empty when they break none. */
    private fun problems(nodeSource: String, serviceSource: String): List<String> {
        val node: String = SourceFiles.strip(nodeSource).code
        val service: String = SourceFiles.strip(serviceSource).code
        val both: String = node + "\n" + service
        val bad: MutableList<String> = ArrayList()
        val eventBody: String? = bodyOf(service, "onAccessibilityEvent")
        if (eventBody == null || eventBody.isNotBlank()) bad.add("EVENT")
        val interruptBody: String? = bodyOf(service, "onInterrupt")
        if (interruptBody == null || interruptBody.isNotBlank()) bad.add("INTERRUPT")
        val tokens: Set<String> = actionToken.findAll(both).map { it.value }.toSet()
        if (tokens.any { it !in AdapterAllowed.actions && it !in AdapterAllowed.arguments } || !tokens.containsAll(AdapterAllowed.actions)) bad.add("ACTIONS")
        val performed: List<String> = performCall.findAll(both).map { squash(it.groupValues[1]) }.sorted().toList()
        if (performed != qualified(AdapterAllowed.actions) || otherActionApis.containsMatchIn(both)) bad.add("PERFORM")
        val puts: List<Pair<String, String>> = putCall.findAll(both).map { Pair(it.groupValues[1], squash(it.groupValues[2])) }.toList()
        val onlyKnownPuts: Boolean = puts.all { it.first == "putCharSequence" || it.first == "putInt" }
        if (!onlyKnownPuts || puts.map { it.second }.sorted() != qualified(AdapterAllowed.arguments)) bad.add("KEYS")
        val focused: List<String> = focusCall.findAll(both).map { squash(it.groupValues[1]) }.toList()
        if (focused != listOf("AccessibilityNodeInfo.FOCUS_INPUT")) bad.add("FOCUS")
        if (rootUse.findAll(both).count() != 1 || !rootUse.containsMatchIn(node)) bad.add("ROOT")
        if (walk.containsMatchIn(both)) bad.add("WALK")
        var unlisted = false
        for ((variable, allowed) in AdapterAllowed.members) {
            val usage = Regex("""\b""" + variable + """\s*\??\s*\.\s*([A-Za-z_]\w*)""")
            if (usage.findAll(both).any { it.groupValues[1] !in allowed }) unlisted = true
        }
        if (constantUse.findAll(both).any { it.groupValues[1] !in AdapterAllowed.constants }) unlisted = true
        if (unlisted) bad.add("MEMBERS")
        val connected: String? = bodyOf(service, "onServiceConnected")
        val unbind: String? = bodyOf(service, "onUnbind")
        val destroy: String? = bodyOf(service, "onDestroy")
        val helper: String? = bodyOf(service, "closePublished")
        val publishesOnce: Boolean = publishCall.findAll(both).count() == 1 && holderPublish.findAll(service).count() == 1
        if (!publishesOnce || connected == null || !holderPublish.containsMatchIn(connected)) bad.add("PUBLISH")
        if (unbind == null || destroy == null || !closing.containsMatchIn(unbind) || !closing.containsMatchIn(destroy)) bad.add("CLOSE")
        val helperOk: Boolean = helper != null && closeCall.containsMatchIn(helper) && clearsField.containsMatchIn(helper) &&
            catchesException.containsMatchIn(helper)
        if (!helperOk || throwable.containsMatchIn(both)) bad.add("HELPER")
        val ordered: Boolean = inOrder(connected, superConnected, closing) && inOrder(connected, closing, holderPublish) &&
            inOrder(unbind, closing, returnSuperUnbind) && inOrder(destroy, closing, superDestroy)
        if (!ordered) bad.add("ORDER")
        val releaseBody: String? = bodyOf(node, "release")
        val findBody: String? = bodyOf(node, "findInputFocus")
        val releasesRight: Boolean = releaseBody != null && recycleCall.findAll(releaseBody).count() == 1 &&
            suppressedRecycle.containsMatchIn(releaseBody) && findBody != null && finallyRecycle.containsMatchIn(findBody)
        val twoNarrow: Boolean = recycleCall.findAll(both).count() == 2 && !recycleCall.containsMatchIn(service) &&
            suppressWord.findAll(both).count() == 2 && suppressedRecycle.findAll(both).count() == 2 &&
            rawSuppress.findAll(nodeSource + "\n" + serviceSource).count() == 2
        if (!releasesRight || !twoNarrow) bad.add("RECYCLE")
        if (eventMembers.containsMatchIn(service) || eventWord.findAll(service).count() != 1) bad.add("EVENT_READ")
        val serviceClasses: List<String> = classDecl.findAll(service).map { it.groupValues[1] }.toList()
        val classOk: Boolean = serviceClasses == listOf("BreakerAccessibilityService") && publicClass.containsMatchIn(service) &&
            !classModifier.containsMatchIn(service)
        if (!classOk) bad.add("CLASS")
        val nodeClasses: List<String> = classDecl.findAll(node).map { it.groupValues[1] }.sorted().toList()
        val internalNodeClasses: List<String> = internalClass.findAll(node).map { it.groupValues[1] }.sorted().toList()
        if (nodeClasses != listOf("AndroidFieldNode", "AndroidNodeFinder") || nodeClasses != internalNodeClasses) bad.add("INTERNAL")

        val imports: Set<String> = importLine.findAll(both).map { it.groupValues[1] }.toSet()
        val body: String = headerLine.replace(both, "")
        if (!AdapterAllowed.imports.containsAll(imports) || qualifiedName.containsMatchIn(body)) bad.add("IMPORTS")
        return bad
    }

    private fun explain(keys: List<String>): String = keys.joinToString("; ") { key -> key + " (" + rules[key] + ")" }

    private fun realProblems(group: Set<String>): List<String> {
        val main: Map<String, String> = SourceFiles.mainSources()
        for (file in listOf(nodeFile, serviceFile)) {
            val source: String = main[file] ?: ""
            assertTrue("commit/accessibility: the adapter gate did not find $file, found ${main.keys}", source.isNotBlank())
            assertTrue("commit/accessibility: the adapter gate read no code in $file", SourceFiles.strip(source).code.isNotBlank())
        }
        return problems(main.getValue(nodeFile), main.getValue(serviceFile)).filter { it in group }
    }

    private fun assertRealFilesPass(what: String, group: Set<String>) {
        val found: List<String> = realProblems(group)
        assertTrue("commit/accessibility: the real adapter files break $what: " + explain(found), found.isEmpty())
    }

    @Test
    fun `the service reads no event and its event and interrupt callbacks are empty`() =
        assertRealFilesPass("the empty callbacks rule", emptyCallbacks)

    @Test
    fun `only the two text actions and their three argument keys are used`() =
        assertRealFilesPass("the two actions rule", twoActions)

    @Test
    fun `only the active window's input focus is looked up, with no window list, child or parent`() =
        assertRealFilesPass("the input focus rule", inputFocus)

    @Test
    fun `the service publishes once on connect and clears the handle on unbind and destroy`() =
        assertRealFilesPass("the publish and close rule", publishAndClose)

    @Test
    fun `recycle is used only on the two release paths, each with one narrow suppression`() =
        assertRealFilesPass("the release rule", releaseRule)

    @Test
    fun `the service is one public final class with the right name and the node classes are internal`() =
        assertRealFilesPass("the class rule", classRule)

    @Test
    fun `the two files import only what they need and name no other framework type`() =
        assertRealFilesPass("the import rule", importRule)

    @Test
    fun `every rule is checked against the real files by one of the tests above`() {
        val checked: Set<String> = emptyCallbacks + twoActions + inputFocus + publishAndClose + releaseRule + classRule + importRule
        assertEquals("commit/accessibility: a rule is not checked against the real files", rules.keys, checked)
    }

    @Test
    fun `the unedited sample files break no rule`() {
        val found: List<String> = problems(AdapterGateSamples.node, AdapterGateSamples.service)
        assertTrue("commit/accessibility: the sample adapter files were reported: " + explain(found), found.isEmpty())
    }

    @Test
    fun `an edited copy that breaks one thing is reported under the rule that guards it`() {
        val variants: List<AdapterVariant> = AdapterGateSamples.variants()
        assertTrue("commit/accessibility: the adapter gate has too few firing samples", variants.size >= 10)
        val covered: MutableSet<String> = HashSet()
        for (variant in variants) {
            val found: List<String> = problems(variant.node, variant.service)
            assertTrue(
                "commit/accessibility: the adapter gate missed '${variant.name}': expected ${variant.expect}, found $found",
                found.containsAll(variant.expect),
            )
            covered.addAll(variant.expect)
        }
        assertEquals("commit/accessibility: an adapter rule has no firing sample", rules.keys, covered)
    }

    @Test
    fun `forbidden names in comments and string literals are not reported`() {
        val prose: AdapterVariant = AdapterGateSamples.withProse()
        assertTrue("commit/accessibility: the prose sample lost its added text", prose.node.length > AdapterGateSamples.node.length)
        val found: List<String> = problems(prose.node, prose.service)
        assertTrue("commit/accessibility: names in prose were reported: " + explain(found), found.isEmpty())
    }

    @Test
    fun `the body reader finds a function body and gives nothing for a missing or expression body`() {
        val code = "class A { fun f() { if (x) { y() } } fun g() { } fun k(): Boolean { return true } fun h() = 1 fun onDestroyAll() { } }"
        assertEquals("commit/accessibility: the body reader misread a nested block", "if (x) { y() }", bodyOf(code, "f")?.trim())
        assertNotNull("commit/accessibility: the body reader lost an empty body", bodyOf(code, "g"))
        assertTrue("commit/accessibility: the body reader found text in an empty body", bodyOf(code, "g").orEmpty().isBlank())
        assertEquals("commit/accessibility: the body reader misread a return type", "return true", bodyOf(code, "k")?.trim())
        assertNull("commit/accessibility: the body reader gave a body to an expression function", bodyOf(code, "h"))
        assertNull("commit/accessibility: the body reader matched a longer name", bodyOf(code, "onDestroy"))
        assertNull("commit/accessibility: the body reader invented a function", bodyOf(code, "missing"))
    }

    @Test
    fun `a file that cannot be read as code is refused, not passed`() {
        assertThrows(
            "commit/accessibility: the adapter gate passed a node file it cannot read",
            IllegalStateException::class.java,
        ) { problems("object A { val s = \"never ends\n}", AdapterGateSamples.service) }
        assertThrows(
            "commit/accessibility: the adapter gate passed a service file it cannot read",
            IllegalStateException::class.java,
        ) { problems(AdapterGateSamples.node, "class A /* never closed\n") }
    }
}
