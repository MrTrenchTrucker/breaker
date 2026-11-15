package dev.breaker.dictation.wiring

import dev.breaker.dictation.ui.HomeControl
import dev.breaker.shared.api.ServiceStatus
import dev.breaker.shared.api.ServiceStatusReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service-status Home wiring: one ask on a resume, the two-hour re-ask, the pause that takes
 * the queued ask back, and the answer handed to the Home view as plain facts.
 *
 * The clock and the post are the same fake source, so advancing the clock runs the queued ask by
 * hand, and [FakeAsker] serves a script of answers.
 */
class ServiceStatusHomeTest {

    private val post = FakeDelayedPost()
    private val asker = FakeAsker()
    private val home = FakeHome()
    private val twoHours: Long = 2 * 60 * 60 * 1000L

    private fun wired(asked: () -> Boolean = { true }): ServiceStatusHome =
        ServiceStatusHome(asker, asked, post, statusClock(post))

    private fun replyOf(vararg services: Pair<String, Boolean>): ServiceStatusAnswer =
        ServiceStatusAnswer.Answered(ServiceStatusReply(services.map { ServiceStatus(it.first, it.second) }))

    @Test
    fun `it asks once on resume and hands the reply to the Home view`() {
        asker.answers.add(replyOf("sync-api" to true, "whisper-server" to false))
        wired().onResume(home)
        assertEquals(1, asker.asks)
        assertEquals(
            listOf(FakeHome.Status(listOf("sync-api" to true, "whisper-server" to false), failed = false, asked = true)),
            home.status,
        )
        assertEquals("the next ask is armed two hours out", twoHours, post.posts.single().dueAt)
    }

    @Test
    fun `it does not ask again before two hours`() {
        wired().onResume(home)
        post.advance(twoHours - 1)
        assertEquals(1, asker.asks)
    }

    @Test
    fun `it asks again once the clock reaches the two-hour mark`() {
        wired().onResume(home)
        post.advance(twoHours)
        assertEquals(2, asker.asks)
    }

    @Test
    fun `it stops asking when the launcher is paused`() {
        val wiring = wired()
        wiring.onResume(home)
        post.advance(twoHours)
        assertEquals(2, asker.asks)
        wiring.onPause()
        assertTrue("the queued ask is taken back", post.posts.last().cancelled)
        post.fireStale()
        assertEquals("nothing runs while the app is not resumed", 2, asker.asks)
    }

    @Test
    fun `a resume followed by a pause cancels the ask that resume queued`() {
        val wiring = wired()
        wiring.onResume(home)
        val queued = post.posts.single()
        wiring.onPause()
        assertTrue("the queued ask is cancelled, so the pause reaches the instance that armed it", queued.cancelled)
        post.fireStale()
        assertEquals(1, asker.asks)
    }

    @Test
    fun `nothing is asked and the line is taken away when sync is off, the session is dead or no address is set`() {
        var syncOn = true
        var sessionLive = true
        var address = "https://server.test"
        val wiring = wired(asked = { syncOn && sessionLive && address.isNotBlank() })
        val off = listOf(
            "sync off" to { syncOn = false },
            "signed out" to { sessionLive = false },
            "no address" to { address = " " },
        )
        for ((name, turnOff) in off) {
            turnOff()
            wiring.onResume(home)
            assertEquals("asked while $name", 0, asker.asks)
            assertEquals(
                "the Home view is told nothing was asked while $name",
                FakeHome.Status(null, failed = false, asked = false),
                home.status.last(),
            )
            wiring.onPause()
            home.status.clear()
            syncOn = true
            sessionLive = true
            address = "https://server.test"
        }
        assertTrue("nothing is queued while the caller is not asking", post.posts.none { !it.cancelled && !it.fired })
    }

    @Test
    fun `a failure is a state, not a crash`() {
        asker.answers.add(ServiceStatusAnswer.Failed)
        wired().onResume(home)
        assertEquals(1, asker.asks)
        assertEquals(FakeHome.Status(null, failed = true, asked = true), home.status.last())
        post.advance(twoHours)
        assertEquals("a failure does not stop the next ask", 2, asker.asks)
    }

    /** The facts the app hands the Home view, recorded in the order they arrived. */
    private class FakeHome : HomeControl {
        data class Status(val services: List<Pair<String, Boolean>>?, val failed: Boolean, val asked: Boolean)

        val status: MutableList<Status> = ArrayList()

        override fun showLine(line: String?) {}

        override fun showRecording(recording: Boolean) {}

        override fun refresh() {}

        override fun showServiceStatus(services: List<Pair<String, Boolean>>?, failed: Boolean, asked: Boolean) {
            status.add(Status(services, failed, asked))
        }
    }
}
