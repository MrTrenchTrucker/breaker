package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.model.PhraseKind
import dev.breaker.dictation.core.model.PhraseSample
import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource

/** What [PhraseRecorder.start] answered. */
enum class RecorderStart { STARTED, ALREADY_RECORDING, SOURCE_FAILED }

/** Why a recording was not kept. */
enum class RejectReason { TOO_SHORT, TOO_LONG, SILENT, NOT_RECORDING }

/** What [PhraseRecorder.finish] answered: a usable sample, or the reason there is none. */
sealed interface RecordResult {
    data class Accepted(val sample: PhraseSample) : RecordResult
    data class Rejected(val reason: RejectReason) : RecordResult
}

/**
 * Records one phrase sample at a time from an [AudioSource].
 *
 * Frames arrive on the capture thread of the source while [finish] runs on the caller's
 * thread, so everything they share is guarded by ONE lock. This class creates no thread
 * and no timer and never blocks on the source while holding the lock. Written and
 * unit-tested by reading only: not compiled, not run, not tried on a phone.
 */
class PhraseRecorder(private val source: AudioSource) {

    /** One recording. Every field is read and written only under [lock] until the take ends. */
    private class Take(val kind: PhraseKind) {
        val buffer = FloatArray(BUFFER_CAP)
        var length = 0
        var overflowed = false
    }

    private val lock = Any()

    /** The recording in progress, or null. A frame belongs to a take only while that take is this one. */
    private var active: Take? = null

    /**
     * Begin a recording of [kind]. The source is started OUTSIDE the lock: its own start may
     * call back into [append] on another thread, which needs the lock, so holding it here could
     * deadlock. The take is claimed first, so a second start meanwhile answers
     * [RecorderStart.ALREADY_RECORDING] without touching the source.
     */
    fun start(kind: PhraseKind): RecorderStart {
        val take = Take(kind)
        val claimed = synchronized(lock) {
            if (active == null) {
                active = take
                true
            } else {
                false
            }
        }
        if (!claimed) return RecorderStart.ALREADY_RECORDING
        try {
            source.start(AudioListener { samples -> append(take, samples) })
        } catch (failure: Exception) {
            // Only this take is released: a finish and a new start may have run meanwhile.
            synchronized(lock) {
                if (active === take) active = null
            }
            return RecorderStart.SOURCE_FAILED
        }
        return RecorderStart.STARTED
    }

    /**
     * End the recording and judge it. Clearing the active take under the lock comes first, so
     * a late frame from the capture thread is dropped and can never reach a later recording.
     * The source is stopped OUTSIDE the lock, for the same reason as in [start]: a stop that
     * waits for the capture thread to finish delivering would deadlock against a lock held here.
     * The take is no longer written once cleared, so reading it after the lock is safe.
     */
    fun finish(): RecordResult {
        val take = synchronized(lock) {
            val current = active
            active = null
            current
        }
        if (take == null) return RecordResult.Rejected(RejectReason.NOT_RECORDING)
        try {
            source.stop()
        } catch (failure: Exception) {
            // The take is already closed and judged below, so a stop that failed loses nothing.
        }
        return judge(take)
    }

    /** Samples held by the recording in progress; 0 when none. Internal, for the tests of the cap. */
    internal fun bufferedSamples(): Int = synchronized(lock) { active?.length ?: 0 }

    private fun append(take: Take, samples: FloatArray) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            if (active !== take) return
            val room = take.buffer.size - take.length
            val kept = if (samples.size < room) samples.size else room
            if (kept > 0) System.arraycopy(samples, 0, take.buffer, take.length, kept)
            take.length += kept
            if (kept < samples.size) take.overflowed = true
        }
    }

    /**
     * Length is checked before loudness because a silent clip is also often short, and the
     * length is the more useful hint to show the user.
     */
    private fun judge(take: Take): RecordResult {
        val length = take.length
        if (take.overflowed || length > RecorderBounds.MAX_SAMPLES) {
            return RecordResult.Rejected(RejectReason.TOO_LONG)
        }
        if (length < RecorderBounds.MIN_SAMPLES) return RecordResult.Rejected(RejectReason.TOO_SHORT)
        if (peak(take.buffer, length) < RecorderBounds.SILENT_PEAK) {
            return RecordResult.Rejected(RejectReason.SILENT)
        }
        return RecordResult.Accepted(PhraseSample(take.kind, take.buffer.copyOf(length)))
    }

    private fun peak(buffer: FloatArray, length: Int): Float {
        var highest = 0f
        for (i in 0 until length) {
            val magnitude = if (buffer[i] < 0f) -buffer[i] else buffer[i]
            if (magnitude > highest) highest = magnitude
        }
        return highest
    }

    private companion object {
        const val BUFFER_CAP: Int = RecorderBounds.MAX_SAMPLES + RecorderBounds.BUFFER_MARGIN_SAMPLES
    }
}
