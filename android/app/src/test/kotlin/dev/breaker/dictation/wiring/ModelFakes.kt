package dev.breaker.dictation.wiring

import dev.breaker.shared.models.ModelEntry
import dev.breaker.shared.models.ModelFamily
import java.io.IOException

/** A main-thread post that keeps the blocks until the test runs them, in order; nothing runs by itself. */
internal class ModelQueueMain : MainPost {
    private val pending: MutableList<() -> Unit> = ArrayList()

    /** When set, every post is refused with an exception. */
    var refuse: Boolean = false

    val queued: Int
        get() = pending.size

    override fun post(block: () -> Unit) {
        if (refuse) throw IllegalStateException("post refused")
        pending.add(block)
    }

    /** Runs every queued block, including blocks queued while running; answers how many ran. */
    fun runAll(): Int {
        var ran = 0
        while (pending.isNotEmpty()) {
            pending.removeAt(0)()
            ran += 1
        }
        return ran
    }
}

/** A background worker that keeps the blocks until the test runs them, in submission order. */
internal class ModelQueueBackground : Background {
    private val pending: MutableList<() -> Unit> = ArrayList()

    /** When set, every submit is refused with an exception. */
    var refuse: Boolean = false

    var submitted: Int = 0
        private set

    val queued: Int
        get() = pending.size

    override fun submit(block: () -> Unit) {
        if (refuse) throw IllegalStateException("submit refused")
        submitted += 1
        pending.add(block)
    }

    /** Runs every queued block, including blocks queued while running; answers how many ran. */
    fun runAll(): Int {
        var ran = 0
        while (pending.isNotEmpty()) {
            pending.removeAt(0)()
            ran += 1
        }
        return ran
    }
}

/** An install port that answers the queued outcomes in order (the last one repeats) and keeps the entries it was given. */
internal class ModelFakeInstaller(vararg outcomes: InstallOutcome) : ModelInstallPort {
    private val answers: List<InstallOutcome> = if (outcomes.isEmpty()) listOf(InstallOutcome.Installed) else outcomes.toList()
    val entries: MutableList<ModelEntry> = ArrayList()

    /** When set, install throws this instead of answering. */
    var failure: RuntimeException? = null

    override fun install(entry: ModelEntry): InstallOutcome {
        val number = entries.size
        entries.add(entry)
        failure?.let { throw it }
        return answers[minOf(number, answers.size - 1)]
    }
}

/** A download notice that writes each call into [log] and can throw from each call. */
internal class ModelRecordingNotice(val log: MutableList<String> = ArrayList()) : ModelDownloadNotice {
    var downloadingThrows: Boolean = false
    var doneThrows: Boolean = false
    var failedThrows: Boolean = false

    override fun downloading() {
        log.add("downloading")
        if (downloadingThrows) throw IllegalStateException("notice broke")
    }

    override fun done() {
        log.add("done")
        if (doneThrows) throw IllegalStateException("notice broke")
    }

    override fun failed(sentence: String) {
        log.add("failed:$sentence")
        if (failedThrows) throw IllegalStateException("notice broke")
    }
}

/** An off store in memory that keeps a log of its writes and can fail on a read or a write. */
internal class MemoryOffStore(@set:JvmName("assignOff") var off: Boolean = false) : OffStore {
    val writes: MutableList<Boolean> = ArrayList()
    var readThrows: Boolean = false
    var writeThrows: Boolean = false

    override fun isOff(): Boolean {
        if (readThrows) throw IllegalStateException("store unreadable")
        return off
    }

    override fun setOff(off: Boolean) {
        writes.add(off)
        if (writeThrows) throw IOException("store not writable")
        this.off = off
    }
}

/** A registry-shaped entry for a test; the address and checksum are placeholders no code may fetch. */
internal fun modelEntryFor(id: String): ModelEntry =
    ModelEntry(
        id = id,
        family = ModelFamily.SHERPA_ONNX,
        url = "https://example.invalid/$id",
        sha256 = "0".repeat(64),
        sizeMb = 1,
        licence = "test",
        hosted = false,
    )
