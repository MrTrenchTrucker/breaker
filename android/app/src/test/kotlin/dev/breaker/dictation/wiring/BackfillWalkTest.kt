package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checked backfill walk: it says whether it reached the end of the tombstones. The tombstone sweep may
 * only follow a complete walk, so a walk the page cap stopped must never read as complete.
 */
class BackfillWalkTest {

    /** Hands out one scripted page per call, an empty page once the script is spent, and counts the calls. */
    private class Script(private val pages: List<List<String>>) {
        var calls = 0
        var lastLimit = -1
        val lastIds = mutableListOf<String>()

        fun first(limit: Int): List<String> {
            lastLimit = limit
            return next()
        }

        fun after(lastId: String, limit: Int): List<String> {
            lastLimit = limit
            lastIds.add(lastId)
            return next()
        }

        private fun next(): List<String> {
            val page = pages.getOrElse(calls) { emptyList() }
            calls++
            return page
        }
    }

    private fun walk(script: Script, limit: Int, maxPages: Int, queued: MutableList<String> = mutableListOf()): BackfillWalk =
        backfillAllUserTombstonesChecked(
            pendingIds = script::first,
            pendingIdsAfter = script::after,
            isDeleteQueued = { false },
            enqueueDelete = { queued.add(it) },
            limit = limit,
            maxPages = maxPages,
        )

    @Test
    fun `a short page ends the walk and it is complete`() {
        val script = Script(listOf(listOf("b", "a")))
        val result = walk(script, limit = 3, maxPages = 10)
        assertTrue("android_app: a short page is the end of the tombstones", result.complete)
        assertEquals("android_app: both ids were queued", 2, result.queued)
        assertEquals("android_app: no lookup after a short page", 1, script.calls)
    }

    @Test
    fun `an empty first page is a complete walk that queued nothing`() {
        val script = Script(emptyList())
        val result = walk(script, limit = 3, maxPages = 10)
        assertTrue("android_app: nothing to walk is a complete walk", result.complete)
        assertEquals("android_app: nothing was queued", 0, result.queued)
    }

    @Test
    fun `a full page followed by an empty page is complete`() {
        val script = Script(listOf(listOf("c", "b"), emptyList()))
        val result = walk(script, limit = 2, maxPages = 10)
        assertTrue("android_app: the empty page proves the end", result.complete)
        assertEquals("android_app: the two ids were queued", 2, result.queued)
        assertEquals("android_app: two lookups", 2, script.calls)
        assertEquals("android_app: the second page is asked below the last id of the first", listOf("b"), script.lastIds)
        assertEquals("android_app: every lookup carries the page size", 2, script.lastLimit)
    }

    @Test
    fun `a walk stopped by the page cap is not complete even when every page was full`() {
        val script = Script(listOf(listOf("d", "c"), listOf("b", "a"), listOf("z")))
        val result = walk(script, limit = 2, maxPages = 2)
        assertFalse("android_app: a walk the cap stopped must not read as complete", result.complete)
        assertEquals("android_app: four ids were queued before the cap", 4, result.queued)
        assertEquals("android_app: exactly two lookups", 2, script.calls)
    }

    @Test
    fun `a cap that lands on the last full page is still not complete`() {
        val script = Script(listOf(listOf("b", "a")))
        val result = walk(script, limit = 2, maxPages = 1)
        assertFalse("android_app: the walk cannot tell, so it is not complete", result.complete)
        assertEquals("android_app: the page was queued", 2, result.queued)
    }

    @Test
    fun `the walk that answers a count still reports how many deletes it queued`() {
        val script = Script(listOf(listOf("b", "a")))
        val queued = mutableListOf<String>()
        val count = backfillAllUserTombstones(
            pendingIds = script::first,
            pendingIdsAfter = script::after,
            isDeleteQueued = { false },
            enqueueDelete = { queued.add(it) },
            limit = 5,
            maxPages = 10,
        )
        assertEquals("android_app: the count", 2, count)
        assertEquals("android_app: the queued ids", listOf("b", "a"), queued)
    }
}
