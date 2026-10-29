package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.controls.ControlPart
import dev.breaker.dictation.ui.controls.ControlsPalette
import dev.breaker.dictation.ui.screen.KitLook
import dev.breaker.dictation.ui.screen.onboarding.OpenAction
import dev.breaker.dictation.ui.screen.onboarding.PlatformStatus
import dev.breaker.dictation.ui.screen.onboarding.SetupIntentHandler
import dev.breaker.dictation.ui.screen.onboarding.SetupScreen
import dev.breaker.dictation.ui.screen.onboarding.SetupState
import dev.breaker.dictation.ui.screen.onboarding.SetupTexts
import dev.breaker.dictation.ui.testing.FakeBreakerSwitch
import dev.breaker.dictation.ui.testing.FakeSetupPlatform
import dev.breaker.dictation.ui.theme.PaletteSlot
import dev.breaker.dictation.ui.theme.Themes
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/*
 * The look the Next button takes, driven by the current grant: grey while the page's own step is
 * missing, green once it is granted. This tests FirstRunNext (ready and look) directly, and walks
 * a real controller over a fake platform to show the look follows the grant as it is re-read on
 * resume. The views are not run here; the colour test reads the two face parts through the palette.
 */
class FirstRunNextTest {

    /** A page as drawn when [grantOwn] says whether the page's own step is granted. */
    private fun shownFor(page: FirstRunPage, grantOwn: Boolean): FirstRunShown {
        val allGranted = PlatformStatus(
            overlay = true,
            microphone = true,
            notifications = true,
            accessibility = true,
            sdkInt = 36,
        )
        val status = when (page) {
            FirstRunPage.MICROPHONE -> allGranted.copy(microphone = grantOwn)
            FirstRunPage.OVERLAY -> allGranted.copy(overlay = grantOwn)
            FirstRunPage.NOTIFICATIONS -> allGranted.copy(notifications = grantOwn)
            FirstRunPage.ACCESSIBILITY, FirstRunPage.KEYBOARD_TILE -> allGranted.copy(accessibility = grantOwn)
            else -> allGranted
        }
        val state = SetupState(
            status = status,
            switchedOn = false,
            micAskedBefore = false,
            notificationsAskedBefore = false,
        )
        return FirstRunShown(
            page = page,
            step = FirstRunPages.stepOf(page),
            state = state,
            notice = null,
            missing = FirstRunMissing.of(state),
            download = DownloadShown(DownloadPhase.CHECKING, 0, 0, null),
        )
    }

    @Test
    fun `next is ready only when the pages own step is granted`() {
        val pages = listOf(
            FirstRunPage.MICROPHONE,
            FirstRunPage.OVERLAY,
            FirstRunPage.NOTIFICATIONS,
            FirstRunPage.ACCESSIBILITY,
            FirstRunPage.KEYBOARD_TILE,
        )
        for (page in pages) {
            assertEquals(
                "first run: a page that still misses its own step is not ready",
                false,
                FirstRunNext.ready(shownFor(page, grantOwn = false)),
            )
            assertEquals(
                "first run: a page with its own step granted is ready",
                true,
                FirstRunNext.ready(shownFor(page, grantOwn = true)),
            )
        }
        // a missing item of another page does not change the answer for this page
        val otherMissing = shownFor(FirstRunPage.MICROPHONE, grantOwn = true).copy(
            missing = listOf(MissingItem(FirstRunPage.OVERLAY, OpenAction.OVERLAY_PAGE)),
        )
        assertEquals(
            "first run: another page's missing step does not affect this page",
            true,
            FirstRunNext.ready(otherMissing),
        )
    }

    @Test
    fun `next turns from grey to green when a grant is seen on resume and back when it is taken away`() {
        val platform = FakeSetupPlatform(
            PlatformStatus(
                overlay = true,
                microphone = false,
                notifications = true,
                accessibility = true,
                sdkInt = 36,
            ),
        )
        val handler = SetupIntentHandler(platform, FakeBreakerSwitch(false), { false }, { null }, { 0 }, { }, SetupScreen())
        val controller = FirstRunController(
            handler = handler,
            modelReady = { false },
            downloadLine = { null },
            onDownloadModel = { },
            onFinished = { },
        )
        // start on a permission page with the microphone step still missing
        controller.advance()
        assertEquals(
            "first run: the missing look is neutral",
            KitLook.NEUTRAL,
            FirstRunNext.look(controller.shown()),
        )
        // the grant is seen on resume: the phone's answer becomes granted, then a refresh (what resume does)
        platform.answer = PlatformStatus(
            overlay = true,
            microphone = true,
            notifications = true,
            accessibility = true,
            sdkInt = 36,
        )
        controller.refresh()
        assertEquals(
            "first run: the granted look is raised green",
            KitLook.RAISED,
            FirstRunNext.look(controller.shown()),
        )
        // the grant is taken away again; one more refresh makes it neutral once more
        platform.answer = PlatformStatus(
            overlay = true,
            microphone = false,
            notifications = true,
            accessibility = true,
            sdkInt = 36,
        )
        controller.refresh()
        assertEquals(
            "first run: a lost look is neutral again",
            KitLook.NEUTRAL,
            FirstRunNext.look(controller.shown()),
        )
    }

    @Test
    fun `next is grey when missing and green when granted in the light and the dark mode`() {
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            val theme = Themes.of(mode)
            val neutralFace = ControlsPalette.partColor(ControlPart.FACE_SURFACE, theme)
            val raisedFace = ControlsPalette.partColor(ControlPart.FACE, theme)
            assertEquals(
                "first run: the $mode neutral face is the surface fill",
                theme.color(PaletteSlot.SURFACE).argb,
                neutralFace,
            )
            assertNotEquals(
                "first run: the $mode neutral and raised faces differ",
                neutralFace,
                raisedFace,
            )
        }
    }

    /** A page as drawn for the given platform answers, with no notice and no download under way. */
    private fun shownWith(page: FirstRunPage, status: PlatformStatus): FirstRunShown {
        val state = SetupState(
            status = status,
            switchedOn = false,
            micAskedBefore = false,
            notificationsAskedBefore = false,
        )
        return FirstRunShown(
            page = page,
            step = FirstRunPages.stepOf(page),
            state = state,
            notice = null,
            missing = FirstRunMissing.of(state),
            download = DownloadShown(DownloadPhase.CHECKING, 0, 0, null),
        )
    }

    @Test
    fun `every page is ready exactly when the grant it names is given, for every combination of answers`() {
        val named = mapOf(
            "WELCOME" to "",
            "MICROPHONE" to "microphone",
            "OVERLAY" to "overlay",
            "NOTIFICATIONS" to "notifications",
            "ACCESSIBILITY" to "accessibility",
            "KEYBOARD_TILE" to "accessibility",
            "DOWNLOAD" to "",
        )
        assertEquals(
            "ui: first run: the pages with an expected readiness are not the pages of the first run",
            named.keys,
            FirstRunPage.entries.map { it.name }.toSet(),
        )
        for (bits in 0 until 16) {
            val status = PlatformStatus(
                overlay = (bits and 1) != 0,
                microphone = (bits and 2) != 0,
                notifications = (bits and 4) != 0,
                accessibility = (bits and 8) != 0,
                sdkInt = 36,
            )
            val given = mapOf(
                "overlay" to status.overlay,
                "microphone" to status.microphone,
                "notifications" to status.notifications,
                "accessibility" to status.accessibility,
            )
            for (page in FirstRunPage.entries) {
                val grant = named.getValue(page.name)
                val expected = grant.isEmpty() || given.getValue(grant)
                assertEquals(
                    "ui: first run: readiness of ${page.name} with answers $bits",
                    expected,
                    FirstRunNext.ready(shownWith(page, status)),
                )
            }
        }
    }

    @Test
    fun `the keyboard tile page shows not done and the grey Next while accessibility is off, and done with the raised Next once it is on`() {
        val off = shownWith(
            FirstRunPage.KEYBOARD_TILE,
            PlatformStatus(overlay = true, microphone = true, notifications = true, accessibility = false, sdkInt = 36),
        )
        assertEquals(
            "ui: first run: the keyboard tile page with accessibility off does not show the not-granted word",
            SetupTexts.STATUS_NOT_GRANTED,
            FirstRunWords.status(FirstRunNext.ready(off)),
        )
        assertEquals("ui: first run: the keyboard tile Next is not grey with accessibility off", KitLook.NEUTRAL, FirstRunNext.look(off))
        val on = shownWith(
            FirstRunPage.KEYBOARD_TILE,
            PlatformStatus(overlay = true, microphone = true, notifications = true, accessibility = true, sdkInt = 36),
        )
        assertEquals(
            "ui: first run: the keyboard tile page with accessibility on does not show the granted word",
            SetupTexts.STATUS_GRANTED,
            FirstRunWords.status(FirstRunNext.ready(on)),
        )
        assertEquals("ui: first run: the keyboard tile Next is not raised with accessibility on", KitLook.RAISED, FirstRunNext.look(on))
    }
}
