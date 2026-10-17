package dev.breaker.dictation.wiring

/** Takes a level from 0 to 1 from the audio thread and passes it on for the tile. */
fun interface LevelSink {
    fun publish(level: Float)
}

/**
 * Carries a level from the audio dispatch thread to the tile on the main thread.
 *
 * [publish] only queues the work on [main]. Whether the take is still recording is read inside the
 * queued block, on the main thread, so a level that arrives after the take has left RECORDING is
 * dropped. The feed reads no clock and starts no thread.
 */
internal class LevelFeed(
    private val tile: TilePort,
    private val main: MainPost,
    private val isRecording: () -> Boolean,
) : LevelSink {
    override fun publish(level: Float) {
        main.post { if (isRecording()) tile.level(level) }
    }
}
