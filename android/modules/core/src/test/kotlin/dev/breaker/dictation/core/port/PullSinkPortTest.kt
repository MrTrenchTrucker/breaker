package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.testing.FakePullSink
import dev.breaker.dictation.core.testing.aTranscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullSinkPortTest {
    @Test
    fun `the fake records the id and the time of each call in order`() {
        val sink = FakePullSink()
        sink.applyRemoteDelete(id = "t-1", deletedAt = 100L)
        sink.applyRemoteDelete(id = "t-2", deletedAt = 200L)
        assertEquals(2, sink.deletes.size)
        assertEquals("t-1", sink.deletes[0].first)
        assertEquals(100L, sink.deletes[0].second)
        assertEquals("t-2", sink.deletes[1].first)
        assertEquals(200L, sink.deletes[1].second)
    }

    @Test
    fun `the answer follows the setting true then set to false`() {
        val sink = FakePullSink(answer = true)
        assertTrue(sink.applyRemoteDelete(id = "t-1", deletedAt = 100L))
        sink.answer = false
        assertFalse(sink.applyRemoteDelete(id = "t-2", deletedAt = 200L))
    }

    @Test
    fun `two calls are two entries`() {
        val sink = FakePullSink()
        sink.applyRemoteDelete(id = "t-1", deletedAt = 100L)
        sink.applyRemoteDelete(id = "t-1", deletedAt = 200L)
        assertEquals(2, sink.deletes.size)
        assertEquals(100L, sink.deletes[0].second)
        assertEquals(200L, sink.deletes[1].second)
    }

    @Test
    fun `a call through a variable typed PullSink reaches the fake`() {
        val sink: PullSink = FakePullSink()
        sink.applyRemoteDelete(id = "t-1", deletedAt = 100L)
        assertEquals(1, (sink as FakePullSink).deletes.size)
    }

    @Test
    fun `the parameter names are id and deletedAt`() {
        val sink = FakePullSink()
        sink.applyRemoteDelete(deletedAt = 300L, id = "t-9")
        assertEquals(1, sink.deletes.size)
        assertEquals("t-9", sink.deletes[0].first)
        assertEquals(300L, sink.deletes[0].second)
    }

    @Test
    fun `the fake records each pulled row in order`() {
        val sink = FakePullSink()
        sink.applyRemoteRow(aTranscription(id = "t-1"))
        sink.applyRemoteRow(aTranscription(id = "t-2"))
        assertEquals(2, sink.rows.size)
        assertEquals("t-1", sink.rows[0].id)
        assertEquals("t-2", sink.rows[1].id)
    }

    @Test
    fun `rowAnswer follows its setting true then false`() {
        val sink = FakePullSink()
        assertTrue(sink.applyRemoteRow(aTranscription(id = "t-1")))
        sink.rowAnswer = false
        assertFalse(sink.applyRemoteRow(aTranscription(id = "t-2")))
    }

    @Test
    fun `a row call through a variable typed PullSink reaches the fake`() {
        val sink: PullSink = FakePullSink()
        sink.applyRemoteRow(aTranscription(id = "t-1"))
        assertEquals(1, (sink as FakePullSink).rows.size)
    }

    @Test
    fun `the parameter name is row`() {
        val sink = FakePullSink()
        sink.applyRemoteRow(row = aTranscription(id = "t-9"))
        assertEquals(1, sink.rows.size)
        assertEquals("t-9", sink.rows[0].id)
    }
}
