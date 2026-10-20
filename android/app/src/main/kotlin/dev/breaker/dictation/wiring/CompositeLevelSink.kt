package dev.breaker.dictation.wiring

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [LevelSink] that fans out every level to the sinks that were added.
 *
 * The one source (the dictation component) feeds both the tile and the Home mic:
 * each sink posts to the main thread on its own.
 *
 * Safe to add and remove while publish runs on another thread, because the fan-out is a CopyOnWriteArrayList.
 */
internal class CompositeLevelSink : LevelSink {
    private val sinks: MutableList<LevelSink> = CopyOnWriteArrayList()

    /** The number of fans-out currently registered. */
    internal val size: Int get() = sinks.size

    /** Adds [sink] to the fan-out. */
    fun add(sink: LevelSink) { sinks.add(sink) }

    /** Removes [sink] from the fan-out. */
    fun remove(sink: LevelSink) { sinks.remove(sink) }

    override fun publish(level: Float) {
        // Iterates the copy-on-write list directly: the iterator is a snapshot, so a concurrent add or remove cannot throw.
        for (sink in sinks) sink.publish(level)
    }
}
