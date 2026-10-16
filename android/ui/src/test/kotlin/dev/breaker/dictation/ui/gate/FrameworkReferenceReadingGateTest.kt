package dev.breaker.dictation.ui.gate

import org.junit.Assert.assertEquals
import org.junit.Test

/*
 * How a framework name is read out of text or bytes, guarded on its own.
 *
 * The reading is settled once, in AndroidConfinementGateTest, and the
 * confinement rule depends on it: a framework name read where there is none, or
 * a name that is not read where there is one, would let a reference through or
 * flag a carve-out that is real. These tests are the controls for that reading,
 * each a piece of text the rule must read one way and not another. They are
 * split from AndroidConfinementGateTest, which holds the enforcement of this
 * module's own carve-out, so that the check stays inside its size cap.
 */

class FrameworkReferenceReadingGateTest {

    @Test
    fun `an import of a framework class is caught`() {
        val control = "package dev.breaker.dictation.ui.theme\n\nimport android.util.Log\n"
        assertEquals(listOf("android."), frameworkNamesIn(control))
    }

    @Test
    fun `a framework class named in full is caught with no import anywhere`() {
        val control = "package dev.breaker.dictation.ui.theme\n\nval shade = android.graphics.Color.RED\n"
        assertEquals(listOf("android."), frameworkNamesIn(control))
    }

    @Test
    fun `a support library reference is caught on the same rule`() {
        val control = "package dev.breaker.dictation.ui.render\n\nimport androidx.core.view.ViewCompat\n"
        assertEquals(listOf("androidx."), frameworkNamesIn(control))
    }

    @Test
    fun `a framework name in a comment or a string is prose or data, not a reference`() {
        val control = """
            package dev.breaker.dictation.ui.theme

            // the renderer imports android.view.View; nothing here does
            val note = "android.util.Log"
        """.trimIndent()
        assertEquals(emptyList<String>(), frameworkNamesIn(control))
    }

    @Test
    fun `a framework package in a class file is caught`() {
        val control = "\u0000android/view/View\u0000"
        assertEquals(listOf("android/"), frameworkPackagesIn(control))
    }

    @Test
    fun `a class of this module's own package is not mistaken for a framework class`() {
        val control = "dev/breaker/dictation/ui/render/ScreenRenderer"
        assertEquals(emptyList<String>(), frameworkPackagesIn(control))
    }
}
