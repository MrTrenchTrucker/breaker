package dev.breaker.dictation.wiring

/**
 * Carries a level from the audio dispatch thread to the Home mic on the main thread.
 *
 * [publish] only queues the work on [main]. Whether the take is still recording is read inside the
 * queued block, on the main thread, so a level that arrives after the take has left RECORDING is
 * dropped. The feed reads no clock and starts no thread.
 */
internal class HomeLevelFeed(
    private val onLevel: (Float) -> Unit,
    private val main: MainPost,
    private val isRecording: () -> Boolean,
) : LevelSink {
    override fun publish(level: Float) {
        main.post { if (isRecording()) onLevel(level) }
    }
}
