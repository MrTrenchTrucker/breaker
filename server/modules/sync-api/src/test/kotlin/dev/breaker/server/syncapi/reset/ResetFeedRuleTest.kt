package dev.breaker.server.syncapi.reset

import dev.breaker.server.syncapi.transcriptions.PageResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reset does to the changes feed. Alice starts at change_seq 5 and purged_through 0. A reset makes
 * both 6, so every cursor from before the reset gets Gone and a new reader is never sent into a loop.
 */
internal class ResetFeedRuleTest : ResetStoreTestBase() {

    private fun page(since: Long?): PageResult = runBlocking { transcriptions.page(alice, since) }

    @Test
    fun `a cursor equal to the old change number is Gone after a reset and a page before it`() {
        assertTrue("sync-api: before the reset the cursor 5 is a page", page(5L) is PageResult.Page)
        reset("alice")
        assertEquals("sync-api: after the reset the cursor 5", PageResult.Gone, page(5L))
    }

    @Test
    fun `cursor 0 is Gone after a reset`() {
        reset("alice")
        assertEquals("sync-api: cursor 0", PageResult.Gone, page(0L))
    }

    @Test
    fun `a first page after a reset and then its next cursor are not Gone`() {
        reset("alice")
        val first = page(null)
        assertTrue("sync-api: the first page is a page: $first", first is PageResult.Page)
        val next = (first as PageResult.Page).next
        assertEquals("sync-api: the next cursor is the new change number", 6L, next)
        assertTrue("sync-api: the next cursor is a page", page(next) is PageResult.Page)
    }

    @Test
    fun `the next push after a reset takes a change number above purged_through`() {
        reset("alice")
        push(alice, 9, 3)
        assertEquals("sync-api: the change number of the new row", 7L, num("SELECT change_no FROM transcriptions WHERE account_id = $alice"))
        assertEquals("sync-api: change_seq", "7", col("change_seq"))
        assertEquals("sync-api: purged_through stays", "6", col("purged_through"))
        val after = page(6L)
        assertTrue("sync-api: the page after purged_through: $after", after is PageResult.Page)
        assertEquals("sync-api: one record", 1, (after as PageResult.Page).records.size)
        assertEquals("sync-api: the record change number", 7L, after.records.single().change)
    }
}
