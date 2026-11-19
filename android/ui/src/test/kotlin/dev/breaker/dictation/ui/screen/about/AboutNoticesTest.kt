package dev.breaker.dictation.ui.screen.about

import dev.breaker.dictation.ui.screen.Action
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.TypeRole
import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutNoticesTest {

    private val ownerText = "This app includes the Parakeet TDT 0.6B v3 model by NVIDIA Corporation (https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). Model weights were converted/quantized from the original."

    private val screen = AboutScreen(listOf(ownerText))

    @Test
    fun `the notice text reaches the screen unchanged`() {
        assertEquals(ownerText, screen.render().nodes.filterIsInstance<Label>().single { it.id == AboutNodeIds.NOTICES }.text)
    }

    @Test
    fun `the notices node is a body label in muted colour`() {
        val label = screen.render().nodes.filterIsInstance<Label>().single { it.id == AboutNodeIds.NOTICES }
        assertEquals(ownerText, label.text)
        assertEquals(TypeRole.BODY, label.role)
        assertEquals(PaletteSlot.TEXT_MUTED, label.color)
    }

    @Test
    fun `the notices node is present in the admin tree as a label and not an action`() {
        val nodes = screen.render(AboutState(admin = true, updateAvailable = true, installReady = true)).nodes
        assertEquals(1, nodes.filterIsInstance<Label>().count { it.id == AboutNodeIds.NOTICES })
        assertTrue(nodes.filterIsInstance<Action>().none { it.id == AboutNodeIds.NOTICES })
    }

    @Test
    fun `the notices node comes right after the second line`() {
        val ids = screen.render().nodes.map { it.id }
        assertEquals(ids.indexOf(AboutNodeIds.LINE_2) + 1, ids.indexOf(AboutNodeIds.NOTICES))
    }

    @Test
    fun `the notices node comes right after the second line and before the admin rows`() {
        val ids = screen.render(AboutState(admin = true, updateAvailable = true, installReady = true)).nodes.map { it.id }
        assertEquals(ids.indexOf(AboutNodeIds.LINE_2) + 1, ids.indexOf(AboutNodeIds.NOTICES))
        assertTrue(ids.indexOf(AboutNodeIds.NOTICES) < ids.indexOf(AboutNodeIds.CHECK))
    }
}
