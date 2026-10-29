package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The link from the take owner to the spoken Send: what is done, and in which order, when a take begins and
 * when it is settled. The worker is the manual queue, so each submitted block runs only when the test runs it.
 */
internal class PhraseTakeLinkTest {
    private val log: MutableList<String> = ArrayList()
    private val worker = ManualBackground()
    private val owner = TakeOwner()
    private val link = PhraseTakeLink(
        worker = worker,
        owner = owner,
        prepare = { log.add("prepare") },
        start = { log.add("start") },
        stop = { log.add("stop") },
    )

    @Test
    fun `begun prepares the stream once and submits one start to the worker`() {
        owner.setListener(link)
        owner.begun(TakeOwnerKind.TILE)
        assertEquals("app: a begin must prepare the stream once, before anything runs", listOf("prepare"), log)
        assertEquals("app: a begin must submit exactly one start", 1, worker.pending)
    }

    @Test
    fun `the submitted start runs start while the same take is still held`() {
        owner.setListener(link)
        owner.begun(TakeOwnerKind.TILE)
        worker.runAll()
        assertEquals("app: the start must run once the take it was made for is still held", listOf("prepare", "start"), log)
    }

    @Test
    fun `the submitted start does nothing once that take has ended`() {
        owner.setListener(link)
        owner.begun(TakeOwnerKind.TILE)
        owner.settled()
        worker.runAll()
        assertEquals("app: a take that ended before the worker ran must not start listening", listOf("prepare", "stop"), log)
    }

    @Test
    fun `a start queued for an older take does nothing when a newer take has begun`() {
        owner.setListener(link)
        owner.begun(TakeOwnerKind.TILE)
        owner.begun(TakeOwnerKind.HOME)
        worker.runAll()
        assertEquals("app: only the start of the take that is held runs", listOf("prepare", "prepare", "start"), log)
    }

    @Test
    fun `settled submits one stop and the stop runs after the start`() {
        owner.setListener(link)
        owner.begun(TakeOwnerKind.TILE)
        worker.runAll()
        owner.settled()
        assertEquals("app: a settle must submit one stop", 1, worker.pending)
        worker.runAll()
        assertEquals("app: the stop must run after the start", listOf("prepare", "start", "stop"), log)
    }

    @Test
    fun `a start and a stop queued in that order run in that order`() {
        owner.setListener(link)
        owner.begun(TakeOwnerKind.TILE)
        link.cleared()
        worker.runAll()
        assertEquals("app: the queued start and stop must keep their submit order", listOf("prepare", "start", "stop"), log)
    }

    @Test
    fun `with no listener set a begin and a settle change nothing`() {
        owner.begun(TakeOwnerKind.TILE)
        owner.settled()
        assertEquals("app: no listener means no prepare and no work", emptyList<String>(), log)
        assertEquals("app: no listener means nothing is submitted", 0, worker.pending)
    }
}
