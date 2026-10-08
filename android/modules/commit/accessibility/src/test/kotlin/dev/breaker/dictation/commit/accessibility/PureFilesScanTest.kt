package dev.breaker.dictation.commit.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The files that hold the insert logic are free of the Android framework, and the
 * only place that may name it is the `adapter/` folder. They also never name the
 * parent module's holder or its adapter package: only the service publishes.
 *
 * That is what lets a plain JVM test run the whole insert without a device, and
 * nothing else stops an `import android...` or a `Context` parameter from landing
 * in one of them. The scan reads code only: a comment, a string or a longer word
 * that merely contains one of the names is not a reference.
 *
 * They also keep nothing between calls: no pure file declares a mutable property, and
 * the service holds exactly one, the handle of what it published.
 *
 * It is text. A framework type that is reached without any of these names is not
 * seen here; the module's compile classpath is the stronger guard.
 */
internal class PureFilesScanTest {

    /** The pure files. A new main file is not covered until it is added here on purpose. */
    private val pureFiles: Set<String> = setOf("InsertPlan.kt", "FieldNode.kt", "NodeFocusedField.kt")

    /**
     * The device files in `adapter/`, the only code no JVM test runs.
     * A new device file must be argued for and listed here.
     */
    private val adapterFiles: Set<String> = setOf("adapter/AndroidFieldNode.kt", "adapter/BreakerAccessibilityService.kt")

    private fun word(name: String): Regex = Regex("""(?<![\p{L}\p{N}_.])""" + name + """(?![\p{L}\p{N}_])""")

    /** A `var` declared at class level (or file level): optional modifiers, then `var`; the name is group 1. */
    private val mutableMember: Regex = Regex(
        """^(?: {4})?(?:(?:private|protected|internal|public|open|final|override|lateinit|@[A-Za-z.]+)\s+)*var\s+([A-Za-z_][A-Za-z0-9_]*)""",
        RegexOption.MULTILINE,
    )

    /** The names of the mutable properties in the code of [source]. */
    private fun mutableNames(source: String): List<String> =
        mutableMember.findAll(SourceFiles.strip(source).code).map { it.groupValues[1] }.toList()

    private val rules: Map<String, Regex> = mapOf(
        "framework package" to Regex("""(?<![\p{L}\p{N}_.])androidx?\s*\."""),
        "Context" to word("Context"),
        "Handler" to word("Handler"),
        "Looper" to word("Looper"),
        "AccessibilityNodeInfo" to word("AccessibilityNodeInfo"),
        "AccessibilityService" to word("AccessibilityService"),
        "AccessibilityEvent" to word("AccessibilityEvent"),
        "Bundle" to word("Bundle"),
        "adapter import" to Regex("""(?<![\p{L}\p{N}_])commit\s*\.\s*(accessibility\s*\.\s*)?adapter\b"""),
        "parent holder" to Regex("""\bFocusedFieldHolder\b"""),
        "parent adapter package" to Regex("""\bcommit\s*\.\s*adapter\b"""),
        "mutable property" to mutableMember,
    )

    private class Sample(val label: String, val source: String, val rule: String)

    /** One line per way [sources] differs from the pinned [pure] and [adapter] sets; none means they match exactly. */
    private fun layoutProblems(sources: Map<String, String>, pure: Set<String>, adapter: Set<String>): List<String> {
        val top: Set<String> = sources.keys.filter { !it.contains('/') }.toSet()
        val inAdapter: Set<String> = sources.keys.filter { it.startsWith("adapter/") }.toSet()
        val elsewhere: List<String> = sources.keys.filter { it.contains('/') && !it.startsWith("adapter/") }.sorted()
        val problems: MutableList<String> = ArrayList()
        for (name in (top - pure).sorted()) {
            problems.add("main file $name is not pinned as a pure file")
        }
        for (name in (pure - top).sorted()) {
            problems.add("pinned pure file $name is missing")
        }
        for (name in (inAdapter - adapter).sorted()) {
            problems.add("adapter file $name is not pinned")
        }
        for (name in (adapter - inAdapter).sorted()) {
            problems.add("pinned adapter file $name is missing")
        }
        for (name in elsewhere) {
            problems.add("main file $name is in neither the main folder nor adapter/")
        }
        return problems
    }

    private fun assertLayout(label: String, expected: List<String>, sources: Map<String, String>, adapter: Set<String> = adapterFiles) {
        assertEquals(
            "commit/accessibility: the layout check misjudged $label",
            expected,
            layoutProblems(sources, pureFiles, adapter),
        )
    }

    @Test
    fun `the pure files name no framework type and do not import from an adapter folder`() {
        val main: Map<String, String> = SourceFiles.mainSources()
        val chosen: MutableMap<String, String> = LinkedHashMap()
        for (name in pureFiles) {
            val source: String = main[name] ?: ""
            assertTrue("commit/accessibility: pure file $name is missing or empty in the main folder", source.isNotBlank())
            chosen[name] = source
        }
        assertTrue("commit/accessibility: the pure file scan has no file to read", chosen.isNotEmpty())
        assertEquals(
            "commit/accessibility: a pure file names the framework, an accessibility type, Context, Handler, Looper, Bundle, the parent's holder, an adapter folder or a mutable property",
            emptyList<String>(),
            SourceFiles.offences(chosen, rules),
        )
    }

    @Test
    fun `every main file is a pinned pure file or a pinned adapter file, both ways`() {
        assertEquals(
            "commit/accessibility: the main folder changed; a new file has to be argued for and pinned in this test",
            emptyList<String>(),
            layoutProblems(SourceFiles.mainSources(), pureFiles, adapterFiles),
        )
    }

    @Test
    fun `the layout check sees an added file, a missing file, a nested file and a look-alike folder`() {
        val pureOnly: Map<String, String> = pureFiles.associateWith { "" }
        val clean: Map<String, String> = (pureFiles + adapterFiles).associateWith { "" }
        assertEquals(
            "commit/accessibility: the pinned pure files are not the three the module is built from",
            setOf("InsertPlan.kt", "FieldNode.kt", "NodeFocusedField.kt"),
            pureFiles,
        )
        assertEquals(
            "commit/accessibility: the pinned adapter files are not the two device files the module is built from",
            setOf("adapter/AndroidFieldNode.kt", "adapter/BreakerAccessibilityService.kt"),
            adapterFiles,
        )
        assertLayout("the pinned layout", emptyList(), clean)
        assertLayout("an added main file", listOf("main file Extra.kt is not pinned as a pure file"), clean + ("Extra.kt" to ""))
        assertLayout("a missing pure file", listOf("pinned pure file InsertPlan.kt is missing"), clean - "InsertPlan.kt")
        assertLayout("an adapter file nobody pinned", listOf("adapter file adapter/One.kt is not pinned"), clean + ("adapter/One.kt" to ""))
        assertLayout("a nested adapter file", listOf("adapter file adapter/deep/Two.kt is not pinned"), clean + ("adapter/deep/Two.kt" to ""))
        assertLayout(
            "a look-alike folder",
            listOf("main file adapterOther/Three.kt is in neither the main folder nor adapter/"),
            clean + ("adapterOther/Three.kt" to ""),
        )
        assertLayout(
            "a folder that is not adapter",
            listOf("main file util/Four.kt is in neither the main folder nor adapter/"),
            clean + ("util/Four.kt" to ""),
        )
        assertLayout(
            "a missing node adapter file",
            listOf("pinned adapter file adapter/AndroidFieldNode.kt is missing"),
            clean - "adapter/AndroidFieldNode.kt",
        )
        assertLayout(
            "a missing service file",
            listOf("pinned adapter file adapter/BreakerAccessibilityService.kt is missing"),
            clean - "adapter/BreakerAccessibilityService.kt",
        )
        assertLayout(
            "a device file moved out of adapter/",
            listOf(
                "main file AndroidFieldNode.kt is not pinned as a pure file",
                "pinned adapter file adapter/AndroidFieldNode.kt is missing",
            ),
            clean - "adapter/AndroidFieldNode.kt" + ("AndroidFieldNode.kt" to ""),
        )
        val pinned: Set<String> = setOf("adapter/Device.kt")
        assertLayout("a missing adapter file", listOf("pinned adapter file adapter/Device.kt is missing"), pureOnly, pinned)
        assertLayout("a present adapter file", emptyList(), pureOnly + ("adapter/Device.kt" to ""), pinned)
    }

    @Test
    fun `samples that name the framework are reported under the rule that sees them`() {
        val samples: List<Sample> = listOf(
            Sample("an import", "import android.util.Log\nobject A", "framework package"),
            Sample("an androidx import", "import androidx.core.app.Foo\nobject A", "framework package"),
            Sample("a qualified name over a line break", "object A { val c: android\n    .content.Foo? = null }", "framework package"),
            Sample("a spaced dot", "object A { val c: android . os . Foo? = null }", "framework package"),
            Sample("the Context type", "object A { fun f(c: Context) {} }", "Context"),
            Sample("a Handler", "object A { val h = Handler(x) }", "Handler"),
            Sample("a Looper", "object A { val l: Looper? = null }", "Looper"),
            Sample("an AccessibilityNodeInfo", "object A { val n: AccessibilityNodeInfo? = null }", "AccessibilityNodeInfo"),
            Sample("an AccessibilityService", "object A { val s: AccessibilityService? = null }", "AccessibilityService"),
            Sample("an AccessibilityEvent", "object A { fun f(e: AccessibilityEvent) {} }", "AccessibilityEvent"),
            Sample("a Bundle", "object A { val b: Bundle? = null }", "Bundle"),
            Sample("an import from the parent adapter folder", "import dev.breaker.dictation.commit.adapter.Foo\nobject A", "adapter import"),
            Sample("an import from this module's adapter folder", "import dev.breaker.dictation.commit.accessibility.adapter.Foo\nobject A", "adapter import"),
            Sample("the parent's holder", "object A { val h = FocusedFieldHolder }", "parent holder"),
            Sample("the parent's holder in an import", "import dev.breaker.dictation.commit.adapter.FocusedFieldHolder\nobject A", "parent holder"),
            Sample("an import from the parent's adapter package", "import dev.breaker.dictation.commit.adapter.Other\nobject A", "parent adapter package"),
            Sample("the parent's adapter package over a line break", "object A { val x = commit\n    .adapter.Other }", "parent adapter package"),
            Sample("a stashed text", "internal class A {\n    private var lastText: String? = null\n}", "mutable property"),
        Sample("a stashed node", "internal class A {\n    private var lastNode: FieldNode? = null\n}", "mutable property"),
        Sample("a late-initialised property", "internal class A {\n    private lateinit var node: FieldNode\n}", "mutable property"),
        Sample("a public property", "internal class A {\n    var count: Int = 0\n}", "mutable property"),
        Sample("a mutable constructor property", "internal class A(\n    private var held: String,\n) {\n}", "mutable property"),
        Sample("a file level variable", "private var cache: String? = null\ninternal object A", "mutable property"),
        Sample("a name inside a template hole", "object A { val s = \"x \${Context}\" }", "Context"),
            Sample("code after a character literal that holds a quote", "object A { val q = '\"'; val c: Context? = null }", "Context"),
            Sample("code after a raw string that ends in a quote", "object A { val s = \"\"\"say \"hi\"\"\"\"; val c: Context? = null }", "Context"),
        )
        assertEquals(
            "commit/accessibility: a pure file rule has no firing sample",
            rules.keys,
            samples.map { it.rule }.toSet(),
        )
        for (sample in samples) {
            val found: List<String> = SourceFiles.offences(mapOf("sample.kt" to sample.source), rules)
            assertTrue(
                "commit/accessibility: the pure file scan missed ${sample.label}, found $found",
                found.any { it.startsWith("sample.kt: ${sample.rule} x") },
            )
        }
    }

    @Test
    fun `samples with the names only in comments, strings or longer words are not reported`() {
        val fine: Map<String, String> = mapOf(
            "a line comment" to "// import android.util.Log\nobject A",
            "a block comment" to "/* Context Handler Looper Bundle */ object A",
            "a nested block comment" to "/* a /* Context */ Looper */ object A",
            "a KDoc" to "/**\n * Runs on android.os with an AccessibilityNodeInfo and a Context.\n */\nobject A",
            "a string" to "object A { const val S = \"android.util.Log Context Looper AccessibilityEvent\" }",
            "a raw string" to "object A { const val S = \"\"\"Handler android.os commit.adapter\"\"\" }",
            "a comment marker inside a string" to "object A { const val S = \"http://x\" }\nobject B",
            "a quote in a character literal" to "object A { val q = '\"' }\n// android.os",
            "the two parent imports that are allowed" to "import dev.breaker.dictation.commit.FocusedField\nimport dev.breaker.dictation.commit.FieldCommit\nobject A",
            "the parent's holder named in a comment or a string" to "// FocusedFieldHolder.publish\nobject A { const val S = \"FocusedFieldHolder commit.adapter\" }",
            "names that only contain the holder's name" to "object A { val MyFocusedFieldHolder = 1; val FocusedFieldHolderX = 2; val focusedFieldHolder = 3 }",
            "an import from the module's own package" to "import dev.breaker.dictation.commit.accessibility.FieldNode\nobject A",
            "the adapter folder named in a comment" to "// see commit.accessibility.adapter\nobject A",
            "longer words" to "object A { val androidFoo = 1; val myandroid = 2; val ContextFree = 3; val HandlerMainThread = 4; val LooperX = 5 }",
            "longer type names" to "object A { val a: BreakerAccessibilityService? = null; val b = AccessibilityServiceInfoX; val c = MyBundle; val d = BundleSize }",
            "a variable inside a function" to "internal class A {\n    fun f(): Int {\n        var n = 0\n        n += 1\n        return n\n    }\n}",
            "constructor properties that are read only" to "internal class A(\n    private val finder: X,\n    private val ownPackage: String,\n) {\n    val size: Int = 1\n}",
            "var in a comment, a string or a longer word" to "internal class A {\n    // private var lastText: String? = null\n    /** var x */\n    const val S = \"    var x\"\n    val variance = 1\n    fun invariant() = 1\n}",
            "a name that only starts or ends like the adapter folder" to "object A { val adapter = 1; val commitAdapter = 2; val x = commit.adapterName }",
        )
        for ((label, source) in fine) {
            assertEquals(
                "commit/accessibility: the pure file scan flagged $label",
                emptyList<String>(),
                SourceFiles.offences(mapOf("sample.kt" to source), rules),
            )
        }
    }

    @Test
    fun `no pure file and no node adapter keeps a mutable property and the service keeps only its handle`() {
        val main: Map<String, String> = SourceFiles.mainSources()
        for (name in pureFiles + "adapter/AndroidFieldNode.kt") {
            val source: String = main[name] ?: ""
            assertTrue("commit/accessibility: " + name + " is missing or empty in the main folder", source.isNotBlank())
            assertEquals("commit/accessibility: " + name + " keeps a mutable property", emptyList<String>(), mutableNames(source))
        }
        val service: String = main["adapter/BreakerAccessibilityService.kt"] ?: ""
        assertTrue("commit/accessibility: the service file is missing or empty", service.isNotBlank())
        assertEquals(
            "commit/accessibility: the service must keep exactly one mutable property, the handle it published",
            listOf("published"),
            mutableNames(service),
        )
        assertEquals("commit/accessibility: the property scan missed two properties", listOf("a", "b"), mutableNames("class S {\n    private var a = 1\n    var b = 2\n}"))
    }

    @Test
    fun `a file that cannot be read as code is refused, not passed`() {
        val broken: List<String> = listOf(
            "object A { val s = \"never ends\n}",
            "object A /* never closed\n",
            "object A { val s = \"\"\"never closed }",
            "object A { val s = \"\"\"x \${a",
            "object A { val c = 'a }",
            "object A { fun `never closed() }",
        )
        for (source in broken) {
            assertThrows(
                "commit/accessibility: the scanner passed code it cannot read: $source",
                IllegalStateException::class.java,
            ) { SourceFiles.strip(source) }
        }
    }
}
