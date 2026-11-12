package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home take adapter while a finish is in flight. A tap during SENDING is ignored silently, the guard
 * clears in the settle and in the refuse-catch, a second check finishes once, and a settle whose outcome
 * line throws still clears the guard. Each test names in its KDoc the change that makes it fail.
 *
 * Only the first and the fifth tests fail on the unguarded adapter; the others pass there and fail on
 * their named change. The fakes are shared: [FakeHomeTake], [ManualBackground], [ManualMain], [FakeBackground],
 * [FakeMainPost] and [MemoryOffStore] are the ones the adapter tests already use.
 */
internal class HomeTakeAdapterSendingGuardTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val owner = TakeOwner()
    private val settle = TakeSettle(store, clipboard)
    private val main = FakeMainPost()
    private val lines = ArrayList<String?>()
    private val states = ArrayList<DictationState>()

    /** The adapter over the shared ports; the switch is on unless [offStore] says it is stored off. */
    private fun sendingGuardAdapter(
        background: Background,
        main: MainPost = this.main,
        offStore: MemoryOffStore = MemoryOffStore(),
        onLine: (String?) -> Unit = { line -> lines.add(line) },
    ): HomeTakeAdapter {
        val controller = DictationServiceController(FakePermission(), FakeLauncher())
        return HomeTakeAdapter(
            take = OwnedTakePort(take, owner, TakeOwnerKind.HOME),
            owner = owner,
            settle = settle,
            switch = ArmedSwitch(controller, offStore),
            background = background,
            main = main,
            onLine = onLine,
            onState = { state -> states.add(state) },
        )
    }

    /**
     * Fails on the unguarded adapter: the third tap queues a begin behind the finish, so one block is pending
     * instead of none. Fails when the check in onTap is removed: "queues no begin" sees two pending blocks.
     */
    @Test
    fun `a tap during SENDING queues no begin and pushes nothing`() {
        val bg = ManualBackground()
        val a = sendingGuardAdapter(bg)
        a.onTap()
        bg.runAll()
        a.onTap()
        assertEquals("app: home: only the finish is queued after the second tap", 1, bg.pending)
        val before: List<DictationState> = states.toList()
        a.onTap()
        assertEquals("app: home: a tap during SENDING queues no begin", 1, bg.pending)
        assertEquals("app: home: a tap during SENDING pushes no state", before, states)
        bg.runAll()
        assertEquals("app: home: the only begin is the first tap", 1, take.beginCount)
        assertEquals(
            "app: home: the take settles IDLE and nothing follows it",
            listOf(DictationState.RECORDING, DictationState.SENDING, DictationState.IDLE),
            states,
        )
    }

    /**
     * Passes on the unguarded adapter. Fails when the clear is deleted from the settle block: the next tap is
     * ignored and "the tap after the settle begins a take" sees one begin, not two. Also fails when the set
     * is moved after the submit: the inline settle clears first, then the set leaves the guard on.
     */
    @Test
    fun `the guard clears when the settle runs and the next tap begins`() {
        val a = sendingGuardAdapter(FakeBackground())
        a.onTap()
        a.onTap()
        a.onTap()
        assertEquals("app: home: the tap after the settle begins a take", 2, take.beginCount)
        assertEquals("app: home: the tap after the settle shows RECORDING", DictationState.RECORDING, states.last())
    }

    /**
     * Passes on the unguarded adapter. Fails when the clear is deleted from the refuse-catch: the next tap is
     * ignored and "the tap after a refused finish begins" sees one begin, not two.
     */
    @Test
    fun `the guard clears when the background refuses the finish and the next tap begins`() {
        val bg = ManualBackground()
        val a = sendingGuardAdapter(bg)
        a.onTap()
        bg.runAll()
        bg.submitError = IllegalStateException("the background refused the finish")
        a.onTap()
        bg.submitError = null
        a.onTap()
        bg.runAll()
        assertEquals("app: home: the tap after a refused finish begins", 2, take.beginCount)
        assertEquals(
            "app: home: the refused finish settles IDLE before the new take",
            listOf(DictationState.RECORDING, DictationState.SENDING, DictationState.IDLE, DictationState.RECORDING),
            states,
        )
    }

    /**
     * Passes on the unguarded adapter. Fails when the owner clear at the start of finishAndSettle is deleted:
     * the second check then queues a second finish, and "the second check queues no finish" sees two pending.
     */
    @Test
    fun `a double onSend finishes the take once`() {
        val bg = ManualBackground()
        val a = sendingGuardAdapter(bg)
        a.onTap()
        bg.runAll()
        a.onSend()
        a.onSend()
        assertEquals("app: home: the second check queues no finish", 1, bg.pending)
        bg.runAll()
        assertEquals("app: home: the take is finished once", 1, take.finishCount)
    }

    /**
     * Fails on the unguarded adapter: the third tap reaches beginFromTap, which answers the stored-off line.
     * Fails also when the guard is moved below the stored-off check: the line is still answered.
     */
    @Test
    fun `a tap during SENDING with the switch turned off meanwhile is silent`() {
        val bg = ManualBackground()
        val offStore = MemoryOffStore()
        val a = sendingGuardAdapter(bg, offStore = offStore)
        a.onTap()
        bg.runAll()
        a.onTap()
        offStore.off = true
        val before: List<String?> = lines.toList()
        a.onTap()
        assertEquals("app: home: a tap during SENDING shows no line, switch off or not", before, lines)
        assertEquals("app: home: a tap during SENDING queues nothing with the switch off", 1, bg.pending)
    }

    /**
     * Passes on the guarded adapter, where the clear is the first statement of the settle block. Fails when
     * that clear is moved below the line push: the outcome line throws here, the clear never runs, and
     * "the tap after a line that threw begins a take" sees one begin, not two.
     */
    @Test
    fun `a settle whose outcome line throws still lets the next tap begin`() {
        val bg = ManualBackground()
        val deferred = ManualMain()
        val a = sendingGuardAdapter(
            bg,
            main = deferred,
            onLine = { line ->
                if (line != null) throw IllegalStateException("the outcome line was refused")
                lines.add(line)
            },
        )
        a.onTap()
        bg.runAll()
        deferred.drain()
        a.onTap()
        bg.runAll()
        var refused = false
        try {
            deferred.drain()
        } catch (e: IllegalStateException) {
            refused = true
        }
        assertEquals("app: home: the outcome line was refused", true, refused)
        a.onTap()
        bg.runAll()
        assertEquals("app: home: the tap after a line that threw begins a take", 2, take.beginCount)
    }
}
