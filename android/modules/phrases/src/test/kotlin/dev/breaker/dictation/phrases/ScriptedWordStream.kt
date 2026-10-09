package dev.breaker.dictation.phrases

import dev.breaker.dictation.core.model.HeardWord
import dev.breaker.dictation.core.model.WordUpdate
import dev.breaker.dictation.core.port.WordStream

/**
 * A synchronous word stream for tests. [feed] delivers on the caller's own thread, and
 * only while the stream is running, and only to the callback of the current run (the
 * first callback given since the last start). No thread, clock or wait is involved.
 *
 * [startCount] counts real starts (a start while running does not count). [stopCount]
 * counts every call to [stop]. [callbacks] keeps every callback a real start was given,
 * in order, so a test can feed a callback from an old run through [feedThrough].
 */
internal class ScriptedWordStream : WordStream {
    private var callback: ((WordUpdate) -> Unit)? = null

    /** Every callback a real start was given, in order. */
    val callbacks = mutableListOf<(WordUpdate) -> Unit>()

    var startCount: Int = 0
        private set

    var stopCount: Int = 0
        private set

    override var isRunning: Boolean = false
        private set

    override fun start(onUpdate: (WordUpdate) -> Unit) {
        if (isRunning) return
        startCount++
        callbacks += onUpdate
        callback = onUpdate
        isRunning = true
    }

    override fun stop() {
        stopCount++
        isRunning = false
        callback = null
    }

    /** Delivers [update] to the current callback, if the stream is running; otherwise nothing happens. */
    fun feed(update: WordUpdate) {
        val listener = callback ?: return
        listener(update)
    }

    /** Delivers [update] to the callback given at real start number [index], running or not. */
    fun feedThrough(index: Int, update: WordUpdate) {
        callbacks[index](update)
    }
}

/**
 * A whole recogniser update from (word, startMs) pairs, each pair one heard word.
 * Usable by every test in this module.
 */
internal fun update(final: Boolean, vararg word: Pair<String, Long>): WordUpdate =
    WordUpdate(word.map { HeardWord(it.first, it.second) }, final)
