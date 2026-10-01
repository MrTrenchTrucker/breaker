package dev.breaker.shared.tokens

import kotlin.math.nextDown
import kotlin.math.nextUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band a width falls into when the width is fractional, and the pin on the band names.
 *
 * A width is compared exactly: below 640 is mobile, 640 through 1024 inclusive is
 * tablet, above 1024 is desktop. No rounding direction gives that at both edges
 * (rounding up puts 639.5 in tablet, rounding down puts 1024.5 in tablet), so the
 * widths that show it are tested for every overload. The numbers are written out
 * here, not read from the metrics, so a band function that compares against the wrong
 * number is seen.
 */
class TokenBandTest {
    private val metrics = TruckingTokens.metrics

    private fun expected(width: Double): LayoutBand = when {
        width < 640.0 -> LayoutBand.MOBILE
        width <= 1024.0 -> LayoutBand.TABLET
        else -> LayoutBand.DESKTOP
    }

    @Test
    fun aFractionalWidthBelowTheMobileBreakpointIsMobile() {
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(639.5))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(640.0.nextDown()))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(639.5f))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(640f.nextDown()))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(0.5))
    }

    @Test
    fun theBreakpointsThemselvesAreTablet() {
        for (width in listOf(640.0, 640.5, 1023.5, 1024.0)) {
            assertEquals("$width as a Double", LayoutBand.TABLET, metrics.bandFor(width))
            assertEquals("$width as a Float", LayoutBand.TABLET, metrics.bandFor(width.toFloat()))
        }
    }

    @Test
    fun aFractionalWidthAboveTheTabletBreakpointIsDesktop() {
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(1024.5))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(1024.0.nextUp()))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(1024.5f))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(1024f.nextUp()))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(1.0e9))
    }

    @Test
    fun fractionalWidthsFollowTheBreakpointsOfTheMetricsTheyAreAskedOn() {
        // Not the shipped numbers, so a fractional path that hard-codes them is seen.
        val custom = TruckingMetrics(cornerRadiusDp = 4, minTouchTargetDp = 48, mobileBreakpointDp = 100, tabletBreakpointDp = 200)
        assertEquals(LayoutBand.MOBILE, custom.bandFor(99.5))
        assertEquals(LayoutBand.TABLET, custom.bandFor(100.0))
        assertEquals(LayoutBand.TABLET, custom.bandFor(199.5f))
        assertEquals(LayoutBand.TABLET, custom.bandFor(200.0))
        assertEquals(LayoutBand.DESKTOP, custom.bandFor(200.5))
        assertEquals(LayoutBand.DESKTOP, custom.bandFor(200.5f))
    }

    @Test
    fun negativeAndInfiniteWidthsAreDefined() {
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(-0.5))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(-0.5f))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(Double.NEGATIVE_INFINITY))
        assertEquals(LayoutBand.MOBILE, metrics.bandFor(Float.NEGATIVE_INFINITY))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(Double.POSITIVE_INFINITY))
        assertEquals(LayoutBand.DESKTOP, metrics.bandFor(Float.POSITIVE_INFINITY))
    }

    @Test
    fun aWidthThatIsNotANumberIsRefusedAndNamed() {
        val double = assertThrows(IllegalArgumentException::class.java) { metrics.bandFor(Double.NaN) }
        assertTrue(double.message.orEmpty(), double.message.orEmpty().contains("NaN"))
        assertTrue(double.message.orEmpty(), double.message.orEmpty().contains("not a number"))
        val float = assertThrows(IllegalArgumentException::class.java) { metrics.bandFor(Float.NaN) }
        assertTrue(float.message.orEmpty(), float.message.orEmpty().contains("NaN"))
        assertTrue(float.message.orEmpty(), float.message.orEmpty().contains("not a number"))
    }

    @Test
    fun everyOverloadAgreesOnWholeWidths() {
        for (width in -1..1100) {
            val want = expected(width.toDouble())
            assertEquals("$width as an Int", want, metrics.bandFor(width))
            assertEquals("$width as a Float", want, metrics.bandFor(width.toFloat()))
            assertEquals("$width as a Double", want, metrics.bandFor(width.toDouble()))
        }
    }

    @Test
    fun everyOverloadAgreesOnQuarterDpSteps() {
        // Quarter steps are exact in a Float, so the two fractional overloads must agree.
        for (step in -4..4400) {
            val width = step / 4.0
            val want = expected(width)
            assertEquals("$width as a Double", want, metrics.bandFor(width))
            assertEquals("$width as a Float", want, metrics.bandFor(width.toFloat()))
        }
    }

    @Test
    fun theLayoutBandsArePinnedToTheCardsThree() {
        // Written out, so an entry added, renamed, reordered or removed has to be changed here too.
        assertEquals(listOf("MOBILE", "TABLET", "DESKTOP"), LayoutBand.entries.map { it.name })
    }
}
