package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.DictationServiceController
import dev.breaker.dictation.service.FakeLauncher
import dev.breaker.dictation.service.FakePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** A listener that writes each callback to a log and keeps the mark it was given, so a test can count the calls. */
private class RecordingOwnerListener : TakeOwnerListener {
    val log = ArrayList<String>()
    val marks = ArrayList<TakeMark>()

    override fun begun(kind: TakeOwnerKind, mark: TakeMark) {
        log.add("begun:$kind")
        marks.add(mark)
    }

    override fun cleared() {
        log.add("cleared")
    }
}

/** A switch over a fake controller and an off-store that is not set, for the Home wirings below. */
private fun switchOver(): ArmedSwitch =
    ArmedSwitch(DictationServiceController(FakePermission(), FakeLauncher()), MemoryOffStore())

/** A Home wiring over the shared fakes, registered on [owner]. */
private fun wiringOver(owner: TakeOwner, take: FakeHomeTake, switch: ArmedSwitch): HomeTakeWiring =
    HomeTakeWiring(
        take = { take },
        history = { InMemoryHistoryStore(ArrayList<String>()) },
        clipboard = FakeTakenClipboard(),
        switch = switch,
        owner = owner,
        background = FakeBackground(),
        main = FakeMainPost(),
        modelReady = { true },
    )

/** The take owner's listener slot and its Home send slot: which callbacks fire, and when. */
internal class TakeOwnerSlotsTest {

    @Test
    fun `begun fires the listener once with the kind and the take's own mark`() {
        val owner = TakeOwner()
        val listener = RecordingOwnerListener()
        owner.setListener(listener)
        owner.begun(TakeOwnerKind.HOME)
        assertEquals("app: begun must fire the listener once, with the kind", listOf("begun:HOME"), listener.log)
        assertSame("app: begun must hand over the mark the owner holds", owner.mark(), listener.marks.single())
    }

    @Test
    fun `a settle fires cleared once and a second settle fires nothing`() {
        val owner = TakeOwner()
        val listener = RecordingOwnerListener()
        owner.setListener(listener)
        owner.begun(TakeOwnerKind.TILE)
        owner.settled()
        owner.settled()
        assertEquals("app: a settle with a take held fires cleared once and nothing for the second", listOf("begun:TILE", "cleared"), listener.log)
        assertNull("app: a settle clears the holder", owner.current())
    }

    @Test
    fun `settledIf with a stale mark fires nothing and with the current mark fires once`() {
        val owner = TakeOwner()
        val listener = RecordingOwnerListener()
        owner.setListener(listener)
        owner.begun(TakeOwnerKind.HOME)
        val stale = owner.mark()
        owner.settled()
        owner.begun(TakeOwnerKind.TILE)
        val current = owner.mark()
        listener.log.clear()
        owner.settledIf(stale)
        assertEquals("app: a stale mark must not fire the clear", emptyList<String>(), listener.log)
        assertEquals("app: a stale mark must leave the newer take held", TakeOwnerKind.TILE, owner.current())
        owner.settledIf(current)
        assertEquals("app: the current mark fires the clear once", listOf("cleared"), listener.log)
        assertNull("app: the current mark clears the holder", owner.current())
    }

    @Test
    fun `a self-ended take clears the owner and fires the clear callback once`() {
        val owner = TakeOwner()
        val listener = RecordingOwnerListener()
        owner.setListener(listener)
        owner.begun(TakeOwnerKind.TILE)
        val mark = owner.mark()
        owner.settledIf(mark)
        owner.settledIf(mark)
        assertNull("app: the self-ended take must clear the owner", owner.current())
        assertEquals("app: the self-ended take fires the clear callback once", listOf("begun:TILE", "cleared"), listener.log)
    }

    @Test
    fun `a listener that was never set changes nothing`() {
        val owner = TakeOwner()
        owner.begun(TakeOwnerKind.HOME)
        assertEquals("app: begun without a listener still holds the take", TakeOwnerKind.HOME, owner.current())
        owner.settled()
        assertNull("app: settled without a listener still clears the take", owner.current())
    }

    @Test
    fun `sendToHome with an empty slot drops without a call`() {
        val owner = TakeOwner()
        var calls = 0
        val entry: (Long?) -> Unit = { calls += 1 }
        owner.setHomeSend(entry)
        owner.clearHomeSendIf(entry)
        owner.sendToHome(1500L)
        assertEquals("app: a send to an empty Home slot must not reach any entry", 0, calls)
    }

    @Test
    fun `sendToHome with a registered entry calls it once per send with the trim unchanged`() {
        val owner = TakeOwner()
        val trims = ArrayList<Long?>()
        owner.setHomeSend { trims.add(it) }
        owner.sendToHome(1500L)
        owner.sendToHome(250L)
        owner.sendToHome(null)
        assertEquals("app: each send reaches the entry once, with its trim unchanged", listOf(1500L, 250L, null), trims)
    }

    @Test
    fun `after unwatchSwitch the Home send is dropped`() {
        val owner = TakeOwner()
        val take = FakeHomeTake(CountingCommitter(), ArrayList<String>())
        val wiring = wiringOver(owner, take, switchOver())
        take.listeningElsewhere()
        owner.begun(TakeOwnerKind.HOME)
        owner.sendToHome(250L)
        assertEquals("app: before the teardown the Home send reaches the take once", 1, take.finishCount)
        wiring.unwatchSwitch(wiring.switchListener)
        take.listeningElsewhere()
        owner.begun(TakeOwnerKind.HOME)
        owner.sendToHome(250L)
        assertEquals("app: after the teardown the Home send is dropped", 1, take.finishCount)
    }

    @Test
    fun `a newer wiring's entry survives an older wiring's unwatchSwitch`() {
        val owner = TakeOwner()
        val switch = switchOver()
        val older = wiringOver(owner, FakeHomeTake(CountingCommitter(), ArrayList<String>()), switch)
        val newerTake = FakeHomeTake(CountingCommitter(), ArrayList<String>())
        wiringOver(owner, newerTake, switch)
        older.unwatchSwitch(older.switchListener)
        newerTake.listeningElsewhere()
        owner.begun(TakeOwnerKind.HOME)
        owner.sendToHome(250L)
        assertEquals("app: the newer wiring's Home send must survive the older wiring's teardown", 1, newerTake.finishCount)
    }
}
