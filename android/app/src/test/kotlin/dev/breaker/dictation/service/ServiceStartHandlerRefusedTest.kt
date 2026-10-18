package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused foreground promotion inside the service. The refusal goes to the sink once, with the existing
 * arm-refused sentence, before the switch is marked off and the service stops. A sink that throws changes
 * none of the rest. The platform is a host that keeps its calls in order, and the switch is the real
 * controller over stand-ins for the permission and the launcher.
 */
internal class ServiceStartHandlerRefusedTest {

    /** Answers that the microphone is granted. */
    private class StubPermission : MicPermission {
        override fun isRecordAudioGranted(): Boolean = true
    }

    /** Launches without a platform. */
    private class StubLauncher : ServiceLauncher {
        override fun launch(): LaunchResult = LaunchResult.Launched

        override fun halt() {
        }
    }

    /** Keeps the platform calls in order and refuses the foreground while [refuse] is set. */
    private class RefusingHost(private val events: MutableList<String>, var refuse: Boolean) : ServiceHost {
        override fun enterForeground() {
            events.add("enter")
            if (refuse) throw IllegalStateException("refused by the platform")
        }

        override fun leaveForegroundAndStop() {
            events.add("leave")
        }

        override fun stopWithoutForeground() {
            events.add("stop")
        }
    }

    /** A switch that is on, with its state changes and the sink's calls written into one list. */
    private class Rig(sinkFails: Boolean) {
        val events: MutableList<String> = ArrayList()
        val sentences: MutableList<String> = ArrayList()
        val controller = DictationServiceController(StubPermission(), StubLauncher())
        val host = RefusingHost(events, refuse = true)
        val handler: ServiceStartHandler

        init {
            controller.setStateObserver { armed, _ -> events.add("observer:$armed") }
            check(controller.arm() == StartResult.Started) { "app: the test rig could not switch the service on" }
            events.clear()
            handler = ServiceStartHandler(controller, host, { sentence ->
                sentences.add(sentence)
                events.add("sink")
                if (sinkFails) throw UnsupportedOperationException("the sink failed")
            })
        }
    }

    @Test
    fun `a refused foreground gives the arm refused sentence to the sink once before the switch goes off`() {
        val rig = Rig(sinkFails = false)
        rig.handler.onStart(null)
        assertEquals("app: the sink must get the arm refused sentence once", listOf(ServiceSentences.ARM_REFUSED), rig.sentences)
        assertEquals(
            "app: the order must be the foreground, the sink, the switch off, then the stop",
            listOf("enter", "sink", "observer:false", "stop"),
            rig.events,
        )
        assertFalse("app: the switch must end off", rig.controller.isArmed)
    }

    @Test
    fun `an accepted foreground never gives anything to the sink`() {
        val rig = Rig(sinkFails = false)
        rig.host.refuse = false
        rig.handler.onStart(null)
        assertEquals("app: an accepted foreground must not reach the sink", emptyList<String>(), rig.sentences)
        assertTrue("app: the foreground must be the first call", rig.events.first() == "enter")
    }

    @Test
    fun `a sink that throws still leaves the switch off and the service stopped once`() {
        val rig = Rig(sinkFails = true)
        rig.handler.onStart(null)
        assertEquals("app: the sink must have been called", listOf(ServiceSentences.ARM_REFUSED), rig.sentences)
        assertFalse("app: the switch must end off even when the sink throws", rig.controller.isArmed)
        assertEquals("app: the service must stop once after a throwing sink", 1, rig.events.count { it == "stop" })
    }
}
