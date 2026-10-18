package dev.breaker.dictation.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The microphone preference: Bluetooth, then wired, then the phone's own.
 *
 * A failure means the policy would record from a worse microphone than one
 * that is present, or invent a device when none is.
 */
class MicRoutePolicyTest {

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items) else items.flatMap { head ->
            permutations(items - head).map { listOf(head) + it }
        }

    @Test
    fun `bluetooth beats wired and built-in in every order of the list`() {
        val all = listOf(bt(1), wired(2), builtIn(3))
        for (order in permutations(all)) {
            assertEquals("audio: list $order should pick the Bluetooth device", bt(1), MicRoutePolicy.pick(order))
        }
    }

    @Test
    fun `wired beats built-in in every order when there is no bluetooth`() {
        val all = listOf(wired(2), builtIn(3))
        for (order in permutations(all)) {
            assertEquals("audio: list $order should pick the wired device", wired(2), MicRoutePolicy.pick(order))
        }
    }

    @Test
    fun `two of a kind pick the first in list position not the lowest or last id`() {
        assertEquals(bt(5), MicRoutePolicy.pick(listOf(bt(5), bt(4))))
        assertEquals(bt(4), MicRoutePolicy.pick(listOf(bt(4), bt(5))))
        assertEquals(wired(8), MicRoutePolicy.pick(listOf(wired(8), wired(6))))
        assertEquals(wired(6), MicRoutePolicy.pick(listOf(wired(6), wired(8))))
        assertEquals(builtIn(9), MicRoutePolicy.pick(listOf(builtIn(9), builtIn(7))))
        assertEquals(builtIn(7), MicRoutePolicy.pick(listOf(builtIn(7), builtIn(9))))
    }

    @Test
    fun `the first of a kind wins even with other kinds between and after it`() {
        assertEquals(bt(7), MicRoutePolicy.pick(listOf(bt(7), wired(1), bt(2), builtIn(3))))
        assertEquals(wired(7), MicRoutePolicy.pick(listOf(builtIn(3), wired(7), wired(2))))
    }

    @Test
    fun `an empty list picks nothing`() {
        assertNull("audio: an empty list must pick no device", MicRoutePolicy.pick(emptyList()))
    }

    @Test
    fun `a list with one kind only picks that device`() {
        assertEquals(builtIn(3), MicRoutePolicy.pick(listOf(builtIn(3))))
        assertEquals(wired(2), MicRoutePolicy.pick(listOf(wired(2))))
        assertEquals(bt(1), MicRoutePolicy.pick(listOf(bt(1))))
    }
}
