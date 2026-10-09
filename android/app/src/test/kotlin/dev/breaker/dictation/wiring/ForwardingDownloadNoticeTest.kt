package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

internal class ForwardingDownloadNoticeTest {

    private class RecordingNotice : ModelDownloadNotice {
        val calls = ArrayList<String>()
        override fun downloading() { calls.add("downloading") }
        override fun done() { calls.add("done") }
        override fun failed(sentence: String) { calls.add("failed:$sentence") }
    }

    @Test
    fun `downloading forwards to notifications and listener`() {
        val notifications = RecordingNotice()
        val listener = RecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        forwarder.downloading()
        assertEquals(listOf("downloading"), notifications.calls)
        assertEquals(listOf("downloading"), listener.calls)
    }

    @Test
    fun `done forwards to notifications and listener`() {
        val notifications = RecordingNotice()
        val listener = RecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        forwarder.done()
        assertEquals(listOf("done"), notifications.calls)
        assertEquals(listOf("done"), listener.calls)
    }

    @Test
    fun `failed forwards the sentence to notifications and listener`() {
        val notifications = RecordingNotice()
        val listener = RecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        forwarder.failed("boom")
        assertEquals(listOf("failed:boom"), notifications.calls)
        assertEquals(listOf("failed:boom"), listener.calls)
    }

    @Test
    fun `a cleared listener gets nothing while notifications still do`() {
        val notifications = RecordingNotice()
        val listener = RecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        forwarder.downloading()
        forwarder.setListener(null)
        forwarder.done()
        assertEquals(listOf("downloading", "done"), notifications.calls)
        assertEquals(listOf("downloading"), listener.calls)
    }
}
