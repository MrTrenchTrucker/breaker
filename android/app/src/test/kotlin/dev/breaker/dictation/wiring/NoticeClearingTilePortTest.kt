package dev.breaker.dictation.wiring

import dev.breaker.dictation.overlay.TileState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A fake of [DelayedPost] with no real time: each post is kept with its due time, a cancel marks it,
 * and [advance] moves the clock on and runs every live post that falls due on the way, in due order.
 */
internal class FakeDelayedPost : DelayedPost {

    internal class Post(val dueAt: Long, val block: () -> Unit) {
        var cancelled: Boolean = false
        var fired: Boolean = false
    }

    val posts: MutableList<Post> = ArrayList()
    var now: Long = 0L
        private set

    /** When set, [postDelayed] throws it, the way a refusing queue would. */
    var postError: RuntimeException? = null

    /** When set, the handle's cancel throws it after it has marked the post cancelled. */
    var handleError: RuntimeException? = null

    override fun postDelayed(delayMs: Long, block: () -> Unit): PostHandle {
        postError?.let { throw it }
        val post = Post(now + delayMs, block)
        posts.add(post)
        return object : PostHandle {
            override fun cancel() {
                post.cancelled = true
                handleError?.let { throw it }
            }
        }
    }

    /** Moves the clock on by [ms], running each live post that falls due, in due order. */
    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = posts.filter { !it.cancelled && !it.fired && it.dueAt <= target }
                .minByOrNull { it.dueAt } ?: break
            now = next.dueAt
            next.fired = true
            next.block()
        }
        now = target
    }

    /** The posts that are neither cancelled nor run. */
    fun live(): Int = posts.count { !it.cancelled && !it.fired }
}

internal class NoticeClearingTilePortTest {

    private val tile = RecordingTile()
    private val delayed = FakeDelayedPost()
    private var dismissed: Int = 0
    private val port = NoticeClearingTilePort(tile, delayed, onDismissed = { dismissed += 1 })

    /** How many times the tile was asked to clear its notice. */
    private fun clears(): Int = tile.calls.count { it == "clearNotice" }

    @Test
    fun `a notice is cleared at exactly four thousand milliseconds`() {
        port.showNotice("first")
        delayed.advance(3999)
        assertEquals("app: the notice was cleared before 4000 ms", 0, clears())
        delayed.advance(1)
        assertEquals("app: the notice was not cleared at 4000 ms", 1, clears())
    }

    @Test
    fun `the clear delay constant is four thousand milliseconds`() {
        assertEquals("app: the clear delay is not 4000 ms", 4000L, NOTICE_CLEAR_MS)
    }

    @Test
    fun `a second notice replaces the first and only the second post is live`() {
        port.showNotice("first")
        delayed.advance(1000)
        port.showNotice("second")
        delayed.advance(3000)
        assertEquals("app: the first post still cleared the tile at its old due time", 0, clears())
        assertEquals("app: more than the second post is live", 1, delayed.live())
        delayed.advance(1000)
        assertEquals("app: the second notice was not cleared 4000 ms after it was shown", 1, clears())
        assertEquals("app: the notices are not in the order shown", listOf("first", "second"), tile.notices)
    }

    @Test
    fun `a later notice after the timer cleared the first is cleared again`() {
        port.showNotice("a")
        delayed.advance(4000)
        port.showNotice("b")
        delayed.advance(4000)
        assertEquals("app: the second notice was not cleared by its own post", 2, clears())
    }

    @Test
    fun `a state change cancels the pending clear`() {
        port.showNotice("a")
        delayed.advance(1000)
        port.setState(TileState.RECORDING)
        delayed.advance(10000)
        assertEquals("app: the state change left the clear pending", 0, clears())
        assertEquals("app: the state was not passed on", listOf(TileState.RECORDING), tile.states)
    }

    @Test
    fun `an explicit clear cancels the pending clear`() {
        port.showNotice("a")
        delayed.advance(1000)
        port.clearNotice()
        delayed.advance(10000)
        assertEquals("app: the timer cleared the notice a second time", 1, clears())
    }

    @Test
    fun `hide cancels the pending clear`() {
        port.showNotice("a")
        port.hide()
        delayed.advance(10000)
        assertEquals("app: the timer cleared a tile that was hidden", 0, clears())
        assertEquals("app: hide was not passed on", listOf("notice", "hide"), tile.calls)
    }

    @Test
    fun `show cancels the pending clear`() {
        port.showNotice("a")
        port.show()
        delayed.advance(10000)
        assertEquals("app: the timer cleared the notice after a show", 0, clears())
    }

    @Test
    fun `a level change does not cancel the pending clear`() {
        port.showNotice("a")
        delayed.advance(1000)
        port.level(0.5f)
        delayed.advance(3000)
        assertEquals("app: a level change cancelled the clear", 1, clears())
        assertEquals("app: the level was not passed on", listOf(0.5f), tile.levels)
    }

    @Test
    fun `a tap on the notice cancels the clear and reports once`() {
        port.showNotice("a")
        delayed.advance(1000)
        port.onNoticeDismissed()
        assertEquals("app: the tap was not reported exactly once", 1, dismissed)
        delayed.advance(10000)
        assertEquals("app: the tapped notice was cleared later by the app", 0, clears())
        assertEquals("app: a post is still live after the tap", 0, delayed.live())
    }

    @Test
    fun `the tap report never clears the notice on the tile itself`() {
        port.showNotice("a")
        port.onNoticeDismissed()
        assertEquals("app: the tap report asked the tile to clear", 0, clears())
    }

    @Test
    fun `a notice tapped away does not come back`() {
        port.showNotice("a")
        port.onNoticeDismissed()
        delayed.advance(10000)
        assertEquals("app: the tapped notice was shown again", listOf("a"), tile.notices)
        assertEquals("app: the tapped notice was cleared later", 0, clears())
    }

    @Test
    fun `every member is forwarded to the inner tile once with the same argument`() {
        port.showNotice("t")
        port.show()
        port.hide()
        port.setState(TileState.SENDING)
        port.level(0.25f)
        port.clearNotice()
        assertEquals(
            "app: a member was not forwarded once in order",
            listOf("notice", "show", "hide", "state:SENDING", "clearNotice"),
            tile.calls,
        )
        assertEquals("app: the notice text was not forwarded", listOf("t"), tile.notices)
        assertEquals("app: the level was not forwarded", listOf(0.25f), tile.levels)
    }

    @Test
    fun `show returns the answer of the inner tile`() {
        tile.showResult = TileShow.NO_PERMISSION
        assertEquals("app: show does not return the inner answer", TileShow.NO_PERMISSION, port.show())
    }

    @Test
    fun `a throwing inner clear does not escape the timer`() {
        port.showNotice("a")
        tile.throwing = true
        delayed.advance(4000)
        assertEquals("app: the timer did not reach the tile before the throw", 1, clears())
    }

    @Test
    fun `a throwing handle does not escape the cancel`() {
        delayed.handleError = RuntimeException("the handle refused")
        port.showNotice("a")
        port.showNotice("b")
        assertEquals("app: the second post was not left live after the throw", 1, delayed.live())
        delayed.advance(4000)
        assertEquals("app: the second notice was not cleared after the throw", 1, clears())
    }

    @Test
    fun `a throwing post does not escape the notice`() {
        delayed.postError = RuntimeException("the queue refused")
        port.showNotice("a")
        assertEquals("app: the notice was not shown when the post was refused", listOf("a"), tile.notices)
        delayed.postError = null
        delayed.advance(10000)
        assertEquals("app: a refused post cleared the notice later", 0, clears())
    }

    @Test
    fun `a throwing callback does not escape the tap report`() {
        val throwing = NoticeClearingTilePort(tile, delayed, onDismissed = { throw RuntimeException("the callback refused") })
        throwing.showNotice("a")
        throwing.onNoticeDismissed()
        assertEquals("app: the tap report left a post live after the throw", 0, delayed.live())
    }

    @Test
    fun `the tap report is safe when no notice is pending`() {
        port.onNoticeDismissed()
        assertEquals("app: the tap was not reported with no notice pending", 1, dismissed)
        assertEquals("app: the tap report asked the tile to clear", 0, clears())
    }
}
