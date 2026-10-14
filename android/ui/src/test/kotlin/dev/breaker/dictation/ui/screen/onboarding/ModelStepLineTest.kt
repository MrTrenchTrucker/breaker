package dev.breaker.dictation.ui.screen.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The model step's sentence makes two promises to the user: the download does not use their
 * server, and the file is checked before it is used. Every other test compares against the
 * constant itself, so only this one pins the words; dropping a promise or changing where the
 * file comes from fails here. The {size} hole is filled when the step is drawn.
 */
class ModelStepLineTest {
    @Test
    fun `the model step sentence says where the file comes from and both promises, word for word`() {
        assertEquals(
            "ui: the model step sentence must keep its exact words",
            "Downloads about {size} MB from GitHub, where the makers of the speech library publish it. " +
                "Your server is not used. The file is checked before it is used.",
            SetupTexts.MODEL_STEP_LINE,
        )
    }
}
