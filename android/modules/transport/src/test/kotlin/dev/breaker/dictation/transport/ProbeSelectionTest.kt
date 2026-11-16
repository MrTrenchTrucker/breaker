package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order-and-fallback policy: LIST ORDER every time, the first entry that answers wins and
 * the rest are not asked, and the last good address is only the fallback.
 *
 * Every test records the addresses [ProbeSelection.select] actually asked about, so a policy
 * that reorders the list, tries the last good address first, or keeps asking after an answer
 * fails on the recorded order and not merely on the returned text.
 */
class ProbeSelectionTest {
    private val home = "https://home.example"
    private val road = "https://road.example"
    private val away = "https://away.example"

    /** A predicate that answers true for [reachable] and records every address it was asked about. */
    private fun recorder(vararg reachable: String): Pair<MutableList<String>, (String) -> Boolean> {
        val asked = mutableListOf<String>()
        val reachableSet = reachable.toSet()
        return asked to { address: String ->
            asked += address
            address in reachableSet
        }
    }

    @Test
    fun `the entries are asked in list order and the first that answers wins`() {
        val (asked, answers) = recorder(road)
        val selected = ProbeSelection.select(listOf(home, road, away), lastGood = "", answers = answers)
        assertEquals("the first answering entry must win", road, selected)
        assertEquals("the entries must be asked in list order", listOf(home, road), asked)
    }

    @Test
    fun `the entries after the first answer are not asked`() {
        val (asked, answers) = recorder(home)
        val selected = ProbeSelection.select(listOf(home, road, away), lastGood = "", answers = answers)
        assertEquals("the first entry answered, so it must win", home, selected)
        assertEquals("nothing after the first answer may be asked", listOf(home), asked)
    }

    @Test
    fun `the last good address is never tried ahead of the list`() {
        val (asked, answers) = recorder(road)
        val selected = ProbeSelection.select(listOf(home, road), lastGood = road, answers = answers)
        assertEquals("the road address answers, so it is used", road, selected)
        assertEquals("the list must be asked first, not the last good address", listOf(home, road), asked)
    }

    @Test
    fun `a phone back home returns home even when the road address still answers`() {
        val (asked, answers) = recorder(home, road)
        val selected = ProbeSelection.select(listOf(home, road), lastGood = road, answers = answers)
        assertEquals("home is entry 1 and answers, so home must win over the last good road", home, selected)
        assertEquals("home must be asked before road", listOf(home), asked)
    }

    @Test
    fun `when nothing answers the last good address is used`() {
        val (asked, answers) = recorder()
        val selected = ProbeSelection.select(listOf(home, road), lastGood = road, answers = answers)
        assertEquals("no entry answered, so the last good address is the fallback", road, selected)
        assertEquals("every entry must be asked before the fallback", listOf(home, road), asked)
    }

    @Test
    fun `a blank last good address is not used when nothing answers`() {
        val (asked, answers) = recorder()
        val selected = ProbeSelection.select(listOf(home, road), lastGood = "   ", answers = answers)
        assertEquals("a blank last good is no address, so entry 1 is the fallback", home, selected)
        assertEquals("every entry must still be asked", listOf(home, road), asked)
    }

    @Test
    fun `when nothing answers and there is no last good entry 1 is used`() {
        val (_, answers) = recorder()
        assertEquals(home, ProbeSelection.select(listOf(home, road), lastGood = "", answers = answers))
    }

    @Test
    fun `an empty list with a last good address returns it`() {
        val (_, answers) = recorder()
        assertEquals(road, ProbeSelection.select(emptyList(), lastGood = road, answers = answers))
    }

    @Test
    fun `an empty list with no last good returns the empty string`() {
        val (asked, answers) = recorder()
        assertEquals("", ProbeSelection.select(emptyList(), lastGood = "", answers = answers))
        assertEquals("an empty list asks nothing", emptyList<String>(), asked)
    }

    @Test
    fun `a blank entry is asked like any other and never wins`() {
        val (asked, answers) = recorder(home)
        val selected = ProbeSelection.select(listOf("", home), lastGood = "", answers = answers)
        assertEquals("a blank entry answers false, so the next entry wins", home, selected)
        assertEquals("the blank entry is still asked, in list order", listOf("", home), asked)
    }
}
