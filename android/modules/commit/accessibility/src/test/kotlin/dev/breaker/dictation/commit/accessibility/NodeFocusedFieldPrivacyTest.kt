package dev.breaker.dictation.commit.accessibility

import dev.breaker.dictation.commit.FieldCommit
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What [NodeFocusedField] does with text it must keep to itself: a marker string stands in for
 * the dictated text and for the field's own text, and nothing may escape as an exception or be
 * read from a field that is turned away. A field that has not been asked to do anything
 * touches nothing. The counters come from the node double.
 */
internal class NodeFocusedFieldPrivacyTest {

    private companion object {
        const val MARKER: String = "SECRET-DICTATION-7f3a"
    }

    /** A field over a fresh double behind a [FailingMemberNode] that throws [failure] from the members in [failing]. */
    private class Rig(failing: Set<String> = emptySet(), failure: Exception = IllegalStateException(MARKER)) {
        val ownPackage: String = "com.breaker.own"
        val calls: MutableList<String> = ArrayList()
        val inner: FakeFieldNode = FakeFieldNode(calls)
        val finder: FakeFocusedNodeFinder = FakeFocusedNodeFinder(FailingMemberNode(inner, failing, failure), calls)
        val field: NodeFocusedField = NodeFocusedField(finder, ownPackage)
    }

    /** Commit [dictated] and fail with [label] if anything is thrown; the exception itself is never shown. */
    private fun commitWithoutEscape(label: String, rig: Rig, dictated: String): FieldCommit {
        try {
            return rig.field.commitText(dictated)
        } catch (e: Exception) {
            fail("commit/accessibility: " + label + ": an exception escaped from commitText")
            return FieldCommit.REFUSED
        }
    }

    /**
     * What a commit must answer when the one member named throws: every throw up to and
     * including setText is a refusal; a cursor call that throws and a release that throws
     * come after the text landed, so the insert stays accepted.
     */
    private fun expectedWhenThrowing(member: String): FieldCommit =
        if (member == FakeFieldNode.SET_SELECTION || member == FakeFieldNode.RELEASE) {
            FieldCommit.ACCEPTED
        } else {
            FieldCommit.REFUSED
        }

    @Test
    fun `the marker as dictated text and as field text is merged without an exception`() {
        // No selection means the end of the text: MARKER + MARKER, and the cursor is 21 + 21.
        val rig = Rig()
        rig.inner.holdText(MARKER)

        val result: FieldCommit = commitWithoutEscape("marker both ways", rig, MARKER)

        assertEquals("commit/accessibility: the insert must be accepted", FieldCommit.ACCEPTED, result)
        assertEquals(
            "commit/accessibility: setText must get the dictated text after the field's text",
            listOf(MARKER + MARKER),
            rig.inner.setTextArguments,
        )
        assertEquals(
            "commit/accessibility: the cursor must be after both",
            listOf(Pair(MARKER.length * 2, MARKER.length * 2)),
            rig.inner.setSelectionArguments,
        )
    }

    @Test
    fun `a text refused for its length is a refusal and not an exception`() {
        val rig = Rig()
        rig.inner.holdText(MARKER)
        rig.inner.maxTextLength = 3

        val result: FieldCommit = commitWithoutEscape("marker over the limit", rig, MARKER)

        assertEquals("commit/accessibility: a text over the limit must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: nothing may be written when the text is refused", 0, rig.inner.setTextCalls)
    }

    @Test
    fun `no exception escapes whichever single node member throws`() {
        for (member in FailingMemberNode.MEMBERS) {
            val rig = Rig(setOf(member))
            rig.inner.holdText(MARKER)

            val result: FieldCommit = commitWithoutEscape("throwing " + member, rig, MARKER)

            assertEquals(
                "commit/accessibility: wrong answer when " + member + " throws",
                expectedWhenThrowing(member),
                result,
            )
            assertEquals("commit/accessibility: the node must be released once when " + member + " throws", 1, rig.inner.releaseCalls)
        }
    }

    @Test
    fun `an exception that is not a runtime exception is a refusal too`() {
        // The write, the text read and the release throw a plain checked-style Exception, one at a time.
        val checked: Exception = Exception(MARKER)
        val onWrite = Rig(setOf(FakeFieldNode.SET_TEXT), checked)
        val onRead = Rig(setOf(FakeFieldNode.READ_TEXT), checked)
        val onRelease = Rig(setOf(FakeFieldNode.RELEASE), checked)

        assertEquals(
            "commit/accessibility: a plain Exception from setText must be a refusal",
            FieldCommit.REFUSED,
            commitWithoutEscape("plain exception from setText", onWrite, MARKER),
        )
        assertEquals(
            "commit/accessibility: a plain Exception from the text read must be a refusal",
            FieldCommit.REFUSED,
            commitWithoutEscape("plain exception from the text read", onRead, MARKER),
        )
        assertEquals(
            "commit/accessibility: a plain Exception from release must not change an accepted insert",
            FieldCommit.ACCEPTED,
            commitWithoutEscape("plain exception from release", onRelease, MARKER),
        )
    }

    @Test
    fun `every member throwing at once is still only a refusal`() {
        val rig = Rig(FailingMemberNode.MEMBERS.toSet())
        rig.inner.holdText(MARKER)

        val result: FieldCommit = commitWithoutEscape("every member throws", rig, MARKER)

        assertEquals("commit/accessibility: a node that fails everywhere must be refused", FieldCommit.REFUSED, result)
        assertEquals("commit/accessibility: that node must still be released once", 1, rig.inner.releaseCalls)
        assertEquals("commit/accessibility: nothing may be read after the refresh threw", 0, rig.inner.textReads)
    }

    @Test
    fun `a finder that throws is a refusal`() {
        var runtimeAsks = 0
        var plainAsks = 0
        val runtime = NodeFocusedField(
            FocusedNodeFinder {
                runtimeAsks++
                throw IllegalStateException(MARKER)
            },
            "com.breaker.own",
        )
        val plain = NodeFocusedField(
            FocusedNodeFinder {
                plainAsks++
                throw Exception(MARKER)
            },
            "com.breaker.own",
        )

        assertEquals(
            "commit/accessibility: a finder that throws a runtime exception must give a refusal",
            FieldCommit.REFUSED,
            runtime.commitText(MARKER),
        )
        assertEquals(
            "commit/accessibility: a finder that throws a plain Exception must give a refusal",
            FieldCommit.REFUSED,
            plain.commitText(MARKER),
        )
        assertEquals("commit/accessibility: the runtime-exception finder must be asked exactly once", 1, runtimeAsks)
        assertEquals("commit/accessibility: the plain-Exception finder must be asked exactly once", 1, plainAsks)
    }

    @Test
    fun `the text of a field that is turned away is never read`() {
        // Four fields that are refused before step 7, each holding the marker as its text.
        val password = Rig()
        password.inner.isPassword = true
        val readOnly = Rig()
        readOnly.inner.isEditable = false
        val disabled = Rig()
        disabled.inner.isEnabled = false
        val own = Rig()
        own.inner.packageName = own.ownPackage
        val all = listOf(
            Pair("password", password),
            Pair("not editable", readOnly),
            Pair("not enabled", disabled),
            Pair("own package", own),
        )

        for ((label, rig) in all) {
            rig.inner.holdText(MARKER)
            rig.inner.selectionStart = 0
            rig.inner.selectionEnd = MARKER.length

            val result: FieldCommit = commitWithoutEscape(label, rig, MARKER)

            assertEquals("commit/accessibility: " + label + ": the field must be refused", FieldCommit.REFUSED, result)
            assertEquals("commit/accessibility: " + label + ": its text must never be read", 0, rig.inner.textReads)
            assertEquals("commit/accessibility: " + label + ": its selection start must never be read", 0, rig.inner.selectionStartReads)
            assertEquals("commit/accessibility: " + label + ": its selection end must never be read", 0, rig.inner.selectionEndReads)
            assertEquals("commit/accessibility: " + label + ": nothing may be written to it", 0, rig.inner.setTextCalls)
            assertEquals("commit/accessibility: " + label + ": no cursor may be placed in it", 0, rig.inner.setSelectionCalls)
        }
    }

    @Test
    fun `building the field makes no call on the finder or on a node`() {
        val rig = Rig()
        rig.inner.holdText(MARKER)

        assertEquals("commit/accessibility: building the field must not ask the finder", 0, rig.finder.findCalls)
        assertEquals("commit/accessibility: building the field must not touch a node", List(13) { 0 }, rig.inner.counts())
        assertEquals("commit/accessibility: building the field must make no call at all", emptyList<String>(), rig.calls)
    }

    /** What a class stores of its own: each non-synthetic field as its private, static and final marks and its name, sorted. */
    private fun fieldShape(type: Class<*>): List<String> =
        type.declaredFields
            .filter { !it.isSynthetic }
            .map { Modifier.toString(it.modifiers and (Modifier.PRIVATE or Modifier.STATIC or Modifier.FINAL)) + " " + it.name }
            .sorted()

    private val resolverShape: List<String> = listOf("private final finder", "private final ownPackage")
    private val planShape: List<String> = listOf("static final INSTANCE")
    private val spanShape: List<String> = listOf("private final end", "private final start")

    private class KeptList { private val seen: MutableList<String> = ArrayList() }

    private class KeptStatic { companion object { var last: String? = null } }

    private object KeptBuffer { private val log = StringBuilder() }

    private class KeptHolder { private class Span(var start: Int, val end: Int, var held: String?) }

    @Test
    fun `the resolver and the insert rule keep nothing between calls`() {
        val resolver: List<String> = fieldShape(NodeFocusedField::class.java)
        assertEquals("commit/accessibility: the field resolver must store only its finder and its own package, found $resolver", resolverShape, resolver)
        val plan: List<String> = fieldShape(InsertPlan::class.java)
        assertEquals("commit/accessibility: the insert rule must store nothing but its one instance, found $plan", planShape, plan)
        val nested: List<String> = InsertPlan::class.java.declaredClasses.map { it.simpleName }.sorted()
        assertEquals("commit/accessibility: the insert rule must hold exactly one nested class, Span, found $nested", listOf("Span"), nested)
        val span: List<String> = fieldShape(InsertPlan::class.java.declaredClasses.single())
        assertEquals("commit/accessibility: a span must store only a final start and a final end, found $span", spanShape, span)
    }

    @Test
    fun `a list, a static, a text buffer or a var in a nested class is seen by the field shape`() {
        val list: List<String> = fieldShape(KeptList::class.java)
        val static: List<String> = fieldShape(KeptStatic::class.java)
        val buffer: List<String> = fieldShape(KeptBuffer::class.java)
        val held: List<String> = fieldShape(KeptHolder::class.java.declaredClasses.single())
        assertEquals("commit/accessibility: the list kept in the resolver was not the one field, found $list", listOf("private final seen"), list)
        assertTrue("commit/accessibility: a static kept in a companion was not listed, found $static", static.any { it.endsWith(" last") })
        assertTrue("commit/accessibility: a text buffer kept in the insert rule was not listed, found $buffer", buffer.any { it.endsWith(" log") })
        assertTrue("commit/accessibility: a var in a nested class was not listed, found $held", held.any { it.endsWith(" held") })
        assertNotEquals("commit/accessibility: a list kept in the resolver was not seen", resolverShape, list)
        assertNotEquals("commit/accessibility: a static kept in a companion was not seen", resolverShape, static)
        assertNotEquals("commit/accessibility: a text buffer kept in the insert rule was not seen", planShape, buffer)
        assertNotEquals("commit/accessibility: a var in a nested class was not seen", spanShape, held)
    }
}
