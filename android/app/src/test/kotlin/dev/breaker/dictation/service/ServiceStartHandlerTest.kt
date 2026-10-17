package dev.breaker.dictation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the start handling of the microphone service on a plain JVM: the real controller, a recording
 * host in place of the platform, and small stand-ins for the permission and the launcher.
 * Every case checks the first thing done (the foreground), what follows, and the state of the switch.
 */
internal class ServiceStartHandlerTest {

    /** Answers a set permission. */
    private class StubPermission(var granted: Boolean) : MicPermission {
        override fun isRecordAudioGranted(): Boolean = granted
    }

    /** Launches without a platform and counts its calls. */
    private class StubLauncher : ServiceLauncher {
        var launches: Int = 0
        var halts: Int = 0

        override fun launch(): LaunchResult {
            launches += 1
            return LaunchResult.Launched
        }

        override fun halt() {
            halts += 1
        }
    }

    /** An [IllegalStateException] subclass, the way the platform refuses a start that is not allowed. */
    private class StartNotAllowed(message: String) : IllegalStateException(message)

    /** Keeps the calls in order, notes whether the switch was on when the foreground was entered, and can refuse. */
    private class HostRecorder(private val switchIsOn: () -> Boolean) : ServiceHost {
        val calls: MutableList<String> = ArrayList()
        var switchOnAtEnter: Boolean? = null
        var refusal: RuntimeException? = null

        override fun enterForeground() {
            calls.add("enterForeground")
            switchOnAtEnter = switchIsOn()
            refusal?.let { throw it }
        }

        override fun leaveForegroundAndStop() {
            calls.add("leaveForegroundAndStop")
        }

        override fun stopWithoutForeground() {
            calls.add("stopWithoutForeground")
        }
    }

    /** One service with the switch on or off and the permission granted or not at the time of the start. */
    private class Rig(armed: Boolean, granted: Boolean) {
        val permission = StubPermission(true)
        val launcher = StubLauncher()
        val controller = DictationServiceController(permission, launcher)
        val host = HostRecorder { controller.isArmed }
        val handler = ServiceStartHandler(controller, host)

        init {
            if (armed) {
                check(controller.arm() == StartResult.Started) { "app: the test rig could not switch the service on" }
            }
            permission.granted = granted
        }
    }

    private fun assertStart(
        label: String,
        action: String?,
        armed: Boolean,
        granted: Boolean,
        calls: List<String>,
        armedAfter: Boolean,
        halts: Int,
    ) {
        val rig = Rig(armed, granted)
        rig.handler.onStart(action)
        assertEquals("app: $label: the foreground must be the first call and the rest as listed", calls, rig.host.calls)
        assertEquals("app: $label: the switch was read before the foreground was entered", armed, rig.host.switchOnAtEnter)
        assertEquals("app: $label: the switch is wrong after the start", armedAfter, rig.controller.isArmed)
        assertEquals("app: $label: the launcher was halted a wrong number of times", halts, rig.launcher.halts)
        assertEquals("app: $label: a start request must not launch the service", if (armed) 1 else 0, rig.launcher.launches)
    }

    private val enter = "enterForeground"
    private val leave = "leaveForegroundAndStop"
    private val stopOnly = "stopWithoutForeground"

    @Test
    fun `switch-on while on stays on and does nothing else`() =
        assertStart("ARM on", ACTION_ARM, armed = true, granted = true, calls = listOf(enter), armedAfter = true, halts = 0)

    @Test
    fun `switch-on while on stays on even when the permission has gone`() =
        assertStart("ARM on, no permission", ACTION_ARM, armed = true, granted = false, calls = listOf(enter), armedAfter = true, halts = 0)

    @Test
    fun `switch-on from the notification while off with permission marks the switch on and stays`() =
        assertStart("ARM off, permission", ACTION_ARM, armed = false, granted = true, calls = listOf(enter), armedAfter = true, halts = 0)

    @Test
    fun `switch-on while off without permission leaves the foreground and stops`() =
        assertStart("ARM off, no permission", ACTION_ARM, armed = false, granted = false, calls = listOf(enter, leave), armedAfter = false, halts = 0)

    @Test
    fun `switch-off while on switches the service off, halts once, then leaves the foreground and stops`() =
        assertStart("DISARM on", ACTION_DISARM, armed = true, granted = true, calls = listOf(enter, leave), armedAfter = false, halts = 1)

    @Test
    fun `switch-off while off halts nothing and leaves the foreground and stops`() =
        assertStart("DISARM off", ACTION_DISARM, armed = false, granted = true, calls = listOf(enter, leave), armedAfter = false, halts = 0)

    @Test
    fun `an unknown action while on stays on`() =
        assertStart("UNKNOWN on", "some.other.action", armed = true, granted = true, calls = listOf(enter), armedAfter = true, halts = 0)

    @Test
    fun `an unknown action while off leaves the foreground and stops`() =
        assertStart("UNKNOWN off", "some.other.action", armed = false, granted = true, calls = listOf(enter, leave), armedAfter = false, halts = 0)

    @Test
    fun `no action while on stays on`() =
        assertStart("null on", null, armed = true, granted = true, calls = listOf(enter), armedAfter = true, halts = 0)

    @Test
    fun `no action while off leaves the foreground and stops`() =
        assertStart("null off", null, armed = false, granted = true, calls = listOf(enter, leave), armedAfter = false, halts = 0)

    @Test
    fun `an action that only looks like a switch-on is unknown`() =
        assertStart("near miss", ACTION_ARM.uppercase(), armed = false, granted = true, calls = listOf(enter, leave), armedAfter = false, halts = 0)

    private fun assertRefused(label: String, error: RuntimeException) {
        for (action in listOf(ACTION_ARM, ACTION_DISARM, "some.other.action", null)) {
            for (armed in listOf(true, false)) {
                val rig = Rig(armed, granted = true)
                rig.host.refusal = error
                val context = "$label, action $action, on $armed"
                try {
                    rig.handler.onStart(action)
                } catch (escaped: Throwable) {
                    throw AssertionError("app: $context: a refused foreground must not escape the start handling", escaped)
                }
                assertEquals("app: $context: after a refusal only the foreground try and the plain stop happen", listOf(enter, stopOnly), rig.host.calls)
                assertFalse("app: $context: a refused foreground must leave the switch off", rig.controller.isArmed)
                assertEquals("app: $context: ending by itself must not halt the launcher", 0, rig.launcher.halts)
                assertEquals("app: $context: a refusal must not launch anything", if (armed) 1 else 0, rig.launcher.launches)
            }
        }
    }

    @Test
    fun `a refused foreground with a RuntimeException ends the service quietly and switches off`() =
        assertRefused("RuntimeException", RuntimeException("refused"))

    @Test
    fun `a refused foreground with a SecurityException ends the service quietly and switches off`() =
        assertRefused("SecurityException", SecurityException("microphone not allowed"))

    @Test
    fun `a refused foreground with a start-not-allowed IllegalStateException ends the service quietly and switches off`() =
        assertRefused("IllegalStateException subclass", StartNotAllowed("start not allowed"))

    @Test
    fun `a refusal turns an armed switch off`() {
        val rig = Rig(armed = true, granted = true)
        assertTrue("app: the rig must start with the switch on", rig.controller.isArmed)
        rig.host.refusal = SecurityException("no")
        rig.handler.onStart(ACTION_ARM)
        assertFalse("app: a refused foreground must turn the switch off", rig.controller.isArmed)
    }
}
