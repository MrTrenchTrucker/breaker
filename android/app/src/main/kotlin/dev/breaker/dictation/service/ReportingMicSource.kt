package dev.breaker.dictation.service

import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.audio.MicSourceException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [MicSource] that tells its owner when the capture ended by itself.
 *
 * Every member passes straight to [inner]. [onEnded] is called at most once per take, from [close]
 * (after the inner close, even when that throws), when [open] or [read] throws a
 * [MicSourceException] (reported, then rethrown), or when [read] returns a negative value (the
 * device failed; reported, then the value is returned unchanged).
 *
 * The capture loop ends a take by itself on such a read and never closes the source, so a failed
 * device would stay open, with the recording indicator lit, until the next user action. For that
 * reason a failed [read] closes [inner] first (a close that throws an Exception is swallowed) and
 * only then reports. A failed [open] opened nothing, so it closes nothing. Once a take has been
 * reported its device is not closed again by a later failed read. A zero result is not reported:
 * it is transient, and when a long run of them ends the take the decorator does not see it; the
 * owner's stop is owed then anyway.
 *
 * When the app itself asked for the end, [markStopRequested] is called first and nothing is
 * reported or closed here: the owner's stop closes the source. [rearm] starts the next take.
 * [onEnded] runs on the capture thread and must not throw.
 */
class ReportingMicSource(
    private val inner: MicSource,
    private val onEnded: () -> Unit,
) : MicSource {
    private val stopRequested = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    override val sampleRateHz: Int
        get() = inner.sampleRateHz

    override val channelCount: Int
        get() = inner.channelCount

    override fun open() {
        try {
            inner.open()
        } catch (e: MicSourceException) {
            report()
            throw e
        }
    }

    override fun read(buffer: ShortArray, offset: Int, lengthInShorts: Int): Int {
        val count = try {
            inner.read(buffer, offset, lengthInShorts)
        } catch (e: MicSourceException) {
            deviceFailed()
            throw e
        }
        if (count < 0) deviceFailed()
        return count
    }

    override fun close() {
        try {
            inner.close()
        } finally {
            report()
        }
    }

    /** The app asked for the end: nothing is reported until [rearm]. */
    fun markStopRequested() {
        stopRequested.set(true)
    }

    /** Clears the stop request and the report, so the next take reports again. */
    fun rearm() {
        stopRequested.set(false)
        reported.set(false)
    }

    /** A read failed on the capture thread: release the device, then tell the owner. */
    private fun deviceFailed() {
        if (stopRequested.get() || reported.get()) return
        try {
            inner.close()
        } catch (e: Exception) {
            // The device is being given up on; the failure is reported below either way.
        }
        report()
    }

    private fun report() {
        if (stopRequested.get()) return
        if (reported.compareAndSet(false, true)) onEnded()
    }
}
