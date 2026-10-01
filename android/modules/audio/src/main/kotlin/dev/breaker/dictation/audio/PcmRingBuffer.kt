package dev.breaker.dictation.audio

/**
 * A bounded buffer between the capture thread and the consumer.
 *
 * The microphone produces audio on its own schedule and the consumer (the
 * transcription engine, the WAV writer) takes it when it can. This buffer is
 * where the two meet without either blocking: when the consumer falls behind,
 * the oldest audio is dropped and counted rather than the capture thread
 * stalling, because a stalled capture thread is an overrun the driver handles
 * by dropping audio anyway — invisibly, and without a number to report.
 *
 * Dropping is counted in [droppedSamples]. A take that lost audio says so
 * rather than returning a shorter recording that sounds complete.
 *
 * Thread-safe: one producer and one consumer is the expected shape, but any
 * number of threads may call in.
 */
internal class PcmRingBuffer(
    /** Capacity in samples. Must be at least one frame. */
    val capacitySamples: Int,
    /**
     * How long a blocked read waits before re-checking, so a stop cannot hang
     * forever. A production buffer polls at this rate; the tests pass a long
     * one so that only a real wake-up, never the poll, can end a wait.
     */
    internal val readWaitMs: Long = READ_WAIT_MS,
) {
    init {
        require(capacitySamples > 0) { "capacitySamples must be positive: $capacitySamples" }
    }

    private val lock = Object()
    private val buffer = FloatArray(capacitySamples)
    private var writeIndex = 0
    private var size = 0

    @Volatile
    private var droppedTotal: Long = 0L

    /** Samples waiting to be read. */
    val availableSamples: Int
        get() = synchronized(lock) { size }

    /** True when nothing is buffered. */
    fun isEmpty(): Boolean = availableSamples == 0

    /** True when the buffer is full, so the next write will drop audio. */
    fun isFull(): Boolean = synchronized(lock) { size == capacitySamples }

    /** Samples dropped since construction because a consumer fell behind. */
    val droppedSamples: Long
        get() = droppedTotal

    /**
     * Append [length] samples from [source] starting at [offset].
     *
     * Returns how many were stored. A write larger than the whole buffer keeps
     * the newest audio — the part nearest the moment the user is still talking
     * — and reports the rest as dropped.
     */
    fun write(source: FloatArray, offset: Int = 0, length: Int = source.size - offset): Int {
        require(offset >= 0) { "offset cannot be negative: $offset" }
        require(length >= 0) { "length cannot be negative: $length" }
        require(offset + length <= source.size) {
            "read of $length samples at $offset runs past the end of a ${source.size}-sample source"
        }
        if (length == 0) return 0

        var stored = 0
        var dropped = 0L
        synchronized(lock) {
            var remaining = length
            var readFrom = offset

            // A write bigger than the buffer can only keep its tail.
            if (remaining > capacitySamples) {
                val skipped = remaining - capacitySamples
                dropped += skipped.toLong()
                readFrom += skipped
                remaining -= skipped
            }

            // Make room by discarding the oldest samples, and discard enough of
            // them for the WHOLE write rather than one per pass. The slot freed
            // is the one writeIndex already points at: writeIndex stays put and
            // size shrinks, so the oldest audio is dropped and the newest is
            // stored, in that order. Advancing writeIndex here as well would
            // drop one sample and then overwrite a live one.
            while (remaining > 0 && size + remaining > capacitySamples) {
                size--
                dropped++
            }

            while (remaining > 0) {
                buffer[writeIndex] = source[readFrom]
                writeIndex = (writeIndex + 1) % capacitySamples
                size++
                readFrom++
                remaining--
                stored++
            }
            // Counted under the same lock that computed the loss: the counter is
            // read by anyone at any time, so two producers that both drop must
            // not be able to read-modify-write over each other and lose one.
            if (dropped > 0) droppedTotal += dropped
            // A consumer waiting for audio cannot know its wait has timed out,
            // so the write that finally gives it something is what wakes it.
            lock.notifyAll()
        }
        return stored
    }

    /**
     * Copy up to [length] samples into [destination] from [offset].
     *
     * Returns how many were copied. When [block] is true the call waits at
     * most [readWaitMs] for audio to arrive rather than returning empty, which
     * is what a consumer thread wants; a caller that must not wait passes
     * false.
     *
     * The wait is bounded and happens once, so a read on a buffer nobody is
     * writing to returns 0 instead of parking forever. A consumer is expected
     * to call again: the bound is what lets it notice it has been asked to
     * stop, not a guarantee that audio is waiting.
     */
    fun read(
        destination: FloatArray,
        offset: Int = 0,
        length: Int = destination.size - offset,
        block: Boolean = false,
    ): Int {
        require(offset >= 0) { "offset cannot be negative: $offset" }
        require(length >= 0) { "length cannot be negative: $length" }
        require(offset + length <= destination.size) {
            "write of $length samples at $offset runs past the end of a " +
                "${destination.size}-sample destination"
        }
        synchronized(lock) {
            if (size == 0) {
                if (!block) return 0
                try {
                    lock.wait(readWaitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }
            var copied = 0
            var writeTo = offset
            var readIndex = (writeIndex - size + capacitySamples) % capacitySamples
            while (copied < length && size > 0) {
                destination[writeTo] = buffer[readIndex]
                readIndex = (readIndex + 1) % capacitySamples
                size--
                writeTo++
                copied++
            }
            lock.notifyAll()
            return copied
        }
    }

    /** Throw away anything buffered and wake a blocked reader. */
    fun clear() {
        synchronized(lock) {
            size = 0
            writeIndex = 0
            lock.notifyAll()
        }
    }

    private companion object {
        /** How long a blocked read waits before re-checking, so a stop cannot hang forever. */
        const val READ_WAIT_MS = 50L
    }
}
