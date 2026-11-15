package dev.breaker.dictation.format

import org.junit.Assert.assertFalse
import org.junit.Test

// A surrogate half with no partner is no text, and the whole half range is refused: the first
// code unit of the range, the last one, and the edge between the high and the low halves.
class N9GuardSurrogateRangeTest {

    @Test
    fun `a lone surrogate half at either end of the range is refused`() {
        assertFalse("android_format: the first high half", N9Guard.accepts("hello world", "hello\uD800world"))
        assertFalse("android_format: the last high half", N9Guard.accepts("hello world", "hello\uDBFFworld"))
        assertFalse("android_format: the first low half", N9Guard.accepts("hello world", "hello\uDC00world"))
        assertFalse("android_format: the last low half", N9Guard.accepts("hello world", "hello\uDFFFworld"))
        assertFalse("android_format: the last low half at the end", N9Guard.accepts("hello world", "hello world\uDFFF"))
    }
}
