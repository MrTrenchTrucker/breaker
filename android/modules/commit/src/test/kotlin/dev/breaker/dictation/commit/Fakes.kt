package dev.breaker.dictation.commit

import dev.breaker.dictation.core.model.CommitRequest

/** Counts how deep inside a main-thread hop we are, and records every seam call made outside one. */
internal class HopProbe {
    var depth: Int = 0
    val outsideCalls: MutableList<String> = ArrayList()

    fun seamCalled(name: String) {
        if (depth == 0) outsideCalls.add(name)
    }
}

/** Runs the block at once on the calling thread. */
internal class InlineMainThread(private val probe: HopProbe) : MainThread {
    var calls: Int = 0

    override fun <T> call(block: () -> T): T {
        calls++
        probe.depth++
        try {
            return block()
        } finally {
            probe.depth--
        }
    }
}

/** Never runs the block inside the call: it keeps it, and gives up. */
internal class LateMainThread(private val probe: HopProbe) : MainThread {
    val pending: MutableList<() -> Any?> = ArrayList()

    override fun <T> call(block: () -> T): T {
        pending.add(block)
        throw MainThreadUnavailable("test: the hop was late")
    }

    /** The main thread finally got to every kept block. Results are ignored. */
    fun runLate() {
        val blocks: List<() -> Any?> = ArrayList(pending)
        for (block in blocks) {
            probe.depth++
            try {
                block()
            } finally {
                probe.depth--
            }
        }
    }
}

/** Throws [failure] from the call; the block never runs. */
internal class FailingMainThread(private val failure: Exception) : MainThread {
    override fun <T> call(block: () -> T): T {
        throw failure
    }
}

internal class FakeField(private val probe: HopProbe) : FocusedField {
    var answer: FieldCommit = FieldCommit.ACCEPTED
    var failure: Exception? = null
    val received: MutableList<String> = ArrayList()

    override fun commitText(text: String): FieldCommit {
        probe.seamCalled("field.commitText")
        received.add(text)
        val toThrow: Exception? = failure
        if (toThrow != null) throw toThrow
        return answer
    }
}

internal class FakeFocus(private val probe: HopProbe, var field: FocusedField?) : FocusedFieldSource {
    var reads: Int = 0

    override fun current(): FocusedField? {
        probe.seamCalled("focus.current")
        reads++
        return field
    }
}

internal class FakeClipboard(private val probe: HopProbe) : ClipboardWriter {
    var result: Boolean = true
    var failure: Exception? = null

    /** Each write as (text, sensitive). */
    val writes: MutableList<Pair<String, Boolean>> = ArrayList()

    override fun copy(text: String, sensitive: Boolean): Boolean {
        probe.seamCalled("clipboard.copy")
        writes.add(Pair(text, sensitive))
        val toThrow: Exception? = failure
        if (toThrow != null) throw toThrow
        return result
    }
}

internal class FakeNotice(private val probe: HopProbe) : UserNotice {
    var failure: Exception? = null
    var shown: Int = 0

    override fun showCopied() {
        probe.seamCalled("notice.showCopied")
        shown++
        val toThrow: Exception? = failure
        if (toThrow != null) throw toThrow
    }
}

/** One service with its own fakes. Every test builds its own. */
internal class Rig(sdkInt: Int = 32, withField: Boolean = true) {
    val probe: HopProbe = HopProbe()
    val field: FakeField = FakeField(probe)
    val focus: FakeFocus = FakeFocus(probe, if (withField) field else null)
    val clipboard: FakeClipboard = FakeClipboard(probe)
    val notice: FakeNotice = FakeNotice(probe)
    val hop: InlineMainThread = InlineMainThread(probe)
    val service: CommitService = CommitService(focus, clipboard, notice, hop, sdkInt)

    fun serviceWith(mainThread: MainThread, sdk: Int = 32): CommitService =
        CommitService(focus, clipboard, notice, mainThread, sdk)
}

internal object Texts {
    /** Distinctive; must never appear in any detail, string form or exception. */
    const val SECRET: String = "my pin is 4417 and the vault code is kite-ram-ox"

    /** Put into thrown exception messages; must never appear anywhere. */
    const val LEAK_MARKER: String = "LEAK-MARKER-9137"

    fun request(text: String = SECRET): CommitRequest = CommitRequest(text)
}
