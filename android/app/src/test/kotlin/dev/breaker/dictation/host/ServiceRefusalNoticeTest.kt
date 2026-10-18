package dev.breaker.dictation.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The refusal left by a service that could not go to the foreground. The sentence is held first, so the
 * launcher activity can show it on its next resume; a notice is posted only when notifications are
 * permitted. Each step is guarded: a failing step is skipped and nothing escapes. The platform is replaced by
 * plain functions that record their calls.
 */
internal class ServiceRefusalNoticeTest {

    private val sentence = "Android did not let Breaker start listening. Open Breaker and try again."

    /** A notice whose steps record their calls into [events], with the permission and the post as given. */
    private class Rig(permitted: () -> Boolean, postFails: Boolean = false) {
        val events: MutableList<String> = ArrayList()
        val posts: MutableList<String> = ArrayList()
        val hold = RefusalHold()
        val notice = ServiceRefusalNotice(
            hold = { held ->
                events.add("hold")
                hold.set(held)
            },
            notificationPermitted = {
                events.add("permitted")
                permitted()
            },
            postNotice = { posted ->
                events.add("post")
                posts.add(posted)
                if (postFails) throw UnsupportedOperationException("the post failed")
            },
        )
    }

    @Test
    fun `a refusal is held and posted when notifications are permitted, in that order`() {
        val rig = Rig(permitted = { true })
        rig.notice.refuse(sentence)
        assertEquals("app: the refusal must be posted once with the sentence", listOf(sentence), rig.posts)
        assertEquals("app: the order must be the hold, the permission, then the post", listOf("hold", "permitted", "post"), rig.events)
        assertEquals("app: the activity must find the sentence held", sentence, rig.hold.take())
    }

    @Test
    fun `a refusal with notifications denied is held and not posted`() {
        val rig = Rig(permitted = { false })
        rig.notice.refuse(sentence)
        assertEquals("app: a denied permission must not post", emptyList<String>(), rig.posts)
        assertEquals("app: the sentence must still be held", sentence, rig.hold.take())
    }

    @Test
    fun `the held sentence is taken once and a second take finds nothing`() {
        val hold = RefusalHold()
        hold.set(sentence)
        assertEquals("app: the first take must return the sentence", sentence, hold.take())
        assertNull("app: the second take must find nothing", hold.take())
    }

    @Test
    fun `a post that throws still leaves the sentence held`() {
        val rig = Rig(permitted = { true }, postFails = true)
        rig.notice.refuse(sentence)
        assertEquals("app: the post must have been tried", listOf(sentence), rig.posts)
        assertEquals("app: the sentence must be held even when the post fails", sentence, rig.hold.take())
    }

    @Test
    fun `a permission check that throws counts as denied`() {
        val rig = Rig(permitted = { throw UnsupportedOperationException("the check failed") })
        rig.notice.refuse(sentence)
        assertEquals("app: a failed check must not post", emptyList<String>(), rig.posts)
        assertEquals("app: the sentence must still be held", sentence, rig.hold.take())
    }

    @Test
    fun `a hold that throws does not stop the post`() {
        val posts: MutableList<String> = ArrayList()
        val notice = ServiceRefusalNotice(
            hold = { throw UnsupportedOperationException("the hold failed") },
            notificationPermitted = { true },
            postNotice = { posted -> posts.add(posted) },
        )
        notice.refuse(sentence)
        assertEquals("app: the post must still happen when the hold fails", listOf(sentence), posts)
    }
}
