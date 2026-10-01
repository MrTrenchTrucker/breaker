package dev.breaker.shared.tokens

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fixed dimensions, the band boundaries, and the small helpers of the token set. */
class TokenMetricsTest {
    private val metrics = TruckingTokens.metrics

    @Test
    fun cornerRadiusIsAtMost4() {
        assertTrue("hard edges: corner radius ${metrics.cornerRadiusDp} exceeds 4", metrics.cornerRadiusDp <= 4)
    }

    @Test
    fun touchTargetIsAtLeast48() {
        assertTrue("touch target ${metrics.minTouchTargetDp} is below 48", metrics.minTouchTargetDp >= 48)
    }

    @Test
    fun breakpointsAreExactly640And1024() {
        assertEquals(640, metrics.mobileBreakpointDp)
        assertEquals(1024, metrics.tabletBreakpointDp)
    }

    @Test
    fun bandsChangeExactlyAtTheBreakpoints() {
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(0))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(639))
        assertEquals(LayoutBand.TABLET, metrics.bandFor(640))
        assertEquals(LayoutBand.TABLET, metrics.bandFor(1024))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(1025))
    }

    @Test
    fun bandsFollowTheBreakpointsOfTheMetricsTheyAreAskedOn() {
        // Not the shipped numbers, so a band function that hard-codes them is seen.
        val custom = TruckingMetrics(cornerRadiusDp = 4, minTouchTargetDp = 48, mobileBreakpointDp = 100, tabletBreakpointDp = 200)
        assertEquals(LayoutBand.MOBILE, custom.bandFor(99))
        assertEquals(LayoutBand.TABLET, custom.bandFor(100))
        assertEquals(LayoutBand.TABLET, custom.bandFor(200))
        assertEquals(LayoutBand.DESKTOP, custom.bandFor(201))
    }

    @Test
    fun bandsAreDefinedAtTheExtremes() {
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(-1))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(Int.MIN_VALUE))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(Int.MAX_VALUE))
    }

    @Test
    fun breakpointsMustBeInOrder() {
        assertThrows(IllegalArgumentException::class.java) {
            TruckingMetrics(cornerRadiusDp = 4, minTouchTargetDp = 48, mobileBreakpointDp = 1024, tabletBreakpointDp = 640)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TruckingMetrics(cornerRadiusDp = 4, minTouchTargetDp = 48, mobileBreakpointDp = 640, tabletBreakpointDp = 640)
        }
    }

    @Test
    fun ledBarRangeIs12To16() {
        assertEquals(12, TruckingTokens.ledBar.minSegments)
        assertEquals(16, TruckingTokens.ledBar.maxSegments)
        assertEquals(12..16, TruckingTokens.ledBar.segmentRange)
    }

    @Test
    fun theSegmentRangeFollowsTheFieldsOfTheBarItIsAskedOn() {
        assertEquals(3..7, TruckingLedBar(minSegments = 3, maxSegments = 7).segmentRange)
    }

    @Test
    fun anInvertedLedRangeIsRefused() {
        val e = assertThrows(IllegalArgumentException::class.java) { TruckingLedBar(minSegments = 5, maxSegments = 4) }
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("above its maximum"))
    }

    @Test
    fun aLedRangeBelowOneSegmentIsRefused() {
        for ((min, max) in listOf(0 to 5, -1 to 3, 0 to 0, -3 to -1)) {
            val e = assertThrows(IllegalArgumentException::class.java) { TruckingLedBar(minSegments = min, maxSegments = max) }
            assertTrue("($min, $max): ${e.message}", e.message.orEmpty().contains("below 1"))
        }
    }

    @Test
    fun aSingleSegmentRangeIsAccepted() {
        assertEquals(1..1, TruckingLedBar(minSegments = 1, maxSegments = 1).segmentRange)
        assertEquals(12..12, TruckingLedBar(minSegments = 12, maxSegments = 12).segmentRange)
    }

    @Test
    fun theTokenObjectInitialisesOnFirstAccess() {
        // Reading any member must not throw while the object is being built.
        assertNotNull(TruckingTokens.LIGHT)
        assertNotNull(TruckingTokens.DARK)
        assertNotNull(TruckingTokens.type)
        assertNotNull(TruckingTokens.metrics)
        assertNotNull(TruckingTokens.ledBar)
    }

    @Test
    fun aTranslucentColorIsRefused() {
        for (argb in listOf(0x801E7A46.toInt(), 0x00FFFFFF, 0xFE1E7A46.toInt(), 0x011E7A46)) {
            val e = assertThrows(IllegalArgumentException::class.java) { TokenColor(argb) }
            assertTrue("$argb: ${e.message}", e.message.orEmpty().contains("opaque"))
        }
    }

    @Test
    fun hexRendersRrggbbWithoutAlpha() {
        assertEquals("#1E7A46", TokenColor(0xFF1E7A46.toInt()).hex)
        assertEquals("#000000", TokenColor(0xFF000000.toInt()).hex)
        assertEquals("#000001", TokenColor(0xFF000001.toInt()).hex)
        assertEquals("#FFFFFF", TokenColor(0xFFFFFFFF.toInt()).hex)
        assertEquals("TokenColor(#1E7A46)", TokenColor(0xFF1E7A46.toInt()).toString())
    }

    @Test
    fun paletteSelectsByModeAndToggleFlipsIt() {
        assertSame(TruckingTokens.LIGHT, TruckingTokens.palette(ThemeMode.LIGHT))
        assertSame(TruckingTokens.DARK, TruckingTokens.palette(ThemeMode.DARK))
        assertNotSame(TruckingTokens.LIGHT, TruckingTokens.DARK)
        assertEquals(ThemeMode.DARK, TruckingTokens.toggled(ThemeMode.LIGHT))
        assertEquals(ThemeMode.LIGHT, TruckingTokens.toggled(ThemeMode.DARK))
    }

    private fun instanceFields(type: Class<*>): Set<String> =
        type.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    @Test
    fun theTokenTypesDeclareExactlyTheFieldsTheCardGivesThem() {
        assertEquals(
            setOf("bg", "surface", "text", "textMuted", "primary", "primaryHover", "accent", "danger", "trim", "sent", "warning"),
            instanceFields(TruckingPalette::class.java),
        )
        assertEquals(
            setOf("cornerRadiusDp", "minTouchTargetDp", "mobileBreakpointDp", "tabletBreakpointDp"),
            instanceFields(TruckingMetrics::class.java),
        )
        assertEquals(
            setOf("displayFamily", "displayAlternateFamily", "bodyFamily", "monoFamily"),
            instanceFields(TruckingType::class.java),
        )
        assertEquals(setOf("minSegments", "maxSegments"), instanceFields(TruckingLedBar::class.java))
    }

    @Test
    fun theTokenObjectHoldsExactlyTheTokenGroups() {
        val held = TruckingTokens::class.java.declaredFields
            .filter { !it.isSynthetic && it.name != "INSTANCE" }
            .map { it.name }
            .toSet()
        assertEquals(setOf("LIGHT", "DARK", "type", "metrics", "ledBar"), held)
    }

    @Test
    fun theFileDeclaresNoLooseTopLevelValues() {
        // A top-level val or fun would compile into a TokensKt class.
        try {
            Class.forName("dev.breaker.shared.tokens.TokensKt")
            fail("tokens.kt declares a top-level function or property; the tokens live in named types")
        } catch (expected: ClassNotFoundException) {
            // none, as it should be
        }
    }
}
