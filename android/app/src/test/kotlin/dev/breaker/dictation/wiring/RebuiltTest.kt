package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

internal class RebuiltTest {

    /** Makes a new [Item] on each call and counts the calls. */
    private class Maker {
        var calls: Int = 0
            private set

        fun make(): Item {
            calls += 1
            return Item(calls)
        }
    }

    private class Item(val number: Int)

    @Test
    fun `again builds a new value every time and current follows it`() {
        val maker = Maker()
        val rebuilt = Rebuilt { maker.make() }
        val first: Item = rebuilt.current
        assertEquals(1, maker.calls)
        assertSame(first, rebuilt.current)
        val second: Item = rebuilt.again()
        assertNotSame(first, second)
        assertEquals(2, maker.calls)
        assertSame(second, rebuilt.current)
        val third: Item = rebuilt.again()
        assertEquals(3, maker.calls)
        assertSame(third, rebuilt.current)
    }

    @Test
    fun `the first value is built once and not again until asked`() {
        val maker = Maker()
        val rebuilt = Rebuilt { maker.make() }
        assertEquals(1, maker.calls)
        assertEquals(1, rebuilt.current.number)
        assertEquals(1, rebuilt.current.number)
        assertEquals(1, maker.calls)
        rebuilt.again()
        assertEquals(2, maker.calls)
    }
}
