package dev.breaker.dictation.ui.screen.firstrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture names: the exact text of each name for both themes, the pages that have none, the logo
 * names, the list of every name and the shape a resource name must have. The names are written out
 * here, apart from the code, so a renamed picture fails here.
 */
class FirstRunPicturesTest {
    @Test
    fun `the permission pictures have their exact names for both themes`() {
        assertEquals("ui: first run: the microphone light name is wrong", "breaker_perm_mic_light", FirstRunPictures.nameFor(FirstRunPage.MICROPHONE, dark = false))
        assertEquals("ui: first run: the microphone dark name is wrong", "breaker_perm_mic_dark", FirstRunPictures.nameFor(FirstRunPage.MICROPHONE, dark = true))
        assertEquals("ui: first run: the overlay light name is wrong", "breaker_perm_overlay_light", FirstRunPictures.nameFor(FirstRunPage.OVERLAY, dark = false))
        assertEquals("ui: first run: the overlay dark name is wrong", "breaker_perm_overlay_dark", FirstRunPictures.nameFor(FirstRunPage.OVERLAY, dark = true))
        assertEquals("ui: first run: the notification light name is wrong", "breaker_perm_notify_light", FirstRunPictures.nameFor(FirstRunPage.NOTIFICATIONS, dark = false))
        assertEquals("ui: first run: the notification dark name is wrong", "breaker_perm_notify_dark", FirstRunPictures.nameFor(FirstRunPage.NOTIFICATIONS, dark = true))
        assertEquals("ui: first run: the accessibility light name is wrong", "breaker_perm_access_light", FirstRunPictures.nameFor(FirstRunPage.ACCESSIBILITY, dark = false))
        assertEquals("ui: first run: the accessibility dark name is wrong", "breaker_perm_access_dark", FirstRunPictures.nameFor(FirstRunPage.ACCESSIBILITY, dark = true))
    }

    @Test
    fun `the welcome and the download page have no permission picture`() {
        for (dark in listOf(false, true)) {
            assertNull("ui: first run: the welcome page has a permission picture", FirstRunPictures.nameFor(FirstRunPage.WELCOME, dark))
            assertNull("ui: first run: the download page has a permission picture", FirstRunPictures.nameFor(FirstRunPage.DOWNLOAD, dark))
        }
    }

    @Test
    fun `the logo names are exact for both themes`() {
        assertEquals("ui: first run: the light logo name is wrong", "breaker_logo_light", FirstRunPictures.logoName(dark = false))
        assertEquals("ui: first run: the dark logo name is wrong", "breaker_logo_dark", FirstRunPictures.logoName(dark = true))
    }

    @Test
    fun `ALL has exactly ten names in the design order and no duplicates`() {
        assertEquals(
            "ui: first run: the picture list is not the ten names in design order",
            listOf(
                "breaker_perm_mic_light", "breaker_perm_mic_dark",
                "breaker_perm_overlay_light", "breaker_perm_overlay_dark",
                "breaker_perm_notify_light", "breaker_perm_notify_dark",
                "breaker_perm_access_light", "breaker_perm_access_dark",
                "breaker_logo_light", "breaker_logo_dark",
            ),
            FirstRunPictures.ALL,
        )
        assertEquals("ui: first run: the picture list has a duplicate", 10, FirstRunPictures.ALL.toSet().size)
    }

    @Test
    fun `every name is lower case letters, digits and underscores only`() {
        val valid = Regex("[a-z0-9_]+")
        val bad = FirstRunPictures.ALL.filterNot { valid.matches(it) }
        assertEquals("ui: first run: picture names that are not resource names: $bad", emptyList<String>(), bad)
    }

    @Test
    fun `every picture a page asks for is listed in ALL`() {
        val asked = FirstRunPage.entries.flatMap { page ->
            listOf(false, true).mapNotNull { dark -> FirstRunPictures.nameFor(page, dark) }
        }
        assertTrue("ui: first run: a page asks for a picture that ALL does not list", FirstRunPictures.ALL.containsAll(asked))
    }
}
