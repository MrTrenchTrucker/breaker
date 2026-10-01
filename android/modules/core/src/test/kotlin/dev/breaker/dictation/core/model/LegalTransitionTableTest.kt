package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The state machine refuses moves that are not in its table.
 *
 * [DictationSessionTest] checks the individual rules. This file checks the
 * *table itself*: it walks every (from, to) pair the six states can produce and
 * asserts that the machine's verdict matches the documented table exactly. A
 * rule that is only spot-checked is a rule that can quietly rot — one added
 * edge to [DictationSession.LEGAL_TRANSITIONS] would let a dictation jump from
 * idle straight to sending, and the existing tests would never notice.
 *
 * Two properties are pinned:
 * 1. Every pair either is in the table and is allowed, or is not in the table
 *    and throws. There is no third behaviour, and no silent tolerance.
 * 2. The table is the one the card describes: a strict happy path
 *    `IDLE -> ARMED -> RECORDING -> TRANSCRIBING -> SENDING -> IDLE`, with
 *    ERROR reachable only from the two states that can fail and always
 *    returning to idle.
 */
class LegalTransitionTableTest {
    private val allStates = DictationState.values().toList()

    @Test
    fun `every pair of states is either allowed by the table or refused`() {
        allStates.forEach { from ->
            allStates.forEach { to ->
                val session = DictationSession(state = from)
                val legal = to in DictationSession.LEGAL_TRANSITIONS[from].orEmpty()
                if (legal) {
                    val moved = session.transitionTo(to)
                    assertEquals(
                        "$from -> $to is in the table but the session did not move",
                        to,
                        moved.state,
                    )
                } else {
                    try {
                        session.transitionTo(to)
                        fail("$from -> $to is NOT in the table but the machine allowed it")
                    } catch (e: IllegalStateException) {
                        // The message is the contract: it names the move and what
                        // would have been legal, so a wiring bug is diagnosable
                        // from a crash report alone.
                        assertTrue(
                            "the message does not name the refused move: ${e.message}",
                            e.message!!.contains("$from -> $to"),
                        )
                        val expectedOptions = DictationSession.LEGAL_TRANSITIONS[from]
                            .orEmpty()
                            .joinToString(", ") { it.name }
                            .ifEmpty { "nothing" }
                        assertTrue(
                            "the message does not list the legal moves: ${e.message}",
                            e.message!!.contains(expectedOptions),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `no state may skip ahead on the happy path`() {
        // The shortcuts a wiring bug produces: straight to sending, straight to
        // transcribing, straight out of a failure to work.
        val shortcuts = listOf(
            Triple(DictationState.IDLE, DictationState.SENDING, "idle straight to sending"),
            Triple(DictationState.IDLE, DictationState.TRANSCRIBING, "idle straight to transcribing"),
            Triple(DictationState.ARMED, DictationState.SENDING, "armed straight to sending"),
            Triple(DictationState.RECORDING, DictationState.SENDING, "recording straight to sending"),
            Triple(DictationState.ERROR, DictationState.ARMED, "error straight back to armed"),
            Triple(DictationState.ERROR, DictationState.SENDING, "error straight to sending"),
            Triple(DictationState.SENDING, DictationState.TRANSCRIBING, "sending back to transcribing"),
        )
        shortcuts.forEach { (from, to, what) ->
            try {
                DictationSession(state = from).transitionTo(to)
                fail("$what ($from -> $to) must be refused")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("$from -> $to"))
            }
        }
    }

    @Test
    fun `a state may not transition to itself`() {
        // Self-transitions are not in the table, so a caller that re-enters a
        // state is told so rather than being handed a no-op session.
        allStates.forEach { state ->
            try {
                DictationSession(state = state).transitionTo(state)
                fail("$state -> $state must be refused: no state is its own legal move")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("$state -> $state"))
            }
        }
    }

    @Test
    fun `error is reachable only from the two states that can fail`() {
        val sources = DictationSession.LEGAL_TRANSITIONS
            .filterValues { DictationState.ERROR in it }
            .keys
        assertEquals(
            "only transcribing and sending may raise an error",
            setOf(DictationState.TRANSCRIBING, DictationState.SENDING),
            sources.toSet(),
        )
    }

    @Test
    fun `every state can get back to idle`() {
        // Cancel has to be possible everywhere, or a failed dictation would
        // leave the app stuck in a state it cannot leave. "Possible" means
        // reachable in one or more legal moves, not directly: a transcribing
        // session returns to idle by failing or by sending first.
        allStates.filter { it != DictationState.IDLE }.forEach { state ->
            val reachable = mutableSetOf(state)
            val pending = ArrayDeque(listOf(state))
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                DictationSession.LEGAL_TRANSITIONS[current].orEmpty()
                    .filter { reachable.add(it) }
                    .forEach { pending.addLast(it) }
            }
            assertTrue(
                "$state cannot get back to IDLE; reachable from it: $reachable",
                reachable.contains(DictationState.IDLE),
            )
        }
    }

    @Test
    fun `the happy path is reachable one step at a time`() {
        val path = listOf(
            DictationState.IDLE,
            DictationState.ARMED,
            DictationState.RECORDING,
            DictationState.TRANSCRIBING,
            DictationState.SENDING,
            DictationState.IDLE,
        )
        var session = DictationSession()
        path.drop(1).forEach { next ->
            val moved = session.transitionTo(next)
            assertEquals(next, moved.state)
            session = moved
        }
        // The table is not a dead end: idle really is idle again.
        assertTrue(!session.isBusy)
    }

    @Test
    fun `the named helpers cannot be used out of order`() {
        // arm/startRecording/withError/withTranscription are thin wrappers over
        // the table, so they must be exactly as strict as the table itself.
        val armed = DictationSession().arm()
        val recording = armed.startRecording()

        try {
            recording.arm()
            fail("expected arm() from recording to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("RECORDING -> ARMED"))
        }
        try {
            DictationSession().startRecording()
            fail("expected startRecording() from idle to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("IDLE -> RECORDING"))
        }
        try {
            armed.withTranscription(
                Transcription("t-1", "hello", TranscriptionSource.LOCAL, "small", 1L, 1L),
            )
            fail("expected withTranscription() from armed to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ARMED -> SENDING"))
        }
        try {
            recording.withError(SttError.TIMEOUT)
            fail("expected withError() from recording to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("RECORDING -> ERROR"))
        }
    }

    @Test
    fun `a refused move leaves the original session untouched`() {
        val session = DictationSession()
        try {
            session.transitionTo(DictationState.SENDING)
            fail("expected idle -> sending to be refused")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
        // Immutability is what makes a refusal safe: a caller holding a session
        // cannot be surprised by a half-applied move.
        assertEquals(DictationState.IDLE, session.state)
        assertEquals(DictationSession(), session)
    }

    @Test
    fun `an error carries its reason and clears it once the dictation is abandoned`() {
        val transcribing = DictationSession().arm().startRecording()
            .transitionTo(DictationState.TRANSCRIBING)
        val failed = transcribing.withError(SttError.LOCAL_MODEL_MISSING)
        assertEquals(SttError.LOCAL_MODEL_MISSING, failed.lastError)
        assertNotNull(failed.lastError)

        val abandoned = failed.cancel()
        assertEquals(DictationState.IDLE, abandoned.state)
        assertNull(abandoned.lastError)
    }
}
