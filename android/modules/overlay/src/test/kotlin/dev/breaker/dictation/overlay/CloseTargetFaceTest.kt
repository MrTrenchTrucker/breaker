package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The face of the close target box: six colours, every one taken from the palette it is built from. */
class CloseTargetFaceTest {

    private val dark = CloseTargetFace.from(TruckingTokens.DARK)
    private val light = CloseTargetFace.from(TruckingTokens.LIGHT)

    /** A failure here means one of the six colours of the dark face differs from the value the rule gives. */
    @Test
    fun `the dark face takes its six colours from the dark palette`() {
        assertEquals("overlay: the fill is the dark surface lightened", 0xFF2A2E31.toInt(), dark.fill)
        assertEquals("overlay: the edge is the dark muted text", 0xFF9AA5A0.toInt(), dark.edge)
        assertEquals("overlay: the active edge is the dark danger colour", 0xFFE74C3C.toInt(), dark.edgeActive)
        assertEquals("overlay: the truck is the dark text darkened", 0xFFE6E9E6.toInt(), dark.truck)
        assertEquals("overlay: the load is the dark danger colour", 0xFFE74C3C.toInt(), dark.load)
        assertEquals("overlay: the active load is the danger colour lightened", 0xFFE9594A.toInt(), dark.loadActive)
    }

    /** A failure here means the light palette gives other values than the same rule gives it. */
    @Test
    fun `the light face takes the same rule from the light palette`() {
        assertEquals("overlay: the light fill is the light surface lightened", 0xFFF5F7F5.toInt(), light.fill)
        assertEquals("overlay: the light edge is the light muted text", 0xFF5A6368.toInt(), light.edge)
        assertEquals("overlay: the light active edge is the light danger colour", 0xFFC0392B.toInt(), light.edgeActive)
        assertEquals("overlay: the light truck is the light text darkened", 0xFF101316.toInt(), light.truck)
        assertEquals("overlay: the light load is the light danger colour", 0xFFC0392B.toInt(), light.load)
        assertEquals("overlay: the light active load is the danger colour lightened", 0xFFC4473A.toInt(), light.loadActive)
    }

    /** A failure here means a colour of either face has an alpha byte below 255. */
    @Test
    fun `every colour of both faces is opaque`() {
        listOf(dark, light).forEach { face ->
            listOf(face.fill, face.edge, face.edgeActive, face.truck, face.load, face.loadActive).forEach { colour ->
                assertEquals("overlay: every colour of the close target face must be opaque", 0xFF, colour ushr 24)
            }
        }
    }

    /** A failure here means the fill is the surface itself, or the active load is the same colour as the load. */
    @Test
    fun `the fill is not the surface and the active load differs from the load`() {
        assertNotEquals("overlay: the fill must not be the surface itself", TruckingTokens.DARK.surface.argb, dark.fill)
        assertNotEquals("overlay: the dark active load must differ from the load", dark.load, dark.loadActive)
        assertNotEquals("overlay: the light active load must differ from the load", light.load, light.loadActive)
    }

    /** A failure here means the truck is not lighter than the box fill in the dark palette. */
    @Test
    fun `the truck is lighter than the fill in the dark palette`() {
        assertTrue(
            "overlay: the truck must be lighter than the box fill",
            relativeLuminance(dark.truck) > relativeLuminance(dark.fill),
        )
    }
}
