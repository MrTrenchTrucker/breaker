package dev.breaker.dictation.wiring

import dev.breaker.dictation.service.ServiceSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A switch-on from the launcher icon that comes back refused is handed to the activity's sink with the
 * refusal sentence, once per start. The rule stays open so the next icon start tries again. Only an answer of
 * On or AlreadyOn leaves the rule done, and a sink that throws must not escape the start.
 */
internal class IconLaunchRefusedTest {

    @Test
    fun `a refused icon switch on hands its sentence to the sink once and leaves the rule open`() {
        val once = IconLaunchOnce(false)
        val sentences: MutableList<String> = ArrayList()
        var switchOns = 0
        once.onStart(
            true,
            {
                switchOns++
                SwitchOn.Refused(ServiceSentences.ARM_REFUSED)
            },
            { },
            { sentence -> sentences.add(sentence) },
        )
        assertEquals("app: the refused switch-on must be asked once", 1, switchOns)
        assertEquals("app: the sink must get the refusal sentence once", listOf(ServiceSentences.ARM_REFUSED), sentences)
        assertFalse("app: a refused switch-on must leave the rule open", once.isDone)
    }

    @Test
    fun `the next icon start after a refusal tries the switch on again`() {
        val once = IconLaunchOnce(false)
        val sentences: MutableList<String> = ArrayList()
        var switchOns = 0
        repeat(2) {
            once.onStart(
                true,
                {
                    switchOns++
                    SwitchOn.Refused(ServiceSentences.ARM_REFUSED)
                },
                { },
                { sentence -> sentences.add(sentence) },
            )
        }
        assertEquals("app: each icon start after a refusal must ask again", 2, switchOns)
        assertEquals("app: each refusal must reach the sink", 2, sentences.size)
    }

    @Test
    fun `an answer of on or already on never reaches the sink`() {
        for (answer in listOf<SwitchOn>(SwitchOn.On, SwitchOn.AlreadyOn)) {
            val once = IconLaunchOnce(false)
            val sentences: MutableList<String> = ArrayList()
            once.onStart(true, { answer }, { }, { sentence -> sentences.add(sentence) })
            assertEquals("app: $answer must not reach the sink", emptyList<String>(), sentences)
            assertTrue("app: $answer must mark the rule done", once.isDone)
        }
    }

    @Test
    fun `a start that is not an icon launch never asks for the switch on or reaches the sink`() {
        val once = IconLaunchOnce(false)
        val sentences: MutableList<String> = ArrayList()
        var switchOns = 0
        var arms = 0
        once.onStart(
            false,
            {
                switchOns++
                SwitchOn.Refused(ServiceSentences.ARM_REFUSED)
            },
            { arms++ },
            { sentence -> sentences.add(sentence) },
        )
        assertEquals("app: a plain start must not ask for the switch on", 0, switchOns)
        assertEquals("app: a plain start must not reach the sink", emptyList<String>(), sentences)
        assertEquals("app: a plain start must arm at start", 1, arms)
    }

    @Test
    fun `a sink that throws does not escape the start and the rule stays open`() {
        val once = IconLaunchOnce(false)
        once.onStart(
            true,
            { SwitchOn.Refused(ServiceSentences.ARM_REFUSED) },
            { },
            { throw UnsupportedOperationException("the sink failed") },
        )
        assertFalse("app: a throwing sink must leave the rule open", once.isDone)
    }
}
