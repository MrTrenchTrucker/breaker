package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Test

/** A download notice that writes each call into a shared [log] under its own [name]. */
private class FwdProgressRecorder(private val name: String, private val log: MutableList<String>) : ModelDownloadNotice {
    override fun downloading(sentence: String) {
        log.add("$name:downloading")
    }

    override fun progress(sentence: String, doneMb: Int, totalMb: Int) {
        log.add("$name:progress:$sentence:$doneMb:$totalMb")
    }

    override fun done() {
        log.add("$name:done")
    }

    override fun failed(sentence: String) {
        log.add("$name:failed")
    }
}

internal class ForwardingProgressTest {

    private val sentence = "Downloading the speech model: 120 of 366 MB."

    @Test
    fun `progress reaches the notification first and the listener second with the same arguments`() {
        val log: MutableList<String> = ArrayList()
        val forwarder = ForwardingDownloadNotice(FwdProgressRecorder("notification", log))
        forwarder.setListener(FwdProgressRecorder("listener", log))
        forwarder.progress(sentence, 120, 366)
        assertEquals(
            "app: the notification, then the listener, with the same three arguments",
            listOf("notification:progress:$sentence:120:366", "listener:progress:$sentence:120:366"),
            log,
        )
    }

    @Test
    fun `with no listener the notification still gets progress`() {
        val log: MutableList<String> = ArrayList()
        val forwarder = ForwardingDownloadNotice(FwdProgressRecorder("notification", log))
        forwarder.progress(sentence, 120, 366)
        assertEquals(
            "app: the notification alone gets the progress",
            listOf("notification:progress:$sentence:120:366"),
            log,
        )
    }

    @Test
    fun `after the listener is cleared the old listener gets no progress`() {
        val log: MutableList<String> = ArrayList()
        val forwarder = ForwardingDownloadNotice(FwdProgressRecorder("notification", log))
        forwarder.setListener(FwdProgressRecorder("listener", log))
        forwarder.setListener(null)
        forwarder.progress(sentence, 120, 366)
        assertEquals(
            "app: only the notification gets progress after setListener(null)",
            listOf("notification:progress:$sentence:120:366"),
            log,
        )
    }
}
