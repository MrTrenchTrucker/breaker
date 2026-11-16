package dev.breaker.dictation

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.ui.screen.settings.ServerAddressStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's reading of the stored server addresses, [serverAddressStatusOf], over the stored list.
 *
 * The settings screen's provider is a one-line call to this function, so this is the behaviour every
 * drawing inherits: ONE snapshot of the probe's cache-only answer per entry; InUse only when some
 * entry measured true (the FIRST such entry in list order); NoneAnswered only when every entry
 * measured false; Checking while anything is unmeasured. Every test's probe double FAILS the test if
 * it is asked to dial — both dial paths throw — and records every cachedAnswer call, so a provider
 * that re-asks, re-dials, or reads the list twice fails on the record and not merely on the value.
 */
class EffectiveAddressStatusTest {
    private val home = "https://home.example"
    private val road = "https://road.example"
    private val away = "https://away.example"

    /** A probe double over [answers] that throws on either dial path and records every cache read. */
    private class FakeProbe(val answers: MutableMap<String, Boolean?>) : ServerAddressProbe {
        val asked: MutableList<String> = ArrayList()
        var dials: Int = 0

        override fun cachedAnswer(address: String): Boolean? {
            asked += address
            return answers[address]
        }

        override fun isServerReachable(): Boolean {
            dials += 1
            throw AssertionError("the status provider must never dial (isServerReachable)")
        }

        override fun refresh(): Boolean {
            dials += 1
            throw AssertionError("the status provider must never dial (refresh)")
        }
    }

    /** Settings whose stored list is [addresses], entry 1 first. */
    private fun settings(vararg addresses: String): AppSettings =
        AppSettings(serverUrl = addresses.first(), serverAddresses = addresses.toList())

    @Test
    fun `an answering entry is in use`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to true))
        assertEquals(ServerAddressStatus.InUse(home), serverAddressStatusOf(settings(home), probe))
        assertEquals("the single entry must be asked once", listOf(home), probe.asked)
        assertTrue("no dial path may run", probe.dials == 0)
    }

    @Test
    fun `the first answering entry in list order is the one in use`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to false, road to true, away to true))
        assertEquals(
            "entry 2 answers first in list order, so it wins",
            ServerAddressStatus.InUse(road),
            serverAddressStatusOf(settings(home, road, away), probe),
        )
        assertEquals("one snapshot asks every entry once", listOf(home, road, away), probe.asked)
    }

    @Test
    fun `every entry measured false is none answered`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to false, road to false))
        assertEquals(ServerAddressStatus.NoneAnswered, serverAddressStatusOf(settings(home, road), probe))
    }

    @Test
    fun `an unmeasured entry among refusals is still checking`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to false, road to null))
        assertEquals(ServerAddressStatus.Checking, serverAddressStatusOf(settings(home, road), probe))
    }

    @Test
    fun `nothing measured is checking`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to null, road to null))
        assertEquals(ServerAddressStatus.Checking, serverAddressStatusOf(settings(home, road), probe))
    }

    @Test
    fun `no stored address is checking`() {
        val probe = FakeProbe(mutableMapOf())
        assertEquals(ServerAddressStatus.Checking, serverAddressStatusOf(AppSettings(), probe))
        assertTrue("nothing is asked when there is no stored address", probe.asked.isEmpty())
    }

    @Test
    fun `a legacy single address follows the same states`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to true))
        assertEquals(
            "a store with only serverUrl is the one-entry list",
            ServerAddressStatus.InUse(home),
            serverAddressStatusOf(AppSettings(serverUrl = home), probe),
        )
    }

    @Test
    fun `one snapshot asks each entry exactly once`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to false, road to false, away to false))
        serverAddressStatusOf(settings(home, road, away), probe)
        assertEquals("each entry is asked once, not once per state check", 3, probe.asked.size)
        assertEquals(listOf(home, road, away), probe.asked)
    }

    @Test
    fun `the next call follows the changed list`() {
        val probe = FakeProbe(mutableMapOf<String, Boolean?>(home to false))
        assertEquals(ServerAddressStatus.NoneAnswered, serverAddressStatusOf(settings(home), probe))
        probe.answers[road] = true
        assertEquals(
            "a new reading must use the new list and the new answers",
            ServerAddressStatus.InUse(road),
            serverAddressStatusOf(settings(home, road), probe),
        )
        assertTrue("no dial path may run", probe.dials == 0)
    }
}
