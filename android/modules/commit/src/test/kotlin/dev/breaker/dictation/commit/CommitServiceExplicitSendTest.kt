package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitOutcome
import dev.breaker.dictation.core.port.CommitOutcomeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text is committed only when the caller asks for it. Building the service,
 * and a field coming, changing or going, never put text anywhere.
 */
internal class CommitServiceExplicitSendTest {

    private fun registryService(rig: Rig, registry: FocusedFieldRegistry): CommitService =
        CommitService(registry, rig.clipboard, rig.notice, rig.hop, 32)

    private fun assertNoTextMoved(rig: Rig, extra: FakeField?, moment: String) {
        assertTrue("commit: the field received text $moment", rig.field.received.isEmpty())
        if (extra != null) {
            assertTrue("commit: the second field received text $moment", extra.received.isEmpty())
        }
        assertTrue("commit: the clipboard was written $moment", rig.clipboard.writes.isEmpty())
        assertEquals("commit: the notice was shown $moment", 0, rig.notice.shown)
        assertEquals("commit: the hop was used $moment", 0, rig.hop.calls)
    }

    @Test
    fun `building the service puts no text anywhere`() {
        val rig = Rig(sdkInt = 32, withField = true)

        assertNoTextMoved(rig, null, "when the service was built")
        assertEquals("commit: the focus must not be read by construction", 0, rig.focus.reads)
    }

    @Test
    fun `publishing replacing and clearing a field never commits text`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val registry = FocusedFieldRegistry()
        registryService(rig, registry)
        val second = FakeField(rig.probe)

        val firstToken: FocusedFieldRegistry.Token = registry.publish(rig.field)
        assertNoTextMoved(rig, second, "after a field was published")

        val secondToken: FocusedFieldRegistry.Token = registry.publish(second)
        assertNoTextMoved(rig, second, "after a field was replaced")

        registry.clear(firstToken)
        assertNoTextMoved(rig, second, "after a stale clear")

        registry.clear(secondToken)
        assertNoTextMoved(rig, second, "after the current field was cleared")

        registry.publish(rig.field)
        registry.clearAll()
        assertNoTextMoved(rig, second, "after everything was cleared")
    }

    @Test
    fun `one commit call gives exactly one commit into the focused field`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val registry = FocusedFieldRegistry()
        val service: CommitService = registryService(rig, registry)
        registry.publish(rig.field)

        val result: CommitOutcomeResult = service.commit(Texts.request("first dictated text"))

        assertEquals("commit: the field must take the text", CommitOutcome.COMMITTED, result.outcome)
        assertEquals(
            "commit: one commit call must give exactly one text commit",
            listOf("first dictated text"),
            rig.field.received,
        )
    }

    @Test
    fun `a second commit call gives a second commit into the field`() {
        val rig = Rig(sdkInt = 32, withField = true)
        val registry = FocusedFieldRegistry()
        val service: CommitService = registryService(rig, registry)
        registry.publish(rig.field)

        service.commit(Texts.request("first dictated text"))
        service.commit(Texts.request("second dictated text"))

        assertEquals(
            "commit: each commit call must give its own text commit, in order",
            listOf("first dictated text", "second dictated text"),
            rig.field.received,
        )
    }

    @Test
    fun `a field published after a commit is not committed into until the next commit call`() {
        val rig = Rig(sdkInt = 32, withField = false)
        val registry = FocusedFieldRegistry()
        val service: CommitService = registryService(rig, registry)

        val copied: CommitOutcomeResult = service.commit(Texts.request("before any field"))
        assertEquals("commit: no field must give COPIED", CommitOutcome.COPIED, copied.outcome)

        registry.publish(rig.field)
        assertTrue("commit: a field coming into focus must not commit text", rig.field.received.isEmpty())
        assertEquals("commit: the clipboard must not be written again", 1, rig.clipboard.writes.size)

        val typed: CommitOutcomeResult = service.commit(Texts.request("after the field"))

        assertEquals("commit: the next commit call must use the field", CommitOutcome.COMMITTED, typed.outcome)
        assertEquals(
            "commit: only the second text may reach the field",
            listOf("after the field"),
            rig.field.received,
        )
    }

    @Test
    fun `a cleared field is not committed into by a later commit call`() {
        val rig = Rig(sdkInt = 32, withField = false)
        val registry = FocusedFieldRegistry()
        val service: CommitService = registryService(rig, registry)
        val token: FocusedFieldRegistry.Token = registry.publish(rig.field)
        registry.clear(token)

        val result: CommitOutcomeResult = service.commit(Texts.request("after the field left"))

        assertEquals("commit: a cleared field must give COPIED", CommitOutcome.COPIED, result.outcome)
        assertTrue("commit: a cleared field must not receive text", rig.field.received.isEmpty())
    }
}
