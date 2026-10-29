package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.PhraseEvent
import dev.breaker.dictation.core.port.PhraseTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The spoken send handler: one gate for each listening run, the trim carried unchanged to the path that
 * holds the take, and every callback of a stopped or replaced run dropped. The detector is a test-local
 * fake that keeps each callback it is given and logs its calls.
 */
internal class PhraseSendHandlerTest {

    /** A detector that keeps its callbacks and logs its calls; like the real detector, it marks itself listening before a failing start. */
    private class FakeTrigger : PhraseTrigger {
        val log: MutableList<String> = ArrayList()
        val callbacks: MutableList<(PhraseEvent) -> Unit> = ArrayList()
        var startError: Throwable? = null
        var stopError: Throwable? = null
        private var listening: Boolean = false

        override val isListening: Boolean
            get() = listening

        override fun start(onPhrase: (PhraseEvent) -> Unit) {
            log.add("start")
            listening = true
            callbacks.add(onPhrase)
            startError?.let { throw it }
        }

        override fun stop() {
            log.add("stop")
            listening = false
            stopError?.let { throw it }
        }

        fun stops(): Int = log.count { it == "stop" }
    }

    private val trigger = FakeTrigger()
    private val owner = TakeOwner()
    private val main = ManualMain()
    private val home: MutableList<Long?> = ArrayList()
    private val tile: MutableList<Long?> = ArrayList()
    private var unavailable: Int = 0

    private fun handler(): PhraseSendHandler =
        PhraseSendHandler(trigger, owner, main, { home.add(it) }, { tile.add(it) }, { unavailable++ })

    @Test
    fun `the first send of a take reaches the home surface with its trim unchanged`() {
        owner.begun(TakeOwnerKind.HOME)
        handler().start()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        main.drain()
        assertEquals("app: a home take should receive the trim as the detector sent it", listOf<Long?>(1_500L), home)
        assertEquals("app: a home take should not reach the tile", emptyList<Long?>(), tile)
    }

    @Test
    fun `the first send of a take reaches the tile surface with a null trim unchanged`() {
        owner.begun(TakeOwnerKind.TILE)
        handler().start()
        trigger.callbacks[0](PhraseEvent.Send(null))
        main.drain()
        assertEquals("app: a tile take should receive a null trim as null", listOf<Long?>(null), tile)
        assertEquals("app: a tile take should not reach the home surface", emptyList<Long?>(), home)
    }

    @Test
    fun `a 250 millisecond trim reaches the home surface as 250`() {
        owner.begun(TakeOwnerKind.HOME)
        handler().start()
        trigger.callbacks[0](PhraseEvent.Send(250L))
        main.drain()
        assertEquals("app: a home take should receive 250 as 250", listOf<Long?>(250L), home)
    }

    @Test
    fun `a second send of the same take is dropped`() {
        owner.begun(TakeOwnerKind.HOME)
        handler().start()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        trigger.callbacks[0](PhraseEvent.Send(250L))
        main.drain()
        assertEquals("app: only the first send of a take should reach the home surface", listOf<Long?>(1_500L), home)
    }

    @Test
    fun `a send with no take held is dropped`() {
        handler().start()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        main.drain()
        assertEquals("app: a send with no holder should reach no surface", emptyList<Long?>(), home)
        assertEquals("app: a send with no holder should reach no tile", emptyList<Long?>(), tile)
    }

    @Test
    fun `a send after stop is dropped`() {
        owner.begun(TakeOwnerKind.HOME)
        val h = handler()
        h.start()
        h.stop()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        assertEquals("app: a send after stop should post nothing to the main queue", 0, main.pending)
        main.drain()
        assertEquals("app: a send after stop should reach no surface", emptyList<Long?>(), home)
    }

    @Test
    fun `a send from an earlier start is dropped after a newer start`() {
        owner.begun(TakeOwnerKind.HOME)
        val h = handler()
        h.start()
        val earlier = trigger.callbacks[0]
        h.start()
        earlier(PhraseEvent.Send(1_500L))
        assertEquals("app: a send from an earlier run should post nothing to the main queue", 0, main.pending)
        main.drain()
        assertEquals("app: a send from the earlier run should reach no surface", emptyList<Long?>(), home)
        trigger.callbacks[1](PhraseEvent.Send(250L))
        main.drain()
        assertEquals("app: a send from the newer run should reach the home surface", listOf<Long?>(250L), home)
    }

    @Test
    fun `the gate re-opens on the next start for a new take`() {
        owner.begun(TakeOwnerKind.HOME)
        val h = handler()
        h.start()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        main.drain()
        h.start()
        trigger.callbacks[1](PhraseEvent.Send(250L))
        main.drain()
        assertEquals("app: each take should send once, so the second start should pass", listOf<Long?>(1_500L, 250L), home)
    }

    @Test
    fun `a send queued before stop is dropped when it is routed after stop`() {
        owner.begun(TakeOwnerKind.HOME)
        val h = handler()
        h.start()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        h.stop()
        main.drain()
        assertEquals("app: a send posted before stop should be dropped when it runs after stop", emptyList<Long?>(), home)
    }

    @Test
    fun `a send is routed on the main queue and not inline on the detection callback`() {
        owner.begun(TakeOwnerKind.HOME)
        handler().start()
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        assertEquals("app: the route should wait for the main queue", emptyList<Long?>(), home)
        main.drain()
        assertEquals("app: the route should run once the main queue drains", listOf<Long?>(1_500L), home)
    }

    @Test
    fun `a wake event is ignored and leaves the gate open`() {
        owner.begun(TakeOwnerKind.HOME)
        handler().start()
        trigger.callbacks[0](PhraseEvent.Wake)
        main.drain()
        assertEquals("app: a wake event should reach no surface", emptyList<Long?>(), home)
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        main.drain()
        assertEquals("app: the send after a wake should still reach the home surface once", listOf<Long?>(1_500L), home)
    }

    @Test
    fun `a refused start sends nothing, stops the detector and calls onUnavailable once on the main queue`() {
        owner.begun(TakeOwnerKind.HOME)
        trigger.startError = IllegalStateException("the detector refused the stream")
        handler().start()
        assertEquals("app: the owner should be told only from the main queue", 0, unavailable)
        main.drain()
        assertEquals("app: a refused start should tell the owner once", 1, unavailable)
        assertFalse("app: a refused start should leave the detector stopped", trigger.isListening)
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        main.drain()
        assertEquals("app: a callback of a refused start should route nothing", emptyList<Long?>(), home)
        assertEquals("app: a refused start should leave the take with its holder", TakeOwnerKind.HOME, owner.current())
    }

    @Test
    fun `a start refused with a link error is swallowed the same way`() {
        owner.begun(TakeOwnerKind.HOME)
        trigger.startError = LinkageError("the detector cannot be linked")
        handler().start()
        main.drain()
        assertEquals("app: a link error from start should tell the owner once", 1, unavailable)
        assertFalse("app: a link error from start should leave the detector stopped", trigger.isListening)
        trigger.callbacks[0](PhraseEvent.Send(1_500L))
        main.drain()
        assertEquals("app: a callback after a link error should route nothing", emptyList<Long?>(), home)
    }

    @Test
    fun `a virtual machine error from start is not swallowed`() {
        trigger.startError = OutOfMemoryError("the virtual machine is out of memory")
        try {
            handler().start()
            fail("app: a virtual machine error from start should reach the caller")
        } catch (e: OutOfMemoryError) {
            assertEquals("app: a virtual machine error should not tell the owner", 0, unavailable)
        }
    }

    @Test
    fun `a stop the detector refuses with an exception is swallowed`() {
        trigger.stopError = IllegalStateException("the detector refused to stop")
        val h = handler()
        h.start()
        h.stop()
        assertEquals("app: stop and start should call the detector in order, each stop refused and swallowed", listOf("stop", "start", "stop"), trigger.log)
    }

    @Test
    fun `stop called twice reaches the detector once`() {
        val h = handler()
        h.start()
        val before = trigger.stops()
        h.stop()
        h.stop()
        assertEquals("app: a second stop should not reach the detector", before + 1, trigger.stops())
    }

    @Test
    fun `start stops the detector before it starts it`() {
        handler().start()
        assertEquals("app: the detector should be stopped before it is started", listOf("stop", "start"), trigger.log)
    }

    @Test
    fun `two sends fired from two threads at once let exactly one through`() {
        owner.begun(TakeOwnerKind.HOME)
        val h = handler()
        h.start()
        val callback = trigger.callbacks[0]
        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        val threads = (1..2).map {
            Thread {
                go.await(5_000, TimeUnit.MILLISECONDS)
                callback(PhraseEvent.Send(1_500L))
                done.countDown()
            }
        }
        threads.forEach { it.start() }
        go.countDown()
        assertTrue("app: both sends should finish within the bound", done.await(5_000, TimeUnit.MILLISECONDS))
        main.drain()
        assertEquals("app: of two sends at once only one should reach the take", listOf<Long?>(1_500L), home)
    }
}
