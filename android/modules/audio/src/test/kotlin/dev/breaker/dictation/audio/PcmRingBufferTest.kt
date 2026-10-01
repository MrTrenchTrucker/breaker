package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer between the capture thread and the consumer: what it stores.
 *
 * The properties that matter are the ones a queue gets wrong quietly: order,
 * the wrap, and an honest drop count. A buffer that loses audio without saying
 * so produces a recording that sounds complete and is not. Waiting, waking and
 * concurrency are held in their own files.
 */
class PcmRingBufferTest {

    @Test
    fun `audio comes out in the order it went in`() {
        val buffer = PcmRingBuffer(capacitySamples = 100)
        buffer.write(FloatArray(10) { it.toFloat() })              // 0..9
        buffer.write(FloatArray(10) { (it + 100).toFloat() })      // 100..109

        val out = FloatArray(20)
        val read = buffer.read(out)

        assertEquals(20, read)
        // FIFO across the two writes: the first chunk leaves before the
        // second, and no sample is invented or dropped on the way out.
        assertEquals(
            (0 until 10).map { it.toFloat() } + (100 until 110).map { it.toFloat() },
            out.toList(),
        )
        assertEquals(0L, buffer.droppedSamples)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `reading more than is buffered returns only what is there`() {
        val buffer = PcmRingBuffer(capacitySamples = 100)
        buffer.write(FloatArray(5) { it.toFloat() })

        val out = FloatArray(50)
        assertEquals("a read asked for 50 samples from a buffer holding 5", 5, buffer.read(out))
        assertEquals(0, buffer.read(out))
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `a read into part of a larger array starts where it was told`() {
        val buffer = PcmRingBuffer(capacitySamples = 100)
        buffer.write(FloatArray(4) { 7f })

        val out = FloatArray(10) { -1f }
        val read = buffer.read(out, offset = 3, length = 4)

        assertEquals(4, read)
        assertEquals(listOf(-1f, -1f, -1f, 7f, 7f, 7f, 7f, -1f, -1f, -1f), out.toList())
    }

    @Test
    fun `a write from part of a larger array starts where it was told`() {
        val buffer = PcmRingBuffer(capacitySamples = 100)
        buffer.write(FloatArray(4) { 7f })
        buffer.write(FloatArray(6) { it.toFloat() }, offset = 2, length = 3)

        val out = FloatArray(7)
        assertEquals(7, buffer.read(out))
        // The offset write adds source[2..4] - the samples before it are left
        // where they were, and the prefix already buffered survives in order.
        assertEquals(listOf(7f, 7f, 7f, 7f, 2f, 3f, 4f), out.toList())
    }

    @Test
    fun `the buffer wraps without losing or reordering audio`() {
        // The wrap is where a ring buffer is usually wrong, so this one writes
        // and reads far past the end of its storage and checks that every
        // sample comes back out in the order it went in.
        //
        // 1,000 samples are written against a capacity of 64, so both the
        // write index and the read index cross the seam 1,000 / 64 = 15 whole
        // times, and the split between the two reads per step rotates so the
        // seam is crossed from a different phase each time rather than always
        // at the same one.
        //
        // The consumer keeps up: each step drains everything that was just
        // written, so a zero drop count below is a real assertion rather than
        // luck. The buffered amount is watched as it goes and the run asserts
        // it stayed clear of capacity, which is what earns the right to
        // expect no drops - a consumer that fell behind here would be a
        // different test, and one that cannot expect a zero drop count.
        //
        // The last few steps deliberately stop draining, so the trailing
        // drain has a real backlog of wrapped audio to work through.
        val capacitySamples = 64
        val stepsThatKeepDraining = 94
        val buffer = PcmRingBuffer(capacitySamples = capacitySamples)
        val written = ArrayList<Float>()
        val delivered = ArrayList<Float>()
        val scratch = FloatArray(32)
        var peakOccupancy = 0

        repeat(100) { step ->
            val chunk = FloatArray(10) { (step * 10 + it).toFloat() }
            written.addAll(chunk.toList())
            buffer.write(chunk)

            val buffered = buffer.availableSamples
            if (buffered > peakOccupancy) peakOccupancy = buffered

            if (step < stepsThatKeepDraining) {
                // Two reads, split at a point that rotates with the step, so
                // the samples either side of the seam are not always handed
                // back by the same read.
                val firstRead = (step * 3) % (buffered + 1)
                if (firstRead > 0) {
                    repeat(buffer.read(scratch, length = firstRead)) { delivered.add(scratch[it]) }
                }
                repeat(buffer.read(scratch, length = buffered - firstRead)) {
                    delivered.add(scratch[it])
                }
            }
        }
        // Drain whatever is left, including audio that wrapped the seam.
        while (!buffer.isEmpty()) {
            repeat(buffer.read(scratch, length = 8)) { delivered.add(scratch[it]) }
        }

        assertEquals(1_000, written.size)
        assertTrue(
            "the buffer filled up, so the zero drop count below would be meaningless: " +
                "it held $peakOccupancy of $capacitySamples samples",
            peakOccupancy < capacitySamples,
        )
        assertEquals(
            "every sample must come back out, in the order it went in, across the wrap",
            written,
            delivered,
        )
        assertEquals("the buffer lost audio while wrapping", 0L, buffer.droppedSamples)
        assertTrue("draining left audio behind", buffer.isEmpty())
    }

    @Test
    fun `a full buffer drops the oldest audio and counts it`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        buffer.write(FloatArray(10) { it.toFloat() })      // 0..9
        assertTrue(buffer.isFull())

        val stored = buffer.write(floatArrayOf(99f))
        assertEquals("only one sample fitted, so one was stored", 1, stored)

        val out = FloatArray(10)
        val read = buffer.read(out)
        assertEquals(10, read)
        // 10 in, 1 more, capacity 10: exactly the oldest sample goes, and the
        // nine survivors keep their order behind the new one.
        assertEquals(
            "the newest sample should be in the buffer and the oldest one gone",
            listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 99f),
            out.toList(),
        )
        assertEquals(1L, buffer.droppedSamples)
    }

    @Test
    fun `a write larger than the whole buffer keeps the newest audio`() {
        // The part nearest the moment the user is still talking is the part
        // worth keeping.
        val buffer = PcmRingBuffer(capacitySamples = 10)
        buffer.write(FloatArray(25) { it.toFloat() })

        val out = FloatArray(10)
        buffer.read(out)
        assertEquals((15..24).map { it.toFloat() }, out.toList())
        assertEquals(15L, buffer.droppedSamples)
    }

    @Test
    fun `a full buffer refuses to grow`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        repeat(100) { buffer.write(FloatArray(10) { 1f }) }
        assertEquals(10, buffer.availableSamples)
        assertTrue(buffer.isFull())
    }

    @Test
    fun `clearing throws the audio away and wakes a blocked reader`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        buffer.write(FloatArray(10) { 1f })
        buffer.clear()
        assertEquals(0, buffer.availableSamples)
        assertEquals(0, buffer.read(FloatArray(10)))
    }

    @Test
    fun `a zero-length write or read is a no-op`() {
        val buffer = PcmRingBuffer(capacitySamples = 10)
        assertEquals(0, buffer.write(FloatArray(0)))
        assertEquals(0, buffer.read(FloatArray(0)))
        assertEquals(0, buffer.droppedSamples)
    }

}
