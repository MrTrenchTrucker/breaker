package dev.breaker.dictation

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app's one effective-address decision, [effectiveServerUrl], over the stored list.
 *
 * The composition root's `serverUrl` provider is a one-line call to this function, so this is the
 * behaviour every reader inherits: the entries are asked IN LIST ORDER, the first that answers wins,
 * the last good address is only the fallback when nothing answers, and entry 1 is the fallback when
 * there is no last good. Every test records the addresses the predicate was asked about, so a policy
 * that returns entry 1 at once, reorders the list, or tries the last good address first fails on the
 * recorded order and not merely on the returned text.
 */
class EffectiveServerUrlTest {
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

    /** Settings whose entry 1 is [list]'s head, with [lastGood] as the stored last good address. */
    private fun settings(list: List<String>, lastGood: String = ""): AppSettings =
        AppSettings(serverUrl = list.first(), serverAddresses = list, serverLastGood = lastGood)

    @Test
    fun `entry 1 answers, so entry 1 is the effective address`() {
        val (asked, answers) = recorder(home)
        assertEquals(home, effectiveServerUrl(settings(listOf(home, road)), answers))
        assertEquals("entry 1 must be the first address asked", listOf(home), asked)
    }

    @Test
    fun `the first answering entry wins and the entries after it are not asked`() {
        val (asked, answers) = recorder(road)
        assertEquals("the answering entry must win", road, effectiveServerUrl(settings(listOf(home, road, away)), answers))
        assertEquals("the entries must be asked in list order", listOf(home, road), asked)
    }

    @Test
    fun `a phone back home returns home even when the road address still answers`() {
        val (asked, answers) = recorder(home, road)
        assertEquals(
            "home is entry 1 and answers, so home must win over the last good road",
            home,
            effectiveServerUrl(settings(listOf(home, road), lastGood = road), answers),
        )
        assertEquals("home must be asked before road and the rest are not asked", listOf(home), asked)
    }

    @Test
    fun `when nothing answers the last good address is the fallback`() {
        val (asked, answers) = recorder()
        assertEquals(road, effectiveServerUrl(settings(listOf(home, road), lastGood = road), answers))
        assertEquals("every entry must be asked before the fallback", listOf(home, road), asked)
    }

    @Test
    fun `when nothing answers and there is no last good entry 1 is the fallback`() {
        val (asked, answers) = recorder()
        assertEquals(home, effectiveServerUrl(settings(listOf(home, road), lastGood = ""), answers))
        assertEquals("every entry must still be asked", listOf(home, road), asked)
    }

    @Test
    fun `the last good address is never asked ahead of the list`() {
        val (asked, answers) = recorder(road)
        effectiveServerUrl(settings(listOf(home, road), lastGood = road), answers)
        assertEquals("the list head must be asked first, never the last good address", home, asked.first())
    }

    @Test
    fun `a blank install has no effective address`() {
        val (_, answers) = recorder()
        assertEquals("", effectiveServerUrl(AppSettings(), answers))
    }

    @Test
    fun `a legacy single address with an empty list is still the effective address`() {
        val (asked, answers) = recorder(home)
        assertEquals(home, effectiveServerUrl(AppSettings(serverUrl = home), answers))
        assertEquals("the legacy address must be asked as entry 1", listOf(home), asked)
    }

    /**
     * The decision the composition root makes: `probe.cachedAnswer(address) == true`.
     *
     * [cachedAnswer][dev.breaker.dictation.transport.TcpConnectivityProbe.cachedAnswer] answers
     * `Boolean?` — null when nothing was measured or the measurement expired — while
     * [effectiveServerUrl] takes `(String) -> Boolean`. The root wraps it with `== true`, so this
     * adapter is that exact expression over a nullable answer, and these tests pin what null,
     * false and true each mean to the policy.
     */
    private fun cached(vararg answers: Pair<String, Boolean?>): Pair<MutableList<String>, (String) -> Boolean> {
        val map = answers.toMap()
        val asked = mutableListOf<String>()
        return asked to { address: String ->
            asked += address
            map[address] == true
        }
    }

    @Test
    fun `an unmeasured entry is not answering, so the policy falls through to the next entry`() {
        val (asked, answers) = cached(home to null, road to true)
        assertEquals(
            "a null answer is not an answer, so entry 2 must win",
            road,
            effectiveServerUrl(settings(listOf(home, road)), answers),
        )
        assertEquals("both entries must still be asked in list order", listOf(home, road), asked)
    }

    @Test
    fun `an unmeasured entry is not answering, so with nothing measured the last good is the fallback`() {
        val (_, answers) = cached(home to null, road to null)
        assertEquals(
            "null for every entry must behave exactly like a measured refusal",
            road,
            effectiveServerUrl(settings(listOf(home, road), lastGood = road), answers),
        )
    }

    @Test
    fun `a measured refusal is not answering`() {
        val (asked, answers) = cached(home to false, road to true)
        assertEquals("a measured false must not win", road, effectiveServerUrl(settings(listOf(home, road)), answers))
        assertEquals("the refusal must still be asked before entry 2", listOf(home, road), asked)
    }

    @Test
    fun `a measured answer wins`() {
        val (asked, answers) = cached(home to null, road to false, away to true)
        assertEquals(
            "the first entry with a measured true must win",
            away,
            effectiveServerUrl(settings(listOf(home, road, away)), answers),
        )
        assertEquals("every entry before the winner must be asked", listOf(home, road, away), asked)
    }
}
