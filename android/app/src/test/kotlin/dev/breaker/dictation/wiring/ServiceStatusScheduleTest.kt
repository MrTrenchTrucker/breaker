package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The foreground + two-hour rule, driven by a fake clock, a fake post and a fake asker. */
class ServiceStatusScheduleTest {

    private val post = FakeDelayedPost()
    private val asker = FakeAsker()
    private val twoHours: Long = 2 * 60 * 60 * 1000L

    private fun schedule(
        asked: () -> Boolean = { true },
        scripted: ServiceStatusAsker = asker,
        onAnswer: (ServiceStatusAnswer) -> Unit = {},
    ): ServiceStatusSchedule = ServiceStatusSchedule(scripted, asked, post, statusClock(post), onAnswer)

    @Test
    fun `it asks once on the foreground and arms the next ask two hours out`() {
        val answers = mutableListOf<ServiceStatusAnswer>()
        val schedule = schedule(onAnswer = { answers.add(it) })
        schedule.onResume()
        assertEquals(1, asker.asks)
        assertEquals("the next ask is armed two hours after the one that just ran", twoHours, post.posts.single().dueAt)
        assertEquals(1, answers.size)
        schedule.onResume()
        assertEquals("a second foreground without a pause does not ask twice", 1, asker.asks)
    }

    @Test
    fun `it asks again when the clock reaches the two-hour mark`() {
        schedule().onResume()
        post.advance(twoHours)
        assertEquals(2, asker.asks)
    }

    @Test
    fun `it does not ask before two hours`() {
        schedule().onResume()
        post.advance(twoHours - 1)
        assertEquals(1, asker.asks)
    }

    @Test
    fun `it stops asking when the launcher is paused`() {
        val schedule = schedule()
        schedule.onResume()
        post.advance(twoHours)
        assertEquals(2, asker.asks)
        schedule.onPause()
        assertTrue("the queued block is taken back", post.posts.last().cancelled)
        post.fireStale()
        assertEquals("nothing runs while the app is not resumed", 2, asker.asks)
    }

    @Test
    fun `it asks nothing when the caller is not asking`() {
        schedule(asked = { false }).onResume()
        assertEquals(0, asker.asks)
        assertTrue("nothing is queued while the caller is not asking", post.posts.isEmpty())
    }

    @Test
    fun `it asks on the next foreground once the caller is asking again`() {
        var allowed = false
        val schedule = schedule(asked = { allowed })
        schedule.onResume()
        assertEquals(0, asker.asks)
        allowed = true
        schedule.onPause()
        schedule.onResume()
        assertEquals(1, asker.asks)
    }

    @Test
    fun `a failure is a state, not a crash`() {
        asker.answers.add(ServiceStatusAnswer.Failed)
        val answers = mutableListOf<ServiceStatusAnswer>()
        schedule(onAnswer = { answers.add(it) }).onResume()
        assertEquals(listOf<ServiceStatusAnswer>(ServiceStatusAnswer.Failed), answers)
        assertEquals(1, asker.asks)
        post.advance(twoHours)
        assertEquals("a failure does not stop the next ask", 2, asker.asks)
    }
}
