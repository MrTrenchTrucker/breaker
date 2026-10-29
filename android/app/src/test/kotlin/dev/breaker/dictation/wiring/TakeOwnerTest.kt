package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.core.usecase.SendResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The take owner and the port that keeps it: a begin that records makes its path the holder, every settle
 * clears the holder, and a refused begin during another path's take keeps that path as the holder. Each
 * test says in its KDoc what would break it.
 */
internal class TakeOwnerTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val home = OwnedTakePort(take, owner, TakeOwnerKind.HOME)

    /** Fails if a begin that records does not make its path the holder. */
    @Test
    fun `a begin that records makes its path the holder`() {
        home.begin()
        assertEquals("app: the recording begin must make the Home the holder", TakeOwnerKind.HOME, owner.current())
        assertTrue("app: the Home holds the take", owner.heldByHome())
    }

    /** Fails if the tile's port does not record the tile as the holder. */
    @Test
    fun `a begin that records through the tile port makes the tile the holder`() {
        OwnedTakePort(take, owner, TakeOwnerKind.TILE).begin()
        assertEquals("app: the tile's recording begin must make the tile the holder", TakeOwnerKind.TILE, owner.current())
        assertFalse("app: a tile holder is not the Home's to finish", owner.heldByHome())
    }

    /** Fails if a finish leaves the holder set. */
    @Test
    fun `a finish releases the holder`() {
        home.begin()
        home.finish()
        assertNull("app: a finish must release the holder", owner.current())
    }

    /** Fails if a cancel leaves the holder set. */
    @Test
    fun `a cancel releases the holder`() {
        home.begin()
        home.cancel()
        assertNull("app: a cancel must release the holder", owner.current())
    }

    /** Fails if a send leaves the holder set. */
    @Test
    fun `a send releases the holder`() {
        home.begin()
        home.send()
        assertNull("app: a send must release the holder", owner.current())
    }

    /** Fails if the reset after a taken take leaves the holder set. */
    @Test
    fun `the reset after a taken take releases the holder`() {
        home.begin()
        home.resetAfterTaken()
        assertNull("app: the reset must release the holder", owner.current())
    }

    /** Fails if a refused begin during another path's take takes the holder away from that path. */
    @Test
    fun `a refused begin during a take keeps the holder of that take`() {
        take.listeningElsewhere()
        owner.begun(TakeOwnerKind.TILE)
        take.beginResult = BeginResult.Refused(RunnerSentences.BUSY)
        home.begin()
        assertEquals("app: a refusal during a take must keep its holder", TakeOwnerKind.TILE, owner.current())
    }

    /** Fails if a refused begin with no take running leaves a holder behind. */
    @Test
    fun `a refused begin with no take running clears the holder`() {
        owner.begun(TakeOwnerKind.TILE)
        take.beginResult = BeginResult.Refused(RunnerSentences.BUSY)
        home.begin()
        assertNull("app: a refusal with no take running must clear the holder", owner.current())
    }

    /** Fails if a failed start leaves the holder set. */
    @Test
    fun `a failed start with no take running clears the holder`() {
        owner.begun(TakeOwnerKind.HOME)
        take.beginResult = BeginResult.Failed(RunnerSentences.COULD_NOT_RECORD)
        home.begin()
        assertNull("app: a failed start must clear the holder", owner.current())
    }

    /** Fails if a mic end or a device failure, which the tile host reports by a plain clear, leaves the holder set. */
    @Test
    fun `a settle that the tile host reports clears a tile holder`() {
        owner.begun(TakeOwnerKind.TILE)
        owner.settled()
        assertNull("app: a mic end or a device failure must clear the tile holder", owner.current())
    }

    /** Fails if a settle clears the holder after its port call: the port must see no holder at each of the four settles. */
    @Test
    fun `every settle clears the holder before the port call`() {
        val probe = HolderSeenByPort(owner)
        val port = OwnedTakePort(probe, owner, TakeOwnerKind.HOME)
        owner.begun(TakeOwnerKind.HOME)
        port.finish()
        owner.begun(TakeOwnerKind.HOME)
        port.send()
        owner.begun(TakeOwnerKind.HOME)
        port.cancel()
        owner.begun(TakeOwnerKind.HOME)
        port.resetAfterTaken()
        assertEquals("app: the port must see no holder at any settle", listOf<TakeOwnerKind?>(null, null, null, null), probe.seen)
    }

    /** Records the holder the port sees at each settle call; answers as a take that was just finished would. */
    private class HolderSeenByPort(private val owner: TakeOwner) : TakePort {
        val seen: MutableList<TakeOwnerKind?> = ArrayList()

        override val sessionState: DictationState
            get() = DictationState.RECORDING

        override fun begin(): BeginResult = BeginResult.Recording

        override fun finish(trimBeforeMs: Long?): FinishResult {
            seen.add(owner.current())
            return FinishResult.ReadyToSend(SAMPLE)
        }

        override fun send(): SendResult? {
            seen.add(owner.current())
            return null
        }

        override fun cancel() {
            seen.add(owner.current())
        }

        override fun resetAfterTaken() {
            seen.add(owner.current())
        }
    }
}
