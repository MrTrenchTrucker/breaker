package dev.breaker.dictation.ui.screen.home

import dev.breaker.dictation.ui.screen.flatten
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the notice line on the home screen: it renders when the state carries
 * a notice and is absent when it does not.
 *
 * Anchored to `PublicSurfaceGateTest`: both test the ui module's home screen
 * surface, this one the drawn notice.
 */
internal class HomeScreenNoticeTest {

    private val screen = HomeScreen()

    @Test
    fun `the notice renders as a node when the state carries one`() {
        val rendered = screen.render(HomeState(notice = "Not available yet."))
        val noticeNode = rendered.nodes.flatMap { it.flatten() }.find { it.id == HomeNodeIds.NOTICE }
        assertTrue("a notice node must be drawn when state.notice is non-null", noticeNode != null)
    }

    @Test
    fun `no notice node is drawn when the state has none`() {
        val rendered = screen.render(HomeState())
        val noticeNode = rendered.nodes.flatMap { it.flatten() }.find { it.id == HomeNodeIds.NOTICE }
        assertNull("no notice node must be drawn when state.notice is null", noticeNode)
    }

    @Test
    fun `the notice node sits between the transcript and the stripes`() {
        val rendered = screen.render(HomeState(notice = "Not available yet."))
        val ids = rendered.nodes.map { it.id }
        val transcriptIndex = ids.indexOf(HomeNodeIds.TRANSCRIPT)
        val noticeIndex = ids.indexOf(HomeNodeIds.NOTICE)
        val stripeIndex = ids.indexOf("${HomeNodeIds.STRIPES}.1")
        assertTrue("notice must come after the transcript", noticeIndex > transcriptIndex)
        assertTrue("notice must come before the stripes", noticeIndex < stripeIndex)
    }
}
