package dev.breaker.dictation.core.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The phrase detector reports a [PhraseEvent], and only a send phrase can say where
 * it began. These pin the shape of that report: which phrases exist, how an event
 * maps onto a [PhraseKind], and that a wake event has nowhere to put an offset.
 */
class PhraseEventTest {
    private fun instanceFields(type: Class<*>): List<String> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }

    private fun allFields(type: Class<*>): List<String> = type.declaredFields.map { it.name }

    @Test
    fun `there are exactly two phrase kinds and no cancel phrase`() {
        assertEquals(listOf("WAKE", "SEND"), PhraseKind.entries.map { it.name })
    }

    @Test
    fun `each event maps onto the kind it reports`() {
        assertEquals(PhraseKind.WAKE, PhraseEvent.Wake.kind)
        assertEquals(PhraseKind.SEND, PhraseEvent.Send(null).kind)
        assertEquals(PhraseKind.SEND, PhraseEvent.Send(1_200L).kind)
    }

    @Test
    fun `every phrase kind is reported by some event and no event reports a kind twice`() {
        val everyEvent = listOf(PhraseEvent.Wake, PhraseEvent.Send(null))

        assertEquals(PhraseKind.entries.toList(), everyEvent.map { it.kind })
    }

    @Test
    fun `a wake event has no field to carry an offset in`() {
        // A property of a Kotlin object lives in a static field, so every field counts here, and the
        // only one a bare object has is the one that holds the object itself.
        assertEquals("a wake event carries nothing", listOf("INSTANCE"), allFields(PhraseEvent.Wake.javaClass))
    }

    @Test
    fun `a send event carries the offset it was given, or none`() {
        assertEquals(listOf("trimBeforeMs"), instanceFields(PhraseEvent.Send::class.java))
        assertEquals(1_200L, PhraseEvent.Send(1_200L).trimBeforeMs)
        assertNull(PhraseEvent.Send(null).trimBeforeMs)
        assertEquals(PhraseEvent.Send(7L), PhraseEvent.Send(7L))
    }
}
