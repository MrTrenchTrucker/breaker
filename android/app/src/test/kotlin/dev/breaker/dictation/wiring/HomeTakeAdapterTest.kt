package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.DictationState
import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Home take adapter over the shared port: the stored-off check and the first tap. Each test
 * says in its KDoc what would break it.
 *
 * The ports are the shared [HomeTakeFakes] ones, so this test and the settle test drive the
 * same [TakePort], [HistoryStore] and clipboard and neither declares its own.
 */
internal class HomeTakeAdapterTest {

    private val log = ArrayList<String>()
    private val committer = CountingCommitter()
    private val store = InMemoryHistoryStore(log)
    private val clipboard = FakeTakenClipboard()
    private val take = FakeHomeTake(committer, log)
    private val settle = TakeSettle(store, clipboard)
    private val background = FakeBackground()
    private val main = FakeMainPost()
    private val lines = ArrayList<String?>()
    private val states = ArrayList<DictationState>()

    private fun adapter(switch: ArmedSwitch): HomeTakeAdapter {
        lines.clear()
        states.clear()
        return HomeTakeAdapter(
            take = take,
            settle = settle,
            switch = switch,
            background = background,
            main = main,
            onLine = { line -> lines.add(line); log.add("line:$line") },
            onState = { state -> states.add(state); log.add("state:$state") },
        )
    }

    /** The switch the Home take shares, over the in-memory off-store; [off] is the user's stored choice. */
    private fun homeSwitch(off: Boolean): HomeSwitch {
        val launcher = FakeLauncher()
        val controller = DictationServiceController(FakePermission(), launcher)
        val offStore = MemoryOffStore()
        offStore.off = off
        val switch = ArmedSwitch(controller, offStore)
        controller.setStateObserver { armed, reason -> switch.onStateChanged(armed, reason) }
        return HomeSwitch(switch, launcher)
    }

    /** Fails if the stored-off check is removed, so the adapter begins with the switch stored off. */
    @Test
    fun `a stored-off switch answers the off line and begins nothing`() {
        val home = homeSwitch(off = true)
        adapter(home.switch).onTap()
        assertEquals("app: home: the tap must answer the off line", HomeSentences.SWITCHED_OFF, lines.single())
        assertEquals("app: home: no begin may be called", 0, take.beginCount)
        assertEquals("app: home: no arm may be asked of the launcher", 0, home.launcher.launches)
        assertEquals("app: home: no state may be pushed", emptyList<DictationState>(), states)
        assertEquals("app: home: no save and no copy may run", 0, store.calls.size + clipboard.copies)
        assertEquals("app: home: no committer may be reached", 0, committer.commits)
    }

    /** Fails if a switch that is on does not begin the take exactly once. */
    @Test
    fun `a switch that is on begins the take once`() {
        adapter(homeSwitch(off = false).switch).onTap()
        assertEquals("app: home: the tap must begin once", 1, take.beginCount)
        assertEquals("app: home: the take must push RECORDING", listOf(DictationState.RECORDING), states)
        assertEquals("app: home: the plain line comes back", listOf<String?>(null), lines)
    }

    /** Fails if the first tap finishes the take and settles it at once. */
    @Test
    fun `the first tap begins once and finishes nothing`() {
        adapter(homeSwitch(off = false).switch).onTap()
        assertEquals("app: home: one begin", 1, take.beginCount)
        assertEquals("app: home: no finish", 0, take.finishCount)
        assertEquals("app: home: RECORDING is pushed", listOf(DictationState.RECORDING), states)
    }

    /** Fails if a thrown finish escapes, so the take never settles. */
    @Test
    fun `a thrown finish settles as not-converted`() {
        take.finishThrows = true
        val a = adapter(homeSwitch(off = false).switch)
        a.onTap()
        a.onTap()
        assertEquals(
            "app: home: the settle still runs",
            listOf<String?>(null, "The speech could not be converted."),
            lines,
        )
        assertEquals("app: home: a thrown finish ends IDLE", DictationState.IDLE, states.last())
    }

    /** The switch the adapter is given, with the launcher it must never have to call. */
    private class HomeSwitch(val switch: ArmedSwitch, val launcher: FakeLauncher)
}
