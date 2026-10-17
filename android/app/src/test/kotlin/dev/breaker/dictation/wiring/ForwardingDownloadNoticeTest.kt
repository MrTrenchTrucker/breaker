package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

internal class ForwardingDownloadNoticeTest {

    private class RecordingNotice : ModelDownloadNotice {
        val calls = ArrayList<String>()
        override fun downloading(sentence: String) { calls.add("downloading:$sentence") }
        override fun done() { calls.add("done") }
        override fun failed(sentence: String) { calls.add("failed:$sentence") }
    }

    @Test
    fun `downloading forwards to notifications and listener`() {
        val notifications = RecordingNotice()
        val listener = RecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        forwarder.downloading("Downloading the speech model from GitHub (366 MB)...")
        assertEquals(listOf("downloading:Downloading the speech model from GitHub (366 MB)..."), notifications.calls)
        assertEquals(listOf("downloading:Downloading the speech model from GitHub (366 MB)..."), listener.calls)
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
        forwarder.downloading("Downloading the speech model from GitHub (366 MB)...")
        forwarder.setListener(null)
        forwarder.done()
        assertEquals(listOf("downloading:Downloading the speech model from GitHub (366 MB)...", "done"), notifications.calls)
        assertEquals(listOf("downloading:Downloading the speech model from GitHub (366 MB)..."), listener.calls)
    }

    @Test
    fun `the downloading sentence is passed on to the listener`() {
        val notifications = RecordingNotice()
        val listener = RecordingNotice()
        val forwarder = ForwardingDownloadNotice(notifications)
        forwarder.setListener(listener)
        forwarder.downloading("Downloading the speech model from GitHub (366 MB)...")
        assertEquals(listOf("downloading:Downloading the speech model from GitHub (366 MB)..."), listener.calls)
    }
}
