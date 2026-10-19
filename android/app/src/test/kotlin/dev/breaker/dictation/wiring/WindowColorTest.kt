package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [windowColorFor], which maps a theme mode to the correct window
 * background colour from the Trucking palettes.
 *
 * DARK always returns the dark palette background; LIGHT always returns the
 * light palette background; SYSTEM follows the phone's night mode.
 */
internal class WindowColorTest {

    @Test
    fun `DARK returns the dark palette background`() {
        assertEquals(
            "DARK mode should return the dark palette bg",
            TruckingTokens.DARK.bg.argb,
            windowColorFor(ThemeMode.DARK, systemIsNight = false),
        )
    }

    @Test
    fun `DARK returns dark even when system is in night mode`() {
        assertEquals(
            "DARK mode should return the dark palette bg regardless of system night",
            TruckingTokens.DARK.bg.argb,
            windowColorFor(ThemeMode.DARK, systemIsNight = true),
        )
    }

    @Test
    fun `LIGHT returns the light palette background`() {
        assertEquals(
            "LIGHT mode should return the light palette bg",
            TruckingTokens.LIGHT.bg.argb,
            windowColorFor(ThemeMode.LIGHT, systemIsNight = false),
        )
    }

    @Test
    fun `LIGHT returns light even when system is in night mode`() {
        assertEquals(
            "LIGHT mode should return the light palette bg regardless of system night",
            TruckingTokens.LIGHT.bg.argb,
            windowColorFor(ThemeMode.LIGHT, systemIsNight = true),
        )
    }

    @Test
    fun `SYSTEM with night returns the dark palette background`() {
        assertEquals(
            "SYSTEM mode in night should return the dark palette bg",
            TruckingTokens.DARK.bg.argb,
            windowColorFor(ThemeMode.SYSTEM, systemIsNight = true),
        )
    }

    @Test
    fun `SYSTEM with day returns the light palette background`() {
        assertEquals(
            "SYSTEM mode in day should return the light palette bg",
            TruckingTokens.LIGHT.bg.argb,
            windowColorFor(ThemeMode.SYSTEM, systemIsNight = false),
        )
    }

    @Test
    fun `dark and light backgrounds are different`() {
        val dark = windowColorFor(ThemeMode.DARK, systemIsNight = false)
        val light = windowColorFor(ThemeMode.LIGHT, systemIsNight = false)
        assertEquals(
            "dark and light bg should differ",
            true,
            dark != light,
        )
    }

    @Test
    fun `SYSTEM night matches DARK and SYSTEM day matches LIGHT`() {
        val systemNight = windowColorFor(ThemeMode.SYSTEM, systemIsNight = true)
        val dark = windowColorFor(ThemeMode.DARK, systemIsNight = false)
        assertEquals("SYSTEM night should equal DARK", dark, systemNight)

        val systemDay = windowColorFor(ThemeMode.SYSTEM, systemIsNight = false)
        val light = windowColorFor(ThemeMode.LIGHT, systemIsNight = false)
        assertEquals("SYSTEM day should equal LIGHT", light, systemDay)
    }
}
